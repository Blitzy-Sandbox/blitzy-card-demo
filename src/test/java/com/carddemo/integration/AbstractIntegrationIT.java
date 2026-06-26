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
package com.carddemo.integration;

import com.carddemo.config.AwsConfig;
import com.carddemo.service.JwtTokenService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.QueueNameExistsException;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared Testcontainers base class for every integration / end-to-end test in the
 * CardDemo migration. It is the single source of real-infrastructure wiring for the
 * whole verify-phase suite: every concrete {@code *IT} in
 * {@code com.carddemo.integration} extends it, and the sibling
 * {@code com.carddemo.gates.GateVerificationIT} reuses it as well. Because the gates
 * package consumes this class, every member exposed here is {@code public} or
 * {@code protected} (never package-private to {@code integration}).
 *
 * <h2>Why real containers (no H2, no mocks)</h2>
 * The migration must demonstrate byte-equivalent, VSAM-fidelity behaviour for
 * Validation Gates 1, 4 and 5. An in-memory database (H2) or a mocked AWS client would
 * silently diverge from the production contract, so this base wires <em>real</em>
 * infrastructure through Testcontainers:
 * <ul>
 *   <li><strong>PostgreSQL&nbsp;16</strong> ({@link #POSTGRES}) — Flyway applies the real
 *       {@code V1}/{@code V2}/{@code V3} migrations (schema, indexes, and the seed derived
 *       from the nine ASCII fixtures) inside the container, and Hibernate runs with
 *       {@code ddl-auto=validate}, so the context starts only when every {@code @Entity}
 *       mapping agrees with the migrated schema.</li>
 *   <li><strong>LocalStack</strong> ({@link #LOCALSTACK}) — provides real S3, SQS and SNS
 *       endpoints so the GDG→S3, TDQ→SQS-FIFO and notification→SNS bridges are exercised
 *       against the actual AWS contract with zero live-AWS dependencies.</li>
 * </ul>
 *
 * <h2>Singleton container lifecycle</h2>
 * The containers are {@code protected static final} and are started once in a
 * {@code static} initializer (the Testcontainers "singleton container" pattern). They are
 * deliberately <strong>not</strong> annotated {@code @Container}: that annotation would let
 * the JUnit extension stop and restart them per class, whereas this suite wants a single
 * PostgreSQL and a single LocalStack shared across <em>all</em> {@code *IT} classes for
 * speed and consistency. The JVM (and Ryuk) reap them at shutdown; cleanup helpers here
 * never tear the shared containers down between classes.
 *
 * <h2>No hardcoded ports or credentials</h2>
 * Container host names and ports are random per run, so {@link #properties} injects the
 * datasource URL and the AWS endpoints at runtime via {@link DynamicPropertySource}. The
 * only credentials used are the LocalStack dummy {@code test}/{@code test} values; the JWT
 * signing key comes from {@code application-test.yml}. No real secret is registered here.
 *
 * <h2>What this base provides to subclasses</h2>
 * <ul>
 *   <li>AWS SDK&nbsp;v2 client factories pre-pointed at LocalStack
 *       ({@link #newS3Client()}, {@link #newSqsClient()}, {@link #newSqsAsyncClient()},
 *       {@link #newSnsClient()}).</li>
 *   <li>Idempotent provisioning / cleanup of the canonical resources
 *       ({@link #provisionCanonicalAwsResources()}, {@link #cleanupCanonicalAwsResources()},
 *       {@link #emptyBucket(String)}, {@link #purgeQueue(String)}, {@link #reportQueueUrl()}).
 *       Resource names are read from the injected {@link AwsConfig.AwsResourceProperties} so
 *       they never drift from {@code application*.yml}.</li>
 *   <li>Database helpers ({@link #countRows(String)}, {@link #deleteFrom(String)},
 *       {@link #truncateTables(String...)}).</li>
 *   <li>JWT / authentication helpers ({@link #bearerTokenFor(String, String)},
 *       {@link #authHeaders(String, String)}, {@link #adminAuthHeaders()},
 *       {@link #userAuthHeaders()}).</li>
 *   <li>HTTP accessors for E2E ({@link #restTemplate}, {@link #port}, {@link #url(String)})
 *       and a {@link #mockMvc} alternative (this base is annotated
 *       {@link AutoConfigureMockMvc}, so both styles are available).</li>
 * </ul>
 *
 * <h2>Data isolation</h2>
 * Flyway&nbsp;V3 seeds the reference / master tables (accounts, cards, card_xref,
 * customers, disclosure_group, transaction_category_balance, transaction_category,
 * transaction_type, the seeded users {@code ADMIN001}/{@code USER0001}) and the
 * {@code daily_transaction} staging feed (300 rows from {@code dailytran.txt}), while the
 * posted {@code transactions} table starts empty. A mutating IT
 * should keep the shared data deterministic by either annotating the test
 * {@code @Transactional} (Spring rolls the test transaction back) or cleaning up
 * explicitly. Batch-job ITs <strong>cannot</strong> be {@code @Transactional} — the job
 * commits in its own transactions — so they must clean up the rows they create (for
 * example via {@link #truncateTables(String...)} or {@link #deleteFrom(String)}).
 *
 * <h2>Spring Batch test support</h2>
 * This base intentionally does <em>not</em> declare {@code JobLauncherTestUtils} /
 * {@code JobRepositoryTestUtils} beans: those require exactly one {@code Job} bean and the
 * migration defines several. Each batch IT instead supplies its own
 * {@code @TestConfiguration} that wires {@code JobLauncherTestUtils} to the specific job
 * under test. The base only guarantees that the real {@code JobRepository},
 * {@code JobLauncher} and {@code BATCH_*} metadata tables exist — the latter are created in
 * the PostgreSQL container by {@code spring.batch.jdbc.initialize-schema=always}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
public abstract class AbstractIntegrationIT {

    /** Docker image for the PostgreSQL 16 container (pinned and locally cached). */
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    /**
     * Docker image for LocalStack. The community image is used on purpose: S3, SQS and SNS
     * are community features, so the suite needs no {@code LOCALSTACK_AUTH_TOKEN} and stays
     * credential-free. The tag is pinned for deterministic, offline-friendly runs.
     */
    private static final String LOCALSTACK_IMAGE = "localstack/localstack:3.8.1";

    /** Logical database name, user and password for the test PostgreSQL container. */
    private static final String DB_NAME = "carddemo";
    private static final String DB_USER = "carddemo";
    private static final String DB_PASSWORD = "carddemo";

    /** LocalStack dummy AWS credentials (never real secrets). */
    private static final String AWS_TEST_ACCESS_KEY = "test";
    private static final String AWS_TEST_SECRET_KEY = "test";

    /**
     * Canonical AWS resource names. These mirror the defaults bound by
     * {@code application.yml} / {@code application-test.yml} and provisioned by
     * {@code localstack-init/init-aws.sh}; they are the single, in-one-place fallback used
     * only when {@link #awsResourceProperties} injection is unavailable. The injected
     * properties remain the authoritative source so a name can never drift from config.
     */
    protected static final String S3_INPUT_BUCKET = "carddemo-batch-input";
    protected static final String S3_OUTPUT_BUCKET = "carddemo-batch-output";
    protected static final String S3_STATEMENT_BUCKET = "carddemo-statements";
    protected static final String SQS_REPORT_QUEUE = "carddemo-report-jobs.fifo";
    protected static final String SNS_TOPIC = "carddemo-notifications";

    /** Seeded administrator user (Flyway V3); user-type {@code 'A'} maps to {@code ROLE_ADMIN}. */
    protected static final String SEEDED_ADMIN_USER_ID = "ADMIN001";
    protected static final String SEEDED_ADMIN_USER_TYPE = "A";

    /** Seeded standard user (Flyway V3); user-type {@code 'U'} maps to {@code ROLE_USER}. */
    protected static final String SEEDED_STANDARD_USER_ID = "USER0001";
    protected static final String SEEDED_STANDARD_USER_TYPE = "U";

    /**
     * Guards table names interpolated into the few unavoidable dynamic-DDL/DML helper
     * statements below. Table identifiers in tests are trusted constants, but validating
     * them against a strict SQL-identifier pattern keeps the helpers injection-safe by
     * construction (and keeps the unsafe-code audit, Gate&nbsp;6, clean).
     */
    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    /**
     * Shared PostgreSQL 16 container. Declared raw because in Testcontainers 2.x
     * {@code PostgreSQLContainer} is a concrete, non-generic type (it fixes its self-type to
     * itself), so no type argument is applicable.
     */
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                    .withDatabaseName(DB_NAME)
                    .withUsername(DB_USER)
                    .withPassword(DB_PASSWORD)
                    .withReuse(true);

    /**
     * Shared LocalStack container exposing S3, SQS and SNS. Declared with the
     * {@code org.testcontainers.localstack} (2.x) type whose {@code withServices} accepts
     * service names as strings; the legacy {@code org.testcontainers.containers.localstack}
     * type is deprecated and is intentionally avoided.
     */
    protected static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
                    .withServices("s3", "sqs", "sns")
                    .withReuse(true);

    static {
        POSTGRES.start();
        LOCALSTACK.start();
    }

    /**
     * Registers every runtime-dynamic property: the random datasource coordinates from the
     * PostgreSQL container and the random LocalStack endpoint (plus region, dummy
     * credentials and the S3 path-style flag LocalStack requires). No port and no real
     * secret is hardcoded.
     *
     * @param registry the Spring test property registry to populate
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Datasource — injected from the PostgreSQL container.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // AWS — single endpoint plus per-service endpoints (robust across Spring Cloud
        // AWS 3.3.0 endpoint resolution). Resolve the random endpoint once.
        final String endpoint = LOCALSTACK.getEndpoint().toString();
        registry.add("spring.cloud.aws.endpoint", () -> endpoint);
        registry.add("spring.cloud.aws.s3.endpoint", () -> endpoint);
        registry.add("spring.cloud.aws.sqs.endpoint", () -> endpoint);
        registry.add("spring.cloud.aws.sns.endpoint", () -> endpoint);

        // Reinforce the test profile's region / credentials / path-style settings.
        registry.add("spring.cloud.aws.region.static", () -> "us-east-1");
        registry.add("spring.cloud.aws.credentials.access-key", () -> AWS_TEST_ACCESS_KEY);
        registry.add("spring.cloud.aws.credentials.secret-key", () -> AWS_TEST_SECRET_KEY);
        registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
    }

    /** REST client bound to the random server port for E2E HTTP exchanges. */
    @Autowired
    protected TestRestTemplate restTemplate;

    /** {@link MockMvc} alternative to {@link #restTemplate} for servlet-layer assertions. */
    @Autowired
    protected MockMvc mockMvc;

    /** JDBC access for deterministic database setup / verification in repository and batch ITs. */
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** Issues signed JWTs for authenticated E2E requests against the secured REST surface. */
    @Autowired
    protected JwtTokenService jwtTokenService;

    /** Authoritative, config-bound AWS resource names (buckets, FIFO queue, topic). */
    @Autowired
    protected AwsConfig.AwsResourceProperties awsResourceProperties;

    /** Random HTTP port the embedded server bound to for this test. */
    @LocalServerPort
    protected int port;

    // -------------------------------------------------------------------------------------
    // AWS SDK v2 client factories — pre-pointed at LocalStack (credential-free beyond the
    // test/test dummies). Every returned client is AutoCloseable: callers must use
    // try-with-resources (or close()) so SDK threads/connections do not leak between tests.
    // -------------------------------------------------------------------------------------

    /**
     * Creates an {@link S3Client} pointed at LocalStack with path-style addressing enabled
     * (required by LocalStack S3).
     *
     * @return a new, caller-closed S3 client
     */
    protected static S3Client newS3Client() {
        return S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(testCredentials())
                .forcePathStyle(true)
                .build();
    }

    /**
     * Creates a synchronous {@link SqsClient} pointed at LocalStack.
     *
     * @return a new, caller-closed SQS client
     */
    protected static SqsClient newSqsClient() {
        return SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(testCredentials())
                .build();
    }

    /**
     * Creates an asynchronous {@link SqsAsyncClient} pointed at LocalStack (the async client
     * mirrors the one Spring Cloud AWS uses for {@code SqsTemplate}).
     *
     * @return a new, caller-closed asynchronous SQS client
     */
    protected static SqsAsyncClient newSqsAsyncClient() {
        return SqsAsyncClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(testCredentials())
                .build();
    }

    /**
     * Creates an {@link SnsClient} pointed at LocalStack.
     *
     * @return a new, caller-closed SNS client
     */
    protected static SnsClient newSnsClient() {
        return SnsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(testCredentials())
                .build();
    }

    /** Builds the LocalStack dummy credential provider shared by every client factory. */
    private static StaticCredentialsProvider testCredentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(AWS_TEST_ACCESS_KEY, AWS_TEST_SECRET_KEY));
    }

    // -------------------------------------------------------------------------------------
    // Canonical AWS resource names — read from the injected, config-bound properties so a
    // name can never drift from application*.yml; the static constants are the fallback.
    // -------------------------------------------------------------------------------------

    /** @return the batch-input S3 bucket name. */
    protected String inputBucket() {
        return awsResourceProperties != null
                ? awsResourceProperties.getS3().getInputBucket() : S3_INPUT_BUCKET;
    }

    /** @return the batch-output S3 bucket name. */
    protected String outputBucket() {
        return awsResourceProperties != null
                ? awsResourceProperties.getS3().getOutputBucket() : S3_OUTPUT_BUCKET;
    }

    /** @return the statement S3 bucket name. */
    protected String statementBucket() {
        return awsResourceProperties != null
                ? awsResourceProperties.getS3().getStatementBucket() : S3_STATEMENT_BUCKET;
    }

    /** @return the report SQS FIFO queue name. */
    protected String reportQueueName() {
        return awsResourceProperties != null
                ? awsResourceProperties.getSqs().getReportQueue() : SQS_REPORT_QUEUE;
    }

    /** @return the notification SNS topic name. */
    protected String snsTopicName() {
        return awsResourceProperties != null
                ? awsResourceProperties.getSns().getTopic() : SNS_TOPIC;
    }

    // -------------------------------------------------------------------------------------
    // Canonical resource provisioning + cleanup (LocalStack Verification rule). Every
    // operation is idempotent so ITs may provision freely; the shared containers are never
    // torn down here.
    // -------------------------------------------------------------------------------------

    /**
     * Idempotently provisions the canonical AWS resources the production code paths use: the
     * three S3 buckets, the report FIFO queue, and the notification SNS topic. AWS / E2E ITs
     * should call this from their own {@code @BeforeEach}/{@code @BeforeAll} so they exercise
     * the real contract (Gate&nbsp;5). Calling it repeatedly is safe.
     */
    protected void provisionCanonicalAwsResources() {
        try (S3Client s3 = newS3Client();
             SqsClient sqs = newSqsClient();
             SnsClient sns = newSnsClient()) {
            createBucketIfAbsent(s3, inputBucket());
            createBucketIfAbsent(s3, outputBucket());
            createBucketIfAbsent(s3, statementBucket());
            createFifoQueueIfAbsent(sqs, reportQueueName());
            // create-topic returns the existing ARN if the topic already exists (idempotent).
            sns.createTopic(CreateTopicRequest.builder().name(snsTopicName()).build());
        }
    }

    /** Creates an S3 bucket unless it already exists for this run. */
    private static void createBucketIfAbsent(S3Client s3, String bucket) {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException alreadyThere) {
            // Idempotent: the bucket already exists — nothing to do.
        }
    }

    /** Creates the report FIFO queue (content-based dedup) unless it already exists. */
    private static void createFifoQueueIfAbsent(SqsClient sqs, String queueName) {
        try {
            sqs.createQueue(CreateQueueRequest.builder()
                    .queueName(queueName)
                    .attributes(Map.of(
                            QueueAttributeName.FIFO_QUEUE, "true",
                            QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"))
                    .build());
        } catch (QueueNameExistsException alreadyThere) {
            // Idempotent: the FIFO queue already exists — nothing to do.
        }
    }

    /**
     * Deletes every object in the given bucket (so an IT can reset S3 state) without removing
     * the bucket itself. Tolerates a missing bucket.
     *
     * @param bucket the bucket to empty
     */
    protected static void emptyBucket(String bucket) {
        try (S3Client s3 = newS3Client()) {
            ListObjectsV2Response listing =
                    s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build());
            for (S3Object object : listing.contents()) {
                s3.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key(object.key())
                        .build());
            }
        } catch (NoSuchBucketException absent) {
            // Nothing to empty — the bucket was never provisioned.
        }
    }

    /**
     * Purges every message from the given SQS queue. Tolerates a missing queue.
     *
     * @param queueUrl the absolute queue URL to purge
     */
    protected static void purgeQueue(String queueUrl) {
        try (SqsClient sqs = newSqsClient()) {
            sqs.purgeQueue(PurgeQueueRequest.builder().queueUrl(queueUrl).build());
        } catch (QueueDoesNotExistException absent) {
            // Nothing to purge — the queue was never provisioned.
        }
    }

    /**
     * Resets the canonical AWS resources to an empty state between tests: empties the three
     * buckets and purges the report FIFO queue. The shared containers themselves are left
     * running.
     */
    protected void cleanupCanonicalAwsResources() {
        emptyBucket(inputBucket());
        emptyBucket(outputBucket());
        emptyBucket(statementBucket());
        try {
            purgeQueue(reportQueueUrl());
        } catch (QueueDoesNotExistException absent) {
            // Queue not provisioned — nothing to purge.
        }
    }

    /**
     * Resolves the absolute URL of the canonical report FIFO queue from SQS.
     *
     * @return the queue URL
     * @throws QueueDoesNotExistException if the queue has not been provisioned
     */
    protected String reportQueueUrl() {
        try (SqsClient sqs = newSqsClient()) {
            return sqs.getQueueUrl(GetQueueUrlRequest.builder()
                    .queueName(reportQueueName())
                    .build()).queueUrl();
        }
    }

    // -------------------------------------------------------------------------------------
    // Database helpers — deterministic setup / verification for repository and batch ITs.
    // Table names are trusted test constants and are validated against a strict identifier
    // pattern before being interpolated, keeping the helpers injection-safe by construction.
    // -------------------------------------------------------------------------------------

    /**
     * Counts the rows in a table.
     *
     * @param table the (optionally schema-qualified) table name
     * @return the row count
     */
    protected long countRows(String table) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + safeIdentifier(table), Long.class);
        return count == null ? 0L : count;
    }

    /**
     * Deletes all rows from a table (referential order is the caller's responsibility).
     *
     * @param table the (optionally schema-qualified) table name
     */
    protected void deleteFrom(String table) {
        jdbcTemplate.execute("DELETE FROM " + safeIdentifier(table));
    }

    /**
     * Truncates the given tables, restarting identity sequences and cascading to dependent
     * rows. Intended for batch-job ITs (which cannot be {@code @Transactional}) to clear the
     * {@code transactions} / {@code daily_transaction} tables they populate.
     *
     * @param tables the (optionally schema-qualified) table names to truncate
     */
    protected void truncateTables(String... tables) {
        for (String table : tables) {
            jdbcTemplate.execute(
                    "TRUNCATE TABLE " + safeIdentifier(table) + " RESTART IDENTITY CASCADE");
        }
    }

    /** Validates a SQL identifier against a strict pattern before interpolation. */
    private static String safeIdentifier(String identifier) {
        if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Unsafe SQL identifier: " + identifier);
        }
        return identifier;
    }

    // -------------------------------------------------------------------------------------
    // JWT / authentication helpers — stateless, token-based auth for the secured REST
    // surface. user-type 'A' grants ROLE_ADMIN, anything else (e.g. 'U') grants ROLE_USER,
    // matching JwtAuthenticationFilter. spring-security-test (@WithMockUser /
    // SecurityMockMvcRequestPostProcessors.jwt()) is also available for MockMvc assertions.
    // -------------------------------------------------------------------------------------

    /**
     * Builds the {@code Authorization} header value (a {@code "Bearer <jwt>"} string) for a
     * user.
     *
     * @param userId   the user identifier stamped as the token subject
     * @param userType the single-character user-type ({@code 'A'} admin, {@code 'U'} user)
     * @return the bearer header value
     */
    protected String bearerTokenFor(String userId, String userType) {
        return "Bearer " + jwtTokenService.generateToken(userId, userType);
    }

    /**
     * Builds {@link HttpHeaders} carrying a bearer token for a user, for use with
     * {@link #restTemplate} exchanges.
     *
     * @param userId   the user identifier
     * @param userType the single-character user-type
     * @return headers with {@code Authorization} set
     */
    protected HttpHeaders authHeaders(String userId, String userType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, bearerTokenFor(userId, userType));
        return headers;
    }

    /** @return authorization headers for the seeded administrator ({@link #SEEDED_ADMIN_USER_ID}). */
    protected HttpHeaders adminAuthHeaders() {
        return authHeaders(SEEDED_ADMIN_USER_ID, SEEDED_ADMIN_USER_TYPE);
    }

    /** @return authorization headers for the seeded standard user ({@link #SEEDED_STANDARD_USER_ID}). */
    protected HttpHeaders userAuthHeaders() {
        return authHeaders(SEEDED_STANDARD_USER_ID, SEEDED_STANDARD_USER_TYPE);
    }

    // -------------------------------------------------------------------------------------
    // HTTP accessors for E2E.
    // -------------------------------------------------------------------------------------

    /**
     * Builds an absolute URL against the random local server port.
     *
     * @param path the request path (a leading slash is added if absent)
     * @return the absolute {@code http://localhost:<port><path>} URL
     */
    protected String url(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        return "http://localhost:" + port + normalized;
    }
}
