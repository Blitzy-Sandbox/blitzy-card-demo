/*
 * ============================================================================
 *  CardDemo — Greenfield Java 25 LTS + Spring Boot 3.x Migration
 *  S3 Batch File-Staging Integration Test
 *  (VSAM/GDG generation datasets + sequential PS files  ->  AWS S3 objects)
 * ============================================================================
 *
 *  PROVENANCE & TRACEABILITY (AAP §0.7.1 / §0.7.2)
 *  Net-new greenfield infrastructure test with NO COBOL-level S3 API in the
 *  legacy source — S3 is entirely net-new in the Java target, so this test
 *  validates the Java target's S3 staging CONTRACT rather than any COBOL
 *  behaviour. The legacy mainframe staged batch data in VSAM/GDG generation
 *  datasets and sequential PS files, provisioned by IDCAMS JCL:
 *
 *    - app/jcl/DEFGDGB.jcl defines SIX GDG bases (each LIMIT(5), SCRATCH):
 *        AWS.M2.CARDDEMO.TRANSACT.BKUP, AWS.M2.CARDDEMO.TRANSACT.DALY,
 *        AWS.M2.CARDDEMO.TRANREPT,      AWS.M2.CARDDEMO.TCATBALF.BKUP,
 *        AWS.M2.CARDDEMO.SYSTRAN,       AWS.M2.CARDDEMO.TRANSACT.COMBINED.
 *      Their versioned generations (G0001V00, G0002V00, …) map (decision
 *      D-003) to S3 versioned object KEYS with generation prefixes.
 *    - app/jcl/CREASTMT.JCL produces per-card account STATEMENT output
 *      (STEP040 PGM=CBSTM03A -> STMTFILE AWS.M2.CARDDEMO.STATEMNT.PS text +
 *      HTMLFILE AWS.M2.CARDDEMO.STATEMNT.HTML) -> S3 bucket carddemo-statements.
 *    - app/jcl/DALYREJS.jcl defines the daily REJECTION GDG base
 *      AWS.M2.CARDDEMO.DALYREJS -> an S3 reject output object.
 *
 *  TECHNOLOGY SUBSTITUTION UNDER TEST (decision D-003, Minimal Change Clause):
 *      Sequential PS staging datasets + GDG generations  ->  versioned S3
 *      objects in three buckets — carddemo-batch-input (staging input /
 *      input generations), carddemo-batch-output (report + reject output
 *      generations), and carddemo-statements (CBSTM03A/CBSTM03B statements)
 *      (tech-spec L22, transformation rule L87; AAP §0.4.1 S3 rows / §0.7.7).
 *      GDG generation numbering (DEFGDGB LIMIT(5)) is realized through S3
 *      object keys carrying a generation suffix (…/G0001V00, …/G0002V00).
 *
 *  The COBOL/JCL sources are read-only reference and are NEVER copied into this
 *  repository; traceability to the frozen legacy baseline is by commit SHA
 *  27d6c6f only. Base package is com.cardemo (decision D-006 — deliberately NOT
 *  com.carddemo), matching <groupId>com.cardemo</groupId> in carddemo-java/pom.xml.
 * ============================================================================
 */
package com.cardemo.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * End-to-end LocalStack integration test for the CardDemo <strong>S3 batch file-staging layer</strong>
 * &mdash; the migration of the legacy z/OS sequential PS staging datasets and GDG generations to
 * versioned AWS <strong>S3</strong> objects (decision&nbsp;<strong>D-003</strong>).
 *
 * <h2>What this test proves</h2>
 * <p>There is <strong>no COBOL-level S3 API</strong> in the frozen legacy estate &mdash; S3 is net-new
 * in the Java target. This test therefore validates the Java target's <em>infrastructure contract</em>
 * rather than any COBOL behaviour: that the three application-owned buckets resolve to the names bound
 * in {@link com.cardemo.config.AwsConfig.AwsResourceProperties}, that LocalStack S3 is reachable in
 * <strong>path-style</strong> addressing mode, and that objects keyed with GDG-generation-style and
 * statement/report/reject prefixes round-trip byte-for-byte. Concretely it asserts, against a real
 * (LocalStack) S3 surface, that:</p>
 * <ol>
 *   <li>a GDG-generation-style key on {@code carddemo-batch-input}
 *       ({@code transact-daly/G0001V00}, mirroring {@code AWS.M2.CARDDEMO.TRANSACT.DALY(+1)}) stores and
 *       retrieves <strong>byte-identical</strong> content, a second generation
 *       ({@code transact-daly/G0002V00}) is independently retrievable, both are enumerated under the
 *       {@code transact-daly/} generation prefix, and a never-written generation yields
 *       {@link NoSuchKeyException} (proving generation-prefix semantics, D-003);</li>
 *   <li>a statement object on {@code carddemo-statements}
 *       ({@code statements/2025/0000000001.txt}, mirroring {@code CREASTMT.JCL} {@code CBSTM03A}
 *       {@code STMTFILE}) exists with the exact byte length and content uploaded; and</li>
 *   <li>a report object ({@code reports/tranrept-G0001V00.txt}, mirroring the {@code TRANREPT} GDG) and a
 *       rejection object ({@code rejects/dalyrejs.txt}, mirroring {@code DALYREJS.jcl}) on
 *       {@code carddemo-batch-output} are both enumerable under their respective prefixes &mdash;
 *       implicitly confirming end-to-end path-style access.</li>
 * </ol>
 *
 * <h2>Self-managed resource lifecycle (AAP §0.7.7)</h2>
 * <p>All three buckets are created in {@link #createBuckets()} ({@code @BeforeAll}) and emptied &amp;
 * deleted in {@link #deleteBuckets()} ({@code @AfterAll}); bucket creation is idempotent (a pre-existing
 * bucket owned by the same dummy account is tolerated) and every object key is deterministic, so reruns
 * against the shared container are repeatable with no pre-existing state assumed. The shared LocalStack
 * container is owned by {@link AbstractLocalStackIntegrationTest} and is intentionally left running
 * (reaped by the Testcontainers <em>Ryuk</em> sidecar at JVM exit) &mdash; this test never stops it.</p>
 *
 * <h2>ZERO live AWS / zero live credentials (AAP §0.7.2 / §0.7.7)</h2>
 * <p>Every S3 interaction targets the inherited LocalStack <strong>community</strong> Testcontainer via
 * the inherited synchronous {@link AbstractLocalStackIntegrationTest#newS3Client()} factory, which is
 * pre-configured with {@code forcePathStyle(true)} and LocalStack's dummy {@code test}/{@code test}
 * keys. No real AWS endpoints or secrets are involved; the endpoint can only ever resolve to LocalStack.
 * Path-style addressing is the well-documented LocalStack S3 pitfall (tech-spec&nbsp;L981): virtual-host
 * URLs ({@code http://bucket.host/…}) do not resolve against the emulator, only path-style
 * ({@code http://host:port/bucket/key}) does &mdash; the successful round-trips below confirm it is in
 * effect end-to-end.</p>
 *
 * <h2>Contract fidelity (no hardcoded resource names)</h2>
 * <p>Each bucket name is resolved from {@link AbstractLocalStackIntegrationTest#awsResourceProperties
 * awsResourceProperties} (bound from {@code carddemo.aws.s3.*}), never from a magic string, and is
 * asserted to equal its contract value so the test and the production beans resolve the same buckets.</p>
 *
 * <h2>Execution</h2>
 * <p>The mandatory {@code IT} suffix and the {@code com.cardemo.integration} package route this class to
 * the {@code maven-failsafe-plugin} (run via {@code mvn verify -Pintegration}); the
 * {@code maven-surefire-plugin} explicitly excludes it. Lifecycle methods are non-static, relying on the
 * {@code @TestInstance(PER_CLASS)} the base class declares (inherited by this subclass), which also lets
 * {@code @BeforeAll} read the autowired {@code awsResourceProperties}. This subclass deliberately adds
 * <strong>no</strong> Spring context annotations ({@code @SpringBootTest} / {@code @ActiveProfiles} /
 * {@code @DynamicPropertySource}) so the resolved configuration stays identical to its siblings and the
 * cached {@code ApplicationContext} is shared across the AWS IT suite.</p>
 *
 * @see com.cardemo.config.AwsConfig.AwsResourceProperties
 * @see AbstractLocalStackIntegrationTest
 * @see software.amazon.awssdk.services.s3.S3Client
 */
@DisplayName("S3 batch file staging (GDG / sequential PS dataset -> S3) integration")
public class S3StagingIT extends AbstractLocalStackIntegrationTest {

    /** Contract name of the batch-staging INPUT bucket (sequential PS / GDG input generations). */
    private static final String EXPECTED_INPUT_BUCKET = "carddemo-batch-input";

    /** Contract name of the batch-staging OUTPUT bucket (report + reject GDG output generations). */
    private static final String EXPECTED_OUTPUT_BUCKET = "carddemo-batch-output";

    /** Contract name of the generated-statement bucket (CBSTM03A/CBSTM03B statement output). */
    private static final String EXPECTED_STATEMENTS_BUCKET = "carddemo-statements";

    /**
     * Independent, synchronous S3 verification client built from the inherited
     * {@link AbstractLocalStackIntegrationTest#newS3Client()} factory (path-style, LocalStack dummy
     * credentials). Deliberately decoupled from the production Spring Cloud AWS beans so the test cleanly
     * exercises the S3 surface directly. Created in {@code @BeforeAll}, closed in {@code @AfterAll}.
     */
    private S3Client s3;

    /** Batch-staging input bucket name, resolved from {@code awsResourceProperties} in {@code @BeforeAll}. */
    private String inputBucket;

    /** Batch-staging output bucket name, resolved from {@code awsResourceProperties} in {@code @BeforeAll}. */
    private String outputBucket;

    /** Generated-statement bucket name, resolved from {@code awsResourceProperties} in {@code @BeforeAll}. */
    private String statementsBucket;

    // -------------------------------------------------------------------------------------------------
    // Lifecycle — self-managed buckets (AAP §0.7.7: tests create and destroy their own resources).
    // -------------------------------------------------------------------------------------------------

    /**
     * Provisions the three batch-staging buckets on the shared LocalStack container before any test
     * runs. Non-static (the base declares {@code @TestInstance(PER_CLASS)}) so it can read the autowired
     * {@link AbstractLocalStackIntegrationTest#awsResourceProperties}.
     *
     * <p>Every bucket name is taken from configuration (never hardcoded) and asserted to equal its
     * contract value, locking the resource-name contract so a drifting configuration fails the test
     * loudly. Each bucket is then created and emptied via {@link #ensureCleanBucket(String)} so the suite
     * always starts from a known-empty state &mdash; making reruns against the long-lived shared
     * container fully idempotent with no pre-existing state assumed (AAP §0.7.7).</p>
     */
    @BeforeAll
    void createBuckets() {
        s3 = newS3Client();

        // Resolve every bucket name from the SAME bean the production code uses — contract fidelity,
        // never a hardcoded literal (AAP §0.7.2). These getters are the real AwsResourceProperties.S3
        // API: getBatchInputBucket() / getBatchOutputBucket() / getStatementsBucket().
        inputBucket = awsResourceProperties.getS3().getBatchInputBucket();
        outputBucket = awsResourceProperties.getS3().getBatchOutputBucket();
        statementsBucket = awsResourceProperties.getS3().getStatementsBucket();

        assertThat(inputBucket)
                .as("batch-input bucket name must come from awsResourceProperties "
                        + "(carddemo.aws.s3.batch-input-bucket), not a hardcoded literal")
                .isEqualTo(EXPECTED_INPUT_BUCKET);
        assertThat(outputBucket)
                .as("batch-output bucket name must come from awsResourceProperties "
                        + "(carddemo.aws.s3.batch-output-bucket), not a hardcoded literal")
                .isEqualTo(EXPECTED_OUTPUT_BUCKET);
        assertThat(statementsBucket)
                .as("statements bucket name must come from awsResourceProperties "
                        + "(carddemo.aws.s3.statements-bucket), not a hardcoded literal")
                .isEqualTo(EXPECTED_STATEMENTS_BUCKET);

        // Create + empty each bucket so the suite starts clean regardless of any prior aborted run.
        ensureCleanBucket(inputBucket);
        ensureCleanBucket(outputBucket);
        ensureCleanBucket(statementsBucket);
    }

    // -------------------------------------------------------------------------------------------------
    // Test 1 — GDG-generation-style key round-trip + generation-prefix listing on the input bucket.
    //          (Sequential PS staging dataset / GDG generations -> versioned S3 object keys, D-003.)
    // -------------------------------------------------------------------------------------------------

    /**
     * Verifies that GDG-generation-style object keys store and retrieve byte-for-byte, that successive
     * generations coexist under a shared generation prefix and are enumerable there, and that an
     * unwritten generation yields a typed {@link NoSuchKeyException}.
     *
     * <p>This realizes decision <strong>D-003</strong>: the legacy GDG base
     * {@code AWS.M2.CARDDEMO.TRANSACT.DALY} (defined by {@code app/jcl/DEFGDGB.jcl}, {@code LIMIT(5)})
     * with its relative generations {@code (+1)}, {@code (+2)} maps to S3 keys
     * {@code transact-daly/G0001V00}, {@code transact-daly/G0002V00} &mdash; the {@code transact-daly/}
     * prefix is the GDG base and the {@code G000nV00} suffix encodes the generation.</p>
     */
    @Test
    @DisplayName("GDG-generation-style keys round-trip byte-for-byte and list under their generation prefix")
    void gdgGenerationKeysRoundTripOnInputBucket() {
        // GDG mapping (D-003): AWS.M2.CARDDEMO.TRANSACT.DALY(+1) -> transact-daly/G0001V00,
        //                      AWS.M2.CARDDEMO.TRANSACT.DALY(+2) -> transact-daly/G0002V00.
        final String generationPrefix = "transact-daly/";
        final String gen1Key = generationPrefix + "G0001V00";
        final String gen2Key = generationPrefix + "G0002V00";

        // Small, deterministic fixed-width-style staging records (mirror sequential PS staging lines).
        final String gen1Payload = "TRAN0000000001CARD4111111111111111000000123456C20250101DALY+1GEN0001";
        final String gen2Payload = "TRAN0000000002CARD4111111111111111000000654321D20250102DALY+2GEN0002";

        // ---- Act: stage the first generation. ----
        s3.putObject(PutObjectRequest.builder().bucket(inputBucket).key(gen1Key).build(),
                RequestBody.fromString(gen1Payload, StandardCharsets.UTF_8));

        // ---- Assert: byte-identical round-trip (no charset/encoding drift through S3). ----
        ResponseBytes<GetObjectResponse> retrieved = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(inputBucket).key(gen1Key).build());
        assertThat(retrieved.asByteArray())
                .as("staged generation object must round-trip byte-for-byte")
                .isEqualTo(gen1Payload.getBytes(StandardCharsets.UTF_8));
        assertThat(retrieved.asUtf8String())
                .as("staged generation object content (UTF-8 view)")
                .isEqualTo(gen1Payload);

        // ---- Act: stage a second generation under the same GDG base prefix. ----
        s3.putObject(PutObjectRequest.builder().bucket(inputBucket).key(gen2Key).build(),
                RequestBody.fromString(gen2Payload, StandardCharsets.UTF_8));

        // ---- Assert: both generations are enumerated under the generation prefix (proves D-003). ----
        List<String> generationKeys = listKeys(inputBucket, generationPrefix);
        assertThat(generationKeys)
                .as("both GDG generations listed under the %s prefix", generationPrefix)
                .containsExactlyInAnyOrder(gen1Key, gen2Key);

        // ---- Assert: a never-written generation yields a typed 404 (NoSuchKey), proving the emulator
        //      enforces real key semantics rather than silently returning empty content. ----
        assertThatThrownBy(() -> s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(inputBucket).key(generationPrefix + "G9999V00").build()))
                .as("absent generation must raise NoSuchKeyException")
                .isInstanceOf(NoSuchKeyException.class);
    }

    // -------------------------------------------------------------------------------------------------
    // Test 2 — statement output object on the statements bucket.
    //          (CREASTMT.JCL CBSTM03A STMTFILE AWS.M2.CARDDEMO.STATEMNT.PS -> carddemo-statements.)
    // -------------------------------------------------------------------------------------------------

    /**
     * Verifies that a statement output object stored on {@code carddemo-statements} exists with the exact
     * uploaded byte length (via {@code HEAD}) and round-trips its content byte-for-byte (via {@code GET}).
     *
     * <p>The legacy {@code CREASTMT.JCL} step {@code STEP040} ({@code PGM=CBSTM03A}) wrote per-card
     * statements to the sequential dataset {@code AWS.M2.CARDDEMO.STATEMNT.PS} (and an HTML variant to
     * {@code AWS.M2.CARDDEMO.STATEMNT.HTML}); that statement output maps to a keyed object in the
     * dedicated {@code carddemo-statements} bucket.</p>
     */
    @Test
    @DisplayName("statement output object stores on the statements bucket with exact size and content")
    void statementObjectOnStatementsBucket() {
        // CBSTM03A STMTFILE (AWS.M2.CARDDEMO.STATEMNT.PS) -> statements/2025/0000000001.txt.
        final String statementKey = "statements/2025/0000000001.txt";
        final String statementPayload = String.join("\n",
                "CARDDEMO ACCOUNT STATEMENT",
                "ACCOUNT 0000000001",
                "STATEMENT DATE 2025-01-31",
                "OPENING BALANCE        0000001234.56",
                "CLOSING BALANCE        0000001357.91");

        // ---- Act. ----
        s3.putObject(PutObjectRequest.builder().bucket(statementsBucket).key(statementKey).build(),
                RequestBody.fromString(statementPayload, StandardCharsets.UTF_8));

        // ---- Assert: object exists with the exact uploaded byte length (HEAD). ----
        final byte[] expectedBytes = statementPayload.getBytes(StandardCharsets.UTF_8);
        HeadObjectResponse head = s3.headObject(
                HeadObjectRequest.builder().bucket(statementsBucket).key(statementKey).build());
        assertThat(head.contentLength())
                .as("statement object content length")
                .isEqualTo((long) expectedBytes.length);

        // ---- Assert: full content round-trips byte-for-byte (GET). ----
        ResponseBytes<GetObjectResponse> retrieved = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(statementsBucket).key(statementKey).build());
        assertThat(retrieved.asByteArray())
                .as("statement object content")
                .isEqualTo(expectedBytes);
    }

    // -------------------------------------------------------------------------------------------------
    // Test 3 — report + rejection output objects on the output bucket (path-style access confirmed).
    //          (TRANREPT GDG -> reports/…; DALYREJS GDG -> rejects/…  ->  carddemo-batch-output.)
    // -------------------------------------------------------------------------------------------------

    /**
     * Verifies that a report object (mirroring the {@code TRANREPT} GDG) and a rejection object
     * (mirroring {@code DALYREJS.jcl}) are stored on {@code carddemo-batch-output} and each is enumerable
     * under its own key prefix &mdash; and that path-style access works end-to-end.
     *
     * <p>{@code app/jcl/DEFGDGB.jcl} defines the {@code AWS.M2.CARDDEMO.TRANREPT} GDG and
     * {@code app/jcl/DALYREJS.jcl} defines the {@code AWS.M2.CARDDEMO.DALYREJS} GDG; both batch
     * <em>output</em> artifacts collapse into the shared {@code carddemo-batch-output} bucket under the
     * {@code reports/} and {@code rejects/} prefixes respectively (decision D-003).</p>
     */
    @Test
    @DisplayName("report and rejection output objects store on the output bucket under their prefixes")
    void reportAndRejectObjectsOnOutputBucket() {
        // TRANREPT GDG (AWS.M2.CARDDEMO.TRANREPT)  -> reports/tranrept-G0001V00.txt
        // DALYREJS GDG (AWS.M2.CARDDEMO.DALYREJS)  -> rejects/dalyrejs.txt
        final String reportKey = "reports/tranrept-G0001V00.txt";
        final String rejectKey = "rejects/dalyrejs.txt";
        final String reportPayload = String.join("\n",
                "TRANSACTION DETAIL REPORT",
                "PAGE 0001",
                "TRAN 0000000001 ACCT 0000000001 AMT 0000123456 CR");
        final String rejectPayload = String.join("\n",
                "0000000099REJECTED RECORD",
                "REASON: ACCOUNT NOT FOUND");

        // ---- Act: write both batch OUTPUT artifacts. ----
        s3.putObject(PutObjectRequest.builder().bucket(outputBucket).key(reportKey).build(),
                RequestBody.fromString(reportPayload, StandardCharsets.UTF_8));
        s3.putObject(PutObjectRequest.builder().bucket(outputBucket).key(rejectKey).build(),
                RequestBody.fromString(rejectPayload, StandardCharsets.UTF_8));

        // ---- Assert: each output artifact is enumerable under its own prefix. ----
        assertThat(listKeys(outputBucket, "reports/"))
                .as("report output object listed under reports/ prefix")
                .contains(reportKey);
        assertThat(listKeys(outputBucket, "rejects/"))
                .as("rejection output object listed under rejects/ prefix")
                .contains(rejectKey);

        // PATH-STYLE ACCESS (LocalStack S3 pitfall, tech-spec L981): every put/list above necessarily
        // traversed path-style URLs (http://host:port/bucket/key). LocalStack S3 does NOT resolve
        // virtual-host-style URLs (http://bucket.host/...), so these operations succeeding end-to-end is
        // itself the proof that forcePathStyle(true) / spring.cloud.aws.s3.path-style-access-enabled=true
        // is in effect — handled for us by the inherited newS3Client() factory.
    }

    // -------------------------------------------------------------------------------------------------
    // Suite teardown.
    // -------------------------------------------------------------------------------------------------

    /**
     * Empties then deletes the three buckets this test created and closes the verification client
     * (self-managed lifecycle, AAP §0.7.7). The shared LocalStack container is owned by the base class
     * and is intentionally NOT stopped here (it is reaped by the Testcontainers <em>Ryuk</em> sidecar at
     * JVM exit).
     */
    @AfterAll
    void deleteBuckets() {
        if (s3 == null) {
            return;
        }
        emptyAndDeleteBucket(inputBucket);
        emptyAndDeleteBucket(outputBucket);
        emptyAndDeleteBucket(statementsBucket);
        s3.close();
    }

    // -------------------------------------------------------------------------------------------------
    // Helpers.
    // -------------------------------------------------------------------------------------------------

    /**
     * Ensures {@code bucketName} exists and is empty: creates it if absent (tolerating a pre-existing
     * bucket owned by the same dummy account) and then removes any residual objects from a prior aborted
     * run, so the suite starts from a known-empty state and reruns stay idempotent (AAP §0.7.7).
     *
     * @param bucketName the bucket to create and empty; never {@code null}
     */
    private void ensureCleanBucket(String bucketName) {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException alreadyPresent) {
            // Idempotent: the bucket already exists on the shared container — reuse it as-is.
        }
        emptyBucket(bucketName);
    }

    /**
     * Removes every object from {@code bucketName} via a single bulk delete; a no-op when already empty.
     *
     * @param bucketName the bucket to empty; never {@code null}
     */
    private void emptyBucket(String bucketName) {
        List<ObjectIdentifier> toDelete = s3.listObjectsV2(
                        ListObjectsV2Request.builder().bucket(bucketName).build())
                .contents().stream()
                .map(S3Object::key)
                .map(key -> ObjectIdentifier.builder().key(key).build())
                .toList();
        if (toDelete.isEmpty()) {
            return;
        }
        s3.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(bucketName)
                .delete(Delete.builder().objects(toDelete).build())
                .build());
    }

    /**
     * Empties then deletes {@code bucketName} (S3 requires a bucket to be empty before it can be deleted).
     *
     * @param bucketName the bucket to remove; never {@code null}
     */
    private void emptyAndDeleteBucket(String bucketName) {
        emptyBucket(bucketName);
        s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build());
    }

    /**
     * Lists the object keys under {@code prefix} in {@code bucketName}.
     *
     * @param bucketName the bucket to list; never {@code null}
     * @param prefix     the key prefix to filter on (a GDG base / output folder); never {@code null}
     * @return the matching object keys (possibly empty); never {@code null}
     */
    private List<String> listKeys(String bucketName, String prefix) {
        ListObjectsV2Response response = s3.listObjectsV2(
                ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix).build());
        return response.contents().stream().map(S3Object::key).toList();
    }
}
