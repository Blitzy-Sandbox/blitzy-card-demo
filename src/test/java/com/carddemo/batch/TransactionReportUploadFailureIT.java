package com.carddemo.batch;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.carddemo.entity.Transaction;
import com.carddemo.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for QA finding <strong>F4</strong> (MAJOR) &mdash; the batch report writer used to
 * perform its terminal S3 upload inside {@code ItemStream.close()}, whose exception
 * {@code AbstractStep} swallows <em>after</em> it has already persisted the step/job as
 * {@code COMPLETED}. A failed upload therefore surfaced as a <strong>false success</strong> (silent
 * data loss). Decision {@code D-027} moved the flush+upload into
 * {@link org.springframework.batch.core.StepExecutionListener#afterStep(StepExecution)} &mdash; which
 * runs <em>before</em> the status is persisted &mdash; where an upload failure now sets
 * {@code BatchStatus.FAILED} and returns {@code ExitStatus.FAILED}, failing the step and the job.
 *
 * <p>This test drives the <em>real</em> {@code transactionReportJob} against real PostgreSQL&nbsp;16
 * and real LocalStack S3 (zero live AWS). It launches the job directly through the {@link JobLauncher}
 * (rather than via SQS) so the terminal {@link JobExecution} / {@link StepExecution} statuses can be
 * asserted deterministically and synchronously.</p>
 *
 * <h2>What this proves</h2>
 * <ol>
 *   <li><strong>Failure surfaces (F4 regression):</strong> with the output bucket <em>absent</em>, the
 *       chunk phase still runs (the reader/processor touch only the database) and buffers the report,
 *       but the {@code afterStep} upload fails &mdash; so both the {@code transactionReportStep} step
 *       execution and the job end {@code FAILED} (never a false {@code COMPLETED}), a failure exception
 *       is recorded, and no {@code tranrept.dat} object is left behind.</li>
 *   <li><strong>Happy path intact (byte-parity):</strong> with the output bucket present, the same job
 *       ends {@code COMPLETED} and writes the fixed-width {@code tranrept.dat} (every record exactly
 *       {@value #RECORD_LENGTH} chars, {@code LRECL=133}) with the expected grand total &mdash; proving
 *       the F4 fix did not regress the successful path.</li>
 * </ol>
 *
 * <h2>COBOL/JCL lineage (reference-only, source commit SHA {@code 27d6c6f}; NOT copied)</h2>
 * <p>The legacy {@code TRANREPT} job ({@code CBTRN03C}) wrote its report to a sequential dataset; a
 * write/close I/O failure abended the job step (a non-zero JCL condition code) and the pipeline
 * stopped &mdash; it never reported success on a failed write. Preserving that abend-on-write-failure
 * semantics is exactly what {@code D-027} restores in the Spring Batch translation.</p>
 *
 * @see TransactionReportItemWriter
 * @see TransactionReportJob
 * @see AbstractBatchIntegrationTest
 */
@DisplayName("F4 IT — transactionReportJob fails (not false COMPLETED) when the S3 upload fails; happy path intact")
class TransactionReportUploadFailureIT extends AbstractBatchIntegrationTest {

    // ---------------------------------------------------------------------------------------------
    // Report artifact / layout contract constants (mirror the production defaults they exercise)
    // ---------------------------------------------------------------------------------------------

    /** Stable S3 object key of the report artifact ({@code carddemo.batch.report.object-key}). */
    private static final String REPORT_OBJECT_KEY = "tranrept.dat";

    /** Bean name of the launched Spring Batch step whose status this test asserts. */
    private static final String REPORT_STEP_NAME = "transactionReportStep";

    /** Inclusive reporting-window start (JCL {@code PARM-START-DATE}). */
    private static final String WINDOW_START_DATE = "2022-01-01";

    /** Inclusive reporting-window end (JCL {@code PARM-END-DATE}). */
    private static final String WINDOW_END_DATE = "2022-07-06";

    /** Fixed record width of the {@code TRANREPT} dataset ({@code LRECL=133}). */
    private static final int RECORD_LENGTH = 133;

    /** Character offset of the amount field on a total line ({@code pad("Grand Total",11)} + {@code X(86)'.'}). */
    private static final int TOTAL_AMOUNT_OFFSET = 97;

    /** Width of the edited amount field on a total line ({@code PIC +ZZZ,ZZZ,ZZZ.ZZ}). */
    private static final int TOTAL_AMOUNT_WIDTH = 15;

    /** Leading label of the grand-total record; uniquely identifies it. */
    private static final String GRAND_TOTAL_LABEL = "Grand Total";

    // ---------------------------------------------------------------------------------------------
    // Deterministic seed data (a small, known set of in-window, enrichable transactions)
    // ---------------------------------------------------------------------------------------------

    /** Card number present in the V3-seeded {@code card_xref} (resolves to account&nbsp;2). */
    private static final String SEED_CARD_NUMBER = "0923877193247330";

    /** Transaction type code {@code '01'} (V3 {@code transaction_type} = "Purchase"). */
    private static final String SEED_TYPE_CODE = "01";

    /** Transaction category code {@code 1} (V3 {@code transaction_category_type} ('01',1)). */
    private static final int SEED_CATEGORY_CODE = 1;

    /** Processing timestamp whose leading {@code yyyy-MM-dd} falls inside the reporting window. */
    private static final String IN_WINDOW_PROC_TS = "2022-06-10-12.00.00.000000";

    /** First seeded transaction id (16 chars, {@code TRAN-ID PIC X(16)}). */
    private static final String SEED_TRAN_ID_1 = "IT000000000UF001";

    /** Second seeded transaction id (16 chars, {@code TRAN-ID PIC X(16)}). */
    private static final String SEED_TRAN_ID_2 = "IT000000000UF002";

    /** First seeded amount (scale&nbsp;2). */
    private static final BigDecimal SEED_AMOUNT_1 = new BigDecimal("100.00");

    /** Second seeded amount (scale&nbsp;2). */
    private static final BigDecimal SEED_AMOUNT_2 = new BigDecimal("250.50");

    /** Expected grand total = {@link #SEED_AMOUNT_1} + {@link #SEED_AMOUNT_2} (scale&nbsp;2). */
    private static final BigDecimal EXPECTED_GRAND_TOTAL = new BigDecimal("350.50");

    // ---------------------------------------------------------------------------------------------
    // Injected collaborators
    // ---------------------------------------------------------------------------------------------

    /** The synchronous, auto-configured Spring Batch launcher used to run the report job directly. */
    @Autowired
    private JobLauncher jobLauncher;

    /** The real report job under test (by bean name, per {@code TransactionReportJob}). */
    @Autowired
    @Qualifier("transactionReportJob")
    private Job transactionReportJob;

    /** Repository used to seed and clear the known in-window {@link Transaction} rows. */
    @Autowired
    private TransactionRepository transactionRepository;

    // ---------------------------------------------------------------------------------------------
    // Per-test seeding (both tests use the identical in-window input)
    // ---------------------------------------------------------------------------------------------

    /**
     * Seeds two deterministic in-window transactions on a V3-known card so the report processor keeps
     * them (grand total {@link #EXPECTED_GRAND_TOTAL}). The rows are committed (the test is not
     * {@code @Transactional}) so the batch job, running on its own connection, can read them.
     */
    @BeforeEach
    void seedInWindowTransactions() {
        transactionRepository.deleteAll();
        transactionRepository.saveAll(List.of(
                newSeededTransaction(SEED_TRAN_ID_1, SEED_AMOUNT_1),
                newSeededTransaction(SEED_TRAN_ID_2, SEED_AMOUNT_2)));
    }

    /**
     * Removes the seeded rows and the output bucket so no state leaks to sibling integration tests
     * sharing the singleton containers (AAP&nbsp;&sect;0.7.7). Both deletions are idempotent.
     */
    @AfterEach
    void teardown() {
        transactionRepository.deleteAll();
        deleteBucketRecursively(BUCKET_OUTPUT);
    }

    // ---------------------------------------------------------------------------------------------
    // Test 1 — F4 regression: a failed S3 upload must FAIL the step and job (no false COMPLETED)
    // ---------------------------------------------------------------------------------------------

    /**
     * With the output bucket absent, launching the report job must end {@code FAILED} at both the step
     * and job level, record a failure exception, and leave no report object &mdash; the exact opposite
     * of the pre-fix behaviour where the upload failure was swallowed and the job reported
     * {@code COMPLETED}.
     */
    @Test
    @DisplayName("Output bucket absent → transactionReportStep and job end FAILED, no tranrept.dat written")
    void uploadFailureFailsStepAndJob() throws Exception {
        // Inject the failure: guarantee the writer's target bucket does not exist at afterStep upload.
        deleteBucketRecursively(BUCKET_OUTPUT);

        final JobExecution execution = jobLauncher.run(transactionReportJob, newRunParameters());

        // (1) The job did NOT falsely complete — it failed (F4 core assertion).
        assertThat(execution.getStatus())
                .as("job must end FAILED when the report S3 upload fails (F4: no false COMPLETED)")
                .isEqualTo(BatchStatus.FAILED);

        // (2) The report step itself is FAILED (the failure was surfaced at the step boundary).
        final StepExecution reportStep = reportStepExecution(execution);
        assertThat(reportStep.getStatus())
                .as("transactionReportStep must end FAILED when its afterStep upload fails")
                .isEqualTo(BatchStatus.FAILED);

        // (3) A concrete failure exception was recorded (not merely a status flip).
        assertThat(reportStep.getFailureExceptions())
                .as("the S3 upload failure must be recorded on the step execution")
                .isNotEmpty();

        // (4) No report artifact was written (a missing bucket yields a 404, i.e. object-absent).
        assertThat(objectExists(BUCKET_OUTPUT, REPORT_OBJECT_KEY))
                .as("no tranrept.dat may be written when the upload fails")
                .isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // Test 2 — happy path intact: the F4 fix must not regress a successful upload (byte-parity)
    // ---------------------------------------------------------------------------------------------

    /**
     * With the output bucket present, the same job must complete successfully and write the fixed-width
     * {@code tranrept.dat} (every record exactly {@value #RECORD_LENGTH} chars) with the expected grand
     * total &mdash; proving the afterStep relocation preserves the byte-parity success path.
     */
    @Test
    @DisplayName("Output bucket present → job COMPLETED and writes the 133-char tranrept.dat (byte-parity intact)")
    void uploadSuccessCompletesWithByteParity() throws Exception {
        createBucket(BUCKET_OUTPUT);

        final JobExecution execution = jobLauncher.run(transactionReportJob, newRunParameters());

        // (1) The job completed successfully.
        assertThat(execution.getStatus())
                .as("job must COMPLETE when the report S3 upload succeeds")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(reportStepExecution(execution).getStatus())
                .as("transactionReportStep must COMPLETE on the happy path")
                .isEqualTo(BatchStatus.COMPLETED);

        // (2) The report artifact exists.
        assertThat(objectExists(BUCKET_OUTPUT, REPORT_OBJECT_KEY))
                .as("tranrept.dat must exist in S3 after a successful run")
                .isTrue();

        // (3) LRECL=133 parity: every non-empty record is exactly 133 characters wide.
        final String report = new String(readObject(BUCKET_OUTPUT, REPORT_OBJECT_KEY), StandardCharsets.UTF_8);
        final String[] lines = report.split("\n", -1);
        for (final String line : lines) {
            if (!line.isEmpty()) {
                assertThat(line.length())
                        .as("every report record must be exactly %d characters (LRECL=133)", RECORD_LENGTH)
                        .isEqualTo(RECORD_LENGTH);
            }
        }

        // (4) The grand total equals the sum of the seeded amounts (decimal fidelity, scale 2).
        assertThat(parseGrandTotal(lines))
                .as("grand total must equal the sum of the seeded in-window amounts")
                .isEqualByComparingTo(EXPECTED_GRAND_TOTAL);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a fresh set of identifying job parameters (unique {@code jobId} + {@code run.ts}) so each
     * launch is a distinct, restartable {@code JobInstance} — never rejected as an already-complete
     * duplicate — while carrying the reporting-window dates the processor binds.
     *
     * @return the job parameters for one launch
     */
    private static JobParameters newRunParameters() {
        return new JobParametersBuilder()
                .addString("startDate", WINDOW_START_DATE)
                .addString("endDate", WINDOW_END_DATE)
                .addString("jobId", UUID.randomUUID().toString())
                .addLong("run.ts", System.nanoTime())
                .toJobParameters();
    }

    /**
     * Extracts the {@code transactionReportStep} execution from a job execution.
     *
     * @param execution the completed/failed job execution
     * @return the report step execution
     * @throws AssertionError if the step execution is not present
     */
    private static StepExecution reportStepExecution(final JobExecution execution) {
        return execution.getStepExecutions().stream()
                .filter(se -> REPORT_STEP_NAME.equals(se.getStepName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "job execution did not contain a '" + REPORT_STEP_NAME + "' step execution"));
    }

    /**
     * Builds one in-window, enrichable {@link Transaction} for seeding (identical shape to the proven
     * {@code ReportJobLauncherIT} seed): a V3-resolvable card/type/category and an in-window timestamp.
     *
     * @param tranId the 16-character transaction id (primary key); must not be {@code null}
     * @param amount the signed transaction amount (scale&nbsp;2); must not be {@code null}
     * @return a fully populated transaction ready to persist
     */
    private static Transaction newSeededTransaction(final String tranId, final BigDecimal amount) {
        final Transaction tx = new Transaction();
        tx.setTranId(tranId);
        tx.setTranTypeCd(SEED_TYPE_CODE);
        tx.setTranCatCd(SEED_CATEGORY_CODE);
        tx.setTranSource("POS");
        tx.setTranDesc("Integration-test seeded report row (F4)");
        tx.setTranAmt(amount);
        tx.setTranCardNum(SEED_CARD_NUMBER);
        tx.setTranOrigTs(IN_WINDOW_PROC_TS);
        tx.setTranProcTs(IN_WINDOW_PROC_TS);
        return tx;
    }

    /**
     * Parses the grand-total amount from the rendered report lines. The grand-total record is the only
     * line beginning with {@code "Grand Total"}; its edited amount occupies the fixed 15-character
     * field at offset {@value #TOTAL_AMOUNT_OFFSET}. The field is one sign position
     * ({@code '+'}/{@code '-'}/space) followed by a zero-suppressed, comma-grouped numeric.
     *
     * @param lines the report split on {@code '\n'}
     * @return the parsed grand total (scale&nbsp;2)
     * @throws AssertionError if no grand-total line is present
     */
    private static BigDecimal parseGrandTotal(final String[] lines) {
        for (final String line : lines) {
            if (line.startsWith(GRAND_TOTAL_LABEL)) {
                assertThat(line.length())
                        .as("grand-total line must be at least %d characters", TOTAL_AMOUNT_OFFSET + TOTAL_AMOUNT_WIDTH)
                        .isGreaterThanOrEqualTo(TOTAL_AMOUNT_OFFSET + TOTAL_AMOUNT_WIDTH);
                final String amountField = line.substring(TOTAL_AMOUNT_OFFSET, TOTAL_AMOUNT_OFFSET + TOTAL_AMOUNT_WIDTH);
                final char sign = amountField.charAt(0);
                final String digits = amountField.substring(1).replace(",", "").replace(" ", "");
                final BigDecimal value = new BigDecimal(digits);
                return (sign == '-') ? value.negate() : value;
            }
        }
        throw new AssertionError("report did not contain a 'Grand Total' line");
    }
}
