package com.carddemo.batch;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.carddemo.entity.Transaction;
import com.carddemo.repository.TransactionRepository;

/**
 * Chunk-step {@link RepositoryItemReader} for the CardDemo transaction-detail report — reader
 * <strong>#5 of 5</strong> in the batch pipeline, wired into {@code TransactionReportJob}. It streams
 * {@link Transaction} entities out of the {@link TransactionRepository} in a stable, deterministic
 * order so the downstream processor and writer can reproduce the legacy report exactly.
 *
 * <h2>COBOL lineage (reference-only, source SHA {@code 27d6c6f})</h2>
 * <p>In the legacy flow the {@code TRANREPT} job ({@code app/jcl/TRANREPT.jcl},
 * {@code app/proc/TRANREPT.prc}) runs a DFSORT pre-step that does two things before
 * {@code CBTRN03C} ever reads the file:</p>
 * <ol>
 *   <li><strong>Sort</strong> the processed-transaction file ascending by card number —
 *       {@code SYMNAMES TRAN-CARD-NUM,263,16,ZD} with {@code SORT FIELDS=(TRAN-CARD-NUM,A)}. This
 *       ordering is what lets {@code CBTRN03C} group its output per card and emit account totals on
 *       each card-number break (the COBOL {@code IF WS-CURR-CARD-NUM NOT = TRAN-CARD-NUM} logic).</li>
 *   <li><strong>Filter</strong> to a processing-date window —
 *       {@code SYMNAMES TRAN-PROC-DT,305,10,CH} with
 *       {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)},
 *       where {@code PARM-START-DATE = C'2022-01-01'} and {@code PARM-END-DATE = C'2022-07-06'}.</li>
 * </ol>
 * <p>{@code CBTRN03C}'s {@code 1000-TRANFILE-GET-NEXT} paragraph then performs a plain sequential
 * {@code READ TRANSACT-FILE}, treating {@code FILE STATUS '10'} (end-of-file) as normal loop
 * termination ({@code MOVE 'Y' TO END-OF-FILE}) and only abending on other, unexpected statuses.</p>
 *
 * <h2>What this reader reproduces</h2>
 * <p>This reader is responsible for the DFSORT <em>ordering</em> only. It is configured against the
 * repository's inherited {@code findAll(Pageable)} method
 * ({@link #TransactionReportItemReader(TransactionRepository, int) see constructor}) with an
 * <strong>ordered</strong> sort:</p>
 * <ul>
 *   <li>{@code tranCardNum} ascending — the DFSORT key {@code TRAN-CARD-NUM,A}; and</li>
 *   <li>{@code tranId} ascending — a deterministic tie-breaker on the entity's primary key.</li>
 * </ul>
 * <p>The tie-breaker matters because the reader pages through the result set: without a total order,
 * rows that share a card number could be returned in different relative positions across page reads
 * (and across a restart), producing a non-repeatable stream. Pairing the DFSORT key with the unique
 * {@code tranId} guarantees a total order, which makes the paged read <strong>deterministic and
 * restartable</strong> — the reader is named ({@code "transactionReportItemReader"}) so Spring Batch
 * persists its read cursor in the step {@code ExecutionContext} and resumes correctly after a
 * failure, mirroring the rerun semantics of the JCL job.</p>
 *
 * <h2>End-of-input contract</h2>
 * <p>On exhaustion this reader returns {@code null} (the standard Spring Batch end-of-input signal),
 * never an exception — the idiomatic equivalent of the COBOL {@code FILE STATUS '10'} EOF handling in
 * {@code 1000-TRANFILE-GET-NEXT}. The {@code null}-on-EOF behaviour is inherited unchanged from
 * {@link RepositoryItemReader#doRead()}.</p>
 *
 * <h2>Date-window filter lives in the processor, not here (AAP &sect;0.8.5)</h2>
 * <p>The DFSORT {@code INCLUDE COND} date window is deliberately <em>not</em> applied in this reader.
 * Its idiomatic Spring Batch equivalent is a processor that returns {@code null} to exclude a row, so
 * the window is realized in {@code TransactionReportProcessor}. Keeping the filter out of the reader
 * lets this class remain a simple ordered source over the fixed {@link TransactionRepository}
 * contract (which is owned by the {@code repository} package and must not be widened with a range
 * query here). The window bounds are supplied to the processor as job parameters
 * ({@code startDate} / {@code endDate}, propagated by {@code ReportJobLauncher} from the SQS FIFO
 * message) with fallback to the {@code carddemo.batch.report.start-date} /
 * {@code carddemo.batch.report.end-date} properties (defaults {@code 2022-01-01} / {@code 2022-07-06},
 * from the JCL {@code PARM-START-DATE} / {@code PARM-END-DATE} symbols). Because {@code TRAN-PROC-DT}
 * is the first ten characters ({@code YYYY-MM-DD}) of {@link Transaction#getTranProcTs()}, the
 * processor's lexicographic {@code >= start AND <= end} comparison on that substring reproduces the
 * COBOL {@code INCLUDE COND} exactly. A JDBC range reader is a documented future optimization
 * (decision-log / next-task candidate) and is out of scope here.</p>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>The class is {@code final}: it is a concrete, fully-configured pipeline component that is not
 *       intended to be subclassed. Declaring it {@code final} also keeps the configuration performed
 *       in the constructor free of any {@code this}-escape concern under {@code -Xlint:all}
 *       (Gate&nbsp;2, zero-warning build).</li>
 *   <li>Collaborators are supplied by <strong>constructor injection</strong> only; the reader holds no
 *       mutable state of its own beyond the paging cursor managed by the superclass.</li>
 * </ul>
 *
 * @see TransactionRepository
 * @see Transaction
 * @see RepositoryItemReader
 */
@Component
public final class TransactionReportItemReader extends RepositoryItemReader<Transaction> {

    /** Unique reader name used to key this reader's paging state in the step {@code ExecutionContext}. */
    private static final String READER_NAME = "transactionReportItemReader";

    /**
     * Repository method invoked to page through the transaction master. The inherited
     * {@code JpaRepository.findAll(Pageable)} is used; {@link RepositoryItemReader} appends the
     * {@link org.springframework.data.domain.PageRequest} (page index, page size and sort) as the
     * trailing argument on each page read.
     */
    private static final String FIND_ALL_METHOD = "findAll";

    /**
     * Builds a fully-configured reader over the supplied {@link TransactionRepository}.
     *
     * <p>The reader is wired to the repository's inherited {@code findAll(Pageable)} method with an
     * ordered sort of {@code tranCardNum} ascending (the DFSORT {@code TRAN-CARD-NUM,A} key) then
     * {@code tranId} ascending (deterministic tie-breaker). A {@link LinkedHashMap} is used so the
     * primary sort field is honoured before the tie-breaker; the map is built explicitly in the
     * constructor body (rather than with a double-brace initializer) to keep the build warning-free
     * under {@code -Xlint:all}.</p>
     *
     * @param transactionRepository the transaction repository to page over; injected by Spring, must
     *                              not be {@code null}
     * @param chunkSize             the page size, bound from {@code carddemo.batch.chunk-size}
     *                              (default {@code 100}); this is aligned with the pipeline chunk size
     *                              so each read fetches exactly one chunk's worth of rows
     */
    public TransactionReportItemReader(
            TransactionRepository transactionRepository,
            @Value("${carddemo.batch.chunk-size:100}") int chunkSize) {

        super();

        // The transaction master is paged through with the inherited findAll(Pageable); the
        // repository is a PagingAndSortingRepository (via JpaRepository), which RepositoryItemReader
        // requires.
        setRepository(transactionRepository);
        setMethodName(FIND_ALL_METHOD);

        // Ordered sort: primary DFSORT key (TRAN-CARD-NUM ascending) then a unique tie-breaker
        // (tranId ascending). The insertion order of a LinkedHashMap is preserved, so the primary
        // key is applied first, yielding a total order that makes paged reads deterministic and
        // restartable.
        Map<String, Sort.Direction> sort = new LinkedHashMap<>();
        sort.put("tranCardNum", Sort.Direction.ASC);
        sort.put("tranId", Sort.Direction.ASC);
        setSort(sort);

        // Page size == pipeline chunk size so each page read maps to one chunk transaction.
        setPageSize(chunkSize);

        // Named so paging state is persisted in the ExecutionContext (restartability).
        setName(READER_NAME);
    }
}
