/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.batch.job;

import com.carddemo.batch.writer.FixedWidthS3ItemWriter;
import com.carddemo.config.AwsConfig;
import com.carddemo.config.BatchConfig;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import io.awspring.cloud.s3.S3Template;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch configuration that sequences the entire CardDemo batch pipeline,
 * the Java realization of the overall batch-orchestration JCL together with
 * {@code app/jcl/PRTCATBL.jcl} (category-balance print) and the
 * {@code app/jcl/TRANBKP.jcl} {@code STEP10 COND=(4,LT)} backup gate at source
 * commit {@code 27d6c6f}.
 *
 * <p>This configuration composes the {@link Step}s exposed by the five sibling
 * job configurations ({@link PostTransactionJobConfig},
 * {@link InterestCalculationJobConfig}, {@link CombineTransactionsJobConfig},
 * {@link TransactionReportJobConfig}, {@link StatementJobConfig}) into a single
 * master flow job, {@value #JOB_NAME}. The flow reproduces the legacy step
 * sequence and condition-code routing:</p>
 *
 * <pre>
 *   POSTTRAN -&gt; [COND=(4,LT) gate] -&gt; TCATBAL backup
 *            -&gt; INTCALC -&gt; COMBTRAN
 *            -&gt; split( TRANREPT || CREASTMT )
 *            -&gt; join -&gt; PRTCATBL
 * </pre>
 *
 * <ul>
 *   <li><b>Sequence</b> &mdash; {@code postTransactionStep} (POSTTRAN /
 *       {@code CBTRN02C}) runs first, followed by {@code interestCalculationStep}
 *       (INTCALC / {@code CBACT04C}) and {@code combineTransactionsStep}
 *       (COMBTRAN sort-by-transaction-id).</li>
 *   <li><b>Parallel split</b> &mdash; {@code transactionReportStep} (TRANREPT /
 *       {@code CBTRN03C}) and {@code statementStep} (CREASTMT / {@code CBSTM03A})
 *       are independent legs that both consume the posted/combined transactions;
 *       they execute concurrently through a {@code split()} backed by the
 *       {@link #carddemoBatchTaskExecutor() carddemo batch task executor} and
 *       join before the print step.</li>
 *   <li><b>Category-balance print</b> &mdash; {@link #printCategoryBalanceStep}
 *       (PRTCATBL {@code STEP10R}) reads the transaction-category balances in
 *       account/type/category order, formats each balance line, and writes a
 *       fixed-width {@value #PRINT_RECORD_WIDTH}-byte object to the output
 *       bucket.</li>
 *   <li><b>Backup gate</b> &mdash; {@link #backupGateDecider()} reproduces the
 *       {@code TRANBKP STEP10 COND=(4,LT)} condition: the category-balance backup
 *       step runs only when the posting step's return code is {@code <= 4}
 *       (exit status {@code COMPLETED} or
 *       {@value PostTransactionJobConfig#EXIT_CODE_COMPLETED_WITH_REJECTS}). A
 *       return code greater than {@code 4} skips the backup and fails the
 *       pipeline. A return code of {@code 4} (rejects present) is tolerated and
 *       does not abort the pipeline.</li>
 * </ul>
 *
 * <p>The job is assembled with the fluent {@link JobBuilder} / {@link StepBuilder}
 * / {@link FlowBuilder} API and is never auto-run on startup
 * ({@code spring.batch.job.enabled=false}); it is launched explicitly by a
 * launcher or by the SQS FIFO report consumer (the F-011 report-submission
 * bridge). All AWS access flows through the injected
 * {@link io.awspring.cloud.s3.S3Template} against the buckets named in
 * {@link AwsConfig.AwsResourceProperties}.</p>
 *
 * <p><b>Job parameter contract.</b> Because the pipeline runs as one job
 * execution, every step-scoped late binding resolves from the same parameter
 * set. {@link #pipelineJobParameters(String, String, String)} builds the
 * parameters consumed downstream: the interest run date keyed by
 * {@link InterestCalculationJobConfig#RUN_DATE_PARAMETER_KEY} and the report
 * window bounds keyed by {@code reportStartDate} and {@code reportEndDate}.</p>
 */
@Configuration
public class BatchPipelineOrchestrator {

    /** Canonical name of the master pipeline job. */
    public static final String JOB_NAME = "carddemoBatchPipelineJob";

    /** Name of the top-level pipeline flow assembled by {@link #carddemoBatchPipelineJob}. */
    static final String PIPELINE_FLOW_NAME = "carddemoBatchPipelineFlow";

    /** Name of the category-balance print step (PRTCATBL {@code STEP10R}). */
    static final String PRINT_STEP_NAME = "printCategoryBalanceStep";

    /** Name of the category-balance backup step (gated by {@code COND=(4,LT)}). */
    static final String BACKUP_STEP_NAME = "categoryBalanceBackupStep";

    /** Bean and restart-state name of the print-step category-balance reader. */
    static final String PRINT_READER_BEAN = "printCategoryBalanceReader";

    /** Bean name of the print-step fixed-width S3 writer. */
    static final String PRINT_WRITER_BEAN = "printCategoryBalanceWriter";

    /** Bean and restart-state name of the backup-step category-balance reader. */
    static final String BACKUP_READER_BEAN = "categoryBalanceBackupReader";

    /** Bean name of the backup-step fixed-width S3 writer. */
    static final String BACKUP_WRITER_BEAN = "categoryBalanceBackupWriter";

    /** Bean name of the parallel report/statement split flow. */
    static final String SPLIT_FLOW_BEAN = "reportStatementSplitFlow";

    /** Name of the report sub-flow inside the split. */
    static final String REPORT_LEG_FLOW_NAME = "transactionReportFlow";

    /** Name of the statement sub-flow inside the split. */
    static final String STATEMENT_LEG_FLOW_NAME = "statementFlow";

    /** Bean name of the task executor backing the parallel split. */
    static final String TASK_EXECUTOR_BEAN = "carddemoBatchTaskExecutor";

    /** Thread-name prefix for the split task executor. */
    static final String TASK_EXECUTOR_THREAD_PREFIX = "carddemo-batch-";

    /** Flow status emitted by the gate when the backup must run (posting RC {@code <= 4}). */
    static final String RUN_BACKUP_STATUS = "RUN_BACKUP";

    /** Flow status emitted by the gate when the backup must be skipped (posting RC {@code > 4}). */
    static final String SKIP_BACKUP_STATUS = "SKIP_BACKUP";

    /** Exit-status pattern matching any outcome of the preceding step. */
    static final String ANY_EXIT_STATUS = "*";

    /** Fixed record width of the PRTCATBL formatted print output ({@code SORTOUT LRECL=40}). */
    static final int PRINT_RECORD_WIDTH = 40;

    /** Fixed record width of the category-balance backup unload ({@code BKUP LRECL=50}). */
    static final int BACKUP_RECORD_WIDTH = 50;

    /** Width of the zoned account id ({@code TRANCAT-ACCT-ID PIC 9(11)}). */
    private static final int ACCT_ID_WIDTH = 11;

    /** Width of the transaction type code ({@code TRANCAT-TYPE-CD PIC X(02)}). */
    private static final int TYPE_CD_WIDTH = 2;

    /** Width of the zoned category code ({@code TRANCAT-CD PIC 9(04)}). */
    private static final int CAT_CD_WIDTH = 4;

    /** Integer-digit count of the balance ({@code TRAN-CAT-BAL PIC S9(09)V99}). */
    private static final int BALANCE_INT_DIGITS = 9;

    /** Fractional-digit count (scale) of the balance. */
    private static final int BALANCE_SCALE = 2;

    /** Total zoned digit count of the balance used by the backup unload (9 + 2). */
    private static final int BALANCE_ZONED_WIDTH = BALANCE_INT_DIGITS + BALANCE_SCALE;

    /**
     * Zoned-decimal overpunch characters for a non-negative trailing digit
     * {@code 0-9}, encoding the sign into the final byte of a signed
     * {@code PIC S9(n)V99 USAGE DISPLAY} field (positive {@code 0} renders as
     * {@code '{'}, {@code 1-9} as {@code 'A'-'I'}).
     */
    private static final char[] POSITIVE_OVERPUNCH =
            {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

    /**
     * Zoned-decimal overpunch characters for a negative trailing digit
     * {@code 0-9} (negative {@code 0} renders as {@code '}'}, {@code 1-9} as
     * {@code 'J'-'R'}).
     */
    private static final char[] NEGATIVE_OVERPUNCH =
            {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

    /** Single-blank inter-field separator emitted by the DFSORT {@code X} positions. */
    private static final String FIELD_SEPARATOR = " ";

    /** Object-key prefix for the PRTCATBL formatted print output. */
    private static final String PRINT_OBJECT_PREFIX = "category-balance/PRTCATBL-";

    /** Object-key prefix for the category-balance backup unload. */
    private static final String BACKUP_OBJECT_PREFIX = "category-balance-backup/TCATBAL-BKUP-";

    /** Object-key suffix for the formatted print output. */
    private static final String PRINT_OBJECT_SUFFIX = ".txt";

    /** Object-key suffix for the backup unload. */
    private static final String BACKUP_OBJECT_SUFFIX = ".dat";

    /** Formatter producing a sortable UTC timestamp token for object keys. */
    private static final DateTimeFormatter OBJECT_KEY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    /** Gate status returned when the posting return code is {@code <= 4}. */
    private static final FlowExecutionStatus RUN_BACKUP = new FlowExecutionStatus(RUN_BACKUP_STATUS);

    /** Gate status returned when the posting return code is {@code > 4}. */
    private static final FlowExecutionStatus SKIP_BACKUP = new FlowExecutionStatus(SKIP_BACKUP_STATUS);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final Step postTransactionStep;
    private final Step interestCalculationStep;
    private final Step combineTransactionsStep;
    private final Step transactionReportStep;
    private final Step statementStep;
    private final BatchConfig.BatchTuningProperties batchTuningProperties;
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;
    private final S3Template s3Template;
    private final AwsConfig.AwsResourceProperties awsResourceProperties;

    /**
     * Creates the pipeline orchestrator with every collaborator injected by the
     * container. The five sibling {@link Step}s are resolved by their canonical
     * bean names so the orchestrator can compose them into the master flow.
     *
     * @param jobRepository                        the Spring Batch job repository backing
     *                                             the master job and orchestrator-owned steps
     * @param transactionManager                   the platform transaction manager providing
     *                                             the per-chunk commit boundary for the
     *                                             orchestrator-owned steps
     * @param postTransactionStep                  the daily-posting step (POSTTRAN /
     *                                             {@code CBTRN02C}); its exit status feeds the
     *                                             backup gate
     * @param interestCalculationStep              the interest-calculation step (INTCALC /
     *                                             {@code CBACT04C})
     * @param combineTransactionsStep              the transaction-combine step (COMBTRAN sort
     *                                             by transaction id)
     * @param transactionReportStep                the transaction-report step (TRANREPT /
     *                                             {@code CBTRN03C}); the first split leg
     * @param statementStep                        the statement-generation step (CREASTMT /
     *                                             {@code CBSTM03A}); the second split leg
     * @param batchTuningProperties                the externalized batch tuning supplying the
     *                                             chunk commit interval for orchestrator steps
     * @param transactionCategoryBalanceRepository the repository over the transaction-category
     *                                             balances read by the print and backup steps
     * @param s3Template                           the S3 client used by the orchestrator-owned
     *                                             fixed-width writers
     * @param awsResourceProperties                the resource properties supplying the output
     *                                             bucket name (never a literal)
     */
    public BatchPipelineOrchestrator(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            @Qualifier(PostTransactionJobConfig.STEP_NAME) Step postTransactionStep,
            @Qualifier(InterestCalculationJobConfig.STEP_NAME) Step interestCalculationStep,
            @Qualifier(CombineTransactionsJobConfig.STEP_NAME) Step combineTransactionsStep,
            @Qualifier(TransactionReportJobConfig.STEP_NAME) Step transactionReportStep,
            @Qualifier(StatementJobConfig.STEP_NAME) Step statementStep,
            BatchConfig.BatchTuningProperties batchTuningProperties,
            TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            S3Template s3Template,
            AwsConfig.AwsResourceProperties awsResourceProperties) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.postTransactionStep = postTransactionStep;
        this.interestCalculationStep = interestCalculationStep;
        this.combineTransactionsStep = combineTransactionsStep;
        this.transactionReportStep = transactionReportStep;
        this.statementStep = statementStep;
        this.batchTuningProperties = batchTuningProperties;
        this.transactionCategoryBalanceRepository = transactionCategoryBalanceRepository;
        this.s3Template = s3Template;
        this.awsResourceProperties = awsResourceProperties;
    }

    // ------------------------------------------------------------------
    // Task executor backing the parallel report/statement split
    // ------------------------------------------------------------------

    /**
     * Task executor used by the {@link #reportStatementSplitFlow(TaskExecutor)
     * split flow} to run the transaction-report and statement legs concurrently.
     *
     * @return a {@link SimpleAsyncTaskExecutor} that names its threads with the
     *     {@value #TASK_EXECUTOR_THREAD_PREFIX} prefix
     */
    @Bean(TASK_EXECUTOR_BEAN)
    public TaskExecutor carddemoBatchTaskExecutor() {
        return new SimpleAsyncTaskExecutor(TASK_EXECUTOR_THREAD_PREFIX);
    }

    // ------------------------------------------------------------------
    // PRTCATBL category-balance print step (STEP10R, 40-byte output)
    // ------------------------------------------------------------------

    /**
     * Reader over the transaction-category balances for the print step, ordered
     * by account id, transaction type, and category code, realizing the
     * ascending {@code SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)}
     * of PRTCATBL {@code STEP10R}.
     *
     * <p>The sort properties use the embedded-id nested paths
     * ({@code id.acctId}, {@code id.typeCd}, {@code id.catCd}) of
     * {@link TransactionCategoryBalanceId}; the keys are held in an
     * insertion-ordered map because {@link RepositoryItemReader} builds the
     * paging {@link Sort} from the map iteration order.</p>
     *
     * @return a paging, sorted reader yielding category balances one at a time
     */
    @Bean(PRINT_READER_BEAN)
    @StepScope
    public RepositoryItemReader<TransactionCategoryBalance> printCategoryBalanceReader() {
        return categoryBalanceReader(PRINT_READER_BEAN);
    }

    /**
     * Writer that materializes the formatted PRTCATBL print lines as a single
     * fixed-width {@value #PRINT_RECORD_WIDTH}-byte object on the output bucket,
     * realizing the {@code SORTOUT LRECL=40} of PRTCATBL {@code STEP10R}.
     *
     * @return a step-scoped fixed-width S3 writer of width {@value #PRINT_RECORD_WIDTH}
     */
    @Bean(name = PRINT_WRITER_BEAN, destroyMethod = "")
    @StepScope
    public FixedWidthS3ItemWriter printCategoryBalanceWriter() {
        return new FixedWidthS3ItemWriter(
                s3Template,
                awsResourceProperties.getS3().getOutputBucket(),
                objectKeySupplier(PRINT_OBJECT_PREFIX, PRINT_OBJECT_SUFFIX),
                PRINT_RECORD_WIDTH);
    }

    /**
     * The category-balance print step (PRTCATBL {@code STEP10R}). It reads the
     * sorted category balances, formats each into a fixed-width line, and writes
     * the {@value #PRINT_RECORD_WIDTH}-byte object to the output bucket.
     *
     * @param printCategoryBalanceReader the sorted category-balance reader
     * @param printCategoryBalanceWriter the fixed-width output writer
     * @return the configured print step
     */
    @Bean(PRINT_STEP_NAME)
    public Step printCategoryBalanceStep(
            @Qualifier(PRINT_READER_BEAN) RepositoryItemReader<TransactionCategoryBalance> printCategoryBalanceReader,
            @Qualifier(PRINT_WRITER_BEAN) FixedWidthS3ItemWriter printCategoryBalanceWriter) {
        return new StepBuilder(PRINT_STEP_NAME, jobRepository)
                .<TransactionCategoryBalance, String>chunk(batchTuningProperties.getChunkSize(), transactionManager)
                .reader(printCategoryBalanceReader)
                .processor(printLineProcessor())
                .writer(printCategoryBalanceWriter)
                .build();
    }

    // ------------------------------------------------------------------
    // Category-balance backup step (TCATBALF unload, 50-byte output), COND=(4,LT) gated
    // ------------------------------------------------------------------

    /**
     * Reader over the transaction-category balances for the backup step, ordered
     * identically to the print reader, realizing the {@code TCATBALF} unload of
     * PRTCATBL {@code STEP05R}.
     *
     * @return a paging, sorted reader yielding category balances one at a time
     */
    @Bean(BACKUP_READER_BEAN)
    @StepScope
    public RepositoryItemReader<TransactionCategoryBalance> categoryBalanceBackupReader() {
        return categoryBalanceReader(BACKUP_READER_BEAN);
    }

    /**
     * Writer that materializes the category-balance backup unload as a single
     * fixed-width {@value #BACKUP_RECORD_WIDTH}-byte object on the output bucket,
     * realizing the {@code BKUP LRECL=50} unload of PRTCATBL {@code STEP05R}.
     *
     * @return a step-scoped fixed-width S3 writer of width {@value #BACKUP_RECORD_WIDTH}
     */
    @Bean(name = BACKUP_WRITER_BEAN, destroyMethod = "")
    @StepScope
    public FixedWidthS3ItemWriter categoryBalanceBackupWriter() {
        return new FixedWidthS3ItemWriter(
                s3Template,
                awsResourceProperties.getS3().getOutputBucket(),
                objectKeySupplier(BACKUP_OBJECT_PREFIX, BACKUP_OBJECT_SUFFIX),
                BACKUP_RECORD_WIDTH);
    }

    /**
     * The category-balance backup step gated by {@link #backupGateDecider()}. It
     * reads the sorted category balances, formats each into the
     * {@value #BACKUP_RECORD_WIDTH}-byte unload layout, and writes the object to
     * the output bucket.
     *
     * @param categoryBalanceBackupReader the sorted category-balance reader
     * @param categoryBalanceBackupWriter the fixed-width backup writer
     * @return the configured backup step
     */
    @Bean(BACKUP_STEP_NAME)
    public Step categoryBalanceBackupStep(
            @Qualifier(BACKUP_READER_BEAN) RepositoryItemReader<TransactionCategoryBalance> categoryBalanceBackupReader,
            @Qualifier(BACKUP_WRITER_BEAN) FixedWidthS3ItemWriter categoryBalanceBackupWriter) {
        return new StepBuilder(BACKUP_STEP_NAME, jobRepository)
                .<TransactionCategoryBalance, String>chunk(batchTuningProperties.getChunkSize(), transactionManager)
                .reader(categoryBalanceBackupReader)
                .processor(backupLineProcessor())
                .writer(categoryBalanceBackupWriter)
                .build();
    }

    // ------------------------------------------------------------------
    // Condition-code gate: TRANBKP STEP10 COND=(4,LT)
    // ------------------------------------------------------------------

    /**
     * Decider that reproduces the {@code TRANBKP STEP10 COND=(4,LT)} condition.
     * It inspects the posting step's exit status and returns
     * {@link #RUN_BACKUP_STATUS} when the posting return code is {@code <= 4}
     * (exit status {@code COMPLETED} or
     * {@value PostTransactionJobConfig#EXIT_CODE_COMPLETED_WITH_REJECTS}) and
     * {@link #SKIP_BACKUP_STATUS} otherwise (return code {@code > 4} or failure).
     *
     * @return the backup-gate decider
     */
    @Bean
    public JobExecutionDecider backupGateDecider() {
        return new PostingReturnCodeDecider();
    }

    // ------------------------------------------------------------------
    // Parallel split: TRANREPT || CREASTMT
    // ------------------------------------------------------------------

    /**
     * The parallel split flow that runs the transaction-report leg (TRANREPT)
     * and the statement leg (CREASTMT) concurrently and joins before the print
     * step. Each leg wraps the corresponding sibling step in its own sub-flow,
     * and both legs execute on the supplied {@link TaskExecutor}.
     *
     * @param taskExecutor the executor running the two legs concurrently
     * @return the joined split flow
     */
    @Bean(SPLIT_FLOW_BEAN)
    public Flow reportStatementSplitFlow(@Qualifier(TASK_EXECUTOR_BEAN) TaskExecutor taskExecutor) {
        Flow reportLeg = new FlowBuilder<Flow>(REPORT_LEG_FLOW_NAME)
                .start(transactionReportStep)
                .build();
        Flow statementLeg = new FlowBuilder<Flow>(STATEMENT_LEG_FLOW_NAME)
                .start(statementStep)
                .build();
        return new FlowBuilder<Flow>(SPLIT_FLOW_BEAN)
                .split(taskExecutor)
                .add(reportLeg, statementLeg)
                .build();
    }

    // ------------------------------------------------------------------
    // Master pipeline job
    // ------------------------------------------------------------------

    /**
     * The master pipeline job, {@value #JOB_NAME}, composing the sibling and
     * orchestrator-owned steps into one flow.
     *
     * <p>The flow runs {@code postTransactionStep}, routes through the
     * {@link #backupGateDecider() backup gate}, conditionally runs the
     * category-balance backup, then runs {@code interestCalculationStep} and
     * {@code combineTransactionsStep}, executes the
     * {@link #reportStatementSplitFlow(TaskExecutor) report/statement split} in
     * parallel, and finally runs {@link #printCategoryBalanceStep}. When the
     * posting return code exceeds {@code 4} the gate routes to a failing
     * transition, aborting the pipeline.</p>
     *
     * @param reportStatementSplitFlow  the parallel report/statement split flow
     * @param backupGateDecider         the {@code COND=(4,LT)} backup gate
     * @param categoryBalanceBackupStep the gated category-balance backup step
     * @param printCategoryBalanceStep  the category-balance print step
     * @return the configured master pipeline job
     */
    @Bean(JOB_NAME)
    public Job carddemoBatchPipelineJob(
            @Qualifier(SPLIT_FLOW_BEAN) Flow reportStatementSplitFlow,
            JobExecutionDecider backupGateDecider,
            @Qualifier(BACKUP_STEP_NAME) Step categoryBalanceBackupStep,
            @Qualifier(PRINT_STEP_NAME) Step printCategoryBalanceStep) {
        Flow pipelineFlow = new FlowBuilder<Flow>(PIPELINE_FLOW_NAME)
                .start(postTransactionStep)
                    .on(ANY_EXIT_STATUS).to(backupGateDecider)
                .from(backupGateDecider)
                    .on(RUN_BACKUP_STATUS).to(categoryBalanceBackupStep)
                .from(backupGateDecider)
                    .on(SKIP_BACKUP_STATUS).fail()
                .from(categoryBalanceBackupStep)
                    .on(ANY_EXIT_STATUS).to(interestCalculationStep)
                .from(interestCalculationStep)
                    .on(ANY_EXIT_STATUS).to(combineTransactionsStep)
                .from(combineTransactionsStep)
                    .on(ANY_EXIT_STATUS).to(reportStatementSplitFlow)
                .from(reportStatementSplitFlow)
                    .on(ANY_EXIT_STATUS).to(printCategoryBalanceStep)
                .build();
        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(new PipelineJobParametersValidator())
                .start(pipelineFlow)
                .end()
                .build();
    }

    // ------------------------------------------------------------------
    // Job parameter contract
    // ------------------------------------------------------------------

    /**
     * Builds the job parameters consumed by the pipeline's step-scoped late
     * bindings: the interest run date keyed by
     * {@link InterestCalculationJobConfig#RUN_DATE_PARAMETER_KEY} and the report
     * window bounds keyed by {@code reportStartDate} and {@code reportEndDate}.
     *
     * <p>The three bounds are validated deterministically before the parameters
     * are assembled, so a malformed launch fails fast at the call site with an
     * {@link IllegalArgumentException} rather than corrupting downstream interest
     * transaction IDs or producing an incorrect report window mid-pipeline. The
     * same checks are re-applied by {@link PipelineJobParametersValidator} on the
     * master job, covering parameters assembled by any other launcher.</p>
     *
     * @param parmDate        the interest run date in the legacy ten-character
     *                        {@code yyyyMMddHH} form (for example {@code 2022071800})
     * @param reportStartDate the inclusive report-window start ({@code yyyy-MM-dd})
     * @param reportEndDate   the inclusive report-window end ({@code yyyy-MM-dd})
     * @return the assembled job parameters for the master pipeline job
     * @throws IllegalArgumentException if {@code parmDate} is not a valid
     *                                  {@code yyyyMMddHH} value, if either report bound is not a valid
     *                                  {@code yyyy-MM-dd} date, or if the report start is after the end
     */
    public static JobParameters pipelineJobParameters(String parmDate, String reportStartDate, String reportEndDate) {
        try {
            InterestCalculationJobConfig.validateRunDateParameter(parmDate);
            TransactionReportJobConfig.validateReportDateWindow(reportStartDate, reportEndDate);
        } catch (JobParametersInvalidException ex) {
            throw new IllegalArgumentException(
                    "Invalid CardDemo batch pipeline job parameters: " + ex.getMessage(), ex);
        }
        return new JobParametersBuilder()
                .addString(InterestCalculationJobConfig.RUN_DATE_PARAMETER_KEY, parmDate)
                .addString(TransactionReportJobConfig.START_DATE_PARAM, reportStartDate)
                .addString(TransactionReportJobConfig.END_DATE_PARAM, reportEndDate)
                .toJobParameters();
    }

    /**
     * {@link JobParametersValidator} for the master pipeline job. It reuses the
     * per-job validation owned by {@link InterestCalculationJobConfig} (the
     * {@code yyyyMMddHH} interest run date) and {@link TransactionReportJobConfig}
     * (the {@code yyyy-MM-dd} report window with {@code start <= end}), so the
     * composed pipeline enforces exactly the same parameter contract as the
     * individual jobs. Wired via {@link JobBuilder#validator(JobParametersValidator)}
     * on {@link #carddemoBatchPipelineJob} so an invalid launch is rejected with a
     * {@link JobParametersInvalidException} before any step executes.
     */
    static final class PipelineJobParametersValidator implements JobParametersValidator {

        @Override
        public void validate(JobParameters parameters) throws JobParametersInvalidException {
            String parmDate = parameters == null
                    ? null : parameters.getString(InterestCalculationJobConfig.RUN_DATE_PARAMETER_KEY);
            String reportStartDate = parameters == null
                    ? null : parameters.getString(TransactionReportJobConfig.START_DATE_PARAM);
            String reportEndDate = parameters == null
                    ? null : parameters.getString(TransactionReportJobConfig.END_DATE_PARAM);
            InterestCalculationJobConfig.validateRunDateParameter(parmDate);
            TransactionReportJobConfig.validateReportDateWindow(reportStartDate, reportEndDate);
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Builds a paging category-balance reader sorted by account id, transaction
     * type, and category code.
     *
     * @param readerName the reader name used for restart-state keys
     * @return the configured reader
     */
    private RepositoryItemReader<TransactionCategoryBalance> categoryBalanceReader(String readerName) {
        Map<String, Sort.Direction> sortKeys = new LinkedHashMap<>();
        sortKeys.put("id.acctId", Sort.Direction.ASC);
        sortKeys.put("id.typeCd", Sort.Direction.ASC);
        sortKeys.put("id.catCd", Sort.Direction.ASC);

        RepositoryItemReader<TransactionCategoryBalance> reader = new RepositoryItemReader<>();
        reader.setName(readerName);
        reader.setRepository(transactionCategoryBalanceRepository);
        reader.setMethodName("findAll");
        reader.setPageSize(batchTuningProperties.getChunkSize());
        reader.setSort(sortKeys);
        return reader;
    }

    /**
     * Supplies a run-unique S3 object key composed of the given prefix, a UTC
     * timestamp token, a random identifier, and the given suffix.
     *
     * @param prefix the object-key prefix (including any folder path)
     * @param suffix the object-key suffix (including the leading dot)
     * @return a supplier yielding a fresh object key on each invocation
     */
    private static Supplier<String> objectKeySupplier(String prefix, String suffix) {
        return () -> prefix + OBJECT_KEY_TIMESTAMP.format(Instant.now()) + "-" + UUID.randomUUID() + suffix;
    }

    /**
     * Processor that formats a category balance into the PRTCATBL print line.
     *
     * @return the print-line processor
     */
    private static ItemProcessor<TransactionCategoryBalance, String> printLineProcessor() {
        return BatchPipelineOrchestrator::formatPrintLine;
    }

    /**
     * Processor that formats a category balance into the backup unload line.
     *
     * @return the backup-line processor
     */
    private static ItemProcessor<TransactionCategoryBalance, String> backupLineProcessor() {
        return BatchPipelineOrchestrator::formatBackupLine;
    }

    /**
     * Formats a category balance into the PRTCATBL {@code STEP10R} print line:
     * the zoned account id, transaction type, and category code separated by
     * single blanks, followed by the balance edited as {@code TTTTTTTTT.TT}
     * (nine integer digits, a decimal point, and two fraction digits). The
     * writer pads the result to {@value #PRINT_RECORD_WIDTH} bytes.
     *
     * @param balance the category balance to format; never {@code null}
     * @return the formatted print line (32 significant characters)
     */
    static String formatPrintLine(TransactionCategoryBalance balance) {
        TransactionCategoryBalanceId id = balance.getId();
        return zonedDigits(acctId(id), ACCT_ID_WIDTH)
                + FIELD_SEPARATOR
                + charField(id.getTypeCd(), TYPE_CD_WIDTH)
                + FIELD_SEPARATOR
                + zonedDigits(catCd(id), CAT_CD_WIDTH)
                + FIELD_SEPARATOR
                + editBalance(balance.getTranCatBal());
    }

    /**
     * Formats a category balance into the PRTCATBL {@code STEP05R} backup unload
     * line: the zoned account id, transaction type, category code, and the
     * eleven-character signed-zoned balance, concatenated without separators.
     * The backup unload is the byte-for-byte IDCAMS {@code REPRO} image of the
     * {@code TCATBALF} VSAM record, so {@code TRAN-CAT-BAL PIC S9(09)V99} is
     * rendered with its trailing-byte overpunch sign (see
     * {@link #signedZonedBalance(BigDecimal)}). The writer pads the result to
     * {@value #BACKUP_RECORD_WIDTH} bytes.
     *
     * @param balance the category balance to format; never {@code null}
     * @return the formatted backup line (28 significant characters)
     */
    static String formatBackupLine(TransactionCategoryBalance balance) {
        TransactionCategoryBalanceId id = balance.getId();
        return zonedDigits(acctId(id), ACCT_ID_WIDTH)
                + charField(id.getTypeCd(), TYPE_CD_WIDTH)
                + zonedDigits(catCd(id), CAT_CD_WIDTH)
                + signedZonedBalance(balance.getTranCatBal());
    }

    /**
     * Returns the account id of the composite key as a primitive, treating a
     * {@code null} as zero.
     *
     * @param id the composite key; never {@code null}
     * @return the account id, or zero when unset
     */
    private static long acctId(TransactionCategoryBalanceId id) {
        return id.getAcctId() == null ? 0L : id.getAcctId();
    }

    /**
     * Returns the category code of the composite key as a primitive, treating a
     * {@code null} as zero.
     *
     * @param id the composite key; never {@code null}
     * @return the category code, or zero when unset
     */
    private static int catCd(TransactionCategoryBalanceId id) {
        return id.getCatCd() == null ? 0 : id.getCatCd();
    }

    /**
     * Coerces a numeric value to exactly {@code width} zoned (zero-padded)
     * decimal digits: the absolute value is rendered, left-padded with zeros
     * when short and truncated to its low-order digits when long.
     *
     * @param value the numeric value to render
     * @param width the exact target width; positive
     * @return a string of exactly {@code width} digit characters
     */
    private static String zonedDigits(long value, int width) {
        String digits = Long.toString(Math.abs(value));
        if (digits.length() == width) {
            return digits;
        }
        if (digits.length() > width) {
            return digits.substring(digits.length() - width);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Coerces a value to exactly {@code width} characters, left-justified and
     * right-padded with spaces or truncated, mirroring a COBOL
     * {@code MOVE ... TO PIC X(width)}.
     *
     * @param value the value to coerce; may be {@code null}
     * @param width the exact target width; positive
     * @return a string of exactly {@code width} characters
     */
    private static String charField(String value, int width) {
        String text = value == null ? "" : value;
        if (text.length() == width) {
            return text;
        }
        if (text.length() > width) {
            return text.substring(0, width);
        }
        return text + " ".repeat(width - text.length());
    }

    /**
     * Edits a balance into the {@code TTTTTTTTT.TT} mask: the absolute value
     * scaled to two fraction digits ({@link RoundingMode#HALF_EVEN}), with the
     * integer part zero-padded to {@value #BALANCE_INT_DIGITS} digits, a decimal
     * point, and two fraction digits.
     *
     * @param value the balance to edit; may be {@code null} (treated as zero)
     * @return the twelve-character edited balance
     */
    private static String editBalance(BigDecimal value) {
        BigDecimal scaled = scaleBalance(value);
        String plain = scaled.toPlainString();
        int dot = plain.indexOf('.');
        String intDigits = dot < 0 ? plain : plain.substring(0, dot);
        String fracDigits = dot < 0 ? "" : plain.substring(dot + 1);
        return padDigits(intDigits, BALANCE_INT_DIGITS) + "." + padFraction(fracDigits);
    }

    /**
     * Renders a balance as the {@value #BALANCE_ZONED_WIDTH}-character
     * signed-zoned {@code PIC S9(09)V99 USAGE DISPLAY} field that the
     * {@code TCATBALF} VSAM record stores: the value scaled to
     * {@value #BALANCE_SCALE} fraction digits ({@link RoundingMode#HALF_EVEN}),
     * its magnitude zero-padded to {@value #BALANCE_ZONED_WIDTH} digits, and the
     * sign encoded as a trailing-byte overpunch on the final digit (positive
     * {@code 0-9} render as {@code '{'} and {@code 'A'-'I'}, negative as
     * {@code '}'} and {@code 'J'-'R'}). This preserves byte-equivalence with the
     * legacy IDCAMS {@code REPRO} unload, including the sign of negative
     * category balances. Mirrors the encoding applied to other signed
     * {@code PIC S9(09)V99} fields in {@code DailyTransactionRecordImage}.
     *
     * @param value the balance to render; may be {@code null} (treated as zero)
     * @return the eleven-character signed-zoned balance
     */
    private static String signedZonedBalance(BigDecimal value) {
        BigDecimal scaled = (value == null ? BigDecimal.ZERO : value)
                .setScale(BALANCE_SCALE, RoundingMode.HALF_EVEN);
        boolean negative = scaled.signum() < 0;
        BigInteger units = scaled.abs().unscaledValue();

        String digits = units.toString();
        if (digits.length() > BALANCE_ZONED_WIDTH) {
            digits = digits.substring(digits.length() - BALANCE_ZONED_WIDTH);
        } else if (digits.length() < BALANCE_ZONED_WIDTH) {
            digits = "0".repeat(BALANCE_ZONED_WIDTH - digits.length()) + digits;
        }

        int lastDigit = digits.charAt(BALANCE_ZONED_WIDTH - 1) - '0';
        char overpunch = (negative ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH)[lastDigit];
        return digits.substring(0, BALANCE_ZONED_WIDTH - 1) + overpunch;
    }

    /**
     * Scales a balance to {@value #BALANCE_SCALE} fraction digits using
     * {@link RoundingMode#HALF_EVEN} and returns its absolute value, treating a
     * {@code null} as zero.
     *
     * @param value the balance to scale; may be {@code null}
     * @return the absolute, two-scale balance
     */
    private static BigDecimal scaleBalance(BigDecimal value) {
        BigDecimal source = value == null ? BigDecimal.ZERO : value;
        return source.setScale(BALANCE_SCALE, RoundingMode.HALF_EVEN).abs();
    }

    /**
     * Left-pads integer digits with zeros to exactly {@code width} characters,
     * truncating to the low-order digits when longer.
     *
     * @param digits the integer digit string
     * @param width  the exact target width; positive
     * @return a string of exactly {@code width} digit characters
     */
    private static String padDigits(String digits, int width) {
        if (digits.length() == width) {
            return digits;
        }
        if (digits.length() > width) {
            return digits.substring(digits.length() - width);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Right-pads or truncates fraction digits to exactly {@value #BALANCE_SCALE}
     * characters.
     *
     * @param digits the fraction digit string
     * @return a string of exactly {@value #BALANCE_SCALE} digit characters
     */
    private static String padFraction(String digits) {
        if (digits.length() == BALANCE_SCALE) {
            return digits;
        }
        if (digits.length() > BALANCE_SCALE) {
            return digits.substring(0, BALANCE_SCALE);
        }
        return digits + "0".repeat(BALANCE_SCALE - digits.length());
    }

    /**
     * Decider realizing the {@code TRANBKP STEP10 COND=(4,LT)} condition. The
     * posting return code is inferred from the posting step's exit status: a
     * normal completion or a completion with rejects maps to a return code
     * {@code <= 4} (run the backup), and any other outcome maps to a return code
     * {@code > 4} (skip the backup and fail the pipeline).
     */
    static final class PostingReturnCodeDecider implements JobExecutionDecider {

        /**
         * Decides whether the category-balance backup runs based on the posting
         * step's exit status.
         *
         * @param jobExecution  the running job execution
         * @param stepExecution the most recently completed step execution, used
         *                      as a fallback when the posting step cannot be
         *                      located by name
         * @return {@link #RUN_BACKUP} when posting RC {@code <= 4}, otherwise
         *     {@link #SKIP_BACKUP}
         */
        @Override
        public FlowExecutionStatus decide(JobExecution jobExecution, StepExecution stepExecution) {
            ExitStatus postingExitStatus = resolvePostingExitStatus(jobExecution, stepExecution);
            String exitCode = postingExitStatus.getExitCode();
            if (ExitStatus.COMPLETED.getExitCode().equals(exitCode)
                    || PostTransactionJobConfig.EXIT_CODE_COMPLETED_WITH_REJECTS.equals(exitCode)) {
                return RUN_BACKUP;
            }
            return SKIP_BACKUP;
        }

        /**
         * Resolves the posting step's exit status by scanning the job execution
         * for the step named {@link PostTransactionJobConfig#STEP_NAME}, falling
         * back to the supplied step execution and finally to
         * {@link ExitStatus#UNKNOWN}.
         *
         * @param jobExecution  the running job execution
         * @param stepExecution the most recently completed step execution
         * @return the resolved posting exit status; never {@code null}
         */
        private static ExitStatus resolvePostingExitStatus(JobExecution jobExecution, StepExecution stepExecution) {
            ExitStatus resolved = null;
            for (StepExecution candidate : jobExecution.getStepExecutions()) {
                if (PostTransactionJobConfig.STEP_NAME.equals(candidate.getStepName())) {
                    resolved = candidate.getExitStatus();
                }
            }
            if (resolved == null && stepExecution != null) {
                resolved = stepExecution.getExitStatus();
            }
            return resolved == null ? ExitStatus.UNKNOWN : resolved;
        }
    }
}
