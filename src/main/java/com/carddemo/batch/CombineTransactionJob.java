package com.carddemo.batch;

import com.carddemo.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch {@link Configuration} for pipeline stage&nbsp;3 &mdash; the <strong>combine /
 * sort</strong> stage (job/step config&nbsp;#3 of&nbsp;5). It wires the chunk-oriented
 * {@code combineTransactionStep} and the standalone {@code combineTransactionJob}, reproducing the
 * legacy {@code COMBTRAN} mainframe job (DFSORT-by-{@code TRAN-ID}-ascending followed by an
 * {@code IDCAMS REPRO} load of the transaction master).
 *
 * <h2>COBOL/JCL lineage (reference-only, source commit SHA {@code 27d6c6f})</h2>
 * <p>The source artifacts are <strong>not</strong> copied into this repository; they are referenced
 * by commit SHA for traceability only (see {@code docs/traceability-matrix.md}). The migrated job is
 * a one-to-one translation of {@code app/jcl/COMBTRAN.jcl} (which reuses the shared
 * {@code app/proc/REPROC.prc} REPRO procedure):</p>
 *
 * <pre>
 *   //STEP05R  EXEC PGM=SORT
 *   //SORTIN   DD DISP=SHR,DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(0)   (posted transactions)
 *   //         DD DISP=SHR,DSN=AWS.M2.CARDDEMO.SYSTRAN(0)         (system/interest transactions)
 *   //SYMNAMES DD *
 *   TRAN-ID,1,16,CH
 *   //SYSIN    DD *
 *    SORT FIELDS=(TRAN-ID,A)                                      (ascending by 16-char TRAN-ID)
 *   //SORTOUT  DD ... DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)
 *
 *   //STEP10   EXEC PGM=IDCAMS
 *   //SYSIN    DD *
 *    REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)                     (load TRANSACT.VSAM.KSDS)
 * </pre>
 *
 * <h2>Chunk-step realization (AAP &sect;0.4.3 Comparator strategy, &sect;0.8.5 batch-pipeline rules)</h2>
 * <p>In the Java model the posted and system-generated transactions have already been persisted into
 * the single {@code transaction} table, so the legacy "combine" becomes an <em>ordered
 * re-materialization</em> of that table rather than a physical merge of two datasets. The three
 * responsibilities of {@code COMBTRAN} are split across the standard Spring Batch
 * {@code reader &rarr; processor &rarr; writer} chunk shape:</p>
 * <ul>
 *   <li><strong>Sort ({@code STEP05R})</strong> &mdash; the DFSORT ascending key
 *       {@code SORT FIELDS=(TRAN-ID,A)} is realized by {@link CombineTransactionItemReader}, whose
 *       query orders rows {@code ORDER BY tranId ASC}. That ordering is the exact semantic encoded by
 *       {@link TransactionIdComparator} ({@code TRAN-ID}, positions&nbsp;1&ndash;16, format {@code CH},
 *       direction {@code A}); the comparator is retained as the traceable, self-documenting anchor for
 *       the sort key even though the ordering is pushed into the database rather than performed
 *       in&nbsp;memory.</li>
 *   <li><strong>Pass-through</strong> &mdash; {@link CombineTransactionProcessor} is an intentional
 *       identity transform: the sort lives in the reader and the load lives in the writer, so there is
 *       no record-level work to do. It never returns {@code null} (which would filter the item out of
 *       the chunk and break parity).</li>
 *   <li><strong>REPRO load ({@code STEP10})</strong> &mdash; the {@code IDCAMS REPRO} reload of the
 *       transaction master is realized by {@link #combineTransactionWriter(TransactionRepository)}, a
 *       built-in {@link RepositoryItemWriter} that re-persists each item in the order received. The
 *       KSDS is keyed by {@code TRAN-ID}, so key order equals physical load order and the writer
 *       preserves the ascending stream the reader supplies.</li>
 * </ul>
 *
 * <h2>Duplicate handling</h2>
 * <p>{@code TRAN-ID} is the unique primary key of the {@code transaction} KSDS
 * ({@link Transaction#getTranId()}), so no two records share a key in well-formed input and there is
 * <strong>no duplicate collapse</strong>: the writer re-persists exactly the set the reader supplies,
 * one row per {@code tranId}. Because {@code RepositoryItemWriter} invokes the JPA {@code save}
 * (merge) method keyed by {@code tranId}, the step is <strong>idempotent</strong> &mdash; re-running it
 * yields the same table state, matching the rerun semantics of the legacy {@code REPRO} load
 * (AAP&nbsp;&sect;0.8.5).</p>
 *
 * <h2>Wiring notes</h2>
 * <ul>
 *   <li>This configuration deliberately does <strong>not</strong> declare
 *       {@code @EnableBatchProcessing}; the {@link JobRepository} and
 *       {@link PlatformTransactionManager} are the Spring Boot auto-configured beans, injected by
 *       constructor.</li>
 *   <li>The job registers {@link BatchCorrelationIdListener} (Observability rule, AAP&nbsp;&sect;0.7.1)
 *       so every log line emitted during the job carries the MDC {@code correlationId}, and a
 *       {@link RunIdIncrementer} so each launch is a distinct, re-runnable {@code JobInstance} (the JCL
 *       rerun analogue).</li>
 *   <li>The chunk size is externalized from the JCL {@code SYSIN}/PARM model to the Spring property
 *       {@code carddemo.batch.chunk-size} (default {@code 100}), and doubles as the reader page size in
 *       {@link CombineTransactionItemReader}.</li>
 *   <li>The bean methods reference one another as sibling calls (the job starts the step; the step uses
 *       the writer), which relies on the CGLIB proxying of a full {@code @Configuration}
 *       ({@code proxyBeanMethods = true}, the default) to return the managed singletons.</li>
 * </ul>
 *
 * <p><strong>Bean naming.</strong> The configuration class is explicitly named
 * {@code "combineTransactionJobConfig"} so that the class's own component bean name does not collide
 * with the {@code combineTransactionJob} {@link Job} bean it exposes (the decapitalized class name and
 * that bean name would otherwise be identical, which Spring Boot rejects under
 * {@code spring.main.allow-bean-definition-overriding = false}). The three exposed bean names
 * &mdash; {@code combineTransactionWriter}, {@code combineTransactionStep} and
 * {@code combineTransactionJob} &mdash; are contractually fixed by the AAP file specification.</p>
 *
 * <p>This class is pure wiring: it holds no business logic, performs no arithmetic, and defines no
 * monetary fields, so no {@code float}/{@code double} decimal-precision concerns apply
 * (AAP&nbsp;&sect;0.8.2).</p>
 *
 * @see CombineTransactionItemReader
 * @see CombineTransactionProcessor
 * @see TransactionIdComparator
 * @see BatchCorrelationIdListener
 * @see TransactionRepository
 * @see RepositoryItemWriter
 */
@Configuration("combineTransactionJobConfig")
public class CombineTransactionJob {

    /** Spring Boot auto-configured Spring Batch job repository, backing the job and step meta-data. */
    private final JobRepository jobRepository;

    /** Spring Boot auto-configured transaction manager bounding each chunk's read/commit window. */
    private final PlatformTransactionManager transactionManager;

    /** Listener that seeds the per-execution MDC correlation id; registered on the combine job. */
    private final BatchCorrelationIdListener batchCorrelationIdListener;

    /** Reader supplying transactions ordered ascending by {@code tranId} (the DFSORT key). */
    private final CombineTransactionItemReader combineTransactionItemReader;

    /** Identity (pass-through) processor preserving the reader's ordering for the REPRO-load writer. */
    private final CombineTransactionProcessor combineTransactionProcessor;

    /** Repository backing the {@link RepositoryItemWriter} that re-persists the ordered transactions. */
    private final TransactionRepository transactionRepository;

    /**
     * Chunk size and commit interval, externalized from the JCL {@code SYSIN}/PARM model to the
     * Spring property {@code carddemo.batch.chunk-size}. Defaults to {@code 100} when the property is
     * not supplied by the active profile.
     */
    private final int chunkSize;

    /**
     * Creates the combine-stage configuration with all collaborators injected by constructor (the
     * single constructor is auto-detected by Spring, so no {@code @Autowired} is required).
     *
     * @param jobRepository                the auto-configured Spring Batch job repository
     * @param transactionManager           the auto-configured platform transaction manager
     * @param batchCorrelationIdListener   the correlation-id listener registered on the combine job
     * @param combineTransactionItemReader the reader streaming transactions ascending by {@code tranId}
     * @param combineTransactionProcessor  the identity pass-through processor for the combine stage
     * @param transactionRepository        the transaction repository backing the REPRO-load writer
     * @param chunkSize                    the chunk size / commit interval
     *                                     ({@code carddemo.batch.chunk-size}, default {@code 100})
     */
    CombineTransactionJob(final JobRepository jobRepository,
                          final PlatformTransactionManager transactionManager,
                          final BatchCorrelationIdListener batchCorrelationIdListener,
                          final CombineTransactionItemReader combineTransactionItemReader,
                          final CombineTransactionProcessor combineTransactionProcessor,
                          final TransactionRepository transactionRepository,
                          @Value("${carddemo.batch.chunk-size:100}") final int chunkSize) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.batchCorrelationIdListener = batchCorrelationIdListener;
        this.combineTransactionItemReader = combineTransactionItemReader;
        this.combineTransactionProcessor = combineTransactionProcessor;
        this.transactionRepository = transactionRepository;
        this.chunkSize = chunkSize;
    }

    /**
     * The combine-stage writer &mdash; the {@code IDCAMS REPRO} load analogue ({@code COMBTRAN}
     * {@code STEP10}). It re-persists each {@link Transaction} in the order received via the JPA
     * {@code save} (merge) method, reloading the transaction master. Because {@code save} merges by
     * the {@code tranId} primary key, the operation is idempotent: re-running the step yields the same
     * table state, and no duplicate collapse occurs (keys are unique).
     *
     * <p>This is Spring Batch's built-in {@link RepositoryItemWriter} rather than a bespoke writer
     * class, keeping the documented component budget intact (the three custom writers are reserved for
     * the posting, statement, and report stages).</p>
     *
     * @param repo the transaction repository whose {@code save} method performs the REPRO-load merge;
     *             injected by type (the sole {@link TransactionRepository} bean)
     * @return a {@link RepositoryItemWriter} typed to {@link Transaction}, invoking {@code save} once
     *         per item
     */
    @Bean
    ItemWriter<Transaction> combineTransactionWriter(final TransactionRepository repo) {
        final RepositoryItemWriter<Transaction> writer = new RepositoryItemWriter<>();
        writer.setRepository(repo);
        writer.setMethodName("save");
        return writer;
    }

    /**
     * The combine-stage chunk step: reads transactions ascending by {@code tranId}
     * ({@link CombineTransactionItemReader}), passes them through unchanged
     * ({@link CombineTransactionProcessor}), and re-persists them in order
     * ({@link #combineTransactionWriter(TransactionRepository)} &mdash; the REPRO-load analogue).
     *
     * <p>The chunk is explicitly typed {@code <Transaction, Transaction>} (input and output types are
     * identical for this pass-through stage), and the commit interval equals {@link #chunkSize}.</p>
     *
     * @return the fully built {@code combineTransactionStep}
     */
    @Bean
    Step combineTransactionStep() {
        return new StepBuilder("combineTransactionStep", jobRepository)
                .<Transaction, Transaction>chunk(chunkSize, transactionManager)
                .reader(combineTransactionItemReader)
                .processor(combineTransactionProcessor)
                .writer(combineTransactionWriter(transactionRepository))
                .build();
    }

    /**
     * The standalone combine job wrapping {@link #combineTransactionStep()}. It registers a
     * {@link RunIdIncrementer} (so each launch is a distinct, re-runnable {@code JobInstance}, matching
     * the JCL rerun semantics) and the {@link BatchCorrelationIdListener} (so every log line emitted
     * during the run carries the MDC {@code correlationId}). No {@link org.springframework.batch.core.Job Job}
     * is auto-run on startup; launching is driven explicitly (for example by the report bridge or a
     * scheduler), consistent with the other pipeline stages.
     *
     * @return the fully built {@code combineTransactionJob}
     */
    @Bean
    Job combineTransactionJob() {
        return new JobBuilder("combineTransactionJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(batchCorrelationIdListener)
                .start(combineTransactionStep())
                .build();
    }
}
