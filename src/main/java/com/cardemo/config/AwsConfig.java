/*
 * AwsConfig.java — AWS S3/SQS/SNS Client Configuration
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 *
 * This configuration class provides AWS client beans (S3, SQS, SNS) with
 * profile-aware endpoint configuration for LocalStack (local/test profiles)
 * and real AWS (production). It replaces the following COBOL/z/OS constructs:
 *
 * GDG Base Definitions (DEFGDGB.jcl — 6 Generation Data Group bases):
 *   1. AWS.M2.CARDDEMO.TRANSACT.BKUP  (LIMIT 5, SCRATCH) → S3 prefix batch-output/transact-backup/
 *   2. AWS.M2.CARDDEMO.TRANSACT.DALY  (LIMIT 5, SCRATCH) → S3 prefix batch-input/daily-transactions/
 *   3. AWS.M2.CARDDEMO.TRANREPT       (LIMIT 5, SCRATCH) → S3 prefix batch-output/transaction-reports/
 *   4. AWS.M2.CARDDEMO.TCATBALF.BKUP  (LIMIT 5, SCRATCH) → S3 prefix batch-output/tcatbal-backup/
 *   5. AWS.M2.CARDDEMO.SYSTRAN        (LIMIT 5, SCRATCH) → S3 prefix batch-output/system-transactions/
 *   6. AWS.M2.CARDDEMO.TRANSACT.COMBINED (LIMIT 5, SCRATCH) → S3 prefix batch-output/combined-transactions/
 *
 * GDG semantics (LIMIT 5, SCRATCH: keep last 5 generations, delete oldest)
 * map to S3 versioned objects with lifecycle policies retaining 5 versions
 * (Decision D-003).
 *
 * CICS TDQ Queue Allocation (CORPT00C.cbl → TDQ 'JOBS'):
 *   Online-to-batch bridge for report submission maps to SQS FIFO queue
 *   carddemo-report-jobs.fifo with ordered message delivery (Decision D-004).
 *
 * JCL DD Sequential File Staging (POSTTRAN.jcl, COMBTRAN.jcl):
 *   DALYTRAN DD → S3 object batch-input/daily-transactions/dailytran.txt
 *   DALYREJS DD → S3 object batch-output/daily-rejects/{timestamp}.dat
 *   SORTOUT DD  → S3 object batch-output/combined-transactions/{timestamp}.dat
 *
 * S3 Bucket Allocation (3 buckets replacing all GDG and sequential datasets):
 *   carddemo-batch-input    — Input files (daily transactions from GDG TRANSACT.DALY)
 *   carddemo-batch-output   — Output files (reports, rejects, backups, combined)
 *   carddemo-statements     — Statement files (CREASTMT.JCL output)
 *
 * SQS Queue Allocation (1 FIFO queue replacing CICS TDQ):
 *   carddemo-report-jobs.fifo — Report job submissions (FIFO for ordering)
 *
 * All AWS endpoints are externalized in application.yml and profile-specific
 * overrides (application-local.yml, application-test.yml). Local development
 * and testing use LocalStack at localhost:4566 with zero live AWS dependencies
 * (AAP §0.7.7). Credentials are resolved via the AWS SDK v2 default credential
 * chain (environment variables, system properties, profiles, EC2/ECS roles) —
 * no hardcoded credentials (AAP §0.8.1).
 *
 * Decision Log References:
 *   D-003: S3 versioned objects for GDG replacement
 *   D-004: SQS FIFO for TDQ replacement with ordering guarantee
 */
package com.cardemo.config;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * AWS S3/SQS/SNS client configuration for the CardDemo application.
 *
 * <p>Provides three AWS client beans that replace COBOL/z/OS data access constructs:
 *
 * <ul>
 *   <li><strong>{@link S3Client}</strong> — Replaces 6 GDG base definitions from
 *       DEFGDGB.jcl and JCL DD sequential file staging. Used by batch readers
 *       ({@code DailyTransactionReader}), writers ({@code TransactionWriter},
 *       {@code RejectWriter}, {@code StatementWriter}), and diagnostic utilities
 *       ({@code AccountFileReader}, {@code CardFileReader}, {@code CrossReferenceFileReader},
 *       {@code CustomerFileReader}). Configured with path-style access for LocalStack
 *       compatibility.</li>
 *   <li><strong>{@link SqsClient}</strong> — Replaces the CICS TDQ JOBS queue used by
 *       CORPT00C.cbl for online-to-batch report submission. Targets the FIFO queue
 *       {@code carddemo-report-jobs.fifo} providing sequential message delivery that
 *       preserves CICS TDQ read ordering semantics. Used by
 *       {@code ReportSubmissionService}.</li>
 *   <li><strong>{@link SnsClient}</strong> — Provides fan-out messaging capability for
 *       batch pipeline completion notifications and system alerts. Verified against
 *       LocalStack with zero live AWS dependencies.</li>
 * </ul>
 *
 * <p><strong>Profile-Aware Endpoint Resolution:</strong>
 * <ul>
 *   <li>When endpoint properties are set (local/test profiles): clients connect to
 *       LocalStack at the configured URL (default {@code http://localhost:4566})</li>
 *   <li>When endpoint properties are blank (production): clients use default AWS SDK
 *       endpoint resolution for the configured region</li>
 * </ul>
 *
 * <p><strong>Credential Resolution:</strong> Uses {@link DefaultCredentialsProvider}
 * which checks (in order): environment variables ({@code AWS_ACCESS_KEY_ID},
 * {@code AWS_SECRET_ACCESS_KEY}), system properties, credential profiles,
 * EC2 instance metadata, and ECS container credentials. No credentials are
 * hardcoded in this configuration (AAP §0.8.1).
 *
 * <p><strong>S3 Bucket Names</strong> (externalized via {@code carddemo.aws.s3.*}):
 * <ul>
 *   <li>{@code carddemo-batch-input} — GDG TRANSACT.DALY daily transaction files</li>
 *   <li>{@code carddemo-batch-output} — GDG TRANREPT, DALYREJS, TRANSACT.BKUP,
 *       SYSTRAN, TRANSACT.COMBINED output files</li>
 *   <li>{@code carddemo-statements} — CREASTMT.JCL statement generation output</li>
 * </ul>
 *
 * <p><strong>SQS Queue Names</strong> (externalized via {@code carddemo.aws.sqs.*}):
 * <ul>
 *   <li>{@code carddemo-report-jobs.fifo} — CICS TDQ JOBS report submission queue</li>
 * </ul>
 *
 * @see software.amazon.awssdk.services.s3.S3Client
 * @see software.amazon.awssdk.services.sqs.SqsClient
 * @see software.amazon.awssdk.services.sns.SnsClient
 * @see DefaultCredentialsProvider
 */
@Configuration
public class AwsConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsConfig.class);

    // -------------------------------------------------------------------------
    // AWS Endpoint Configuration (profile-aware for LocalStack override)
    // -------------------------------------------------------------------------
    // When set (local/test profiles): overrides default AWS endpoint to LocalStack.
    // When blank (production): uses standard AWS SDK endpoint resolution.
    // Source: application.yml → spring.cloud.aws.{s3,sqs,sns}.endpoint
    // -------------------------------------------------------------------------

    @Value("${spring.cloud.aws.s3.endpoint:}")
    private String s3Endpoint;

    @Value("${spring.cloud.aws.sqs.endpoint:}")
    private String sqsEndpoint;

    @Value("${spring.cloud.aws.sns.endpoint:}")
    private String snsEndpoint;

    // -------------------------------------------------------------------------
    // AWS Region Configuration
    // -------------------------------------------------------------------------
    // Default: us-east-1 (matching original z/OS region mapping).
    // Source: application.yml → spring.cloud.aws.region.static
    // -------------------------------------------------------------------------

    @Value("${spring.cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    // -------------------------------------------------------------------------
    // S3 Path-Style Access (required for LocalStack compatibility)
    // -------------------------------------------------------------------------
    // LocalStack requires path-style access (bucket name in URL path, not subdomain).
    // Without this, S3 operations fail with DNS resolution errors for virtual-hosted
    // bucket names. Source: application-local.yml → spring.cloud.aws.s3.path-style-access-enabled
    // -------------------------------------------------------------------------

    @Value("${spring.cloud.aws.s3.path-style-access-enabled:false}")
    private boolean s3PathStyleAccessEnabled;

    // -------------------------------------------------------------------------
    // S3 Bucket Names (from carddemo.aws.s3.* application properties)
    // -------------------------------------------------------------------------
    // Maps from DEFGDGB.jcl GDG base definitions:
    //   batch-input-bucket  ← GDG AWS.M2.CARDDEMO.TRANSACT.DALY (daily txn files)
    //   batch-output-bucket ← GDG TRANREPT, DALYREJS, TRANSACT.BKUP, SYSTRAN,
    //                          TRANSACT.COMBINED (all batch output)
    //   statements-bucket   ← CREASTMT.JCL statement output files
    // -------------------------------------------------------------------------

    @Value("${carddemo.aws.s3.batch-input-bucket:carddemo-batch-input}")
    private String batchInputBucket;

    @Value("${carddemo.aws.s3.batch-output-bucket:carddemo-batch-output}")
    private String batchOutputBucket;

    @Value("${carddemo.aws.s3.statements-bucket:carddemo-statements}")
    private String statementsBucket;

    // -------------------------------------------------------------------------
    // SQS Queue Name (from carddemo.aws.sqs.* application properties)
    // -------------------------------------------------------------------------
    // Maps from CICS TDQ 'JOBS' queue (CORPT00C.cbl online-to-batch bridge).
    // FIFO queue provides ordered message delivery matching CICS TDQ sequential
    // read semantics (Decision D-004).
    // -------------------------------------------------------------------------

    @Value("${carddemo.aws.sqs.report-queue:carddemo-report-jobs.fifo}")
    private String reportQueue;

    // =========================================================================
    // AWS Client Bean Definitions
    // =========================================================================

    /**
     * Creates an AWS S3 client bean for batch file staging operations.
     *
     * <p>Replaces 6 GDG base definitions from DEFGDGB.jcl:
     * <ol>
     *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.BKUP} (LIMIT 5, SCRATCH) →
     *       {@code batch-output/transact-backup/}</li>
     *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.DALY} (LIMIT 5, SCRATCH) →
     *       {@code batch-input/daily-transactions/}</li>
     *   <li>{@code AWS.M2.CARDDEMO.TRANREPT} (LIMIT 5, SCRATCH) →
     *       {@code batch-output/transaction-reports/}</li>
     *   <li>{@code AWS.M2.CARDDEMO.TCATBALF.BKUP} (LIMIT 5, SCRATCH) →
     *       {@code batch-output/tcatbal-backup/}</li>
     *   <li>{@code AWS.M2.CARDDEMO.SYSTRAN} (LIMIT 5, SCRATCH) →
     *       {@code batch-output/system-transactions/}</li>
     *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.COMBINED} (LIMIT 5, SCRATCH) →
     *       {@code batch-output/combined-transactions/}</li>
     * </ol>
     *
     * <p>Also replaces JCL DD sequential file staging from POSTTRAN.jcl:
     * <ul>
     *   <li>DALYTRAN DD → {@code batch-input/daily-transactions/dailytran.txt}</li>
     *   <li>DALYREJS DD → {@code batch-output/daily-rejects/{timestamp}.dat}</li>
     * </ul>
     *
     * <p>And from COMBTRAN.jcl:
     * <ul>
     *   <li>SORTOUT DD → {@code batch-output/combined-transactions/{timestamp}.dat}</li>
     * </ul>
     *
     * <p>Path-style access is enabled when {@code spring.cloud.aws.s3.path-style-access-enabled}
     * is {@code true} (required for LocalStack — documented pitfall in AAP §0.7.6).
     *
     * @return configured {@link S3Client} instance
     */
    @Bean
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create());

        if (s3Endpoint != null && !s3Endpoint.isBlank()) {
            builder.endpointOverride(URI.create(s3Endpoint));
            log.info("AWS S3 client configured with endpoint override: {}", s3Endpoint);
        } else {
            log.info("AWS S3 client configured with default AWS endpoint for region: {}", awsRegion);
        }

        // Enable path-style access when configured (required for LocalStack S3 compatibility).
        // LocalStack uses path-style URLs (http://localhost:4566/bucket-name/key) rather than
        // virtual-hosted-style (http://bucket-name.localhost:4566/key). Without this setting,
        // S3 operations fail with DNS resolution errors for virtual-hosted bucket names.
        builder.forcePathStyle(s3PathStyleAccessEnabled);

        S3Client client = builder.build();

        log.info("AWS S3 client initialized — region: {}, pathStyleAccess: {}, buckets: [{}, {}, {}]",
                awsRegion, s3PathStyleAccessEnabled,
                batchInputBucket, batchOutputBucket, statementsBucket);

        return client;
    }

    /**
     * Creates an AWS SQS client bean for message queue integration.
     *
     * <p>Replaces the CICS TDQ JOBS queue used by CORPT00C.cbl for online-to-batch
     * report submission. The target SQS FIFO queue ({@code carddemo-report-jobs.fifo})
     * provides ordered message delivery that maps from CICS TDQ sequential read
     * semantics (Decision D-004: SQS FIFO has 300 msg/sec throughput limit, which
     * is sufficient for this workload).
     *
     * <p>Message flow:
     * <ol>
     *   <li>{@code ReportSubmissionService} publishes report request to SQS FIFO queue</li>
     *   <li>Spring Batch listener picks up the message and triggers
     *       {@code StatementGenerationJob} or {@code TransactionReportJob}</li>
     * </ol>
     *
     * @return configured {@link SqsClient} instance
     */
    @Bean
    public SqsClient sqsClient() {
        var builder = SqsClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create());

        if (sqsEndpoint != null && !sqsEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(sqsEndpoint));
            log.info("AWS SQS client configured with endpoint override: {}", sqsEndpoint);
        } else {
            log.info("AWS SQS client configured with default AWS endpoint for region: {}", awsRegion);
        }

        SqsClient client = builder.build();

        log.info("AWS SQS client initialized — region: {}, reportQueue: {}", awsRegion, reportQueue);

        return client;
    }

    /**
     * Creates an AWS SNS client bean for notification and alert publishing.
     *
     * <p>Provides fan-out messaging capability for:
     * <ul>
     *   <li>Batch pipeline completion notifications (5-stage pipeline:
     *       POSTTRAN → INTCALC → COMBTRAN → CREASTMT/TRANREPT)</li>
     *   <li>System alerts (batch failure notifications, threshold alerts)</li>
     * </ul>
     *
     * <p>Verified against LocalStack with zero live AWS dependencies (AAP §0.7.7).
     * Test resource lifecycle follows @BeforeAll create / @AfterAll delete pattern
     * with no pre-existing LocalStack state dependency.
     *
     * @return configured {@link SnsClient} instance
     */
    @Bean
    public SnsClient snsClient() {
        var builder = SnsClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create());

        if (snsEndpoint != null && !snsEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(snsEndpoint));
            log.info("AWS SNS client configured with endpoint override: {}", snsEndpoint);
        } else {
            log.info("AWS SNS client configured with default AWS endpoint for region: {}", awsRegion);
        }

        SnsClient client = builder.build();

        log.info("AWS SNS client initialized — region: {}", awsRegion);

        return client;
    }

    // =========================================================================
    // Bucket and Queue Name Accessors
    // =========================================================================
    // Downstream services (DailyTransactionReader, TransactionWriter,
    // RejectWriter, StatementWriter, ReportSubmissionService) inject this
    // AwsConfig bean and use these accessor methods to resolve bucket/queue
    // names without duplicating @Value annotations across multiple classes.
    // =========================================================================

    /**
     * Returns the S3 bucket name for batch input files.
     *
     * <p>Maps from GDG base {@code AWS.M2.CARDDEMO.TRANSACT.DALY} in DEFGDGB.jcl.
     * Contains daily transaction files read by {@code DailyTransactionReader}.
     * Default: {@code carddemo-batch-input}.
     *
     * @return the batch input bucket name
     */
    public String getBatchInputBucket() {
        return batchInputBucket;
    }

    /**
     * Returns the S3 bucket name for batch output files.
     *
     * <p>Maps from 5 GDG bases in DEFGDGB.jcl:
     * <ul>
     *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.BKUP} → {@code transact-backup/} prefix</li>
     *   <li>{@code AWS.M2.CARDDEMO.TRANREPT} → {@code transaction-reports/} prefix</li>
     *   <li>{@code AWS.M2.CARDDEMO.TCATBALF.BKUP} → {@code tcatbal-backup/} prefix</li>
     *   <li>{@code AWS.M2.CARDDEMO.SYSTRAN} → {@code system-transactions/} prefix</li>
     *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.COMBINED} → {@code combined-transactions/} prefix</li>
     * </ul>
     * Also contains DALYREJS DD output from POSTTRAN.jcl at {@code daily-rejects/} prefix.
     * Default: {@code carddemo-batch-output}.
     *
     * @return the batch output bucket name
     */
    public String getBatchOutputBucket() {
        return batchOutputBucket;
    }

    /**
     * Returns the S3 bucket name for statement generation output.
     *
     * <p>Maps from CREASTMT.JCL statement generation job output (CBSTM03A.CBL +
     * CBSTM03B.CBL dual-format text/HTML generation). Used by {@code StatementWriter}.
     * Default: {@code carddemo-statements}.
     *
     * @return the statements bucket name
     */
    public String getStatementsBucket() {
        return statementsBucket;
    }

    /**
     * Returns the SQS FIFO queue name for report job submissions.
     *
     * <p>Maps from CICS TDQ 'JOBS' queue (CORPT00C.cbl online-to-batch bridge).
     * FIFO queue provides ordered message delivery matching CICS TDQ sequential
     * read semantics (Decision D-004). Used by {@code ReportSubmissionService}.
     * Default: {@code carddemo-report-jobs.fifo}.
     *
     * @return the report queue name
     */
    public String getReportQueue() {
        return reportQueue;
    }
}
