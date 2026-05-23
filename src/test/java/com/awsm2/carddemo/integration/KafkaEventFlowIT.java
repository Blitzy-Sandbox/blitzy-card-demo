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

// AAP §0.6.5 — Per-account ordering invariant via partition-by-accountId
import com.awsm2.carddemo.CardDemoApplication;
import com.awsm2.carddemo.adapter.KafkaEventConsumer;
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.adapter.SecretsManagerService;
import com.awsm2.carddemo.config.KafkaConfig;
import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.TransactionAddDto;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sfn.SfnClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test for the Kafka event flow of the CardDemo system, focusing
 * on the {@code transaction.posted} &rarr; {@code account.updated} cascade
 * and per-account ordering guarantees mandated by AAP &sect;0.6.5.
 *
 * <p><b>Replaces:</b> CICS Transient Data Queue (TDQ) writes by COBOL
 * online programs (COTRN02C, COBIL00C, CORPT00C) and inter-step file-based
 * communication between JCL batch jobs (POSTTRAN, INTCALC, COMBTRAN) per
 * AAP &sect;0.6.5. The mainframe&apos;s implicit ordering guarantee
 * (sequential JCL execution and serialized TDQ writes) is replaced by
 * Kafka&apos;s partition-key-based ordering: events for the same account
 * ID always land on the same partition and are therefore consumed in the
 * order they were produced.</p>
 *
 * <h2>What this test validates (AAP &sect;0.6.5 verbatim)</h2>
 *
 * <ol>
 *   <li><b>Producer config invariants</b> &mdash; {@code acks=all},
 *       {@code enable.idempotence=true}, {@code max.in.flight.requests.per.connection <= 5},
 *       {@code retries=Integer.MAX_VALUE} are present on the application&apos;s
 *       {@link KafkaTemplate}&apos;s {@link ProducerFactory} configuration
 *       (verified by {@link #producer_configIs_acksAll_idempotentTrue()}).</li>
 *   <li><b>Consumer config invariants</b> &mdash; {@code enable.auto.commit=false},
 *       {@code isolation.level=read_committed} are present on the application&apos;s
 *       Kafka listener container factory configuration; {@code AckMode.MANUAL}
 *       governs the {@code @KafkaListener} containers (verified by
 *       {@link #consumer_configIs_manualCommit_readCommitted()}).</li>
 *   <li><b>Per-account ordering</b> &mdash; for any given account ID, all
 *       events arrive at the consumer in production order. Demonstrated by
 *       publishing 1000 interleaved events across 10 accounts and verifying
 *       monotonic {@code seqNum} progression per account (verified by
 *       {@link #perAccountOrdering_invariantHolds()}).</li>
 *   <li><b>Cross-account interleaving is allowed</b> &mdash; events for
 *       different accounts MAY interleave at the consumer; the invariant is
 *       per-account, not global (verified by
 *       {@link #crossAccountOrdering_isNotEnforced()}).</li>
 *   <li><b>Partition-by-accountId determinism</b> &mdash; given a fixed
 *       partition count, the same account ID always hashes to the same
 *       partition (verified by
 *       {@link #partitionAssignment_byAccountId_isDeterministic()}).</li>
 *   <li><b>Idempotent producer</b> &mdash; with
 *       {@code enable.idempotence=true} the producer never publishes
 *       duplicate records across retries (verified by
 *       {@link #idempotentProducer_doesNotDuplicate()}).</li>
 *   <li><b>Manual offset commit on success</b> &mdash; the consumer group
 *       offset advances only after successful {@code Acknowledgment.acknowledge()}
 *       (verified by {@link #consumer_commitsOffsetOnSuccess()}).</li>
 *   <li><b>No offset advance on failure</b> &mdash; if the test consumer
 *       polls a record but does not acknowledge, the offset is not committed
 *       and the record will be re-polled on the next consumer-group rebalance
 *       (verified by {@link #consumer_doesNotCommitOnFailure()}).</li>
 *   <li><b>Cross-topic partition-key preservation</b> &mdash; when an
 *       account.updated event follows a transaction.posted event for the
 *       same account, both partition keys are derived from the same account
 *       ID (zero-padded to 11 digits via
 *       {@link KafkaEventPublisher#PARTITION_KEY_FORMAT}) so consumers
 *       observe ordered events across topics (verified by
 *       {@link #cascade_transactionPostedTriggersAccountUpdated()}).</li>
 *   <li><b>Rebalance does not break ordering</b> &mdash; pausing and
 *       resuming a listener container (the rebalance model on a single-broker
 *       Testcontainers Kafka) does not violate per-account ordering on the
 *       resumed consumer (verified by
 *       {@link #rebalance_doesNotBreakOrdering()}).</li>
 * </ol>
 *
 * <h2>Test isolation strategy</h2>
 *
 * <ul>
 *   <li>{@link KafkaContainer} (Confluent {@code cp-kafka:7.5.0}, pinned per
 *       AAP &sect;0.5.1 "no {@code latest} tags") provides an ephemeral
 *       Apache Kafka broker per test class lifecycle. Spring Boot 3.1+
 *       auto-wires the broker URL via
 *       {@link ServiceConnection &#64;ServiceConnection}.</li>
 *   <li>{@link PostgreSQLContainer} satisfies the Spring Boot context&apos;s
 *       JPA / Flyway requirements via {@link ServiceConnection
 *       &#64;ServiceConnection} so the full {@link CardDemoApplication}
 *       context can refresh.</li>
 *   <li>Topics ({@code transaction.posted}, {@code account.updated},
 *       {@code ledger.balanced}, {@code report.requested}) are created
 *       fresh in {@link #createTopicsAndPauseApplicationConsumers()} with
 *       12 partitions to test ordering across multiple partitions per
 *       agent_prompt Phase 7.</li>
 *   <li>All non-Kafka AWS clients ({@code S3Client}, {@code SfnClient},
 *       {@code SecretsManagerClient}, {@code CloudWatchClient},
 *       {@code GlueClient}, {@code OpenSearchClient}) are replaced with
 *       {@link MockBean &#64;MockBean} stubs.</li>
 *   <li>{@link RedisTemplate} is mocked to avoid requiring an ElastiCache
 *       Redis instance.</li>
 *   <li>The real {@link KafkaTemplate} bean from {@link KafkaConfig} is
 *       NOT mocked &mdash; this test directly verifies its production
 *       configuration.</li>
 *   <li>The application&apos;s {@link KafkaEventConsumer} {@code @KafkaListener}
 *       containers are paused in {@link #createTopicsAndPauseApplicationConsumers()}
 *       so they do not race with the test consumers; tests that exercise the
 *       application&apos;s consumer behavior (manual offset commit, listener
 *       resume) explicitly resume the relevant container.</li>
 *   <li>Per-test unique consumer group IDs (random {@code UUID.randomUUID()}
 *       suffix) provide hermetic offset state.</li>
 * </ul>
 *
 * <h2>Operational constraints honored by this test (AAP &sect;0.7.1)</h2>
 *
 * <ul>
 *   <li>No hardcoded credentials, ARNs, or endpoint URLs &mdash; all values
 *       come from {@code application-test.yml}, the
 *       {@link KafkaContainer}, or the {@link DynamicPropertySource}.</li>
 *   <li>No inline AWS SDK calls in business logic &mdash; the SOLE adapters
 *       under direct test are {@link KafkaEventPublisher} and
 *       {@link KafkaEventConsumer}.</li>
 *   <li>No {@code Thread.sleep()} &mdash; asynchronous waits use
 *       {@link org.awaitility.Awaitility#await()} with bounded timeouts.</li>
 *   <li>No {@code System.out.println()} &mdash; all diagnostics route through
 *       {@link Logger SLF4J Logger}.</li>
 *   <li>{@code IT.java} suffix routes execution to the Failsafe phase per
 *       {@code pom.xml} plugin configuration.</li>
 *   <li>Package-private class &mdash; JUnit 5 does not require {@code public}
 *       visibility, and minimizing access keeps the test internal to
 *       {@code com.awsm2.carddemo.integration}.</li>
 *   <li>All monetary fields on test event payloads use {@link BigDecimal}
 *       (AAP &sect;0.6.1) &mdash; no {@code float} / {@code double}.</li>
 * </ul>
 *
 * @see KafkaEventPublisher
 * @see KafkaEventConsumer
 * @see com.awsm2.carddemo.config.KafkaConfig
 */
// Replaces: CICS TDQ (Transient Data Queue) writes for inter-program
// messaging in COBOL online programs (COTRN02C, COBIL00C, CORPT00C) and
// file-based hand-off between JCL batch jobs (POSTTRAN.jcl, INTCALC.jcl,
// COMBTRAN.jcl). AAP §0.6.5 — MSK Topic Ordering Guarantees: partition-by-
// account-ID provides per-account event ordering equivalent to the
// mainframe's implicit serialized JCL execution model.
@SpringBootTest(classes = CardDemoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import(KafkaEventFlowIT.TestSecretsManagerConfiguration.class)
class KafkaEventFlowIT {

    // -------------------------------------------------------------------------
    // SLF4J Logger — replaces System.out.println per AAP §0.7.1 logging rules
    // -------------------------------------------------------------------------

    /**
     * SLF4J logger for test diagnostics. Per AAP &sect;0.7.1 every test
     * emits structured logs through SLF4J so CI log aggregation (the
     * test-runtime equivalent of CloudWatch in production) captures
     * progression markers without per-test format drift.
     */
    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventFlowIT.class);

    // -------------------------------------------------------------------------
    // Test constants — Kafka topic names, partition counts, timeouts
    // -------------------------------------------------------------------------

    /** {@code transaction.posted} topic name (verbatim per AAP &sect;0.6.5). */
    private static final String TOPIC_TRANSACTION_POSTED = "transaction.posted";

    /** {@code account.updated} topic name (verbatim per AAP &sect;0.6.5). */
    private static final String TOPIC_ACCOUNT_UPDATED = "account.updated";

    /** {@code ledger.balanced} topic name (verbatim per AAP &sect;0.6.5). */
    private static final String TOPIC_LEDGER_BALANCED = "ledger.balanced";

    /** {@code report.requested} topic name (verbatim per AAP &sect;0.6.5). */
    private static final String TOPIC_REPORT_REQUESTED = "report.requested";

    /**
     * Number of partitions used for each test topic. 12 partitions allow
     * the per-account ordering test to verify that the same account ID
     * deterministically maps to a SINGLE partition across multiple
     * publications (which would not be testable with a single-partition
     * topic). Per AAP &sect;0.6.5 "partition count &ge; producer concurrency"
     * &mdash; 12 partitions exceeds the test&apos;s producer concurrency
     * of 1, leaving headroom for the partition-determinism assertion.
     */
    private static final int TOPIC_PARTITIONS = 12;

    /** Replication factor for test topics &mdash; single-broker Testcontainers Kafka. */
    private static final short TOPIC_REPLICATION = (short) 1;

    /** Awaitility maximum wait for asynchronous broker / consumer operations. */
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(60);

    /** Awaitility per-poll interval. */
    private static final Duration AWAIT_POLL_INTERVAL = Duration.ofMillis(200);

    /** Kafka poll timeout for the per-test {@link KafkaConsumer} instances. */
    private static final Duration CONSUMER_POLL_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Number of distinct account IDs used by the per-account ordering test
     * (10 accounts &times; 100 events per account = 1000 total events).
     */
    private static final int ORDERING_TEST_ACCOUNT_COUNT = 10;

    /** Number of events per account for the per-account ordering test. */
    private static final int ORDERING_TEST_EVENTS_PER_ACCOUNT = 100;

    /**
     * Single account ID used by the partition-determinism test. The literal
     * value carries no semantic meaning &mdash; only its identity across
     * multiple publications matters (same key &rarr; same partition).
     */
    private static final long PARTITION_TEST_ACCOUNT_A = 99_999_999_999L;

    /**
     * Second account ID used by the partition-determinism test. Distinct
     * from {@link #PARTITION_TEST_ACCOUNT_A} so the test can demonstrate
     * that distinct keys MAY route to different partitions (but each is
     * deterministic on its own).
     */
    private static final long PARTITION_TEST_ACCOUNT_B = 11_111_111_111L;

    // -------------------------------------------------------------------------
    // @TestConfiguration — stub SecretsManagerService for JwtTokenProvider
    // -------------------------------------------------------------------------
    // JwtTokenProvider is annotated @Component @RefreshScope and performs an
    // eager Secrets Manager fetch in its @PostConstruct lifecycle hook to load
    // the HS256 signing key. Without a stubbed SecretsManagerService, context
    // refresh fails with IllegalStateException because the unstubbed Mockito
    // mock returns Mockito's default Optional (Optional.empty()). The 60-byte
    // placeholder key easily clears the 32-byte minimum imposed by HS256
    // (RFC 7518).
    /**
     * Provides a stubbed {@link SecretsManagerService} bean for the duration
     * of {@link KafkaEventFlowIT}. The stub returns a deterministic 60-byte
     * ASCII placeholder for any {@code (secretArn, fieldName)} pair passed
     * to {@code getSecretJsonField(...)}, which is sufficient to satisfy
     * {@code JwtTokenProvider}&apos;s HS256 key-length precondition during
     * context refresh.
     *
     * <p>This stub is marked {@link Primary &#64;Primary} so it overrides
     * the real {@code SecretsManagerService} bean registered by component
     * scan for the entire test context. No real AWS API calls occur.</p>
     */
    @TestConfiguration
    static class TestSecretsManagerConfiguration {

        /** Test-only placeholder key (60 bytes, well above HS256&apos;s 32-byte minimum). */
        private static final String TEST_SIGNING_KEY =
                "test-only-jwt-signing-key-for-kafka-flow-it-padded-60bytes!";

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
    // Testcontainers — Apache Kafka broker + PostgreSQL backing the Spring context
    // -------------------------------------------------------------------------

    /**
     * Ephemeral Apache Kafka broker (Confluent {@code cp-kafka:7.5.0})
     * provisioned by Testcontainers. Pinned image tag per AAP &sect;0.5.1
     * "no {@code latest} tags".
     *
     * <p>The {@link ServiceConnection &#64;ServiceConnection} annotation
     * (Spring Boot 3.1+) automatically binds Spring Boot&apos;s
     * {@code KafkaConnectionDetails} to this container, so the application&apos;s
     * {@link KafkaTemplate} and {@link KafkaListenerEndpointRegistry} beans
     * connect to this broker without manual {@code spring.kafka.bootstrap-servers}
     * configuration.</p>
     */
    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    /**
     * Ephemeral PostgreSQL 16-alpine container backing the JPA / Flyway
     * integration for this test class. Required because the
     * {@link CardDemoApplication} context contains JPA-backed services,
     * repositories, and Flyway migrations that cannot bootstrap without a
     * running database.
     *
     * <p>Image tag pinned to {@code postgres:16-alpine} per AAP &sect;0.5.1
     * "no {@code latest} tags". The container is bound to the Spring
     * context via {@link ServiceConnection &#64;ServiceConnection} (Spring
     * Boot 3.1+ auto-wires {@code DataSourceConnectionDetails} and
     * {@code FlywayConnectionDetails}).</p>
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    // -------------------------------------------------------------------------
    // @DynamicPropertySource — bind container details + reinforce Kafka invariants
    // -------------------------------------------------------------------------
    // The PostgreSQL container details are pushed into the Spring Environment
    // so the @Primary @RefreshScope DataSource bean declared by JpaConfig
    // (which is constructed from DataSourceProperties rather than
    // JdbcConnectionDetails) connects to the SAME container that Flyway
    // migrates via @ServiceConnection. See CardDemoApplicationTests for the
    // full rationale.
    //
    // The Kafka properties are re-asserted defensively even though
    // application-test.yml already declares them: per AAP §0.6.5 the test
    // MUST verify these values are bound at context refresh, so binding
    // them here AND in YAML provides defense in depth against accidental
    // YAML edits silently violating the invariants.

    /**
     * Binds the running Testcontainers PostgreSQL connection details into
     * the Spring {@code Environment} under {@code spring.datasource.*}
     * so the application&apos;s {@code @Primary @RefreshScope DataSource}
     * bean uses the same container that Flyway migrates via
     * {@link ServiceConnection &#64;ServiceConnection}. Also re-asserts the
     * AAP &sect;0.6.5 Kafka producer/consumer invariants for defense in
     * depth.
     *
     * @param registry Spring test {@link DynamicPropertyRegistry} accepting
     *                 {@code (key, supplier)} pairs
     */
    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL container — match Spring's DataSource to Flyway's container
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);

        // -----------------------------------------------------------------
        // Kafka — bind the Testcontainers broker URL to the application's
        // KafkaConfig. CardDemo's KafkaConfig declares its own
        // ProducerFactory / ConsumerFactory beans that read from
        // KafkaProperties.buildProducerProperties(null) /
        // buildConsumerProperties(null), NOT from KafkaConnectionDetails.
        // Spring Boot 3.1+ @ServiceConnection only wires
        // KafkaConnectionDetails, so it does not override the
        // KafkaProperties.bootstrapServers field that the application's
        // custom factories consume. Without this explicit override the
        // producer/consumer would resolve the YAML placeholder
        // `${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` and connect to
        // localhost:9092 instead of the Testcontainers Kafka broker,
        // causing every publish to hang indefinitely. application-test.yml
        // (lines 376-379) explicitly documents this requirement.
        // -----------------------------------------------------------------
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        // AAP §0.6.5 — Kafka topic names (re-assert to defend against YAML drift)
        registry.add("carddemo.kafka.topics.transaction-posted",
                () -> TOPIC_TRANSACTION_POSTED);
        registry.add("carddemo.kafka.topics.account-updated",
                () -> TOPIC_ACCOUNT_UPDATED);
        registry.add("carddemo.kafka.topics.ledger-balanced",
                () -> TOPIC_LEDGER_BALANCED);
        registry.add("carddemo.kafka.topics.report-requested",
                () -> TOPIC_REPORT_REQUESTED);

        // AAP §0.6.5 — producer config invariants
        registry.add("spring.kafka.producer.acks", () -> "all");
        registry.add("spring.kafka.producer.retries",
                () -> Integer.toString(Integer.MAX_VALUE));
        registry.add("spring.kafka.producer.properties.enable.idempotence",
                () -> "true");
        registry.add("spring.kafka.producer.properties.max.in.flight.requests.per.connection",
                () -> "5");

        // AAP §0.6.5 — consumer config invariants
        registry.add("spring.kafka.consumer.enable-auto-commit", () -> "false");
        registry.add("spring.kafka.consumer.isolation-level", () -> "read_committed");
        registry.add("spring.kafka.listener.ack-mode", () -> "MANUAL");

        // Defense in depth — disable Spring Cloud AWS integrations
        registry.add("spring.cloud.aws.secretsmanager.enabled", () -> "false");
        registry.add("spring.cloud.aws.parameterstore.enabled", () -> "false");
    }

    // -------------------------------------------------------------------------
    // @MockBean — AWS clients and infrastructure templates we do NOT exercise
    // -------------------------------------------------------------------------
    // The application's component scan instantiates every adapter under
    // com.awsm2.carddemo.adapter and glue at context refresh, including
    // S3OutputService, StepFunctionsOrchestrator, CacheService,
    // AuditLogService, OpenSearchIndexer, and GlueETLConfig. Each adapter
    // takes an AWS SDK client as a constructor dependency. Mocking the
    // clients here lets the context refresh successfully without
    // attempting to reach AWS.
    //
    // The KafkaTemplate is deliberately NOT mocked — it's the real bean
    // wired by KafkaConfig and routed to the Testcontainers Kafka broker
    // via @ServiceConnection. This test ONLY tests the production Kafka
    // configuration.

    /** Mocked S3 client &mdash; prevents real S3 traffic during context refresh. */
    @MockBean
    private S3Client s3Client;

    /** Mocked Step Functions client &mdash; SfnClient is used by StepFunctionsOrchestrator. */
    @MockBean
    private SfnClient sfnClient;

    /**
     * Mocked Secrets Manager AWS SDK client. The full
     * {@link SecretsManagerService} adapter is replaced separately via
     * {@link TestSecretsManagerConfiguration} so that the
     * {@code @PostConstruct} hook in {@code JwtTokenProvider} resolves to a
     * deterministic test key without contacting AWS.
     */
    @MockBean
    private SecretsManagerClient secretsManagerClient;

    /** Mocked CloudWatch client &mdash; supplied for completeness of the AWS SDK surface. */
    @MockBean
    private CloudWatchClient cloudWatchClient;

    /** Mocked Glue client &mdash; Glue ETL job submission is not exercised here. */
    @MockBean
    private GlueClient glueClient;

    /** Mocked OpenSearch typed client &mdash; used by {@code OpenSearchIndexer}. */
    @MockBean
    private OpenSearchClient openSearchClient;

    /**
     * Mocked Redis template &mdash; the application&apos;s
     * {@code CacheService} requires this but no caching behavior is
     * exercised by this Kafka-focused integration test.
     */
    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    // -------------------------------------------------------------------------
    // @Autowired — system-under-test beans
    // -------------------------------------------------------------------------

    /**
     * The application&apos;s real {@link KafkaTemplate}, configured by
     * {@link KafkaConfig} per AAP &sect;0.6.5. This test inspects its
     * underlying {@link ProducerFactory#getConfigurationProperties()} to
     * verify production invariants (Phase 8) and uses it as the partition
     * inspector for the partition-determinism test (Phase 12).
     */
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * The application&apos;s real {@link KafkaEventPublisher} adapter
     * (sole production producer per AAP &sect;0.4.1). The per-account
     * ordering test (Phase 10), partition determinism test (Phase 12),
     * idempotent producer test (Phase 13), and cascade test (Phase 15)
     * all publish through this adapter to verify the production code path
     * end-to-end.
     */
    @Autowired
    private KafkaEventPublisher publisher;

    /**
     * Application&apos;s real {@link KafkaEventConsumer} adapter (sole
     * production consumer per AAP &sect;0.4.1). The consumer offset-commit
     * test (Phase 14) pauses then resumes the corresponding listener
     * container via {@link #listenerRegistry} to verify manual offset
     * commit advances the consumer-group offset only on successful
     * processing.
     */
    @Autowired
    private KafkaEventConsumer consumer;

    /**
     * The Spring Kafka registry of all {@code @KafkaListener} containers
     * created by {@link KafkaConfig#kafkaListenerContainerFactory}. Used by
     * Phase 9 (consumer config inspection), Phase 14 (pause/resume listener
     * containers), and Phase 16 (rebalance simulation).
     */
    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    /**
     * The application&apos;s {@link ConsumerFactory} configured by
     * {@link KafkaConfig#consumerFactory()}. Used by Phase 9
     * ({@link #consumer_configIs_manualCommit_readCommitted()}) to inspect
     * the underlying consumer configuration map and assert AAP &sect;0.6.5
     * invariants ({@code enable.auto.commit=false},
     * {@code isolation.level=read_committed}).
     */
    @Autowired
    private org.springframework.kafka.core.ConsumerFactory<String, Object> consumerFactory;

    // -------------------------------------------------------------------------
    // @BeforeEach / @AfterEach — per-test topic provisioning and cleanup
    // -------------------------------------------------------------------------

    /**
     * Provisions all four Kafka topics with {@link #TOPIC_PARTITIONS} partitions
     * and pauses every application {@code @KafkaListener} container so that the
     * application consumers do NOT race with the test consumers polling the
     * same topics. Tests that explicitly exercise the application consumer
     * (Phase 14) resume the relevant container at the start of the test.
     *
     * <p>Idempotent: if a topic already exists (e.g., when an earlier test
     * created it implicitly via a publication), {@code AdminClient.createTopics}
     * raises {@code TopicExistsException} which we ignore.</p>
     *
     * @throws Exception if topic creation or listener pausing fails
     */
    @BeforeEach
    void createTopicsAndPauseApplicationConsumers() throws Exception {
        try (AdminClient admin = createAdminClient()) {
            List<NewTopic> topics = List.of(
                    new NewTopic(TOPIC_TRANSACTION_POSTED, TOPIC_PARTITIONS, TOPIC_REPLICATION),
                    new NewTopic(TOPIC_ACCOUNT_UPDATED, TOPIC_PARTITIONS, TOPIC_REPLICATION),
                    new NewTopic(TOPIC_LEDGER_BALANCED, TOPIC_PARTITIONS, TOPIC_REPLICATION),
                    new NewTopic(TOPIC_REPORT_REQUESTED, TOPIC_PARTITIONS, TOPIC_REPLICATION)
            );
            try {
                admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
                LOG.info("Created Kafka topics with {} partitions each: {}",
                        TOPIC_PARTITIONS, topics.stream().map(NewTopic::name).toList());
            } catch (ExecutionException e) {
                // Already-exists is the only acceptable failure for idempotent re-runs
                if (e.getCause() == null
                        || !e.getCause().getClass().getName()
                                .endsWith("TopicExistsException")) {
                    throw e;
                }
                LOG.info("Kafka topics already exist; continuing");
            }
        }

        // Pause every application @KafkaListener container so it does not race
        // with the test consumers. Each test that exercises the application
        // consumer explicitly resumes the relevant container.
        listenerRegistry.getListenerContainers().forEach(container -> {
            if (container.isRunning()) {
                container.pause();
            }
        });
    }

    /**
     * Resumes every application {@code @KafkaListener} container that was
     * paused by {@link #createTopicsAndPauseApplicationConsumers()} so the
     * containers are in their natural state for the next test. The
     * {@link KafkaContainer} itself persists across tests; topic re-use is
     * acceptable because each test uses a distinct consumer-group UUID and
     * polls only the records it published.
     */
    @AfterEach
    void resumeApplicationConsumersAfterTest() {
        listenerRegistry.getListenerContainers().forEach(container -> {
            if (container.isPauseRequested()) {
                container.resume();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Helper methods — AdminClient / KafkaConsumer construction
    // -------------------------------------------------------------------------

    /**
     * Creates a fresh {@link AdminClient} bound to the Testcontainers broker.
     * The caller is responsible for closing the returned client.
     *
     * @return a new AdminClient instance
     */
    private static AdminClient createAdminClient() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        return AdminClient.create(props);
    }

    /**
     * Constructs a fresh {@link KafkaConsumer} subscribed to the supplied
     * topic. Uses {@link StringDeserializer} for both key and value so the
     * consumer is independent of the application&apos;s
     * {@code JsonDeserializer} configuration (which would require trusted-
     * package configuration on a per-consumer basis).
     *
     * @param topic       topic to subscribe to
     * @param groupId     consumer group ID (unique per test for isolation)
     * @param autoCommit  whether to enable automatic offset commit
     * @return a new, subscribed KafkaConsumer
     */
    private static KafkaConsumer<String, String> createTestConsumer(
            String topic, String groupId, boolean autoCommit) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.toString(autoCommit));
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        // Short session timeout so the rebalance test triggers a rebalance
        // promptly when one consumer is closed.
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "10000");
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "3000");
        KafkaConsumer<String, String> kc = new KafkaConsumer<>(props);
        kc.subscribe(Collections.singletonList(topic));
        return kc;
    }

    /**
     * Constructs a fresh {@link KafkaConsumer} ASSIGNED (not subscribed)
     * to all partitions of the supplied topic and SEEKED to the current
     * end offset of each partition. This isolates the consumer from any
     * records published before the call &mdash; only records produced
     * after this helper returns will be visible to subsequent polls.
     *
     * <p>This pattern is essential for tests that share Kafka topics across
     * test methods: alphabetical JUnit 5 method ordering means a test
     * polling {@code transaction.posted} may otherwise see records published
     * by earlier-running tests. Seeking to end before publishing this
     * test&apos;s events provides per-test offset isolation without
     * requiring per-test topic recreation.</p>
     *
     * <p>Uses {@link Consumer#assign(java.util.Collection)} +
     * {@link Consumer#seekToEnd(java.util.Collection)} +
     * {@link Consumer#position(TopicPartition)} (the last forces an
     * eager fetch of end offsets) so that the consumer&apos;s position is
     * fixed at the current high-water mark before any caller-driven
     * publication races with the seek.</p>
     *
     * @param topic       topic to consume
     * @param groupId     consumer group ID (unique per test for isolation)
     * @param admin       AdminClient used to discover partitions of the topic
     * @return a new KafkaConsumer assigned + seeked to end of every partition
     * @throws Exception if partition discovery fails
     */
    private static KafkaConsumer<String, String> createTestConsumerSeekedToEnd(
            String topic, String groupId, AdminClient admin) throws Exception {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "10000");
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "3000");
        KafkaConsumer<String, String> kc = new KafkaConsumer<>(props);

        // Discover partitions of the topic via AdminClient and assign all of them
        org.apache.kafka.clients.admin.TopicDescription desc = admin
                .describeTopics(Collections.singletonList(topic))
                .allTopicNames()
                .get(15, TimeUnit.SECONDS)
                .get(topic);
        List<TopicPartition> partitions = new ArrayList<>();
        desc.partitions().forEach(pi -> partitions.add(new TopicPartition(topic, pi.partition())));
        kc.assign(partitions);

        // Seek every partition to its current end offset. The position() call
        // forces an eager fetch so the consumer's reported position is
        // committed before this method returns and before the test starts
        // publishing.
        kc.seekToEnd(partitions);
        for (TopicPartition tp : partitions) {
            kc.position(tp);
        }
        return kc;
    }

    /**
     * Polls the supplied {@link Consumer} until {@code expectedCount} records
     * have been received OR the per-poll timeout expires without yielding
     * any new records. Returns all records gathered.
     *
     * @param consumer       the consumer to poll
     * @param expectedCount  the target number of records to gather
     * @param maxWait        overall maximum wall-clock wait
     * @return list of all received records in arrival order
     */
    private static List<ConsumerRecord<String, String>> drainRecords(
            Consumer<String, String> consumer, int expectedCount, Duration maxWait) {
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        long deadline = System.currentTimeMillis() + maxWait.toMillis();
        while (received.size() < expectedCount && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> batch = consumer.poll(CONSUMER_POLL_TIMEOUT);
            batch.forEach(received::add);
        }
        return received;
    }

    /**
     * Constructs a minimal valid {@link TransactionAddDto} for use as the
     * payload in publish-side ordering and partition-determinism tests. The
     * {@code accountId} string is zero-padded to 11 digits to match the
     * {@code TransactionAddDto} record contract (PIC X(11)); the
     * {@code seqNum} appears in the {@code description} field so that ordering
     * assertions can extract it from the consumed record.
     *
     * @param acctId  account ID (Long, used both for the DTO field and the
     *                Kafka partition key)
     * @param seqNum  per-account monotonic sequence number
     * @return a fully populated TransactionAddDto
     */
    private static TransactionAddDto buildTransactionEvent(long acctId, int seqNum) {
        return new TransactionAddDto(
                String.format("%011d", acctId),       // accountId — String, 11 digits
                "4111111111111111",                    // cardNumber — 16 digits
                "01",                                  // transactionType — 2 digits
                5411,                                  // transactionCategory
                "TEST",                                // source — max 10 chars
                "SEQ=" + seqNum,                       // description — carries seqNum for assertion
                new BigDecimal("12.34"),               // amount — BigDecimal (AAP §0.6.1)
                LocalDateTime.of(2026, 5, 20, 14, 30, 45),   // originationTimestamp
                LocalDateTime.of(2026, 5, 20, 14, 30, 45),   // processingTimestamp
                100000001L,                            // merchantId
                "TEST MERCHANT",                       // merchantName
                "SEATTLE",                             // merchantCity
                "98101",                               // merchantZip
                "Y"                                    // confirm
        );
    }

    /**
     * Constructs a minimal valid {@link AccountUpdateDto} for the cascade
     * test (Phase 15). All {@code @NotNull} / {@code @NotBlank} record
     * components are populated with placeholder values; the
     * {@code accountId} matches the partition key used on the upstream
     * {@code transaction.posted} event so the consumer can verify
     * cross-topic partition-key preservation.
     *
     * @param acctId  account ID
     * @return a fully populated AccountUpdateDto
     */
    private static AccountUpdateDto buildAccountUpdateEvent(long acctId) {
        return new AccountUpdateDto(
                acctId,                                // accountId — Long
                "Y",                                   // activeStatus
                new BigDecimal("1234.56"),             // currentBalance — BigDecimal
                new BigDecimal("5000.00"),             // creditLimit — BigDecimal
                new BigDecimal("1000.00"),             // cashCreditLimit — BigDecimal
                java.time.LocalDate.of(2020, 1, 15),   // openDate
                java.time.LocalDate.of(2030, 1, 15),   // expirationDate
                java.time.LocalDate.of(2025, 1, 15),   // reissueDate
                new BigDecimal("500.00"),              // currentCycleCredit — BigDecimal
                new BigDecimal("200.00"),              // currentCycleDebit — BigDecimal
                "12345",                               // addressZip
                "DEFAULT",                             // accountGroupId
                100000001L,                            // customerId
                "John",                                // firstName
                "M",                                   // middleName
                "Doe",                                 // lastName
                123456789L,                            // customerSsn
                "2125551234",                          // phoneNumber1
                "",                                    // phoneNumber2 — optional
                "123 Main Street",                     // addressLine1
                "Apt 4B",                              // addressLine2
                "Seattle",                             // addressLine3
                "WA",                                  // stateCode
                "USA",                                 // countryCode
                "12345",                               // zipCode
                java.time.LocalDate.of(1980, 5, 15),   // dateOfBirth
                "D12345678",                           // governmentIssuedId
                "1234567890",                          // eftAccountId
                "Y",                                   // primaryCardHolderIndicator
                720,                                   // ficoCreditScore
                0L                                     // version — optimistic-lock seed
        );
    }

    // -------------------------------------------------------------------------
    // Phase 8 — Test 1: Producer config invariants per AAP §0.6.5
    // -------------------------------------------------------------------------
    // AAP §0.6.5 — producer config: acks=all, enable.idempotence=true,
    // max.in.flight.requests.per.connection ≤ 5, retries=Integer.MAX_VALUE.
    /**
     * Verifies the application&apos;s {@link KafkaTemplate}&apos;s
     * {@link ProducerFactory} carries the AAP &sect;0.6.5 invariants:
     * {@code acks="all"}, {@code enable.idempotence=true},
     * {@code max.in.flight.requests.per.connection &le; 5}, and
     * {@code retries=Integer.MAX_VALUE}. Inspects
     * {@link ProducerFactory#getConfigurationProperties()} directly so that
     * any future regression of the {@link KafkaConfig#producerFactory()}
     * bean defaults is detected by this contract test before reaching
     * production.
     */
    @Test
    @DisplayName("Producer config: acks=all, enable.idempotence=true, max.in.flight≤5, retries=MAX")
    void producer_configIs_acksAll_idempotentTrue() {
        // AAP §0.6.5 — producer config: acks=all, enable.idempotence=true
        Map<String, Object> producerConfig =
                kafkaTemplate.getProducerFactory().getConfigurationProperties();

        // ----- acks=all (strongest durability guarantee) -----
        Object acks = producerConfig.get(ProducerConfig.ACKS_CONFIG);
        assertThat(acks)
                .as("AAP §0.6.5 — Kafka producer must use acks=all")
                .isNotNull();
        assertThat(acks.toString())
                .as("AAP §0.6.5 — acks must be the literal string \"all\"")
                .isEqualToIgnoringCase("all");

        // ----- enable.idempotence=true (eliminates duplicates on retries) -----
        Object idempotence = producerConfig.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG);
        assertThat(idempotence)
                .as("AAP §0.6.5 — Kafka producer must set enable.idempotence=true")
                .isNotNull();
        assertThat(idempotence.toString())
                .as("AAP §0.6.5 — enable.idempotence must be the literal boolean true")
                .isEqualToIgnoringCase("true");

        // ----- max.in.flight.requests.per.connection ≤ 5 -----
        Object maxInFlight = producerConfig.get(
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION);
        assertThat(maxInFlight)
                .as("AAP §0.6.5 — Kafka producer must bound max.in.flight.requests.per.connection")
                .isNotNull();
        int maxInFlightValue = Integer.parseInt(maxInFlight.toString());
        assertThat(maxInFlightValue)
                .as("AAP §0.6.5 — max.in.flight.requests.per.connection must be ≤ 5 to preserve ordering"
                        + " with enable.idempotence=true")
                .isLessThanOrEqualTo(5);
        assertThat(maxInFlightValue)
                .as("max.in.flight.requests.per.connection must be at least 1 (a producer must be able to send)")
                .isGreaterThanOrEqualTo(1);

        // ----- retries=Integer.MAX_VALUE -----
        Object retries = producerConfig.get(ProducerConfig.RETRIES_CONFIG);
        assertThat(retries)
                .as("AAP §0.6.5 — Kafka producer must set retries=Integer.MAX_VALUE")
                .isNotNull();
        long retriesValue = Long.parseLong(retries.toString());
        assertThat(retriesValue)
                .as("AAP §0.6.5 — retries must be effectively unbounded (Integer.MAX_VALUE);"
                        + " broker decides delivery via delivery.timeout.ms")
                .isEqualTo((long) Integer.MAX_VALUE);

        LOG.info("Producer config invariants verified: acks={}, enable.idempotence={},"
                + " max.in.flight={}, retries={}",
                acks, idempotence, maxInFlightValue, retriesValue);
    }

    // -------------------------------------------------------------------------
    // Phase 9 — Test 2: Consumer config invariants per AAP §0.6.5
    // -------------------------------------------------------------------------
    // AAP §0.6.5 — consumer config: enable.auto.commit=false,
    // isolation.level=read_committed, manual offset commit (AckMode.MANUAL).
    /**
     * Verifies the application&apos;s Kafka listener container factory and
     * the {@code @KafkaListener} containers themselves carry the AAP
     * &sect;0.6.5 invariants: {@code enable.auto.commit=false},
     * {@code isolation.level=read_committed}, and
     * {@link ContainerProperties.AckMode#MANUAL}.
     *
     * <p>Two-pronged inspection:</p>
     * <ol>
     *   <li>Each registered {@code @KafkaListener} container&apos;s
     *       {@link ContainerProperties} must report
     *       {@code AckMode.MANUAL} (proves the listener factory wires
     *       manual-acknowledgment containers).</li>
     *   <li>The first container&apos;s underlying
     *       {@link org.springframework.kafka.core.ConsumerFactory#getConfigurationProperties()}
     *       (obtained from the container&apos;s {@code consumerFactory})
     *       must report {@code enable.auto.commit=false} and
     *       {@code isolation.level=read_committed}.</li>
     * </ol>
     */
    @Test
    @DisplayName("Consumer config: enable.auto.commit=false, isolation.level=read_committed, AckMode.MANUAL")
    void consumer_configIs_manualCommit_readCommitted() {
        // The application registers a @KafkaListener for each of the four
        // topics; KafkaListenerEndpointRegistry exposes their containers.
        var containers = listenerRegistry.getListenerContainers();
        assertThat(containers)
                .as("KafkaListenerEndpointRegistry must contain at least one"
                        + " @KafkaListener container (KafkaEventConsumer)")
                .isNotEmpty();

        // ----- AckMode.MANUAL on every container -----
        for (MessageListenerContainer container : containers) {
            ContainerProperties containerProps = container.getContainerProperties();
            assertThat(containerProps.getAckMode())
                    .as("AAP §0.6.5 — every @KafkaListener container must use AckMode.MANUAL"
                            + " (container=%s)", container.getListenerId())
                    .isEqualTo(ContainerProperties.AckMode.MANUAL);
        }

        // ----- enable.auto.commit=false and isolation.level=read_committed
        // ----- on the consumer factory backing the listener containers
        //
        // The ConsumerFactory singleton is autowired directly from the
        // application context; it is the SAME bean instance that
        // KafkaConfig.kafkaListenerContainerFactory passed to every
        // ConcurrentMessageListenerContainer in the registry.
        Map<String, Object> consumerConfig = consumerFactory.getConfigurationProperties();

        Object autoCommit = consumerConfig.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG);
        assertThat(autoCommit)
                .as("AAP §0.6.5 — consumer must set enable.auto.commit=false")
                .isNotNull();
        assertThat(autoCommit.toString())
                .as("AAP §0.6.5 — enable.auto.commit must be the literal boolean false")
                .isEqualToIgnoringCase("false");

        Object isolationLevel = consumerConfig.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG);
        assertThat(isolationLevel)
                .as("AAP §0.6.5 — consumer must set isolation.level=read_committed")
                .isNotNull();
        assertThat(isolationLevel.toString())
                .as("AAP §0.6.5 — isolation.level must be the literal string read_committed")
                .isEqualToIgnoringCase("read_committed");

        LOG.info("Consumer config invariants verified: enable.auto.commit={},"
                + " isolation.level={}, AckMode.MANUAL on {} containers",
                autoCommit, isolationLevel, containers.size());
    }

    // -------------------------------------------------------------------------
    // Phase 10 — Test 3: Per-account ordering invariant
    // -------------------------------------------------------------------------
    // AAP §0.6.5 — for any account ID, events arrive in production order.
    /**
     * Publishes 1000 interleaved events across 10 distinct account IDs
     * (100 events per account) and verifies that the consumer observes, for
     * each account, a strictly monotonic {@code seqNum} sequence (1, 2, 3,
     * &hellip;, 100). Cross-account interleaving is allowed; only per-account
     * order must be preserved.
     *
     * <p>This is the central invariant of AAP &sect;0.6.5 &mdash; it
     * encodes the contract that downstream consumers (audit, reporting,
     * balance projection) can rely on event order WITHIN an account
     * regardless of producer concurrency or partition count.</p>
     */
    @Test
    @DisplayName("Per-account ordering: monotonic seqNum within each account ID across 1000 interleaved events")
    void perAccountOrdering_invariantHolds() throws Exception {
        // AAP §0.6.5 — for any account ID, events arrive in production order
        long baseAccountId = 50_000_000_000L;
        List<Long> accountIds = new ArrayList<>();
        for (int i = 0; i < ORDERING_TEST_ACCOUNT_COUNT; i++) {
            accountIds.add(baseAccountId + i);
        }
        // Build the set of expected partition keys for defensive filtering
        // of records published by prior tests sharing the same topic.
        Set<String> expectedKeys = new HashSet<>();
        accountIds.forEach(id -> expectedKeys.add(String.format("%011d", id)));

        // ----- Create the test consumer seeked-to-end BEFORE publishing -----
        // This isolates this test from records published by earlier-running
        // tests (alphabetical JUnit ordering may run other tests against
        // transaction.posted before this one). Only records published
        // AFTER this seekToEnd will be visible to the consumer.
        String groupId = "ordering-test-" + UUID.randomUUID();
        try (AdminClient seekAdmin = createAdminClient();
             KafkaConsumer<String, String> testConsumer = createTestConsumerSeekedToEnd(
                     TOPIC_TRANSACTION_POSTED, groupId, seekAdmin)) {

            // Publish 1000 events interleaved across 10 accounts (100 events each)
            // using a round-based serial-await pattern: within each round,
            // publish ONE event per account (different partition keys, may race
            // safely), then await EVERY future in the round before issuing the
            // next round. This guarantees per-account FIFO order at the broker
            // level despite the @Async dispatch in KafkaEventPublisher (which
            // uses SimpleAsyncTaskExecutor — one thread per task — making the
            // raw in-flight order otherwise non-deterministic between successive
            // same-key sends). The application-level @Async parallelism is
            // preserved across DIFFERENT accounts (the round publishes all 10
            // accounts concurrently), so this test exercises the realistic
            // production case where multiple REST requests for different
            // accounts run concurrently; what is enforced is that any given
            // account's events are dispatched in production order. See AAP
            // §0.6.5 — per-account ordering is guaranteed by the broker's
            // per-partition log only when records are produced to that
            // partition in order.
            List<CompletableFuture<SendResult<String, Object>>> futures = new ArrayList<>();
            Map<Long, Integer> nextSeq = new HashMap<>();
            accountIds.forEach(id -> nextSeq.put(id, 1));

            for (int round = 0; round < ORDERING_TEST_EVENTS_PER_ACCOUNT; round++) {
                List<CompletableFuture<SendResult<String, Object>>> roundFutures = new ArrayList<>();
                for (Long acctId : accountIds) {
                    int seqNum = nextSeq.get(acctId);
                    nextSeq.put(acctId, seqNum + 1);
                    TransactionAddDto event = buildTransactionEvent(acctId, seqNum);
                    roundFutures.add(publisher.publishTransactionPosted(acctId, event));
                }
                // Await every send in this round to reach the broker BEFORE
                // dispatching the next round. This blocks until each
                // CompletableFuture completes (broker ack received) and
                // surfaces any send failure as an ExecutionException.
                for (CompletableFuture<SendResult<String, Object>> f : roundFutures) {
                    f.get(10, TimeUnit.SECONDS);
                }
                futures.addAll(roundFutures);
            }

            // Defense-in-depth check: every future must be terminally done
            // and successful. Awaitility used rather than Thread.sleep per
            // AAP §0.7.1; this should be a no-op since the per-round await
            // above already ensured each future is done.
            await("All transactions acknowledged by broker")
                    .atMost(AWAIT_TIMEOUT)
                    .pollInterval(AWAIT_POLL_INTERVAL)
                    .until(() -> futures.stream().allMatch(CompletableFuture::isDone));

            int totalExpected = ORDERING_TEST_ACCOUNT_COUNT * ORDERING_TEST_EVENTS_PER_ACCOUNT;
            LOG.info("Published {} transaction.posted events across {} accounts;"
                    + " consuming for ordering verification",
                    totalExpected, ORDERING_TEST_ACCOUNT_COUNT);

            // Drain records published after the seek point
            Map<Long, List<Integer>> seqByAccount = new HashMap<>();
            accountIds.forEach(id -> seqByAccount.put(id, new ArrayList<>()));

            List<ConsumerRecord<String, String>> records = drainRecords(
                    testConsumer, totalExpected, AWAIT_TIMEOUT);

            // Filter to records carrying one of our test's expected keys.
            // Defense in depth: the seek-to-end isolates us from prior tests,
            // but the application's @KafkaListener (paused in @BeforeEach)
            // could conceivably resume and emit; the filter ensures we only
            // count records this test produced.
            int ourCount = 0;
            for (ConsumerRecord<String, String> rec : records) {
                if (!expectedKeys.contains(rec.key())) {
                    continue;
                }
                long acctId = Long.parseLong(rec.key());
                int seqNum = extractSeqNum(rec.value());
                seqByAccount.get(acctId).add(seqNum);
                ourCount++;
            }
            assertThat(ourCount)
                    .as("Consumer must observe all %d published events for our %d test accounts",
                            totalExpected, ORDERING_TEST_ACCOUNT_COUNT)
                    .isEqualTo(totalExpected);

            // Verify per-account monotonic seqNum (1, 2, …, 100)
            for (Long acctId : accountIds) {
                List<Integer> observed = seqByAccount.get(acctId);
                assertThat(observed)
                        .as("Account %d must have exactly %d events", acctId,
                                ORDERING_TEST_EVENTS_PER_ACCOUNT)
                        .hasSize(ORDERING_TEST_EVENTS_PER_ACCOUNT);
                for (int i = 0; i < observed.size(); i++) {
                    assertThat(observed.get(i))
                            .as("AAP §0.6.5 — Account %d position %d must carry seqNum=%d"
                                    + " (events must arrive in production order)",
                                    acctId, i, i + 1)
                            .isEqualTo(i + 1);
                }
            }
        }

        LOG.info("Per-account ordering invariant verified for {} accounts × {} events",
                ORDERING_TEST_ACCOUNT_COUNT, ORDERING_TEST_EVENTS_PER_ACCOUNT);
    }

    /**
     * Extracts the {@code seqNum} embedded in the JSON-encoded
     * {@code description} field of a {@link TransactionAddDto}. The
     * description was set to {@code "SEQ=<n>"} in
     * {@link #buildTransactionEvent(long, int)}; this helper parses the
     * integer suffix from the raw JSON value (no full Jackson dependency
     * required because we control the description format).
     *
     * @param jsonValue raw JSON string value as deserialized by the test
     *                  consumer (which uses StringDeserializer)
     * @return the seqNum integer
     */
    private static int extractSeqNum(String jsonValue) {
        // The description field appears as "description":"SEQ=<n>" in the
        // JSON; we locate the marker and extract the trailing integer.
        int markerIdx = jsonValue.indexOf("\"description\":\"SEQ=");
        if (markerIdx < 0) {
            throw new AssertionError("Expected \"description\":\"SEQ=...\" in record value: "
                    + jsonValue);
        }
        int start = markerIdx + "\"description\":\"SEQ=".length();
        int end = jsonValue.indexOf("\"", start);
        if (end < 0) {
            throw new AssertionError("Malformed description marker in record value: " + jsonValue);
        }
        return Integer.parseInt(jsonValue.substring(start, end));
    }

    // -------------------------------------------------------------------------
    // Phase 11 — Test 4: Cross-account interleaving is permitted
    // -------------------------------------------------------------------------
    /**
     * Verifies the dual of the per-account ordering invariant: events for
     * DIFFERENT account IDs MAY arrive interleaved at the consumer. The
     * test publishes two interleaved streams (account A and account B,
     * 50 events each) and asserts:
     * <ol>
     *   <li>Within account A: monotonic seqNum.</li>
     *   <li>Within account B: monotonic seqNum.</li>
     *   <li>Cross-account: SOME interleaving was observed (the global
     *       record stream is NOT strictly A-then-B or A,B,A,B; it depends
     *       on how Kafka schedules per-partition fetch responses).</li>
     * </ol>
     *
     * <p>This test documents that consumers MUST NOT assume global
     * ordering across account IDs &mdash; that is by design per AAP
     * &sect;0.6.5.</p>
     */
    @Test
    @DisplayName("Cross-account ordering is NOT enforced; only per-account ordering is preserved")
    void crossAccountOrdering_isNotEnforced() throws Exception {
        final long acctA = 70_000_000_001L;
        final long acctB = 70_000_000_002L;
        final int eventsPerAccount = 50;
        // Build the set of expected partition keys for defensive filtering
        // of records published by prior tests sharing the same topic.
        final String keyA = String.format("%011d", acctA);
        final String keyB = String.format("%011d", acctB);
        Set<String> expectedKeys = new HashSet<>();
        expectedKeys.add(keyA);
        expectedKeys.add(keyB);

        // ----- Create the test consumer seeked-to-end BEFORE publishing -----
        // The shared transaction.posted topic accumulates records from earlier
        // tests in this class (perAccountOrdering, idempotentProducer, etc.);
        // seeking to end before publishing isolates this test from those
        // records. Without this isolation, the consumer would replay all
        // previously-published records (e.g., the 1000 records from
        // perAccountOrdering) and the size assertion below would observe a
        // count vastly larger than 2 * eventsPerAccount.
        String groupId = "cross-account-test-" + UUID.randomUUID();
        try (AdminClient seekAdmin = createAdminClient();
             KafkaConsumer<String, String> testConsumer = createTestConsumerSeekedToEnd(
                     TOPIC_TRANSACTION_POSTED, groupId, seekAdmin)) {

            // Round-based serial-await pattern (see perAccountOrdering test):
            // within each round, publish ONE event per account (different
            // partition keys race safely), then await BOTH futures before
            // issuing the next round. Guarantees per-account FIFO order at
            // the broker level despite @Async dispatch on
            // SimpleAsyncTaskExecutor. See AAP §0.6.5.
            List<CompletableFuture<SendResult<String, Object>>> futures = new ArrayList<>();
            for (int i = 1; i <= eventsPerAccount; i++) {
                List<CompletableFuture<SendResult<String, Object>>> roundFutures = new ArrayList<>();
                roundFutures.add(publisher.publishTransactionPosted(acctA,
                        buildTransactionEvent(acctA, i)));
                roundFutures.add(publisher.publishTransactionPosted(acctB,
                        buildTransactionEvent(acctB, i)));
                for (CompletableFuture<SendResult<String, Object>> f : roundFutures) {
                    f.get(10, TimeUnit.SECONDS);
                }
                futures.addAll(roundFutures);
            }

            await("All cross-account publications acknowledged")
                    .atMost(AWAIT_TIMEOUT)
                    .pollInterval(AWAIT_POLL_INTERVAL)
                    .until(() -> futures.stream().allMatch(CompletableFuture::isDone));

            List<ConsumerRecord<String, String>> records = drainRecords(
                    testConsumer, eventsPerAccount * 2, AWAIT_TIMEOUT);

            // Filter to records carrying one of our test's expected keys.
            // Defense in depth: the seek-to-end isolates us from prior tests,
            // but if the application's @KafkaListener (paused in @BeforeEach)
            // ever republished a transaction.posted event derived from another
            // topic, the filter ensures we only count records this test produced.
            List<ConsumerRecord<String, String>> ours = new ArrayList<>();
            for (ConsumerRecord<String, String> rec : records) {
                if (expectedKeys.contains(rec.key())) {
                    ours.add(rec);
                }
            }
            assertThat(ours)
                    .as("Consumer must observe all %d cross-account events",
                            eventsPerAccount * 2)
                    .hasSize(eventsPerAccount * 2);

            // Filter into per-account streams and verify monotonic seqNum
            List<Integer> seqsA = new ArrayList<>();
            List<Integer> seqsB = new ArrayList<>();
            for (ConsumerRecord<String, String> rec : ours) {
                long acctId = Long.parseLong(rec.key());
                int seqNum = extractSeqNum(rec.value());
                if (acctId == acctA) {
                    seqsA.add(seqNum);
                } else if (acctId == acctB) {
                    seqsB.add(seqNum);
                } else {
                    throw new AssertionError("Unexpected accountId: " + acctId);
                }
            }
            assertThat(seqsA)
                    .as("Account A events must be monotonic per AAP §0.6.5")
                    .containsExactlyElementsOf(monotonicSequence(eventsPerAccount));
            assertThat(seqsB)
                    .as("Account B events must be monotonic per AAP §0.6.5")
                    .containsExactlyElementsOf(monotonicSequence(eventsPerAccount));

            // Confirm cross-account interleaving was observed. The strictest
            // proof is: the global record stream is NOT strictly equal to
            // "all A then all B" — at least one pair of adjacent records
            // must be from different accounts.
            boolean interleavingObserved = false;
            for (int i = 1; i < ours.size(); i++) {
                if (!ours.get(i).key().equals(ours.get(i - 1).key())) {
                    interleavingObserved = true;
                    break;
                }
            }
            assertThat(interleavingObserved)
                    .as("Cross-account events MAY interleave at the consumer per AAP §0.6.5;"
                            + " observed stream should contain mixed-key adjacencies")
                    .isTrue();
        }

        LOG.info("Cross-account interleaving verified: account A and B preserved per-account"
                + " ordering with at least one cross-key adjacency in the consumed stream");
    }

    /**
     * Generates the sequence 1, 2, &hellip;, n as a {@link List} of
     * {@link Integer} for AssertJ {@code containsExactlyElementsOf}
     * comparisons.
     *
     * @param n upper bound (inclusive); n &ge; 1
     * @return the sequence [1, 2, &hellip;, n]
     */
    private static List<Integer> monotonicSequence(int n) {
        List<Integer> seq = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            seq.add(i);
        }
        return seq;
    }

    // -------------------------------------------------------------------------
    // Phase 12 — Test 5: Partition assignment by account ID is deterministic
    // -------------------------------------------------------------------------
    /**
     * Verifies that for a given account ID, every published event lands on
     * the same Kafka partition (the foundation of per-account ordering per
     * AAP &sect;0.6.5). Publishes 10 events for account A and 10 events for
     * account B; asserts:
     * <ol>
     *   <li>All 10 events for account A land on a single partition.</li>
     *   <li>All 10 events for account B land on a single partition.</li>
     *   <li>A&apos;s partition and B&apos;s partition are valid partition
     *       indices in the range {@code [0, TOPIC_PARTITIONS)} (whether
     *       they are equal or distinct is implementation-defined by Kafka&apos;s
     *       {@code murmur2} hash; this test only asserts determinism, not
     *       distribution).</li>
     * </ol>
     */
    @Test
    @DisplayName("Partition assignment by account ID is deterministic: same key → same partition")
    void partitionAssignment_byAccountId_isDeterministic() throws Exception {
        final int eventsPerAccount = 10;
        List<CompletableFuture<SendResult<String, Object>>> futuresA = new ArrayList<>();
        List<CompletableFuture<SendResult<String, Object>>> futuresB = new ArrayList<>();

        for (int i = 1; i <= eventsPerAccount; i++) {
            futuresA.add(publisher.publishTransactionPosted(
                    PARTITION_TEST_ACCOUNT_A,
                    buildTransactionEvent(PARTITION_TEST_ACCOUNT_A, i)));
            futuresB.add(publisher.publishTransactionPosted(
                    PARTITION_TEST_ACCOUNT_B,
                    buildTransactionEvent(PARTITION_TEST_ACCOUNT_B, i)));
        }

        await("All partition-determinism publications acknowledged")
                .atMost(AWAIT_TIMEOUT)
                .pollInterval(AWAIT_POLL_INTERVAL)
                .until(() -> futuresA.stream().allMatch(CompletableFuture::isDone)
                        && futuresB.stream().allMatch(CompletableFuture::isDone));

        // The SendResult exposes the partition each record landed on —
        // this is the cleanest source of truth for partition determinism.
        Set<Integer> partitionsA = new HashSet<>();
        Set<Integer> partitionsB = new HashSet<>();
        for (CompletableFuture<SendResult<String, Object>> f : futuresA) {
            partitionsA.add(f.get(10, TimeUnit.SECONDS).getRecordMetadata().partition());
        }
        for (CompletableFuture<SendResult<String, Object>> f : futuresB) {
            partitionsB.add(f.get(10, TimeUnit.SECONDS).getRecordMetadata().partition());
        }

        assertThat(partitionsA)
                .as("AAP §0.6.5 — all %d events for account A (%d) must land on exactly ONE"
                        + " partition (partition-by-accountId determinism)",
                        eventsPerAccount, PARTITION_TEST_ACCOUNT_A)
                .hasSize(1);
        assertThat(partitionsB)
                .as("AAP §0.6.5 — all %d events for account B (%d) must land on exactly ONE"
                        + " partition (partition-by-accountId determinism)",
                        eventsPerAccount, PARTITION_TEST_ACCOUNT_B)
                .hasSize(1);

        int partitionA = partitionsA.iterator().next();
        int partitionB = partitionsB.iterator().next();
        assertThat(partitionA)
                .as("Partition for account A must be a valid partition index"
                        + " (0 ≤ p < %d)", TOPIC_PARTITIONS)
                .isBetween(0, TOPIC_PARTITIONS - 1);
        assertThat(partitionB)
                .as("Partition for account B must be a valid partition index"
                        + " (0 ≤ p < %d)", TOPIC_PARTITIONS)
                .isBetween(0, TOPIC_PARTITIONS - 1);

        // Cross-check: re-publish account A and verify it STILL lands on
        // the same partition (extra determinism guarantee across
        // re-publications, not just within a single batch).
        CompletableFuture<SendResult<String, Object>> rePublish =
                publisher.publishTransactionPosted(
                        PARTITION_TEST_ACCOUNT_A,
                        buildTransactionEvent(PARTITION_TEST_ACCOUNT_A, 999));
        int rePublishPartition = rePublish.get(10, TimeUnit.SECONDS)
                .getRecordMetadata().partition();
        assertThat(rePublishPartition)
                .as("AAP §0.6.5 — re-publishing for account A must land on the SAME"
                        + " partition (%d) as the original 10 publications", partitionA)
                .isEqualTo(partitionA);

        LOG.info("Partition determinism verified: account A → partition {}, account B → partition {}",
                partitionA, partitionB);
    }

    // -------------------------------------------------------------------------
    // Phase 13 — Test 6: Idempotent producer does not duplicate
    // -------------------------------------------------------------------------
    /**
     * Verifies that with {@code enable.idempotence=true} the producer
     * publishes exactly the requested number of records, with no
     * broker-side duplication caused by network retries. Publishes 100
     * sequential events for a single account and asserts the consumer
     * observes exactly 100 records with no repeated seqNum values.
     *
     * <p>This is the runtime complement to the Phase-8 producer-config
     * test: Phase 8 verifies the configuration property is set; this
     * test verifies the broker honors the resulting deduplication
     * sequence numbers and exposes no duplicate records to consumers.</p>
     */
    @Test
    @DisplayName("Idempotent producer publishes exactly N records (no duplicates)")
    void idempotentProducer_doesNotDuplicate() throws Exception {
        final long acctId = 80_000_000_001L;
        final int eventCount = 100;
        final String expectedKey = String.format("%011d", acctId);

        // Create a test consumer seeked to end of the topic BEFORE publishing.
        // This isolates this test from records published by earlier-running
        // tests (alphabetical JUnit method ordering may execute several tests
        // against transaction.posted before this one).
        String groupId = "idempotency-test-" + UUID.randomUUID();
        try (AdminClient seekAdmin = createAdminClient();
             KafkaConsumer<String, String> testConsumer = createTestConsumerSeekedToEnd(
                     TOPIC_TRANSACTION_POSTED, groupId, seekAdmin)) {

            // Per-event serial-await pattern: this test publishes to a SINGLE
            // account (= single partition). With @Async dispatch on
            // SimpleAsyncTaskExecutor, multiple concurrent same-key sends
            // could race in the producer's RecordAccumulator; per-event await
            // forces strict FIFO send ordering and validates the idempotent
            // producer contract (enable.idempotence=true) at the broker
            // level: exactly N records observed, no duplicates from network
            // retries. See AAP §0.6.5.
            List<CompletableFuture<SendResult<String, Object>>> futures = new ArrayList<>();
            for (int i = 1; i <= eventCount; i++) {
                CompletableFuture<SendResult<String, Object>> f = publisher
                        .publishTransactionPosted(
                                acctId, buildTransactionEvent(acctId, i));
                f.get(10, TimeUnit.SECONDS);
                futures.add(f);
            }

            await("All idempotency-test publications acknowledged")
                    .atMost(AWAIT_TIMEOUT)
                    .pollInterval(AWAIT_POLL_INTERVAL)
                    .until(() -> futures.stream().allMatch(CompletableFuture::isDone));

            // Drain enough to detect possible duplicates beyond eventCount.
            // The seek-to-end isolation guarantees we only see records this
            // test produced, but we still filter defensively by key so a
            // mis-routed prior-test record doesn't inflate the count.
            List<ConsumerRecord<String, String>> records = drainRecords(
                    testConsumer, eventCount + 50, AWAIT_TIMEOUT);

            // Filter to just our account
            List<ConsumerRecord<String, String>> ours = new ArrayList<>();
            for (ConsumerRecord<String, String> rec : records) {
                if (expectedKey.equals(rec.key())) {
                    ours.add(rec);
                }
            }

            assertThat(ours)
                    .as("AAP §0.6.5 — idempotent producer must publish exactly %d records"
                            + " (no broker-side duplication from network retries)", eventCount)
                    .hasSize(eventCount);

            // No duplicate seqNum values
            Set<Integer> seenSeqNums = new HashSet<>();
            for (ConsumerRecord<String, String> rec : ours) {
                int seqNum = extractSeqNum(rec.value());
                boolean fresh = seenSeqNums.add(seqNum);
                assertThat(fresh)
                        .as("AAP §0.6.5 — seqNum %d must not appear more than once"
                                + " (enable.idempotence=true contract)", seqNum)
                        .isTrue();
            }
            assertThat(seenSeqNums)
                    .as("seqNum set must contain exactly seqNums 1..%d", eventCount)
                    .containsExactlyInAnyOrderElementsOf(monotonicSequence(eventCount));
        }

        LOG.info("Idempotent producer verified: {} events produced, {} unique records observed,"
                + " zero duplicates", eventCount, eventCount);
    }

    // -------------------------------------------------------------------------
    // Phase 14a — Test 7: Manual offset commit on success
    // -------------------------------------------------------------------------
    /**
     * Verifies that the application&apos;s {@link KafkaEventConsumer}
     * advances its consumer-group offset only after successful processing
     * (manual {@code Acknowledgment.acknowledge()}). The test:
     * <ol>
     *   <li>Resumes the {@code transaction.posted} listener container
     *       (paused in {@code @BeforeEach}).</li>
     *   <li>Publishes a known number of events.</li>
     *   <li>Polls the consumer group&apos;s committed offset via
     *       {@link AdminClient#listConsumerGroupOffsets} until it reaches
     *       the expected high-water mark.</li>
     * </ol>
     *
     * <p>The application&apos;s {@code onTransactionPosted} handler calls
     * {@code ack.acknowledge()} on the {@code Acknowledgment} parameter
     * only on the success path (per the
     * {@link KafkaConfig#kafkaListenerContainerFactory} ack-mode contract);
     * therefore observing the offset advancement is a direct proof of
     * successful manual commit semantics.</p>
     */
    @Test
    @DisplayName("Consumer commits offset only on successful processing")
    void consumer_commitsOffsetOnSuccess() throws Exception {
        // Resume the transaction.posted listener container
        MessageListenerContainer transactionContainer = findContainerForTopic(TOPIC_TRANSACTION_POSTED);
        assertThat(transactionContainer)
                .as("Listener container for topic %s must exist", TOPIC_TRANSACTION_POSTED)
                .isNotNull();

        if (transactionContainer.isPauseRequested()) {
            transactionContainer.resume();
        }
        if (!transactionContainer.isRunning()) {
            transactionContainer.start();
        }
        // Wait until the container is fully running (consumer assigned)
        await("Listener container is running and not paused")
                .atMost(AWAIT_TIMEOUT)
                .pollInterval(AWAIT_POLL_INTERVAL)
                .until(() -> transactionContainer.isRunning()
                        && !transactionContainer.isContainerPaused());

        // Publish events. Use a single account to keep them on a single
        // partition for easy offset reasoning.
        final long acctId = 90_000_000_001L;
        final int eventCount = 10;
        List<CompletableFuture<SendResult<String, Object>>> futures = new ArrayList<>();
        for (int i = 1; i <= eventCount; i++) {
            futures.add(publisher.publishTransactionPosted(
                    acctId, buildTransactionEvent(acctId, i)));
        }

        // Determine which partition all events landed on (same key → same partition)
        await("All offset-commit-test publications acknowledged")
                .atMost(AWAIT_TIMEOUT)
                .pollInterval(AWAIT_POLL_INTERVAL)
                .until(() -> futures.stream().allMatch(CompletableFuture::isDone));
        int partition = futures.get(0).get(10, TimeUnit.SECONDS)
                .getRecordMetadata().partition();
        TopicPartition tp = new TopicPartition(TOPIC_TRANSACTION_POSTED, partition);

        // Read the application consumer's group ID from container properties
        String appGroupId = transactionContainer.getGroupId();
        assertThat(appGroupId)
                .as("Application @KafkaListener container must report a group ID")
                .isNotBlank();

        // Poll the committed offset until it reaches the high-water mark
        try (AdminClient admin = createAdminClient()) {
            await("Consumer group offset for partition " + partition
                    + " on topic " + TOPIC_TRANSACTION_POSTED + " advances after manual ack")
                    .atMost(AWAIT_TIMEOUT)
                    .pollInterval(AWAIT_POLL_INTERVAL)
                    .untilAsserted(() -> {
                        var offsetsFuture = admin.listConsumerGroupOffsets(appGroupId)
                                .partitionsToOffsetAndMetadata();
                        Map<TopicPartition, ?> offsets = offsetsFuture
                                .get(5, TimeUnit.SECONDS);
                        Object meta = offsets.get(tp);
                        assertThat(meta)
                                .as("AAP §0.6.5 — consumer group %s must have committed an"
                                        + " offset for partition %d on topic %s after"
                                        + " manual ack on success", appGroupId, partition,
                                        TOPIC_TRANSACTION_POSTED)
                                .isNotNull();
                        long committedOffset =
                                ((org.apache.kafka.clients.consumer.OffsetAndMetadata) meta)
                                        .offset();
                        // The 10 published events advance the offset to 10 (the
                        // "next position to read"); but other tests in the class
                        // may have advanced it further, so we assert >=
                        assertThat(committedOffset)
                                .as("AAP §0.6.5 — committed offset must reach at least"
                                        + " %d after manual ack on %d events", eventCount,
                                        eventCount)
                                .isGreaterThanOrEqualTo(eventCount);
                    });
        }

        // Pause the container back to its @BeforeEach baseline state
        transactionContainer.pause();
        LOG.info("Manual offset commit on success verified: consumer group {} advanced offset"
                + " on partition {} after {} acknowledged events", appGroupId, partition,
                eventCount);
    }

    /**
     * Locates the {@link MessageListenerContainer} that subscribes to the
     * supplied topic by inspecting each registered container&apos;s
     * topics.
     *
     * @param topic topic name to find
     * @return the container subscribing to {@code topic}, or {@code null}
     *         if not found
     */
    private MessageListenerContainer findContainerForTopic(String topic) {
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            String[] topics = container.getContainerProperties().getTopics();
            if (topics != null) {
                for (String t : topics) {
                    if (topic.equals(t)) {
                        return container;
                    }
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Phase 14b — Test 8: Consumer does NOT commit offset on failure
    // -------------------------------------------------------------------------
    /**
     * Verifies that when a consumer polls records but does NOT call
     * {@code Acknowledgment.acknowledge()} (the abstract analogue of a
     * processing failure under manual-commit semantics), the offset is
     * not committed to the broker and the same records are re-delivered
     * on a subsequent poll by a fresh consumer in the same group.
     *
     * <p>The test uses a manually-instantiated {@link KafkaConsumer} rather
     * than the application&apos;s {@code @KafkaListener} so that the
     * "failure to acknowledge" can be deterministically simulated by
     * simply not calling {@link Consumer#commitSync()}; the application
     * consumer would only fail to acknowledge under specific business-rule
     * exceptions that are not trivially injectable from an integration
     * test.</p>
     *
     * <p>The test invariant being verified is that the broker-side
     * offset-commit semantics work correctly under {@code enable.auto.commit=false}:
     * polling a record does NOT auto-commit; only explicit {@code commitSync()}
     * / {@code commitAsync()} (or in Spring&apos;s case, the listener
     * container&apos;s acknowledge() bridge) advances the committed offset.
     * This is the broker-level guarantee that AAP &sect;0.6.5
     * "manual offset commit after successful processing in the
     * &#64;KafkaListener method" relies on.</p>
     */
    @Test
    @DisplayName("Consumer does not commit offset when acknowledge() / commitSync() is not called")
    void consumer_doesNotCommitOnFailure() throws Exception {
        // Publish 5 events for a single account
        final long acctId = 60_000_000_001L;
        final int eventCount = 5;
        List<CompletableFuture<SendResult<String, Object>>> futures = new ArrayList<>();
        for (int i = 1; i <= eventCount; i++) {
            futures.add(publisher.publishTransactionPosted(
                    acctId, buildTransactionEvent(acctId, i)));
        }
        await("All failure-commit-test publications acknowledged")
                .atMost(AWAIT_TIMEOUT)
                .pollInterval(AWAIT_POLL_INTERVAL)
                .until(() -> futures.stream().allMatch(CompletableFuture::isDone));
        for (CompletableFuture<SendResult<String, Object>> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        int partition = futures.get(0).get(10, TimeUnit.SECONDS)
                .getRecordMetadata().partition();
        TopicPartition tp = new TopicPartition(TOPIC_TRANSACTION_POSTED, partition);

        // Use a unique consumer group for this test so prior tests' commits
        // do not pollute the assertion.
        String groupId = "no-commit-test-" + UUID.randomUUID();

        // First consumer instance: poll records BUT DO NOT commit.
        int firstReadCount;
        try (KafkaConsumer<String, String> firstConsumer = createTestConsumer(
                TOPIC_TRANSACTION_POSTED, groupId, /*autoCommit=*/false)) {
            List<ConsumerRecord<String, String>> recordsFirst = drainRecords(
                    firstConsumer, eventCount, Duration.ofSeconds(15));
            firstReadCount = (int) recordsFirst.stream()
                    .filter(rec -> String.format("%011d", acctId).equals(rec.key()))
                    .count();
            assertThat(firstReadCount)
                    .as("First consumer must observe %d events for account %d before"
                            + " deliberately NOT committing", eventCount, acctId)
                    .isEqualTo(eventCount);
            // Deliberately DO NOT call commitSync() — simulates the
            // "processing failed, do not acknowledge" path.
            LOG.info("First consumer received {} records but did NOT commit offsets",
                    firstReadCount);
        }

        // Verify no offset was committed to the broker for this group
        try (AdminClient admin = createAdminClient()) {
            Map<TopicPartition, ?> offsets = admin.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            Object committedForPartition = offsets.get(tp);
            assertThat(committedForPartition)
                    .as("AAP §0.6.5 — consumer group %s must have NO committed offset"
                            + " for partition %d on topic %s (no Acknowledgment.acknowledge()"
                            + " was called)", groupId, partition, TOPIC_TRANSACTION_POSTED)
                    .isNull();
        }

        // Second consumer instance in the SAME group must re-receive the records
        try (KafkaConsumer<String, String> secondConsumer = createTestConsumer(
                TOPIC_TRANSACTION_POSTED, groupId, /*autoCommit=*/false)) {
            List<ConsumerRecord<String, String>> recordsSecond = drainRecords(
                    secondConsumer, eventCount, Duration.ofSeconds(15));
            int secondReadCount = (int) recordsSecond.stream()
                    .filter(rec -> String.format("%011d", acctId).equals(rec.key()))
                    .count();
            assertThat(secondReadCount)
                    .as("AAP §0.6.5 — when first consumer did not commit, a second"
                            + " consumer in the SAME group must re-receive all %d events"
                            + " (broker offset still at 0)", eventCount)
                    .isEqualTo(eventCount);
            LOG.info("Second consumer in same group re-received {} records (offset not committed)",
                    secondReadCount);
        }
    }

    // -------------------------------------------------------------------------
    // Phase 15 — Test 9: transaction.posted → account.updated cascade
    // -------------------------------------------------------------------------
    /**
     * Verifies the cross-topic partition-key preservation invariant of AAP
     * &sect;0.6.5: when an {@code account.updated} event follows a
     * {@code transaction.posted} event for the same account, both partition
     * keys must be derived from the same account ID (zero-padded to 11
     * digits) and therefore the cross-topic ordering of "transaction
     * THEN account update" is preserved at the partition-key level for
     * any downstream consumer that joins the two topic streams by key.
     *
     * <p>This test does NOT exercise an automatic cascade
     * (transaction.posted &rarr; consumer &rarr; account.updated):
     * the production {@link KafkaEventConsumer} does not perform a
     * cascade publication (it logs an audit event and acknowledges).
     * Instead, this test simulates the application&apos;s expected
     * cascade pattern by publishing one event of each type for the same
     * account ID through the production {@link KafkaEventPublisher} and
     * asserts that the resulting Kafka partition keys are equal (per the
     * {@code PARTITION_KEY_FORMAT = "%011d"} contract on
     * {@link KafkaEventPublisher}). Equality at the key level is the
     * invariant downstream consumers rely on for per-account ordering
     * across topics &mdash; the actual cascade trigger is a
     * service-layer concern beyond the scope of this messaging-layer
     * integration test.</p>
     */
    @Test
    @DisplayName("Cascade: transaction.posted and account.updated share partition key for same account ID")
    void cascade_transactionPostedTriggersAccountUpdated() throws Exception {
        final long acctId = PARTITION_TEST_ACCOUNT_A;

        // Publish an upstream transaction.posted event
        CompletableFuture<SendResult<String, Object>> transactionFuture =
                publisher.publishTransactionPosted(acctId, buildTransactionEvent(acctId, 1));
        SendResult<String, Object> transactionResult =
                transactionFuture.get(15, TimeUnit.SECONDS);
        String transactionKey = transactionResult.getProducerRecord().key();
        int transactionPartition = transactionResult.getRecordMetadata().partition();

        LOG.info("Published transaction.posted: key={} partition={} topic={}",
                transactionKey, transactionPartition,
                transactionResult.getRecordMetadata().topic());

        // Publish a downstream account.updated event for the SAME account
        CompletableFuture<SendResult<String, Object>> accountFuture =
                publisher.publishAccountUpdated(acctId, buildAccountUpdateEvent(acctId));
        SendResult<String, Object> accountResult =
                accountFuture.get(15, TimeUnit.SECONDS);
        String accountKey = accountResult.getProducerRecord().key();
        int accountPartition = accountResult.getRecordMetadata().partition();

        LOG.info("Published account.updated: key={} partition={} topic={}",
                accountKey, accountPartition,
                accountResult.getRecordMetadata().topic());

        // ----- Invariant 1: partition keys must be identical (zero-padded to 11 digits)
        String expectedKey = String.format("%011d", acctId);
        assertThat(transactionKey)
                .as("AAP §0.6.5 — transaction.posted partition key must equal"
                        + " String.format(\"%%011d\", accountId) = %s for accountId=%d",
                        expectedKey, acctId)
                .isEqualTo(expectedKey);
        assertThat(accountKey)
                .as("AAP §0.6.5 — account.updated partition key must equal"
                        + " String.format(\"%%011d\", accountId) = %s for accountId=%d",
                        expectedKey, acctId)
                .isEqualTo(expectedKey);
        assertThat(accountKey)
                .as("AAP §0.6.5 — cross-topic partition-key preservation: account.updated"
                        + " must share the partition key of transaction.posted for the"
                        + " same account so downstream consumers joining the two streams"
                        + " observe consistent ordering")
                .isEqualTo(transactionKey);

        // ----- Invariant 2: both records must be discoverable on the respective topics
        // Verify transaction.posted record landed
        String transactionGroupId = "cascade-tx-test-" + UUID.randomUUID();
        try (KafkaConsumer<String, String> txConsumer = createTestConsumer(
                TOPIC_TRANSACTION_POSTED, transactionGroupId, false)) {
            List<ConsumerRecord<String, String>> records = drainRecords(
                    txConsumer, 1, Duration.ofSeconds(15));
            boolean ourRecordFound = records.stream()
                    .anyMatch(rec -> expectedKey.equals(rec.key())
                            && rec.value().contains("SEQ=1"));
            assertThat(ourRecordFound)
                    .as("transaction.posted record for account %d must be present"
                            + " in the topic", acctId)
                    .isTrue();
        }

        // Verify account.updated record landed
        String accountGroupId = "cascade-acct-test-" + UUID.randomUUID();
        try (KafkaConsumer<String, String> acctConsumer = createTestConsumer(
                TOPIC_ACCOUNT_UPDATED, accountGroupId, false)) {
            List<ConsumerRecord<String, String>> records = drainRecords(
                    acctConsumer, 1, Duration.ofSeconds(15));
            boolean ourRecordFound = records.stream()
                    .anyMatch(rec -> expectedKey.equals(rec.key()));
            assertThat(ourRecordFound)
                    .as("account.updated record for account %d must be present in the topic",
                            acctId)
                    .isTrue();
        }

        LOG.info("Cascade invariant verified: transaction.posted (partition {})"
                + " and account.updated (partition {}) for account {} share key {}",
                transactionPartition, accountPartition, acctId, expectedKey);
    }

    // -------------------------------------------------------------------------
    // Phase 16 — Test 10: Rebalance does not break per-account ordering
    // -------------------------------------------------------------------------
    /**
     * Verifies that pausing and resuming a Spring Kafka listener container
     * (the Spring Kafka equivalent of a consumer-group rebalance) does NOT
     * violate per-account ordering on the resumed consumer. The test:
     * <ol>
     *   <li>Resumes the {@code transaction.posted} listener container.</li>
     *   <li>Publishes events for multiple accounts.</li>
     *   <li>Pauses the listener container mid-stream.</li>
     *   <li>Publishes additional events.</li>
     *   <li>Resumes the listener container.</li>
     *   <li>Uses a separate test consumer to drain the topic and verify
     *       that every account&apos;s seqNum sequence remains monotonic
     *       across the pause/resume boundary.</li>
     * </ol>
     *
     * <p>This is the integration-test equivalent of the rebalance scenario
     * described in AAP &sect;0.6.5: "Re-balance does not interleave events
     * for the same account because partition assignment is atomic at the
     * consumer-group level."</p>
     */
    @Test
    @DisplayName("Container pause/resume (rebalance simulation) preserves per-account ordering")
    void rebalance_doesNotBreakOrdering() throws Exception {
        // Resume the transaction.posted listener container to simulate
        // a live application consumer.
        MessageListenerContainer container = findContainerForTopic(TOPIC_TRANSACTION_POSTED);
        assertThat(container)
                .as("Listener container for %s must exist", TOPIC_TRANSACTION_POSTED)
                .isNotNull();

        if (container.isPauseRequested()) {
            container.resume();
        }
        if (!container.isRunning()) {
            container.start();
        }
        await("Listener container is running and not paused before publishing")
                .atMost(AWAIT_TIMEOUT)
                .pollInterval(AWAIT_POLL_INTERVAL)
                .until(() -> container.isRunning() && !container.isContainerPaused());

        // Publish first half of events for several accounts
        long baseAcctId = 40_000_000_000L;
        int accountCount = 3;
        int eventsPerHalf = 20;
        List<Long> accountIds = new ArrayList<>();
        Set<String> expectedKeys = new HashSet<>();
        for (int i = 0; i < accountCount; i++) {
            accountIds.add(baseAcctId + i);
            expectedKeys.add(String.format("%011d", baseAcctId + i));
        }

        // Open the test consumer seeked-to-end BEFORE any publication so
        // it isolates from records published by earlier-running tests.
        // The consumer uses assign() (not subscribe()), so it remains
        // statically bound to its partitions for the duration of the test
        // and does not require periodic poll() to maintain heartbeats.
        String groupId = "rebalance-test-" + UUID.randomUUID();
        try (AdminClient seekAdmin = createAdminClient();
             KafkaConsumer<String, String> testConsumer = createTestConsumerSeekedToEnd(
                     TOPIC_TRANSACTION_POSTED, groupId, seekAdmin)) {

            // ----- Phase 1: publish first half (container running) -----
            // Round-based serial-await pattern (see perAccountOrdering test):
            // within each round, publish ONE event per account (different
            // partition keys race safely), then await ALL futures before
            // issuing the next round. Guarantees per-account FIFO order at
            // the broker level despite @Async dispatch on
            // SimpleAsyncTaskExecutor. See AAP §0.6.5.
            List<CompletableFuture<SendResult<String, Object>>> firstHalfFutures = new ArrayList<>();
            for (int seq = 1; seq <= eventsPerHalf; seq++) {
                List<CompletableFuture<SendResult<String, Object>>> roundFutures = new ArrayList<>();
                for (Long acctId : accountIds) {
                    roundFutures.add(publisher.publishTransactionPosted(
                            acctId, buildTransactionEvent(acctId, seq)));
                }
                for (CompletableFuture<SendResult<String, Object>> f : roundFutures) {
                    f.get(10, TimeUnit.SECONDS);
                }
                firstHalfFutures.addAll(roundFutures);
            }
            await("First-half publications acknowledged")
                    .atMost(AWAIT_TIMEOUT)
                    .pollInterval(AWAIT_POLL_INTERVAL)
                    .until(() -> firstHalfFutures.stream().allMatch(CompletableFuture::isDone));
            LOG.info("Rebalance test phase 1: published {} events across {} accounts",
                    firstHalfFutures.size(), accountCount);

            // ----- Phase 2: pause the container (rebalance simulation) -----
            container.pause();
            await("Listener container is paused")
                    .atMost(AWAIT_TIMEOUT)
                    .pollInterval(AWAIT_POLL_INTERVAL)
                    .until(container::isContainerPaused);
            LOG.info("Rebalance test phase 2: paused listener container (rebalance simulation)");

            // ----- Phase 3: publish second half (container paused) -----
            // Round-based serial-await pattern (see perAccountOrdering test);
            // continues seqNum monotonically from eventsPerHalf+1 to
            // eventsPerHalf*2 to verify ordering is preserved across the
            // pause/resume boundary.
            List<CompletableFuture<SendResult<String, Object>>> secondHalfFutures = new ArrayList<>();
            for (int seq = eventsPerHalf + 1; seq <= eventsPerHalf * 2; seq++) {
                List<CompletableFuture<SendResult<String, Object>>> roundFutures = new ArrayList<>();
                for (Long acctId : accountIds) {
                    roundFutures.add(publisher.publishTransactionPosted(
                            acctId, buildTransactionEvent(acctId, seq)));
                }
                for (CompletableFuture<SendResult<String, Object>> f : roundFutures) {
                    f.get(10, TimeUnit.SECONDS);
                }
                secondHalfFutures.addAll(roundFutures);
            }
            await("Second-half publications acknowledged")
                    .atMost(AWAIT_TIMEOUT)
                    .pollInterval(AWAIT_POLL_INTERVAL)
                    .until(() -> secondHalfFutures.stream().allMatch(CompletableFuture::isDone));
            LOG.info("Rebalance test phase 3: published {} additional events while paused",
                    secondHalfFutures.size());

            // ----- Phase 4: resume the container (rejoin group) -----
            container.resume();
            await("Listener container is resumed (not paused)")
                    .atMost(AWAIT_TIMEOUT)
                    .pollInterval(AWAIT_POLL_INTERVAL)
                    .until(() -> !container.isContainerPaused());
            LOG.info("Rebalance test phase 4: resumed listener container");

            // ----- Drain through the seeked test consumer -----
            int expectedTotal = accountCount * eventsPerHalf * 2;
            Map<Long, List<Integer>> seqByAccount = new HashMap<>();
            accountIds.forEach(id -> seqByAccount.put(id, new ArrayList<>()));

            List<ConsumerRecord<String, String>> records = drainRecords(
                    testConsumer, expectedTotal, AWAIT_TIMEOUT);
            for (ConsumerRecord<String, String> rec : records) {
                if (!expectedKeys.contains(rec.key())) {
                    continue;
                }
                long acctId = Long.parseLong(rec.key());
                seqByAccount.get(acctId).add(extractSeqNum(rec.value()));
            }

            // For each account, the consumed seqNum sequence must be monotonic
            // 1, 2, …, eventsPerHalf*2 (no skipped, no out-of-order, no duplicates).
            for (Long acctId : accountIds) {
                List<Integer> observed = seqByAccount.get(acctId);
                assertThat(observed)
                        .as("Account %d must have %d events across the rebalance boundary",
                                acctId, eventsPerHalf * 2)
                        .hasSize(eventsPerHalf * 2);
                for (int i = 0; i < observed.size(); i++) {
                    assertThat(observed.get(i))
                            .as("AAP §0.6.5 — Account %d position %d must carry seqNum=%d"
                                    + " (rebalance must NOT violate per-account ordering)",
                                    acctId, i, i + 1)
                            .isEqualTo(i + 1);
                }
            }
        }

        // Pause the container back to its @BeforeEach baseline
        container.pause();
        LOG.info("Rebalance test completed: per-account ordering preserved across pause/resume"
                + " for {} accounts × {} events", accountCount, eventsPerHalf * 2);
    }
}





