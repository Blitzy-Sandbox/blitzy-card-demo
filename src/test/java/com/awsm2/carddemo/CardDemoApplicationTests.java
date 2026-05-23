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
package com.awsm2.carddemo;

import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sfn.SfnClient;

/**
 * Foundational Spring Boot context-load smoke test for the entire CardDemo
 * application.
 *
 * <p>This is THE canonical {@code @SpringBootTest} smoke test for the COBOL
 * &rarr; Java / AWS migration target. It validates that the
 * {@link org.springframework.boot.autoconfigure.SpringBootApplication
 * &#64;SpringBootApplication} component scan rooted at
 * {@code com.awsm2.carddemo} succeeds end-to-end and that <em>every</em>
 * application-defined bean wires correctly into a single Spring
 * {@code ApplicationContext}: {@code @Configuration} classes
 * ({@link com.awsm2.carddemo.config.SecurityConfig SecurityConfig},
 * {@link com.awsm2.carddemo.config.JpaConfig JpaConfig},
 * {@link com.awsm2.carddemo.config.KafkaConfig KafkaConfig},
 * {@link com.awsm2.carddemo.config.RedisConfig RedisConfig},
 * {@link com.awsm2.carddemo.config.BatchConfig BatchConfig},
 * {@link com.awsm2.carddemo.config.OpenSearchConfig OpenSearchConfig},
 * {@link com.awsm2.carddemo.config.CloudWatchConfig CloudWatchConfig},
 * {@link com.awsm2.carddemo.config.SecretsManagerConfig SecretsManagerConfig},
 * {@link com.awsm2.carddemo.config.AwsSdkConfig AwsSdkConfig}, and
 * {@link com.awsm2.carddemo.config.OpenApiConfig OpenApiConfig}); every
 * {@code @Service}, {@code @RestController}, {@code @Repository},
 * {@code @Component}, {@code @RestControllerAdvice}, and {@code @KafkaListener}
 * across the 13 sibling subpackages of {@link CardDemoApplication}.</p>
 *
 * <h2>What this test asserts</h2>
 * <ul>
 *   <li><b>Component scan integrity</b> &mdash; failure here indicates a
 *       missing {@code @Component} / {@code @Service} / etc., a misplaced bean
 *       outside {@code com.awsm2.carddemo}, or a circular bean dependency.</li>
 *   <li><b>Configuration class validity</b> &mdash; failure here indicates a
 *       {@code @Bean} method that throws at construction time, an
 *       {@code @ConfigurationProperties} binding failure, or an
 *       {@code @Autowired} dependency that cannot be satisfied.</li>
 *   <li><b>Auto-configuration compatibility</b> &mdash; failure here indicates
 *       a Spring Boot auto-config class that conflicts with our explicit
 *       configuration, or a property in {@code application-test.yml} that
 *       does not bind to any configuration class.</li>
 *   <li><b>JPA mapping validity</b> &mdash; Hibernate {@code ddl-auto=validate}
 *       is active in the {@code test} profile; failure here indicates a
 *       {@code @Entity} field whose JPA mapping does not match the Flyway
 *       migration schema under {@code src/main/resources/db/migration/V*.sql}.</li>
 *   <li><b>Flyway migration success</b> &mdash; the Testcontainers PostgreSQL
 *       16 instance is empty at startup; Flyway must apply every
 *       {@code V*__*.sql} migration cleanly before Hibernate validates the
 *       schema (AAP &sect;0.6.2).</li>
 * </ul>
 *
 * <h2>Strategy &mdash; Testcontainers PostgreSQL + &#64;MockBean for AWS SDK</h2>
 * <p>This test follows the canonical AAP &sect;0.7.2 testing approach for
 * Spring Boot 3.x context smoke tests:</p>
 * <ol>
 *   <li>A real, ephemeral <strong>PostgreSQL 16-alpine</strong> container is
 *       provisioned via Testcontainers and bound to Spring Boot's
 *       {@code DataSource} through {@link ServiceConnection &#64;ServiceConnection}
 *       (Spring Boot 3.1+). This eliminates the need for a manual
 *       {@code @DynamicPropertySource} callback and exercises the production
 *       JPA / Flyway code paths against a real PostgreSQL instance &mdash;
 *       catching schema-mapping bugs at the smoke-test level.</li>
 *   <li>All AWS SDK v2 clients ({@link S3Client}, {@link SfnClient},
 *       {@link SecretsManagerClient}, {@link CloudWatchClient},
 *       {@link GlueClient}, {@link OpenSearchClient}) are replaced with
 *       Mockito mocks via {@link MockBean &#64;MockBean} so that real AWS
 *       credential lookups, IMDS round-trips, and STS calls never occur
 *       during context bootstrap. This keeps the smoke test fast
 *       (&lt; 60 s typical) and runnable in any CI environment without an
 *       AWS account (AAP &sect;0.7.1).</li>
 *   <li>The {@link KafkaTemplate} and {@link RedisTemplate} infrastructure
 *       templates are similarly replaced with mocks so that the smoke test
 *       does not require a running MSK broker or ElastiCache Redis instance.
 *       The full {@link com.awsm2.carddemo.config.KafkaConfig KafkaConfig}
 *       and {@link com.awsm2.carddemo.config.RedisConfig RedisConfig} are
 *       still loaded &mdash; only the runtime client templates are stubbed
 *       out.</li>
 *   <li>The {@code test} Spring profile (selected by
 *       {@link ActiveProfiles &#64;ActiveProfiles("test")}) loads
 *       {@code src/test/resources/application-test.yml}, which disables
 *       {@code spring-cloud-aws} Secrets Manager / Parameter Store
 *       auto-configuration and points all AWS endpoint URLs at LocalStack
 *       defaults. No real AWS endpoints are ever contacted.</li>
 * </ol>
 *
 * <h2>Provenance &mdash; what this test replaces</h2>
 * <p>This test has no direct COBOL counterpart. It replaces operationally the
 * mainframe build-time {@code LKED} (linkage editor) / {@code BIND} pass that
 * verified all CICS programs and shared copybooks linked into a runnable load
 * module before deployment, plus the {@code CEMT INQ PROG} / {@code CEMT
 * SET PROG NEWCOPY} smoke checks performed on the live CICS region after
 * deployment. In the Spring Boot target the analogous gate is whether the
 * application context can be assembled and refreshed without error.</p>
 *
 * <h2>CI integration</h2>
 * <p>Maven Surefire picks this test up via the {@code *Tests.java} include
 * pattern declared in {@code pom.xml}. The CI pipeline (
 * {@code .github/workflows/build.yml}) runs {@code mvn clean verify} on every
 * push and pull request; failure of this single smoke test fails the entire
 * build and blocks the merge. The test runs FIRST in the suite by ordering
 * convention &mdash; if context loading is broken, no other test can be
 * trusted (AAP &sect;0.7.2).</p>
 *
 * <h2>Operational constraints honored by this test</h2>
 * <ul>
 *   <li>No hardcoded credentials, ARNs, or endpoint URLs (AAP &sect;0.7.1)
 *       &mdash; all configuration comes from {@code application-test.yml}.</li>
 *   <li>No inline AWS SDK calls (AAP &sect;0.7.1) &mdash; AWS clients are
 *       only referenced as {@code @MockBean} field types so the Mockito
 *       proxies replace the real beans.</li>
 *   <li>Jakarta EE only &mdash; no {@code javax.*} imports anywhere in this
 *       file (AAP &sect;0.5.1).</li>
 *   <li>AWS SDK v2 only &mdash; no {@code com.amazonaws.*} imports anywhere
 *       in this file (AAP &sect;0.5.1).</li>
 *   <li>JUnit Jupiter (JUnit 5) only &mdash; no JUnit 4 / Vintage imports
 *       (AAP &sect;0.7.2).</li>
 *   <li>Package-private class and test method &mdash; JUnit 5 best practice
 *       (no {@code public} keyword required).</li>
 *   <li>PostgreSQL container image version pinned to {@code postgres:16-alpine}
 *       &mdash; no {@code latest} tags (AAP &sect;0.5.1 / &sect;0.7.2).</li>
 * </ul>
 *
 * @see CardDemoApplication
 * @see <a href="https://docs.spring.io/spring-boot/docs/3.3.x/reference/html/features.html#features.testing.spring-boot-applications">
 *      Spring Boot Reference &mdash; Testing Spring Boot Applications</a>
 * @see <a href="https://docs.spring.io/spring-boot/docs/3.3.x/reference/html/features.html#features.testing.testcontainers.service-connections">
 *      Spring Boot Reference &mdash; Service Connections with Testcontainers</a>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CardDemoApplicationTests {

    // -------------------------------------------------------------------------
    // Testcontainers — real PostgreSQL 16 for realistic JPA / Flyway validation
    // -------------------------------------------------------------------------
    // The container is declared `static` so it is started exactly once per test
    // class lifecycle (managed by @Testcontainers) rather than per test method.
    // @ServiceConnection auto-configures the following Spring properties from
    // the running container, eliminating the need for a manual
    // @DynamicPropertySource callback:
    //   * spring.datasource.url        (jdbc:postgresql://<host>:<port>/<db>)
    //   * spring.datasource.username   (container start-up credential)
    //   * spring.datasource.password   (container start-up credential)
    //   * spring.datasource.driver-class-name  (org.postgresql.Driver)
    //
    // Replaces (operationally): VSAM cluster definitions (IDCAMS DEFINE
    // CLUSTER) per AAP §0.6.2 — the same Flyway migrations that build the
    // production RDS schema build the test schema here.
    /**
     * Ephemeral PostgreSQL 16-alpine Docker container backing the JPA /
     * Flyway integration for this test class.
     *
     * <p>The image tag is pinned to {@code postgres:16-alpine} per AAP
     * &sect;0.5.1 (no {@code latest} tags). The container starts before any
     * test method runs and stops after the last test method completes
     * (managed by {@link Testcontainers &#64;Testcontainers}).</p>
     *
     * <p>The {@link ServiceConnection &#64;ServiceConnection} annotation
     * (Spring Boot 3.1+) automatically binds Spring Boot's
     * {@code DataSource} to this container at context-refresh time,
     * removing the need for a manual {@code @DynamicPropertySource} callback
     * that would otherwise be required to push the container's randomly
     * mapped JDBC URL into the Spring {@code Environment}.</p>
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    // -------------------------------------------------------------------------
    // AWS SDK v2 client mocks — prevent real AWS credential lookups
    // -------------------------------------------------------------------------
    // Every AWS SDK v2 client declared by `AwsSdkConfig` (and used by the
    // adapter classes under com.awsm2.carddemo.adapter) is replaced with a
    // Mockito mock here. Without these mocks, context bootstrap would attempt
    // to resolve real AWS credentials via the SDK credential provider chain
    // (env vars → ~/.aws/credentials → IMDS) and fail in a CI environment
    // that has none, breaking the smoke test.
    //
    // Note: The production CloudWatch metrics integration uses
    // CloudWatchAsyncClient (registered by AwsSdkConfig). The synchronous
    // CloudWatchClient mock declared below is supplied for completeness of
    // the AWS SDK v2 service surface required by the file schema's external
    // imports; the Spring context will register both as @MockBean-supplied
    // singletons. No production code currently injects CloudWatchClient,
    // so this mock simply sits in the context unused — providing forward
    // compatibility for future direct CloudWatchClient usage without
    // breaking this smoke test.
    /**
     * Mocked {@link S3Client}. Replaces the real Amazon S3 client registered
     * by {@link com.awsm2.carddemo.config.AwsSdkConfig AwsSdkConfig} so that
     * {@link com.awsm2.carddemo.adapter.S3OutputService S3OutputService} can
     * be instantiated during context refresh without contacting AWS.
     */
    @MockBean
    private S3Client s3Client;

    /**
     * Mocked {@link SfnClient}. Replaces the real AWS Step Functions client
     * registered by {@link com.awsm2.carddemo.config.AwsSdkConfig
     * AwsSdkConfig} so that
     * {@link com.awsm2.carddemo.adapter.StepFunctionsOrchestrator
     * StepFunctionsOrchestrator} can be instantiated during context refresh
     * without contacting AWS.
     */
    @MockBean
    private SfnClient sfnClient;

    /**
     * Mocked {@link SecretsManagerClient}. Replaces the real AWS Secrets
     * Manager client registered by
     * {@link com.awsm2.carddemo.config.AwsSdkConfig AwsSdkConfig} so that
     * {@link com.awsm2.carddemo.adapter.SecretsManagerService
     * SecretsManagerService} can be instantiated during context refresh
     * without contacting AWS (per AAP &sect;0.7.1, the {@code test} profile
     * must NOT require real Secrets Manager access).
     */
    @MockBean
    private SecretsManagerClient secretsManagerClient;

    /**
     * Mocked {@link CloudWatchClient}. Reserved for forward compatibility
     * with future synchronous CloudWatch client usage (AAP &sect;0.6.6).
     * The current production code uses
     * {@code software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient}
     * via Micrometer; this synchronous mock satisfies the file schema's
     * external import surface without affecting Micrometer wiring.
     */
    @MockBean
    private CloudWatchClient cloudWatchClient;

    /**
     * Mocked {@link GlueClient}. Replaces the real AWS Glue client used by
     * {@link com.awsm2.carddemo.glue.GlueETLConfig GlueETLConfig} so that
     * Glue job submission code paths do not contact AWS during context
     * refresh (AAP &sect;0.4.1).
     */
    @MockBean
    private GlueClient glueClient;

    /**
     * Mocked {@link OpenSearchClient}. Replaces the real OpenSearch typed
     * client registered by
     * {@link com.awsm2.carddemo.config.OpenSearchConfig OpenSearchConfig}
     * (bean name {@code openSearchTypedClient}) so that
     * {@link com.awsm2.carddemo.adapter.OpenSearchIndexer OpenSearchIndexer}
     * can be instantiated during context refresh without contacting an
     * OpenSearch domain (AAP &sect;0.6.6).
     */
    @MockBean
    private OpenSearchClient openSearchClient;

    // -------------------------------------------------------------------------
    // Spring infrastructure templates — mocked to avoid real broker / cache
    // -------------------------------------------------------------------------
    // KafkaTemplate (registered by KafkaConfig) and RedisTemplate
    // (registered by RedisConfig) require connectivity to MSK and
    // ElastiCache respectively at runtime. They are mocked here so that
    // the context bootstrap does not stall waiting on a Kafka broker or
    // Redis server that is intentionally not provisioned for this smoke
    // test. Tests that actually exercise Kafka or Redis behaviour use
    // dedicated KafkaContainer / GenericContainer("redis") fixtures in
    // their own test classes (see AAP §0.7.2 testing approach).
    /**
     * Mocked {@link KafkaTemplate}. Replaces the real Kafka producer template
     * registered by {@link com.awsm2.carddemo.config.KafkaConfig KafkaConfig}
     * so that
     * {@link com.awsm2.carddemo.adapter.KafkaEventPublisher KafkaEventPublisher}
     * can be instantiated during context refresh without contacting an MSK
     * broker (AAP &sect;0.6.5).
     */
    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Mocked {@link RedisTemplate}. Replaces the real Redis template
     * registered by {@link com.awsm2.carddemo.config.RedisConfig RedisConfig}
     * so that {@link com.awsm2.carddemo.adapter.CacheService CacheService}
     * can be instantiated during context refresh without contacting an
     * ElastiCache Redis instance (AAP &sect;0.7.1 cache-aside requirement).
     */
    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    // -------------------------------------------------------------------------
    // contextLoads() — the single smoke-test assertion
    // -------------------------------------------------------------------------
    // Per Spring Boot testing convention, this method has an intentionally
    // empty body: the entire Spring ApplicationContext bootstrap IS the
    // assertion. If the context loads successfully, JUnit reports the test
    // as passing; if the context fails to load (BeanCreationException,
    // NoSuchBeanDefinitionException, UnsatisfiedDependencyException, Flyway
    // migration failure, Hibernate schema validation failure, etc.), the
    // test fails with the underlying exception captured in the failure log.
    //
    // Replaces (operationally): the COBOL LKED (linkage editor) post-build
    // gate that verified all CICS programs and copybooks linked into a
    // runnable load module before deployment. In the Spring Boot target the
    // analogous gate is whether the Spring ApplicationContext can be
    // assembled and refreshed without error.
    /**
     * Asserts that the entire Spring {@code ApplicationContext} loads
     * successfully with all beans wired.
     *
     * <p>Failure of this test indicates a bean-wiring, configuration, or
     * auto-configuration problem at the Spring framework level that MUST be
     * fixed before any other test in the suite can be trusted. The empty
     * method body is deliberate &mdash; the {@code @SpringBootTest}
     * framework performs context refresh before this method is invoked, so
     * by the time JUnit reaches this line the context is already known to
     * have loaded; the test simply records a success.</p>
     */
    @Test
    void contextLoads() {
        // Intentionally empty: the test IS context-load success.
    }
}
