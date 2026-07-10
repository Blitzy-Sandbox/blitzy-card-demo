package com.carddemo.batch;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import com.carddemo.observability.CorrelationIdFilter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * F2 idempotency integration test for {@code postTransactionJob} (QA finding <strong>F2</strong>,
 * decision-log <strong>D-028</strong>) — proves that re-running {@code POSTTRAN} does
 * <em>not</em> double-post account balances.
 *
 * <p><strong>The defect (before D-028).</strong> Legacy {@code CBTRN02C} blind-{@code ADD}s each
 * amount into {@code ACCT-CURR-BAL}/{@code ACCT-CURR-CYC-CREDIT}/{@code ACCT-CURR-CYC-DEBIT}, and
 * the mainframe relied on the surrounding JCL ({@code TRANBKP} backup/restore of the account master
 * plus GDG generations) to make a rerun safe. The Java target deliberately does not reproduce that
 * dataset-staging layer, so a plain {@code RunIdIncrementer}-style re-execution re-posted all 262
 * valid records: transaction rows stayed at 262 (primary-key upsert on {@code TRAN-ID}) but the
 * balance accumulators doubled and the dirtied balances shifted the reject count 38&nbsp;&rarr;&nbsp;113.</p>
 *
 * <p><strong>The fix (D-028).</strong> {@code PostTransactionProcessor} now consults the TRANSACT
 * master and returns {@code null} for any daily row whose {@code DALYTRAN-ID} is already a posted
 * {@code Transaction}, so Spring Batch <em>filters</em> it (not posted, not rejected, not counted).</p>
 *
 * <p><strong>What this test pins.</strong> It launches the real job twice against the same seeded
 * data (two distinct {@code run.id}s = two blind re-executions, exactly as the QA report reproduced)
 * and asserts, at runtime against PostgreSQL + LocalStack:</p>
 * <ol>
 *   <li><strong>Run&nbsp;1 parity (Gate&nbsp;1/4):</strong> 262 posted, 38 rejected, read=300, write=300,
 *       filter=0, and the S3 reject object is byte-identical to {@code dalyrejs-expected.txt}.</li>
 *   <li><strong>Run&nbsp;2 idempotency:</strong> read=300 (the reader re-reads every daily row),
 *       <strong>filter=262</strong> (the already-posted rows are filtered by the guard),
 *       write=38 (only the 38 re-evaluated rejects reach the writer), and the transaction master
 *       still holds exactly 262 rows (<strong>zero</strong> new posts).</li>
 *   <li><strong>No double-posting:</strong> every account's
 *       {@code (acct_curr_bal, acct_curr_cyc_credit, acct_curr_cyc_debit)} triple is
 *       <em>identical</em> after run&nbsp;2 to what it was after run&nbsp;1.</li>
 *   <li><strong>Stable rejects:</strong> the run&nbsp;2 reject object is byte-identical to the
 *       run&nbsp;1 reject object (the monotonic-balance invariant guarantees the same 38 records
 *       re-reject), so the count never drifts 38&nbsp;&rarr;&nbsp;113.</li>
 * </ol>
 *
 * @see PostTransactionProcessor
 * @see PostTransactionJobIT
 * @see AbstractBatchIntegrationTest
 */
@SpringBatchTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("PostTransactionJob IT — F2 idempotency: a re-run must not double-post balances (D-028)")
class PostTransactionIdempotencyIT extends AbstractBatchIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PostTransactionIdempotencyIT.class);

    /** The seeded {@code daily_transaction} population (the {@code dailytran.txt} fixture). */
    private static final int EXPECTED_DAILY_COUNT = 300;

    /** The stateful posted/rejected split for that population (see {@link PostTransactionJobIT}). */
    private static final int EXPECTED_POSTED_COUNT = 262;
    private static final int EXPECTED_REJECT_COUNT = 38;

    /** The S3 key under {@link #BUCKET_OUTPUT} where the posting writer uploads the reject object. */
    private static final String REJECT_OBJECT_KEY = "dalyrejs.dat";

    /** 430-byte reject record + one {@code \n} separator (matches {@link PostTransactionJobIT}). */
    private static final int REJECT_STRIDE = 431;

    /** Baseline reject object on the test classpath (Gate-1 byte-equivalence anchor). */
    private static final String EXPECTED_REJECT_RESOURCE = "/expected/dalyrejs-expected.txt";

    /** Step-execution-context key under which the writer promotes its reject count. */
    private static final String REJECT_COUNT_KEY = "rejectCount";

    private static final String SQL_COUNT_TRANSACTION = "SELECT count(*) FROM transaction";
    private static final String SQL_COUNT_DAILY = "SELECT count(*) FROM daily_transaction";
    private static final String SQL_DELETE_TRANSACTION = "DELETE FROM transaction";

    /** Reads the full account-balance state; the map's equality is the no-double-post assertion. */
    private static final String SQL_SELECT_BALANCES =
            "SELECT acct_id, acct_curr_bal, acct_curr_cyc_credit, acct_curr_cyc_debit "
                    + "FROM account ORDER BY acct_id";

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    @Qualifier("postTransactionJob")
    private Job postTransactionJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        // Several Job beans exist -> the @SpringBatchTest utility cannot auto-bind one; set it here.
        jobLauncherTestUtils.setJob(postTransactionJob);
    }

    /**
     * Runs {@code postTransactionJob} twice and proves the second run is a no-op on balances.
     *
     * @throws Exception if a job launch fails
     */
    @Test
    @DisplayName("A blind re-run filters the 262 already-posted rows; balances and rejects are unchanged")
    void rerunIsIdempotent_noDoublePosting() throws Exception {
        createBucket(BUCKET_OUTPUT);
        try {
            establishDeterministicPreState();

            // -------------------------------------------------------------------------------------
            // RUN 1 — the first, authoritative posting run (must reproduce the Gate-1 262/38 split).
            // -------------------------------------------------------------------------------------
            final RunResult run1 = launchOnce("it-idem-run1");
            assertEquals(EXPECTED_DAILY_COUNT, run1.readCount(),
                    "run 1 must read every one of the " + EXPECTED_DAILY_COUNT + " seeded daily rows");
            assertEquals(EXPECTED_DAILY_COUNT, run1.writeCount(),
                    "run 1: every read item (262 posted + 38 rejected) must reach the writer");
            assertEquals(0L, run1.filterCount(),
                    "run 1: nothing is filtered on the first run (the transaction master starts empty)");
            assertEquals(EXPECTED_REJECT_COUNT, run1.rejectCount(),
                    "run 1 must reject exactly " + EXPECTED_REJECT_COUNT + " records");

            final int txnAfterRun1 = transactionCount();
            assertEquals(EXPECTED_POSTED_COUNT, txnAfterRun1,
                    "run 1 must post exactly " + EXPECTED_POSTED_COUNT + " transactions");

            final byte[] rejectAfterRun1 = readObject(BUCKET_OUTPUT, REJECT_OBJECT_KEY);
            assertEquals(0, rejectAfterRun1.length % REJECT_STRIDE,
                    "the reject object length must be a whole multiple of the 431-byte record+LF stride");
            assertEquals(EXPECTED_REJECT_COUNT, rejectAfterRun1.length / REJECT_STRIDE,
                    "run 1 reject object must contain exactly " + EXPECTED_REJECT_COUNT + " records");
            // Gate 1: the fresh single-run reject file is byte-equivalent to the documented baseline.
            assertArrayEquals(readClasspathBytes(EXPECTED_REJECT_RESOURCE), rejectAfterRun1,
                    "run 1 reject object must be byte-identical to " + EXPECTED_REJECT_RESOURCE
                            + " (Gate-1 fresh single-run parity must survive the F2 guard)");

            final Map<Long, String> balancesAfterRun1 = snapshotBalances();
            log.info("F2 IT: run 1 posted={} rejected={} distinctAccountsTouched={}",
                    txnAfterRun1, rejectAfterRun1.length / REJECT_STRIDE, balancesAfterRun1.size());

            // -------------------------------------------------------------------------------------
            // RUN 2 — a blind re-execution (distinct run.id), exactly as the QA report reproduced.
            // The F2 guard (D-028) must filter the 262 already-posted rows so nothing double-posts.
            // -------------------------------------------------------------------------------------
            final RunResult run2 = launchOnce("it-idem-run2");
            assertEquals(EXPECTED_DAILY_COUNT, run2.readCount(),
                    "run 2: the reader still reads all " + EXPECTED_DAILY_COUNT + " daily rows");
            assertEquals(EXPECTED_POSTED_COUNT, run2.filterCount(),
                    "run 2: the F2 guard must FILTER all " + EXPECTED_POSTED_COUNT + " already-posted rows");
            assertEquals(EXPECTED_REJECT_COUNT, run2.writeCount(),
                    "run 2: only the " + EXPECTED_REJECT_COUNT + " re-evaluated rejects reach the writer");
            assertEquals(EXPECTED_DAILY_COUNT, run2.filterCount() + run2.writeCount(),
                    "run 2: filtered + written must reconstruct the full " + EXPECTED_DAILY_COUNT + "-row input");
            assertEquals(EXPECTED_REJECT_COUNT, run2.rejectCount(),
                    "run 2 must re-reject exactly the same " + EXPECTED_REJECT_COUNT + " records (stable classification)");

            // -------------------------------------------------------------------------------------
            // The idempotency guarantees.
            // -------------------------------------------------------------------------------------
            final int txnAfterRun2 = transactionCount();
            assertEquals(txnAfterRun1, txnAfterRun2,
                    "the transaction master must be unchanged by run 2 (zero new posts): "
                            + txnAfterRun1 + " expected, was " + txnAfterRun2);

            final Map<Long, String> balancesAfterRun2 = snapshotBalances();
            assertEquals(balancesAfterRun1, balancesAfterRun2,
                    "EVERY account balance triple (bal, cyc-credit, cyc-debit) must be IDENTICAL after "
                            + "run 2 — a double-post would have doubled the cycle accumulators (F2 regression)");

            final byte[] rejectAfterRun2 = readObject(BUCKET_OUTPUT, REJECT_OBJECT_KEY);
            assertArrayEquals(rejectAfterRun1, rejectAfterRun2,
                    "the reject object must be byte-identical across runs — the monotonic-balance invariant "
                            + "keeps the same 38 records rejecting (never drifting 38 -> 113)");

            log.info("F2 IT: run 2 filtered={} rejected={} newPosts={} balancesIdentical={}",
                    run2.filterCount(), run2.writeCount(), txnAfterRun2 - txnAfterRun1, true);
        } finally {
            // Restore the shared baseline for any subsequently-ordered IT in the module: drop the bucket,
            // empty the transaction master, AND reset the account cycle accumulators this run mutated back
            // to their V3 seed (0.00) so a later posting IT inherits a pristine pre-state.
            deleteBucketRecursively(BUCKET_OUTPUT);
            jdbcTemplate.update(SQL_DELETE_TRANSACTION);
            resetAccountCycleTotalsToSeed(jdbcTemplate);
        }
    }

    /**
     * Empties the {@code transaction} master and asserts the seeded pre-state (300 daily rows, 0
     * posted rows) so the post-launch counts are unambiguous.
     */
    private void establishDeterministicPreState() {
        jdbcTemplate.update(SQL_DELETE_TRANSACTION);
        // The POSTGRES singleton is shared across the batch IT suite and @DirtiesContext does not reset
        // the database, so a prior posting IT may have left the account cycle accumulators non-zero.
        // Restore them to their V3 seed (0.00) so the 262/38 split is reproduced regardless of suite
        // order (see AbstractBatchIntegrationTest#resetAccountCycleTotalsToSeed).
        resetAccountCycleTotalsToSeed(jdbcTemplate);

        final Integer dailyCount = jdbcTemplate.queryForObject(SQL_COUNT_DAILY, Integer.class);
        assertNotNull(dailyCount, "daily_transaction count query returned null");
        assertEquals(EXPECTED_DAILY_COUNT, dailyCount.intValue(),
                "V3__seed_data.sql must seed exactly " + EXPECTED_DAILY_COUNT + " daily_transaction rows");

        final Integer preTransactionCount = jdbcTemplate.queryForObject(SQL_COUNT_TRANSACTION, Integer.class);
        assertNotNull(preTransactionCount, "transaction count query returned null");
        assertEquals(0, preTransactionCount.intValue(),
                "transaction master must start EMPTY so the post-launch count is exactly the posted count");
    }

    /**
     * Launches the posting job once with a fresh {@code run.id} (a distinct job instance) and returns
     * the four step-level counts asserted by the test.
     *
     * <p>This deliberately returns a small value object rather than a {@link StepExecution} (or a
     * {@link JobExecution}): {@code @SpringBatchTest}'s {@code StepScopeTestExecutionListener} scans
     * the test class for a method whose return type is {@code StepExecution} to synthesise the
     * step-scope context, and would otherwise try (and fail) to invoke this helper with no arguments.</p>
     *
     * @param correlationId the correlation id to propagate for this run
     * @return the read / write / filter / reject counts of the single {@code postTransactionStep}
     * @throws Exception if the launch fails
     */
    private RunResult launchOnce(final String correlationId) throws Exception {
        final JobParameters parameters = new JobParametersBuilder()
                .addString(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, correlationId)
                .addLong("run.id", System.nanoTime())
                .toJobParameters();

        final JobExecution execution = jobLauncherTestUtils.launchJob(parameters);
        assertNotNull(execution, "launchJob returned a null JobExecution");
        assertEquals(BatchStatus.COMPLETED, execution.getStatus(),
                "postTransactionJob must finish COMPLETED (a business reject is data, never an abend)");
        assertEquals(1, execution.getStepExecutions().size(),
                "postTransactionJob must run exactly one step (postTransactionStep)");
        final StepExecution step = execution.getStepExecutions().iterator().next();
        return new RunResult(
                step.getReadCount(),
                step.getWriteCount(),
                step.getFilterCount(),
                step.getExecutionContext().getLong(REJECT_COUNT_KEY, -1L));
    }

    /** Immutable snapshot of the step-level counts a single posting run produced. */
    private record RunResult(long readCount, long writeCount, long filterCount, long rejectCount) {
    }

    /** @return the current row count of the {@code transaction} master. */
    private int transactionCount() {
        final Integer count = jdbcTemplate.queryForObject(SQL_COUNT_TRANSACTION, Integer.class);
        return (count == null) ? -1 : count.intValue();
    }

    /**
     * Snapshots every account's balance triple keyed by account id. Two snapshots comparing equal is
     * the direct, exhaustive assertion that no account was mutated between runs.
     *
     * @return an ordered {@code acct_id -> "bal|cyc-credit|cyc-debit"} map
     */
    private Map<Long, String> snapshotBalances() {
        final Map<Long, String> balances = new TreeMap<>();
        jdbcTemplate.query(SQL_SELECT_BALANCES, rs -> {
            balances.put(
                    rs.getLong("acct_id"),
                    rs.getBigDecimal("acct_curr_bal") + "|"
                            + rs.getBigDecimal("acct_curr_cyc_credit") + "|"
                            + rs.getBigDecimal("acct_curr_cyc_debit"));
        });
        return balances;
    }

    /**
     * Reads a classpath resource fully into a byte array.
     *
     * @param resource the absolute classpath resource path
     * @return the resource bytes
     */
    private byte[] readClasspathBytes(final String resource) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "missing test resource on classpath: " + resource);
            return in.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException("failed to read classpath resource " + resource, e);
        }
    }
}
