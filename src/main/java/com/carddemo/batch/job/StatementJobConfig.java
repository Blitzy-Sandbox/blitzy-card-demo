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

import com.carddemo.batch.processor.StatementProcessor;
import com.carddemo.batch.processor.StatementProcessor.StatementBundle;
import com.carddemo.batch.reader.CardXrefItemReader;
import com.carddemo.batch.writer.FixedWidthS3ItemWriter;
import com.carddemo.config.AwsConfig;
import com.carddemo.config.BatchConfig;
import com.carddemo.entity.CardXref;
import io.awspring.cloud.s3.S3Template;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch configuration for the statement-generation job, the Java
 * realization of the legacy COBOL statement generator {@code CBSTM03A} driven by
 * the JCL job {@code CREASTMT} (members {@code app/cbl/CBSTM03A.CBL} and
 * {@code app/jcl/CREASTMT.JCL} at source commit {@code 27d6c6f}).
 *
 * <p>The legacy program drives the run from the card cross-reference file
 * ({@code 1000-MAINLINE} loops {@code PERFORM UNTIL END-OF-FILE} over
 * {@code 1000-XREFFILE-GET-NEXT}); for each card it reads the owning customer and
 * account, formats the statement, and emits two parallel fixed-width streams:
 * a plain-text statement ({@code FD-STMTFILE-REC PIC X(80)}, DD {@code STMTFILE}
 * {@code LRECL=80}) and an HTML statement ({@code FD-HTMLFILE-REC PIC X(100)},
 * DD {@code HTMLFILE} {@code LRECL=100}). Both files are opened once and closed
 * once per run, so a single text object and a single HTML object are produced
 * per execution, each holding every card's statement in card-number order.</p>
 *
 * <p>The legacy artifacts map to a single chunk-oriented Spring Batch step:</p>
 * <ul>
 *   <li>The {@code XREFFILE} driver read is realized by the injected
 *       {@link CardXrefItemReader}, which pages the {@code card_xref} table in
 *       ascending {@code xrefCardNum} order, reproducing the card-number ordering
 *       that {@code CREASTMT} {@code STEP010} established with
 *       {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}.</li>
 *   <li>The per-card customer/account lookups, transaction iteration, running
 *       total, and dual-presentation rendering are owned by the injected
 *       {@link StatementProcessor}, which returns a {@link StatementBundle}
 *       carrying the fully rendered 80-column text payload, the 100-column HTML
 *       payload, and the {@link java.math.BigDecimal} total.</li>
 *   <li>The {@code STMTFILE} and {@code HTMLFILE} writes are realized by two
 *       {@link FixedWidthS3ItemWriter} delegates (record widths {@code 80} and
 *       {@code 100}) that accumulate every card's records and upload one object
 *       each to the statement bucket; a composite writer routes the text payload
 *       to the 80-width delegate and the HTML payload to the 100-width
 *       delegate.</li>
 * </ul>
 *
 * <p>The chunk commit interval is bound from {@code carddemo.batch.chunk-size}
 * through {@link BatchConfig.BatchTuningProperties}, the statement bucket is
 * bound from {@link AwsConfig.AwsResourceProperties}, and the job is wired with
 * the fluent {@link JobBuilder} / {@link StepBuilder} API. The job is not
 * auto-run on startup ({@code spring.batch.job.enabled=false}); it is triggered
 * explicitly by the pipeline orchestrator.</p>
 */
@Configuration
public class StatementJobConfig {

    /** Canonical name of the statement-generation job. */
    static final String JOB_NAME = "statementJob";

    /** Canonical name of the single statement step. */
    static final String STEP_NAME = "statementStep";

    /** Spring bean name of the 80-column plain-text statement writer. */
    static final String TEXT_WRITER_BEAN = "statementTextWriter";

    /** Spring bean name of the 100-column HTML statement writer. */
    static final String HTML_WRITER_BEAN = "statementHtmlWriter";

    /** Fixed record width of the plain-text statement ({@code FD-STMTFILE-REC PIC X(80)}). */
    static final int TEXT_RECORD_WIDTH = 80;

    /** Fixed record width of the HTML statement ({@code FD-HTMLFILE-REC PIC X(100)}). */
    static final int HTML_RECORD_WIDTH = 100;

    /** Object-key prefix mapping the {@code STMTFILE} text statement to the statement bucket. */
    private static final String TEXT_KEY_PREFIX = "statements/text/statement-";

    /** Suffix of the text statement object key. */
    private static final String TEXT_KEY_SUFFIX = ".txt";

    /** Object-key prefix mapping the {@code HTMLFILE} HTML statement to the statement bucket. */
    private static final String HTML_KEY_PREFIX = "statements/html/statement-";

    /** Suffix of the HTML statement object key. */
    private static final String HTML_KEY_SUFFIX = ".html";

    /** Run discriminator used when no job execution id is available. */
    private static final String NO_EXECUTION_ID = "na";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final StatementProcessor statementProcessor;
    private final CardXrefItemReader cardXrefItemReader;
    private final BatchConfig.BatchTuningProperties batchTuningProperties;
    private final S3Template s3Template;
    private final AwsConfig.AwsResourceProperties awsResourceProperties;

    /**
     * Creates the statement-generation job configuration with all collaborators
     * injected by the container.
     *
     * @param jobRepository         the Spring Batch job repository backing the job
     *                              and step metadata
     * @param transactionManager    the platform transaction manager that provides
     *                              the per-chunk commit boundary
     * @param statementProcessor    the per-card processor that resolves the owning
     *                              customer and account, iterates the card's
     *                              transactions, and renders the text and HTML
     *                              statement payloads with the running total
     * @param cardXrefItemReader    the cross-reference driver reader that streams
     *                              {@link CardXref} rows in ascending card-number
     *                              order, one statement per card
     * @param batchTuningProperties the externalized batch tuning supplying the
     *                              chunk commit interval
     * @param s3Template            the Spring Cloud AWS S3 template used by the
     *                              statement writers to upload the statement
     *                              objects (LocalStack-backed in local/test)
     * @param awsResourceProperties the typed AWS resource names supplying the
     *                              statement bucket
     */
    public StatementJobConfig(JobRepository jobRepository,
                              PlatformTransactionManager transactionManager,
                              StatementProcessor statementProcessor,
                              CardXrefItemReader cardXrefItemReader,
                              BatchConfig.BatchTuningProperties batchTuningProperties,
                              S3Template s3Template,
                              AwsConfig.AwsResourceProperties awsResourceProperties) {
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.transactionManager =
                Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.statementProcessor =
                Objects.requireNonNull(statementProcessor, "statementProcessor must not be null");
        this.cardXrefItemReader =
                Objects.requireNonNull(cardXrefItemReader, "cardXrefItemReader must not be null");
        this.batchTuningProperties =
                Objects.requireNonNull(batchTuningProperties, "batchTuningProperties must not be null");
        this.s3Template = Objects.requireNonNull(s3Template, "s3Template must not be null");
        this.awsResourceProperties =
                Objects.requireNonNull(awsResourceProperties, "awsResourceProperties must not be null");
    }

    /**
     * The 80-column plain-text statement writer, realizing the {@code CBSTM03A}
     * {@code STMTFILE} stream ({@code FD-STMTFILE-REC PIC X(80)}).
     *
     * <p>Accumulates every text record produced during the run and uploads a
     * single object to the statement bucket when the stream is closed. The object
     * key is made run-unique by the job execution id so each run produces a new
     * generation (the GDG {@code (+1)} equivalent). The bean is
     * {@link StepScope step-scoped} so every step execution acquires a fresh
     * accumulator and a fresh object key.</p>
     *
     * @param jobExecutionId the current job execution id, late-bound from the step
     *                       execution; may be {@code null} outside a repository
     *                       launch
     * @return the configured 80-width statement writer
     */
    @Bean
    @StepScope
    public FixedWidthS3ItemWriter statementTextWriter(
            @Value("#{stepExecution.jobExecution.id}") Long jobExecutionId) {
        return new FixedWidthS3ItemWriter(
                s3Template,
                awsResourceProperties.getS3().getStatementBucket(),
                TEXT_KEY_PREFIX + runDiscriminator(jobExecutionId) + TEXT_KEY_SUFFIX,
                TEXT_RECORD_WIDTH);
    }

    /**
     * The 100-column HTML statement writer, realizing the {@code CBSTM03A}
     * {@code HTMLFILE} stream ({@code FD-HTMLFILE-REC PIC X(100)}).
     *
     * <p>Accumulates every HTML record produced during the run and uploads a
     * single object to the statement bucket when the stream is closed, under a
     * key distinct from the text statement and made run-unique by the job
     * execution id. The bean is {@link StepScope step-scoped} so every step
     * execution acquires a fresh accumulator and a fresh object key.</p>
     *
     * @param jobExecutionId the current job execution id, late-bound from the step
     *                       execution; may be {@code null} outside a repository
     *                       launch
     * @return the configured 100-width statement writer
     */
    @Bean
    @StepScope
    public FixedWidthS3ItemWriter statementHtmlWriter(
            @Value("#{stepExecution.jobExecution.id}") Long jobExecutionId) {
        return new FixedWidthS3ItemWriter(
                s3Template,
                awsResourceProperties.getS3().getStatementBucket(),
                HTML_KEY_PREFIX + runDiscriminator(jobExecutionId) + HTML_KEY_SUFFIX,
                HTML_RECORD_WIDTH);
    }

    /**
     * The composite statement writer that splits each {@link StatementBundle} into
     * its two presentations and routes the text payload to the 80-width writer and
     * the HTML payload to the 100-width writer.
     *
     * <p>The composite owns the lifecycle of both delegates: it opens, updates,
     * and closes them so each delegate's accumulated object is flushed to S3 when
     * the step completes. The delegates are step-scoped, so each run writes a
     * fresh pair of statement objects.</p>
     *
     * @param statementTextWriter the 80-width text statement delegate
     * @param statementHtmlWriter the 100-width HTML statement delegate
     * @return the composite writer over the two statement delegates
     */
    @Bean
    public StatementBundleS3Writer statementBundleWriter(
            @Qualifier(TEXT_WRITER_BEAN) FixedWidthS3ItemWriter statementTextWriter,
            @Qualifier(HTML_WRITER_BEAN) FixedWidthS3ItemWriter statementHtmlWriter) {
        return new StatementBundleS3Writer(statementTextWriter, statementHtmlWriter);
    }

    /**
     * The single chunk-oriented step that reads each {@link CardXref} in card
     * order, renders its statement, and writes the text and HTML records.
     *
     * <p>The chunk commit interval is taken from
     * {@link BatchConfig.BatchTuningProperties#getChunkSize()} and the supplied
     * {@link PlatformTransactionManager} provides the commit boundary. A step
     * failure leaves the step in a failed state and stops the job, preserving the
     * {@code COND=(0,NE)} short-circuit of the legacy chain.</p>
     *
     * @param statementBundleWriter the composite statement writer
     * @return the configured statement step
     */
    @Bean
    public Step statementStep(StatementBundleS3Writer statementBundleWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<CardXref, StatementBundle>chunk(batchTuningProperties.getChunkSize(), transactionManager)
                .reader(cardXrefItemReader)
                .processor(statementProcessor)
                .writer(statementBundleWriter)
                .build();
    }

    /**
     * The statement-generation job, composed of the single statement step.
     *
     * @param statementStep the statement step
     * @return the configured job
     */
    @Bean
    public Job statementJob(Step statementStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(statementStep)
                .build();
    }

    /**
     * Builds the run discriminator embedded in the statement object keys from the
     * job execution id, zero-padding it to a stable width; falls back to a fixed
     * marker when no execution id is available.
     *
     * @param jobExecutionId the job execution id, or {@code null}
     * @return the run discriminator for the object key
     */
    private static String runDiscriminator(Long jobExecutionId) {
        return jobExecutionId != null ? String.format("%010d", jobExecutionId) : NO_EXECUTION_ID;
    }

    /**
     * Composite {@link ItemStreamWriter} over the two fixed-width statement
     * delegates. Each {@link StatementBundle} carries a fully rendered, newline
     * joined text payload and HTML payload; this writer splits each payload back
     * into its constituent fixed-width records and forwards them to the matching
     * delegate, so the 80-column records reach the text object and the 100-column
     * records reach the HTML object.
     *
     * <p>The {@link ItemStream} lifecycle is delegated to both writers so their
     * accumulated objects are uploaded to S3 when the step completes; {@link #close()}
     * always attempts to close both delegates even if the first close fails.</p>
     */
    static final class StatementBundleS3Writer implements ItemStreamWriter<StatementBundle> {

        /** Record separator used by the processor to join the fixed-width records. */
        private static final char RECORD_DELIMITER = '\n';

        private final FixedWidthS3ItemWriter textDelegate;
        private final FixedWidthS3ItemWriter htmlDelegate;

        /**
         * Creates the composite writer over the two statement delegates.
         *
         * @param textDelegate the 80-width text statement writer; must not be {@code null}
         * @param htmlDelegate the 100-width HTML statement writer; must not be {@code null}
         */
        StatementBundleS3Writer(FixedWidthS3ItemWriter textDelegate, FixedWidthS3ItemWriter htmlDelegate) {
            this.textDelegate = Objects.requireNonNull(textDelegate, "textDelegate must not be null");
            this.htmlDelegate = Objects.requireNonNull(htmlDelegate, "htmlDelegate must not be null");
        }

        /**
         * Opens both delegate streams, resetting each accumulator and resolving
         * each run-unique object key.
         *
         * @param executionContext the step execution context
         * @throws ItemStreamException if either delegate fails to open
         */
        @Override
        public void open(ExecutionContext executionContext) throws ItemStreamException {
            textDelegate.open(executionContext);
            htmlDelegate.open(executionContext);
        }

        /**
         * Updates both delegate streams.
         *
         * @param executionContext the step execution context
         * @throws ItemStreamException if either delegate fails to update
         */
        @Override
        public void update(ExecutionContext executionContext) throws ItemStreamException {
            textDelegate.update(executionContext);
            htmlDelegate.update(executionContext);
        }

        /**
         * Closes both delegate streams, uploading each accumulated statement
         * object to S3. The HTML delegate is closed even when the text delegate
         * close fails so neither upload is silently skipped.
         *
         * @throws ItemStreamException if either delegate fails to close
         */
        @Override
        public void close() throws ItemStreamException {
            try {
                textDelegate.close();
            } finally {
                htmlDelegate.close();
            }
        }

        /**
         * Splits each bundle's text and HTML payloads into their fixed-width
         * records and forwards them to the matching delegate.
         *
         * @param chunk the chunk of rendered statement bundles
         * @throws Exception if either delegate fails to write
         */
        @Override
        public void write(Chunk<? extends StatementBundle> chunk) throws Exception {
            List<String> textRecords = new ArrayList<>();
            List<String> htmlRecords = new ArrayList<>();
            for (StatementBundle bundle : chunk.getItems()) {
                splitRecords(bundle.text(), textRecords);
                splitRecords(bundle.html(), htmlRecords);
            }
            if (!textRecords.isEmpty()) {
                textDelegate.write(new Chunk<String>(textRecords));
            }
            if (!htmlRecords.isEmpty()) {
                htmlDelegate.write(new Chunk<String>(htmlRecords));
            }
        }

        /**
         * Appends the newline-delimited records of {@code payload} to
         * {@code target}, dropping the empty trailing segment that follows the
         * final record separator. A {@code null} or empty payload contributes no
         * records.
         *
         * @param payload the rendered, newline-joined fixed-width payload
         * @param target  the accumulator receiving the individual records
         */
        private static void splitRecords(String payload, List<String> target) {
            if (payload == null || payload.isEmpty()) {
                return;
            }
            int start = 0;
            int length = payload.length();
            while (start < length) {
                int newlineIndex = payload.indexOf(RECORD_DELIMITER, start);
                if (newlineIndex < 0) {
                    target.add(payload.substring(start));
                    break;
                }
                target.add(payload.substring(start, newlineIndex));
                start = newlineIndex + 1;
            }
        }
    }
}
