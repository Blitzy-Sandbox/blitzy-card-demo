package com.carddemo.batch;

import com.carddemo.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import java.util.Map;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Chunk-step {@link org.springframework.batch.item.ItemReader ItemReader} for the CardDemo
 * combine-transactions stage &mdash; reader&nbsp;#3 of 5 in the batch pipeline, wired into
 * {@code CombineTransactionJob}.
 *
 * <p><strong>COBOL/DFSORT lineage (reference-only, source SHA {@code 27d6c6f}).</strong> This
 * reader is the Java translation of the sort step of the legacy {@code COMBTRAN.jcl} job (see also
 * the shared {@code REPROC.prc} REPRO procedure). {@code COMBTRAN} is a two-step DFSORT-then-REPRO
 * job that combines the posted (backup) transactions with the system-generated (interest)
 * transactions, orders the merged set ascending by the transaction id, and reloads the transaction
 * master VSAM KSDS:</p>
 *
 * <pre>
 *   //STEP05R  EXEC PGM=SORT
 *   //SORTIN   DD DISP=SHR,DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(0)   (posted transactions)
 *   //         DD DISP=SHR,DSN=AWS.M2.CARDDEMO.SYSTRAN(0)         (system/interest transactions)
 *   //SYMNAMES DD *
 *   TRAN-ID,1,16,CH
 *   //SYSIN    DD *
 *    SORT FIELDS=(TRAN-ID,A)                                      (ascending by TRAN-ID)
 *   //SORTOUT  DD ... DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)
 *
 *   //STEP10   EXEC PGM=IDCAMS
 *   //SYSIN    DD *
 *    REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)                     (load TRANSACT.VSAM.KSDS)
 * </pre>
 *
 * <p><strong>Migration mapping (AAP &sect;0.4.3 Comparator strategy, &sect;0.8.5 batch-pipeline
 * rules).</strong> In the Java model the posted and interest transactions have already been
 * persisted into the single {@code transaction} table, so the "combine" is an <em>ordered
 * re-materialization</em> of that table rather than a physical merge of two datasets:</p>
 * <ul>
 *   <li>The DFSORT ascending key {@code SORT FIELDS=(TRAN-ID,A)} ({@code TRAN-ID}, positions
 *       1&ndash;16, format {@code CH}, direction {@code A}) is realized here as an
 *       {@code ORDER BY tran_id ASC} pushed into the database via the configured
 *       {@link org.springframework.data.domain.Sort Sort} &mdash; the idiomatic, memory-bounded
 *       equivalent of a pre-sorted DFSORT input. The whole table is deliberately <em>not</em>
 *       loaded into memory to be sorted; {@link RepositoryItemReader} pages through it
 *       {@code carddemo.batch.chunk-size} rows at a time.</li>
 *   <li>The subsequent {@code IDCAMS REPRO} reload is realized by the step's writer (Spring Batch's
 *       built-in {@code RepositoryItemWriter&lt;Transaction&gt;}); the intervening
 *       {@link CombineTransactionProcessor} is an identity pass-through. This reader therefore
 *       supplies the ordered stream that drives byte-equivalent parity with the legacy REPRO load
 *       (the KSDS is keyed by {@code TRAN-ID}, so key order equals physical load order).</li>
 * </ul>
 *
 * <p><strong>Ordering parity with {@link TransactionIdComparator}.</strong> The reader ordering
 * MUST agree with the sibling {@link TransactionIdComparator}, which encodes the same DFSORT key
 * for any explicit in-memory sort/merge and for the bidirectional traceability matrix
 * (AAP &sect;0.7.3): ascending {@code tranId} with {@code null} ids ordered last. Because
 * {@code TRAN-ID} is the non-nullable primary key of the {@code transaction} table
 * ({@link Transaction#getTranId()}), no {@code null} keys occur in practice, so the database
 * {@code ORDER BY tran_id ASC} and {@code TransactionIdComparator}'s {@code nullsLast(naturalOrder)}
 * produce the identical total order over well-formed data.</p>
 *
 * <p><strong>End-of-input contract.</strong> Exhaustion is signalled by
 * {@link RepositoryItemReader#read() read()} returning {@code null}; this reader never raises an
 * exception to mark end-of-input, preserving the COBOL {@code FILE STATUS '10'} (EOF) &rarr; normal
 * loop-termination semantics (AAP &sect;0.8.3).</p>
 *
 * <p><strong>Configuration.</strong> All state is established in the constructor via the inherited
 * setters; because {@link RepositoryItemReader} implements
 * {@link org.springframework.beans.factory.InitializingBean InitializingBean}, Spring invokes
 * {@code afterPropertiesSet()} after construction to assert that the repository, sort, page size and
 * method name are all present. The distinct {@link org.springframework.batch.item.ItemStreamSupport
 * name} {@code "combineTransactionItemReader"} scopes this reader's paging cursor within the Spring
 * Batch {@code ExecutionContext}, keeping restart state isolated from the other pipeline readers.</p>
 *
 * <p>The class is declared {@code final}: it is a leaf batch component and, being non-extensible,
 * avoids the {@code -Xlint:this-escape} warning that a subclassable type would otherwise incur by
 * invoking inherited (overridable) configuration setters from its constructor &mdash; satisfying the
 * zero-warning build gate (Gate 2). It holds no financial fields, so no {@code float}/{@code double}
 * concerns apply.</p>
 *
 * @see TransactionIdComparator
 * @see CombineTransactionProcessor
 * @see TransactionRepository
 * @see Transaction#getTranId()
 * @see RepositoryItemReader
 */
@Component
public final class CombineTransactionItemReader extends RepositoryItemReader<Transaction> {

    /**
     * Name of the entity property that carries the 16-character transaction id
     * ({@code TRAN-ID}) and defines the ascending sort key, mirroring the
     * {@code COMBTRAN.jcl SORT FIELDS=(TRAN-ID,A)} DFSORT field.
     */
    private static final String SORT_PROPERTY_TRAN_ID = "tranId";

    /**
     * Spring Batch stream name for this reader, used to key its paging/restart state in the
     * step {@code ExecutionContext}.
     */
    private static final String READER_NAME = "combineTransactionItemReader";

    /**
     * Repository method invoked to page through the transaction master. Resolves to the inherited
     * {@code PagingAndSortingRepository.findAll(Pageable)} on {@link TransactionRepository}, which
     * returns a {@link org.springframework.data.domain.Page Page} (a
     * {@link org.springframework.data.domain.Slice Slice}) that {@link RepositoryItemReader}
     * consumes page by page.
     */
    private static final String FIND_ALL_METHOD = "findAll";

    /**
     * Constructs the combine-stage reader, configuring the inherited {@link RepositoryItemReader}
     * to stream every {@link Transaction} ordered ascending by {@code tranId} in bounded pages.
     *
     * <p>The {@code carddemo.batch.chunk-size} page size aligns the reader's fetch size with the
     * chunk size of the surrounding Spring Batch step; it defaults to {@code 100} when the property
     * is not present in the active profile's configuration.</p>
     *
     * @param transactionRepository the Spring Data JPA repository for the migrated transaction
     *                               master ({@code TRANSACT} VSAM KSDS, copybook {@code CVTRA05Y});
     *                               injected by constructor injection, must not be {@code null}
     * @param chunkSize              the page size for the underlying
     *                               {@link org.springframework.data.domain.Pageable Pageable}
     *                               reads, bound from {@code carddemo.batch.chunk-size} (default
     *                               {@code 100}); must be a positive integer
     */
    public CombineTransactionItemReader(
            TransactionRepository transactionRepository,
            @Value("${carddemo.batch.chunk-size:100}") int chunkSize) {
        super();
        // Source of records: the migrated transaction master. RepositoryItemReader accepts any
        // PagingAndSortingRepository; TransactionRepository (a JpaRepository) qualifies.
        setRepository(transactionRepository);
        // Page through the repository using the inherited findAll(Pageable) finder. The reader
        // appends a PageRequest (built from the sort + page size below) as the trailing argument
        // and consumes the returned Page/Slice content.
        setMethodName(FIND_ALL_METHOD);
        // DFSORT SORT FIELDS=(TRAN-ID,A): ORDER BY tran_id ASC, pushed into the database so the
        // ordering matches TransactionIdComparator (ascending tranId) without in-memory sorting.
        setSort(Map.of(SORT_PROPERTY_TRAN_ID, Sort.Direction.ASC));
        // Memory-bounded paging aligned with the step chunk size.
        setPageSize(chunkSize);
        // Distinct stream name so paging/restart state is isolated in the ExecutionContext.
        setName(READER_NAME);
    }
}
