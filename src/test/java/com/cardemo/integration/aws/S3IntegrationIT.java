/*
 * S3IntegrationIT.java — S3 LocalStack Integration Test
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 *
 * Testcontainers-backed integration test validating all S3 bucket operations
 * for the CardDemo batch file staging system. This test verifies the GDG
 * (Generation Data Group) replacement pattern — COBOL's z/OS GDG bases
 * (6 defined in DEFGDGB.jcl) are replaced by versioned S3 objects in
 * dedicated buckets (AAP Decision D-003).
 *
 * GDG Base → S3 Bucket Mapping (DEFGDGB.jcl):
 *   1. AWS.M2.CARDDEMO.TRANSACT.BKUP  (LIMIT 5, SCRATCH) → batch-output/transact-backup/
 *   2. AWS.M2.CARDDEMO.TRANSACT.DALY  (LIMIT 5, SCRATCH) → batch-input/daily-transactions/
 *   3. AWS.M2.CARDDEMO.TRANREPT       (LIMIT 5, SCRATCH) → batch-output/transaction-reports/
 *   4. AWS.M2.CARDDEMO.TCATBALF.BKUP  (LIMIT 5, SCRATCH) → batch-output/tcatbal-backup/
 *   5. AWS.M2.CARDDEMO.SYSTRAN        (LIMIT 5, SCRATCH) → batch-output/system-transactions/
 *   6. AWS.M2.CARDDEMO.TRANSACT.COMBINED (LIMIT 5, SCRATCH) → batch-output/combined-transactions/
 *
 * These 6 GDG bases map to 3 S3 buckets (per AwsConfig.java):
 *   - carddemo-batch-input    — Input file staging (DALYTRAN.PS → S3 daily-transactions/)
 *   - carddemo-batch-output   — Batch output (rejects, reports, backups, combined)
 *   - carddemo-statements     — Statement generation output (CREASTMT.JCL text+HTML)
 *
 * JCL DD → S3 Key Pattern Mapping (POSTTRAN.jcl, COMBTRAN.jcl, TRANREPT.jcl):
 *   DALYTRAN DD (DSN=AWS.M2.CARDDEMO.DALYTRAN.PS)       → batch-input/daily-transactions/dailytran.txt
 *   DALYREJS DD (DSN=AWS.M2.CARDDEMO.DALYREJS(+1))      → batch-output/daily-rejects/{timestamp}.dat
 *   SORTOUT DD  (DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED)  → batch-output/combined-transactions/{timestamp}.dat
 *   TRANREPT DD (DSN=AWS.M2.CARDDEMO.TRANREPT(+1))      → batch-output/transaction-reports/{timestamp}.dat
 *   STMTFILE DD (DSN=AWS.M2.CARDDEMO.STATEMNT.PS)       → statements/{acct-id}/statement-{timestamp}.txt
 *   HTMLFILE DD (DSN=AWS.M2.CARDDEMO.STATEMNT.HTML)      → statements/{acct-id}/statement-{timestamp}.html
 *
 * Per AAP §0.7.7 (LocalStack Verification Rule): zero live AWS dependencies.
 * Tests create/destroy their own S3 resources following the strict lifecycle
 * pattern: @BeforeAll create buckets → test execution → @AfterAll delete all.
 *
 * Decision Log References:
 *   D-003: S3 versioned objects for GDG replacement with generation prefixes
 */
package com.cardemo.integration.aws;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for S3 bucket operations against LocalStack.
 *
 * <p>Validates that the CardDemo application can create S3 buckets, upload
 * batch input files, download batch output files, list objects with prefix
 * filters, delete objects, upload dual-format statement files, and simulate
 * GDG generation semantics using timestamped S3 object keys.
 *
 * <p>This test class follows the AAP §0.7.7 resource lifecycle pattern:
 * <ol>
 *   <li>{@code @BeforeAll}: Create all 3 S3 buckets (batch-input, batch-output, statements)</li>
 *   <li>Test execution: Exercise S3 operations against LocalStack</li>
 *   <li>{@code @AfterAll}: Delete all objects in each bucket, then delete the buckets</li>
 * </ol>
 *
 * <p><strong>GDG Replacement Semantics (Decision D-003):</strong>
 * COBOL GDG (Generation Data Group) bases with LIMIT(5) and SCRATCH disposition
 * are replaced by S3 objects with timestamp/generation-prefixed keys. Each GDG
 * generation (e.g., {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)}) maps to a new
 * S3 object with a unique key containing a generation number and timestamp prefix.
 * The GDG SCRATCH semantics (delete oldest generation when LIMIT exceeded) are
 * handled by S3 lifecycle policies in production; this test validates the
 * object creation, listing, and key pattern mechanics.
 *
 * @see com.cardemo.config.AwsConfig#s3Client()
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class S3IntegrationIT {

    private static final Logger log = LoggerFactory.getLogger(S3IntegrationIT.class);

    // -------------------------------------------------------------------------
    // Testcontainers LocalStack Container — S3 Service
    // -------------------------------------------------------------------------
    // Only S3 service is needed for this test. The container is managed by the
    // @Testcontainers JUnit 5 extension which handles automatic start before
    // tests and stop after all tests complete.
    // -------------------------------------------------------------------------

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest"))
            .withServices("s3");

    // -------------------------------------------------------------------------
    // Dynamic Property Registration — Wire LocalStack Endpoints
    // -------------------------------------------------------------------------
    // Overrides the application-test.yml default LocalStack endpoints with the
    // actual Testcontainers-managed container endpoints (dynamically allocated
    // ports). All three AWS service endpoints (S3, SQS, SNS) are overridden
    // because AwsConfig creates @Bean instances for all three during Spring
    // context initialization, and all must point to a valid endpoint.
    // -------------------------------------------------------------------------

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.aws.s3.endpoint",
                () -> localstack.getEndpoint().toString());
        registry.add("spring.cloud.aws.sqs.endpoint",
                () -> localstack.getEndpoint().toString());
        registry.add("spring.cloud.aws.sns.endpoint",
                () -> localstack.getEndpoint().toString());
        registry.add("spring.cloud.aws.credentials.access-key",
                localstack::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key",
                localstack::getSecretKey);
        registry.add("spring.cloud.aws.region.static",
                localstack::getRegion);
    }

    // -------------------------------------------------------------------------
    // Injected Beans — From Spring Application Context
    // -------------------------------------------------------------------------
    // S3Client is provided by AwsConfig.s3Client() @Bean factory method.
    // The AwsConfig configures forcePathStyle based on the
    // spring.cloud.aws.s3.path-style-access-enabled property (true for test).
    // -------------------------------------------------------------------------

    @Autowired
    private S3Client s3Client;

    // -------------------------------------------------------------------------
    // Constants — S3 Bucket Names (matching AwsConfig and application.yml)
    // -------------------------------------------------------------------------
    // These 3 buckets replace the 6 GDG bases defined in DEFGDGB.jcl:
    //   batch-input   ← GDG AWS.M2.CARDDEMO.TRANSACT.DALY
    //   batch-output  ← GDG TRANREPT, DALYREJS, TRANSACT.BKUP, SYSTRAN,
    //                     TRANSACT.COMBINED, TCATBALF.BKUP
    //   statements    ← CREASTMT.JCL statement output (text + HTML)
    // -------------------------------------------------------------------------

    /** S3 bucket for batch input files — replaces GDG TRANSACT.DALY. */
    private static final String BATCH_INPUT_BUCKET = "carddemo-batch-input";

    /** S3 bucket for batch output files — replaces 5 GDG bases from DEFGDGB.jcl. */
    private static final String BATCH_OUTPUT_BUCKET = "carddemo-batch-output";

    /** S3 bucket for statement generation output — replaces CREASTMT.JCL output. */
    private static final String STATEMENTS_BUCKET = "carddemo-statements";

    // =========================================================================
    // Lifecycle Methods — Create/Destroy S3 Buckets (AAP §0.7.7)
    // =========================================================================

    /**
     * Creates all 3 S3 buckets in LocalStack before any test method executes.
     *
     * <p>Uses direct SDK client construction (not Spring-injected beans) because
     * the Spring application context is not yet available in {@code @BeforeAll}
     * static lifecycle methods.
     *
     * <p>Bucket creation maps from DEFGDGB.jcl GDG base definitions:
     * <ul>
     *   <li>{@code BATCH_INPUT_BUCKET}  ← DEFINE GDG AWS.M2.CARDDEMO.TRANSACT.DALY</li>
     *   <li>{@code BATCH_OUTPUT_BUCKET} ← DEFINE GDG AWS.M2.CARDDEMO.TRANSACT.BKUP,
     *       TRANREPT, TCATBALF.BKUP, SYSTRAN, TRANSACT.COMBINED + POSTTRAN.jcl DALYREJS</li>
     *   <li>{@code STATEMENTS_BUCKET}   ← CREASTMT.JCL STMTFILE + HTMLFILE DD statements</li>
     * </ul>
     *
     * <p>CRITICAL: {@code forcePathStyle(true)} is required for LocalStack S3
     * (documented pitfall in AAP §0.7.6). Without path-style access, S3 operations
     * fail with DNS resolution errors for virtual-hosted bucket names.
     */
    @BeforeAll
    static void createBuckets() {
        log.info("Setting up S3 integration test resources against LocalStack");

        S3Client setupClient = buildS3Client();

        try {
            // Create batch input bucket — replaces GDG TRANSACT.DALY (POSTTRAN.jcl DALYTRAN DD)
            setupClient.createBucket(b -> b.bucket(BATCH_INPUT_BUCKET));
            log.info("Created S3 bucket: {} (← GDG AWS.M2.CARDDEMO.TRANSACT.DALY)", BATCH_INPUT_BUCKET);

            // Create batch output bucket — replaces 5 GDG bases + DALYREJS
            setupClient.createBucket(b -> b.bucket(BATCH_OUTPUT_BUCKET));
            log.info("Created S3 bucket: {} (← GDG TRANSACT.BKUP, TRANREPT, TCATBALF.BKUP, SYSTRAN, TRANSACT.COMBINED)",
                    BATCH_OUTPUT_BUCKET);

            // Create statements bucket — replaces CREASTMT.JCL STMTFILE + HTMLFILE output
            setupClient.createBucket(b -> b.bucket(STATEMENTS_BUCKET));
            log.info("Created S3 bucket: {} (← CREASTMT.JCL statement output)", STATEMENTS_BUCKET);
        } finally {
            setupClient.close();
        }

        log.info("S3 integration test setup complete — 3 buckets created");
    }

    /**
     * Deletes all objects and buckets in LocalStack after all tests complete.
     *
     * <p>For each bucket: lists all objects, deletes each one, then deletes the
     * empty bucket. All operations are wrapped in try-catch to ensure cleanup
     * continues even if individual operations fail.
     *
     * <p>This guarantees no leftover AWS resources in the LocalStack container
     * (AAP §0.7.7 — no pre-existing state dependency between test classes).
     */
    @AfterAll
    static void deleteBuckets() {
        log.info("Cleaning up S3 integration test resources");

        S3Client cleanupClient = buildS3Client();

        try {
            cleanupBucket(cleanupClient, BATCH_INPUT_BUCKET);
            cleanupBucket(cleanupClient, BATCH_OUTPUT_BUCKET);
            cleanupBucket(cleanupClient, STATEMENTS_BUCKET);
        } finally {
            cleanupClient.close();
        }

        log.info("S3 integration test cleanup complete — all buckets destroyed");
    }

    // =========================================================================
    // Test Methods — S3 Bucket Integration Verification
    // =========================================================================

    /**
     * Verifies that all 3 S3 buckets were successfully created in LocalStack.
     *
     * <p>Uses the Spring-injected {@link S3Client} bean from
     * {@link com.cardemo.config.AwsConfig#s3Client()} to list all buckets,
     * confirming that the S3 client configuration and LocalStack container
     * are working correctly together.
     *
     * <p>Validates that all 3 bucket names appear in the response:
     * <ul>
     *   <li>{@code carddemo-batch-input}  (← GDG TRANSACT.DALY)</li>
     *   <li>{@code carddemo-batch-output} (← 5 GDG bases + DALYREJS)</li>
     *   <li>{@code carddemo-statements}   (← CREASTMT.JCL)</li>
     * </ul>
     */
    @Test
    void testBucketCreation() {
        log.info("Verifying S3 bucket creation: {}, {}, {}",
                BATCH_INPUT_BUCKET, BATCH_OUTPUT_BUCKET, STATEMENTS_BUCKET);

        // Use Spring-injected S3 client (validates AwsConfig.s3Client() bean)
        ListBucketsResponse response = s3Client.listBuckets();
        List<String> bucketNames = response.buckets().stream()
                .map(Bucket::name)
                .toList();

        assertThat(bucketNames)
                .as("All 3 CardDemo S3 buckets should exist after @BeforeAll setup")
                .contains(BATCH_INPUT_BUCKET, BATCH_OUTPUT_BUCKET, STATEMENTS_BUCKET);

        log.info("All 3 S3 buckets verified — names: {}", bucketNames);
    }

    /**
     * Verifies upload of a batch input file (daily transaction) to the S3
     * batch-input bucket and confirms content integrity via round-trip download.
     *
     * <p>This validates the GDG replacement pattern for POSTTRAN.jcl:
     * <ul>
     *   <li><strong>COBOL:</strong> {@code DALYTRAN DD DSN=AWS.M2.CARDDEMO.DALYTRAN.PS}
     *       — sequential file read by CBTRN02C.cbl for daily transaction posting</li>
     *   <li><strong>Java:</strong> S3 object at
     *       {@code carddemo-batch-input/daily-transactions/dailytran.txt}
     *       — read by {@code DailyTransactionReader}</li>
     * </ul>
     *
     * <p>The test content simulates the fixed-width DALYTRAN.PS record format
     * (350 bytes per record as defined in POSTTRAN.jcl DCB LRECL=430 including
     * the 80-byte rejection trailer area).
     *
     * @throws IOException if reading the S3 object response stream fails
     */
    @Test
    void testUploadBatchInputFile() throws IOException {
        log.info("Testing S3 upload to batch-input bucket — daily transaction file");

        // Key pattern matches DailyTransactionReader S3 key prefix
        // Maps from: DALYTRAN DD DSN=AWS.M2.CARDDEMO.DALYTRAN.PS
        String objectKey = "daily-transactions/dailytran.txt";

        // Sample fixed-width transaction record content (from app/data/ASCII/dailytran.txt format)
        // Format: TRAN-ID(16) | TRAN-CARD-NUM(16) | TRAN-TYPE-CD(2) | TRAN-CAT-CD(4) |
        //         TRAN-SOURCE(10) | TRAN-DESC(100) | TRAN-AMT(12) | TRAN-ORIG-TS(26) | ...
        String transactionContent = """
                0000000000001234123456789012345601SA  POS TERM  Gas Station Fill Up                                                                              0000000005000202401150800000000000000000000
                0000000000005678987654321098765402PR  OPERATOR  Monthly Internet Service                                                                         0000000012500202401201000000000000000000000""";

        // Upload to S3 — replaces DALYTRAN DD sequential file write
        s3Client.putObject(
                b -> b.bucket(BATCH_INPUT_BUCKET).key(objectKey),
                RequestBody.fromString(transactionContent, StandardCharsets.UTF_8));
        log.info("Uploaded daily transaction file to S3: {}/{}", BATCH_INPUT_BUCKET, objectKey);

        // Download and verify content integrity — round-trip validation
        try (ResponseInputStream<GetObjectResponse> responseStream = s3Client.getObject(
                b -> b.bucket(BATCH_INPUT_BUCKET).key(objectKey))) {

            String downloadedContent = new String(
                    responseStream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(downloadedContent)
                    .as("Downloaded daily transaction file should match uploaded content exactly")
                    .isEqualTo(transactionContent);
        }

        log.info("Batch input file upload/download round-trip verified — content integrity confirmed");
    }

    /**
     * Verifies upload and download of a batch output file (transaction report)
     * to the S3 batch-output bucket.
     *
     * <p>This validates the GDG replacement pattern for TRANREPT.jcl:
     * <ul>
     *   <li><strong>COBOL:</strong> {@code TRANREPT DD DSN=AWS.M2.CARDDEMO.TRANREPT(+1)}
     *       — GDG generation (+1) created by CBTRN03C.cbl for formatted reports</li>
     *   <li><strong>Java:</strong> S3 object at
     *       {@code carddemo-batch-output/transaction-reports/{timestamp}.dat}
     *       — written by {@code TransactionReportProcessor}</li>
     * </ul>
     *
     * <p>The key pattern uses a timestamp prefix to simulate GDG generation
     * numbering (Decision D-003: LIMIT 5 → last 5 timestamped objects retained).
     * TRANREPT.jcl DCB specifies LRECL=133, RECFM=FB for 133-byte fixed-length
     * report lines.
     *
     * @throws IOException if reading the S3 object response stream fails
     */
    @Test
    void testDownloadBatchOutputFile() throws IOException {
        log.info("Testing S3 upload/download for batch-output bucket — transaction report");

        // Key pattern matches TransactionReportProcessor output (GDG TRANREPT(+1))
        // Timestamp prefix simulates GDG generation numbering
        String objectKey = "transaction-reports/20240115-103000.dat";

        // Sample transaction report content — LRECL=133 per TRANREPT.jcl DCB specification
        // Line format: 1-byte CC + 132-byte data (matches CBTRN03C.cbl report layout)
        String reportContent = """
                1        CARDDEMO CREDIT CARD DEMO APPLICATION - DAILY TRANSACTION REPORT                          PAGE:    1
                -ACCT ID      CARD NUMBER       TRAN ID          TRAN DATE   TRAN DESC                               TRAN AMT
                 0000000001  1234567890123456  0000000000001234  2024-01-15  Gas Station Fill Up                        50.00
                 0000000001  1234567890123456  0000000000005678  2024-01-20  Monthly Internet Service                  125.00
                0                                                                     ACCOUNT TOTAL:                   175.00
                0                                                                     GRAND   TOTAL:                   175.00""";

        // Upload report to S3 — replaces TRANREPT DD GDG write
        s3Client.putObject(
                b -> b.bucket(BATCH_OUTPUT_BUCKET).key(objectKey),
                RequestBody.fromString(reportContent, StandardCharsets.UTF_8));
        log.info("Uploaded transaction report to S3: {}/{}", BATCH_OUTPUT_BUCKET, objectKey);

        // Download and verify content — validates S3 object retrieval
        try (ResponseInputStream<GetObjectResponse> responseStream = s3Client.getObject(
                b -> b.bucket(BATCH_OUTPUT_BUCKET).key(objectKey))) {

            String downloadedContent = new String(
                    responseStream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(downloadedContent)
                    .as("Downloaded transaction report should match uploaded content exactly")
                    .isEqualTo(reportContent);
        }

        log.info("Batch output file download verified — content integrity confirmed for TRANREPT replacement");
    }

    /**
     * Verifies listing S3 objects with prefix filtering in the batch-output bucket.
     *
     * <p>This validates the GDG generation listing pattern equivalent to
     * IDCAMS LISTCAT for VSAM/GDG entries. In COBOL, {@code LISTCAT.txt}
     * (app/catlg/LISTCAT.txt — 209 entries) lists all VSAM clusters and GDG
     * generations. In Java, S3 {@code listObjectsV2} with prefix filter provides
     * the equivalent generation listing capability.
     *
     * <p>The test uploads multiple objects under different GDG-replacement prefixes
     * in the batch-output bucket and verifies prefix-filtered listing:
     * <ul>
     *   <li>{@code daily-rejects/} — POSTTRAN.jcl DALYREJS DD output</li>
     *   <li>{@code transaction-reports/} — TRANREPT.jcl TRANREPT DD output</li>
     *   <li>{@code combined-transactions/} — COMBTRAN.jcl SORTOUT DD output</li>
     * </ul>
     */
    @Test
    void testListObjects() {
        log.info("Testing S3 object listing with prefix filter — GDG generation listing equivalent");

        // Upload objects simulating different GDG output types in batch-output bucket
        // Maps from POSTTRAN.jcl DALYREJS DD → batch-output/daily-rejects/
        s3Client.putObject(
                b -> b.bucket(BATCH_OUTPUT_BUCKET).key("daily-rejects/20240115-080000.dat"),
                RequestBody.fromString("REJECT:TRAN-001:103:EXPIRED CARD", StandardCharsets.UTF_8));
        s3Client.putObject(
                b -> b.bucket(BATCH_OUTPUT_BUCKET).key("daily-rejects/20240116-080000.dat"),
                RequestBody.fromString("REJECT:TRAN-002:102:CREDIT LIMIT", StandardCharsets.UTF_8));

        // Maps from COMBTRAN.jcl SORTOUT DD → batch-output/combined-transactions/
        s3Client.putObject(
                b -> b.bucket(BATCH_OUTPUT_BUCKET).key("combined-transactions/20240115-120000.dat"),
                RequestBody.fromString("COMBINED-TRAN-DATA-SORTED", StandardCharsets.UTF_8));

        // List objects with prefix filter for daily-rejects (DALYREJS GDG generations)
        ListObjectsV2Response rejectResponse = s3Client.listObjectsV2(
                b -> b.bucket(BATCH_OUTPUT_BUCKET).prefix("daily-rejects/"));

        List<String> rejectKeys = rejectResponse.contents().stream()
                .map(S3Object::key)
                .toList();

        assertThat(rejectKeys)
                .as("daily-rejects/ prefix should list exactly 2 GDG-equivalent rejection files")
                .hasSize(2)
                .allMatch(key -> key.startsWith("daily-rejects/"));

        // List objects with prefix filter for combined-transactions (COMBTRAN GDG)
        ListObjectsV2Response combinedResponse = s3Client.listObjectsV2(
                b -> b.bucket(BATCH_OUTPUT_BUCKET).prefix("combined-transactions/"));

        assertThat(combinedResponse.contents())
                .as("combined-transactions/ prefix should list exactly 1 combined file")
                .hasSize(1);
        assertThat(combinedResponse.contents().getFirst().key())
                .as("Combined transactions key should match the uploaded file key")
                .isEqualTo("combined-transactions/20240115-120000.dat");

        log.info("S3 object listing with prefix filter verified — LISTCAT equivalent works correctly");
    }

    /**
     * Verifies S3 object deletion and confirms the object no longer exists.
     *
     * <p>This validates the GDG SCRATCH semantics from DEFGDGB.jcl:
     * <pre>
     * DEFINE GENERATIONDATAGROUP(NAME(AWS.M2.CARDDEMO.TRANSACT.BKUP) LIMIT(5) SCRATCH)
     * </pre>
     *
     * <p>GDG SCRATCH disposition means when the generation limit (5) is exceeded,
     * the oldest generation is deleted (scratched). In the Java migration, this
     * maps to S3 object deletion — when the generation count exceeds the limit,
     * the oldest timestamped object is deleted. This test verifies that S3 object
     * deletion works correctly and that subsequent access attempts properly fail
     * with {@link NoSuchKeyException}.
     */
    @Test
    void testDeleteObject() {
        log.info("Testing S3 object deletion — GDG SCRATCH semantics validation");

        // Upload an object to simulate a GDG generation
        // Maps from: DEFINE GDG AWS.M2.CARDDEMO.TRANSACT.BKUP (LIMIT 5, SCRATCH)
        String objectKey = "transact-backup/scratch-test-gen-001.dat";

        s3Client.putObject(
                b -> b.bucket(BATCH_OUTPUT_BUCKET).key(objectKey),
                RequestBody.fromString("BACKUP-DATA-FOR-DELETION-TEST", StandardCharsets.UTF_8));
        log.info("Uploaded test object for deletion: {}/{}", BATCH_OUTPUT_BUCKET, objectKey);

        // Verify the object exists before deletion
        s3Client.headObject(b -> b.bucket(BATCH_OUTPUT_BUCKET).key(objectKey));
        log.info("Confirmed object exists before deletion");

        // Delete the object — simulates GDG SCRATCH (oldest generation removal)
        s3Client.deleteObject(b -> b.bucket(BATCH_OUTPUT_BUCKET).key(objectKey));
        log.info("Deleted S3 object: {}/{}", BATCH_OUTPUT_BUCKET, objectKey);

        // Verify the object no longer exists — headObject should throw NoSuchKeyException
        assertThatThrownBy(() -> s3Client.headObject(
                b -> b.bucket(BATCH_OUTPUT_BUCKET).key(objectKey)))
                .as("Accessing deleted S3 object should throw NoSuchKeyException (GDG SCRATCH confirmed)")
                .isInstanceOf(NoSuchKeyException.class);

        log.info("S3 object deletion verified — GDG SCRATCH semantics confirmed");
    }

    /**
     * Verifies dual-format statement file upload to the statements S3 bucket.
     *
     * <p>This validates the CREASTMT.JCL statement generation output pattern:
     * <ul>
     *   <li><strong>COBOL:</strong> STEP040 EXEC PGM=CBSTM03A produces:
     *     <ul>
     *       <li>STMTFILE DD DSN=AWS.M2.CARDDEMO.STATEMNT.PS (LRECL=80 text)</li>
     *       <li>HTMLFILE DD DSN=AWS.M2.CARDDEMO.STATEMNT.HTML (LRECL=100 HTML)</li>
     *     </ul>
     *   </li>
     *   <li><strong>Java:</strong> {@code StatementWriter} produces S3 objects:
     *     <ul>
     *       <li>{@code statements/{acct-id}/statement-{timestamp}.txt}</li>
     *       <li>{@code statements/{acct-id}/statement-{timestamp}.html}</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p>The key pattern uses account ID as a directory prefix to organize
     * statements per account (matching the CREASTMT.JCL processing which iterates
     * through the XREFFILE card cross-reference to process each card's transactions).
     *
     * @throws IOException if reading the S3 object response stream fails
     */
    @Test
    void testStatementsBucketOperations() throws IOException {
        log.info("Testing statements bucket — dual-format statement upload (text + HTML)");

        // Account ID used in key path (from ACCTDATA VSAM — KEYS 11,0)
        String accountId = "00000000001";
        String timestamp = "20240131-235959";

        // Text statement — replaces STMTFILE DD DSN=AWS.M2.CARDDEMO.STATEMNT.PS
        String textKey = "statements/" + accountId + "/statement-" + timestamp + ".txt";
        String textContent = """
                ============================================================
                         CARDDEMO CREDIT CARD STATEMENT
                ============================================================
                Account: 00000000001    Period: 01/2024
                Card: 1234567890123456
                ------------------------------------------------------------
                Date        Description                        Amount
                2024-01-15  Gas Station Fill Up                  $50.00
                2024-01-20  Monthly Internet Service            $125.00
                ------------------------------------------------------------
                                          Statement Total:     $175.00
                ============================================================""";

        // HTML statement — replaces HTMLFILE DD DSN=AWS.M2.CARDDEMO.STATEMNT.HTML
        String htmlKey = "statements/" + accountId + "/statement-" + timestamp + ".html";
        String htmlContent = """
                <html><head><title>CardDemo Statement - 00000000001</title></head>
                <body>
                <h1>CARDDEMO CREDIT CARD STATEMENT</h1>
                <p>Account: 00000000001 | Period: 01/2024</p>
                <p>Card: 1234567890123456</p>
                <table border="1">
                <tr><th>Date</th><th>Description</th><th>Amount</th></tr>
                <tr><td>2024-01-15</td><td>Gas Station Fill Up</td><td>$50.00</td></tr>
                <tr><td>2024-01-20</td><td>Monthly Internet Service</td><td>$125.00</td></tr>
                </table>
                <p><strong>Statement Total: $175.00</strong></p>
                </body></html>""";

        // Upload text statement
        s3Client.putObject(
                b -> b.bucket(STATEMENTS_BUCKET).key(textKey),
                RequestBody.fromString(textContent, StandardCharsets.UTF_8));
        log.info("Uploaded text statement: {}/{}", STATEMENTS_BUCKET, textKey);

        // Upload HTML statement
        s3Client.putObject(
                b -> b.bucket(STATEMENTS_BUCKET).key(htmlKey),
                RequestBody.fromString(htmlContent, StandardCharsets.UTF_8));
        log.info("Uploaded HTML statement: {}/{}", STATEMENTS_BUCKET, htmlKey);

        // Verify both files exist with correct content — text statement
        try (ResponseInputStream<GetObjectResponse> textStream = s3Client.getObject(
                b -> b.bucket(STATEMENTS_BUCKET).key(textKey))) {

            String downloadedText = new String(textStream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(downloadedText)
                    .as("Downloaded text statement should match uploaded content")
                    .isEqualTo(textContent);
        }

        // Verify HTML statement content
        try (ResponseInputStream<GetObjectResponse> htmlStream = s3Client.getObject(
                b -> b.bucket(STATEMENTS_BUCKET).key(htmlKey))) {

            String downloadedHtml = new String(htmlStream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(downloadedHtml)
                    .as("Downloaded HTML statement should match uploaded content")
                    .isEqualTo(htmlContent);
        }

        // Verify listing shows both statement files for this account
        ListObjectsV2Response listResponse = s3Client.listObjectsV2(
                b -> b.bucket(STATEMENTS_BUCKET).prefix("statements/" + accountId + "/"));

        List<String> statementKeys = listResponse.contents().stream()
                .map(S3Object::key)
                .toList();

        assertThat(statementKeys)
                .as("Account statement listing should contain both text and HTML files")
                .hasSize(2)
                .containsExactlyInAnyOrder(textKey, htmlKey);

        log.info("Dual-format statement upload verified — CREASTMT.JCL output pattern confirmed");
    }

    /**
     * Verifies GDG generation replacement semantics using timestamped S3 object keys.
     *
     * <p>This is the core validation for Decision D-003: S3 versioned objects
     * replace COBOL GDG (Generation Data Group) bases. The DEFGDGB.jcl defines
     * 6 GDG bases, each with {@code LIMIT(5) SCRATCH} — keep last 5 generations,
     * delete oldest when exceeded.
     *
     * <p>The test simulates GDG generation creation:
     * <ul>
     *   <li>Each GDG generation (+1, +2, ...) maps to a new S3 object with a
     *       generation-number prefix and timestamp in the key</li>
     *   <li>The prefix {@code transact-backup/} corresponds to GDG base
     *       {@code AWS.M2.CARDDEMO.TRANSACT.BKUP} (LIMIT 5, SCRATCH)</li>
     *   <li>Multiple generations can coexist in S3, listed by prefix</li>
     * </ul>
     *
     * <p>In production, S3 lifecycle policies enforce the LIMIT(5) retention,
     * deleting objects older than 5 generations. This test validates the
     * generation creation and listing mechanics.
     */
    @Test
    void testVersionedObjectSemantics() {
        log.info("Testing GDG generation replacement — versioned S3 object semantics (Decision D-003)");

        // Simulate creating multiple GDG generations for AWS.M2.CARDDEMO.TRANSACT.BKUP
        // GDG reference notation: (0) = current, (+1) = next, (-1) = previous
        // TRANREPT.jcl: DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(+1) — creates new generation
        String generationPrefix = "transact-backup/";

        // Generation 1 — first backup of TRANSACT VSAM (TRANREPT.jcl STEP05R output)
        String gen1Key = generationPrefix + "gen-001-20240101-000000.dat";
        s3Client.putObject(
                b -> b.bucket(BATCH_OUTPUT_BUCKET).key(gen1Key),
                RequestBody.fromString("TRANSACT-BKUP-GEN-001-20240101", StandardCharsets.UTF_8));
        log.info("Created GDG generation 1: {}", gen1Key);

        // Generation 2 — second backup after next batch cycle
        String gen2Key = generationPrefix + "gen-002-20240102-000000.dat";
        s3Client.putObject(
                b -> b.bucket(BATCH_OUTPUT_BUCKET).key(gen2Key),
                RequestBody.fromString("TRANSACT-BKUP-GEN-002-20240102", StandardCharsets.UTF_8));
        log.info("Created GDG generation 2: {}", gen2Key);

        // Generation 3 — third backup
        String gen3Key = generationPrefix + "gen-003-20240103-000000.dat";
        s3Client.putObject(
                b -> b.bucket(BATCH_OUTPUT_BUCKET).key(gen3Key),
                RequestBody.fromString("TRANSACT-BKUP-GEN-003-20240103", StandardCharsets.UTF_8));
        log.info("Created GDG generation 3: {}", gen3Key);

        // List all generations with prefix filter — equivalent to LISTCAT for GDG
        ListObjectsV2Response listResponse = s3Client.listObjectsV2(
                b -> b.bucket(BATCH_OUTPUT_BUCKET).prefix(generationPrefix));

        List<String> generationKeys = listResponse.contents().stream()
                .map(S3Object::key)
                .toList();

        assertThat(generationKeys)
                .as("All 3 GDG generations should be present in the listing")
                .hasSize(3)
                .containsExactlyInAnyOrder(gen1Key, gen2Key, gen3Key);

        // Verify generation keys follow the generation prefix pattern
        assertThat(generationKeys)
                .as("All generation keys should start with the GDG-equivalent prefix")
                .allMatch(key -> key.startsWith(generationPrefix));

        // Verify natural ordering — generations should be sortable by key
        List<String> sortedKeys = generationKeys.stream().sorted().toList();
        assertThat(sortedKeys)
                .as("Generation keys should sort in chronological order (gen-001 < gen-002 < gen-003)")
                .containsExactly(gen1Key, gen2Key, gen3Key);

        // Simulate GDG SCRATCH — delete oldest generation (gen-001) when LIMIT exceeded
        s3Client.deleteObject(b -> b.bucket(BATCH_OUTPUT_BUCKET).key(gen1Key));
        log.info("Scratched oldest generation (LIMIT enforcement): {}", gen1Key);

        // Verify only 2 generations remain after SCRATCH
        ListObjectsV2Response postScratchResponse = s3Client.listObjectsV2(
                b -> b.bucket(BATCH_OUTPUT_BUCKET).prefix(generationPrefix));

        List<String> remainingKeys = postScratchResponse.contents().stream()
                .map(S3Object::key)
                .toList();

        assertThat(remainingKeys)
                .as("After GDG SCRATCH, only 2 remaining generations should exist")
                .hasSize(2)
                .containsExactlyInAnyOrder(gen2Key, gen3Key)
                .doesNotContain(gen1Key);

        log.info("GDG generation replacement verified — Decision D-003 validated "
                + "(creation, listing, ordering, SCRATCH semantics)");
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Builds an S3 client directly from the LocalStack container properties.
     *
     * <p>Used by {@code @BeforeAll} and {@code @AfterAll} static lifecycle methods
     * where the Spring application context (and therefore the Spring-injected
     * {@link S3Client} bean from {@link com.cardemo.config.AwsConfig}) is not
     * yet available.
     *
     * <p>CRITICAL: {@code forcePathStyle(true)} is required for LocalStack S3
     * compatibility (AAP §0.7.6 documented pitfall). Without path-style access,
     * S3 operations fail with DNS resolution errors for virtual-hosted bucket names
     * like {@code carddemo-batch-input.localhost:4566}.
     *
     * @return S3Client configured for the LocalStack container
     */
    private static S3Client buildS3Client() {
        return S3Client.builder()
                .endpointOverride(localstack.getEndpoint())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                localstack.getAccessKey(),
                                localstack.getSecretKey())))
                .region(Region.of(localstack.getRegion()))
                .forcePathStyle(true)
                .build();
    }

    /**
     * Cleans up a single S3 bucket by deleting all objects then the bucket itself.
     *
     * <p>Lists all objects in the bucket, deletes each one, then deletes the
     * empty bucket. All operations are wrapped in try-catch to ensure cleanup
     * continues even if individual deletions fail.
     *
     * <p>This method implements the "destroy" half of the AAP §0.7.7 lifecycle:
     * {@code @BeforeAll create → test execute → @AfterAll destroy}.
     *
     * @param client     S3 client to use for cleanup operations
     * @param bucketName name of the bucket to clean and delete
     */
    private static void cleanupBucket(S3Client client, String bucketName) {
        try {
            // List and delete all objects in the bucket, handling pagination
            ListObjectsV2Response response = client.listObjectsV2(b -> b.bucket(bucketName));
            deleteObjectsFromResponse(client, bucketName, response);

            // Handle pagination — continue listing and deleting if truncated
            while (Boolean.TRUE.equals(response.isTruncated())) {
                String continuationToken = response.nextContinuationToken();
                response = client.listObjectsV2(b -> b.bucket(bucketName)
                        .continuationToken(continuationToken));
                deleteObjectsFromResponse(client, bucketName, response);
            }

            // Delete the empty bucket
            client.deleteBucket(b -> b.bucket(bucketName));
            log.info("Cleaned up S3 bucket: {} (all objects deleted + bucket removed)", bucketName);
        } catch (Exception e) {
            log.warn("Cleanup error for bucket {}: {}", bucketName, e.getMessage());
        }
    }

    /**
     * Deletes all S3 objects listed in a single {@link ListObjectsV2Response} page.
     *
     * @param client     S3 client to use for deletion
     * @param bucketName target bucket name
     * @param response   listing response containing the objects to delete
     */
    private static void deleteObjectsFromResponse(S3Client client, String bucketName,
                                                   ListObjectsV2Response response) {
        for (S3Object obj : response.contents()) {
            client.deleteObject(b -> b.bucket(bucketName).key(obj.key()));
        }
    }
}
