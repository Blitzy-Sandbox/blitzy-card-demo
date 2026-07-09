package com.carddemo.batch;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <strong>Flagship Gate&nbsp;1 / Gate&nbsp;4 end-to-end posting-parity integration test</strong> for the
 * migrated transaction-posting batch stage &mdash; the Java translation of the legacy COBOL program
 * {@code CBTRN02C} launched by {@code app/jcl/POSTTRAN.jcl} (source referenced read-only at commit SHA
 * {@code 27d6c6f}; never copied here).
 *
 * <p>This {@code *IT} (executed by the Maven Failsafe plugin, {@code 3.5.2}) launches the <em>real</em>
 * {@code postTransactionJob} over the V3-seeded {@code daily_transaction} table &mdash; the 300-record
 * {@code app/data/ASCII/dailytran.txt} fixture &mdash; against a <strong>real PostgreSQL&nbsp;16</strong>
 * database and <strong>real LocalStack S3</strong> (both provided by {@link AbstractBatchIntegrationTest}'s
 * singleton Testcontainers). It then proves, with byte-for-byte fidelity, that the migration reproduces
 * the documented COBOL baselines. Mocked I/O does <em>not</em> satisfy Gate&nbsp;1, so this test
 * deliberately uses the live containers and never a mock (AAP&nbsp;&sect;0.7.2, &sect;0.7.7).</p>
 *
 * <h2>The Gate&nbsp;1/4 golden numbers (hard assertions &mdash; never softened)</h2>
 * <ul>
 *   <li><strong>262 posted / 38 rejected.</strong> Posting is <em>stateful</em>: {@code CBTRN02C}
 *       paragraph&nbsp;{@code 2800-UPDATE-ACCOUNT-REC} rewrites the {@code ACCOUNT} record so the cycle
 *       balances accumulate across successive posted rows for the same account. Against the V3-seeded
 *       account / transaction-category-balance baseline this yields exactly 262 posted and 38 rejected
 *       &mdash; a naive stateless engine would wrongly report 287/13.</li>
 *   <li><strong>All 38 rejects carry reason {@code 0102 OVERLIMIT}.</strong> For this fixture every card
 *       resolves in the cross-reference, every account exists, and none is expired (account expirations
 *       are 2024&ndash;2025 versus the 2022-06-10 transaction date), so the only failing validation is
 *       the credit-limit check ({@code CBTRN02C} paragraph&nbsp;{@code 1500-VALIDATE-TRAN},
 *       {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} else {@code MOVE 102}, {@code MOVE 'OVERLIMIT
 *       TRANSACTION'}).</li>
 *   <li><strong>Reject baseline</strong> {@code classpath:/expected/dalyrejs-expected.txt}: 38&nbsp;&times;
 *       430&nbsp;bytes plus one line feed each = {@value #EXPECTED_REJECT_FILE_BYTES}&nbsp;bytes, MD5
 *       {@value #EXPECTED_REJECT_FILE_MD5}. No timestamp field, so it compares exactly.</li>
 *   <li><strong>Posted baseline</strong> {@code classpath:/expected/transact-expected.txt}: 262&nbsp;&times;
 *       350&nbsp;bytes plus one line feed each = {@value #EXPECTED_POSTED_FILE_BYTES}&nbsp;bytes, MD5
 *       {@value #EXPECTED_POSTED_FILE_MD5}. The only non-deterministic field is {@code TRAN-PROC-TS} at
 *       byte offset {@code [304:330]} (the processing timestamp minted at run time), which is masked to
 *       the sentinel {@value #PROC_TS_SENTINEL} on <em>both</em> sides before comparison.</li>
 * </ul>
 *
 * <h2>Deterministic pre-state (AAP&nbsp;Refinement&nbsp;R1)</h2>
 * <p>Two preconditions must hold <em>simultaneously</em> for the 262/38 split to be exact: (1) the
 * {@code transaction} master table starts <strong>empty</strong> &mdash; there is no transaction fixture
 * among the nine ASCII files, so {@code V3__seed_data.sql} deliberately does not seed it and the job
 * populates it, making the post-launch count exactly 262; and (2) the account / tcatbal baseline is the
 * pristine V3 seed, which is what makes the split <em>stateful</em>. The test empties the transaction
 * table defensively and asserts the {@code daily_transaction} row count is exactly 300 before launching
 * (a fail-fast contract coupling this test to the migration/seed folders), and launches the job exactly
 * once per context. {@link DirtiesContext @DirtiesContext(AFTER_CLASS)} drops the context afterwards so
 * the stateful account accumulation can never be doubled by a re-run alongside other {@code *IT}s.</p>
 *
 * <h2>Job wiring (AAP&nbsp;Phase&nbsp;1)</h2>
 * <p>The application defines several Spring Batch jobs, so the {@code @SpringBatchTest}-provided
 * {@link JobLauncherTestUtils} cannot auto-bind a unique {@link Job} (its context post-processor injects
 * the job only when the {@code Job} bean is unique). This test therefore autowires the posting job
 * explicitly by {@link Qualifier} and calls {@link JobLauncherTestUtils#setJob(Job)} before launching.</p>
 *
 * @see AbstractBatchIntegrationTest
 * @see PostTransactionJob
 * @see com.carddemo.config.BatchConfig
 */
@SpringBatchTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("PostTransactionJob IT — Gate 1/4 end-to-end posting parity (CBTRN02C + POSTTRAN.jcl @ 27d6c6f)")
class PostTransactionJobIT extends AbstractBatchIntegrationTest {

    /** SLF4J logger; also used to echo the Gate-1 comparison report at {@code INFO}. */
    private static final Logger log = LoggerFactory.getLogger(PostTransactionJobIT.class);

    // -----------------------------------------------------------------------------------------------
    // Golden numbers and baseline fingerprints (AAP "GATE 1/4 GOLDEN NUMBERS"). These are hard
    // contracts: the assertions below must never be relaxed to make a divergent engine pass.
    // -----------------------------------------------------------------------------------------------

    /** Row count of the V3-seeded {@code daily_transaction} staging table (the POSTTRAN input). */
    private static final int EXPECTED_DAILY_COUNT = 300;

    /** Posted-transaction count persisted to the {@code transaction} master (stateful split). */
    private static final int EXPECTED_POSTED_COUNT = 262;

    /** Rejected-transaction count appended to the {@code DALYREJS} object (stateful split). */
    private static final int EXPECTED_REJECT_COUNT = 38;

    /** Size of {@code dalyrejs-expected.txt}: 38 records &times; (430 bytes + 1 LF). */
    private static final int EXPECTED_REJECT_FILE_BYTES = 16378;

    /** Size of {@code transact-expected.txt}: 262 records &times; (350 bytes + 1 LF). */
    private static final int EXPECTED_POSTED_FILE_BYTES = 91962;

    /** MD5 of the reject baseline exactly as delivered on disk (verified fixture fingerprint). */
    private static final String EXPECTED_REJECT_FILE_MD5 = "cd8b21beb93956d5a0625f1ef4fe4f23";

    /** MD5 of the posted baseline (UNMASKED) exactly as delivered on disk (verified fixture fingerprint). */
    private static final String EXPECTED_POSTED_FILE_MD5 = "477b531e0ff7fd4487882558cef1994b";

    /** The three lowest {@code TRAN-ID}s in ascending order (posted-file spot-check). */
    private static final List<String> FIRST_THREE_TRAN_IDS =
            List.of("0000000000683580", "0000000001774260", "0000000006292564");

    // -----------------------------------------------------------------------------------------------
    // AWS resource + launch constants.
    // -----------------------------------------------------------------------------------------------

    /**
     * S3 object key of the reject file inside {@link AbstractBatchIntegrationTest#BUCKET_OUTPUT}. Matches
     * the writer default {@code carddemo.batch.reject.object-key:dalyrejs.dat} and the
     * {@code DALYREJS(+1)} GDG analogue in {@code POSTTRAN.jcl}.
     */
    private static final String REJECT_OBJECT_KEY = "dalyrejs.dat";

    /** Correlation id propagated as a job parameter and re-published to the MDC by the job listener. */
    private static final String CORRELATION_ID_VALUE = "it-post-001";

    // -----------------------------------------------------------------------------------------------
    // Fixed-width layout constants (copybook CVTRA05Y == CVTRA06Y, 350 bytes) and reject trailer.
    // Byte offsets are 0-based, end-exclusive, matching the documented baselines.
    // -----------------------------------------------------------------------------------------------

    /** {@code TRAN-RECORD} length (copybook {@code CVTRA05Y}); also the {@code DALYTRAN-RECORD} length. */
    private static final int TRANSACTION_RECORD_LENGTH = 350;

    /** {@code DALYREJS} record length ({@code REJECT-TRAN-DATA} 350 + {@code VALIDATION-TRAILER} 80). */
    private static final int REJECT_RECORD_LENGTH = 430;

    /** Record separator emitted after every fixed-width record (a literal LF, platform-independent). */
    private static final String RECORD_SEPARATOR = "\n";

    /** On-disk stride of one posted record: 350 payload bytes + 1 LF. */
    private static final int TRANSACTION_STRIDE = TRANSACTION_RECORD_LENGTH + 1;

    /** On-disk stride of one reject record: 430 payload bytes + 1 LF. */
    private static final int REJECT_STRIDE = REJECT_RECORD_LENGTH + 1;

    /** {@code TRAN-PROC-TS} offset within a 350-byte posted record (the masked, non-deterministic field). */
    private static final int PROC_TS_OFFSET = 304;

    /** {@code TRAN-PROC-TS} width ({@code PIC X(26)}). */
    private static final int PROC_TS_LENGTH = 26;

    /** Deterministic sentinel that replaces {@code TRAN-PROC-TS} on both sides ({@value #PROC_TS_LENGTH} chars). */
    private static final String PROC_TS_SENTINEL = "0000-00-00-00.00.00.000000";

    /** Offset of {@code WS-VALIDATION-FAIL-REASON} within a 430-byte reject record. */
    private static final int REJECT_REASON_OFFSET = 350;

    /** Offset of {@code WS-VALIDATION-FAIL-REASON-DESC} within a 430-byte reject record. */
    private static final int REJECT_DESC_OFFSET = 354;

    /** Width of {@code WS-VALIDATION-FAIL-REASON-DESC} ({@code PIC X(76)}). */
    private static final int REJECT_DESC_LENGTH = 76;

    /** Reject reason code rendered in the trailer for every reject in this fixture ({@code PIC 9(04)}). */
    private static final String REJECT_REASON_CODE = "0102";

    /** Reject reason description text (verbatim COBOL literal) rendered left-justified, space-padded to 76. */
    private static final String REJECT_DESC_TEXT = "OVERLIMIT TRANSACTION";

    // -----------------------------------------------------------------------------------------------
    // Zoned-decimal (DISPLAY) overpunch tables for TRAN-AMT PIC S9(09)V99, mirroring the production
    // writer's serialization so the exported posted record is byte-identical to the baseline.
    // -----------------------------------------------------------------------------------------------

    /** Total digit positions of {@code TRAN-AMT} (9 integer + 2 fraction). */
    private static final int AMOUNT_TOTAL_DIGITS = 11;

    /** Fractional digit count of {@code TRAN-AMT} ({@code V99}). */
    private static final int AMOUNT_SCALE = 2;

    /** Positive trailing-digit overpunch: {@code '{'}=+0 &hellip; {@code 'I'}=+9. */
    private static final char[] POSITIVE_OVERPUNCH =
            {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

    /** Negative trailing-digit overpunch: {@code '}'}=-0 &hellip; {@code 'R'}=-9. */
    private static final char[] NEGATIVE_OVERPUNCH =
            {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

    // -----------------------------------------------------------------------------------------------
    // SQL against the migrated tables. `transaction` is a NON-RESERVED identifier in PostgreSQL 16 and
    // is created unquoted by V1__schema.sql, so it is referenced unquoted here (matching the entity).
    // -----------------------------------------------------------------------------------------------

    /** Counts persisted posted transactions (expected 262 after launch). */
    private static final String SQL_COUNT_TRANSACTION = "SELECT count(*) FROM transaction";

    /** Counts the seeded daily-transaction input (expected 300 before launch). */
    private static final String SQL_COUNT_DAILY = "SELECT count(*) FROM daily_transaction";

    /** Empties the transaction master so the post-launch count is exactly the posted count. */
    private static final String SQL_DELETE_TRANSACTION = "DELETE FROM transaction";

    /**
     * Selects every posted transaction in {@code TRAN-ID} order (the KSDS-equivalent primary-key order,
     * matching the ascending ordering of {@code transact-expected.txt}). Columns are listed in copybook
     * {@code CVTRA05Y} declared order so the row mapper can serialize positionally.
     */
    private static final String SQL_SELECT_POSTED =
            "SELECT tran_id, tran_type_cd, tran_cat_cd, tran_source, tran_desc, tran_amt, "
                    + "tran_merchant_id, tran_merchant_name, tran_merchant_city, tran_merchant_zip, "
                    + "tran_card_num, tran_orig_ts, tran_proc_ts "
                    + "FROM transaction ORDER BY tran_id";

    /** Step-execution-context key under which the posting writer promotes the running reject count. */
    private static final String REJECT_COUNT_KEY = "rejectCount";

    /** Flow status the {@code postingRejectDecider} yields when at least one row was rejected (RC=4). */
    private static final String STATUS_COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    /** Directory for the Gate-1 comparison-report deliverable. */
    private static final String GATE1_REPORT_DIR = "target/gate1";

    /** File name of the Gate-1 comparison-report deliverable. */
    private static final String GATE1_REPORT_FILE = "posttran-comparison.txt";

    // -----------------------------------------------------------------------------------------------
    // Injected collaborators.
    // -----------------------------------------------------------------------------------------------

    /** {@code @SpringBatchTest}-provided launcher utility; its job is set explicitly in {@link #setUp()}. */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /** The standalone posting job under test (selected by name because several {@code Job} beans exist). */
    @Autowired
    @Qualifier("postTransactionJob")
    private Job postTransactionJob;

    /** The production reject-gate decider (from {@code BatchConfig}) exercised for RC-4 parity. */
    @Autowired
    @Qualifier("postingRejectDecider")
    private JobExecutionDecider postingRejectDecider;

    /** Auto-configured JDBC template used for pre-state control and posted-row export (typed; no raw JDBC). */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -----------------------------------------------------------------------------------------------
    // Per-class captured state (populated once by the guarded single launch; read by ordered @Tests).
    // -----------------------------------------------------------------------------------------------

    /** Guards the single launch: the job is launched exactly once per context to keep the split stateful. */
    private boolean launched;

    /** The captured job execution from the single launch. */
    private JobExecution jobExecution;

    /** The single posting step's execution (source of the reject count, read/write counts). */
    private StepExecution postStep;

    /** Persisted posted-transaction count read back from the {@code transaction} table (expected 262). */
    private int dbTransactionCount;

    /** Reject count promoted into the step execution context (expected 38). */
    private long stepRejectCount;

    /** Reject-record count derived from the S3 reject object length (expected 38). */
    private int s3RejectRecordCount;

    /** Step read count (expected 300 &mdash; every seeded daily row is read). */
    private long stepReadCount;

    /** Step write count (expected 300 &mdash; every item, posted or rejected, reaches the writer). */
    private long stepWriteCount;

    /** Raw bytes of the reject object read back from LocalStack S3. */
    private byte[] actualRejectBytes;

    /** Raw bytes of the reject baseline resource {@code /expected/dalyrejs-expected.txt}. */
    private byte[] expectedRejectBytes;

    /** Posted output re-materialized from the DB rows (350-byte records + LF), before masking. */
    private byte[] actualPostedBytes;

    /** Posted baseline {@code /expected/transact-expected.txt} exactly as on disk (before masking). */
    private byte[] expectedPostedBytes;

    /** Posted output after masking {@code TRAN-PROC-TS} on every record. */
    private byte[] maskedActualPostedBytes;

    /** Posted baseline after masking {@code TRAN-PROC-TS} on every record. */
    private byte[] maskedExpectedPostedBytes;

    // ===============================================================================================
    // Lifecycle — set the job explicitly and launch exactly once (AAP Phases 1–3).
    // ===============================================================================================

    /**
     * Binds the posting job to the {@link JobLauncherTestUtils} (mandatory because several {@code Job}
     * beans exist, so the utility cannot auto-bind a unique job) and, on the first invocation only,
     * establishes the deterministic pre-state and launches the job exactly once.
     *
     * <p>{@code setJob} is idempotent and inexpensive, so it is re-affirmed on every {@code @BeforeEach}
     * (per the AAP Phase&nbsp;1 contract); the single launch is guarded by {@link #launched} so the
     * stateful account/tcatbal accumulation is applied once and only once for the whole class (paired
     * with {@link DirtiesContext @DirtiesContext(AFTER_CLASS)}). Running the launch here &mdash; rather
     * than in {@code @BeforeAll} &mdash; guarantees the Spring-injected collaborators are already
     * populated (field injection completes before {@code @BeforeEach}).</p>
     *
     * @throws Exception if the pre-state setup, the job launch, or the output capture fails
     */
    @BeforeEach
    void setUp() throws Exception {
        // Multiple Job beans exist -> the @SpringBatchTest utility cannot auto-bind one; set it here.
        jobLauncherTestUtils.setJob(postTransactionJob);
        if (!launched) {
            provisionAwsResources();
            establishDeterministicPreState();
            launchPostingJobOnce();
            captureActualAndExpectedOutputs();
            launched = true;
        }
    }

    /**
     * Self-provisions the S3 bucket this test exercises (AAP&nbsp;&sect;0.7.7). Only
     * {@link AbstractBatchIntegrationTest#BUCKET_OUTPUT} is required: the posting writer uploads the
     * {@code DALYREJS} reject object there and the posting job touches no other bucket (its reader and
     * processor are database-backed). The bucket is torn down in {@link #tearDown()}.
     */
    private void provisionAwsResources() {
        createBucket(BUCKET_OUTPUT);
        log.info("Gate1 IT: provisioned S3 bucket '{}' for the reject object '{}'",
                BUCKET_OUTPUT, REJECT_OBJECT_KEY);
    }

    /**
     * Establishes the deterministic pre-state (AAP&nbsp;Refinement&nbsp;R1). Empties the
     * {@code transaction} master defensively so the post-launch count is exactly the posted count, and
     * asserts the {@code daily_transaction} input holds exactly {@value #EXPECTED_DAILY_COUNT} rows,
     * failing fast with a clear message if {@code V3__seed_data.sql} has drifted (this couples the test
     * to the resources/migration folders by contract).
     */
    private void establishDeterministicPreState() {
        jdbcTemplate.update(SQL_DELETE_TRANSACTION);

        final Integer dailyCount = jdbcTemplate.queryForObject(SQL_COUNT_DAILY, Integer.class);
        assertNotNull(dailyCount, "daily_transaction count query returned null");
        assertEquals(EXPECTED_DAILY_COUNT, dailyCount.intValue(),
                "V3__seed_data.sql must seed exactly " + EXPECTED_DAILY_COUNT + " daily_transaction rows "
                        + "(the dailytran.txt fixture); the 262/38 split depends on this exact input count");

        final Integer preTransactionCount = jdbcTemplate.queryForObject(SQL_COUNT_TRANSACTION, Integer.class);
        assertNotNull(preTransactionCount, "transaction count query returned null");
        assertEquals(0, preTransactionCount.intValue(),
                "transaction master must start EMPTY so the post-launch count is exactly the posted count");
    }

    /**
     * Launches {@code postTransactionJob} exactly once with a propagated correlation id and a unique
     * {@code run.id}, then captures the job execution and its single step execution.
     *
     * @throws Exception if {@link JobLauncherTestUtils#launchJob(JobParameters)} fails
     */
    private void launchPostingJobOnce() throws Exception {
        final JobParameters parameters = new JobParametersBuilder()
                .addString(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, CORRELATION_ID_VALUE)
                .addLong("run.id", System.nanoTime())
                .toJobParameters();

        this.jobExecution = jobLauncherTestUtils.launchJob(parameters);
        assertNotNull(jobExecution, "launchJob returned a null JobExecution");

        // The standalone posting job has exactly one step (postTransactionStep).
        assertEquals(1, jobExecution.getStepExecutions().size(),
                "postTransactionJob must run exactly one step (postTransactionStep)");
        this.postStep = jobExecution.getStepExecutions().iterator().next();
        this.stepReadCount = postStep.getReadCount();
        this.stepWriteCount = postStep.getWriteCount();
        this.stepRejectCount = postStep.getExecutionContext().getLong(REJECT_COUNT_KEY, -1L);
        log.info("Gate1 IT: postTransactionJob finished status={} exit={} read={} write={} rejectCount={}",
                jobExecution.getStatus(), jobExecution.getExitStatus().getExitCode(),
                stepReadCount, stepWriteCount, stepRejectCount);
    }

    /**
     * Reads back the actual outputs (the S3 reject object and the persisted posted rows) and the two
     * baseline resources, and derives the masked posted byte arrays used by the parity assertions.
     */
    private void captureActualAndExpectedOutputs() {
        // Reject side — raw S3 bytes vs the baseline resource (no masking; no timestamp field).
        this.actualRejectBytes = readObject(BUCKET_OUTPUT, REJECT_OBJECT_KEY);
        this.expectedRejectBytes = readClasspathBytes("/expected/dalyrejs-expected.txt");
        this.s3RejectRecordCount = (REJECT_STRIDE == 0) ? 0 : actualRejectBytes.length / REJECT_STRIDE;

        // Persisted-count read-back (posted branch).
        final Integer postCount = jdbcTemplate.queryForObject(SQL_COUNT_TRANSACTION, Integer.class);
        this.dbTransactionCount = (postCount == null) ? -1 : postCount.intValue();

        // Posted side — re-materialize the DB rows to the 350-byte CVTRA05Y layout, then mask both sides.
        final List<String> postedRecords = exportPostedRecords();
        this.actualPostedBytes = joinRecords(postedRecords);
        this.expectedPostedBytes = readClasspathBytes("/expected/transact-expected.txt");
        this.maskedActualPostedBytes = maskProcTimestamps(actualPostedBytes, postedRecords.size());
        this.maskedExpectedPostedBytes =
                maskProcTimestamps(expectedPostedBytes, expectedPostedBytes.length / TRANSACTION_STRIDE);
    }

    // ===============================================================================================
    // Phase 3 — job status.
    // ===============================================================================================

    /**
     * The job must finish {@link BatchStatus#COMPLETED}: {@code CBTRN02C} treats a business reject as
     * data (it writes {@code DALYREJS} and continues), never as an abend, so a run that rejects records
     * still completes successfully.
     */
    @Test
    @Order(1)
    @DisplayName("Phase 3 — postTransactionJob completes with BatchStatus.COMPLETED")
    void jobCompletes() {
        assertEquals(BatchStatus.COMPLETED, jobExecution.getStatus(),
                "posting job must COMPLETE (business rejects are data, not an abend)");
    }

    // ===============================================================================================
    // Phase 4 — the 262 / 38 split (the #1 correctness assertion) and read/write conservation.
    // ===============================================================================================

    /**
     * Exactly {@value #EXPECTED_POSTED_COUNT} transactions are persisted to the master table. Combined
     * with the empty-table precondition and the stateful account baseline, this is the definitive
     * stateful-posting assertion (262, not the stateless 287).
     */
    @Test
    @Order(2)
    @DisplayName("Phase 4 — 262 posted rows persisted; read/write counts conserve all 300 input rows")
    void postedCountAndRowConservation() {
        assertEquals(EXPECTED_POSTED_COUNT, dbTransactionCount,
                "exactly " + EXPECTED_POSTED_COUNT + " transactions must be posted (stateful split; a "
                        + "stateless engine would wrongly yield 287)");
        assertEquals(EXPECTED_DAILY_COUNT, stepReadCount,
                "the step must read every one of the " + EXPECTED_DAILY_COUNT + " seeded daily rows");
        assertEquals(EXPECTED_DAILY_COUNT, stepWriteCount,
                "every read item (posted or rejected) must reach the writer; no record is silently filtered");
        assertEquals(EXPECTED_DAILY_COUNT, EXPECTED_POSTED_COUNT + EXPECTED_REJECT_COUNT,
                "posted + rejected must reconstruct the full input population");
    }

    /**
     * Exactly {@value #EXPECTED_REJECT_COUNT} records are rejected. The count is asserted from two
     * independent sources that must agree: the writer-promoted step-execution-context value
     * ({@code "rejectCount"}) and the number of 430-byte records in the S3 reject object.
     */
    @Test
    @Order(3)
    @DisplayName("Phase 4 — 38 rejects; step-context count and S3 reject-record count agree")
    void rejectCountFromTwoIndependentSources() {
        assertEquals(EXPECTED_REJECT_COUNT, stepRejectCount,
                "the writer must promote a reject count of " + EXPECTED_REJECT_COUNT
                        + " into the step execution context (key '" + REJECT_COUNT_KEY + "')");
        assertEquals(0, actualRejectBytes.length % REJECT_STRIDE,
                "the reject object length must be a whole multiple of the 431-byte record+LF stride");
        assertEquals(EXPECTED_REJECT_COUNT, s3RejectRecordCount,
                "the S3 reject object must contain exactly " + EXPECTED_REJECT_COUNT + " records");
        assertEquals(stepRejectCount, s3RejectRecordCount,
                "the step-context reject count and the S3 reject-record count must agree");
    }

    // ===============================================================================================
    // Phase 5 — RETURN-CODE 4 (COND-code) parity via the production decider.
    // ===============================================================================================

    /**
     * Feeds the completed posting step to the production {@code postingRejectDecider} (from
     * {@code BatchConfig}) and asserts it yields {@value #STATUS_COMPLETED_WITH_REJECTS}. This is the
     * migrated form of {@code CBTRN02C}'s {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}: because
     * this run rejected records, the reject-gate fires (RC=4 warning), which the master pipeline maps to
     * a non-aborting {@code COMPLETED_WITH_REJECTS} transition.
     */
    @Test
    @Order(4)
    @DisplayName("Phase 5 — reject-gate decider yields COMPLETED_WITH_REJECTS (RETURN-CODE 4 parity)")
    void returnCode4Parity() {
        final FlowExecutionStatus status = postingRejectDecider.decide(jobExecution, postStep);
        assertEquals(STATUS_COMPLETED_WITH_REJECTS, status.getName(),
                "with " + EXPECTED_REJECT_COUNT + " rejects the decider must signal RETURN-CODE 4 "
                        + "(COMPLETED_WITH_REJECTS)");
    }

    // ===============================================================================================
    // Phase 6 — reject byte-parity (strict; no masking — reject records carry no timestamp).
    // ===============================================================================================

    /**
     * The S3 reject object must be byte-identical to {@code dalyrejs-expected.txt}: equal length
     * ({@value #EXPECTED_REJECT_FILE_BYTES} bytes), equal MD5 ({@value #EXPECTED_REJECT_FILE_MD5}), and
     * equal bytes. Every 430-byte record's trailer is additionally spot-asserted to carry reason
     * {@code 0102} and the {@code "OVERLIMIT TRANSACTION"} description padded to 76 characters.
     */
    @Test
    @Order(5)
    @DisplayName("Phase 6 — reject file is byte-identical to dalyrejs-expected.txt; every trailer is 0102 OVERLIMIT")
    void rejectByteParity() {
        assertEquals(EXPECTED_REJECT_FILE_BYTES, expectedRejectBytes.length,
                "reject baseline resource size drifted from the documented " + EXPECTED_REJECT_FILE_BYTES);
        assertEquals(EXPECTED_REJECT_FILE_MD5, md5Hex(expectedRejectBytes),
                "reject baseline resource MD5 drifted from the documented fingerprint");
        assertEquals(EXPECTED_REJECT_FILE_BYTES, actualRejectBytes.length,
                "actual S3 reject object size must equal the documented baseline size");
        assertArrayEquals(expectedRejectBytes, actualRejectBytes,
                "the S3 reject object must be byte-identical to dalyrejs-expected.txt (Gate 1)");

        final String expectedDescription = padRight(REJECT_DESC_TEXT, REJECT_DESC_LENGTH);
        final String actualText = new String(actualRejectBytes, StandardCharsets.ISO_8859_1);
        for (int record = 0; record < EXPECTED_REJECT_COUNT; record++) {
            final int base = record * REJECT_STRIDE;
            final String reason = actualText.substring(base + REJECT_REASON_OFFSET, base + REJECT_DESC_OFFSET);
            final String description =
                    actualText.substring(base + REJECT_DESC_OFFSET, base + REJECT_RECORD_LENGTH);
            assertEquals(REJECT_REASON_CODE, reason,
                    "reject record " + record + " must carry reason code " + REJECT_REASON_CODE);
            assertEquals(expectedDescription, description,
                    "reject record " + record + " must carry the OVERLIMIT description padded to "
                            + REJECT_DESC_LENGTH);
        }
    }

    // ===============================================================================================
    // Phase 7 — posted byte-parity (with TRAN-PROC-TS masked on both sides).
    // ===============================================================================================

    /**
     * The posted transactions, re-materialized from the {@code transaction} table in {@code TRAN-ID}
     * order to their 350-byte {@code CVTRA05Y} layout, must be byte-identical to
     * {@code transact-expected.txt} once the non-deterministic {@code TRAN-PROC-TS} field
     * ({@code [304:330]}) is masked to {@value #PROC_TS_SENTINEL} on both sides. The unmasked baseline
     * fingerprint ({@value #EXPECTED_POSTED_FILE_MD5}) is verified first, then 262 records and the three
     * lowest {@code TRAN-ID}s are spot-checked.
     */
    @Test
    @Order(6)
    @DisplayName("Phase 7 — posted output is byte-identical to transact-expected.txt (TRAN-PROC-TS masked)")
    void postedByteParityWithProcTsMask() {
        assertEquals(EXPECTED_POSTED_FILE_BYTES, expectedPostedBytes.length,
                "posted baseline resource size drifted from the documented " + EXPECTED_POSTED_FILE_BYTES);
        assertEquals(EXPECTED_POSTED_FILE_MD5, md5Hex(expectedPostedBytes),
                "posted baseline resource MD5 drifted from the documented (unmasked) fingerprint");
        assertEquals(EXPECTED_POSTED_FILE_BYTES, actualPostedBytes.length,
                "re-materialized posted output size must equal the documented baseline size");
        assertEquals(EXPECTED_POSTED_COUNT, actualPostedBytes.length / TRANSACTION_STRIDE,
                "re-materialized posted output must contain exactly " + EXPECTED_POSTED_COUNT + " records");

        assertArrayEquals(maskedExpectedPostedBytes, maskedActualPostedBytes,
                "the posted transactions must be byte-identical to transact-expected.txt after masking "
                        + "TRAN-PROC-TS on both sides (Gate 1)");

        final String actualText = new String(actualPostedBytes, StandardCharsets.ISO_8859_1);
        for (int i = 0; i < FIRST_THREE_TRAN_IDS.size(); i++) {
            final int base = i * TRANSACTION_STRIDE;
            final String tranId = actualText.substring(base, base + FIRST_THREE_TRAN_IDS.get(i).length());
            assertEquals(FIRST_THREE_TRAN_IDS.get(i), tranId,
                    "posted record " + i + " must start with the expected TRAN-ID (ascending id order)");
        }
    }

    // ===============================================================================================
    // Phase 8 — Gate-1 comparison report (deliverable).
    // ===============================================================================================

    /**
     * Emits the Gate-1 comparison report deliverable capturing the input artifact, the expected and
     * actual counts and hashes, and the PASS/FAIL match status for both baselines. The report is written
     * to {@code target/gate1/posttran-comparison.txt} and echoed at {@code INFO}. It is intentionally the
     * last ordered test so it summarizes a fully verified run; it also re-asserts the top-level match so
     * a green report can never coexist with a failed comparison.
     *
     * @throws IOException if the report directory or file cannot be written
     */
    @Test
    @Order(7)
    @DisplayName("Phase 8 — emit the Gate-1 comparison report (input, expected, actual, match status)")
    void emitGate1ComparisonReport() throws IOException {
        final boolean rejectMatch = actualRejectBytes.length == expectedRejectBytes.length
                && md5Hex(actualRejectBytes).equals(md5Hex(expectedRejectBytes));
        final boolean postedMatch = java.util.Arrays.equals(maskedActualPostedBytes, maskedExpectedPostedBytes);

        final String report = buildComparisonReport(rejectMatch, postedMatch);
        final Path dir = Path.of(GATE1_REPORT_DIR);
        Files.createDirectories(dir);
        final Path reportPath = dir.resolve(GATE1_REPORT_FILE);
        Files.write(reportPath, report.getBytes(StandardCharsets.UTF_8));

        log.info("Gate 1 comparison report written to {}\n{}", reportPath.toAbsolutePath(), report);

        assertTrue(rejectMatch, "Gate-1 reject-file comparison must PASS");
        assertTrue(postedMatch, "Gate-1 posted-file comparison must PASS");
    }

    // ===============================================================================================
    // Phase 9 — teardown (AAP §0.7.7): remove exactly the bucket this test created.
    // ===============================================================================================

    /**
     * Recursively deletes the reject bucket this test created so no LocalStack state leaks to other
     * {@code *IT}s. The base helper is idempotent, so this is safe even if provisioning failed part-way.
     */
    @AfterAll
    void tearDown() {
        deleteBucketRecursively(BUCKET_OUTPUT);
        log.info("Gate1 IT: torn down S3 bucket '{}'", BUCKET_OUTPUT);
    }

    // ===============================================================================================
    // Helpers — output capture, byte-exact re-serialization, masking, hashing, and reporting.
    // ===============================================================================================

    /**
     * Reads a required classpath resource fully into a byte array (raw, no charset decode/encode so the
     * on-disk bytes of the baseline are compared verbatim).
     *
     * @param resource the absolute classpath resource path (for example {@code /expected/...})
     * @return the resource bytes
     * @throws IllegalStateException if the resource is absent from the classpath
     * @throws UncheckedIOException  if the resource cannot be read
     */
    private byte[] readClasspathBytes(final String resource) {
        try (InputStream in = PostTransactionJobIT.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                        "required test resource not found on the classpath: " + resource);
            }
            return in.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException("failed to read classpath resource " + resource, e);
        }
    }

    /**
     * Re-materializes every posted transaction from the {@code transaction} master, in {@code TRAN-ID}
     * order, to its 350-byte {@code CVTRA05Y} fixed-width representation. The row mapper reads each JDBC
     * column with its declared type ({@code INTEGER}/{@code BIGINT}/{@code NUMERIC(11,2)}/{@code VARCHAR})
     * and serializes it with the same field renderers the production {@code PostTransactionItemWriter}
     * uses, so the exported bytes are identical to what a fixed-width writer would emit.
     *
     * @return the serialized 350-character records, one per posted transaction, in ascending id order
     */
    private List<String> exportPostedRecords() {
        return jdbcTemplate.query(SQL_SELECT_POSTED, (rs, rowNum) -> serializeTransactionRecord(rs));
    }

    /**
     * Serializes a single {@code transaction} row to the 350-byte {@code CVTRA05Y} layout, field-by-field
     * in copybook-declared order. Mirrors {@code PostTransactionItemWriter#formatDailyTransactionRecord}
     * (the layouts of {@code CVTRA05Y} and {@code CVTRA06Y} are identical) so the exported posted output
     * is byte-compatible with {@code transact-expected.txt}.
     *
     * @param rs the current result-set row positioned by the row mapper; never {@code null}
     * @return a 350-character record (one character per byte under ISO-8859-1)
     * @throws SQLException      if a column cannot be read
     * @throws IllegalStateException if the assembled record is not exactly 350 characters
     */
    private static String serializeTransactionRecord(final ResultSet rs) throws SQLException {
        final StringBuilder sb = new StringBuilder(TRANSACTION_RECORD_LENGTH);
        sb.append(alphanumeric(rs.getString("tran_id"), 16));                 // TRAN-ID            X(16)
        sb.append(alphanumeric(rs.getString("tran_type_cd"), 2));             // TRAN-TYPE-CD       X(02)
        sb.append(unsignedNumeric(nullableInteger(rs, "tran_cat_cd"), 4));    // TRAN-CAT-CD        9(04)
        sb.append(alphanumeric(rs.getString("tran_source"), 10));             // TRAN-SOURCE        X(10)
        sb.append(alphanumeric(rs.getString("tran_desc"), 100));              // TRAN-DESC          X(100)
        sb.append(signedAmount(rs.getBigDecimal("tran_amt")));                // TRAN-AMT           S9(09)V99
        sb.append(unsignedNumeric(nullableLong(rs, "tran_merchant_id"), 9));  // TRAN-MERCHANT-ID   9(09)
        sb.append(alphanumeric(rs.getString("tran_merchant_name"), 50));      // TRAN-MERCHANT-NAME X(50)
        sb.append(alphanumeric(rs.getString("tran_merchant_city"), 50));      // TRAN-MERCHANT-CITY X(50)
        sb.append(alphanumeric(rs.getString("tran_merchant_zip"), 10));       // TRAN-MERCHANT-ZIP  X(10)
        sb.append(alphanumeric(rs.getString("tran_card_num"), 16));           // TRAN-CARD-NUM      X(16)
        sb.append(alphanumeric(rs.getString("tran_orig_ts"), 26));            // TRAN-ORIG-TS       X(26)
        sb.append(alphanumeric(rs.getString("tran_proc_ts"), 26));            // TRAN-PROC-TS       X(26)
        sb.append(" ".repeat(20));                                            // FILLER             X(20)
        final String record = sb.toString();
        if (record.length() != TRANSACTION_RECORD_LENGTH) {
            throw new IllegalStateException("serialized transaction record length " + record.length()
                    + " != expected " + TRANSACTION_RECORD_LENGTH
                    + " for tranId=" + rs.getString("tran_id"));
        }
        return record;
    }

    /**
     * Reads an {@code INTEGER} column as a nullable {@link Integer} (SQL {@code NULL} renders as
     * {@code null}, so {@link #unsignedNumeric(Number, int)} produces an all-zeros field — matching the
     * production writer's handling of a {@code null} numeric).
     */
    private static Integer nullableInteger(final ResultSet rs, final String column) throws SQLException {
        final int value = rs.getInt(column);
        return rs.wasNull() ? null : Integer.valueOf(value);
    }

    /**
     * Reads a {@code BIGINT} column as a nullable {@link Long} (see {@link #nullableInteger}).
     */
    private static Long nullableLong(final ResultSet rs, final String column) throws SQLException {
        final long value = rs.getLong(column);
        return rs.wasNull() ? null : Long.valueOf(value);
    }

    /**
     * Concatenates fixed-width records, appending one {@link #RECORD_SEPARATOR} after each, and encodes
     * the result as ISO-8859-1 (one byte per character) so the byte length is
     * {@code records * (recordLength + 1)}.
     *
     * @param records the fixed-width records to join
     * @return the joined, newline-terminated bytes
     */
    private static byte[] joinRecords(final List<String> records) {
        final StringBuilder sb = new StringBuilder(records.size() * TRANSACTION_STRIDE);
        for (final String record : records) {
            sb.append(record).append(RECORD_SEPARATOR);
        }
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Returns a copy of {@code data} with the {@code TRAN-PROC-TS} field of every record overwritten by
     * {@value #PROC_TS_SENTINEL}. Applied identically to both the actual and the expected posted bytes so
     * the only non-deterministic field (the processing timestamp) cannot cause a false mismatch.
     *
     * @param data        the newline-terminated posted bytes ({@link #TRANSACTION_STRIDE}-byte stride)
     * @param recordCount the number of records to mask
     * @return a masked copy of {@code data}
     */
    private static byte[] maskProcTimestamps(final byte[] data, final int recordCount) {
        final byte[] masked = data.clone();
        final byte[] sentinel = PROC_TS_SENTINEL.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < recordCount; i++) {
            final int offset = i * TRANSACTION_STRIDE + PROC_TS_OFFSET;
            System.arraycopy(sentinel, 0, masked, offset, PROC_TS_LENGTH);
        }
        return masked;
    }

    /**
     * Renders {@code value} left-justified and space-padded (or right-truncated) to {@code width} — the
     * COBOL alphanumeric {@code MOVE} semantics. Mirrors {@code PostTransactionItemWriter.alphanumeric}.
     */
    private static String alphanumeric(final String value, final int width) {
        final String v = (value == null) ? "" : value;
        if (v.length() == width) {
            return v;
        }
        if (v.length() > width) {
            return v.substring(0, width);
        }
        return v + " ".repeat(width - v.length());
    }

    /**
     * Renders an unsigned numeric ({@code PIC 9(width)}) right-justified, zero-padded (or high-order
     * truncated), using the magnitude. Mirrors {@code PostTransactionItemWriter.unsignedNumeric}.
     */
    private static String unsignedNumeric(final Number value, final int width) {
        final long magnitude = (value == null) ? 0L : Math.abs(value.longValue());
        final String digits = Long.toString(magnitude);
        if (digits.length() == width) {
            return digits;
        }
        if (digits.length() > width) {
            return digits.substring(digits.length() - width);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Renders {@code TRAN-AMT} ({@code PIC S9(09)V99 USAGE DISPLAY}) as an 11-byte zoned-decimal value
     * with an overpunch sign on the trailing digit. Mirrors {@code PostTransactionItemWriter.signedAmount}
     * byte-for-byte, preserving decimal fidelity ({@link BigDecimal}; never {@code float}/{@code double}).
     */
    private static String signedAmount(final BigDecimal amount) {
        final BigDecimal value = (amount == null) ? BigDecimal.ZERO : amount;
        final boolean negative = value.signum() < 0;
        final BigInteger unscaled =
                value.abs().setScale(AMOUNT_SCALE, RoundingMode.HALF_UP).unscaledValue();
        String digits = unscaled.toString();
        if (digits.length() < AMOUNT_TOTAL_DIGITS) {
            digits = "0".repeat(AMOUNT_TOTAL_DIGITS - digits.length()) + digits;
        } else if (digits.length() > AMOUNT_TOTAL_DIGITS) {
            digits = digits.substring(digits.length() - AMOUNT_TOTAL_DIGITS);
        }
        final int lastDigit = digits.charAt(AMOUNT_TOTAL_DIGITS - 1) - '0';
        final char overpunch = negative ? NEGATIVE_OVERPUNCH[lastDigit] : POSITIVE_OVERPUNCH[lastDigit];
        return digits.substring(0, AMOUNT_TOTAL_DIGITS - 1) + overpunch;
    }

    /**
     * Left-justifies and space-pads {@code value} to {@code width} (delegates to {@link #alphanumeric});
     * used to reproduce the reject trailer's {@code PIC X(76)} description for the spot-check.
     */
    private static String padRight(final String value, final int width) {
        return alphanumeric(value, width);
    }

    /**
     * Computes the lower-case hexadecimal MD5 of {@code data}. Used to fingerprint both baselines and the
     * actual reject bytes for the parity assertions and the Gate-1 report.
     *
     * @param data the bytes to hash
     * @return the 32-character lower-case hex MD5 digest
     */
    private static String md5Hex(final byte[] data) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("MD5");
            final byte[] hash = digest.digest(data);
            final StringBuilder hex = new StringBuilder(hash.length * 2);
            for (final byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm unavailable in this JVM", e);
        }
    }

    /**
     * Builds the human-readable Gate-1 comparison report: the input artifact, the expected and actual
     * counts/hashes for both baselines, and the PASS/FAIL match status. Satisfies the Gate-1 deliverable
     * "comparison report (input, expected, Java output, match status)".
     *
     * @param rejectMatch whether the reject file matched its baseline
     * @param postedMatch whether the (masked) posted file matched its baseline
     * @return the formatted report text
     */
    private String buildComparisonReport(final boolean rejectMatch, final boolean postedMatch) {
        final String actualRejectMd5 = md5Hex(actualRejectBytes);
        final String maskedActualPostedMd5 = md5Hex(maskedActualPostedBytes);
        final String maskedExpectedPostedMd5 = md5Hex(maskedExpectedPostedBytes);
        final String overall = (rejectMatch && postedMatch) ? "PASS" : "FAIL";
        return String.join(System.lineSeparator(),
                "================================================================================",
                " Gate 1 / Gate 4 — POSTTRAN End-to-End Boundary Comparison Report",
                " Source (read-only @ SHA 27d6c6f): app/cbl/CBTRN02C.cbl + app/jcl/POSTTRAN.jcl",
                "================================================================================",
                " INPUT ARTIFACT",
                "   fixture ................ app/data/ASCII/dailytran.txt",
                "   daily_transaction rows . " + EXPECTED_DAILY_COUNT,
                "   correlationId .......... " + CORRELATION_ID_VALUE,
                "",
                " JOB OUTCOME",
                "   status ................. " + jobExecution.getStatus(),
                "   exitCode ............... " + jobExecution.getExitStatus().getExitCode(),
                "   step read count ........ " + stepReadCount,
                "   step write count ....... " + stepWriteCount,
                "",
                " POSTED (transact-expected.txt)",
                "   expected records ....... " + EXPECTED_POSTED_COUNT,
                "   actual records ......... " + (actualPostedBytes.length / TRANSACTION_STRIDE),
                "   actual DB rowcount ..... " + dbTransactionCount,
                "   expected bytes ......... " + EXPECTED_POSTED_FILE_BYTES,
                "   actual bytes ........... " + actualPostedBytes.length,
                "   masked expected MD5 .... " + maskedExpectedPostedMd5,
                "   masked actual MD5 ...... " + maskedActualPostedMd5,
                "   TRAN-PROC-TS mask ...... [" + PROC_TS_OFFSET + ":" + (PROC_TS_OFFSET + PROC_TS_LENGTH)
                        + "] -> " + PROC_TS_SENTINEL,
                "   match .................. " + (postedMatch ? "PASS" : "FAIL"),
                "",
                " REJECTED (dalyrejs-expected.txt)",
                "   expected records ....... " + EXPECTED_REJECT_COUNT,
                "   actual records ......... " + s3RejectRecordCount,
                "   step-context rejectCount " + stepRejectCount,
                "   reject reason .......... " + REJECT_REASON_CODE + " " + REJECT_DESC_TEXT,
                "   expected bytes / MD5 ... " + EXPECTED_REJECT_FILE_BYTES + " / " + EXPECTED_REJECT_FILE_MD5,
                "   actual bytes / MD5 ..... " + actualRejectBytes.length + " / " + actualRejectMd5,
                "   match .................. " + (rejectMatch ? "PASS" : "FAIL"),
                "",
                " RETURN-CODE PARITY",
                "   reject-gate decision ... " + postingRejectDecider.decide(jobExecution, postStep).getName()
                        + " (RC=4 when rejects > 0)",
                "================================================================================",
                " OVERALL: " + overall,
                "================================================================================");
    }
}
