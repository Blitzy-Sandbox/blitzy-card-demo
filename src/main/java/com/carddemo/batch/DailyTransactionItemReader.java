package com.carddemo.batch;

import com.carddemo.entity.DailyTransaction;
import com.carddemo.repository.DailyTransactionRepository;

import java.util.Map;

import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Chunk-oriented Spring Batch reader for daily (unposted) transactions — reader
 * <strong>#1 of 5</strong> in the CardDemo batch pipeline, wired into the transaction
 * posting step of {@code PostTransactionJob}.
 *
 * <p><strong>COBOL lineage (reference-only, source SHA {@code 27d6c6f}).</strong> This
 * reader is the idiomatic Spring Batch replacement for the sequential daily-transaction
 * scan performed by legacy batch program {@code app/cbl/CBTRN02C.cbl} (the {@code POSTTRAN}
 * job, {@code app/jcl/POSTTRAN.jcl} step {@code STEP15 EXEC PGM=CBTRN02C}). In COBOL the
 * daily-transaction file is the physical-sequential dataset assigned by the JCL DD
 * statement:</p>
 *
 * <pre>{@code
 *   //DALYTRAN DD DISP=SHR,
 *   //         DSN=AWS.M2.CARDDEMO.DALYTRAN.PS        (sequential input)
 * }</pre>
 *
 * <p>The dataset is opened by {@code 0000-DALYTRAN-OPEN} ({@code OPEN INPUT DALYTRAN-FILE})
 * and consumed record-by-record inside the driver loop
 * {@code PERFORM UNTIL END-OF-FILE = 'Y'}, which repeatedly performs
 * {@code 1000-DALYTRAN-GET-NEXT}. That fetch paragraph is the direct analogue of a single
 * {@link #read()} invocation of this reader:</p>
 *
 * <pre>{@code
 *   1000-DALYTRAN-GET-NEXT.
 *       READ DALYTRAN-FILE INTO DALYTRAN-RECORD.
 *       IF  DALYTRAN-STATUS = '00'          (a record was read)
 *           MOVE 0 TO APPL-RESULT           (APPL-AOK  -> continue the loop)
 *       ELSE
 *           IF  DALYTRAN-STATUS = '10'      (end of file)
 *               MOVE 16 TO APPL-RESULT      (APPL-EOF)
 *           ELSE
 *               MOVE 12 TO APPL-RESULT      (genuine I/O error)
 *           END-IF
 *       END-IF
 *       IF  APPL-AOK
 *           CONTINUE
 *       ELSE
 *           IF  APPL-EOF                    (status '10')
 *               MOVE 'Y' TO END-OF-FILE     (NORMAL termination)
 *           ELSE
 *               DISPLAY 'ERROR READING DALYTRAN FILE'
 *               PERFORM 9910-DISPLAY-IO-STATUS
 *               PERFORM 9999-ABEND-PROGRAM  (abend)
 *           END-IF
 *       END-IF
 *       EXIT.
 * }</pre>
 *
 * <p>The migrated persistence model stores those daily-transaction records in the
 * {@code daily_transaction} table (JPA entity {@link DailyTransaction}, copybook
 * {@code app/cpy/CVTRA06Y.cpy}). The Gate&nbsp;1/Gate&nbsp;4 named validation fixture
 * {@code app/data/ASCII/dailytran.txt} seeds that table via the Flyway migration
 * {@code V3__seed_data.sql}, so this reader scans exactly the rows the legacy job would
 * have read from {@code DALYTRAN.PS}.</p>
 *
 * <h2>End-of-file semantics (AAP §0.8.3 / §0.8.5)</h2>
 * <p><strong>EOF is normal termination, never an exception.</strong> When the underlying
 * paged query is exhausted, the inherited {@link #read()} returns {@code null}; Spring Batch
 * treats a {@code null} read as the signal to stop the chunk loop. This is the exact
 * counterpart of COBOL {@code DALYTRAN-STATUS = '10'} → {@code APPL-EOF} →
 * {@code MOVE 'Y' TO END-OF-FILE} above: the legacy program ends the {@code PERFORM UNTIL}
 * loop cleanly rather than abending. A genuine I/O failure — the branch COBOL routes to
 * {@code 9910-DISPLAY-IO-STATUS} + {@code 9999-ABEND-PROGRAM} — is <em>not</em> masked here:
 * any Spring Data / JPA exception raised while paging is allowed to propagate so the step
 * fails, mirroring the abend. EOF is therefore never converted into an error, and errors are
 * never swallowed into an EOF.</p>
 *
 * <h2>Reader configuration</h2>
 * <p>All configuration is performed once in the constructor via the inherited setters of
 * {@link RepositoryItemReader} (which implements
 * {@link org.springframework.beans.factory.InitializingBean}, so Spring invokes
 * {@code afterPropertiesSet()} to validate the fully-configured bean):</p>
 * <ul>
 *   <li><strong>Repository</strong> — the constructor-injected
 *       {@link DailyTransactionRepository}; the reader pages over the staging table using its
 *       inherited {@link org.springframework.data.repository.PagingAndSortingRepository}
 *       method.</li>
 *   <li><strong>Method</strong> — {@code findAll} (i.e.
 *       {@link org.springframework.data.repository.PagingAndSortingRepository#findAll(org.springframework.data.domain.Pageable)}),
 *       the plain forward scan that matches the legacy sequential {@code READ}; no keyed or
 *       filtered access is used, in keeping with the repository's deliberately query-free
 *       contract.</li>
 *   <li><strong>Sort</strong> — ascending by {@code dalytranId} (the entity's
 *       {@code @Id}, COBOL {@code DALYTRAN-ID PIC X(16)}). A deterministic sort makes paging
 *       stable and the step <em>restartable</em> and idempotent (AAP §0.8.5). The final stored
 *       order of posted rows is intrinsically by {@code tranId} (the KSDS-equivalent primary key
 *       of the {@code transaction} table), so ascending {@code dalytranId} is a stable,
 *       restart-safe read order that does not alter posted-transaction ordering.</li>
 *   <li><strong>Page size</strong> — bound to the {@code carddemo.batch.chunk-size} property
 *       (default {@code 100}); aligning the reader's page size with the step's commit chunk size
 *       keeps one database page per chunk.</li>
 *   <li><strong>Name</strong> — {@code dailyTransactionItemReader}; required so the reader's
 *       {@code read.count} state is stored under a unique key in the step
 *       {@code ExecutionContext}, enabling correct restart and avoiding collisions with the
 *       other pipeline readers.</li>
 * </ul>
 *
 * <p><strong>Decimal fidelity.</strong> This reader introduces no numeric handling of its own;
 * the monetary field {@code DALYTRAN-AMT} ({@code PIC S9(09)V99}) is carried on
 * {@link DailyTransaction} as a {@link java.math.BigDecimal} of scale&nbsp;2. No {@code float}
 * or {@code double} is used anywhere in the posting path.</p>
 *
 * <p><strong>Immutability.</strong> The class is declared {@code final}: it is a leaf Spring
 * component that is never intended to be subclassed, which also keeps the constructor's
 * configuration calls free of {@code this}-escape concerns.</p>
 *
 * @see DailyTransactionRepository
 * @see DailyTransaction
 * @see RepositoryItemReader
 */
@Component
public final class DailyTransactionItemReader extends RepositoryItemReader<DailyTransaction> {

    /**
     * Unique Spring Batch component name for this reader.
     *
     * <p>Used as the prefix of the {@code read.count} key persisted in the step
     * {@code ExecutionContext}; a stable, unique name is required for correct restart behaviour
     * and to prevent state-key collisions with the other readers in the pipeline.</p>
     */
    private static final String READER_NAME = "dailyTransactionItemReader";

    /**
     * Entity property used as the deterministic, restart-safe sort key.
     *
     * <p>Matches the {@code @Id} field {@link DailyTransaction#getDalytranId()} (COBOL
     * {@code DALYTRAN-ID PIC X(16)}).</p>
     */
    private static final String SORT_KEY_PROPERTY = "dalytranId";

    /**
     * Name of the repository method the reader invokes for each page.
     *
     * <p>Resolves to
     * {@link org.springframework.data.repository.PagingAndSortingRepository#findAll(org.springframework.data.domain.Pageable)},
     * inherited by {@link DailyTransactionRepository}.</p>
     */
    private static final String READ_METHOD_NAME = "findAll";

    /**
     * Builds and fully configures the daily-transaction reader.
     *
     * <p>Every {@link RepositoryItemReader} setting is applied here so that the bean is complete
     * before Spring calls {@code afterPropertiesSet()}. Configuration uses constructor injection
     * only (no field injection), in line with the migration's dependency-injection conventions.</p>
     *
     * @param dailyTransactionRepository the Spring Data repository over the
     *                                   {@code daily_transaction} staging table; paged in
     *                                   ascending {@code dalytranId} order. Must not be
     *                                   {@code null}.
     * @param chunkSize                  the page size, bound to the
     *                                   {@code carddemo.batch.chunk-size} property (default
     *                                   {@code 100}); should match the posting step's commit
     *                                   chunk size so each chunk maps to a single database page.
     */
    public DailyTransactionItemReader(
            final DailyTransactionRepository dailyTransactionRepository,
            @Value("${carddemo.batch.chunk-size:100}") final int chunkSize) {
        setRepository(dailyTransactionRepository);
        setMethodName(READ_METHOD_NAME);
        setSort(Map.of(SORT_KEY_PROPERTY, Sort.Direction.ASC));
        setPageSize(chunkSize);
        setName(READER_NAME);
    }
}
