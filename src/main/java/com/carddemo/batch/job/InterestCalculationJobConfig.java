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

import com.carddemo.batch.processor.InterestProcessor;
import com.carddemo.config.BatchConfig;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.RepositoryItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch configuration for the interest-calculation and fee job, the Java
 * realization of the legacy COBOL batch program {@code CBACT04C} (interest
 * calculator) driven by JCL {@code app/jcl/INTCALC.jcl}
 * ({@code STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}) at source commit
 * {@code 27d6c6f}. The job posts monthly interest to accounts and emits
 * system-generated interest transactions.
 *
 * <p>The single chunk-oriented step assembled here realizes the COBOL main
 * read-compute-write loop ({@code CBACT04C} lines 188-222):</p>
 * <ul>
 *   <li><strong>Reader</strong> &mdash; {@link #interestCalculationReader()}
 *       streams {@link TransactionCategoryBalance} rows (the {@code TCATBALF}
 *       KSDS read by paragraph {@code 1000-TCATBALF-GET-NEXT}) ordered by
 *       account id then transaction type and category code. The account-id
 *       ordering is mandatory: it drives the account control break in
 *       {@link InterestProcessor} so that each account's accumulated interest is
 *       flushed exactly once when the account changes.</li>
 *   <li><strong>Processor</strong> &mdash; the injected {@link InterestProcessor}
 *       (step-scoped) resolves the disclosure interest rate with the
 *       {@code DEFAULT} account-group fallback ({@code 1200-GET-INTEREST-RATE} /
 *       {@code 1200-A-GET-DEFAULT-INT-RATE}), computes
 *       {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} ({@code 1300-COMPUTE-INTEREST}),
 *       builds the system interest transaction ({@code 1300-B-WRITE-TX}), and
 *       performs the per-account balance update ({@code 1050-UPDATE-ACCOUNT}).
 *       The interest formula, divisor, and transaction shaping live entirely in
 *       the processor; this configuration only wires it.</li>
 *   <li><strong>Writer</strong> &mdash; {@link #interestCalculationWriter()}
 *       persists each produced interest {@link Transaction} through
 *       {@link TransactionRepository} (the {@code SYSTRAN} output of the legacy
 *       step is, in the relational model, a row in the transaction master).</li>
 *   <li><strong>Listener</strong> &mdash; the processor is registered as a step
 *       listener so its {@code @AfterStep} callback flushes the final account
 *       group at end of input, mirroring the COBOL {@code ELSE PERFORM
 *       1050-UPDATE-ACCOUNT} branch taken at end-of-file.</li>
 * </ul>
 *
 * <p>The chunk commit interval is bound from {@code carddemo.batch.chunk-size}
 * through {@link BatchConfig.BatchTuningProperties}, and the injected
 * {@link PlatformTransactionManager} establishes the per-chunk commit boundary
 * (rollback on any exception), reproducing the implicit batch commit / CICS
 * {@code SYNCPOINT} unit of work. The job is wired with the fluent
 * {@link JobBuilder} / {@link StepBuilder} API and is never auto-run on startup
 * ({@code spring.batch.job.enabled=false}); it is triggered explicitly by the
 * pipeline orchestrator with the run-date parameter described below.</p>
 *
 * <p><strong>Run-date job parameter.</strong> The legacy {@code PARM-DATE
 * PIC X(10)} ({@code '2022071800'}) becomes the string job parameter keyed by
 * {@link #RUN_DATE_PARAMETER_KEY}, consumed by the step-scoped
 * {@link InterestProcessor} via late binding and used verbatim as the leading
 * ten characters of every generated transaction identifier.
 * {@link #defaultJobParameters()} supplies the default, sourced from
 * {@link BatchConfig.BatchTuningProperties} ({@code carddemo.batch.interest.default-run-date},
 * an ISO {@link LocalDate}) and formatted back to the legacy ten-character form
 * by {@link #formatRunDate(LocalDate)} so launchers can pass a byte-identical
 * value or override it with an explicit run date.</p>
 */
@Configuration
public class InterestCalculationJobConfig {

    /** Canonical name of the interest-calculation job. */
    static final String JOB_NAME = "interestCalculationJob";

    /** Canonical name of the single interest-calculation step. */
    static final String STEP_NAME = "interestCalculationStep";

    /** Name assigned to the category-balance reader for its restart state keys. */
    static final String READER_NAME = "interestCalculationTcatbalReader";

    /**
     * Job-parameter key carrying the ten-character run date (the legacy JCL
     * {@code PARM-DATE}). It matches the late-bound parameter expression read by
     * {@link InterestProcessor} ({@code #{jobParameters['parmDate']}}) so the
     * parameter passed at launch reaches the processor unchanged.
     */
    public static final String RUN_DATE_PARAMETER_KEY = "parmDate";

    /**
     * Formatter for the date portion of the legacy run date. The COBOL
     * {@code PARM='2022071800'} is the run date {@code 2022-07-18} rendered as
     * {@code yyyyMMdd} ({@code 20220718}) followed by {@link #RUN_DATE_TIME_SUFFIX}.
     */
    private static final DateTimeFormatter RUN_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Two-character time suffix appended to the {@code yyyyMMdd} date to recreate
     * the ten-character legacy run date (the trailing {@code 00} of
     * {@code 2022071800}).
     */
    private static final String RUN_DATE_TIME_SUFFIX = "00";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final InterestProcessor interestProcessor;
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;
    private final TransactionRepository transactionRepository;
    private final BatchConfig.BatchTuningProperties batchTuningProperties;

    /**
     * Creates the interest-calculation job configuration with all collaborators
     * injected by the container.
     *
     * @param jobRepository                        the Spring Batch job repository
     *                                             backing the job and step metadata
     * @param transactionManager                   the platform transaction manager
     *                                             providing the per-chunk commit
     *                                             boundary (rollback on any
     *                                             exception)
     * @param interestProcessor                    the step-scoped processor that
     *                                             computes interest, performs the
     *                                             account control break, and
     *                                             produces system interest
     *                                             transactions
     * @param transactionCategoryBalanceRepository the repository over the
     *                                             transaction-category balances
     *                                             read by the step reader
     * @param transactionRepository                the repository used by the step
     *                                             writer to persist generated
     *                                             interest transactions
     * @param batchTuningProperties                the externalized batch tuning
     *                                             supplying the chunk commit
     *                                             interval and the default run date
     */
    public InterestCalculationJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            InterestProcessor interestProcessor,
            TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            TransactionRepository transactionRepository,
            BatchConfig.BatchTuningProperties batchTuningProperties) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.interestProcessor = interestProcessor;
        this.transactionCategoryBalanceRepository = transactionCategoryBalanceRepository;
        this.transactionRepository = transactionRepository;
        this.batchTuningProperties = batchTuningProperties;
    }

    /**
     * Reader over the transaction-category balances, ordered by account id then
     * transaction type and category code, realizing the sequential
     * {@code TCATBALF} read of {@code 1000-TCATBALF-GET-NEXT}.
     *
     * <p>The account-id sort is the primary key and is therefore listed first in
     * an insertion-ordered map, because {@link RepositoryItemReader} builds the
     * paging {@link Sort} from the map's iteration order. This ordering is what
     * makes the {@link InterestProcessor} account control break deterministic and
     * byte-equivalent to the COBOL run. The sort properties use the embedded-id
     * nested paths ({@code id.acctId}, {@code id.typeCd}, {@code id.catCd}) of the
     * {@link com.carddemo.entity.TransactionCategoryBalanceId} composite key. The
     * page size matches the chunk commit interval, and the reader is named so its
     * restart state is keyed in the step execution context.</p>
     *
     * <p>The reader is {@link StepScope step-scoped} so each step execution
     * acquires its own cursor over the balances.</p>
     *
     * @return a paging, sorted reader yielding category balances one at a time
     */
    @Bean
    @StepScope
    public RepositoryItemReader<TransactionCategoryBalance> interestCalculationReader() {
        Map<String, Sort.Direction> sortKeys = new LinkedHashMap<>();
        sortKeys.put("id.acctId", Sort.Direction.ASC);
        sortKeys.put("id.typeCd", Sort.Direction.ASC);
        sortKeys.put("id.catCd", Sort.Direction.ASC);

        RepositoryItemReader<TransactionCategoryBalance> reader = new RepositoryItemReader<>();
        reader.setName(READER_NAME);
        reader.setRepository(transactionCategoryBalanceRepository);
        reader.setMethodName("findAll");
        reader.setPageSize(batchTuningProperties.getChunkSize());
        reader.setSort(sortKeys);
        return reader;
    }

    /**
     * Writer that persists each generated interest {@link Transaction} through
     * {@link TransactionRepository}, realizing the {@code SYSTRAN} output write of
     * paragraph {@code 1300-B-WRITE-TX}.
     *
     * <p>The configured method name {@code save} makes
     * {@link RepositoryItemWriter} persist each item individually via
     * {@link TransactionRepository#save(Object)}; items the processor skips by
     * returning {@code null} (zero or unknown disclosure rate) are filtered by
     * the framework before the writer is invoked.</p>
     *
     * @return a repository-backed writer that saves each interest transaction
     */
    @Bean
    public RepositoryItemWriter<Transaction> interestCalculationWriter() {
        RepositoryItemWriter<Transaction> writer = new RepositoryItemWriter<>();
        writer.setRepository(transactionRepository);
        writer.setMethodName("save");
        return writer;
    }

    /**
     * The single chunk-oriented step that reads category balances, computes
     * interest, and writes the generated interest transactions.
     *
     * <p>The chunk commit interval is taken from
     * {@link BatchConfig.BatchTuningProperties#getChunkSize()} and the injected
     * {@link PlatformTransactionManager} provides the commit boundary, rolling
     * back the chunk on any exception. The processor is also registered as a step
     * listener so its {@code @AfterStep} callback flushes the final account
     * group's accumulated interest once the input is exhausted.</p>
     *
     * @param interestCalculationReader the step-scoped, account-ordered category
     *                                  balance reader
     * @param interestCalculationWriter the repository-backed interest transaction
     *                                  writer
     * @return the configured interest-calculation step
     */
    @Bean
    public Step interestCalculationStep(
            RepositoryItemReader<TransactionCategoryBalance> interestCalculationReader,
            RepositoryItemWriter<Transaction> interestCalculationWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<TransactionCategoryBalance, Transaction>chunk(
                        batchTuningProperties.getChunkSize(), transactionManager)
                .reader(interestCalculationReader)
                .processor(interestProcessor)
                .writer(interestCalculationWriter)
                .listener(interestProcessor)
                .build();
    }

    /**
     * The interest-calculation job, composed of the single interest step.
     *
     * @param interestCalculationStep the interest-calculation step
     * @return the configured job
     */
    @Bean
    public Job interestCalculationJob(Step interestCalculationStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(interestCalculationStep)
                .build();
    }

    /**
     * Builds the default job parameters for the interest-calculation job,
     * carrying the run date under {@link #RUN_DATE_PARAMETER_KEY}.
     *
     * <p>The default run date is sourced from
     * {@link BatchConfig.BatchTuningProperties.Interest#getDefaultRunDate()}
     * ({@code carddemo.batch.interest.default-run-date}) and rendered to the
     * legacy ten-character form by {@link #formatRunDate(LocalDate)}. Launchers
     * and the pipeline orchestrator may use these parameters directly or supply
     * their own run date under the same key.</p>
     *
     * @return job parameters containing the formatted default run date
     * @throws IllegalStateException if the default run date is not configured
     */
    public JobParameters defaultJobParameters() {
        LocalDate defaultRunDate = batchTuningProperties.getInterest().getDefaultRunDate();
        if (defaultRunDate == null) {
            throw new IllegalStateException(
                    "carddemo.batch.interest.default-run-date is not configured; "
                            + "supply the '" + RUN_DATE_PARAMETER_KEY + "' job parameter explicitly");
        }
        return new JobParametersBuilder()
                .addString(RUN_DATE_PARAMETER_KEY, formatRunDate(defaultRunDate))
                .toJobParameters();
    }

    /**
     * Formats a run date into the ten-character legacy form expected by the
     * {@link InterestProcessor} and the generated transaction identifiers:
     * {@code yyyyMMdd} concatenated with the {@code 00} time suffix (for example
     * {@code 2022-07-18} becomes {@code 2022071800}).
     *
     * @param runDate the run date to format; must not be {@code null}
     * @return the ten-character run date string
     */
    public static String formatRunDate(LocalDate runDate) {
        return RUN_DATE_FORMATTER.format(runDate) + RUN_DATE_TIME_SUFFIX;
    }
}
