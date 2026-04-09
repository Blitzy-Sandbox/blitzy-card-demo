/*
 * HealthIndicators.java — Composite Spring Boot Actuator Health Indicators
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 *
 * Provides health checks for PostgreSQL database, AWS S3 buckets, and AWS SQS
 * queues. These replace the concept of VSAM file availability checks from the
 * original COBOL batch reader programs:
 *
 *   CBACT01C.cbl — Account file reader (ACCTFILE OPEN + FILE STATUS check)
 *   CBACT02C.cbl — Card file reader (CARDDAT OPEN + FILE STATUS check)
 *   CBACT03C.cbl — Cross-reference file reader (CARDXREF OPEN + FILE STATUS check)
 *   CBCUS01C.cbl — Customer file reader (CUSTDAT OPEN + FILE STATUS check)
 *
 * In the COBOL system, these batch reader programs verified VSAM dataset
 * availability by opening each file and checking the FILE STATUS code ('00'
 * = success, any other code = error leading to ABEND via CEE3ABD). For example,
 * CBACT01C.cbl paragraph 0000-ACCTFILE-OPEN performs:
 *
 *     OPEN INPUT ACCTFILE-FILE
 *     IF ACCTFILE-STATUS = '00'
 *         MOVE 0 TO APPL-RESULT
 *     ELSE
 *         DISPLAY 'ERROR OPENING ACCTFILE'
 *         PERFORM 9999-ABEND-PROGRAM
 *     END-IF
 *
 * This maps to the Java health check pattern:
 *   FILE STATUS '00' → Health.up() with details
 *   Any other status  → Health.down(exception) with error details
 *
 * The Java implementation extends beyond VSAM file checks to cover all
 * infrastructure dependencies:
 *   1. PostgreSQL connectivity (replaces VSAM KSDS dataset availability)
 *   2. S3 bucket accessibility (replaces GDG base and sequential PS availability)
 *   3. SQS queue availability (replaces CICS TDQ queue availability)
 *
 * All health indicators auto-register via @Component and expose results via
 * the /actuator/health composite endpoint, with Kubernetes liveness and
 * readiness probes enabled in application.yml.
 *
 * Decision Log References:
 *   D-003: S3 versioned objects for GDG replacement
 *   D-004: SQS FIFO for TDQ replacement with ordering guarantee
 *
 * @see com.cardemo.config.AwsConfig — provides S3Client and SqsClient beans
 * @see org.springframework.boot.actuate.health.HealthIndicator
 */
package com.cardemo.observability;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;

/**
 * Composite health indicators for the CardDemo application.
 *
 * <p>Contains three inner {@code @Component} classes that implement
 * {@link HealthIndicator} for PostgreSQL database, AWS S3, and AWS SQS
 * health monitoring. Each inner class is independently registered as a
 * Spring bean and auto-discovered by the Spring Boot Actuator health
 * endpoint ({@code /actuator/health}).
 *
 * <p><strong>COBOL Traceability:</strong>
 * <ul>
 *   <li>{@link PostgresHealthIndicator} — replaces VSAM KSDS file status
 *       checks in CBACT01C.cbl (paragraph 0000-ACCTFILE-OPEN, status '00'
 *       = success, else ABEND)</li>
 *   <li>{@link S3HealthIndicator} — replaces GDG base and sequential PS
 *       dataset availability verification from DEFGDGB.jcl</li>
 *   <li>{@link SqsHealthIndicator} — replaces CICS TDQ JOBS queue
 *       availability check from CORPT00C.cbl</li>
 * </ul>
 *
 * <p><strong>Actuator Endpoints (configured in application.yml):</strong>
 * <ul>
 *   <li>{@code /actuator/health} — composite health status (UP/DOWN)</li>
 *   <li>{@code /actuator/health/liveness} — Kubernetes liveness probe</li>
 *   <li>{@code /actuator/health/readiness} — Kubernetes readiness probe</li>
 * </ul>
 *
 * <p><strong>LocalStack Verification:</strong> All AWS health checks
 * (S3, SQS) are testable against LocalStack at {@code localhost:4566}
 * with zero live AWS dependencies (AAP §0.7.7).
 */
public final class HealthIndicators {

    // Private constructor prevents instantiation — this is a container class
    // for inner @Component health indicator beans only.
    private HealthIndicators() {
        // Utility class — no instantiation
    }

    // =========================================================================
    // PostgreSQL Health Indicator
    // =========================================================================
    // Replaces COBOL VSAM KSDS file availability checks:
    //   CBACT01C.cbl — 0000-ACCTFILE-OPEN: OPEN INPUT ACCTFILE-FILE
    //   CBACT02C.cbl — 0000-CARDFILE-OPEN: OPEN INPUT CARDFILE-FILE
    //   CBACT03C.cbl — 0000-XREFFILE-OPEN: OPEN INPUT XREFFILE-FILE
    //   CBCUS01C.cbl — 0000-CUSTFILE-OPEN: OPEN INPUT CUSTFILE-FILE
    //
    // COBOL pattern: OPEN file → check FILE STATUS = '00' → ABEND on error
    // Java pattern:  DataSource.getConnection().isValid(timeout) → Health.up/down
    //
    // Named 'carddemoDb' to avoid conflict with Spring Boot's auto-configured
    // DataSourceHealthIndicator — this custom indicator adds CardDemo-specific
    // detail fields (database engine name, connection validation status).
    // =========================================================================

    /**
     * Health indicator that verifies PostgreSQL database connectivity.
     *
     * <p>Obtains a JDBC connection from the Spring-configured {@link DataSource}
     * and validates it with a 5-second timeout using {@link Connection#isValid(int)}.
     * This maps from the COBOL VSAM file open + FILE STATUS check pattern used
     * by the 4 batch reader programs (CBACT01C, CBACT02C, CBACT03C, CBCUS01C).
     *
     * <p>The 5-second timeout mirrors the COBOL VSAM file open timeout behavior —
     * in the COBOL system, a file open failure immediately triggers ABEND via
     * CEE3ABD. In Java, the timeout allows graceful degradation with a Health.down()
     * response instead of application termination.
     *
     * <p><strong>Detail Fields:</strong>
     * <ul>
     *   <li>{@code database} — always "PostgreSQL" (identifies the database engine)</li>
     *   <li>{@code status} — "Connected" on success, exception message on failure</li>
     * </ul>
     *
     * @see javax.sql.DataSource
     */
    @Component("carddemoDbHealthIndicator")
    public static class PostgresHealthIndicator implements HealthIndicator {

        private static final Logger log = LoggerFactory.getLogger(PostgresHealthIndicator.class);

        /** JDBC connection validation timeout in seconds. */
        private static final int CONNECTION_VALIDATION_TIMEOUT_SECONDS = 5;

        private final DataSource dataSource;

        /**
         * Constructs the PostgreSQL health indicator with the application DataSource.
         *
         * @param dataSource the Spring-configured JDBC DataSource (HikariCP pool
         *                   connected to PostgreSQL 16+)
         */
        public PostgresHealthIndicator(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        /**
         * Checks PostgreSQL database connectivity.
         *
         * <p>Maps from COBOL VSAM file availability pattern:
         * <pre>
         * COBOL:  OPEN INPUT ACCTFILE-FILE → IF ACCTFILE-STATUS = '00' → OK
         * Java:   dataSource.getConnection() → connection.isValid(5) → Health.up()
         * </pre>
         *
         * @return {@link Health#up()} with database details if PostgreSQL is
         *         reachable, or {@link Health#down()} with exception details
         *         if the connection fails
         */
        @Override
        public Health health() {
            try (Connection connection = dataSource.getConnection()) {
                if (connection.isValid(CONNECTION_VALIDATION_TIMEOUT_SECONDS)) {
                    log.debug("PostgreSQL health check passed — connection valid");
                    return Health.up()
                            .withDetail("database", "PostgreSQL")
                            .withDetail("status", "Connected")
                            .build();
                }
                // Connection obtained but not valid within timeout
                log.warn("PostgreSQL health check failed — connection not valid within {} seconds",
                        CONNECTION_VALIDATION_TIMEOUT_SECONDS);
                return Health.down()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("status", "Connection not valid")
                        .build();
            } catch (SQLException ex) {
                log.warn("PostgreSQL health check failed — {}", ex.getMessage(), ex);
                return Health.down(ex)
                        .withDetail("database", "PostgreSQL")
                        .build();
            }
        }
    }

    // =========================================================================
    // S3 Health Indicator
    // =========================================================================
    // Replaces GDG base and sequential PS dataset availability verification:
    //   DEFGDGB.jcl — 6 GDG base definitions (TRANSACT.BKUP, TRANSACT.DALY,
    //                  TRANREPT, TCATBALF.BKUP, SYSTRAN, TRANSACT.COMBINED)
    //   POSTTRAN.jcl — DALYTRAN DD (daily transaction input file)
    //   COMBTRAN.jcl — SORTOUT DD (combined transaction output file)
    //   CREASTMT.JCL — Statement output files
    //
    // S3 bucket allocation (3 buckets replacing all GDG and sequential datasets):
    //   carddemo-batch-input    — Input files (daily transactions)
    //   carddemo-batch-output   — Output files (reports, rejects, backups)
    //   carddemo-statements     — Statement files (text + HTML)
    //
    // Uses HeadBucket API call — lightweight bucket existence check that does
    // not list objects or transfer data. Equivalent to COBOL OPEN INPUT which
    // only verifies file accessibility.
    // =========================================================================

    /**
     * Health indicator that verifies AWS S3 bucket accessibility for all three
     * CardDemo buckets.
     *
     * <p>Performs lightweight {@code HeadBucket} API calls to verify that the
     * batch input, batch output, and statements buckets are accessible. This
     * replaces the GDG base and sequential PS dataset availability verification
     * from the COBOL JCL jobs.
     *
     * <p><strong>S3 Bucket Mapping (from DEFGDGB.jcl):</strong>
     * <ul>
     *   <li>{@code carddemo-batch-input} — GDG AWS.M2.CARDDEMO.TRANSACT.DALY</li>
     *   <li>{@code carddemo-batch-output} — GDG TRANREPT, DALYREJS, TRANSACT.BKUP,
     *       SYSTRAN, TRANSACT.COMBINED</li>
     *   <li>{@code carddemo-statements} — CREASTMT.JCL statement output</li>
     * </ul>
     *
     * <p><strong>LocalStack Verification:</strong> Testable against LocalStack
     * at {@code localhost:4566} with zero live AWS dependencies.
     *
     * <p><strong>Detail Fields:</strong>
     * <ul>
     *   <li>{@code batchInputBucket} — name of the batch input bucket</li>
     *   <li>{@code batchOutputBucket} — name of the batch output bucket</li>
     *   <li>{@code statementsBucket} — name of the statements bucket</li>
     *   <li>{@code service} — "S3" (on failure only, identifies the failing service)</li>
     * </ul>
     *
     * @see software.amazon.awssdk.services.s3.S3Client
     * @see com.cardemo.config.AwsConfig#s3Client()
     */
    @Component("carddemoS3HealthIndicator")
    public static class S3HealthIndicator implements HealthIndicator {

        private static final Logger log = LoggerFactory.getLogger(S3HealthIndicator.class);

        private final S3Client s3Client;
        private final String batchInputBucket;
        private final String batchOutputBucket;
        private final String statementsBucket;

        /**
         * Constructs the S3 health indicator with the AWS S3 client and bucket names.
         *
         * @param s3Client          the S3 client bean from {@link com.cardemo.config.AwsConfig}
         * @param batchInputBucket  S3 bucket for batch input files (default: carddemo-batch-input)
         * @param batchOutputBucket S3 bucket for batch output files (default: carddemo-batch-output)
         * @param statementsBucket  S3 bucket for statement files (default: carddemo-statements)
         */
        public S3HealthIndicator(
                S3Client s3Client,
                @Value("${carddemo.aws.s3.batch-input-bucket:carddemo-batch-input}") String batchInputBucket,
                @Value("${carddemo.aws.s3.batch-output-bucket:carddemo-batch-output}") String batchOutputBucket,
                @Value("${carddemo.aws.s3.statements-bucket:carddemo-statements}") String statementsBucket) {
            this.s3Client = s3Client;
            this.batchInputBucket = batchInputBucket;
            this.batchOutputBucket = batchOutputBucket;
            this.statementsBucket = statementsBucket;
        }

        /**
         * Checks accessibility of all three CardDemo S3 buckets.
         *
         * <p>Performs {@code HeadBucket} requests for each bucket — a lightweight
         * API call that verifies bucket existence and access permissions without
         * listing objects or transferring data. This mirrors the COBOL pattern of
         * opening a file to verify availability:
         * <pre>
         * COBOL:  OPEN INPUT DALYTRAN → IF DALYTRAN-STATUS = '00'
         * Java:   s3Client.headBucket(bucket) → no exception = accessible
         * </pre>
         *
         * @return {@link Health#up()} with bucket name details if all three
         *         buckets are accessible, or {@link Health#down()} with
         *         exception details if any bucket is inaccessible
         */
        @Override
        public Health health() {
            try {
                s3Client.headBucket(HeadBucketRequest.builder()
                        .bucket(batchInputBucket)
                        .build());

                s3Client.headBucket(HeadBucketRequest.builder()
                        .bucket(batchOutputBucket)
                        .build());

                s3Client.headBucket(HeadBucketRequest.builder()
                        .bucket(statementsBucket)
                        .build());

                log.debug("S3 health check passed — all 3 buckets accessible: [{}, {}, {}]",
                        batchInputBucket, batchOutputBucket, statementsBucket);

                return Health.up()
                        .withDetail("batchInputBucket", batchInputBucket)
                        .withDetail("batchOutputBucket", batchOutputBucket)
                        .withDetail("statementsBucket", statementsBucket)
                        .build();
            } catch (Exception ex) {
                log.warn("S3 health check failed — {}", ex.getMessage(), ex);
                return Health.down(ex)
                        .withDetail("service", "S3")
                        .build();
            }
        }
    }

    // =========================================================================
    // SQS Health Indicator
    // =========================================================================
    // Replaces CICS TDQ JOBS queue availability verification:
    //   CORPT00C.cbl — EXEC CICS WRITEQ TD QUEUE('JOBS') ...
    //
    // The CICS Transient Data Queue (TDQ) named 'JOBS' was the online-to-batch
    // bridge: CORPT00C.cbl writes report job requests to the TDQ, which JES
    // picks up for batch scheduling. In Java, this maps to SQS FIFO queue
    // 'carddemo-report-jobs.fifo' providing ordered message delivery that
    // preserves CICS TDQ sequential read semantics (Decision D-004).
    //
    // Uses GetQueueUrl API call — lightweight queue existence check that does
    // not read or write messages. Returns the queue URL if the queue exists.
    // =========================================================================

    /**
     * Health indicator that verifies AWS SQS queue availability for the
     * CardDemo report submission queue.
     *
     * <p>Performs a lightweight {@code GetQueueUrl} API call to verify that
     * the report job submission FIFO queue is available. This replaces the
     * implicit CICS TDQ availability assumption in CORPT00C.cbl — in the
     * COBOL system, TDQ availability was guaranteed by CICS resource
     * definitions; in the cloud architecture, queue availability must be
     * explicitly verified.
     *
     * <p><strong>SQS Queue Mapping (from CORPT00C.cbl):</strong>
     * <ul>
     *   <li>{@code carddemo-report-jobs.fifo} — CICS TDQ 'JOBS' queue
     *       (online-to-batch bridge for report submission)</li>
     * </ul>
     *
     * <p><strong>LocalStack Verification:</strong> Testable against LocalStack
     * at {@code localhost:4566} with zero live AWS dependencies.
     *
     * <p><strong>Detail Fields:</strong>
     * <ul>
     *   <li>{@code reportQueue} — name of the SQS FIFO queue</li>
     *   <li>{@code service} — "SQS" (identifies the service)</li>
     *   <li>{@code queue} — queue name (on failure, for diagnostic context)</li>
     * </ul>
     *
     * @see software.amazon.awssdk.services.sqs.SqsClient
     * @see com.cardemo.config.AwsConfig#sqsClient()
     */
    @Component("carddemoSqsHealthIndicator")
    public static class SqsHealthIndicator implements HealthIndicator {

        private static final Logger log = LoggerFactory.getLogger(SqsHealthIndicator.class);

        private final SqsClient sqsClient;
        private final String reportQueue;

        /**
         * Constructs the SQS health indicator with the AWS SQS client and queue name.
         *
         * @param sqsClient   the SQS client bean from {@link com.cardemo.config.AwsConfig}
         * @param reportQueue SQS FIFO queue name for report submissions
         *                    (default: carddemo-report-jobs.fifo)
         */
        public SqsHealthIndicator(
                SqsClient sqsClient,
                @Value("${carddemo.aws.sqs.report-queue:carddemo-report-jobs.fifo}") String reportQueue) {
            this.sqsClient = sqsClient;
            this.reportQueue = reportQueue;
        }

        /**
         * Checks availability of the CardDemo SQS report submission queue.
         *
         * <p>Performs a {@code GetQueueUrl} request — a lightweight API call that
         * resolves the queue name to a URL, verifying queue existence and access
         * permissions without reading or writing messages. This mirrors the
         * implicit availability guarantee of CICS TDQ resources:
         * <pre>
         * COBOL:  EXEC CICS WRITEQ TD QUEUE('JOBS') → assumed available
         * Java:   sqsClient.getQueueUrl(queueName) → verify before use
         * </pre>
         *
         * @return {@link Health#up()} with queue details if the SQS queue is
         *         available, or {@link Health#down()} with exception details
         *         if the queue is inaccessible
         */
        @Override
        public Health health() {
            try {
                sqsClient.getQueueUrl(GetQueueUrlRequest.builder()
                        .queueName(reportQueue)
                        .build());

                log.debug("SQS health check passed — queue available: {}", reportQueue);

                return Health.up()
                        .withDetail("reportQueue", reportQueue)
                        .withDetail("service", "SQS")
                        .build();
            } catch (Exception ex) {
                log.warn("SQS health check failed — queue: {}, error: {}", reportQueue, ex.getMessage(), ex);
                return Health.down(ex)
                        .withDetail("service", "SQS")
                        .withDetail("queue", reportQueue)
                        .build();
            }
        }
    }
}
