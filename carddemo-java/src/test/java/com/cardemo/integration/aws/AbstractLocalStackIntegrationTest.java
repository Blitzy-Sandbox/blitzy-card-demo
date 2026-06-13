/*
 * ============================================================================
 *  CardDemo — Greenfield Java 25 LTS + Spring Boot 3.x Migration
 *  Shared AWS / LocalStack Integration-Test Harness (foundational abstract base)
 * ============================================================================
 *
 *  PROVENANCE & TRACEABILITY (AAP §0.7.1 / §0.7.2)
 *  Net-new greenfield test infrastructure with NO COBOL source equivalent. The
 *  behaviour exercised by the concrete subclasses is translated from the frozen
 *  AWS CardDemo COBOL/CICS/JCL baseline at commit SHA 27d6c6f; the COBOL/JCL
 *  sources are read-only reference and are NEVER copied into this repository.
 *  The governing blueprint docs/technical-specifications.md is a REFERENCE
 *  artifact only (not copied). Base package is com.cardemo (decision D-006 —
 *  deliberately NOT com.carddemo), matching <groupId>com.cardemo</groupId> in
 *  carddemo-java/pom.xml.
 * ============================================================================
 */
package com.cardemo.integration.aws;

import com.cardemo.config.AwsConfig;

import org.junit.jupiter.api.TestInstance;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Foundational abstract base class for every AWS-service integration test in
 * {@code com.cardemo.integration.aws}.
 *
 * <p>This is the keystone of the cloud-integration <strong>parity-validation</strong> suite for the
 * AWS CardDemo COBOL/CICS &rarr; Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x migration. It hosts the
 * <strong>shared singleton Testcontainers</strong> (a LocalStack emulator for S3/SQS/SNS plus a
 * PostgreSQL&nbsp;16 database) and the Spring {@code @DynamicPropertySource} wiring that the three
 * concrete {@code *IT} subclasses &mdash; {@code ReportSubmissionSqsIT}, {@code S3StagingIT}, and
 * {@code SnsNotificationIT} &mdash; inherit. Each subclass <strong>extends</strong> this class and
 * contains only its own arrange/act/assert {@code @Test} logic; this base class itself defines
 * <strong>no</strong> {@code @Test} methods and performs <strong>no</strong> resource provisioning
 * (subclasses create and destroy their own buckets/queues/topics in {@code @BeforeAll}/{@code @AfterAll}
 * per AAP §0.7.7, tech-spec L1003). It realizes the authoritative target tree's {@code aws/} folder
 * ("LocalStack integration tests", tech-spec L484) and the "LocalStack Verification" mandate
 * (tech-spec L990&ndash;L1018).
 *
 * <h2>ZERO live AWS / zero live credentials (AAP §0.7.2 / §0.7.7, constraint C-003)</h2>
 * <p>Every AWS interaction is directed at a <strong>LocalStack Testcontainer</strong> built from the
 * <strong>community</strong> image {@code localstack/localstack} (S3, SQS, and SNS are all
 * community-supported). This class deliberately does <strong>not</strong> use
 * {@code localstack/localstack-pro} and does <strong>not</strong> require {@code LOCALSTACK_AUTH_TOKEN};
 * the docker-compose stack may use the Pro image for local development, but the hermetic test surface
 * never does. There are no real AWS endpoints and no hardcoded secrets anywhere: the only credentials
 * in play are LocalStack's dummy {@code test}/{@code test} access/secret keys, which the container
 * itself supplies through {@link LocalStackContainer#getAccessKey()} /
 * {@link LocalStackContainer#getSecretKey()}. The AWS endpoint can therefore only ever resolve to
 * LocalStack, never to a live AWS account.
 *
 * <h2>Technology substitutions exercised by this harness (AAP §0.1.2 / Minimal Change Clause §0.7.1)</h2>
 * <dl>
 *   <dt>VSAM KSDS &rarr; PostgreSQL&nbsp;16 + Spring Data JPA</dt>
 *   <dd>The legacy z/OS VSAM KSDS data layer is replaced by a relational schema. {@code @SpringBootTest}
 *       loads the FULL application context, so the JPA repositories and the Flyway
 *       {@code V1__create_schema} &rarr; {@code V2__create_indexes} &rarr; {@code V3__seed_data}
 *       migrations run on startup and REQUIRE a live datasource &mdash; hence the
 *       {@link #POSTGRES} container below even though the AWS tests do not touch the database directly
 *       (without it the context would fail to start).</dd>
 *   <dt>CICS TDQ {@code WRITEQ} &rarr; AWS SQS (FIFO)</dt>
 *   <dd>The sole online&rarr;batch bridge {@code CORPT00C}'s {@code WRITEQ TD QUEUE('JOBS')} becomes an
 *       SQS publish on {@code carddemo-report-jobs.fifo} (tech-spec L30, L631; AAP §0.6.3), verified by
 *       {@code ReportSubmissionSqsIT}.</dd>
 *   <dt>GDG generations &rarr; versioned S3 objects</dt>
 *   <dd>Sequential PS staging datasets and GDG generations collapse into the
 *       {@code carddemo-batch-input}/{@code carddemo-batch-output}/{@code carddemo-statements} S3
 *       buckets (tech-spec L22), verified by {@code S3StagingIT}.</dd>
 * </dl>
 *
 * <h2>Why this class is {@code abstract} and carries no tests</h2>
 * <p>The class is {@code abstract} and its name carries <strong>no</strong> {@code IT}/{@code Test}
 * suffix, so neither the {@code maven-failsafe-plugin} (which runs {@code **}{@code /*IT.java} and
 * {@code **}{@code /integration/**} under {@code mvn verify -Pintegration}) nor the
 * {@code maven-surefire-plugin} ever instantiates or executes it directly &mdash; JUnit&nbsp;5 does not
 * treat an abstract class as a runnable test. It therefore safely centralizes all shared lifecycle and
 * helper machinery without ever running as a test in its own right, mirroring the established sibling
 * convention {@code com.cardemo.integration.repository.AbstractRepositoryIT} and
 * {@code com.cardemo.integration.batch.AbstractBatchJobIT}.
 *
 * <h2>Why the Testcontainers SINGLETON pattern (NOT {@code @Container})</h2>
 * <p>All three concrete subclasses declare an <em>identical</em> Spring context configuration
 * ({@code @SpringBootTest} + {@code @ActiveProfiles("test")} + the {@code @DynamicPropertySource}
 * below). Spring's {@code TestContext} framework caches and reuses a single {@code ApplicationContext}
 * across test classes <em>only when the resolved configuration — including every registered property —
 * is identical</em>; the datasource URL and the AWS endpoint are part of that configuration, so they
 * must stay <strong>stable for the whole suite</strong>. This is exactly why both containers use the
 * Testcontainers <strong>singleton pattern</strong>: each {@code static} container is started once in a
 * {@code static} initializer and shared by every subclass. The fields are deliberately
 * <strong>not</strong> annotated with {@code @Container}, because {@code @Container} ties a container's
 * start/stop lifecycle to a single test class &mdash; the JUnit extension would stop the container
 * after the first subclass finishes and start a fresh one (with a fresh mapped port, hence a different
 * URL/endpoint) for the next, invalidating the cached context and breaking the other subclasses. The
 * singletons are intentionally <strong>never explicitly stopped</strong>; the Testcontainers
 * <em>Ryuk</em> sidecar reaps them when the JVM exits. The {@link Testcontainers @Testcontainers}
 * annotation is present for convention/intent (this package uses the JUnit&nbsp;5 Testcontainers
 * lifecycle); with no {@code @Container} fields it is a deliberate no-op that documents the singleton
 * choice rather than driving any container.
 *
 * <h2>Testcontainers 2.x note (warning-free build, AAP §0.7.8)</h2>
 * <p>This module pins {@code testcontainers-bom:2.0.3}. In Testcontainers&nbsp;2.x the legacy
 * {@code org.testcontainers.containers.PostgreSQLContainer} and
 * {@code org.testcontainers.containers.localstack.LocalStackContainer} (with its {@code Service} enum)
 * are {@code @Deprecated}; this class therefore uses the current, non-deprecated
 * {@link org.testcontainers.postgresql.PostgreSQLContainer} (non-generic) and
 * {@link org.testcontainers.localstack.LocalStackContainer} (whose {@code withServices(String...)}
 * accepts service names as strings), keeping the build free of deprecation warnings and consistent with
 * the sibling base classes.
 *
 * @see com.cardemo.CardDemoApplication
 * @see com.cardemo.config.AwsConfig.AwsResourceProperties
 * @see org.testcontainers.localstack.LocalStackContainer
 * @see org.testcontainers.postgresql.PostgreSQLContainer
 * @see DynamicPropertySource
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers // Convention/intent marker: containers below use the singleton pattern, NOT @Container (see class Javadoc).
public abstract class AbstractLocalStackIntegrationTest {

    /**
     * Pinned LocalStack <strong>community</strong> image tag for reproducible integration runs. A fixed
     * major tag is preferred over {@code :latest} so the AWS emulator surface is deterministic across
     * machines and CI, and it matches the sibling {@code AbstractBatchJobIT} so the whole integration
     * suite reuses a single cached image. This is the open-source community image
     * ({@code localstack/localstack}); it is deliberately <strong>not</strong>
     * {@code localstack/localstack-pro} and needs no {@code LOCALSTACK_AUTH_TOKEN} (AAP §0.7.7).
     */
    private static final String LOCALSTACK_IMAGE = "localstack/localstack:3";

    /** Pinned PostgreSQL image tag matching the PostgreSQL&nbsp;16 migration target / docker-compose. */
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    // -------------------------------------------------------------------------
    // Shared, singleton containers (started once for the whole AWS IT suite).
    // -------------------------------------------------------------------------

    /**
     * Shared, singleton LocalStack container providing the S3, SQS, and SNS surfaces &mdash; the cloud
     * replacement for GDG generations (S3), the CICS TDQ {@code WRITEQ} online&rarr;batch bridge
     * (SQS&nbsp;FIFO), and CICS notification messaging (SNS).
     *
     * <p>Started once in the {@code static} initializer below and never explicitly stopped (reaped by
     * the Testcontainers <em>Ryuk</em> sidecar at JVM exit), so its mapped endpoint &mdash; and hence
     * the {@code spring.cloud.aws.*} properties registered by
     * {@link #registerProperties(DynamicPropertyRegistry)} &mdash; stays stable for the whole suite,
     * allowing all subclasses to share one cached Spring context. The {@code @SuppressWarnings("resource")}
     * is deliberate and justified: the container is an {@link AutoCloseable} intentionally left open
     * (closing it would defeat the singleton-sharing strategy); it is the only such suppression site for
     * this field (AAP §0.7.8 unsafe-code audit — justified per site).</p>
     */
    @SuppressWarnings("resource") // Singleton container intentionally never closed; reaped by Ryuk at JVM exit.
    protected static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
                    // S3 = GDG/PS staging replacement; SQS = CICS TDQ replacement; SNS = notifications.
                    // Testcontainers 2.x: withServices takes service NAMES as strings (the deprecated
                    // Service enum is intentionally avoided to keep the build warning-free, §0.7.8).
                    .withServices("s3", "sqs", "sns");

    /**
     * Shared, singleton PostgreSQL&nbsp;16 container backing the full Spring context &mdash; the
     * relational replacement for the legacy z/OS VSAM&nbsp;KSDS data layer.
     *
     * <p>The AWS integration tests do not query the database directly, but {@code @SpringBootTest} loads
     * the FULL application context: the Spring Data JPA repositories and the Flyway migrations
     * ({@code V1__create_schema} &rarr; {@code V2__create_indexes} &rarr; {@code V3__seed_data}) run on
     * startup and require a live datasource, so the context cannot start without this container. Started
     * once in the {@code static} initializer and never explicitly stopped (reaped by Ryuk at JVM exit),
     * keeping its JDBC URL stable for the whole suite. The database/user/password are all
     * {@code carddemo}, mirroring production / docker-compose. The {@code @SuppressWarnings("resource")}
     * carries the same justification as {@link #LOCALSTACK}.</p>
     */
    @SuppressWarnings("resource") // Singleton container intentionally never closed; reaped by Ryuk at JVM exit.
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo");

    static {
        // Manual, one-time start of the singletons (NOT @Container-managed; see the class Javadoc).
        // start() blocks until each container is ready, so the mapped endpoints/ports below are
        // resolvable by the time @DynamicPropertySource suppliers and the client factories run.
        LOCALSTACK.start();
        POSTGRES.start();
    }

    // -------------------------------------------------------------------------
    // Dynamic property wiring (highest precedence; overrides application-test.yml).
    // -------------------------------------------------------------------------

    /**
     * Registers the singleton containers' coordinates as Spring properties so the real, auto-configured
     * Spring beans point at the containers rather than at any live or default endpoint.
     *
     * <p>This method must be {@code static} even though the class is {@code @TestInstance(PER_CLASS)}:
     * {@code @DynamicPropertySource} is always invoked statically, before any test instance exists, so
     * the properties are available while the {@code ApplicationContext} is being built. Suppliers are
     * passed as lambdas / method references so each value is resolved <em>lazily</em>, after the
     * containers have started.</p>
     *
     * <p>Three groups of properties are registered:</p>
     * <ol>
     *   <li><strong>AWS &rarr; LocalStack</strong> (Spring Cloud AWS&nbsp;3.3.0 global overrides applied
     *       to the auto-configured S3/SQS/SNS clients). {@code s3.path-style-access-enabled=true} is
     *       REQUIRED for LocalStack S3 &mdash; virtual-host-style URLs ({@code http://bucket.host/...})
     *       do not resolve against the emulator, only path-style ({@code http://host:port/bucket/key})
     *       does (documented pitfall, tech-spec L981).</li>
     *   <li><strong>Datasource &rarr; PostgreSQL container.</strong> All four datasource properties are
     *       overridden &mdash; including {@code driver-class-name} &mdash; because the {@code test}
     *       profile ({@code application-test.yml}) selects the Testcontainers JDBC URL scheme
     *       ({@code jdbc:tc:postgresql:16-alpine:///carddemo} paired with
     *       {@code org.testcontainers.jdbc.ContainerDatabaseDriver}). Since this registrar overrides the
     *       URL to this container's plain {@code jdbc:postgresql://...} value, the driver must also be
     *       overridden to {@code org.postgresql.Driver} to avoid a URL/driver mismatch. Registering all
     *       four makes this base robust regardless of which datasource strategy the YAML happens to
     *       choose.</li>
     *   <li><strong>Defensive context-startup guards.</strong> {@code spring.batch.job.enabled=false}
     *       prevents any Spring Batch {@code Job} bean from auto-executing on context startup (these are
     *       AWS tests, not batch tests), and {@code management.tracing.sampling.probability=0.0} keeps
     *       trace export disabled so no external collector is needed.</li>
     * </ol>
     *
     * @param registry the Spring-provided registry into which the properties are added; never {@code null}.
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // --- AWS -> LocalStack (Spring Cloud AWS global overrides) ---------------------------------
        registry.add("spring.cloud.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
        // REQUIRED for LocalStack S3 (path-style addressing) — see method Javadoc / tech-spec L981.
        registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");

        // --- Datasource -> Testcontainer PostgreSQL (VSAM KSDS replacement) -------------------------
        // Override the application-test.yml jdbc:tc: defaults with this singleton container's real
        // coordinates; the driver MUST also be overridden (see method Javadoc) to avoid a URL/driver
        // mismatch with the profile's ContainerDatabaseDriver.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // --- Defensive context-startup guards -------------------------------------------------------
        // Do NOT auto-run Spring Batch jobs during AWS tests (belt-and-suspenders; also set in yml).
        registry.add("spring.batch.job.enabled", () -> "false");
        // Keep trace export disabled so no Jaeger/OTLP collector is required for a test run.
        registry.add("management.tracing.sampling.probability", () -> "0.0");
    }

    // -------------------------------------------------------------------------
    // Autowired production AWS resource-name holder (names resolved from config,
    // NEVER hardcoded as string literals — AAP §0.7.2).
    // -------------------------------------------------------------------------

    /**
     * The production AWS resource-name holder, bound from {@code carddemo.aws.*} in
     * {@code application-test.yml} (via {@code @ConfigurationProperties} on
     * {@link AwsConfig.AwsResourceProperties}). Subclasses obtain the exact bucket/queue/topic names
     * from this bean rather than from magic strings, so the tests and the production beans resolve the
     * same resources:
     * <ul>
     *   <li>{@code awsResourceProperties.getS3().getBatchInputBucket()} &rarr; {@code carddemo-batch-input}</li>
     *   <li>{@code awsResourceProperties.getS3().getBatchOutputBucket()} &rarr; {@code carddemo-batch-output}</li>
     *   <li>{@code awsResourceProperties.getS3().getStatementsBucket()} &rarr; {@code carddemo-statements}</li>
     *   <li>{@code awsResourceProperties.getSqs().getReportJobsQueue()} &rarr; {@code carddemo-report-jobs.fifo}</li>
     *   <li>{@code awsResourceProperties.getSns().getNotificationsTopic()} &rarr; {@code carddemo-notifications}</li>
     * </ul>
     */
    @Autowired
    protected AwsConfig.AwsResourceProperties awsResourceProperties;

    // -------------------------------------------------------------------------
    // Test-side verification client factories.
    //
    // These build SYNCHRONOUS AWS SDK v2 clients pointed at the LocalStack
    // endpoint and are deliberately INDEPENDENT of the production Spring Cloud
    // AWS beans. Spring Cloud AWS 3.x auto-configures S3Client + SnsClient (sync)
    // but SqsAsyncClient (async); providing our own sync clients here keeps
    // verification simple and decoupled from which beans exist, cleanly
    // separating "exercise production code" from "verify the result". Each call
    // returns a FRESH client; the AWS SDK clients are AutoCloseable, so callers
    // should close them (e.g. try-with-resources) once verification is done.
    // -------------------------------------------------------------------------

    /**
     * Builds a fresh, synchronous {@link S3Client} pointed at the LocalStack S3 surface for test-side
     * verification (e.g. asserting that a batch job produced the expected S3 object).
     *
     * <p>Path-style addressing is forced on ({@code forcePathStyle(true)}) because LocalStack S3 only
     * resolves path-style URLs (see {@link #registerProperties(DynamicPropertyRegistry)} / tech-spec
     * L981). Credentials are LocalStack's dummy {@code test}/{@code test} keys supplied by the container
     * &mdash; no live AWS credentials are ever used.</p>
     *
     * @return a new {@link S3Client} targeting LocalStack (caller owns closing it); never {@code null}.
     */
    protected static S3Client newS3Client() {
        return S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                // Path-style is REQUIRED for LocalStack S3 (http://host:port/bucket/key); virtual-host
                // style (http://bucket.host/...) does not resolve against the emulator.
                .forcePathStyle(true)
                .build();
    }

    /**
     * Builds a fresh, synchronous {@link SqsClient} pointed at the LocalStack SQS surface for test-side
     * verification of the CICS&nbsp;TDQ&nbsp;&rarr;&nbsp;SQS&nbsp;FIFO migration (e.g. creating the
     * {@code carddemo-report-jobs.fifo} queue and asserting message receipt).
     *
     * <p>Credentials are LocalStack's dummy {@code test}/{@code test} keys supplied by the container
     * &mdash; no live AWS credentials are ever used.</p>
     *
     * @return a new {@link SqsClient} targeting LocalStack (caller owns closing it); never {@code null}.
     */
    protected static SqsClient newSqsClient() {
        return SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    /**
     * Builds a fresh, synchronous {@link SnsClient} pointed at the LocalStack SNS surface for test-side
     * verification of the CICS-notification&nbsp;&rarr;&nbsp;SNS migration (e.g. creating the
     * {@code carddemo-notifications} topic and asserting fan-out).
     *
     * <p>Credentials are LocalStack's dummy {@code test}/{@code test} keys supplied by the container
     * &mdash; no live AWS credentials are ever used.</p>
     *
     * @return a new {@link SnsClient} targeting LocalStack (caller owns closing it); never {@code null}.
     */
    protected static SnsClient newSnsClient() {
        return SnsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }
}
