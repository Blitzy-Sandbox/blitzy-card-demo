/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 *
 * COBOL-to-Java Migration: Spring Batch Infrastructure Configuration
 * Source: JCL JOB card parameters + JES job scheduling infrastructure
 *   - POSTTRAN.jcl  (Daily Transaction Posting — CBTRN02C)
 *   - INTCALC.jcl   (Interest Calculation — CBACT04C)
 *   - COMBTRAN.jcl   (Combine Transactions — DFSORT + IDCAMS REPRO)
 *   - CREASTMT.JCL   (Statement Generation — CBSTM03A/CBSTM03B)
 *   - TRANREPT.jcl   (Transaction Report — CBTRN03C)
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 */
package com.cardemo.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch infrastructure configuration that replaces JCL JOB card parameters,
 * JES job scheduling infrastructure, and STEPLIB/DD allocation semantics for the
 * CardDemo 5-stage batch pipeline.
 *
 * <p>This configuration class establishes the Spring Batch infrastructure beans required
 * to execute the batch pipeline migrated from the original COBOL/JCL architecture:</p>
 *
 * <table>
 *   <caption>JCL-to-Spring Batch Pipeline Mapping</caption>
 *   <tr><th>Stage</th><th>JCL Job</th><th>COBOL Program</th><th>Spring Batch Job</th></tr>
 *   <tr><td>1</td><td>POSTTRAN.jcl</td><td>CBTRN02C</td>
 *       <td>DailyTransactionPostingJob</td></tr>
 *   <tr><td>2</td><td>INTCALC.jcl</td><td>CBACT04C</td>
 *       <td>InterestCalculationJob</td></tr>
 *   <tr><td>3</td><td>COMBTRAN.jcl</td><td>(DFSORT+REPRO)</td>
 *       <td>CombineTransactionsJob</td></tr>
 *   <tr><td>4a</td><td>CREASTMT.JCL</td><td>CBSTM03A/B</td>
 *       <td>StatementGenerationJob</td></tr>
 *   <tr><td>4b</td><td>TRANREPT.jcl</td><td>CBTRN03C</td>
 *       <td>TransactionReportJob</td></tr>
 * </table>
 *
 * <p><strong>Key transformation mappings:</strong></p>
 * <ul>
 *   <li>JCL JOB card CLASS/MSGCLASS → Spring Batch job parameters and structured logging</li>
 *   <li>JCL EXEC PGM → Spring Batch Step with ItemReader/Processor/Writer</li>
 *   <li>JCL DD statements → DataSource, S3 client, and repository beans</li>
 *   <li>JCL COND codes → Spring Batch ExitStatus and JobExecutionDecider</li>
 *   <li>DFSORT (COMBTRAN) → Java Comparator with Collections.sort()</li>
 *   <li>IDCAMS REPRO (COMBTRAN) → JPA bulk insert via repository saveAll()</li>
 *   <li>CICS TDQ WRITEQ (CORPT00C) → SQS message triggering async job launch</li>
 * </ul>
 *
 * <p>The {@link JobLauncher} is configured with asynchronous execution support via
 * {@link SimpleAsyncTaskExecutor}, enabling non-blocking job launches from
 * SQS-triggered report submissions (online-to-batch bridge from CORPT00C.cbl).</p>
 *
 * <p>Batch metadata (BATCH_JOB_INSTANCE, BATCH_JOB_EXECUTION, BATCH_STEP_EXECUTION)
 * is stored in the same PostgreSQL database as application data, using the BATCH_
 * table prefix. Schema initialization is controlled by the
 * {@code spring.batch.jdbc.initialize-schema} property in application.yml.</p>
 *
 * @see org.springframework.batch.core.configuration.annotation.EnableBatchProcessing
 * @see org.springframework.batch.core.launch.support.TaskExecutorJobLauncher
 */
@Configuration
public class BatchConfig {

    private static final Logger log = LoggerFactory.getLogger(BatchConfig.class);

    /**
     * Primary PostgreSQL datasource shared with JPA entity persistence.
     * Used by Spring Batch's JobRepository for storing batch metadata in
     * BATCH_ prefix tables (job execution context, step status, exit codes).
     * This mirrors the COBOL pattern where VSAM datasets (TRANFILE, ACCTFILE, etc.)
     * share the same physical z/OS DASD subsystem with batch job metadata.
     */
    private final DataSource dataSource;

    /**
     * JPA-provided transaction manager shared between batch step boundaries
     * and application JPA operations. Ensures that batch step commits align with
     * database commit points, analogous to JCL COND code evaluation at step
     * boundaries in the original JES scheduling infrastructure.
     *
     * <p>This transaction manager is the same instance used by JpaConfig, ensuring
     * that COACTUPC.cbl SYNCPOINT ROLLBACK semantics (dual ACCTDAT+CUSTDAT update)
     * translate correctly to Spring {@code @Transactional} rollback behavior.</p>
     */
    private final PlatformTransactionManager transactionManager;

    /* ------------------------------------------------------------------ */
    /*  Externalized chunk-size configuration from application.yml         */
    /*  Replaces JCL JOB card CLASS parameter and DD SPACE/BLKSIZE tuning */
    /*                                                                    */
    /*  Each chunk size controls the number of records read, processed,   */
    /*  and committed per transaction boundary in the corresponding       */
    /*  Spring Batch step. This is the Java equivalent of JCL DD          */
    /*  BLKSIZE and SPACE allocation tuning for batch I/O throughput.     */
    /* ------------------------------------------------------------------ */

    /**
     * Chunk size for the Daily Transaction Posting step (POSTTRAN → CBTRN02C).
     * Controls the number of daily transactions read from the S3 input file,
     * validated through the 4-stage cascade (reject codes 100-109), and committed
     * to the transactions table per chunk boundary.
     *
     * <p>JCL origin: POSTTRAN.jcl DALYTRAN DD with RECFM=F, LRECL=430, BLKSIZE=0
     * allocated on UNIT=SYSDA with SPACE=(CYL,(1,1),RLSE).</p>
     *
     * <p>Default: 100 records per chunk.</p>
     */
    @Value("${carddemo.batch.chunk-size.posting:100}")
    private int postingChunkSize;

    /**
     * Chunk size for the Interest Calculation step (INTCALC → CBACT04C).
     * Controls the number of transaction category balance records read from
     * TCATBALF, processed through the interest formula
     * {@code (balance × rate) / 1200}, and committed per chunk.
     *
     * <p>JCL origin: INTCALC.jcl with PARM='2022071800' passing date parameter,
     * reading TCATBALF, XREFFILE, ACCTFILE, DISCGRP VSAM datasets and writing
     * TRANSACT to SYSTRAN GDG(+1).</p>
     *
     * <p>Default: 50 records per chunk (lower due to multi-dataset lookups per record).</p>
     */
    @Value("${carddemo.batch.chunk-size.interest:50}")
    private int interestChunkSize;

    /**
     * Chunk size for the Combine Transactions step (COMBTRAN → DFSORT + REPRO).
     * Controls the number of sorted/merged transaction records written per chunk
     * during the bulk insert phase. Higher value for throughput optimization since
     * COMBTRAN is a pure utility step (no COBOL program) that sorts by TRAN-ID
     * ascending and bulk-loads into the TRANSACT VSAM KSDS.
     *
     * <p>JCL origin: COMBTRAN.jcl STEP05R (SORT FIELDS=(TRAN-ID,A)) concatenating
     * TRANSACT.BKUP(0) + SYSTRAN(0) → TRANSACT.COMBINED(+1), followed by
     * STEP10 IDCAMS REPRO to TRANSACT.VSAM.KSDS.</p>
     *
     * <p>Default: 500 records per chunk (high throughput for bulk operations).</p>
     */
    @Value("${carddemo.batch.chunk-size.combine:500}")
    private int combineChunkSize;

    /**
     * Chunk size for the Statement Generation step (CREASTMT → CBSTM03A/CBSTM03B).
     * Lower value due to heavier per-item processing: each statement requires
     * multi-dataset reads (TRNXFILE, XREFFILE, ACCTFILE, CUSTFILE), in-memory
     * buffering, and dual-format output (text STMTFILE + HTML HTMLFILE) to S3.
     *
     * <p>JCL origin: CREASTMT.JCL with 4 steps — DELDEF01 (cluster setup),
     * STEP010 (SORT by card+tran ID), STEP020 (REPRO to VSAM), STEP040
     * (CBSTM03A producing STATEMNT.PS and STATEMNT.HTML outputs).</p>
     *
     * <p>Default: 10 records per chunk (compute-intensive per statement).</p>
     */
    @Value("${carddemo.batch.chunk-size.statement:10}")
    private int statementChunkSize;

    /**
     * Chunk size for the Transaction Report step (TRANREPT → CBTRN03C).
     * Controls the number of date-filtered, card-sorted transaction records
     * enriched with cross-reference, type, and category data per chunk for
     * formatted report generation.
     *
     * <p>JCL origin: TRANREPT.jcl with STEP05R REPROC (unload TRANSACT to BKUP),
     * STEP05R SORT (filter by date range PARM-START-DATE to PARM-END-DATE,
     * sort by TRAN-CARD-NUM ascending), and STEP10R (CBTRN03C formatted output
     * to TRANREPT GDG(+1) with LRECL=133).</p>
     *
     * <p>Default: 100 records per chunk.</p>
     */
    @Value("${carddemo.batch.chunk-size.report:100}")
    private int reportChunkSize;

    /**
     * Constructs the batch configuration with required infrastructure dependencies.
     *
     * <p>Both the datasource and transaction manager are shared with the application's
     * JPA configuration (from JpaConfig), ensuring unified data access across
     * online REST operations and batch processing — mirroring the shared VSAM
     * subsystem in the original z/OS architecture.</p>
     *
     * @param dataSource         the primary PostgreSQL datasource for batch metadata
     *                           and application data persistence
     * @param transactionManager the JPA transaction manager shared with application
     *                           persistence and batch step commit boundaries
     */
    public BatchConfig(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
    }

    /**
     * Configures the Spring Batch {@link JobLauncher} with asynchronous execution
     * support via {@link SimpleAsyncTaskExecutor}.
     *
     * <p>This bean replaces the z/OS JES (Job Entry Subsystem) job submission
     * mechanism. The asynchronous configuration specifically supports the
     * online-to-batch bridge pattern migrated from COBOL:</p>
     *
     * <pre>
     *   COBOL: CORPT00C.cbl → EXEC CICS WRITEQ TD QUEUE('JOBS') → JES job submission
     *   Java:  ReportSubmissionService → SQS message → async Spring Batch job launch
     * </pre>
     *
     * <p>The {@link SimpleAsyncTaskExecutor} creates a new thread per job launch,
     * enabling non-blocking SQS-triggered report generation while the main
     * application continues serving REST requests. Thread names are prefixed
     * with {@code carddemo-batch-} for identification in logs and thread dumps.</p>
     *
     * <p>For the 5-stage pipeline orchestration (POSTTRAN → INTCALC → COMBTRAN →
     * CREASTMT/TRANREPT), the {@code BatchPipelineOrchestrator} manages sequential
     * step execution internally using the same launcher. Stages 4a (CREASTMT) and
     * 4b (TRANREPT) may execute in parallel after COMBTRAN completes, using
     * Spring Batch {@code FlowBuilder.split()} with this async executor.</p>
     *
     * @param jobRepository the Spring Batch job repository for persisting execution
     *                      metadata (BATCH_ prefix tables in PostgreSQL)
     * @return a configured {@link JobLauncher} with async execution capability
     * @throws Exception if the job launcher fails initialization validation
     *                   (e.g., null JobRepository)
     */
    @Bean
    public JobLauncher jobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher jobLauncher = new TaskExecutorJobLauncher();
        jobLauncher.setJobRepository(jobRepository);
        jobLauncher.setTaskExecutor(new SimpleAsyncTaskExecutor("carddemo-batch-"));
        jobLauncher.afterPropertiesSet();

        log.info("Spring Batch infrastructure initialized: async JobLauncher configured "
                + "with SimpleAsyncTaskExecutor for SQS-triggered batch jobs");
        log.info("Batch chunk sizes — posting: {}, interest: {}, combine: {}, "
                + "statement: {}, report: {}",
                postingChunkSize, interestChunkSize, combineChunkSize,
                statementChunkSize, reportChunkSize);

        return jobLauncher;
    }

    /* ------------------------------------------------------------------ */
    /*  Chunk size accessors for batch job step configuration              */
    /*                                                                    */
    /*  These getter methods allow individual Spring Batch Job             */
    /*  configuration classes (DailyTransactionPostingJob,                 */
    /*  InterestCalculationJob, etc.) to inject BatchConfig and            */
    /*  retrieve the appropriate chunk size for their step builders:       */
    /*    stepBuilder.<I, O>chunk(batchConfig.getPostingChunkSize(), tm)   */
    /* ------------------------------------------------------------------ */

    /**
     * Returns the chunk size for the Daily Transaction Posting job step.
     * Maps from POSTTRAN.jcl DALYTRAN DD BLKSIZE tuning.
     *
     * @return the posting chunk size (default: 100)
     */
    public int getPostingChunkSize() {
        return postingChunkSize;
    }

    /**
     * Returns the chunk size for the Interest Calculation job step.
     * Maps from INTCALC.jcl TCATBALF/DISCGRP multi-dataset processing tuning.
     *
     * @return the interest chunk size (default: 50)
     */
    public int getInterestChunkSize() {
        return interestChunkSize;
    }

    /**
     * Returns the chunk size for the Combine Transactions job step.
     * Maps from COMBTRAN.jcl DFSORT SORTIN/SORTOUT throughput tuning.
     *
     * @return the combine chunk size (default: 500)
     */
    public int getCombineChunkSize() {
        return combineChunkSize;
    }

    /**
     * Returns the chunk size for the Statement Generation job step.
     * Maps from CREASTMT.JCL STMTFILE/HTMLFILE output tuning.
     *
     * @return the statement chunk size (default: 10)
     */
    public int getStatementChunkSize() {
        return statementChunkSize;
    }

    /**
     * Returns the chunk size for the Transaction Report job step.
     * Maps from TRANREPT.jcl TRANREPT DD LRECL=133 output tuning.
     *
     * @return the report chunk size (default: 100)
     */
    public int getReportChunkSize() {
        return reportChunkSize;
    }

    /**
     * Returns the shared datasource for batch metadata and application data.
     * Both Spring Batch's BATCH_ tables and the application's entity tables
     * reside in the same PostgreSQL database instance, mirroring the shared
     * z/OS DASD subsystem in the original VSAM architecture.
     *
     * @return the primary PostgreSQL datasource
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * Returns the shared transaction manager for batch step boundaries.
     * This is the same JPA transaction manager used by the application's
     * service layer, ensuring that COACTUPC.cbl SYNCPOINT ROLLBACK semantics
     * are preserved across both online and batch processing paths.
     *
     * @return the JPA-provided platform transaction manager
     */
    public PlatformTransactionManager getTransactionManager() {
        return transactionManager;
    }
}
