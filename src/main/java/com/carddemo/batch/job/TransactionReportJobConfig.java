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

import com.carddemo.batch.processor.TransactionReportProcessor;
import com.carddemo.batch.writer.FixedWidthS3ItemWriter;
import com.carddemo.config.AwsConfig;
import com.carddemo.config.BatchConfig;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import io.awspring.cloud.s3.S3Template;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch configuration for the transaction-report job, the Java
 * realization of the legacy COBOL batch program {@code CBTRN03C} driven by the
 * JCL job {@code TRANREPT} (members {@code app/cbl/CBTRN03C.cbl} and
 * {@code app/jcl/TRANREPT.jcl} at source commit {@code 27d6c6f}). The job
 * produces the date-windowed, card-grouped transaction detail report with
 * page, account, and grand totals, emitting {@value #REPORT_RECORD_WIDTH}-byte
 * fixed-width lines (the {@code TRANREPT} DD {@code LRECL=133} and the COBOL
 * {@code FD-REPTFILE-REC PIC X(133)}) to the Amazon S3 output bucket.
 *
 * <p>The three legacy {@code TRANREPT} JCL steps collapse into one
 * chunk-oriented Spring Batch step:</p>
 * <ul>
 *   <li>{@code STEP05R EXEC PROC=REPROC} &mdash; the IDCAMS unload of
 *       {@code TRANSACT.VSAM.KSDS} is unnecessary because the transaction
 *       master is already the {@code transactions} relational table read
 *       through {@link TransactionRepository}.</li>
 *   <li>{@code STEP05R EXEC PGM=SORT} &mdash; the DFSORT
 *       {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)}
 *       date window (on the first ten characters of the processing timestamp)
 *       is realized by {@link TransactionRepository#findByProcessingDateWindow(String, String)},
 *       and the {@code SORT FIELDS=(TRAN-CARD-NUM,A)} ascending card-number
 *       ordering is realized by the {@linkplain #transactionReportReader stable
 *       in-memory re-sort} applied here.</li>
 *   <li>{@code STEP10R EXEC PGM=CBTRN03C} &mdash; the report rendering,
 *       enrichment lookups, paging, and control-break totalling are performed by
 *       the injected {@link TransactionReportProcessor}; the formatted lines are
 *       written to S3 by {@link FixedWidthS3ItemWriter}.</li>
 * </ul>
 *
 * <p><b>Ordering contract.</b> {@link TransactionRepository#findByProcessingDateWindow(String, String)}
 * returns its rows ordered by {@code tranId} ascending, but the by-card control
 * breaks of {@code CBTRN03C} ({@code WS-CURR-CARD-NUM}) require ascending
 * card-number order. The reader therefore re-sorts the windowed result with a
 * stable comparator keyed on {@code cardNum} first and {@code tranId} second, so
 * the processor observes the same record sequence the DFSORT step produced.</p>
 *
 * <p><b>Job parameters.</b> Two required job parameters carry the inclusive
 * reporting window (the COBOL {@code DATEPARM} {@code WS-START-DATE} /
 * {@code WS-END-DATE} and the JCL {@code PARM-START-DATE} / {@code PARM-END-DATE}):
 * {@code reportStartDate} and {@code reportEndDate}, both {@code String} values
 * in {@code YYYY-MM-DD} form. The same two keys are late-bound by
 * {@link TransactionReportProcessor} (for the printed name-header date range), so
 * a single launch drives both the window filter and the header rendering.</p>
 *
 * <p>The job is wired with the fluent {@link JobBuilder} / {@link StepBuilder}
 * API (no factories, no {@code @EnableBatchProcessing}) and is never auto-run on
 * startup ({@code spring.batch.job.enabled=false}); it is triggered explicitly by
 * the pipeline orchestrator or the report-request consumer. Rationale and the
 * full paragraph-to-method mapping are recorded in {@code DECISION_LOG.md} and
 * {@code TRACEABILITY_MATRIX.md}.</p>
 */
@Configuration
public class TransactionReportJobConfig {

    /** Canonical name of the transaction-report job. */
    static final String JOB_NAME = "transactionReportJob";

    /** Canonical name of the single report step. */
    static final String STEP_NAME = "transactionReportStep";

    /** Fixed report record width &mdash; {@code TRANREPT} {@code LRECL=133}, COBOL {@code FD-REPTFILE-REC PIC X(133)}. */
    static final int REPORT_RECORD_WIDTH = 133;

    /** Required job-parameter key carrying the inclusive window start ({@code YYYY-MM-DD}). */
    static final String START_DATE_PARAM = "reportStartDate";

    /** Required job-parameter key carrying the inclusive window end ({@code YYYY-MM-DD}). */
    static final String END_DATE_PARAM = "reportEndDate";

    /** S3 key prefix grouping the report objects that replace the {@code TRANREPT(+1)} GDG generations. */
    private static final String REPORT_OBJECT_KEY_PREFIX = "transaction-report/";

    /** Stem of the generated object key, echoing the legacy {@code TRANREPT} dataset name. */
    private static final String REPORT_OBJECT_KEY_STEM = "TRANREPT-";

    /** Suffix applied to the generated object key (ASCII text report). */
    private static final String REPORT_OBJECT_KEY_SUFFIX = ".txt";

    /** UTC timestamp format used to version each report object in lieu of a GDG generation number. */
    private static final DateTimeFormatter REPORT_KEY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final TransactionReportProcessor transactionReportProcessor;
    private final TransactionRepository transactionRepository;
    private final BatchConfig.BatchTuningProperties batchTuningProperties;
    private final S3Template s3Template;
    private final AwsConfig.AwsResourceProperties awsResourceProperties;

    /**
     * Creates the transaction-report job configuration with all collaborators
     * injected by the container.
     *
     * @param jobRepository              the Spring Batch job repository backing the
     *                                   job and step metadata
     * @param transactionManager         the platform transaction manager providing
     *                                   the per-chunk commit boundary
     * @param transactionReportProcessor the step-scoped processor that enriches each
     *                                   row, performs paging and by-card control
     *                                   breaks, and supplies the end-of-report
     *                                   trailer lines ({@code CBTRN03C} render logic)
     * @param transactionRepository      the repository over the {@code transactions}
     *                                   master exposing the parameterized
     *                                   date-window finder
     * @param batchTuningProperties      the externalized batch tuning supplying the
     *                                   chunk commit interval
     * @param s3Template                 the auto-configured S3 client used to upload
     *                                   the materialized report object
     * @param awsResourceProperties      the externalized AWS resource names supplying
     *                                   the output bucket
     */
    public TransactionReportJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            TransactionReportProcessor transactionReportProcessor,
            TransactionRepository transactionRepository,
            BatchConfig.BatchTuningProperties batchTuningProperties,
            S3Template s3Template,
            AwsConfig.AwsResourceProperties awsResourceProperties) {
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.transactionManager =
                Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.transactionReportProcessor =
                Objects.requireNonNull(transactionReportProcessor, "transactionReportProcessor must not be null");
        this.transactionRepository =
                Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
        this.batchTuningProperties =
                Objects.requireNonNull(batchTuningProperties, "batchTuningProperties must not be null");
        this.s3Template = Objects.requireNonNull(s3Template, "s3Template must not be null");
        this.awsResourceProperties =
                Objects.requireNonNull(awsResourceProperties, "awsResourceProperties must not be null");
    }

    /**
     * Reader that gathers the date-windowed transactions and returns them ordered
     * by ascending card number, realizing the {@code TRANREPT} {@code SORT} step.
     *
     * <p>The two date job parameters are late-bound for this step execution and
     * forwarded verbatim (as {@code YYYY-MM-DD} strings) to the parameterized
     * {@link TransactionRepository#findByProcessingDateWindow(String, String)}
     * finder, which applies the {@code INCLUDE COND} date window on the first ten
     * characters of the processing timestamp. The finder returns rows ordered by
     * {@code tranId} ascending; this reader then performs a stable re-sort keyed on
     * {@code cardNum} first and {@code tranId} second so the downstream processor
     * observes ascending card-number order for its by-card control breaks, exactly
     * as the DFSORT {@code SORT FIELDS=(TRAN-CARD-NUM,A)} produced. The fully
     * gathered, sorted snapshot is wrapped in a {@link ListItemReader}; the bean is
     * {@link StepScope step-scoped} so each step execution acquires a fresh,
     * window-specific snapshot.</p>
     *
     * @param reportStartDate the inclusive window start bound from the
     *                        {@value #START_DATE_PARAM} job parameter ({@code YYYY-MM-DD})
     * @param reportEndDate   the inclusive window end bound from the
     *                        {@value #END_DATE_PARAM} job parameter ({@code YYYY-MM-DD})
     * @return a reader yielding the windowed transactions in ascending card-number
     *         (then {@code tranId}) order, one item at a time
     */
    @Bean
    @StepScope
    public ItemReader<Transaction> transactionReportReader(
            @Value("#{jobParameters['reportStartDate']}") String reportStartDate,
            @Value("#{jobParameters['reportEndDate']}") String reportEndDate) {
        List<Transaction> windowed = new ArrayList<>(
                transactionRepository.findByProcessingDateWindow(reportStartDate, reportEndDate));
        windowed.sort(
                Comparator.comparing(Transaction::getCardNum, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Transaction::getTranId, Comparator.nullsFirst(Comparator.naturalOrder())));
        return new ListItemReader<>(windowed);
    }

    /**
     * The fixed-width S3 delegate writer that materializes the rendered report
     * lines into a single versioned object in the output bucket.
     *
     * <p>The writer enforces the exact {@value #REPORT_RECORD_WIDTH}-character
     * record width (the {@code TRANREPT} {@code LRECL}) with the default
     * single-byte US-ASCII charset and newline record separator. The destination
     * bucket is taken strictly from {@link AwsConfig.AwsResourceProperties} (never a
     * literal), and the run-unique object key supplied here replaces the legacy
     * {@code TRANREPT(+1)} GDG generation. The bean is {@link StepScope
     * step-scoped} so each step execution targets a distinct object; it is wrapped
     * by {@link #transactionReportWriter(FixedWidthS3ItemWriter)} rather than
     * registered with the step directly.</p>
     *
     * @return the step-scoped fixed-width S3 writer for the report output
     */
    @Bean
    @StepScope
    public FixedWidthS3ItemWriter transactionReportFixedWidthWriter() {
        String outputBucket = awsResourceProperties.getS3().getOutputBucket();
        Supplier<String> objectKeySupplier = TransactionReportJobConfig::buildReportObjectKey;
        return new FixedWidthS3ItemWriter(s3Template, outputBucket, objectKeySupplier, REPORT_RECORD_WIDTH);
    }

    /**
     * The step writer that adapts the processor's per-row {@code List<String>}
     * output to the {@code String}-typed {@link FixedWidthS3ItemWriter} and flushes
     * the end-of-report trailer.
     *
     * <p>{@link TransactionReportProcessor} emits, for each input row, the ordered
     * list of report lines it produces (control-break, header, and detail lines).
     * This writer flattens those lists into the individual fixed-width lines the
     * delegate consumes. Because the final account, page, and grand totals have no
     * triggering input row, the writer invokes
     * {@link TransactionReportProcessor#getReportTrailerLines()} when the stream
     * closes &mdash; before the delegate uploads the object &mdash; serving as the
     * processor's documented writer footer callback. The same step-scoped processor
     * instance is shared with the step's {@code processor}, so the trailer reflects
     * the accumulated totals.</p>
     *
     * @param transactionReportFixedWidthWriter the step-scoped fixed-width S3 delegate
     * @return a step-scoped, trailer-flushing writer over the processor's line lists
     */
    @Bean
    @StepScope
    public ItemStreamWriter<List<String>> transactionReportWriter(
            FixedWidthS3ItemWriter transactionReportFixedWidthWriter) {
        return new ReportLineAggregatingWriter(transactionReportFixedWidthWriter, transactionReportProcessor);
    }

    /**
     * The single chunk-oriented step that reads the windowed, card-ordered
     * transactions, renders them through {@link TransactionReportProcessor}, and
     * writes the fixed-width report to S3.
     *
     * <p>The chunk commit interval is taken from
     * {@link BatchConfig.BatchTuningProperties#getChunkSize()} and the supplied
     * {@link PlatformTransactionManager} provides the commit boundary. The chunk is
     * typed {@code <Transaction, List<String>>} to match the processor output; the
     * {@linkplain #transactionReportWriter(FixedWidthS3ItemWriter) aggregating
     * writer} flattens those line lists onto the fixed-width S3 delegate.</p>
     *
     * @param transactionReportReader the step-scoped, window-filtered, card-ordered reader
     * @param transactionReportWriter the trailer-flushing writer over the processor's line lists
     * @return the configured report step
     */
    @Bean
    public Step transactionReportStep(
            ItemReader<Transaction> transactionReportReader,
            ItemStreamWriter<List<String>> transactionReportWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Transaction, List<String>>chunk(batchTuningProperties.getChunkSize(), transactionManager)
                .reader(transactionReportReader)
                .processor(transactionReportProcessor)
                .writer(transactionReportWriter)
                .build();
    }

    /**
     * The transaction-report job, composed of the single report step.
     *
     * @param transactionReportStep the report step
     * @return the configured job
     */
    @Bean
    public Job transactionReportJob(Step transactionReportStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(transactionReportStep)
                .build();
    }

    /**
     * Builds a run-unique, versioned S3 object key for the report, replacing the
     * legacy {@code TRANREPT(+1)} GDG generation with a UTC-timestamped, randomized
     * key under the report prefix.
     *
     * @return a unique object key of the form
     *         {@code transaction-report/TRANREPT-<utcTimestamp>-<random>.txt}
     */
    private static String buildReportObjectKey() {
        return REPORT_OBJECT_KEY_PREFIX
                + REPORT_OBJECT_KEY_STEM
                + REPORT_KEY_TIMESTAMP.format(Instant.now())
                + "-"
                + UUID.randomUUID().toString().substring(0, 8)
                + REPORT_OBJECT_KEY_SUFFIX;
    }

    /**
     * Step writer that flattens the {@link TransactionReportProcessor} per-row
     * {@code List<String>} output onto a {@code String}-typed
     * {@link FixedWidthS3ItemWriter} delegate and appends the end-of-report trailer
     * lines as the stream closes.
     *
     * <p>The {@link ItemStreamWriter} stream callbacks are delegated to the
     * underlying writer so the S3 object lifecycle (open, accumulate, upload on
     * close) is preserved. On {@link #close()} the writer first appends the
     * processor's {@linkplain TransactionReportProcessor#getReportTrailerLines()
     * trailer lines} to the delegate &mdash; so the final account, page, and grand
     * totals land in the same object &mdash; and then closes the delegate, which
     * uploads exactly one object. Only this aggregating writer is registered with
     * the step, so the delegate is opened and closed exactly once through this
     * wrapper.</p>
     */
    private static final class ReportLineAggregatingWriter implements ItemStreamWriter<List<String>> {

        private final FixedWidthS3ItemWriter delegate;
        private final TransactionReportProcessor reportProcessor;

        /**
         * Creates the aggregating writer.
         *
         * @param delegate        the fixed-width S3 writer that renders and uploads the lines
         * @param reportProcessor the step-scoped processor supplying the end-of-report trailer
         */
        ReportLineAggregatingWriter(FixedWidthS3ItemWriter delegate, TransactionReportProcessor reportProcessor) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
            this.reportProcessor = Objects.requireNonNull(reportProcessor, "reportProcessor must not be null");
        }

        /**
         * Opens the underlying S3 writer, resetting its accumulator and resolving
         * the run-unique object key.
         *
         * @param executionContext the step execution context
         * @throws ItemStreamException if the delegate cannot be opened
         */
        @Override
        public void open(ExecutionContext executionContext) throws ItemStreamException {
            delegate.open(executionContext);
        }

        /**
         * Delegates the periodic execution-context update to the underlying writer.
         *
         * @param executionContext the step execution context
         * @throws ItemStreamException if the delegate update fails
         */
        @Override
        public void update(ExecutionContext executionContext) throws ItemStreamException {
            delegate.update(executionContext);
        }

        /**
         * Flattens each per-row line list into the individual fixed-width lines the
         * delegate consumes and forwards them in a single delegated write.
         *
         * @param chunk the chunk of per-row line lists produced by the processor
         * @throws Exception if the delegate write fails
         */
        @Override
        public void write(Chunk<? extends List<String>> chunk) throws Exception {
            List<String> flattened = new ArrayList<>();
            for (List<String> lines : chunk.getItems()) {
                if (lines != null) {
                    flattened.addAll(lines);
                }
            }
            if (!flattened.isEmpty()) {
                delegate.write(new Chunk<>(flattened));
            }
        }

        /**
         * Appends the end-of-report trailer lines to the delegate and then closes
         * it, uploading the single report object. The delegate is always closed.
         *
         * @throws ItemStreamException if appending the trailer or closing the delegate fails
         */
        @Override
        public void close() throws ItemStreamException {
            try {
                List<String> trailer = reportProcessor.getReportTrailerLines();
                if (trailer != null && !trailer.isEmpty()) {
                    delegate.write(new Chunk<>(trailer));
                }
            } catch (ItemStreamException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new ItemStreamException(
                        "Failed to append transaction-report trailer lines before S3 upload", ex);
            } finally {
                delegate.close();
            }
        }
    }
}
