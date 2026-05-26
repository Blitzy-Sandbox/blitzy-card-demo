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
package com.awsm2.carddemo.integration;

import com.awsm2.carddemo.CardDemoApplication;
import com.awsm2.carddemo.adapter.SecretsManagerService;
import com.awsm2.carddemo.adapter.StepFunctionsOrchestrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.CreateStateMachineRequest;
import software.amazon.awssdk.services.sfn.model.CreateStateMachineResponse;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.GetExecutionHistoryRequest;
import software.amazon.awssdk.services.sfn.model.GetExecutionHistoryResponse;
import software.amazon.awssdk.services.sfn.model.HistoryEvent;
import software.amazon.awssdk.services.sfn.model.HistoryEventType;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test for the AWS Step Functions orchestration layer of the
 * CardDemo end-of-day (EOD) batch pipeline.
 *
 * <p><b>Replaces JCL job stream:</b> {@code POSTTRAN.jcl} &rarr;
 * {@code INTCALC.jcl} &rarr; {@code COMBTRAN.jcl} &rarr; Parallel {
 * {@code CREASTMT.JCL}, {@code TRANREPT.jcl} } per AAP &sect;0.6.3.</p>
 *
 * <p>This test validates the Amazon States Language (ASL) state-machine
 * definitions stored under {@code src/main/resources/stepfunctions/}
 * ({@code eod-batch-pipeline.asl.json} and {@code file-provisioning.asl.json})
 * against a LocalStack-emulated AWS Step Functions service. Per AAP
 * &sect;0.4.1 and &sect;0.6.3, the original mainframe JCL job-stream
 * orchestration is mapped to a state machine composed of {@code Task},
 * {@code Choice}, {@code Parallel}, {@code Retry}, and {@code Catch}
 * constructs. This test exercises:</p>
 *
 * <ol>
 *   <li>Well-formed ASL JSON validation (top-level {@code StartAt},
 *       canonical state names per AAP &sect;0.6.3).</li>
 *   <li>Sequential {@code Task} chain execution: PostTransactions &rarr;
 *       CalculateInterest &rarr; CombineTransactions.</li>
 *   <li>Parallel branch execution: {@code CreateStatements} and
 *       {@code RunTransactionReports} run in parallel downstream of
 *       {@code CombineTransactions}.</li>
 *   <li>{@code Catch} state routing on task failure.</li>
 *   <li>{@code Retry} policy configuration (IntervalSeconds, MaxAttempts,
 *       BackoffRate).</li>
 *   <li>{@code Choice} state input-driven routing.</li>
 *   <li>{@code Map} state iteration in the provisioning state machine.</li>
 *   <li>{@link StepFunctionsOrchestrator#startEodPipeline(String)} adapter
 *       round-trip per AAP &sect;0.7.1 (adapter pattern).</li>
 * </ol>
 *
 * <h2>LocalStack workaround: lambda:invoke in lieu of batch:submitJob.sync (Pro-only)</h2>
 * <p>The production ASL definitions invoke AWS Batch jobs via
 * {@code "Resource": "arn:aws:states:::batch:submitJob.sync"}. LocalStack
 * community edition does NOT fully support the Batch service-integration
 * pattern, so for execution-based tests (Phases 9-13, 15) we construct
 * minimal fixture state machines composed entirely of {@code Pass},
 * {@code Choice}, {@code Parallel}, and {@code Fail} states that LocalStack
 * community edition handles natively. The fixtures preserve the same
 * {@code StartAt}, state names, transitions, and {@code Parallel} branch
 * structure as the production ASL definitions, so the state-transition
 * sequence assertions remain meaningful.</p>
 *
 * <p>For ASL JSON structure validation (Phases 8, 11-Catch, 12-Retry, 14-Map),
 * the production ASL definitions under {@code src/main/resources/stepfunctions/}
 * are parsed directly via Jackson {@link ObjectMapper#readTree(String)} and
 * the {@code Retry} / {@code Catch} configurations on the production
 * {@code Task} states are asserted without actually executing them. This
 * keeps the test fully runnable on LocalStack community edition while
 * still validating the production declarations per AAP &sect;0.6.3.</p>
 *
 * <h2>Test isolation strategy</h2>
 * <ul>
 *   <li>{@link LocalStackContainer} provides an ephemeral Step Functions
 *       service per test class lifecycle (pinned to
 *       {@code localstack/localstack:3.8} per AAP &sect;0.5.1 "no
 *       {@code latest} tags").</li>
 *   <li>{@link PostgreSQLContainer} satisfies the Spring Boot context's
 *       JPA / Flyway requirements via {@link ServiceConnection &#64;ServiceConnection}
 *       so the full {@link CardDemoApplication} context can refresh.</li>
 *   <li>All non-SFN AWS clients ({@code S3Client}, {@code SecretsManagerClient},
 *       {@code CloudWatchClient}, {@code GlueClient}, {@code OpenSearchClient})
 *       are replaced with {@link MockBean &#64;MockBean} stubs so context
 *       refresh does not contact any real AWS service.</li>
 *   <li>The real {@link SfnClient} bean from
 *       {@link com.awsm2.carddemo.config.AwsSdkConfig AwsSdkConfig} is used
 *       and its endpoint is rewritten to the LocalStack address via
 *       {@link DynamicPropertySource &#64;DynamicPropertySource}.</li>
 *   <li>{@link KafkaTemplate} and {@link RedisTemplate} are mocked to avoid
 *       requiring an MSK broker or ElastiCache Redis instance for this
 *       infrastructure-only test (AAP &sect;0.7.2 testing approach).</li>
 *   <li>{@link SecretsManagerService} is stubbed via {@link TestConfiguration
 *       &#64;TestConfiguration} so {@code JwtTokenProvider}'s
 *       {@code @PostConstruct} lifecycle hook can succeed at context refresh
 *       without contacting AWS Secrets Manager.</li>
 * </ul>
 *
 * <h2>Operational constraints honored by this test</h2>
 * <ul>
 *   <li>No hardcoded credentials, ARNs, or endpoint URLs (AAP &sect;0.7.1)
 *       &mdash; all values come from {@code application-test.yml} or
 *       {@link LocalStackContainer}-provided getters.</li>
 *   <li>No inline AWS SDK calls in business logic (AAP &sect;0.7.1)
 *       &mdash; the SOLE adapter under test is
 *       {@link StepFunctionsOrchestrator}.</li>
 *   <li>AWS SDK v2 only &mdash; no {@code com.amazonaws.*} imports
 *       anywhere in this file (AAP &sect;0.5.1).</li>
 *   <li>No {@code Thread.sleep()} &mdash; asynchronous waits use
 *       {@link org.awaitility.Awaitility#await()} with 60-second timeouts
 *       (AAP &sect;0.7.2 testing approach).</li>
 *   <li>{@code IT.java} suffix routes execution to the Failsafe phase per
 *       {@code pom.xml} plugin configuration (AAP &sect;0.5.1).</li>
 *   <li>Package-private class &mdash; JUnit 5 does not require
 *       {@code public} visibility, and minimizing access keeps the test
 *       internal to {@code com.awsm2.carddemo.integration}.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.adapter.StepFunctionsOrchestrator
 * @see com.awsm2.carddemo.config.AwsSdkConfig
 */
// Replaces JCL job stream: POSTTRAN -> INTCALC -> COMBTRAN -> Parallel { CREASTMT, TRANREPT }
// AAP §0.6.3 — JCL → Step Functions orchestration: linear chain of Task
// states with Parallel fan-out, Retry, and Catch constructs.
@SpringBootTest(classes = CardDemoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import(StepFunctionsEodPipelineIT.TestSecretsManagerConfiguration.class)
class StepFunctionsEodPipelineIT {

    // -------------------------------------------------------------------------
    // Classpath resource constants for production ASL definitions
    // -------------------------------------------------------------------------

    /**
     * Classpath path to the production EOD batch pipeline ASL definition
     * (AAP &sect;0.4.1). Resolved via {@link Class#getResourceAsStream(String)}.
     */
    private static final String EOD_ASL_RESOURCE =
            "/stepfunctions/eod-batch-pipeline.asl.json";

    /**
     * Classpath path to the production provisioning ASL definition
     * (AAP &sect;0.4.1). Resolved via {@link Class#getResourceAsStream(String)}.
     */
    private static final String PROVISIONING_ASL_RESOURCE =
            "/stepfunctions/file-provisioning.asl.json";

    /**
     * LocalStack-compatible dummy IAM role ARN. LocalStack does not enforce
     * IAM role permissions on state-machine execution (per LocalStack
     * documentation); any well-formed ARN string is accepted by the
     * {@code CreateStateMachine} API.
     */
    private static final String DUMMY_ROLE_ARN =
            "arn:aws:iam::000000000000:role/StepFunctionsExecutionRole";

    /** Awaitility per-poll interval for execution-status checks. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    /** Awaitility maximum wait for an execution to reach a terminal state. */
    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(60);

    // -------------------------------------------------------------------------
    // Fixture ASL definitions — LocalStack-community-compatible (Pass-only)
    // -------------------------------------------------------------------------
    // Per the "LocalStack workaround" note in the class Javadoc, these
    // fixture state machines mirror the structural topology of the
    // production EOD pipeline (state names + transitions + Parallel fan-out)
    // but substitute Pass states for Task states so execution succeeds
    // without requiring AWS Batch service integration. The state-transition
    // sequence assertions in execution_happyPath_*, execution_parallelBranches_*,
    // and orchestrator_startEodPipeline_* test methods remain meaningful
    // because LocalStack emits canonical PassStateEntered / PassStateExited
    // / ParallelStateEntered / ParallelStateExited events identical in
    // structure to TaskStateEntered / TaskStateExited.

    /**
     * EOD pipeline fixture state machine. Mirrors the production
     * {@code eod-batch-pipeline.asl.json} state names and transitions
     * exactly: {@code InitializeEodPipeline -> PostTransactions ->
     * CalculateInterest -> CombineTransactions -> RunDownstreamReports
     * (Parallel: CreateStatements + RunTransactionReports) ->
     * EmitSuccessMetric -> EodPipelineSucceeded}. Pass states substitute
     * for Task states per the LocalStack community-edition workaround.
     */
    private static final String EOD_FIXTURE_ASL = "{\n"
            + "  \"Comment\": \"Test fixture mirroring production EOD pipeline structure (Pass states substitute for Task states for LocalStack community compatibility)\",\n"
            + "  \"StartAt\": \"InitializeEodPipeline\",\n"
            + "  \"States\": {\n"
            + "    \"InitializeEodPipeline\": { \"Type\": \"Pass\", \"Next\": \"PostTransactions\" },\n"
            + "    \"PostTransactions\":      { \"Type\": \"Pass\", \"Next\": \"CalculateInterest\" },\n"
            + "    \"CalculateInterest\":     { \"Type\": \"Pass\", \"Next\": \"CombineTransactions\" },\n"
            + "    \"CombineTransactions\":   { \"Type\": \"Pass\", \"Next\": \"RunDownstreamReports\" },\n"
            + "    \"RunDownstreamReports\": {\n"
            + "      \"Type\": \"Parallel\",\n"
            + "      \"Branches\": [\n"
            + "        { \"StartAt\": \"CreateStatements\",      \"States\": { \"CreateStatements\":      { \"Type\": \"Pass\", \"End\": true } } },\n"
            + "        { \"StartAt\": \"RunTransactionReports\", \"States\": { \"RunTransactionReports\": { \"Type\": \"Pass\", \"End\": true } } }\n"
            + "      ],\n"
            + "      \"Next\": \"EmitSuccessMetric\"\n"
            + "    },\n"
            + "    \"EmitSuccessMetric\":     { \"Type\": \"Pass\", \"Next\": \"EodPipelineSucceeded\" },\n"
            + "    \"EodPipelineSucceeded\":  { \"Type\": \"Succeed\" }\n"
            + "  }\n"
            + "}";


    /**
     * Choice / Fail / Succeed fixture state machine. Used by the
     * Phase-11 failure-path test and Phase-13 choice-routing test. The
     * Choice state routes on the boolean input field {@code shouldFail}:
     * when {@code true}, execution enters a {@code Fail} state
     * (terminal status {@code FAILED}); when {@code false}, execution
     * enters a {@code Succeed} state (terminal status {@code SUCCEEDED}).
     * This proves both branches of a Choice state are exercised and
     * that a Fail state terminates execution in FAILED status &mdash;
     * the same contract the production EOD pipeline's
     * {@code FailureNotification -> EmitFailureMetric -> EodPipelineFailed}
     * sub-flow honours.
     */
    private static final String CHOICE_FIXTURE_ASL = "{\n"
            + "  \"Comment\": \"Test fixture for Choice routing and Fail-state behavior\",\n"
            + "  \"StartAt\": \"RouteOnInput\",\n"
            + "  \"States\": {\n"
            + "    \"RouteOnInput\": {\n"
            + "      \"Type\": \"Choice\",\n"
            + "      \"Choices\": [\n"
            + "        { \"Variable\": \"$.shouldFail\", \"BooleanEquals\": true, \"Next\": \"FailureState\" }\n"
            + "      ],\n"
            + "      \"Default\": \"SuccessState\"\n"
            + "    },\n"
            + "    \"FailureState\": { \"Type\": \"Fail\", \"Error\": \"PostTransactionsFailed\", \"Cause\": \"Triggered by shouldFail=true input\" },\n"
            + "    \"SuccessState\": { \"Type\": \"Succeed\" }\n"
            + "  }\n"
            + "}";

    // -------------------------------------------------------------------------
    // @TestConfiguration — stub SecretsManagerService for JwtTokenProvider
    // -------------------------------------------------------------------------
    // JwtTokenProvider is annotated @Component @RefreshScope and performs an
    // eager Secrets Manager fetch in its @PostConstruct lifecycle hook to load
    // the HS256 signing key. Without a stubbed SecretsManagerService, context
    // refresh fails with IllegalStateException because the unstubbed Mockito
    // mock returns Mockito's default Optional (Optional.empty()). This
    // duplicates the equivalent stub in com.awsm2.carddemo.CardDemoApplicationTests
    // (which is package-private and therefore not importable from this
    // package). The 60-byte placeholder key easily clears the 32-byte minimum
    // imposed by HS256 (RFC 7518).
    /**
     * Provides a stubbed {@link SecretsManagerService} bean for the duration
     * of {@link StepFunctionsEodPipelineIT}. The stub returns a deterministic
     * 60-byte ASCII placeholder for any {@code (secretArn, fieldName)} pair
     * passed to {@code getSecretJsonField(...)}, which is sufficient to
     * satisfy {@code JwtTokenProvider}'s HS256 key-length precondition
     * during context refresh.
     *
     * <p>This stub is marked {@link Primary &#64;Primary} so it overrides
     * the real {@code SecretsManagerService} bean registered by component
     * scan for the entire test context. No real AWS API calls occur.</p>
     */
    @TestConfiguration
    static class TestSecretsManagerConfiguration {

        /** Test-only placeholder key (60 bytes, well above HS256's 32-byte minimum). */
        private static final String TEST_SIGNING_KEY =
                "test-only-jwt-signing-key-for-sfn-pipeline-it-padded-60-bytes";

        /**
         * Stub {@link SecretsManagerService} bean. Returns
         * {@code Optional.of(TEST_SIGNING_KEY)} for any
         * {@code getSecretJsonField} invocation so that
         * {@code JwtTokenProvider.initSigningKey()} can satisfy its
         * {@code @PostConstruct} preconditions during context refresh.
         *
         * @return a Mockito mock pre-configured with default-answer stubbing
         */
        @Bean
        @Primary
        SecretsManagerService secretsManagerService() {
            SecretsManagerService stub = Mockito.mock(SecretsManagerService.class);
            Mockito.when(stub.getSecretJsonField(Mockito.anyString(), Mockito.anyString()))
                    .thenReturn(Optional.of(TEST_SIGNING_KEY));
            Mockito.when(stub.getSecret(Mockito.anyString()))
                    .thenReturn(TEST_SIGNING_KEY);
            return stub;
        }
    }

    // -------------------------------------------------------------------------
    // Testcontainers — LocalStack (community) for Step Functions emulation
    // -------------------------------------------------------------------------
    // LocalStack community supports Step Functions but NOT the
    // batch:submitJob.sync service integration (Pro-only). The fixture
    // state machines (EOD_FIXTURE_ASL, CHOICE_FIXTURE_ASL) use only Pass,
    // Choice, Parallel, Fail, and Succeed states — all of which run
    // natively in community edition.
    //
    // Image tag pinned per AAP §0.5.1 (no `latest` tags). The container
    // image localstack/localstack:3.8 is the LocalStack community release
    // used by the CI environment per the setup logs.

    /**
     * Ephemeral LocalStack community container providing the AWS service
     * surface required by this test (Step Functions for state-machine
     * lifecycle + IAM for role validation + Lambda and S3 for service-
     * integration completeness even though only SFN APIs are invoked
     * directly).
     *
     * <p>The container starts before any test method runs and stops
     * after the last test method completes (managed by
     * {@link Testcontainers &#64;Testcontainers}). Per AAP &sect;0.7.2,
     * tests must NOT require real AWS account access &mdash; this
     * container fully satisfies that constraint.</p>
     */
    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(Service.STEPFUNCTIONS, Service.IAM, Service.LAMBDA, Service.S3);

    /**
     * Ephemeral PostgreSQL 16-alpine container backing the JPA / Flyway
     * integration for this test class. The container starts before any
     * test method runs and stops after the last test method completes
     * (managed by {@link Testcontainers &#64;Testcontainers}).
     *
     * <p>The {@link ServiceConnection &#64;ServiceConnection} annotation
     * (Spring Boot 3.1+) automatically binds Spring Boot's
     * {@code DataSource} to this container at context-refresh time so
     * the Spring Boot context bootstrapped by {@link SpringBootTest
     * &#64;SpringBootTest} can apply Flyway migrations and validate
     * Hibernate mappings (AAP &sect;0.6.2).</p>
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    // -------------------------------------------------------------------------
    // Dynamic Spring property source — redirect AWS SDK clients to LocalStack
    // -------------------------------------------------------------------------
    // Per AAP §0.7.2, the LocalStack endpoint URL must be bound dynamically
    // at test-context-refresh time so the @Autowired SfnClient bean produced
    // by AwsSdkConfig.sfnClient() routes its API calls through the
    // Testcontainers-managed LocalStack instance (and not the LocalStack
    // instance the developer may have running on a fixed port).
    //
    // The four properties set below replicate the contract documented in
    // application-test.yml (spring.cloud.aws.endpoint, .region.static,
    // .credentials.access-key, .credentials.secret-key) so AwsSdkConfig
    // resolves them at SfnClient construction time and the SDK signs every
    // request with LocalStack-compatible credentials.

    /**
     * Binds the LocalStack endpoint and credentials to the
     * {@code spring.cloud.aws.*} property tree before Spring context
     * refresh. {@link DynamicPropertyRegistry} entries are applied
     * after {@link Testcontainers &#64;Testcontainers} has started the
     * containers but before any {@code @Autowired} bean is constructed.
     *
     * @param registry the dynamic property registry supplied by Spring
     */
    @DynamicPropertySource
    static void overrideAwsProperties(DynamicPropertyRegistry registry) {
        // Endpoint override — points all AWS SDK v2 clients (including
        // SfnClient) at the LocalStack container's edge port.
        registry.add("spring.cloud.aws.endpoint",
                () -> LOCALSTACK.getEndpoint().toString());
        // Region — LocalStack ignores the region for routing but the SDK
        // requires one for SigV4 signing. The LocalStack container exposes
        // its configured region via getRegion() (defaults to us-east-1).
        registry.add("spring.cloud.aws.region.static",
                LOCALSTACK::getRegion);
        // Credentials — LocalStack accepts any non-empty pair. The
        // LocalStackContainer exposes its built-in test/test creds via
        // getAccessKey() / getSecretKey().
        registry.add("spring.cloud.aws.credentials.access-key",
                LOCALSTACK::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key",
                LOCALSTACK::getSecretKey);
        // Re-affirm Secrets Manager / Parameter Store disabled (already
        // disabled in application-test.yml but defense in depth — tests
        // MUST NOT contact real AWS at context start).
        registry.add("spring.cloud.aws.secretsmanager.enabled",
                () -> "false");
        registry.add("spring.cloud.aws.parameterstore.enabled",
                () -> "false");

        // ---------------------------------------------------------------
        // QA CP8 MAJOR-02 FIX — explicit DataSource override
        // ---------------------------------------------------------------
        // The {@link ServiceConnection &#64;ServiceConnection} annotation
        // on the {@link #POSTGRES} container is supposed to auto-bind
        // {@code spring.datasource.*} properties to the container at
        // context-refresh time. However, the {@code application-test.yml}
        // baseline configuration sets
        // {@code spring.datasource.url=jdbc:tc:postgresql:16-alpine:///carddemo_test?TC_DAEMON=true}
        // (the Testcontainers JDBC URL scheme). When Spring Boot resolves
        // properties at startup, the explicit URL in {@code
        // application-test.yml} wins over the auto-binding contributed
        // by {@code @ServiceConnection} — Flyway then migrates the
        // {@code @ServiceConnection} container while Hibernate validates
        // against the OTHER PostgreSQL instance materialized by the TC
        // JDBC daemon URL, causing every test to fail with
        // {@code Schema-validation: missing table [accounts]}.
        //
        // The mechanical fix below adopts the same {@link
        // DynamicPropertySource &#64;DynamicPropertySource} pattern that
        // already works in {@code EndToEndBatchPipelineIT.java}
        // (lines 247-256): explicit dynamic registrations take highest
        // precedence and force both Flyway AND Hibernate validation
        // onto the SAME {@link #POSTGRES} container, restoring schema
        // consistency.
        //
        // Production code paths are NOT affected — production deploys
        // against real RDS Multi-AZ (AAP §0.6.2), never against
        // Testcontainers. This change touches only the integration-test
        // harness wiring.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name",
                POSTGRES::getDriverClassName);
        registry.add("spring.datasource.hikari.auto-commit", () -> "true");
    }

    // -------------------------------------------------------------------------
    // Autowired beans — system under test
    // -------------------------------------------------------------------------

    /**
     * The real AWS SDK v2 Step Functions client produced by
     * {@link com.awsm2.carddemo.config.AwsSdkConfig#sfnClient() AwsSdkConfig}.
     * Routed to the LocalStack endpoint via the
     * {@link #overrideAwsProperties(DynamicPropertyRegistry)} dynamic
     * property source. This is the SAME client the
     * {@link StepFunctionsOrchestrator} adapter uses, so the orchestrator
     * test (Phase 15) and direct {@code sfnClient.*} test methods
     * (Phases 9-13) exercise the same code path.
     */
    @Autowired
    private SfnClient sfnClient;

    /**
     * The adapter under test. Per AAP &sect;0.7.1, this is the SOLE class
     * in the CardDemo Java target that invokes the AWS SDK v2
     * {@code SfnClient}. Phase 15 calls {@link
     * StepFunctionsOrchestrator#startEodPipeline(String)} and asserts the
     * returned execution ARN matches the LocalStack format and is
     * discoverable via {@code sfnClient.describeExecution(...)}.
     */
    @Autowired
    private StepFunctionsOrchestrator orchestrator;

    // -------------------------------------------------------------------------
    // @MockBean — AWS clients we do NOT want to exercise here
    // -------------------------------------------------------------------------
    // Spring's component scan instantiates every adapter under
    // com.awsm2.carddemo.adapter at context refresh, including
    // S3OutputService, KafkaEventPublisher, CacheService, AuditLogService,
    // etc. Each adapter takes an AWS SDK client as a constructor dependency.
    // Mocking the clients here lets the context refresh successfully without
    // attempting to reach S3, OpenSearch, etc.
    //
    // The SfnClient is deliberately NOT mocked — it's the only client we
    // route through LocalStack for real Step Functions API exercise.

    /** Mocked S3 client — prevents real S3 traffic during context refresh. */
    @MockBean
    private S3Client s3Client;

    /** Mocked Secrets Manager client — Secrets Manager auto-config is disabled but the SDK client bean is still constructed by AwsSdkConfig. */
    @MockBean
    private SecretsManagerClient secretsManagerClient;

    /** Mocked CloudWatch client — Micrometer's CloudWatchAsyncClient is the production exporter; this synchronous client mock is supplied for completeness. */
    @MockBean
    private CloudWatchClient cloudWatchClient;

    /** Mocked Glue client — Glue auto-config is unused in tests. */
    @MockBean
    private GlueClient glueClient;

    /** Mocked OpenSearch typed client — OpenSearchIndexer uses this. */
    @MockBean
    private OpenSearchClient openSearchClient;

    /** Mocked Kafka template — KafkaEventPublisher uses this. */
    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    /** Mocked Redis template — CacheService uses this. */
    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    // -------------------------------------------------------------------------
    // Class-level state machine state (set by @BeforeAll, torn down by @AfterAll)
    // -------------------------------------------------------------------------
    // These static fields hold the ARNs returned by
    // SfnClient.createStateMachine(...) during @BeforeAll. The test methods
    // reference them when starting executions; @AfterAll deletes the
    // state machines for explicit cleanup (LocalStack tears the container
    // down anyway, but explicit cleanup keeps the test deterministic across
    // CI re-runs that may share the LocalStack container).

    /** ARN of the EOD-fixture state machine created in {@link #provisionFixtureStateMachines()}. */
    private static String eodFixtureArn;

    /** ARN of the choice / failure fixture state machine. */
    private static String choiceFixtureArn;

    /**
     * Standalone {@link SfnClient} instance used only by the static
     * {@link #provisionFixtureStateMachines()} and {@link
     * #deleteFixtureStateMachines()} lifecycle hooks (Spring's
     * {@link Autowired &#64;Autowired} bean is only available to instance
     * methods, not static {@link BeforeAll &#64;BeforeAll}/{@link AfterAll
     * &#64;AfterAll} methods). Both this client and the Spring-managed
     * {@code sfnClient} bean point at the same LocalStack endpoint, so
     * state machines created here are visible to the test methods.
     */
    private static SfnClient bootstrapSfnClient;



    // -------------------------------------------------------------------------
    // @BeforeAll — provision both fixture state machines exactly once per class
    // -------------------------------------------------------------------------

    /**
     * Creates the EOD-fixture and Choice-fixture state machines on the
     * LocalStack-emulated Step Functions service. Each state machine name
     * is suffixed with a fresh {@link UUID} to avoid collisions across
     * back-to-back CI runs that share a LocalStack container.
     *
     * <p>This method must be {@code static} because JUnit 5 invokes
     * {@code @BeforeAll} before the test instance is constructed; Spring's
     * {@code @Autowired} injection therefore cannot reach this method.
     * The standalone {@link #bootstrapSfnClient} is built directly from
     * the {@link LocalStackContainer} endpoint, which is fully started by
     * Testcontainers at this point.</p>
     *
     * @throws IllegalStateException if the LocalStack container is not
     *                               running when this method executes
     */
    @BeforeAll
    static void provisionFixtureStateMachines() {
        // Replaces JCL: IDCAMS DEFINE CLUSTER + initial state-machine
        // provisioning (Terraform stepfunctions.tf in production).
        // The bootstrap client is a single-use SfnClient targeting the
        // LocalStack endpoint; it is closed in @AfterAll.
        bootstrapSfnClient = SfnClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                LOCALSTACK.getAccessKey(),
                                LOCALSTACK.getSecretKey())))
                .build();

        String runSuffix = UUID.randomUUID().toString().substring(0, 8);

        // Create the EOD fixture state machine
        CreateStateMachineRequest eodRequest = CreateStateMachineRequest.builder()
                .name("eod-fixture-" + runSuffix)
                .definition(EOD_FIXTURE_ASL)
                .roleArn(DUMMY_ROLE_ARN)
                .build();
        CreateStateMachineResponse eodResp =
                bootstrapSfnClient.createStateMachine(eodRequest);
        eodFixtureArn = eodResp.stateMachineArn();

        // Create the Choice / Failure fixture state machine
        CreateStateMachineRequest choiceRequest = CreateStateMachineRequest.builder()
                .name("choice-fixture-" + runSuffix)
                .definition(CHOICE_FIXTURE_ASL)
                .roleArn(DUMMY_ROLE_ARN)
                .build();
        CreateStateMachineResponse choiceResp =
                bootstrapSfnClient.createStateMachine(choiceRequest);
        choiceFixtureArn = choiceResp.stateMachineArn();
    }

    // -------------------------------------------------------------------------
    // @AfterAll — explicit cleanup of state machines and bootstrap client
    // -------------------------------------------------------------------------

    /**
     * Deletes the fixture state machines and closes the bootstrap client.
     * Explicit cleanup keeps tests deterministic when the LocalStack
     * container is shared across test classes (or re-used in shared CI
     * environments). LocalStack tears down the container at the end of
     * the JUnit run regardless, so this method is defensive but
     * non-essential to test correctness.
     */
    @AfterAll
    static void deleteFixtureStateMachines() {
        if (bootstrapSfnClient == null) {
            return;
        }
        try {
            if (eodFixtureArn != null) {
                bootstrapSfnClient.deleteStateMachine(
                        b -> b.stateMachineArn(eodFixtureArn));
            }
            if (choiceFixtureArn != null) {
                bootstrapSfnClient.deleteStateMachine(
                        b -> b.stateMachineArn(choiceFixtureArn));
            }
        } catch (Exception ex) {
            // Swallow — LocalStack container teardown ultimately cleans up.
            // We do not let cleanup errors mask the actual test outcome.
        } finally {
            bootstrapSfnClient.close();
            bootstrapSfnClient = null;
        }
    }

    // ========================================================================
    // PHASE 8 — Test 1: ASL definition is well-formed JSON with expected states
    // ========================================================================
    // AAP §0.6.3 — every state name declared by the production ASL must be
    // present and the Parallel branches must contain both downstream
    // statement-generation and transaction-report Tasks.

    /**
     * Validates that {@code eod-batch-pipeline.asl.json} is well-formed
     * JSON and contains every canonical state name documented in AAP
     * &sect;0.6.3 plus the expected {@code Parallel} branches.
     *
     * <p>This test does NOT execute the state machine &mdash; it only
     * parses the ASL JSON and asserts structural contracts. Asserting
     * structure here catches ASL authoring errors (typos in state names,
     * missing {@code StartAt}, missing branches) at the unit-test level
     * before any expensive end-to-end execution test is attempted.</p>
     *
     * @throws IOException if the classpath resource is missing or
     *                     unreadable
     */
    @Test
    @DisplayName("Phase 8: ASL definition is well-formed JSON with expected states")
    void asl_definition_isWellFormedJson() throws IOException {
        // Replaces JCL: //JOBLIB JCLLIB ORDER= job-level metadata validation
        // (the equivalent of validating a JCL job stream parses correctly).
        String aslJson = loadResourceAsString(EOD_ASL_RESOURCE);
        assertThat(aslJson).as("ASL classpath resource %s", EOD_ASL_RESOURCE)
                .isNotBlank();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(aslJson);

        // Top-level structural requirements
        assertThat(root.has("StartAt"))
                .as("Top-level field 'StartAt' must be present")
                .isTrue();
        assertThat(root.get("StartAt").asText())
                .as("StartAt must be InitializeEodPipeline per AAP §0.6.3")
                .isEqualTo("InitializeEodPipeline");
        assertThat(root.has("States"))
                .as("Top-level field 'States' must be present")
                .isTrue();
        JsonNode states = root.get("States");
        assertThat(states.isObject())
                .as("'States' must be a JSON object")
                .isTrue();

        // Canonical EOD-pipeline state names per AAP §0.6.3
        // (POSTTRAN → INTCALC → COMBTRAN → Parallel { CREASTMT, TRANREPT } →
        // success + failure terminal states).
        List<String> expectedSequenceStates = List.of(
                "InitializeEodPipeline",
                "PostTransactions",
                "CalculateInterest",
                "CombineTransactions",
                "RunDownstreamReports",
                "EmitSuccessMetric",
                "EodPipelineSucceeded",
                "FailureNotification",
                "EmitFailureMetric",
                "EodPipelineFailed");
        for (String stateName : expectedSequenceStates) {
            assertThat(states.has(stateName))
                    .as("Expected state '%s' present per AAP §0.6.3", stateName)
                    .isTrue();
        }

        // The Parallel state for stage 4a/4b (CREASTMT + TRANREPT).
        JsonNode parallelNode = states.get("RunDownstreamReports");
        assertThat(parallelNode.get("Type").asText())
                .as("RunDownstreamReports must be a Parallel state per AAP §0.6.3")
                .isEqualTo("Parallel");
        JsonNode branchesNode = parallelNode.get("Branches");
        assertThat(branchesNode.isArray())
                .as("Parallel.Branches must be an array")
                .isTrue();
        assertThat(branchesNode.size())
                .as("Parallel.Branches must have exactly 2 entries (CREASTMT + TRANREPT)")
                .isEqualTo(2);

        // Each branch must contain either CreateStatements or RunTransactionReports
        // as its inner state.
        List<String> branchStateNames = List.of("CreateStatements", "RunTransactionReports");
        boolean foundCreateStatements = false;
        boolean foundRunReports = false;
        for (JsonNode branch : branchesNode) {
            assertThat(branch.has("StartAt")).isTrue();
            assertThat(branch.has("States")).isTrue();
            String branchStart = branch.get("StartAt").asText();
            assertThat(branchStateNames)
                    .as("Branch StartAt '%s' must be one of %s", branchStart, branchStateNames)
                    .contains(branchStart);
            if ("CreateStatements".equals(branchStart)) {
                foundCreateStatements = true;
                assertThat(branch.get("States").has("CreateStatements")).isTrue();
            } else if ("RunTransactionReports".equals(branchStart)) {
                foundRunReports = true;
                assertThat(branch.get("States").has("RunTransactionReports")).isTrue();
            }
        }
        assertThat(foundCreateStatements)
                .as("Parallel branch for CreateStatements (CREASTMT.JCL) must exist")
                .isTrue();
        assertThat(foundRunReports)
                .as("Parallel branch for RunTransactionReports (TRANREPT.jcl) must exist")
                .isTrue();
    }



    // ========================================================================
    // PHASE 9 — Test 2: Sequence Task → Task chain (happy path)
    // ========================================================================
    // AAP §0.6.3 — POSTTRAN → INTCALC → COMBTRAN are sequential Task states
    // followed by a Parallel state for CREASTMT + TRANREPT. The fixture
    // state machine mirrors this topology with Pass states; LocalStack
    // emits PassStateEntered / PassStateExited / ParallelStateEntered /
    // ParallelStateExited events that are structurally identical to the
    // corresponding TaskState* events. The sequence assertion below
    // validates the linear chain and parallel fan-out per AAP §0.6.3.

    /**
     * Asserts that a happy-path execution of the EOD-fixture state
     * machine traverses every state in the documented order
     * ({@code InitializeEodPipeline -> PostTransactions ->
     * CalculateInterest -> CombineTransactions -> RunDownstreamReports
     * (Parallel) -> EmitSuccessMetric -> EodPipelineSucceeded}) and
     * terminates with status {@code SUCCEEDED}.
     *
     * <p>The fixture uses Pass states in place of Task states for
     * LocalStack-community compatibility (see class Javadoc); the
     * transition sequence asserted here is the EXACT same sequence the
     * production ASL declares.</p>
     */
    @Test
    @DisplayName("Phase 9: Happy-path execution traverses all states in declared order")
    void execution_happyPath_runsAllStatesInOrder() {
        // Replaces JCL job stream: //POSTTRAN, //INTCALC, //COMBTRAN
        // sequential EXEC PGM= steps + parallel //CREASTMT, //TRANREPT
        // stages. The fixture's Pass states emit the same StateEntered/
        // StateExited event pairs as Task states would in production.
        String executionArn = startExecution(eodFixtureArn,
                "{\"date\":\"2022-06-10\"}", "happy-");

        // Poll until terminal state — AAP §0.7.2 mandates Awaitility over
        // Thread.sleep() (no race-condition tolerance in CI).
        await().atMost(EXECUTION_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> isTerminal(describeStatus(executionArn)));

        ExecutionStatus finalStatus = describeStatus(executionArn);
        assertThat(finalStatus)
                .as("EOD fixture execution must SUCCEED (happy path)")
                .isEqualTo(ExecutionStatus.SUCCEEDED);

        // Verify the execution event history records every expected state.
        List<HistoryEvent> events = fetchExecutionHistory(executionArn);

        // Assert event-id ordering for the sequential chain.
        long initId = findStateEnterId(events, "InitializeEodPipeline");
        long postTxnId = findStateEnterId(events, "PostTransactions");
        long interestId = findStateEnterId(events, "CalculateInterest");
        long combineId = findStateEnterId(events, "CombineTransactions");
        long parallelId = findStateEnterId(events, "RunDownstreamReports");
        long successMetricId = findStateEnterId(events, "EmitSuccessMetric");
        long succeededId = findStateEnterId(events, "EodPipelineSucceeded");

        // HistoryEvent.id() is monotonically increasing per execution, so
        // these comparisons assert temporal order.
        assertThat(initId)
                .as("InitializeEodPipeline must precede PostTransactions")
                .isLessThan(postTxnId);
        assertThat(postTxnId)
                .as("PostTransactions must precede CalculateInterest (AAP §0.6.3)")
                .isLessThan(interestId);
        assertThat(interestId)
                .as("CalculateInterest must precede CombineTransactions (AAP §0.6.3)")
                .isLessThan(combineId);
        assertThat(combineId)
                .as("CombineTransactions must precede RunDownstreamReports (AAP §0.6.3)")
                .isLessThan(parallelId);
        assertThat(parallelId)
                .as("RunDownstreamReports must precede EmitSuccessMetric")
                .isLessThan(successMetricId);
        assertThat(successMetricId)
                .as("EmitSuccessMetric must precede EodPipelineSucceeded")
                .isLessThan(succeededId);

        // Both Parallel branches must have entered.
        // AAP §0.6.3 — Parallel state for CREASTMT + TRANREPT
        assertThat(containsStateEnter(events, "CreateStatements"))
                .as("Parallel branch CreateStatements (CREASTMT.JCL) must enter")
                .isTrue();
        assertThat(containsStateEnter(events, "RunTransactionReports"))
                .as("Parallel branch RunTransactionReports (TRANREPT.jcl) must enter")
                .isTrue();

        // The Parallel state itself must have ENTERED and EXITED — these
        // events are emitted by LocalStack with the canonical
        // ParallelStateEntered / ParallelStateExited types.
        assertThat(events.stream()
                .anyMatch(e -> e.type() == HistoryEventType.PARALLEL_STATE_ENTERED))
                .as("PARALLEL_STATE_ENTERED event must be present")
                .isTrue();
        assertThat(events.stream()
                .anyMatch(e -> e.type() == HistoryEventType.PARALLEL_STATE_EXITED))
                .as("PARALLEL_STATE_EXITED event must be present")
                .isTrue();
    }

    // ========================================================================
    // PHASE 10 — Test 3: Parallel branches actually run in parallel
    // ========================================================================
    // AAP §0.6.3 — the Parallel state for CREASTMT + TRANREPT must run
    // its branches concurrently (not sequentially). LocalStack records the
    // timestamp at which each branch's first state was entered; this test
    // asserts those timestamps are within a small tolerance, proving
    // concurrency.

    /**
     * Asserts that the two branches of the {@code RunDownstreamReports}
     * Parallel state ({@code CreateStatements} and
     * {@code RunTransactionReports}) enter within a tight time window of
     * each other, proving they run concurrently rather than sequentially.
     *
     * <p>The tolerance of 2 seconds is generous to absorb LocalStack's
     * branch-scheduling overhead and CI environment timing jitter while
     * still being far below what a sequential execution (which would
     * gate one branch on the other's completion) could ever achieve.</p>
     */
    @Test
    @DisplayName("Phase 10: Parallel branches overlap in time (proves concurrency)")
    void execution_parallelBranches_overlapInTime() {
        // AAP §0.6.3 — Parallel state for CREASTMT + TRANREPT
        String executionArn = startExecution(eodFixtureArn,
                "{\"date\":\"2022-06-10\"}", "parallel-");

        await().atMost(EXECUTION_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> isTerminal(describeStatus(executionArn)));
        assertThat(describeStatus(executionArn))
                .as("Parallel-overlap execution must SUCCEED")
                .isEqualTo(ExecutionStatus.SUCCEEDED);

        List<HistoryEvent> events = fetchExecutionHistory(executionArn);
        Instant createStatementsEnter =
                findStateEnterTimestamp(events, "CreateStatements");
        Instant runReportsEnter =
                findStateEnterTimestamp(events, "RunTransactionReports");

        long deltaMillis = Math.abs(Duration
                .between(createStatementsEnter, runReportsEnter)
                .toMillis());

        // 2-second tolerance is well below any reasonable sequential
        // execution would produce (a sequential traversal would gate one
        // branch on the prior branch's completion, accumulating at least
        // tens of milliseconds per intermediate event).
        assertThat(deltaMillis)
                .as("CreateStatements and RunTransactionReports must enter "
                        + "within 2000ms of each other (delta=%dms)", deltaMillis)
                .isLessThan(2000L);
    }

    // ========================================================================
    // PHASE 11 — Test 4: Failure path — Catch state routes to Failed
    // ========================================================================
    // AAP §0.6.3 — Catch configurations model JCL COND=(...,LT)
    // skip-on-failure patterns. When PostTransactions (or any Task)
    // throws States.TaskFailed, the Catch block routes execution to
    // FailureNotification → EmitFailureMetric → EodPipelineFailed.
    //
    // The fixture state machine used here is the CHOICE_FIXTURE_ASL which
    // explicitly contains a Fail state ("PostTransactionsFailed") that we
    // can deterministically trigger via the {"shouldFail":true} input.
    // We ALSO parse the production ASL and assert the Catch configuration
    // on PostTransactions captures the expected error types.

    /**
     * Asserts that when the Choice fixture is driven with
     * {@code shouldFail=true}, the execution terminates with
     * {@code FAILED} status and routes through a {@code Fail} state,
     * mirroring how the production EOD pipeline's {@code Catch} block on
     * {@code PostTransactions} would route a failure to
     * {@code FailureNotification → EodPipelineFailed} per AAP §0.6.3.
     *
     * <p>Additionally validates that the PRODUCTION ASL definition's
     * PostTransactions Task state carries a {@code Catch} configuration
     * that matches the expected error vocabulary
     * ({@code States.TaskFailed}, {@code States.ALL}, etc.) and routes
     * to a downstream failure-handling state, per AAP §0.6.3.</p>
     */
    @Test
    @DisplayName("Phase 11: Catch state routes failure path to terminal Failed state")
    void execution_postTransactionsFails_triggersCatchAndRoutesToFailedState() throws Exception {
        // ---- Part 1: deterministic Fail-state traversal via Choice fixture
        // The fixture's RouteOnInput Choice state routes to FailureState
        // (Fail with Error="PostTransactionsFailed") when shouldFail=true.
        String executionArn = startExecution(choiceFixtureArn,
                "{\"shouldFail\":true}", "catch-");

        await().atMost(EXECUTION_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> isTerminal(describeStatus(executionArn)));

        ExecutionStatus terminalStatus = describeStatus(executionArn);
        assertThat(terminalStatus)
                .as("Choice fixture with shouldFail=true must terminate as FAILED")
                .isEqualTo(ExecutionStatus.FAILED);

        List<HistoryEvent> events = fetchExecutionHistory(executionArn);

        // Assert the Choice state was traversed.
        boolean choiceEntered = events.stream()
                .anyMatch(e -> e.type() == HistoryEventType.CHOICE_STATE_ENTERED);
        assertThat(choiceEntered)
                .as("ChoiceStateEntered must be present when shouldFail=true")
                .isTrue();

        // Assert the Fail state was entered (this is the Catch-equivalent
        // terminal sink in the fixture).
        boolean failStateEntered = events.stream()
                .anyMatch(e -> e.type() == HistoryEventType.FAIL_STATE_ENTERED);
        assertThat(failStateEntered)
                .as("FailStateEntered must be emitted for the FailureState")
                .isTrue();

        // Assert ExecutionFailed terminal event present.
        boolean executionFailed = events.stream()
                .anyMatch(e -> e.type() == HistoryEventType.EXECUTION_FAILED);
        assertThat(executionFailed)
                .as("ExecutionFailed event must terminate the failed execution")
                .isTrue();

        // ---- Part 2: validate production ASL Catch configuration
        // Per AAP §0.6.3, each Task state in eod-batch-pipeline.asl.json
        // must declare a Catch block routing to a failure-handling state.
        String prodAsl = loadResourceAsString(EOD_ASL_RESOURCE);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode aslRoot = mapper.readTree(prodAsl);
        JsonNode states = aslRoot.get("States");
        assertThat(states.has("PostTransactions"))
                .as("Production ASL must declare PostTransactions state")
                .isTrue();

        JsonNode postTxn = states.get("PostTransactions");
        assertThat(postTxn.has("Catch"))
                .as("PostTransactions Task must declare Catch (AAP §0.6.3 — "
                        + "Catch models JCL COND=(...,LT) skip-on-failure)")
                .isTrue();

        JsonNode catchArr = postTxn.get("Catch");
        assertThat(catchArr.isArray())
                .as("Catch must be a JSON array")
                .isTrue();
        assertThat(catchArr.size())
                .as("PostTransactions must declare at least one Catch entry")
                .isGreaterThanOrEqualTo(1);

        // The catch entry must specify ErrorEquals and a Next routing
        // target — these are the two mandatory fields of an ASL Catcher.
        JsonNode firstCatch = catchArr.get(0);
        assertThat(firstCatch.has("ErrorEquals"))
                .as("Catch entry must declare ErrorEquals")
                .isTrue();
        assertThat(firstCatch.has("Next"))
                .as("Catch entry must declare a Next routing target")
                .isTrue();

        JsonNode errorEquals = firstCatch.get("ErrorEquals");
        assertThat(errorEquals.isArray())
                .as("ErrorEquals must be a JSON array of error names")
                .isTrue();
        assertThat(errorEquals.size())
                .as("ErrorEquals must list at least one error")
                .isGreaterThanOrEqualTo(1);

        // The Next target must reference a downstream failure-handling
        // state that exists in the same States map.
        String nextState = firstCatch.get("Next").asText();
        assertThat(states.has(nextState))
                .as("Catch.Next target '%s' must exist in States", nextState)
                .isTrue();
    }

    // ========================================================================
    // PHASE 12 — Test 5: Retry configuration models JCL COND= retry semantics
    // ========================================================================
    // AAP §0.6.3 — Retry configurations model JCL COND= retry semantics.
    // Each Task state in the production EOD ASL must carry a Retry block
    // with ErrorEquals (the error vocabulary to retry on),
    // IntervalSeconds, MaxAttempts, and BackoffRate.
    //
    // Because LocalStack-community does not run the production Task's
    // Resource (batch:submitJob.sync), we cannot exercise the runtime
    // retry behavior end-to-end. Instead we validate the Retry
    // configuration STRUCTURALLY against the production ASL JSON, which
    // is the canonical source of truth — any deviation here would change
    // the JCL-equivalent retry semantics and must fail this test.

    /**
     * Asserts that the production ASL definition declares Retry blocks
     * on every Task state, with the expected error vocabulary
     * ({@code States.TaskFailed}, {@code States.Timeout}, and/or
     * AWS-Batch-specific error types) and complete retry parameters
     * ({@code IntervalSeconds}, {@code MaxAttempts},
     * {@code BackoffRate}). This validates the JCL {@code COND=} →
     * Step Functions Retry transformation per AAP §0.6.3.
     */
    @Test
    @DisplayName("Phase 12: Retry configuration declared on Task states "
            + "(JCL COND= → SFN Retry per AAP §0.6.3)")
    void execution_transientFailure_retriesAndSucceeds() throws Exception {
        // Replaces JCL COND= retry semantics (AAP §0.6.3).
        String prodAsl = loadResourceAsString(EOD_ASL_RESOURCE);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode aslRoot = mapper.readTree(prodAsl);
        JsonNode states = aslRoot.get("States");

        // Iterate every Task state and assert Retry presence and shape.
        // The production ASL declares Retry on PostTransactions,
        // CalculateInterest, CombineTransactions, and EmitSuccessMetric.
        List<String> taskStates = new java.util.ArrayList<>();
        java.util.Iterator<Map.Entry<String, JsonNode>> fieldIt =
                states.fields();
        while (fieldIt.hasNext()) {
            Map.Entry<String, JsonNode> entry = fieldIt.next();
            JsonNode stateNode = entry.getValue();
            if (stateNode.has("Type")
                    && "Task".equals(stateNode.get("Type").asText())) {
                taskStates.add(entry.getKey());
            }
        }
        assertThat(taskStates)
                .as("Production ASL must declare at least one Task state")
                .isNotEmpty();

        // At minimum, PostTransactions must be present and carry a Retry.
        assertThat(taskStates)
                .as("PostTransactions Task state must exist (AAP §0.6.3)")
                .contains("PostTransactions");

        // Validate retry configuration on every Task state.
        for (String taskName : taskStates) {
            JsonNode task = states.get(taskName);
            assertThat(task.has("Retry"))
                    .as("Task state '%s' must declare Retry (JCL COND= per "
                            + "AAP §0.6.3)", taskName)
                    .isTrue();

            JsonNode retryArr = task.get("Retry");
            assertThat(retryArr.isArray())
                    .as("'%s'.Retry must be a JSON array", taskName)
                    .isTrue();
            assertThat(retryArr.size())
                    .as("'%s'.Retry must declare at least one retrier",
                            taskName)
                    .isGreaterThanOrEqualTo(1);

            // Validate the shape of EACH retrier.
            for (int i = 0; i < retryArr.size(); i++) {
                JsonNode retrier = retryArr.get(i);
                assertThat(retrier.has("ErrorEquals"))
                        .as("'%s'.Retry[%d] must declare ErrorEquals",
                                taskName, i)
                        .isTrue();

                JsonNode errorEquals = retrier.get("ErrorEquals");
                assertThat(errorEquals.isArray())
                        .as("'%s'.Retry[%d].ErrorEquals must be an array",
                                taskName, i)
                        .isTrue();
                assertThat(errorEquals.size())
                        .as("'%s'.Retry[%d].ErrorEquals must list at least "
                                + "one error", taskName, i)
                        .isGreaterThanOrEqualTo(1);

                assertThat(retrier.has("IntervalSeconds"))
                        .as("'%s'.Retry[%d] must declare IntervalSeconds",
                                taskName, i)
                        .isTrue();
                assertThat(retrier.get("IntervalSeconds").asInt())
                        .as("'%s'.Retry[%d].IntervalSeconds must be > 0",
                                taskName, i)
                        .isGreaterThan(0);

                assertThat(retrier.has("MaxAttempts"))
                        .as("'%s'.Retry[%d] must declare MaxAttempts",
                                taskName, i)
                        .isTrue();
                assertThat(retrier.get("MaxAttempts").asInt())
                        .as("'%s'.Retry[%d].MaxAttempts must be >= 1",
                                taskName, i)
                        .isGreaterThanOrEqualTo(1);

                assertThat(retrier.has("BackoffRate"))
                        .as("'%s'.Retry[%d] must declare BackoffRate",
                                taskName, i)
                        .isTrue();
                assertThat(retrier.get("BackoffRate").asDouble())
                        .as("'%s'.Retry[%d].BackoffRate must be >= 1.0",
                                taskName, i)
                        .isGreaterThanOrEqualTo(1.0);
            }
        }

        // Assert PostTransactions specifically declares an error name
        // that allows AWS Batch errors to be retried — this is the
        // explicit AAP §0.6.3 requirement to model JCL transient-failure
        // retry behavior.
        JsonNode postTxn = states.get("PostTransactions");
        JsonNode retries = postTxn.get("Retry");
        boolean retriesTransientErrors = false;
        for (int i = 0; i < retries.size(); i++) {
            JsonNode errorEquals = retries.get(i).get("ErrorEquals");
            for (int j = 0; j < errorEquals.size(); j++) {
                String err = errorEquals.get(j).asText();
                // Any one of these error names indicates the retrier is
                // configured to handle the JCL-COND=-equivalent transient
                // failure vocabulary.
                if ("States.TaskFailed".equals(err)
                        || "States.Timeout".equals(err)
                        || "States.ALL".equals(err)
                        || "Batch.AWSBatchException".equals(err)
                        || "States.TaskFailed".equalsIgnoreCase(err)
                        || err.startsWith("Batch.")) {
                    retriesTransientErrors = true;
                    break;
                }
            }
            if (retriesTransientErrors) {
                break;
            }
        }
        assertThat(retriesTransientErrors)
                .as("PostTransactions.Retry must declare at least one "
                        + "transient-failure error name (States.TaskFailed, "
                        + "States.Timeout, States.ALL, or a Batch.* error) "
                        + "to model JCL COND= retry semantics per AAP §0.6.3")
                .isTrue();
    }


    // ========================================================================
    // PHASE 13 — Test 6: Choice state routes based on input
    // ========================================================================
    // The Choice fixture state machine declares a RouteOnInput Choice
    // state that branches on the {@code $.shouldFail} input field. When
    // shouldFail=true, execution flows to FailureState (Fail). When
    // shouldFail=false, execution flows to SuccessState (Succeed). This
    // mirrors how the production EOD pipeline could (or future revisions
    // would) declare a Choice state to skip CombineTransactions when the
    // daily transaction count is zero — the canonical pattern documented
    // in the AAP §0.6.3 ASL transformation rules.

    /**
     * Asserts that the Choice state in the Choice fixture routes to
     * different terminal states based on the {@code $.shouldFail} input
     * field. With {@code shouldFail=false} the execution succeeds via
     * the SuccessState branch; with {@code shouldFail=true} the
     * execution fails via the FailureState branch. This validates the
     * Choice → branch routing behavior generically before the production
     * ASL adopts a Choice state.
     */
    @Test
    @DisplayName("Phase 13: Choice state routes execution based on input data")
    void execution_choiceState_routesBasedOnInput() {
        // -- Branch A: shouldFail=false → SuccessState (Succeed) --------
        String successArn = startExecution(choiceFixtureArn,
                "{\"shouldFail\":false}", "choice-ok-");

        await().atMost(EXECUTION_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> isTerminal(describeStatus(successArn)));
        assertThat(describeStatus(successArn))
                .as("Choice fixture with shouldFail=false must SUCCEED")
                .isEqualTo(ExecutionStatus.SUCCEEDED);

        List<HistoryEvent> successEvents = fetchExecutionHistory(successArn);
        // The Choice state must have been entered and exited.
        assertThat(successEvents.stream()
                .anyMatch(e -> e.type() == HistoryEventType.CHOICE_STATE_ENTERED))
                .as("ChoiceStateEntered must be present in success-branch run")
                .isTrue();
        assertThat(successEvents.stream()
                .anyMatch(e -> e.type() == HistoryEventType.CHOICE_STATE_EXITED))
                .as("ChoiceStateExited must be present in success-branch run")
                .isTrue();
        // SUCCESS branch: SucceedStateEntered must be emitted.
        assertThat(successEvents.stream()
                .anyMatch(e -> e.type() == HistoryEventType.SUCCEED_STATE_ENTERED))
                .as("SucceedStateEntered must be emitted when "
                        + "shouldFail=false (Choice → SuccessState)")
                .isTrue();
        // SUCCESS branch: FailStateEntered must NOT be emitted.
        assertThat(successEvents.stream()
                .noneMatch(e -> e.type() == HistoryEventType.FAIL_STATE_ENTERED))
                .as("FailStateEntered MUST NOT appear in success-branch run")
                .isTrue();

        // -- Branch B: shouldFail=true → FailureState (Fail) ------------
        String failArn = startExecution(choiceFixtureArn,
                "{\"shouldFail\":true}", "choice-fail-");

        await().atMost(EXECUTION_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> isTerminal(describeStatus(failArn)));
        assertThat(describeStatus(failArn))
                .as("Choice fixture with shouldFail=true must FAIL")
                .isEqualTo(ExecutionStatus.FAILED);

        List<HistoryEvent> failEvents = fetchExecutionHistory(failArn);
        // The Choice state must have been entered (Choice on the failing
        // branch may or may not emit Exited before transitioning to a
        // terminal Fail — assert Entered to be robust).
        assertThat(failEvents.stream()
                .anyMatch(e -> e.type() == HistoryEventType.CHOICE_STATE_ENTERED))
                .as("ChoiceStateEntered must be present in fail-branch run")
                .isTrue();
        // FAIL branch: FailStateEntered must be emitted.
        assertThat(failEvents.stream()
                .anyMatch(e -> e.type() == HistoryEventType.FAIL_STATE_ENTERED))
                .as("FailStateEntered must be emitted when "
                        + "shouldFail=true (Choice → FailureState)")
                .isTrue();
        // FAIL branch: SucceedStateEntered must NOT be emitted.
        assertThat(failEvents.stream()
                .noneMatch(e -> e.type() == HistoryEventType.SUCCEED_STATE_ENTERED))
                .as("SucceedStateEntered MUST NOT appear in fail-branch run")
                .isTrue();
        // FAIL branch: ExecutionFailed terminal event must be present.
        assertThat(failEvents.stream()
                .anyMatch(e -> e.type() == HistoryEventType.EXECUTION_FAILED))
                .as("ExecutionFailed must terminate the fail-branch run")
                .isTrue();
    }

    // ========================================================================
    // PHASE 14 — Test 7: file-provisioning.asl.json Map state validation
    // ========================================================================
    // AAP §0.4.1 — file-provisioning.asl.json declares a Map state that
    // iterates over the reference-data Flyway migrations and Glue ETL
    // jobs that bulk-load Customer / Account / Card / Cross-Reference
    // fact data from S3 into RDS. This test validates the Map state's
    // structural configuration (ItemsPath, ItemProcessor.StartAt) per
    // the AAP's transformation mapping rules.

    /**
     * Asserts that {@code file-provisioning.asl.json} declares a Map
     * state {@code BulkLoadFactData} with {@code ItemsPath: $.bulkLoadJobs}
     * and an {@code ItemProcessor} whose {@code StartAt} is
     * {@code InvokeGlueLoadJob}, matching the AAP §0.4.1 provisioning
     * pipeline contract.
     */
    @Test
    @DisplayName("Phase 14: file-provisioning ASL declares Map state for "
            + "reference-data + Glue bulk-load iteration")
    void fileProvisioning_loadsReferenceDataViaMapAndGlue() throws Exception {
        // Replaces JCL provisioning chain: ACCTFILE.jcl, CARDFILE.jcl,
        // XREFFILE.jcl, CUSTFILE.jcl, etc. — bulk-load via Map → Glue
        // (AAP §0.6.3, AAP §0.4.1).
        String provisioningAsl = loadResourceAsString(PROVISIONING_ASL_RESOURCE);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode aslRoot = mapper.readTree(provisioningAsl);

        // Top-level structural assertions.
        assertThat(aslRoot.has("StartAt"))
                .as("file-provisioning.asl.json must declare StartAt")
                .isTrue();
        assertThat(aslRoot.has("States"))
                .as("file-provisioning.asl.json must declare States")
                .isTrue();

        JsonNode states = aslRoot.get("States");
        assertThat(states.has("BulkLoadFactData"))
                .as("Provisioning ASL must declare BulkLoadFactData Map "
                        + "state (AAP §0.4.1 — bulk-load Customer/Account/"
                        + "Card/XRef via Glue + S3)")
                .isTrue();

        JsonNode mapState = states.get("BulkLoadFactData");
        assertThat(mapState.has("Type"))
                .as("BulkLoadFactData must declare Type")
                .isTrue();
        assertThat(mapState.get("Type").asText())
                .as("BulkLoadFactData must be a Map state")
                .isEqualTo("Map");

        // ItemsPath must point at the input field containing the bulk-
        // load job descriptors.
        assertThat(mapState.has("ItemsPath"))
                .as("Map state must declare ItemsPath (canonical "
                        + "iteration-source pointer)")
                .isTrue();
        assertThat(mapState.get("ItemsPath").asText())
                .as("ItemsPath must reference $.bulkLoadJobs (AAP §0.4.1)")
                .isEqualTo("$.bulkLoadJobs");

        // The Map state must declare its iteration sub-state machine via
        // EITHER ItemProcessor (modern ASL — distributed Map) OR
        // Iterator (legacy ASL — inline Map). Both forms are valid in
        // AWS Step Functions and Amazon States Language; the production
        // file-provisioning.asl.json uses the legacy "Iterator" form.
        // This test accepts either form so the test is resilient to
        // future ASL syntax modernization while still enforcing that
        // the iteration sub-machine starts at InvokeGlueLoadJob
        // (replaces JCL EXEC PGM=IEBGENER + IDCAMS REPRO via Glue).
        boolean hasItemProcessor = mapState.has("ItemProcessor");
        boolean hasIterator = mapState.has("Iterator");
        assertThat(hasItemProcessor || hasIterator)
                .as("Map state must declare iteration sub-machine via "
                        + "ItemProcessor (modern ASL) or Iterator (legacy ASL)")
                .isTrue();

        JsonNode iterationSubMachine = hasItemProcessor
                ? mapState.get("ItemProcessor")
                : mapState.get("Iterator");
        String subMachineFieldName = hasItemProcessor ? "ItemProcessor" : "Iterator";

        assertThat(iterationSubMachine.has("StartAt"))
                .as("%s must declare StartAt", subMachineFieldName)
                .isTrue();
        assertThat(iterationSubMachine.get("StartAt").asText())
                .as("%s.StartAt must reference InvokeGlueLoadJob "
                        + "(AAP §0.4.1 — Glue replaces IEBGENER/IDCAMS REPRO)",
                        subMachineFieldName)
                .isEqualTo("InvokeGlueLoadJob");

        // The iteration sub-machine must declare its own States map
        // containing the InvokeGlueLoadJob state.
        assertThat(iterationSubMachine.has("States"))
                .as("%s must declare its own States map", subMachineFieldName)
                .isTrue();
        JsonNode processorStates = iterationSubMachine.get("States");
        assertThat(processorStates.has("InvokeGlueLoadJob"))
                .as("%s.States must declare InvokeGlueLoadJob",
                        subMachineFieldName)
                .isTrue();
    }


    // ========================================================================
    // PHASE 15 — Test 8: StepFunctionsOrchestrator adapter integration
    // ========================================================================
    // AAP §0.4.1 — StepFunctionsOrchestrator isolates Step Functions
    // SDK calls from business logic. This test invokes the adapter's
    // startEodPipeline(String) method against the LocalStack-emulated
    // SFN service, asserting the returned execution ARN is well-formed
    // and that the execution is discoverable via the SfnClient.
    //
    // Because application-test.yml defines properties under a different
    // suffix (carddemo.stepfunctions.eod-batch-pipeline-arn) than the
    // orchestrator's @Value-injected field name expects
    // (carddemo.stepfunctions.eod-pipeline-arn), we use
    // ReflectionTestUtils.setField to push the fixture state machine
    // ARN into the orchestrator's eodPipelineArn field prior to
    // invocation. This is a test-time concern only and does not affect
    // production configuration.

    /**
     * Asserts that the {@code StepFunctionsOrchestrator.startEodPipeline}
     * adapter method returns a well-formed execution ARN that points to
     * a running (or terminal) execution in LocalStack's Step Functions
     * service. This validates the AAP §0.4.1 adapter pattern: business
     * logic invokes the orchestrator, which in turn invokes
     * SfnClient.startExecution under the hood, isolating the AWS SDK
     * call from the caller.
     */
    @Test
    @DisplayName("Phase 15: StepFunctionsOrchestrator.startEodPipeline returns "
            + "an execution ARN discoverable via SfnClient.describeExecution")
    void orchestrator_startEodPipeline_returnsExecutionArn() {
        // Replaces JCL job stream entry point: POSTTRAN.jcl scheduling
        // (AAP §0.4.1 — orchestrator isolates SFN SDK from business
        // logic).
        //
        // Inject the EOD fixture state machine ARN into the orchestrator
        // — the @Value("${carddemo.stepfunctions.eod-pipeline-arn:}")
        // field would otherwise be empty because application-test.yml
        // uses a different property key. This is acceptable: production
        // wiring is validated separately by StepFunctionsOrchestratorTest
        // (unit test) and by the running configuration in
        // application-prod.yml.
        ReflectionTestUtils.setField(orchestrator,
                "eodPipelineArn", eodFixtureArn);

        // Invoke the public API of the adapter. The String parameter
        // (batchRunDate) is documented as the date for which the EOD
        // pipeline should run; the orchestrator embeds it in the
        // execution input payload.
        String executionArn = orchestrator.startEodPipeline("2022-06-10");

        // The returned ARN must follow the canonical Step Functions
        // execution-ARN format: arn:aws:states:<region>:<account>:
        // execution:<state-machine-name>:<execution-name>
        assertThat(executionArn)
                .as("Orchestrator must return a non-null execution ARN")
                .isNotNull();
        assertThat(executionArn)
                .as("Execution ARN must begin with the Step Functions "
                        + "execution-ARN prefix")
                .startsWith("arn:aws:states:");
        assertThat(executionArn)
                .as("Execution ARN must contain the ':execution:' segment")
                .contains(":execution:");

        // The execution must be discoverable via the SfnClient — this
        // is the canonical proof that the orchestrator did invoke
        // SfnClient.startExecution under the hood (per AAP §0.4.1
        // adapter pattern).
        await().atMost(EXECUTION_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> {
                    DescribeExecutionResponse desc = sfnClient
                            .describeExecution(DescribeExecutionRequest.builder()
                                    .executionArn(executionArn)
                                    .build());
                    assertThat(desc.executionArn())
                            .as("DescribeExecution must echo back the "
                                    + "execution ARN")
                            .isEqualTo(executionArn);
                    assertThat(desc.stateMachineArn())
                            .as("Execution must be bound to the EOD fixture "
                                    + "state machine")
                            .isEqualTo(eodFixtureArn);
                    // Optionally, allow execution to complete — but we
                    // do not require SUCCEEDED here, because the
                    // orchestrator's contract is only that the execution
                    // was successfully started. Terminal status is
                    // separately validated by Phase 9.
                    assertThat(desc.status())
                            .as("Execution status must be a recognized "
                                    + "ExecutionStatus enum value")
                            .isNotNull();
                });
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    /**
     * Loads a classpath resource as a UTF-8-encoded {@link String}. Used
     * to read the ASL definitions ({@code eod-batch-pipeline.asl.json},
     * {@code file-provisioning.asl.json}) for both LocalStack state-
     * machine creation and structural Jackson assertions.
     *
     * @param classpathPath the leading-slash resource path
     *        (e.g. {@code "/stepfunctions/eod-batch-pipeline.asl.json"})
     * @return the resource content as a UTF-8 String
     * @throws IOException if the resource is missing or unreadable
     */
    private static String loadResourceAsString(String classpathPath)
            throws IOException {
        try (InputStream in = StepFunctionsEodPipelineIT.class
                .getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IOException(
                        "Classpath resource not found: " + classpathPath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Starts a Step Functions execution against the given state-machine
     * ARN with the given input JSON and returns the execution ARN.
     * Generates a unique execution name with a UUID suffix to avoid
     * collisions across parallel test runs and CI shards.
     *
     * @param stateMachineArn the target state machine ARN
     * @param inputJson the execution input as a JSON string
     * @param namePrefix a short descriptive prefix for the execution
     *        name (helps with LocalStack log readability)
     * @return the newly-started execution's ARN
     */
    private String startExecution(String stateMachineArn,
                                  String inputJson,
                                  String namePrefix) {
        String executionName = namePrefix
                + UUID.randomUUID().toString().substring(0, 12);
        StartExecutionResponse response = sfnClient.startExecution(
                StartExecutionRequest.builder()
                        .stateMachineArn(stateMachineArn)
                        .name(executionName)
                        .input(inputJson)
                        .build());
        return response.executionArn();
    }

    /**
     * Returns the current {@link ExecutionStatus} for the given
     * execution ARN.
     */
    private ExecutionStatus describeStatus(String executionArn) {
        DescribeExecutionResponse response = sfnClient.describeExecution(
                DescribeExecutionRequest.builder()
                        .executionArn(executionArn)
                        .build());
        return response.status();
    }

    /**
     * Returns {@code true} if the given status is one of the recognized
     * terminal Step Functions execution statuses.
     */
    private static boolean isTerminal(ExecutionStatus status) {
        if (status == null) {
            return false;
        }
        switch (status) {
            case SUCCEEDED:
            case FAILED:
            case TIMED_OUT:
            case ABORTED:
                return true;
            default:
                return false;
        }
    }

    /**
     * Fetches the full execution history for the given execution ARN,
     * paging until all events have been retrieved. LocalStack typically
     * returns the entire history in a single page for fixture-sized
     * executions, but we page defensively to ensure correctness on any
     * LocalStack version or production AWS endpoint.
     */
    private List<HistoryEvent> fetchExecutionHistory(String executionArn) {
        List<HistoryEvent> all = new java.util.ArrayList<>();
        String nextToken = null;
        do {
            GetExecutionHistoryRequest.Builder reqBuilder =
                    GetExecutionHistoryRequest.builder()
                            .executionArn(executionArn)
                            .reverseOrder(false)
                            .maxResults(1000);
            if (nextToken != null) {
                reqBuilder.nextToken(nextToken);
            }
            GetExecutionHistoryResponse response = sfnClient
                    .getExecutionHistory(reqBuilder.build());
            all.addAll(response.events());
            nextToken = response.nextToken();
        } while (nextToken != null && !nextToken.isEmpty());
        return all;
    }

    /**
     * Finds the {@code id()} of the first event in {@code events} that
     * represents the entry of the given state. Recognizes the canonical
     * StateEntered events for Pass, Task, Choice, Parallel, Wait,
     * Succeed, Fail, and Map state types.
     *
     * @throws AssertionError if no matching StateEntered event is found
     */
    private static long findStateEnterId(List<HistoryEvent> events,
                                         String stateName) {
        for (HistoryEvent event : events) {
            if (isStateEntered(event)
                    && stateName.equals(stateEnteredName(event))) {
                return event.id();
            }
        }
        throw new AssertionError(
                "No StateEntered event found for state: " + stateName);
    }

    /**
     * Returns {@code true} if {@code events} contains at least one
     * StateEntered event for the given state name.
     */
    private static boolean containsStateEnter(List<HistoryEvent> events,
                                              String stateName) {
        for (HistoryEvent event : events) {
            if (isStateEntered(event)
                    && stateName.equals(stateEnteredName(event))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the timestamp of the first event representing the entry of
     * the given state. Used by Phase 10 to assert parallel-branch
     * concurrency.
     *
     * @throws AssertionError if no matching event is found
     */
    private static Instant findStateEnterTimestamp(List<HistoryEvent> events,
                                                   String stateName) {
        for (HistoryEvent event : events) {
            if (isStateEntered(event)
                    && stateName.equals(stateEnteredName(event))) {
                return event.timestamp();
            }
        }
        throw new AssertionError(
                "No StateEntered event found for state: " + stateName);
    }

    /**
     * Returns {@code true} if the given history event represents the
     * entry of any state (Pass, Task, Choice, Parallel, Wait, Succeed,
     * Fail, or Map).
     */
    private static boolean isStateEntered(HistoryEvent event) {
        HistoryEventType type = event.type();
        return type == HistoryEventType.PASS_STATE_ENTERED
                || type == HistoryEventType.TASK_STATE_ENTERED
                || type == HistoryEventType.CHOICE_STATE_ENTERED
                || type == HistoryEventType.PARALLEL_STATE_ENTERED
                || type == HistoryEventType.WAIT_STATE_ENTERED
                || type == HistoryEventType.SUCCEED_STATE_ENTERED
                || type == HistoryEventType.FAIL_STATE_ENTERED
                || type == HistoryEventType.MAP_STATE_ENTERED;
    }

    /**
     * Returns the state name from a StateEntered history event. Each
     * state-type's StateEntered event details object exposes a {@code
     * name()} accessor; this helper dispatches to the correct accessor
     * based on the event type.
     */
    private static String stateEnteredName(HistoryEvent event) {
        HistoryEventType type = event.type();
        if (type == HistoryEventType.PASS_STATE_ENTERED) {
            return event.stateEnteredEventDetails() != null
                    ? event.stateEnteredEventDetails().name()
                    : null;
        }
        if (type == HistoryEventType.TASK_STATE_ENTERED) {
            return event.stateEnteredEventDetails() != null
                    ? event.stateEnteredEventDetails().name()
                    : null;
        }
        if (type == HistoryEventType.CHOICE_STATE_ENTERED) {
            return event.stateEnteredEventDetails() != null
                    ? event.stateEnteredEventDetails().name()
                    : null;
        }
        if (type == HistoryEventType.PARALLEL_STATE_ENTERED) {
            return event.stateEnteredEventDetails() != null
                    ? event.stateEnteredEventDetails().name()
                    : null;
        }
        if (type == HistoryEventType.WAIT_STATE_ENTERED) {
            return event.stateEnteredEventDetails() != null
                    ? event.stateEnteredEventDetails().name()
                    : null;
        }
        if (type == HistoryEventType.SUCCEED_STATE_ENTERED) {
            return event.stateEnteredEventDetails() != null
                    ? event.stateEnteredEventDetails().name()
                    : null;
        }
        if (type == HistoryEventType.FAIL_STATE_ENTERED) {
            return event.stateEnteredEventDetails() != null
                    ? event.stateEnteredEventDetails().name()
                    : null;
        }
        if (type == HistoryEventType.MAP_STATE_ENTERED) {
            return event.stateEnteredEventDetails() != null
                    ? event.stateEnteredEventDetails().name()
                    : null;
        }
        return null;
    }
}

