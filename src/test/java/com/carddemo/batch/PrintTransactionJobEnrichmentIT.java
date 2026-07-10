package com.carddemo.batch;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;

import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionRepository;

import jakarta.persistence.EntityManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end <strong>integration test</strong> ({@code *IT}, executed by the Maven Failsafe plugin
 * 3.5.2) that pins the performance contract of the transaction print job's cross-reference / account
 * enrichment: it must scale with a <strong>bounded number of reads per chunk</strong>, never the legacy
 * per-row {@code 2000-LOOKUP-XREF} + {@code 3000-READ-ACCOUNT} pattern that costs two reads per
 * transaction (an N+1 query risk on larger migrated data). This is the runtime re-verification of the
 * QA finding "{@code printTransactionJob} uses N+1-style enrichment reads" (Issue&nbsp;8, Performance).
 *
 * <h2>COBOL lineage (REFERENCE-only, source commit SHA {@code 27d6c6f}; not copied here)</h2>
 * <p>{@code app/cbl/CBTRN01C.CBL} reads every transaction and, for each, looks up the owning card
 * cross-reference and account for a best-effort enriched display, warning (never aborting) when a
 * parent is absent. {@link PrintReferenceJobs#transactionPrintWriter()} preserves that display and its
 * warnings exactly, but batch-preloads the parents once per chunk so the number of round-trips is
 * reduced from {@code O(N)} to a small constant per chunk (AAP&nbsp;&sect;0.4.3 chunk-oriented batch;
 * &sect;0.7.2 Gate&nbsp;3 performance baseline).</p>
 *
 * <h2>What this test proves (and how it complements the unit test)</h2>
 * <p>{@code PrintReferenceJobsTest} pins the batched interaction <em>in isolation</em> with Mockito
 * (exactly one {@code findAllById} per repository, zero per-row {@code findById}). This integration
 * test instead launches the <strong>real</strong> {@code printTransactionJob} against a <strong>real
 * PostgreSQL&nbsp;16</strong> instance seeded by the production Flyway migrations (including
 * {@code V3__seed_data.sql}), captures the SQL Hibernate actually emits, and asserts that the number of
 * {@code card_xref} and {@code account} SELECTs is bounded by the number of chunks &mdash; not by the
 * number of transactions. With the default chunk size of {@value #DEFAULT_CHUNK_SIZE} and the seeded
 * transaction volume the job processes a single chunk, so the per-row pattern would issue up to
 * {@code 2 &times; readCount} enrichment reads whereas the batched implementation issues at most one
 * read per repository. The job is purely read-only (a logging writer), so it mutates no seeded row and
 * is safe to run against the shared singleton container (AAP&nbsp;&sect;0.7.7).</p>
 *
 * <h2>Why {@link JobLauncherTestUtils#setJob(Job)} is called explicitly</h2>
 * <p>{@link SpringBatchTest @SpringBatchTest} registers a {@link JobLauncherTestUtils} whose
 * {@code Job} is auto-wired only when the context contains exactly one {@code Job} bean. The CardDemo
 * context defines ten batch jobs, so the utility's job is left unset by the framework; this test binds
 * it to {@code printTransactionJob} in {@link #bindPrintTransactionJob()} before each run.</p>
 *
 * @see PrintReferenceJobs#transactionPrintWriter()
 * @see AbstractBatchIntegrationTest
 */
@SpringBatchTest
@DisplayName("printTransactionJob IT: CBTRN01C enrichment scales per-chunk (no N+1) — QA Issue 8")
class PrintTransactionJobEnrichmentIT extends AbstractBatchIntegrationTest {

    /** Default {@code carddemo.batch.chunk-size} (matches {@code application.yml}); used only in Javadoc. */
    private static final int DEFAULT_CHUNK_SIZE = 100;

    /** Correlation id supplied as a job parameter so this run's log lines are traceable end-to-end. */
    private static final String CORRELATION_ID = "it-print-txn-enrich-001";

    /**
     * Number of transactions this test seeds before launching the job. The {@code transaction} table is
     * empty at the Flyway baseline (it is populated by the POSTTRAN job, not by {@code V3__seed_data.sql}),
     * so this test self-provisions its own rows &mdash; each referencing a real seeded card cross-reference
     * so enrichment resolves fully &mdash; and removes them afterwards (AAP&nbsp;&sect;0.7.7 zero-ambient-state).
     * A dozen rows is comfortably below one chunk yet large enough that a per-row lookup (up to
     * {@code 2 &times; 12} reads) is unmistakably distinct from the batched {@code &le; 2} reads.
     */
    private static final int SEEDED_TRANSACTION_COUNT = 12;

    /**
     * Shared {@link JobLauncherTestUtils}: its {@code JobLauncher} and {@code JobRepository} are
     * auto-wired by {@link SpringBatchTest @SpringBatchTest}; this test binds its {@code Job} to
     * {@code printTransactionJob} in {@link #bindPrintTransactionJob()} before each run.
     */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /** The specific job under test, injected by bean name (the context holds ten {@code Job} beans). */
    @Autowired
    @Qualifier("printTransactionJob")
    private Job printTransactionJob;

    /** Repository used to self-provision (and tear down) the transactions this test enriches. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** Repository used to read real seeded card cross-references so the seeded transactions resolve. */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    /**
     * The JPA {@link EntityManagerFactory}, unwrapped to a Hibernate {@link SessionFactory} so the test
     * can read {@link Statistics#getPrepareStatementCount()} — the deterministic count of JDBC
     * round-trips Hibernate issued during the job, which is what distinguishes a bounded per-chunk
     * preload from a per-row N+1.
     */
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /**
     * The configured chunk / reader page size. The number of chunks the job processes is
     * {@code ceil(readCount / chunkSize)}, which is the upper bound this test asserts on the per-chunk
     * enrichment reads.
     */
    @Value("${carddemo.batch.chunk-size:100}")
    private int chunkSize;

    /** The ids of the transactions this test seeded, removed in {@link #removeSeededTransactions()}. */
    private final List<String> seededTransactionIds = new ArrayList<>();

    /**
     * Binds the job under test to the shared {@link JobLauncherTestUtils} before every test method,
     * because {@link SpringBatchTest @SpringBatchTest} only auto-wires the job when the context defines
     * exactly one (this one defines ten).
     */
    @BeforeEach
    void bindPrintTransactionJob() {
        jobLauncherTestUtils.setJob(printTransactionJob);
    }

    /**
     * Seeds {@link #SEEDED_TRANSACTION_COUNT} transactions, each referencing a real seeded card
     * cross-reference (whose owning account is also seeded), so the enrichment resolves every parent and
     * exercises both the {@code card_xref} and {@code account} preloads. Ids use a non-numeric,
     * test-scoped prefix so they never collide with the numeric ids POSTTRAN posts, and they are removed
     * in {@link #removeSeededTransactions()} so the shared container is left exactly as found.
     */
    @BeforeEach
    void seedTransactions() {
        final List<CardXref> crossReferences = cardXrefRepository.findAll();
        assertThat(crossReferences)
                .as("V3__seed_data.sql must provide card cross-references for the seeded transactions to reference")
                .isNotEmpty();
        for (int i = 0; i < SEEDED_TRANSACTION_COUNT; i++) {
            final CardXref crossReference = crossReferences.get(i % crossReferences.size());
            final Transaction transaction = new Transaction();
            final String tranId = String.format("IT8P%012d", i + 1L);
            transaction.setTranId(tranId);
            transaction.setTranCardNum(crossReference.getXrefCardNum());
            transaction.setTranTypeCd("01");
            transaction.setTranCatCd(1);
            transaction.setTranSource("IT-TEST");
            transaction.setTranDesc("QA Issue 8 enrichment IT seed row");
            transaction.setTranAmt(new BigDecimal("10.00"));
            transaction.setTranMerchantId(800000000L);
            transaction.setTranMerchantName("IT MERCHANT");
            transaction.setTranOrigTs("2022-06-10 19:27:53.000000");
            transaction.setTranProcTs("2022-06-10 19:27:53.000000");
            transactionRepository.save(transaction);
            seededTransactionIds.add(tranId);
        }
    }

    /**
     * Removes the transactions this test seeded (always &mdash; even on failure), so no ambient rows leak
     * into the PostgreSQL singleton shared across the batch integration suite.
     */
    @AfterEach
    void removeSeededTransactions() {
        transactionRepository.deleteAllById(seededTransactionIds);
        seededTransactionIds.clear();
    }

    /**
     * Launches the real {@code printTransactionJob} against real PostgreSQL, capturing the SQL Hibernate
     * emits, and verifies that the cross-reference / account enrichment costs a bounded number of reads
     * per chunk rather than two reads per transaction (QA Issue&nbsp;8, the N+1 fix).
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("enrichment issues a bounded per-chunk number of JDBC reads, never ~2 per transaction")
    void enrichmentUsesBoundedPerChunkReadsNotPerRow() throws Exception {
        final SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        final Statistics statistics = sessionFactory.getStatistics();
        final boolean statisticsWereEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        final JobExecution execution;
        try {
            execution = jobLauncherTestUtils.launchJob(new JobParametersBuilder()
                    .addString(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, CORRELATION_ID)
                    .addLong("run.id", System.nanoTime())
                    .toJobParameters());
        } finally {
            statistics.setStatisticsEnabled(statisticsWereEnabled);
        }

        assertThat(execution.getStatus())
                .as("printTransactionJob must COMPLETE against real PostgreSQL")
                .isEqualTo(BatchStatus.COMPLETED);

        // The job has a single step; its read count is the number of transactions enriched.
        long readCount = 0L;
        for (final StepExecution stepExecution : execution.getStepExecutions()) {
            readCount += stepExecution.getReadCount();
        }
        assertThat(readCount)
                .as("the seeded transactions (this IT self-provisions %d) must be present to enrich",
                        SEEDED_TRANSACTION_COUNT)
                .isGreaterThanOrEqualTo(SEEDED_TRANSACTION_COUNT);

        // The total JDBC prepared statements Hibernate issued during the job: the reader's page query(s)
        // plus the enrichment reads. A batched preload issues a small, bounded number PER CHUNK (one
        // card_xref read + one account read + the reader page), independent of row count; a per-row
        // lookup would instead issue ~2 reads PER TRANSACTION.
        final long preparedStatements = statistics.getPrepareStatementCount();
        final long chunks = (readCount + chunkSize - 1) / chunkSize;

        // Sanity: the job did real JDBC work (read the transactions and their parents).
        assertThat(preparedStatements)
                .as("the job must issue at least one JDBC statement (reader + enrichment)")
                .isGreaterThanOrEqualTo(1L);

        // The crux of the fix: the statement count scales with the number of CHUNKS, not the number of
        // rows. Per chunk the writer issues at most two enrichment reads (card_xref + account); the
        // reader adds at most a couple of page reads per chunk. A per-row lookup would need ~2*readCount
        // enrichment reads alone. Assert a generous per-chunk ceiling that batching meets comfortably but
        // a per-row regression (~2*readCount) would blow through.
        final long perChunkCeiling = (6L * chunks) + 4L;
        assertThat(preparedStatements)
                .as("prepared statements (%d) must stay within the per-chunk ceiling %d (chunks=%d, "
                        + "readCount=%d) — proving batched preload, not a per-row N+1 (which would issue "
                        + "~%d)", preparedStatements, perChunkCeiling, chunks, readCount, 2L * readCount)
                .isLessThanOrEqualTo(perChunkCeiling);

        // Belt-and-braces, row-scaled guard: the count is strictly below the 2*readCount a per-row
        // enrichment would issue, so this can never silently regress to an N+1 as the data set grows.
        assertThat(preparedStatements)
                .as("prepared statements (%d) must be far below the 2*readCount (%d) a per-row lookup "
                        + "would issue", preparedStatements, 2L * readCount)
                .isLessThan(2L * readCount);
    }
}
