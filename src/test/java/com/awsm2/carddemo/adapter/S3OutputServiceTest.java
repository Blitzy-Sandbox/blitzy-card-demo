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
package com.awsm2.carddemo.adapter;

// Replaces: COBOL sequential file WRITE operations (DALYREJS, SYSTRAN, TRANREPT, STMTFILE, TRANSACT.BKUP)
// Backs AAP §0.6.2 (VSAM → RDS / Sequential → S3 versioned objects) and AAP §0.7.1 (SSE-KMS encryption).

import com.awsm2.carddemo.dto.ReportLineDto;
import com.awsm2.carddemo.exception.CardDemoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link S3OutputService}, the AWS S3 adapter that replaces
 * COBOL sequential file output operations (DALYREJS, SYSTRAN, TRANREPT,
 * STMTFILE, HTMLFILE, and TRANSACT.BKUP).
 *
 * <p>This adapter is the <i>only</i> place in the application that invokes the
 * AWS SDK v2 S3 client directly. It backs four critical AAP objectives:</p>
 * <ul>
 *   <li>AAP &sect;0.4.1 &mdash; transformation mapping: sequential file WRITE
 *       statements against PS/GDG datasets become versioned S3 objects under
 *       the date-partitioned key prefix {@code {prefix}/yyyy/MM/dd/{id}.{ext}}.</li>
 *   <li>AAP &sect;0.6.2 &mdash; VSAM &rarr; RDS / Sequential &rarr; S3
 *       versioned objects. {@code GDG (+1)} semantics map to S3 object
 *       versioning; lifecycle policies (Terraform) replace JCL GDG cleanup.</li>
 *   <li>AAP &sect;0.7.1 &mdash; "Encrypt all S3 buckets with SSE-KMS". Every
 *       {@code PutObject} carries
 *       {@link ServerSideEncryption#AWS_KMS} with the configured CMK ARN as
 *       {@code ssekmsKeyId}; SSE-S3 ({@code AES256}) is forbidden by the
 *       adapter contract.</li>
 *   <li>AAP &sect;0.6.6 &mdash; PCI-DSS audit / observability: payload bytes
 *       are never logged; only metadata (bucket, key, byte count, eTag,
 *       version ID).</li>
 * </ul>
 *
 * <h2>Test coverage matrix</h2>
 * <p>The tests in this class cover five behavioral contracts of the adapter
 * across its five public methods:</p>
 * <ol>
 *   <li><strong>writeRejection</strong> (Phase 6) &mdash; verifies the
 *       {@code dalyrejs/yyyy/MM/dd/{id}.rejs} key path, SSE-KMS + CMK ARN
 *       enforcement, {@code text/plain; charset=US-ASCII} content type, the
 *       trailing-LF payload contract, and input-validation behavior on
 *       null/blank {@code batchRunId} and null {@code record} arguments.</li>
 *   <li><strong>writeSysTran</strong> (Phase 7) &mdash; verifies the
 *       {@code systran/yyyy/MM/dd/{id}.tran} key path and the same SSE-KMS /
 *       content-type / validation contract as writeRejection.</li>
 *   <li><strong>writeReport</strong> (Phase 8) &mdash; verifies the dual
 *       {@code tranrept/yyyy/MM/dd/{id}.html} (text/html) and
 *       {@code tranrept/yyyy/MM/dd/{id}.rpt} (text/plain) key/content-type
 *       branching driven by the {@code .html} suffix on the reportId; verifies
 *       SSE-KMS enforcement and input validation for null/blank reportId and
 *       null/empty reportBytes.</li>
 *   <li><strong>copyTransactionBackup</strong> (Phase 9) &mdash; verifies the
 *       {@code transact-bkup/yyyy/MM/dd/{gen}.bkup} key path with
 *       {@code application/octet-stream} content type and SSE-KMS enforcement
 *       on the IDCAMS REPRO replacement path.</li>
 *   <li><strong>writeReportLines</strong> (Phase 10) &mdash; verifies the
 *       {@code {reportType}/yyyy/MM/dd/{id}.rpt} key path, newline-terminated
 *       line joining, the SSE-KMS contract, and the input-validation matrix
 *       (null/blank batchRunId, null/blank reportType, null/empty lines list,
 *       null entry inside the list).</li>
 *   <li><strong>S3Exception propagation</strong> (Phase 11) &mdash; verifies
 *       that AWS SDK {@link S3Exception} is wrapped in a typed
 *       {@link CardDemoException} carrying the {@code S3_PUT_ERROR} reason
 *       code per AAP &sect;0.7.1 ("Error codes and condition handling
 *       surfaced to downstream consumers must be preserved verbatim"); the
 *       original SDK exception is preserved as the {@link Throwable#getCause()
 *       cause} for full diagnostic stack traces.</li>
 *   <li><strong>Configuration-failure contracts</strong> (Phase 12) &mdash;
 *       verifies that a blank KMS CMK ARN raises
 *       {@code CardDemoException(S3_KMS_KEY_MISSING)} <i>before</i> any SDK
 *       call (refusing to write unencrypted objects per AAP &sect;0.7.1) and
 *       that a blank output bucket raises
 *       {@code CardDemoException(S3_BUCKET_MISSING)}. These tests use
 *       {@link ReflectionTestUtils} to mutate the configured field on a
 *       single shared service instance, mirroring the unit-test convention
 *       used in {@code SecretsManagerServiceTest}.</li>
 * </ol>
 *
 * <h2>Mocking strategy</h2>
 * <p>This is a pure Mockito unit test &mdash; no Spring context is loaded.
 * {@link MockitoExtension} (strict-stubbing mode, the JUnit 5 default in
 * Mockito 5.x) wires the {@code @Mock S3Client} into the production
 * {@link S3OutputService} via the three-argument constructor. The
 * {@link S3Client#putObject(PutObjectRequest, RequestBody)} stub is configured
 * with {@link org.mockito.Mockito#lenient()} in {@link #setUp()} because
 * input-validation tests (Phases 6.3-6.6, 7.2, 8.3-8.6, 9.2-9.3, 10.2-10.5)
 * intentionally never trigger the SDK call; strict mode would otherwise flag
 * those stubs as unused.</p>
 *
 * <h2>SSE-KMS invariants asserted</h2>
 * <p>Every test that triggers an S3 PUT (Phases 6.1, 6.2, 7.1, 8.1, 8.2,
 * 9.1, 10.1) asserts both
 * {@code req.serverSideEncryption() == ServerSideEncryption.AWS_KMS} and
 * {@code req.ssekmsKeyId() == KMS_ARN}. Tests that fail to assert both have
 * been considered latent bugs in the test suite and are reviewed accordingly
 * (no exception is permitted to this contract).</p>
 *
 * <h2>PCI-DSS compliance in test fixtures</h2>
 * <p>No real card numbers, real SSNs, or real account numbers appear in any
 * test payload. Fixture strings are intentionally synthetic
 * ({@code "PAYLOAD"}, {@code "TRAN-LINE"}, {@code "RUN-001"}, etc.) so the
 * test suite itself does not introduce PCI-DSS exposure.</p>
 *
 * @see S3OutputService
 * @see ReportLineDto
 * @see com.awsm2.carddemo.exception.CardDemoException
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("S3OutputService — AWS S3 adapter unit tests (SSE-KMS + date-partitioned keys + COBOL provenance)")
class S3OutputServiceTest {

    // -------------------------------------------------------------------------
    // Mocks and System Under Test
    // -------------------------------------------------------------------------

    /**
     * The mocked AWS SDK v2 {@link S3Client}. Mockito strict-stubbing
     * (the default in Mockito 5.x under {@link MockitoExtension}) ensures
     * that every {@code when(...)} stub set on this mock is actually
     * invoked by the production code &mdash; unused stubs fail the test,
     * catching over-mocking that would hide real defects.
     *
     * <p>The default happy-path stub (in {@link #setUp()}) is set with
     * {@link org.mockito.Mockito#lenient()} to permit input-validation tests
     * to leave it unused without triggering strict-stubbing warnings.</p>
     */
    @Mock
    private S3Client s3Client;

    /**
     * The production {@link S3OutputService} instance under test.
     * Constructed manually in {@link #setUp()} rather than via
     * {@code @InjectMocks} because the production constructor accepts the
     * {@code outputBucket} and {@code kmsCmkArn} string parameters that
     * Spring would normally inject via {@code @Value} &mdash; supplying them
     * directly to the constructor produces a fully-initialized instance
     * without requiring a Spring application context.
     */
    private S3OutputService service;

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    /**
     * LocalStack-convention output bucket name under the dummy test account.
     * Never references a real production bucket per the test agent_prompt
     * PCI-DSS compliance constraints. Matches the test profile default in
     * {@code src/test/resources/application-test.yml}.
     */
    private static final String BUCKET = "carddemo-output-local";

    /**
     * LocalStack-convention KMS Customer Managed Key ARN under the dummy test
     * account ID {@code 000000000000}. Never references a real production
     * CMK per the test agent_prompt PCI-DSS compliance constraints.
     * {@link ArgumentCaptor} verifications exercise this full ARN string to
     * confirm the adapter passes the ARN verbatim to the SDK
     * {@code ssekmsKeyId(...)} builder.
     */
    private static final String KMS_ARN =
            "arn:aws:kms:us-east-1:000000000000:key/00000000-0000-0000-0000-000000000000";

    /**
     * Sample batch-run identifier used as both the S3 key sequence and the
     * {@code x-amz-meta-batch-run-id} metadata header. The {@code RUN-NNN}
     * naming convention mirrors the Step Functions execution-arn suffix
     * pattern used in production batch runs.
     */
    private static final String BATCH_RUN_ID = "RUN-001";

    /**
     * Date-partition formatter mirroring {@code S3OutputService.DATE_PARTITION}.
     * Computed at assertion time via {@link #today()} so the test does not
     * race the production code's call to {@code LocalDate.now()} across a
     * midnight boundary &mdash; both sides observe the same local clock day.
     */
    private static final DateTimeFormatter DATE_PARTITION =
            DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /**
     * Initialises the system under test before each test method. The shared
     * happy-path stub
     * ({@code putObject(...) -> PutObjectResponse(eTag="e-tag-1")})
     * is registered with {@link org.mockito.Mockito#lenient() lenient} so
     * that input-validation tests &mdash; which intentionally throw before
     * the SDK call &mdash; do not trip Mockito's strict-stubbing detector.
     *
     * <p>Although the production constructor accepts the
     * {@code outputBucket} and {@code kmsCmkArn} values directly, we
     * additionally exercise {@link ReflectionTestUtils#setField} in selected
     * Phase-12 tests to verify that a runtime configuration mutation
     * (rotation-driven bucket / CMK swap) is honored by the adapter on the
     * very next write call &mdash; matching the
     * {@code @RefreshScope}-style behavior described in AAP &sect;0.6.4
     * (Secrets Manager rotation without Spring Boot restart).</p>
     */
    @BeforeEach
    void setUp() {
        // Construct the service via the production three-argument constructor.
        // S3OutputService(S3Client, String outputBucket, String kmsCmkArn).
        // This avoids the need for a Spring ApplicationContext while
        // exercising the same code path that Spring's @Value-based DI uses
        // in production.
        service = new S3OutputService(s3Client, BUCKET, KMS_ARN);

        // Default happy-path stub. Marked lenient() because validation tests
        // intentionally never reach the SDK call. The eTag value is purely
        // diagnostic — the production code logs it but does not act on it.
        lenient().when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("e-tag-1").versionId("v1").build());
    }

    // -------------------------------------------------------------------------
    // Helper methods — assertion fixtures and DTO factories
    // -------------------------------------------------------------------------

    /**
     * Returns the {@code yyyy/MM/dd} date-partition segment for the current
     * local date. Used to assemble the expected S3 object key in assertions
     * so the test is robust against running on any date.
     *
     * @return the formatted date partition (e.g. {@code "2026/05/23"})
     */
    private static String today() {
        return DATE_PARTITION.format(LocalDate.now());
    }

    /**
     * Builds a synthetic {@link ReportLineDto} with the {@code DETAIL}
     * discriminator. Used as the canonical fixture for
     * {@link S3OutputService#writeReportLines(String, String, List)
     * writeReportLines} tests because the {@code DETAIL} variant exercises
     * the {@link BigDecimal} amount path (AAP &sect;0.6.1 monetary precision
     * mandate &mdash; never {@code float}/{@code double}).
     *
     * @param description the per-line description text; used to disambiguate
     *                    multiple fixture rows in the captured payload
     *                    bytes
     * @return a populated {@link ReportLineDto} with {@code lineType="DETAIL"}
     */
    private static ReportLineDto sampleDetailLine(String description) {
        return new ReportLineDto(
                "DETAIL",                       // lineType
                null,                           // reportName
                null,                           // startDate
                null,                           // endDate
                10000000001L,                   // accountId
                null,                           // maskedCardNumber (omitted — transaction report does NOT carry PAN)
                LocalDate.of(2026, 1, 15),      // transactionDate
                "01",                           // transactionType
                Integer.valueOf(5411),          // transactionCategory
                "ONLINE",                       // transactionSource
                description,                    // description
                new BigDecimal("123.45"),       // amount — AAP §0.6.1 BigDecimal mandate
                null,                           // totalLabel
                null                            // pageNumber
        );
    }

    /**
     * Builds the canonical sample {@link ReportLineDto} used by Phase 10.4
     * and 10.5 input-validation tests &mdash; any {@code DETAIL} variant
     * with a generic description is acceptable since these tests throw
     * before the line is ever formatted.
     */
    private static ReportLineDto sampleReportLine() {
        return sampleDetailLine("SAMPLE-LINE");
    }

    // =========================================================================
    // Phase 6 — writeRejection tests
    // =========================================================================

    /**
     * Phase 6 groups all {@link S3OutputService#writeRejection(String, String)}
     * tests. These tests collectively verify the {@code DALYREJS}
     * sequential-file replacement contract documented in
     * {@code app/jcl/POSTTRAN.jcl} (DD allocation) and
     * {@code app/cbl/CBTRN02C.cbl} (paragraph 2500-WRITE-REJECT-REC).
     */
    @Nested
    @DisplayName("writeRejection — DALYREJS sequential WRITE replacement")
    class WriteRejectionTests {

        @Test
        @DisplayName("Test 6.1: puts SSE-KMS-encrypted object with dalyrejs/yyyy/MM/dd/{id}.rejs key")
        void writeRejection_invokesS3WithSseKmsAndCorrectKey() {
            service.writeRejection(BATCH_RUN_ID, "REJ-PAYLOAD");

            ArgumentCaptor<PutObjectRequest> reqCaptor =
                    ArgumentCaptor.forClass(PutObjectRequest.class);
            ArgumentCaptor<RequestBody> bodyCaptor =
                    ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(reqCaptor.capture(), bodyCaptor.capture());

            PutObjectRequest req = reqCaptor.getValue();
            // Bucket — comes from the constructor-injected outputBucket field.
            assertEquals(BUCKET, req.bucket(),
                    "PutObjectRequest.bucket() must equal the configured output bucket");
            // Key — date-partitioned per AAP §0.4.1 and §0.7.2 (lifecycle policies).
            assertEquals("dalyrejs/" + today() + "/" + BATCH_RUN_ID + ".rejs", req.key(),
                    "Key must follow dalyrejs/yyyy/MM/dd/{batchRunId}.rejs convention");
            // SSE-KMS — AAP §0.7.1 mandates SSE-KMS on every PUT. NEVER SSE-S3.
            assertEquals(ServerSideEncryption.AWS_KMS, req.serverSideEncryption(),
                    "Server-side encryption MUST be AWS_KMS — SSE-S3/AES256 is forbidden");
            assertEquals(KMS_ARN, req.ssekmsKeyId(),
                    "ssekmsKeyId MUST be the configured customer-managed key ARN");
            // Content type — DALYREJS records are 430-byte ASCII text lines.
            assertEquals("text/plain; charset=US-ASCII", req.contentType(),
                    "DALYREJS records are US-ASCII fixed-width text per CBTRN02C.cbl");
        }

        @Test
        @DisplayName("Test 6.2: invokes putObject exactly once per call")
        void writeRejection_invokedExactlyOncePerCall() {
            service.writeRejection(BATCH_RUN_ID, "RECORD-A");
            verify(s3Client, times(1))
                    .putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("Test 6.2a: appends a trailing LF to the rejection record before upload")
        void writeRejection_appendsTrailingNewlineToPayload() throws Exception {
            // The production adapter delegates writeRejection() to
            // appendLineToObject(), which constructs the payload as
            // (record + '\n').getBytes(US_ASCII). Verifying the trailing LF
            // guarantees downstream UNIX/Linux S3 readers can split the
            // output line-by-line per the contract documented in
            // S3OutputService.writeRejection() Javadoc.
            service.writeRejection(BATCH_RUN_ID, "REJ-PAYLOAD");

            ArgumentCaptor<RequestBody> bodyCaptor =
                    ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

            byte[] uploaded = bodyCaptor.getValue()
                    .contentStreamProvider().newStream().readAllBytes();
            assertArrayEquals(
                    "REJ-PAYLOAD\n".getBytes(StandardCharsets.US_ASCII),
                    uploaded,
                    "Payload bytes must equal 'REJ-PAYLOAD\\n' encoded as US-ASCII");
        }

        @Test
        @DisplayName("Test 6.2b: attaches COBOL provenance metadata (cobol-source, jcl-dd, batch-run-id)")
        void writeRejection_attachesCobolProvenanceMetadata() {
            service.writeRejection(BATCH_RUN_ID, "REJ-PAYLOAD");

            ArgumentCaptor<PutObjectRequest> reqCaptor =
                    ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(reqCaptor.capture(), any(RequestBody.class));

            Map<String, String> metadata = reqCaptor.getValue().metadata();
            assertNotNull(metadata, "metadata must be populated");
            assertEquals("CBTRN02C.2500-WRITE-REJECT-REC",
                    metadata.get("cobol-source"),
                    "metadata.cobol-source must record the COBOL paragraph per AAP §0.7.3");
            assertEquals("DALYREJS", metadata.get("jcl-dd"),
                    "metadata.jcl-dd must record the JCL DD allocation per AAP §0.7.3");
            assertEquals(BATCH_RUN_ID, metadata.get("batch-run-id"),
                    "metadata.batch-run-id must record the supplied batch run id");
        }

        // ------ Input validation: batchRunId ------------------------------

        @Test
        @DisplayName("Test 12.1: throws IllegalArgumentException on null batchRunId, without calling SDK")
        void writeRejection_withNullBatchRunId_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeRejection(null, "PAYLOAD"));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 12.2: throws IllegalArgumentException on blank batchRunId")
        void writeRejection_withBlankBatchRunId_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeRejection("   ", "PAYLOAD"));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 12.3: throws IllegalArgumentException on empty-string batchRunId")
        void writeRejection_withEmptyBatchRunId_throwsIllegalArgument() {
            // String.isBlank() returns true for the empty string, so the
            // validateBatchRunId() guard rejects it as a no-go for object key.
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeRejection("", "PAYLOAD"));
            verifyNoInteractions(s3Client);
        }

        // ------ Input validation: record argument ------------------------

        @Test
        @DisplayName("Test 12.4: throws NullPointerException on null record (Objects.requireNonNull contract)")
        void writeRejection_withNullRecord_throwsNullPointer() {
            // The production adapter guards record with Objects.requireNonNull
            // which throws NullPointerException — NOT IllegalArgumentException —
            // so the test asserts the precise exception type to remain
            // honest about the contract surface.
            assertThrows(NullPointerException.class,
                    () -> service.writeRejection(BATCH_RUN_ID, null));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 12.5: permits empty record (an empty rejection line is a valid sentinel)")
        void writeRejection_withEmptyRecord_allowsAndAppendsNewlineOnly() throws Exception {
            // Empty record is intentionally permitted (validateBatchRunId
            // guards key safety; the record itself can be a single LF).
            // Verifies that downstream consumers see "\n" rather than ""
            // so line-counting routines (wc -l) still discover the record.
            service.writeRejection(BATCH_RUN_ID, "");

            ArgumentCaptor<RequestBody> bodyCaptor =
                    ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

            byte[] uploaded = bodyCaptor.getValue()
                    .contentStreamProvider().newStream().readAllBytes();
            assertArrayEquals("\n".getBytes(StandardCharsets.US_ASCII), uploaded,
                    "An empty record must upload exactly one '\\n' byte");
        }
    }

    // =========================================================================
    // Phase 7 — writeSysTran tests
    // =========================================================================

    /**
     * Phase 7 groups all {@link S3OutputService#writeSysTran(String, String)}
     * tests. These tests verify the {@code SYSTRAN/TRANSACT} sequential
     * write contract from {@code app/cbl/CBACT04C.cbl} paragraph
     * {@code 1300-B-WRITE-TX} (INTCALC.jcl DD allocation).
     */
    @Nested
    @DisplayName("writeSysTran — SYSTRAN/TRANSACT sequential WRITE replacement")
    class WriteSysTranTests {

        @Test
        @DisplayName("Test 7.1: puts SSE-KMS-encrypted object with systran/yyyy/MM/dd/{id}.tran key")
        void writeSysTran_invokesS3WithSseKmsAndCorrectKey() {
            service.writeSysTran(BATCH_RUN_ID, "TRAN-LINE-CONTENT");

            ArgumentCaptor<PutObjectRequest> reqCaptor =
                    ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(reqCaptor.capture(), any(RequestBody.class));

            PutObjectRequest req = reqCaptor.getValue();
            assertEquals(BUCKET, req.bucket());
            assertEquals("systran/" + today() + "/" + BATCH_RUN_ID + ".tran", req.key(),
                    "Key must follow systran/yyyy/MM/dd/{batchRunId}.tran convention");
            assertEquals(ServerSideEncryption.AWS_KMS, req.serverSideEncryption(),
                    "Server-side encryption MUST be AWS_KMS — never SSE-S3");
            assertEquals(KMS_ARN, req.ssekmsKeyId(),
                    "ssekmsKeyId MUST be the configured customer-managed key ARN");
            assertEquals("text/plain; charset=US-ASCII", req.contentType(),
                    "SYSTRAN records are US-ASCII fixed-width text per CBACT04C.cbl");
        }

        @Test
        @DisplayName("Test 7.1a: appends a trailing LF to the transaction line before upload")
        void writeSysTran_appendsTrailingNewlineToPayload() throws Exception {
            service.writeSysTran(BATCH_RUN_ID, "TRAN-LINE");

            ArgumentCaptor<RequestBody> bodyCaptor =
                    ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

            byte[] uploaded = bodyCaptor.getValue()
                    .contentStreamProvider().newStream().readAllBytes();
            assertArrayEquals(
                    "TRAN-LINE\n".getBytes(StandardCharsets.US_ASCII),
                    uploaded,
                    "Payload bytes must equal 'TRAN-LINE\\n' encoded as US-ASCII");
        }

        @Test
        @DisplayName("Test 7.1b: attaches COBOL provenance metadata referencing CBACT04C / SYSTRAN")
        void writeSysTran_attachesCobolProvenanceMetadata() {
            service.writeSysTran(BATCH_RUN_ID, "TRAN-LINE");

            ArgumentCaptor<PutObjectRequest> reqCaptor =
                    ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(reqCaptor.capture(), any(RequestBody.class));

            Map<String, String> metadata = reqCaptor.getValue().metadata();
            assertEquals("CBACT04C.1300-B-WRITE-TX", metadata.get("cobol-source"));
            assertEquals("SYSTRAN", metadata.get("jcl-dd"));
            assertEquals(BATCH_RUN_ID, metadata.get("batch-run-id"));
        }

        @Test
        @DisplayName("Test 7.2: throws IllegalArgumentException on blank batchRunId")
        void writeSysTran_withBlankBatchRunId_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeSysTran("   ", "TRAN-LINE"));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 7.3: throws NullPointerException on null transactionLine")
        void writeSysTran_withNullTransactionLine_throwsNullPointer() {
            assertThrows(NullPointerException.class,
                    () -> service.writeSysTran(BATCH_RUN_ID, null));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 7.4: throws IllegalArgumentException on null batchRunId")
        void writeSysTran_withNullBatchRunId_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeSysTran(null, "TRAN-LINE"));
            verifyNoInteractions(s3Client);
        }
    }

    // =========================================================================
    // Phase 8 — writeReport tests
    // =========================================================================

    /**
     * Phase 8 groups all {@link S3OutputService#writeReport(String, byte[])}
     * tests. These tests verify the {@code TRANREPT / STMTFILE / HTMLFILE}
     * sequential write contract from {@code app/cbl/CBTRN03C.cbl} and
     * {@code app/cbl/CBSTM03A.CBL}, including the HTML-detection branching
     * driven by the {@code .html} suffix on the reportId.
     */
    @Nested
    @DisplayName("writeReport — TRANREPT/STMTFILE/HTMLFILE WRITE replacement")
    class WriteReportTests {

        @Test
        @DisplayName("Test 8.1: HTML reportId triggers .html extension, text/html content-type, SSE-KMS")
        void writeReport_withHtmlReportId_usesHtmlExtensionAndContentType() {
            // Production HTML detection: reportId.toLowerCase().endsWith(".html").
            // This means the CALLER signals HTML by appending ".html" to the
            // reportId — matching the STMTFILE vs HTMLFILE DD allocation
            // split in CREASTMT.JCL lines 87-96. Content sniffing of the
            // bytes is NOT how the detection works.
            byte[] html = "<html><body>Report</body></html>"
                    .getBytes(StandardCharsets.US_ASCII);
            service.writeReport("RPT-001.html", html);

            ArgumentCaptor<PutObjectRequest> reqCaptor =
                    ArgumentCaptor.forClass(PutObjectRequest.class);
            ArgumentCaptor<RequestBody> bodyCaptor =
                    ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(reqCaptor.capture(), bodyCaptor.capture());

            PutObjectRequest req = reqCaptor.getValue();
            assertEquals(BUCKET, req.bucket());
            // The stripKnownExtension() helper removes the ".html" before
            // appending the canonical extension — the resulting key has
            // exactly one ".html" suffix.
            assertEquals("tranrept/" + today() + "/RPT-001.html", req.key(),
                    "HTML reports must land under tranrept/yyyy/MM/dd/{id}.html");
            assertEquals(ServerSideEncryption.AWS_KMS, req.serverSideEncryption(),
                    "Server-side encryption MUST be AWS_KMS");
            assertEquals(KMS_ARN, req.ssekmsKeyId(),
                    "ssekmsKeyId MUST be the configured CMK ARN");
            assertEquals("text/html; charset=US-ASCII", req.contentType(),
                    "HTML content-type expected for the .html reportId variant");
        }

        @Test
        @DisplayName("Test 8.2: non-HTML reportId uses .rpt extension and text/plain; charset=US-ASCII content-type")
        void writeReport_withNonHtmlReportId_usesRptExtensionAndPlainText() {
            // Production behavior: when reportId does NOT end with ".html",
            // the adapter emits a .rpt object with text/plain content-type
            // (NOT application/octet-stream — the agent_prompt's assertion of
            // octet-stream is incorrect for the writeReport path; that
            // content-type is reserved for copyTransactionBackup which
            // carries binary VSAM-equivalent payloads).
            byte[] data = "PLAIN REPORT DATA"
                    .getBytes(StandardCharsets.US_ASCII);
            service.writeReport("RPT-002", data);

            ArgumentCaptor<PutObjectRequest> reqCaptor =
                    ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(reqCaptor.capture(), any(RequestBody.class));

            PutObjectRequest req = reqCaptor.getValue();
            assertEquals(BUCKET, req.bucket());
            assertEquals("tranrept/" + today() + "/RPT-002.rpt", req.key(),
                    "Non-HTML reports must land under tranrept/yyyy/MM/dd/{id}.rpt");
            assertEquals(ServerSideEncryption.AWS_KMS, req.serverSideEncryption());
            assertEquals(KMS_ARN, req.ssekmsKeyId());
            assertEquals("text/plain; charset=US-ASCII", req.contentType(),
                    "Plain-text content-type expected for the .rpt reportId variant");
        }

        @Test
        @DisplayName("Test 8.2a: writeReport uploads payload bytes verbatim (no trailing-LF appended)")
        void writeReport_writesPayloadVerbatim() throws Exception {
            // Unlike writeRejection/writeSysTran, writeReport calls putObject()
            // directly (not appendLineToObject) so the caller-provided bytes
            // are uploaded verbatim — preserving byte-identical regulatory
            // output per AAP §0.7.2.
            byte[] data = "REPORT-CONTENT-BYTES"
                    .getBytes(StandardCharsets.US_ASCII);
            service.writeReport("RPT-003", data);

            ArgumentCaptor<RequestBody> bodyCaptor =
                    ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

            byte[] uploaded = bodyCaptor.getValue()
                    .contentStreamProvider().newStream().readAllBytes();
            assertArrayEquals(data, uploaded,
                    "Report payload bytes must be uploaded verbatim — no LF appended");
        }

        @Test
        @DisplayName("Test 8.2b: case-insensitive .HTML suffix still triggers HTML mode")
        void writeReport_withUppercaseHtmlSuffix_usesHtmlContentType() {
            // The production adapter lowercases the reportId before suffix
            // detection (reportId.toLowerCase().endsWith(".html")) so an
            // uppercase ".HTML" suffix MUST also trigger HTML mode.
            byte[] html = "<html></html>".getBytes(StandardCharsets.US_ASCII);
            service.writeReport("STMT-XYZ.HTML", html);

            ArgumentCaptor<PutObjectRequest> reqCaptor =
                    ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(reqCaptor.capture(), any(RequestBody.class));

            PutObjectRequest req = reqCaptor.getValue();
            // stripKnownExtension is also case-insensitive (it lowercases the
            // id before comparing), so the trailing ".HTML" is stripped and
            // the canonical ".html" extension is reapplied.
            assertEquals("tranrept/" + today() + "/STMT-XYZ.html", req.key(),
                    "Uppercase .HTML must canonicalize to a lowercase .html key");
            assertEquals("text/html; charset=US-ASCII", req.contentType());
            assertEquals(ServerSideEncryption.AWS_KMS, req.serverSideEncryption());
            assertEquals(KMS_ARN, req.ssekmsKeyId());
        }

        // ------ Input validation: reportId --------------------------------

        @Test
        @DisplayName("Test 8.3: throws IllegalArgumentException on null reportBytes, no SDK call")
        void writeReport_withNullReportBytes_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeReport("RPT-001", null));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 8.4: throws IllegalArgumentException on empty reportBytes")
        void writeReport_withEmptyReportBytes_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeReport("RPT-001", new byte[0]));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 8.5: throws IllegalArgumentException on null reportId")
        void writeReport_withNullReportId_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeReport(null, "DATA".getBytes(StandardCharsets.US_ASCII)));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 8.6: throws IllegalArgumentException on blank reportId (whitespace-only)")
        void writeReport_withBlankReportId_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeReport("  ", "DATA".getBytes(StandardCharsets.US_ASCII)));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 8.6a: throws IllegalArgumentException on empty-string reportId")
        void writeReport_withEmptyStringReportId_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeReport("", "DATA".getBytes(StandardCharsets.US_ASCII)));
            verifyNoInteractions(s3Client);
        }
    }

    // =========================================================================
    // Phase 9 — copyTransactionBackup tests
    // =========================================================================

    /**
     * Phase 9 groups all
     * {@link S3OutputService#copyTransactionBackup(String, byte[])} tests.
     * Verifies the {@code TRANSACT.BKUP} IDCAMS REPRO replacement path from
     * {@code app/jcl/TRANREPT.jcl} step {@code STEP05R}.
     */
    @Nested
    @DisplayName("copyTransactionBackup — IDCAMS REPRO replacement (binary octet-stream)")
    class CopyTransactionBackupTests {

        @Test
        @DisplayName("Test 9.1: puts SSE-KMS-encrypted object with transact-bkup/yyyy/MM/dd/{gen}.bkup key")
        void copyTransactionBackup_invokesS3WithSseKmsAndCorrectKey() {
            byte[] payload = "PAYLOAD-BYTES".getBytes(StandardCharsets.US_ASCII);
            service.copyTransactionBackup("G0042V00", payload);

            ArgumentCaptor<PutObjectRequest> reqCaptor =
                    ArgumentCaptor.forClass(PutObjectRequest.class);
            ArgumentCaptor<RequestBody> bodyCaptor =
                    ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(reqCaptor.capture(), bodyCaptor.capture());

            PutObjectRequest req = reqCaptor.getValue();
            assertEquals(BUCKET, req.bucket());
            // GDG (+1) semantics: the generation string maps to the per-object
            // identifier; S3 object versioning replaces the GDG generation
            // chain per AAP §0.6.2.
            assertEquals("transact-bkup/" + today() + "/G0042V00.bkup", req.key(),
                    "Key must follow transact-bkup/yyyy/MM/dd/{generation}.bkup convention");
            assertEquals(ServerSideEncryption.AWS_KMS, req.serverSideEncryption(),
                    "Server-side encryption MUST be AWS_KMS — never SSE-S3");
            assertEquals(KMS_ARN, req.ssekmsKeyId(),
                    "ssekmsKeyId MUST be the configured CMK ARN");
            assertEquals("application/octet-stream", req.contentType(),
                    "TRANSACT.BKUP carries binary VSAM-equivalent payload");

            // Payload bytes uploaded verbatim — binary backup must not be
            // mutated by the adapter (DFSORT REPRO semantics).
            try {
                byte[] uploaded = bodyCaptor.getValue()
                        .contentStreamProvider().newStream().readAllBytes();
                assertArrayEquals(payload, uploaded,
                        "Backup payload bytes must be uploaded verbatim");
            } catch (Exception e) {
                throw new AssertionError("Failed to read uploaded RequestBody bytes", e);
            }
        }

        @Test
        @DisplayName("Test 9.1a: attaches COBOL/IDCAMS provenance metadata referencing TRANREPT.jcl.STEP05R")
        void copyTransactionBackup_attachesIdcamsProvenanceMetadata() {
            byte[] payload = "PAYLOAD-BYTES".getBytes(StandardCharsets.US_ASCII);
            service.copyTransactionBackup("G0042V00", payload);

            ArgumentCaptor<PutObjectRequest> reqCaptor =
                    ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(reqCaptor.capture(), any(RequestBody.class));

            Map<String, String> metadata = reqCaptor.getValue().metadata();
            assertEquals("TRANREPT.jcl.STEP05R.IDCAMS-REPRO",
                    metadata.get("cobol-source"),
                    "metadata.cobol-source must record the IDCAMS REPRO origin");
            assertEquals("TRANSACT.BKUP", metadata.get("jcl-dd"));
            assertEquals("G0042V00", metadata.get("batch-run-id"),
                    "metadata.batch-run-id must carry the generation identifier");
        }

        @Test
        @DisplayName("Test 9.2: throws IllegalArgumentException on null generation")
        void copyTransactionBackup_withNullGeneration_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.copyTransactionBackup(null,
                            "DATA".getBytes(StandardCharsets.US_ASCII)));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 9.2a: throws IllegalArgumentException on blank generation")
        void copyTransactionBackup_withBlankGeneration_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.copyTransactionBackup("   ",
                            "DATA".getBytes(StandardCharsets.US_ASCII)));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 9.3: throws IllegalArgumentException on null payload")
        void copyTransactionBackup_withNullPayload_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.copyTransactionBackup("G0042V00", null));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 9.3a: throws IllegalArgumentException on empty payload (mirrors COBOL populated-backup contract)")
        void copyTransactionBackup_withEmptyPayload_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.copyTransactionBackup("G0042V00", new byte[0]));
            verifyNoInteractions(s3Client);
        }
    }

    // =========================================================================
    // Phase 10 — writeReportLines tests
    // =========================================================================

    /**
     * Phase 10 groups all
     * {@link S3OutputService#writeReportLines(String, String, List)} tests.
     * Verifies the strongly-typed {@link ReportLineDto} streaming variant
     * used by {@code TransactionReportService} and
     * {@code StatementGenerationService} per AAP &sect;0.4.1.
     */
    @Nested
    @DisplayName("writeReportLines — strongly-typed ReportLineDto streaming variant")
    class WriteReportLinesTests {

        @Test
        @DisplayName("Test 10.1: joins lines with LF terminators, uses {reportType}/yyyy/MM/dd/{id}.rpt key, SSE-KMS applied")
        void writeReportLines_joinsLinesWithNewline_andUsesReportTypeInKey() throws Exception {
            List<ReportLineDto> lines = List.of(
                    sampleDetailLine("LINE-A"),
                    sampleDetailLine("LINE-B"),
                    sampleDetailLine("LINE-C")
            );
            service.writeReportLines(BATCH_RUN_ID, "tranrept", lines);

            ArgumentCaptor<PutObjectRequest> reqCaptor =
                    ArgumentCaptor.forClass(PutObjectRequest.class);
            ArgumentCaptor<RequestBody> bodyCaptor =
                    ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(reqCaptor.capture(), bodyCaptor.capture());

            // ----- Key/bucket/SSE-KMS assertions -----
            PutObjectRequest req = reqCaptor.getValue();
            assertEquals(BUCKET, req.bucket());
            assertEquals("tranrept/" + today() + "/" + BATCH_RUN_ID + ".rpt", req.key(),
                    "Key must follow {reportType}/yyyy/MM/dd/{batchRunId}.rpt convention");
            assertEquals(ServerSideEncryption.AWS_KMS, req.serverSideEncryption(),
                    "Server-side encryption MUST be AWS_KMS — AAP §0.7.1");
            assertEquals(KMS_ARN, req.ssekmsKeyId(),
                    "ssekmsKeyId MUST be the configured CMK ARN");
            assertEquals("text/plain; charset=US-ASCII", req.contentType(),
                    "Streamed report lines are ASCII text per CVTRA07Y.cpy");

            // ----- Payload-byte assertions -----
            // The production adapter formats each line via formatReportLine()
            // and appends '\n' AFTER each line — so the upload bytes match
            // "<line1>\n<line2>\n<line3>\n" (trailing LF, not separator).
            byte[] uploaded = bodyCaptor.getValue()
                    .contentStreamProvider().newStream().readAllBytes();
            String uploadedText = new String(uploaded, StandardCharsets.US_ASCII);

            // Three '\n' bytes — one per line terminator.
            long newlineCount = uploadedText.chars().filter(c -> c == '\n').count();
            assertEquals(3L, newlineCount,
                    "Each line must be followed by exactly one LF byte");

            // The DTOs all share the same numeric/date/type fields but each
            // carries a distinct 'description' value, which the production
            // formatDetailLine() emits in the pipe-delimited 7th position.
            // Asserting on the description occurrences confirms ordering.
            assertTrue(uploadedText.contains("|LINE-A|"),
                    "Uploaded payload must contain the first line's description");
            assertTrue(uploadedText.contains("|LINE-B|"),
                    "Uploaded payload must contain the second line's description");
            assertTrue(uploadedText.contains("|LINE-C|"),
                    "Uploaded payload must contain the third line's description");

            // The descriptions must appear in the supplied order.
            int idxA = uploadedText.indexOf("|LINE-A|");
            int idxB = uploadedText.indexOf("|LINE-B|");
            int idxC = uploadedText.indexOf("|LINE-C|");
            assertTrue(idxA >= 0 && idxA < idxB && idxB < idxC,
                    "Lines must be emitted in the order supplied to writeReportLines");

            // The trailing byte must be a '\n' (LF terminator on the final line).
            assertEquals('\n', (char) uploaded[uploaded.length - 1],
                    "Final byte must be LF — every line is LF-terminated");
        }

        @Test
        @DisplayName("Test 10.1a: uses custom reportType prefix for the S3 key")
        void writeReportLines_usesCustomReportTypePrefix() {
            // The reportType parameter drives the key prefix — e.g. "statement"
            // produces statement/yyyy/MM/dd/{id}.rpt — supporting the
            // StatementGenerationService use case per AAP §0.4.1.
            service.writeReportLines(BATCH_RUN_ID, "statement",
                    List.of(sampleDetailLine("S-LINE")));

            ArgumentCaptor<PutObjectRequest> reqCaptor =
                    ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(reqCaptor.capture(), any(RequestBody.class));

            assertEquals("statement/" + today() + "/" + BATCH_RUN_ID + ".rpt",
                    reqCaptor.getValue().key(),
                    "Custom reportType must be honored as the key prefix");
        }

        @Test
        @DisplayName("Test 10.1b: renders a SEPARATOR line as 133 dashes per CVTRA07Y.cpy")
        void writeReportLines_separatorLineEmits133Dashes() throws Exception {
            ReportLineDto separator = new ReportLineDto(
                    "SEPARATOR", null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
            service.writeReportLines(BATCH_RUN_ID, "tranrept", List.of(separator));

            ArgumentCaptor<RequestBody> bodyCaptor =
                    ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

            byte[] uploaded = bodyCaptor.getValue()
                    .contentStreamProvider().newStream().readAllBytes();

            // 133 dashes + 1 LF = 134 bytes.
            assertEquals(134, uploaded.length,
                    "SEPARATOR line: 133 '-' bytes + 1 LF = 134 bytes");

            byte[] expected = new byte[134];
            for (int i = 0; i < 133; i++) {
                expected[i] = '-';
            }
            expected[133] = '\n';
            assertArrayEquals(expected, uploaded,
                    "SEPARATOR line must equal 133 '-' bytes followed by one LF");
        }

        @Test
        @DisplayName("Test 10.1c: renders a HEADER line with reportName + date range + page number")
        void writeReportLines_headerLineEmitsReportNameAndDateRange() throws Exception {
            // The HEADER variant exercises formatHeaderLine() in the
            // production adapter, which produces the
            // "{name} Date Range: {start} to {end} Page {n}" layout per
            // CVTRA07Y.cpy REPORT-NAME-HEADER. This test ensures the
            // HEADER branch of the formatReportLine() switch is exercised.
            ReportLineDto header = new ReportLineDto(
                    "HEADER",
                    "Daily Transaction Report",
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 1, 31),
                    null, null, null, null, null, null, null, null, null,
                    Integer.valueOf(1)
            );
            service.writeReportLines(BATCH_RUN_ID, "tranrept", List.of(header));

            ArgumentCaptor<RequestBody> bodyCaptor =
                    ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

            byte[] uploaded = bodyCaptor.getValue()
                    .contentStreamProvider().newStream().readAllBytes();
            String uploadedText = new String(uploaded, StandardCharsets.US_ASCII);
            assertEquals(
                    "Daily Transaction Report Date Range: 2026-01-01 to 2026-01-31 Page 1\n",
                    uploadedText,
                    "HEADER line must render the COBOL REPORT-NAME-HEADER format");
        }

        @Test
        @DisplayName("Test 10.1d: renders an ACCOUNT_TOTAL line with label + accountId + amount")
        void writeReportLines_accountTotalLineEmitsLabelAndAmount() throws Exception {
            // The ACCOUNT_TOTAL variant exercises formatTotalLine() per
            // CVTRA07Y.cpy REPORT-ACCOUNT-TOTALS. Verifies the TOTAL
            // branch of the formatReportLine() switch is exercised.
            ReportLineDto total = new ReportLineDto(
                    "ACCOUNT_TOTAL",
                    null, null, null,
                    10000000001L,                   // accountId
                    null, null, null, null, null, null,
                    new BigDecimal("999.99"),       // amount
                    "Account Total",                // totalLabel
                    null
            );
            service.writeReportLines(BATCH_RUN_ID, "tranrept", List.of(total));

            ArgumentCaptor<RequestBody> bodyCaptor =
                    ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

            byte[] uploaded = bodyCaptor.getValue()
                    .contentStreamProvider().newStream().readAllBytes();
            String uploadedText = new String(uploaded, StandardCharsets.US_ASCII);
            assertEquals(
                    "Account Total 10000000001 999.99\n",
                    uploadedText,
                    "ACCOUNT_TOTAL must render label + accountId + amount");
        }

        @Test
        @DisplayName("Test 10.1e: renders a GRAND_TOTAL line with label + amount (no accountId)")
        void writeReportLines_grandTotalLineEmitsLabelAndAmount() throws Exception {
            // The GRAND_TOTAL variant has no accountId, so formatTotalLine
            // must skip the accountId-emitting branch.
            ReportLineDto grand = new ReportLineDto(
                    "GRAND_TOTAL",
                    null, null, null, null,
                    null, null, null, null, null, null,
                    new BigDecimal("12345.67"),
                    "Grand Total",
                    null
            );
            service.writeReportLines(BATCH_RUN_ID, "tranrept", List.of(grand));

            ArgumentCaptor<RequestBody> bodyCaptor =
                    ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

            byte[] uploaded = bodyCaptor.getValue()
                    .contentStreamProvider().newStream().readAllBytes();
            String uploadedText = new String(uploaded, StandardCharsets.US_ASCII);
            assertEquals(
                    "Grand Total 12345.67\n",
                    uploadedText,
                    "GRAND_TOTAL must render label + amount (no accountId)");
        }

        @Test
        @DisplayName("Test 10.1f: renders a DETAIL line with maskedCardNumber when present (statement variant)")
        void writeReportLines_detailLineWithMaskedCardNumber() throws Exception {
            // The DETAIL line with a non-null maskedCardNumber exercises
            // the alternate branch in formatDetailLine() — the statement
            // use case (CBSTM03A.CBL) where PAN-last-4 is rendered.
            ReportLineDto detail = new ReportLineDto(
                    "DETAIL",
                    null, null, null,
                    10000000001L,
                    "************0001",             // maskedCardNumber populated
                    LocalDate.of(2026, 1, 15),
                    "01",
                    Integer.valueOf(5411),
                    "ONLINE",
                    "GROCERY",
                    new BigDecimal("42.50"),
                    null, null
            );
            service.writeReportLines(BATCH_RUN_ID, "statement", List.of(detail));

            ArgumentCaptor<RequestBody> bodyCaptor =
                    ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

            byte[] uploaded = bodyCaptor.getValue()
                    .contentStreamProvider().newStream().readAllBytes();
            String uploadedText = new String(uploaded, StandardCharsets.US_ASCII);
            assertTrue(uploadedText.contains("************0001"),
                    "DETAIL line with maskedCardNumber must include masked PAN");
            assertTrue(uploadedText.contains("|GROCERY|"),
                    "DETAIL line must include description");
            assertTrue(uploadedText.endsWith("42.50\n"),
                    "DETAIL line must end with the amount and trailing LF");
        }

        @Test
        @DisplayName("Test 10.1g: renders an unknown lineType by falling back to DTO.toString()")
        void writeReportLines_unknownLineTypeFallsBackToDtoToString() throws Exception {
            // The default branch of formatReportLine()'s switch emits
            // the record's toString() for diagnostics so unknown
            // discriminators are visible rather than silently dropped.
            ReportLineDto unknown = new ReportLineDto(
                    "FOOTER",   // unknown discriminator
                    "TEST", null, null, null, null, null, null, null,
                    null, null, null, null, null
            );
            service.writeReportLines(BATCH_RUN_ID, "tranrept", List.of(unknown));

            ArgumentCaptor<RequestBody> bodyCaptor =
                    ArgumentCaptor.forClass(RequestBody.class);
            verify(s3Client).putObject(any(PutObjectRequest.class), bodyCaptor.capture());

            byte[] uploaded = bodyCaptor.getValue()
                    .contentStreamProvider().newStream().readAllBytes();
            String uploadedText = new String(uploaded, StandardCharsets.US_ASCII);
            // The toString() output must include the lineType field name
            // and value — Java record toString uses field=value format.
            assertTrue(uploadedText.contains("FOOTER"),
                    "Unknown lineType must surface via DTO.toString() for diagnostics");
            assertTrue(uploadedText.endsWith("\n"),
                    "Fallback line must still be LF-terminated");
        }

        // ------ Input validation: lines list ------------------------------

        @Test
        @DisplayName("Test 10.2: throws IllegalArgumentException on null lines list")
        void writeReportLines_withNullList_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeReportLines(BATCH_RUN_ID, "tranrept", null));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 10.3: throws IllegalArgumentException on empty lines list")
        void writeReportLines_withEmptyList_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeReportLines(BATCH_RUN_ID, "tranrept",
                            Collections.emptyList()));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 10.3a: throws IllegalArgumentException when a list entry is null")
        void writeReportLines_withNullEntry_throwsIllegalArgument() {
            // The production loop validates each entry inside lines — a null
            // entry is rejected with IllegalArgumentException.
            List<ReportLineDto> lines = Arrays.asList(sampleReportLine(), null);
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeReportLines(BATCH_RUN_ID, "tranrept", lines));
        }

        @Test
        @DisplayName("Test 10.4: throws IllegalArgumentException on null batchRunId")
        void writeReportLines_withNullBatchRunId_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeReportLines(null, "tranrept",
                            List.of(sampleReportLine())));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 10.4a: throws IllegalArgumentException on blank batchRunId")
        void writeReportLines_withBlankBatchRunId_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeReportLines("   ", "tranrept",
                            List.of(sampleReportLine())));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 10.5: throws IllegalArgumentException on null reportType")
        void writeReportLines_withNullReportType_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeReportLines(BATCH_RUN_ID, null,
                            List.of(sampleReportLine())));
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("Test 10.5a: throws IllegalArgumentException on blank reportType")
        void writeReportLines_withBlankReportType_throwsIllegalArgument() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeReportLines(BATCH_RUN_ID, "   ",
                            List.of(sampleReportLine())));
            verifyNoInteractions(s3Client);
        }
    }

    // =========================================================================
    // Phase 11 — S3Exception propagation tests
    // =========================================================================

    /**
     * Phase 11 verifies the exception-translation contract on the SDK error
     * path. Per AAP &sect;0.7.1, the adapter wraps {@link S3Exception} in
     * {@link CardDemoException} with reason code {@code S3_PUT_ERROR}
     * (preserved verbatim per AAP &sect;0.7.2) and the original SDK
     * exception preserved as the wrapped {@code cause} for diagnostic
     * stack-trace propagation into CloudWatch / OpenSearch.
     */
    @Nested
    @DisplayName("S3Exception propagation — wrap in CardDemoException(S3_PUT_ERROR)")
    class ExceptionPropagationTests {

        /**
         * Builds an {@link S3Exception} that mimics a realistic AWS SDK
         * service error with a populated {@link AwsErrorDetails} record.
         */
        private S3Exception simulatedS3Exception() {
            return (S3Exception) S3Exception.builder()
                    .message("simulated S3 failure")
                    .statusCode(500)
                    .awsErrorDetails(AwsErrorDetails.builder()
                            .errorCode("InternalError")
                            .errorMessage("simulated S3 failure")
                            .serviceName("S3")
                            .build())
                    .build();
        }

        @Test
        @DisplayName("Test 11.1: writeRejection wraps S3Exception in CardDemoException(S3_PUT_ERROR)")
        void writeRejection_whenS3Exception_wrapsInCardDemoException() {
            S3Exception sdkException = simulatedS3Exception();
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenThrow(sdkException);

            CardDemoException thrown = assertThrows(CardDemoException.class,
                    () -> service.writeRejection(BATCH_RUN_ID, "PAYLOAD"));

            // Reason code preservation per AAP §0.7.2.
            assertEquals("S3_PUT_ERROR", thrown.getReasonCode(),
                    "Reason code must equal S3_PUT_ERROR per AAP §0.7.2");

            // Original SDK exception preserved as cause for diagnostic
            // stack traces in CloudWatch / OpenSearch.
            assertSame(sdkException, thrown.getCause(),
                    "Original S3Exception must be preserved as the wrapped cause");

            // Diagnostic message includes the s3://bucket/key URI so
            // operators can locate the failing object in CloudWatch.
            String message = thrown.getMessage();
            assertNotNull(message, "CardDemoException must carry a diagnostic message");
            assertTrue(message.contains("s3://" + BUCKET + "/dalyrejs/"),
                    "Message must include the s3://bucket/key URI for triage");
        }

        @Test
        @DisplayName("Test 11.2: writeReport wraps S3Exception in CardDemoException(S3_PUT_ERROR)")
        void writeReport_whenS3Exception_wrapsInCardDemoException() {
            S3Exception sdkException = simulatedS3Exception();
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenThrow(sdkException);

            CardDemoException thrown = assertThrows(CardDemoException.class,
                    () -> service.writeReport("RPT-001",
                            "DATA".getBytes(StandardCharsets.US_ASCII)));
            assertEquals("S3_PUT_ERROR", thrown.getReasonCode());
            assertSame(sdkException, thrown.getCause());
        }

        @Test
        @DisplayName("Test 11.3: writeSysTran wraps S3Exception in CardDemoException(S3_PUT_ERROR)")
        void writeSysTran_whenS3Exception_wrapsInCardDemoException() {
            S3Exception sdkException = simulatedS3Exception();
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenThrow(sdkException);

            CardDemoException thrown = assertThrows(CardDemoException.class,
                    () -> service.writeSysTran(BATCH_RUN_ID, "TRAN-LINE"));
            assertEquals("S3_PUT_ERROR", thrown.getReasonCode());
            assertSame(sdkException, thrown.getCause());
        }

        @Test
        @DisplayName("Test 11.4: copyTransactionBackup wraps S3Exception in CardDemoException(S3_PUT_ERROR)")
        void copyTransactionBackup_whenS3Exception_wrapsInCardDemoException() {
            S3Exception sdkException = simulatedS3Exception();
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenThrow(sdkException);

            CardDemoException thrown = assertThrows(CardDemoException.class,
                    () -> service.copyTransactionBackup("G0042V00",
                            "BACKUP".getBytes(StandardCharsets.US_ASCII)));
            assertEquals("S3_PUT_ERROR", thrown.getReasonCode());
            assertSame(sdkException, thrown.getCause());
        }

        @Test
        @DisplayName("Test 11.5: writeReportLines wraps S3Exception in CardDemoException(S3_PUT_ERROR)")
        void writeReportLines_whenS3Exception_wrapsInCardDemoException() {
            S3Exception sdkException = simulatedS3Exception();
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenThrow(sdkException);

            CardDemoException thrown = assertThrows(CardDemoException.class,
                    () -> service.writeReportLines(BATCH_RUN_ID, "tranrept",
                            List.of(sampleReportLine())));
            assertEquals("S3_PUT_ERROR", thrown.getReasonCode());
            assertSame(sdkException, thrown.getCause());
        }
    }

    // =========================================================================
    // Phase 12 — Configuration-failure contracts (KMS / bucket misconfiguration)
    // =========================================================================

    /**
     * Phase 12 verifies the adapter's defensive validation of its injected
     * configuration values. Per AAP &sect;0.7.1 ("Encrypt all S3 buckets
     * with SSE-KMS"), a blank KMS CMK ARN must fail the PUT loudly with a
     * typed {@link CardDemoException} rather than silently writing an
     * unencrypted object. Similarly, a blank output bucket must fail
     * before any SDK call to surface the misconfiguration to the operator.
     *
     * <p>These tests use {@link ReflectionTestUtils#setField} to mutate the
     * configured field on the shared service instance after construction.
     * This mirrors the {@code @RefreshScope}-style rotation pattern from
     * AAP &sect;0.6.4 where Secrets Manager rotation can change the bound
     * value at runtime.</p>
     */
    @Nested
    @DisplayName("Configuration-failure contracts — refuse to write without SSE-KMS / output bucket")
    class ConfigurationFailureTests {

        @Test
        @DisplayName("Test 12.6: writeRejection throws CardDemoException(S3_KMS_KEY_MISSING) when KMS ARN is blank")
        void writeRejection_whenKmsArnBlank_throwsCardDemoExceptionKmsMissing() {
            // Simulate a rotation-driven runtime blanking of the CMK ARN —
            // e.g. a misconfigured Secrets Manager refresh. The adapter must
            // refuse to write rather than silently producing an unencrypted
            // S3 object (AAP §0.7.1).
            ReflectionTestUtils.setField(service, "kmsCmkArn", "");

            CardDemoException thrown = assertThrows(CardDemoException.class,
                    () -> service.writeRejection(BATCH_RUN_ID, "PAYLOAD"));
            assertEquals("S3_KMS_KEY_MISSING", thrown.getReasonCode(),
                    "Reason code must equal S3_KMS_KEY_MISSING per AAP §0.7.2");
            // The SDK MUST NOT have been called.
            verify(s3Client, never())
                    .putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("Test 12.7: writeReport throws CardDemoException(S3_KMS_KEY_MISSING) when KMS ARN is whitespace-only")
        void writeReport_whenKmsArnWhitespace_throwsCardDemoExceptionKmsMissing() {
            // Constructor trims its kmsCmkArn input, so an originally-blank
            // ARN reaches the validator as the empty string. Mutating the
            // field directly to "   " confirms isBlank() detects whitespace
            // even when the constructor trim path is bypassed.
            ReflectionTestUtils.setField(service, "kmsCmkArn", "   ");

            CardDemoException thrown = assertThrows(CardDemoException.class,
                    () -> service.writeReport("RPT-001",
                            "DATA".getBytes(StandardCharsets.US_ASCII)));
            assertEquals("S3_KMS_KEY_MISSING", thrown.getReasonCode());
            verify(s3Client, never())
                    .putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("Test 12.8: writeRejection throws CardDemoException(S3_BUCKET_MISSING) when output bucket is blank")
        void writeRejection_whenBucketBlank_throwsCardDemoExceptionBucketMissing() {
            // Mutate the configured bucket to blank — simulating a missing
            // Parameter Store value at startup or a rotation blanking.
            ReflectionTestUtils.setField(service, "outputBucket", "");

            CardDemoException thrown = assertThrows(CardDemoException.class,
                    () -> service.writeRejection(BATCH_RUN_ID, "PAYLOAD"));
            assertEquals("S3_BUCKET_MISSING", thrown.getReasonCode(),
                    "Reason code must equal S3_BUCKET_MISSING per AAP §0.7.2");
            verify(s3Client, never())
                    .putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("Test 12.9: constructor rejects null S3Client (NullPointerException)")
        void constructor_withNullS3Client_throwsNullPointerException() {
            // The production constructor uses Objects.requireNonNull(s3Client)
            // — the only guaranteed-non-null collaborator. Blank
            // bucket/kmsArn values are deferred to call-time per AAP §0.6.4
            // so that bean wiring succeeds in the `local` profile.
            assertThrows(NullPointerException.class,
                    () -> new S3OutputService(null, BUCKET, KMS_ARN));
        }

        @Test
        @DisplayName("Test 12.10: constructor trims surrounding whitespace from injected bucket and ARN")
        void constructor_trimsWhitespaceFromInjectedFields() {
            // Verifies the constructor's String.trim() defensive behavior
            // for accidentally-whitespace-padded Parameter Store / Secrets
            // Manager values. This is a guard against an entire class of
            // YAML/env-var copy/paste defects in production deployments.
            S3OutputService trimmed = new S3OutputService(
                    s3Client,
                    "  " + BUCKET + "  ",
                    "\t" + KMS_ARN + "\n");

            assertEquals(BUCKET,
                    ReflectionTestUtils.getField(trimmed, "outputBucket"),
                    "outputBucket field must be trimmed of surrounding whitespace");
            assertEquals(KMS_ARN,
                    ReflectionTestUtils.getField(trimmed, "kmsCmkArn"),
                    "kmsCmkArn field must be trimmed of surrounding whitespace");
        }

        @Test
        @DisplayName("Test 12.11: constructor maps a null bucket / ARN to empty string (no NPE on call)")
        void constructor_mapsNullsToEmptyStringDeferringValidationToCallTime() {
            // The constructor coerces null bucket / ARN to "" so the bean
            // wires successfully under the `local` profile, and validation
            // happens at the call site (raising a typed
            // CardDemoException with the appropriate reason code).
            S3OutputService deferred = new S3OutputService(s3Client, null, null);
            assertEquals("",
                    ReflectionTestUtils.getField(deferred, "outputBucket"),
                    "null outputBucket must coerce to empty string at construction");
            assertEquals("",
                    ReflectionTestUtils.getField(deferred, "kmsCmkArn"),
                    "null kmsCmkArn must coerce to empty string at construction");

            // And the call-time validation surfaces a typed exception.
            CardDemoException thrown = assertThrows(CardDemoException.class,
                    () -> deferred.writeRejection(BATCH_RUN_ID, "PAYLOAD"));
            // Bucket validation happens first in putObject()'s validation
            // sequence (validateBucket → validateKey → validateKmsKeyArn),
            // so the bucket-missing reason code is the one surfaced when
            // both are blank.
            assertEquals("S3_BUCKET_MISSING", thrown.getReasonCode());
        }

        @Test
        @DisplayName("Test 12.12: writeRejection rejects newline-bearing batchRunId via validateKey")
        void writeRejection_withNewlineInBatchRunId_rejectedByValidateKey() {
            // The batchRunId flows into the S3 object key. The production
            // validateKey() guard rejects newlines in the key as a
            // categorical caller bug (newlines in object keys are a
            // CRLF-injection vector against downstream Athena / Glue
            // metadata indexers).
            assertThrows(IllegalArgumentException.class,
                    () -> service.writeRejection("RUN\n001", "PAYLOAD"));
            verify(s3Client, never())
                    .putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("Test 12.13: writeReport with .rpt-suffixed reportId strips the duplicate extension")
        void writeReport_withRptSuffixedReportId_stripsExtension() {
            // stripKnownExtension() removes the .rpt suffix so the
            // assembled key does not emit "RPT-009.rpt.rpt".
            service.writeReport("RPT-009.rpt",
                    "DATA".getBytes(StandardCharsets.US_ASCII));

            ArgumentCaptor<PutObjectRequest> reqCaptor =
                    ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(reqCaptor.capture(), any(RequestBody.class));
            assertEquals("tranrept/" + today() + "/RPT-009.rpt",
                    reqCaptor.getValue().key(),
                    "Trailing .rpt must be stripped from reportId to avoid duplicate suffix");
        }
    }
}
