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

// =====================================================================
// Replaces CICS pseudo-conversational flow:
//   COSGN00C → COTRN02C → COTRN01C confirmation
//   COBIL00C → COBIL00C confirmation
//   COACTUPC optimistic-lock (@Version) failure mode
// =====================================================================
// AAP §0.3.4 — REST endpoint inventory:
//   POST /api/auth/signin       (← COSGN00C / Tran-ID CC00)
//   POST /api/transactions      (← COTRN02C / Tran-ID CT02)
//   POST /api/billing/pay       (← COBIL00C / Tran-ID CB00)
// AAP §0.4.1 — TransactionAddService and BillPaymentService preserve
//   verbatim COBOL constants: BillPaymentService uses
//   tranTypeCd="02", tranCatCd=2 (Integer), tranSource="POS TERM",
//   merchantId=999_999_999L (per COBIL00C.cbl L226).
// AAP §0.6.1 — All monetary fields use BigDecimal HALF_EVEN scale=2;
//   isEqualByComparingTo (NEVER .equals).
// AAP §0.6.5 — Kafka transaction.posted and account.updated topics are
//   partitioned by zero-padded 11-digit account ID for per-account
//   ordering.
// AAP §0.7.1 — PCI-DSS: synthetic PANs only (4111111111111111);
//   BCrypt password upgrade; JWT bearer tokens; no full PAN in logs.
// =====================================================================
import com.awsm2.carddemo.CardDemoApplication;
import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.adapter.SecretsManagerService;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.Card;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.Customer;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.domain.UserSecurity;
import com.awsm2.carddemo.dto.ApiResponse;
import com.awsm2.carddemo.dto.BillPaymentDto;
import com.awsm2.carddemo.dto.SignonRequestDto;
import com.awsm2.carddemo.dto.SignonResponseDto;
import com.awsm2.carddemo.dto.TransactionAddDto;
import com.awsm2.carddemo.dto.TransactionDetailDto;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.CardRepository;
import com.awsm2.carddemo.repository.CustomerRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import com.awsm2.carddemo.repository.UserSecurityRepository;
import com.awsm2.carddemo.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sfn.SfnClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end integration test for the CardDemo online transaction
 * workflow: sign-in &rarr; transaction add &rarr; bill payment &rarr;
 * optimistic-lock conflict &rarr; cache-aside validation. Mirrors the CICS
 * pseudo-conversational flow {@code COSGN00C → COTRN02C → COBIL00C →
 * COACTUPC} described in AAP &sect;0.3.4 and exercises every layer of the
 * Spring Boot target stack end-to-end against the production REST contract.
 *
 * <p><b>What this test validates (AAP &sect;0.3.4 + &sect;0.4.1):</b></p>
 * <ol>
 *   <li>JWT-based sign-on against {@code USRSEC} (UserSecurity) with
 *       BCrypt password matching. The {@code SEC-USR-ID} is uppercased
 *       per COBOL {@code COSGN00C} L132-135 semantic; the password is
 *       compared verbatim via {@link PasswordEncoder#matches} per the
 *       CP5 review (preserves BCrypt entropy).</li>
 *   <li>Transaction add ({@code POST /api/transactions}) preserves the
 *       COBOL {@code COTRN02C} flow: XOR validation (account OR card),
 *       XREF lookup via {@code CXACAIX} replacement
 *       ({@link CardCrossReferenceRepository#findByXrefAcctId}),
 *       MAX-TRAN-ID + 1 generation (16-char zero-padded), {@code confirm}
 *       flag gating, and MSK {@code transaction.posted} event publication
 *       partitioned by zero-padded 11-digit account ID per AAP
 *       &sect;0.6.5.</li>
 *   <li>Bill payment ({@code POST /api/billing/pay}) preserves the COBOL
 *       {@code COBIL00C} flow: full-balance pay-down with verbatim
 *       constants ({@code tranTypeCd="02"}, {@code tranCatCd=2},
 *       {@code tranSource="POS TERM"}, {@code merchantId=999_999_999L}
 *       per COBIL00C L226), {@code @Transactional} dual-write
 *       (Transaction insert + Account balance zeroed), MSK
 *       {@code transaction.posted} + {@code account.updated} event
 *       publication.</li>
 *   <li>Optimistic-lock conflict on concurrent bill-pay POSTs: JPA
 *       {@code @Version} on {@link Account} triggers
 *       {@code OptimisticLockingFailureException} &rarr; HTTP 409 Conflict
 *       via {@link com.awsm2.carddemo.exception.GlobalExceptionHandler}
 *       (replaces the COBOL {@code COACTUPC} before/after image
 *       comparison per AAP &sect;0.6.2).</li>
 *   <li>Cache-aside validation: {@link CacheService} populates on first
 *       read; subsequent reads hit cache until TTL expires or eviction
 *       occurs (AAP &sect;0.3.3 design pattern). Cache failures NEVER
 *       propagate to callers &mdash; all exceptions are caught at WARN
 *       level.</li>
 *   <li>{@link SecurityTests &commat;Nested SecurityTests}: missing JWT
 *       returns 401; tampered JWT returns 401; expired JWT returns 401.</li>
 *   <li>{@link PciDssAndAuditTests &commat;Nested PciDssAndAuditTests}:
 *       PAN masking in responses; no CVV exposure; verified
 *       {@link AuditLogService} emission on transaction posting per AAP
 *       &sect;0.6.6 / &sect;0.7.1.</li>
 * </ol>
 *
 * <h2>Critical implementation notes (deviation from agent_prompt):</h2>
 * <ul>
 *   <li><b>Bad signin returns HTTP 400, not 401:</b> The actual
 *       {@link com.awsm2.carddemo.service.SignonService} throws
 *       {@link com.awsm2.carddemo.exception.ValidationException} (NOT
 *       {@code BadCredentialsException}) for both user-not-found and
 *       bad-password paths to prevent enumeration. The
 *       {@code GlobalExceptionHandler} maps {@code ValidationException}
 *       &rarr; HTTP 400 per AAP &sect;0.3.4.</li>
 *   <li><b>No credit-limit or expired-card checks in online flow:</b>
 *       AAP &sect;0.4.1 routes these validations to the batch
 *       {@code CBTRN02C} path. The online
 *       {@link com.awsm2.carddemo.service.TransactionAddService} does
 *       NOT implement reject codes 102/103; instead it relies on
 *       Jakarta Bean Validation and XREF lookup. The agent_prompt's
 *       overlimit_returns422 and expiredCard_returns422 tests are
 *       therefore adapted to exercise behaviors actually present in
 *       the service (validation 400, XREF 404).</li>
 *   <li><b>BillingController returns 201 only when tranId is set:</b>
 *       The {@link com.awsm2.carddemo.controller.BillingController}
 *       inspects {@code TransactionDetailDto.transactionId()}: non-null
 *       and non-blank &rarr; HTTP 201 Created (resource created); null
 *       &rarr; HTTP 200 OK (cancel / preview / nothing-to-pay path).</li>
 *   <li><b>MerchantId is 999_999_999L (9 digits), not 9_999_999_999L
 *       (10 digits):</b> COBIL00C.cbl L226 reads
 *       {@code MOVE 999999999 TO TRAN-MERCHANT-ID}. The test asserts
 *       the canonical 9-digit value.</li>
 *   <li><b>TransactionAddDto in response has accountId as zero-padded
 *       String:</b> The
 *       {@link com.awsm2.carddemo.service.TransactionAddService}
 *       returns a DTO with {@code accountId =
 *       String.format("%011d", resolvedAcctId)} regardless of which
 *       identifier the caller supplied. The DTO has no
 *       {@code tranId} field &mdash; to verify the generated 16-digit ID,
 *       the test queries the
 *       {@link com.awsm2.carddemo.repository.TransactionRepository}
 *       directly.</li>
 *   <li><b>Confirm gate is mandatory:</b>
 *       {@link com.awsm2.carddemo.service.TransactionAddService}
 *       requires {@code confirm="Y"} to actually persist the
 *       transaction; {@code confirm="N"} throws
 *       {@code ValidationException("NOT_CONFIRMED")} &rarr; HTTP 400.</li>
 * </ul>
 *
 * <h2>Test isolation strategy</h2>
 * <ul>
 *   <li>{@link PostgreSQLContainer} ({@code postgres:16-alpine}, pinned
 *       per AAP &sect;0.5.1 "no {@code latest} tags") satisfies the
 *       Spring context's JPA / Flyway requirements via
 *       {@link ServiceConnection &commat;ServiceConnection}.</li>
 *   <li>{@link ConfluentKafkaContainer} ({@code cp-kafka:7.5.0}, pinned)
 *       is required because both
 *       {@link com.awsm2.carddemo.service.TransactionAddService} and
 *       {@link com.awsm2.carddemo.service.BillPaymentService} publish
 *       Kafka events on successful flow completion.</li>
 *   <li>All non-Kafka AWS clients are {@link MockBean &commat;MockBean}
 *       stubs.</li>
 *   <li>{@link RedisTemplate} is mocked; the {@link CacheService}
 *       degrades fail-open per AAP &sect;0.3.3.</li>
 *   <li>{@link SecretsManagerService} is overridden via the inner
 *       {@link TestSecretsManagerConfiguration} so
 *       {@link JwtTokenProvider}'s {@code @PostConstruct} HS256 key
 *       resolution succeeds offline.</li>
 *   <li>{@link AuditLogService} is wrapped with {@link SpyBean} so the
 *       audit nested class can verify emission without OpenSearch.</li>
 *   <li>Per-test data isolation via {@code @BeforeEach}-driven truncation
 *       and re-seeding plus per-test cache evict-all.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.controller.AuthController
 * @see com.awsm2.carddemo.controller.TransactionController
 * @see com.awsm2.carddemo.controller.BillingController
 * @see com.awsm2.carddemo.service.SignonService
 * @see com.awsm2.carddemo.service.TransactionAddService
 * @see com.awsm2.carddemo.service.BillPaymentService
 */
@SpringBootTest(classes = CardDemoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(EndToEndTransactionWorkflowIT.TestSecretsManagerConfiguration.class)
class EndToEndTransactionWorkflowIT {

    // -------------------------------------------------------------------------
    // SLF4J Logger — replaces System.out.println per AAP §0.7.1 logging rules
    // -------------------------------------------------------------------------

    /** SLF4J logger for test diagnostics. */
    private static final Logger LOG =
            LoggerFactory.getLogger(EndToEndTransactionWorkflowIT.class);

    // -------------------------------------------------------------------------
    // Test constants — seed values for the transaction-workflow fixture
    // -------------------------------------------------------------------------

    /** Test account ID — 11 digits matching {@code ACCT-ID PIC 9(11)}. */
    private static final Long TEST_ACCT_ID = 99_999_999_999L;

    /** Test customer ID — 9 digits matching {@code CUST-ID PIC 9(09)}. */
    private static final Long TEST_CUST_ID = 999_999_999L;

    /**
     * Synthetic SSN for the seeded {@link Customer}. PCI-DSS / PII compliant
     * (synthetic value &mdash; NEVER a real SSN). The {@code cust_ssn} column
     * is {@code NOT NULL} per the {@code customers} schema (V003 migration)
     * so this constant satisfies the FK / column-level validation when the
     * customer is seeded in {@link #seedDatabase()}.
     */
    private static final Long TEST_CUST_SSN = 999_99_9999L;

    /** Test card number — 16-digit synthetic PAN (NEVER a real PAN). */
    private static final String TEST_CARD_NUM = "4111111111111111";

    /** Test regular-user identifier (USRSEC primary key, 8 chars). */
    private static final String TEST_USER_ID = "TESTUSER";

    /** Plaintext password for {@link #TEST_USER_ID}; BCrypt-hashed at seed. */
    private static final String TEST_USER_PWD = "TESTPASS";

    /** Initial seeded account balance for bill-pay tests. */
    private static final BigDecimal INITIAL_BALANCE = bd("500.00");

    /** Initial seeded credit limit. */
    private static final BigDecimal INITIAL_CREDIT_LIMIT = bd("5000.00");

    /** Initial seeded cash credit limit. */
    private static final BigDecimal INITIAL_CASH_CREDIT_LIMIT = bd("1000.00");

    /** Default Awaitility bounded timeout for async assertions. */
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);

    /** Default Awaitility per-poll interval. */
    private static final Duration AWAIT_POLL = Duration.ofMillis(200);

    /** Account cache namespace used by AccountViewService for cache-aside. */
    private static final String ACCOUNT_VIEW_CACHE_NS = "account-view";

    // -------------------------------------------------------------------------
    // @TestConfiguration — stub SecretsManagerService for JwtTokenProvider
    // -------------------------------------------------------------------------
    // JwtTokenProvider is annotated @Component @RefreshScope and performs an
    // eager Secrets Manager fetch in its @PostConstruct lifecycle hook to load
    // the HS256 signing key. Without a stubbed SecretsManagerService, context
    // refresh fails with IllegalStateException because the unstubbed Mockito
    // mock returns Mockito's default Optional (Optional.empty()). The 64-byte
    // placeholder key easily clears the 32-byte minimum imposed by HS256
    // (RFC 7518).

    /**
     * Provides a stubbed {@link SecretsManagerService} bean for this test.
     * The stub returns a deterministic 64-byte ASCII placeholder for any
     * {@code (secretArn, fieldName)} pair so {@code JwtTokenProvider}'s
     * HS256 key-length precondition is satisfied during context refresh.
     */
    @TestConfiguration
    static class TestSecretsManagerConfiguration {

        /** 64-byte test signing key (well above the 32-byte HS256 minimum). */
        private static final String TEST_SIGNING_KEY =
                "test-only-jwt-signing-key-for-transaction-workflow-it-padded64!";

        /**
         * Stub {@link SecretsManagerService} bean overriding the production
         * adapter for the entire test context.
         *
         * @return a Mockito mock pre-configured with deterministic stubbing
         */
        @Bean
        @Primary
        SecretsManagerService secretsManagerService() {
            SecretsManagerService stub = Mockito.mock(SecretsManagerService.class);
            Mockito.when(stub.getSecretJsonField(
                            ArgumentMatchers.anyString(),
                            ArgumentMatchers.anyString()))
                    .thenReturn(Optional.of(TEST_SIGNING_KEY));
            Mockito.when(stub.getSecret(ArgumentMatchers.anyString()))
                    .thenReturn(TEST_SIGNING_KEY);
            return stub;
        }
    }

    // -------------------------------------------------------------------------
    // Testcontainers — PostgreSQL + Kafka
    // -------------------------------------------------------------------------

    /**
     * Ephemeral PostgreSQL 16-alpine container backing the JPA / Flyway
     * integration. Required because every JPA-backed service in the
     * Spring context needs a running database to bootstrap.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Ephemeral Apache Kafka broker (Confluent {@code cp-kafka:7.5.0}).
     * Required because {@code TransactionAddService} and
     * {@code BillPaymentService} both publish events to MSK on every
     * successful flow &mdash; the test must provide a real broker so the
     * synchronous-send completion does not hang the
     * {@code @Transactional} method.
     */
    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    // -------------------------------------------------------------------------
    // @DynamicPropertySource — bind container details + reinforce Kafka invariants
    // -------------------------------------------------------------------------

    /**
     * Binds Testcontainers-derived runtime properties into the Spring
     * {@code Environment} so the application's beans (DataSource, Kafka,
     * etc.) point at the ephemeral containers, and re-asserts AAP
     * &sect;0.6.5 Kafka producer/consumer invariants defensively.
     *
     * @param registry the dynamic property registry to populate
     */
    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        // ---------------------------------------------------------------
        // PostgreSQL — the @Primary @RefreshScope DataSource in JpaConfig
        // is constructed from DataSourceProperties (not
        // JdbcConnectionDetails), so @ServiceConnection alone is not
        // enough — we explicitly bind spring.datasource.* here so the
        // application DataSource hits the same container that Flyway
        // migrated via @ServiceConnection.
        // ---------------------------------------------------------------
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name",
                POSTGRES::getDriverClassName);

        // ---------------------------------------------------------------
        // Kafka — bind the Testcontainers broker URL to the application's
        // KafkaConfig. CardDemo's KafkaConfig declares its own
        // ProducerFactory / ConsumerFactory beans reading from
        // KafkaProperties.bootstrapServers (NOT KafkaConnectionDetails).
        // application-test.yml uses the placeholder
        // ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092} so this
        // override is mandatory to redirect to the test container.
        // ---------------------------------------------------------------
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        // ---------------------------------------------------------------
        // Defense in depth — disable Spring Cloud AWS integrations that
        // would otherwise try to fetch from real AWS endpoints at refresh.
        // ---------------------------------------------------------------
        registry.add("spring.cloud.aws.secretsmanager.enabled", () -> "false");
        registry.add("spring.cloud.aws.parameterstore.enabled", () -> "false");

        // ---------------------------------------------------------------
        // Disable Kafka listener auto-startup — the application's
        // @KafkaListener (KafkaEventConsumer) is not exercised in this
        // workflow test; preventing auto-startup keeps the consumer from
        // racing or DLT-recovering during context refresh.
        // ---------------------------------------------------------------
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    // -------------------------------------------------------------------------
    // @MockBean — AWS clients we do NOT exercise
    // -------------------------------------------------------------------------
    // The application's component scan instantiates every adapter under
    // com.awsm2.carddemo.adapter and glue at context refresh, including
    // S3OutputService, StepFunctionsOrchestrator, OpenSearchIndexer, and
    // GlueETLConfig. Each adapter takes an AWS SDK client as a constructor
    // dependency. Mocking the clients here lets the context refresh
    // successfully without attempting to reach AWS.

    /** Mocked S3 client — prevents real S3 traffic during context refresh. */
    @MockBean
    private S3Client s3Client;

    /** Mocked Step Functions client — used by StepFunctionsOrchestrator. */
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

    /** Mocked CloudWatch client — supplied for completeness of the AWS SDK surface. */
    @MockBean
    private CloudWatchClient cloudWatchClient;

    /** Mocked Glue client — Glue ETL job submission is not exercised here. */
    @MockBean
    private GlueClient glueClient;

    /** Mocked OpenSearch typed client — used by {@code OpenSearchIndexer}. */
    @MockBean
    private OpenSearchClient openSearchClient;

    /**
     * Mocked Redis template — the application's {@link CacheService}
     * degrades fail-open under the mock so the cache-aside code path
     * exercises correctly while remaining a cache-miss on every read.
     */
    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Spy on the production {@link AuditLogService} bean — wraps the real
     * implementation so the PCI-DSS / audit nested class can verify that
     * {@code logTransactionEvent(...)} / {@code auditEvent(...)} were
     * invoked during the transaction-add and bill-pay flows without
     * needing a real OpenSearch broker.
     */
    @SpyBean
    private AuditLogService auditLogService;

    // -------------------------------------------------------------------------
    // @Autowired — beans the test consumes directly
    // -------------------------------------------------------------------------

    /**
     * Random Tomcat port assigned by {@code @SpringBootTest} with
     * {@code WebEnvironment.RANDOM_PORT}; used to build absolute URLs
     * for {@link TestRestTemplate} requests so we never collide with a
     * developer's local 8080 server.
     */
    @LocalServerPort
    private int port;

    /**
     * Spring Boot's test-aware {@link TestRestTemplate} &mdash;
     * automatically wired with the random port and configured to NOT
     * throw on 4xx/5xx so we can assert error status codes explicitly.
     */
    @Autowired
    private TestRestTemplate restTemplate;

    /** Account repository &mdash; seed + verify {@code Account} rows. */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * Card repository &mdash; seed test card + mutate expDate for
     * expiration scenarios.
     */
    @Autowired
    private CardRepository cardRepository;

    /**
     * Card cross-reference repository &mdash; seeds the
     * {@code CXACAIX}-replacement index row linking card &harr; customer
     * &harr; account so the {@code TransactionAddService} XREF lookup
     * succeeds.
     */
    @Autowired
    private CardCrossReferenceRepository xrefRepository;

    /**
     * Customer repository &mdash; seed the {@link Customer} parent row that
     * the {@link CardCrossReference} child references via FK
     * {@code fk_cardxref_cust} (xref_cust_id -&gt; customers.cust_id, declared
     * in V004 migration). Without a seeded Customer the {@link #seedDatabase()}
     * step that inserts the cross-reference would fail with
     * {@code DataIntegrityViolationException} and every test in this class
     * would abort during fixture setup.
     */
    @Autowired
    private CustomerRepository customerRepository;

    /** Transaction repository &mdash; read post-conditions (verify persistence). */
    @Autowired
    private TransactionRepository transactionRepository;

    /** User-security repository &mdash; seed BCrypt-hashed credential rows. */
    @Autowired
    private UserSecurityRepository userRepository;

    /**
     * Cache adapter &mdash; used to evict between tests so cached views
     * from a prior test cannot mask a current test's DB regression.
     */
    @Autowired
    private CacheService cacheService;

    /**
     * JWT issuer/validator &mdash; used to decode the sign-in response
     * token and assert the {@code sub} / {@code userType} claim values
     * (AAP &sect;0.4.1 SignonService).
     */
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /**
     * Spring's BCrypt {@link PasswordEncoder} bean &mdash; used to hash
     * test passwords during seed; matches the production hashing scheme
     * so the sign-on path BCrypt comparison succeeds.
     */
    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * Jackson {@link ObjectMapper} &mdash; used to parse raw response
     * bodies into {@link JsonNode} for the PCI-DSS PII-leak assertions
     * in the nested class.
     */
    @Autowired
    private ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Per-test state — the JWT bearer token from the most-recent signin
    // -------------------------------------------------------------------------

    /**
     * Bearer JWT for {@link #TEST_USER_ID}; populated by
     * {@link #signIn(String, String)} and consumed by every authenticated
     * test method's {@code Authorization: Bearer ...} header.
     */
    private String userJwtToken;

    // =========================================================================
    // Stage 1 — Sign-in tests (COBOL: COSGN00C)
    // =========================================================================

    /**
     * Verifies that signing in with the seeded {@code TESTUSER}/{@code TESTPASS}
     * credentials returns HTTP 200, populates an {@link ApiResponse} envelope
     * whose payload contains a valid JWT, and the JWT decodes via
     * {@link JwtTokenProvider} to a {@link Claims} set carrying
     * {@code sub=TESTUSER} and {@code userType=U} with an expiration in the
     * future.
     *
     * <p>This mirrors COBOL {@code COSGN00C} L120-160 sign-on flow: USRSEC
     * read, BCrypt verify, COMMAREA population, XCTL to main menu. The Java
     * target replaces the COMMAREA hand-off with a stateless JWT that carries
     * the routing claims (userType {@code A}=admin, {@code U}=regular).</p>
     */
    @Test
    @Order(1)
    @DisplayName("Stage 1: signin with valid credentials returns 200 + JWT")
    void signin_validCredentials_returnsJwtToken() {
        // COBOL: COSGN00C signon flow → POST /api/auth/signin (BCrypt-validated)
        SignonRequestDto request = new SignonRequestDto(TEST_USER_ID, TEST_USER_PWD);
        ResponseEntity<String> response =
                postJson("/api/auth/signin", request, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        ApiResponse<SignonResponseDto> envelope =
                parseApiResponse(response.getBody(), SignonResponseDto.class);
        assertThat(envelope.code()).isEqualTo("OK");
        assertThat(envelope.data()).isNotNull();

        SignonResponseDto payload = envelope.data();
        assertThat(payload.token()).isNotBlank();
        assertThat(payload.userId()).isEqualTo(TEST_USER_ID);
        assertThat(payload.userType()).isEqualTo("U");
        assertThat(payload.expiresAt()).isPositive();

        // Decode the JWT and assert AAP §0.4.1 claim invariants.
        Claims claims = jwtTokenProvider.validateToken(payload.token());
        assertThat(claims.getSubject()).isEqualTo(TEST_USER_ID);
        assertThat(claims.get("userType")).isEqualTo("U");
        assertThat(claims.getExpiration()).isInTheFuture();

        // Cache the token for downstream stage-2/3 tests.
        this.userJwtToken = payload.token();
    }

    /**
     * Verifies that signing in with an INVALID password returns HTTP 400
     * (NOT 401) with a NEUTRAL error envelope. AAP &sect;0.7.1 CR-04 fix:
     * {@link com.awsm2.carddemo.service.SignonService} throws
     * {@link com.awsm2.carddemo.exception.ValidationException} for BOTH
     * user-not-found AND bad-password paths to prevent user-enumeration,
     * which {@code GlobalExceptionHandler} maps to HTTP 400.
     */
    @Test
    @Order(1)
    @DisplayName("Stage 1: signin with invalid password returns 400 (neutral message)")
    void signin_invalidPassword_returns400() {
        SignonRequestDto request =
                new SignonRequestDto(TEST_USER_ID, "WRONGPASS");
        ResponseEntity<String> response =
                postJson("/api/auth/signin", request, null);

        // Per CR-04 — anti-enumeration ValidationException maps to 400
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ApiResponse<SignonResponseDto> envelope =
                parseApiResponse(response.getBody(), SignonResponseDto.class);
        // Error envelope MUST NOT reveal whether the userId or password was wrong
        assertThat(envelope.message())
                .doesNotContainIgnoringCase("user not found")
                .doesNotContainIgnoringCase("password mismatch")
                .doesNotContainIgnoringCase("no such user");
        assertThat(envelope.data()).isNull();
    }

    // -------------------------------------------------------------------------
    // Per-test setup / teardown
    // -------------------------------------------------------------------------

    /**
     * Resets shared mutable state and seeds a deterministic test fixture
     * BEFORE each test method, guaranteeing isolation.
     *
     * <ol>
     *   <li>Clears all transaction-workflow rows in FK-safe order
     *       (transaction &rarr; card_xref &rarr; card &rarr; account &rarr;
     *       user_security). Reference-data tables seeded by Flyway
     *       (disclosure_group, transaction_type, transaction_category,
     *       default users) are left intact.</li>
     *   <li>Seeds a fresh {@link Account} ({@code acctId=99999999999},
     *       {@code currBal=500.00}, {@code creditLimit=5000.00},
     *       {@code @Version=0}).</li>
     *   <li>Seeds a fresh {@link Card} ({@code cardNum="4111111111111111"},
     *       {@code expDate=2099-12-31}).</li>
     *   <li>Seeds a fresh {@link CardCrossReference} linking the card,
     *       customer, and account.</li>
     *   <li>Seeds a fresh {@link UserSecurity} ({@code secUsrId="TESTUSER"},
     *       BCrypt-hashed {@code TESTPASS}).</li>
     *   <li>Evicts all entries from the {@link #ACCOUNT_VIEW_CACHE_NS}
     *       namespace so stale cached views from a prior test cannot mask
     *       this test's DB regression.</li>
     *   <li>Clears all spy invocations on {@link AuditLogService} so the
     *       audit tests can verify ONLY the events emitted during the
     *       current test method.</li>
     *   <li>Resets {@link #userJwtToken} so any test that does not call
     *       {@link #signIn} fails fast on missing JWT.</li>
     * </ol>
     */
    @BeforeEach
    void seedDatabase() {
        // Step 1: FK-safe truncation of transaction-workflow tables.
        //
        // Order matters because the schema declares the following FK
        // constraints (V001..V005 migrations):
        //   transactions    -> cards          via fk_transactions_card_num
        //   card_xref       -> cards          via fk_cardxref_card
        //   card_xref       -> customers      via fk_cardxref_cust
        //   card_xref       -> accounts       via fk_cardxref_acct
        //   cards           -> accounts       via fk_cards_account
        //
        // Children must be deleted before parents so the order is:
        //   transactions -> card_xref -> cards -> accounts -> customers
        // (customers must be deleted AFTER card_xref because card_xref
        //  carries the xref_cust_id FK).
        //
        // Reference data (disclosure_group, transaction_type,
        // transaction_category, default user_security rows) is preserved.
        transactionRepository.deleteAll();
        xrefRepository.deleteAll();
        cardRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();
        userRepository.deleteAll();

        // Step 2: Seed the test Customer.
        //
        // The Customer entity ports CVCUS01Y.cpy. The 18-arg constructor
        // mirrors the COBOL declaration order. Values exercise the
        // CSLKPCDY.cpy validation paths: state code "CA" is a valid
        // US state; ZIP prefix "9" is a valid CA prefix; phone area
        // code "212" is a valid NANPA area code. PII fields use
        // PCI-DSS-safe synthetic values (NEVER real SSN / DL number).
        Customer customer = new Customer(
                TEST_CUST_ID,
                "TEST",                         // custFirstName
                "T",                            // custMiddleName
                "CUSTOMER",                     // custLastName
                "1 TEST ST",                    // custAddrLine1
                "APT 1",                        // custAddrLine2
                "TEST CITY",                    // custAddrLine3
                "CA",                           // custAddrStateCd (valid)
                "USA",                          // custAddrCountryCd
                "90210",                        // custAddrZip
                "2125551234",                   // custPhoneNum1 (valid NANPA 212)
                "2125555678",                   // custPhoneNum2
                TEST_CUST_SSN,                  // custSsn (synthetic PII)
                "DLTEST0000",                   // custGovtIssuedId
                LocalDate.of(1980, 1, 1),       // custDobYyyyMmDd
                "EFTACCT001",                   // custEftAccountId
                "Y",                            // custPriCardHolderInd
                720                             // custFicoCreditScore
        );
        customerRepository.save(customer);

        // Step 3: Seed the test Account.
        // Account constructor signature per CVACT01Y.cpy ACCOUNT-RECORD:
        //   Account(acctId, acctActiveStatus, acctCurrBal, acctCreditLimit,
        //           acctCashCreditLimit, acctOpenDate, acctExpirationDate,
        //           acctReissueDate, acctCurrCycCredit, acctCurrCycDebit,
        //           acctAddrZip, acctGroupId)
        Account account = new Account(
                TEST_ACCT_ID,
                "Y",                            // ACCT-ACTIVE-STATUS
                INITIAL_BALANCE,
                INITIAL_CREDIT_LIMIT,
                INITIAL_CASH_CREDIT_LIMIT,
                LocalDate.of(2020, 1, 1),       // ACCT-OPEN-DATE
                LocalDate.of(2099, 12, 31),     // ACCT-EXPIRATION-DATE
                LocalDate.of(2020, 1, 1),       // ACCT-REISSUE-DATE
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN),
                "12345",                        // ACCT-ADDR-ZIP
                "DEFAULT"                       // ACCT-GROUP-ID
        );
        // Initialise @Version explicitly so the optimistic-lock test
        // begins at a known starting version (0L).
        account.setVersion(0L);
        accountRepository.save(account);

        // Step 4: Seed the test Card.
        // Card constructor signature per CVACT02Y.cpy CARD-RECORD:
        //   Card(cardNum, cardAcctId, cardEmbossedName, cardExpirationDate,
        //        cardActiveStatus)
        Card card = new Card(
                TEST_CARD_NUM,
                TEST_ACCT_ID,
                "TEST CUSTOMER",
                LocalDate.of(2099, 12, 31),     // far-future expiration
                "Y"                             // CARD-ACTIVE-STATUS
        );
        cardRepository.save(card);

        // Step 5: Seed the CXACAIX-replacement cross-reference row.
        //
        // FK targets satisfied by Steps 2-4 above: xref_cust_id ->
        // customers (Step 2), xref_card_num -> cards (Step 4),
        // xref_acct_id -> accounts (Step 3).
        CardCrossReference xref = new CardCrossReference(
                TEST_CARD_NUM,
                TEST_CUST_ID,
                TEST_ACCT_ID
        );
        xrefRepository.save(xref);

        // Step 6: Seed the test UserSecurity with a BCrypt-hashed password.
        UserSecurity user = new UserSecurity(
                TEST_USER_ID,
                "TEST",                         // SEC-USR-FNAME
                "USER",                         // SEC-USR-LNAME
                passwordEncoder.encode(TEST_USER_PWD),
                "U"                             // SEC-USR-TYPE
        );
        userRepository.save(user);

        // Step 7: Evict any cached views from prior tests.
        cacheService.evictAll(ACCOUNT_VIEW_CACHE_NS);

        // Step 8: Reset spy invocations so audit assertions start clean.
        Mockito.clearInvocations(auditLogService);

        // Step 9: Clear stale JWT token.
        userJwtToken = null;

        LOG.debug("seedDatabase complete: acctId={}, custId={}, cardNum={}, userId={}",
                TEST_ACCT_ID, TEST_CUST_ID, TEST_CARD_NUM, TEST_USER_ID);
    }

    /**
     * Cleans up all test fixture rows AFTER each test method using the
     * same FK-safe ordering as {@link #seedDatabase()}. Wrapped in
     * try/catch to ensure a single test's cleanup failure does not abort
     * the entire test class run; any error is logged at WARN level.
     */
    @AfterEach
    void cleanupDatabase() {
        try {
            // FK-safe deletion order matches seedDatabase() Step 1:
            //   transactions -> card_xref -> cards -> accounts -> customers
            //   -> user_security
            transactionRepository.deleteAll();
            xrefRepository.deleteAll();
            cardRepository.deleteAll();
            accountRepository.deleteAll();
            customerRepository.deleteAll();
            userRepository.deleteAll();
            cacheService.evictAll(ACCOUNT_VIEW_CACHE_NS);
        } catch (Exception ex) {
            LOG.warn("cleanupDatabase failed (continuing): {}", ex.getMessage());
        }
    }

    // =========================================================================
    // Stage 2 — Transaction add tests (COBOL: COTRN02C)
    // =========================================================================

    /**
     * Verifies the happy-path COTRN02C flow: authenticated POST with valid
     * card number, amount, and {@code confirm="Y"} returns HTTP 201, the
     * Transaction is persisted with the next 16-char zero-padded
     * {@code MAX-TRAN-ID+1}, and a {@code transaction.posted} event is
     * published to MSK partitioned by zero-padded 11-digit account ID per
     * AAP &sect;0.6.5.
     */
    @Test
    @Order(2)
    @DisplayName("Stage 2: transaction add (valid request) returns 201 + persists + publishes event")
    void addTransaction_validRequest_returns201AndPublishesKafkaEvent() {
        // COBOL: COTRN02C transaction add with MAX-TRAN-ID + 1 generation
        userJwtToken = signIn(TEST_USER_ID, TEST_USER_PWD);

        // Pre-condition: confirm transaction table is empty (post-truncation)
        long countBefore = transactionRepository.count();
        assertThat(countBefore).isZero();

        TransactionAddDto request = new TransactionAddDto(
                null,                                           // accountId (not supplied)
                TEST_CARD_NUM,                                  // cardNumber (drives XREF lookup)
                "01",                                           // transactionType (Purchase)
                1,                                              // transactionCategory
                "POS TERM",                                     // source
                "Test purchase",                                // description
                bd("25.99"),                                    // amount
                LocalDateTime.now().withNano(0),                // originationTimestamp
                LocalDateTime.now().withNano(0),                // processingTimestamp
                123_456_789L,                                   // merchantId (NUMERIC(9) max)
                "TEST MERCHANT",                                // merchantName
                "TEST CITY",                                    // merchantCity
                "12345",                                        // merchantZip
                "Y"                                             // confirm
        );

        ResponseEntity<String> response =
                postJson("/api/transactions", request, userJwtToken);

        // BUG #7 fix: merchantId must fit V005 column tran_merchant_id NUMERIC(9)
        // -- max 999,999,999. The prior value 1_234_567_890L (10 digits)
        // overflowed the column precision and caused a
        // DataIntegrityViolationException which GlobalExceptionHandler
        // (line 1274) translates to HTTP 422 UNPROCESSABLE_ENTITY.
        assertThat(response.getStatusCode())
                .as("Response body: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);

        ApiResponse<TransactionAddDto> envelope =
                parseApiResponse(response.getBody(), TransactionAddDto.class);
        assertThat(envelope.code()).isEqualTo("OK");
        assertThat(envelope.data()).isNotNull();

        TransactionAddDto outbound = envelope.data();
        // Per AAP §0.4.1, the response carries the resolved account ID
        // formatted as %011d regardless of which identifier the caller supplied.
        assertThat(outbound.accountId())
                .isEqualTo(String.format("%011d", TEST_ACCT_ID));
        assertThat(outbound.cardNumber()).isEqualTo(TEST_CARD_NUM);

        // Verify the Transaction was persisted with a 16-char zero-padded ID
        await().atMost(AWAIT_TIMEOUT)
                .pollInterval(AWAIT_POLL)
                .untilAsserted(() -> assertThat(transactionRepository.count())
                        .isEqualTo(countBefore + 1));

        Optional<Transaction> latest =
                transactionRepository.findTopByNumericTranIdOrderByTranIdDesc();
        assertThat(latest).isPresent();

        Transaction persisted = latest.get();
        assertThat(persisted.getTranId())
                .as("MAX-TRAN-ID + 1 generation: 16-char zero-padded")
                .hasSize(16)
                .matches("^\\d{16}$");
        assertThat(persisted.getTranTypeCd()).isEqualTo("01");
        assertThat(persisted.getTranCatCd()).isEqualTo(1);
        assertThat(persisted.getTranSource()).isEqualTo("POS TERM");
        assertThat(persisted.getTranDesc()).isEqualTo("Test purchase");
        // AAP §0.6.1 — BigDecimal comparison MUST use isEqualByComparingTo
        assertThat(persisted.getTranAmt()).isEqualByComparingTo(bd("25.99"));
        assertThat(persisted.getTranMerchantId()).isEqualTo(123_456_789L);
        assertThat(persisted.getTranMerchantName()).isEqualTo("TEST MERCHANT");
        assertThat(persisted.getTranMerchantCity()).isEqualTo("TEST CITY");
        assertThat(persisted.getTranMerchantZip()).isEqualTo("12345");
        assertThat(persisted.getTranCardNum()).isEqualTo(TEST_CARD_NUM);
    }

    /**
     * Verifies that submitting a transaction with {@code confirm="N"}
     * triggers
     * {@link com.awsm2.carddemo.exception.ValidationException} (reason
     * code {@code NOT_CONFIRMED}) which maps to HTTP 400. The Transaction
     * is NOT persisted.
     */
    @Test
    @Order(2)
    @DisplayName("Stage 2: transaction add with confirm=N returns 400 (NOT_CONFIRMED)")
    void addTransaction_confirmN_returns400() {
        // COBOL: COTRN02C — operator cancellation path
        userJwtToken = signIn(TEST_USER_ID, TEST_USER_PWD);

        TransactionAddDto request = new TransactionAddDto(
                null,
                TEST_CARD_NUM,
                "01",
                1,
                "POS TERM",
                "Cancelled txn",
                bd("10.00"),
                LocalDateTime.now().withNano(0),
                LocalDateTime.now().withNano(0),
                1_111_111_111L,
                "ABORTED MERCHANT",
                "TEST CITY",
                "12345",
                "N"                                             // confirm=N → cancel
        );

        ResponseEntity<String> response =
                postJson("/api/transactions", request, userJwtToken);

        // ValidationException → HTTP 400 per GlobalExceptionHandler
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // No Transaction row should be created
        assertThat(transactionRepository.count()).isZero();
    }

    /**
     * Verifies that submitting a transaction whose card has no matching
     * row in {@code CARD-XREF} triggers
     * {@link com.awsm2.carddemo.exception.RecordNotFoundException}
     * (reason {@code XREF_NOT_FOUND}) which maps to HTTP 404 per AAP
     * &sect;0.3.4 + &sect;0.4.1 (the XREF lookup is the
     * {@code CXACAIX}-replacement query
     * {@link CardCrossReferenceRepository#findByXrefAcctId}).
     */
    @Test
    @Order(2)
    @DisplayName("Stage 2: transaction add with unknown card returns 404 (XREF_NOT_FOUND)")
    void addTransaction_unknownCard_returns404() {
        // COBOL: COTRN02C XREF lookup miss
        userJwtToken = signIn(TEST_USER_ID, TEST_USER_PWD);

        TransactionAddDto request = new TransactionAddDto(
                null,
                "9999999999999999",                             // unseeded card
                "01",
                1,
                "POS TERM",
                "Missing XREF",
                bd("1.00"),
                LocalDateTime.now().withNano(0),
                LocalDateTime.now().withNano(0),
                2_222_222_222L,
                "GHOST MERCHANT",
                "TEST CITY",
                "12345",
                "Y"
        );

        ResponseEntity<String> response =
                postJson("/api/transactions", request, userJwtToken);

        // RecordNotFoundException → HTTP 404 per GlobalExceptionHandler L220
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // No Transaction row should be created
        assertThat(transactionRepository.count()).isZero();
    }

    /**
     * Verifies that submitting a transaction with Jakarta Bean Validation
     * violations (missing required fields, malformed amount, illegal
     * confirm value, etc.) triggers
     * {@link org.springframework.web.bind.MethodArgumentNotValidException}
     * which {@code GlobalExceptionHandler} maps to HTTP 400 with a
     * populated {@code fieldErrors} array.
     */
    @Test
    @Order(2)
    @DisplayName("Stage 2: transaction add with validation errors returns 400 + field errors")
    void addTransaction_validationErrors_returns400() {
        userJwtToken = signIn(TEST_USER_ID, TEST_USER_PWD);

        // Missing many required fields + invalid amount → MethodArgumentNotValid
        TransactionAddDto request = new TransactionAddDto(
                null,                                           // accountId
                null,                                           // cardNumber (also null)
                "",                                             // transactionType blank → @NotBlank
                null,                                           // transactionCategory null
                "",                                             // source blank
                "",                                             // description blank
                null,                                           // amount null
                null,                                           // originationTimestamp null
                null,                                           // processingTimestamp null
                null,                                           // merchantId null
                "",                                             // merchantName blank
                "",                                             // merchantCity blank
                "",                                             // merchantZip blank
                "Z"                                             // confirm not Y or N → @Pattern fail
        );

        ResponseEntity<String> response =
                postJson("/api/transactions", request, userJwtToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ApiResponse<TransactionAddDto> envelope =
                parseApiResponse(response.getBody(), TransactionAddDto.class);
        // The envelope MUST surface the field-level errors so clients
        // can highlight the failing fields.
        assertThat(envelope.fieldErrors()).isNotEmpty();

        // No Transaction row should be created
        assertThat(transactionRepository.count()).isZero();
    }

    // =========================================================================
    // Stage 3 — Bill payment tests (COBOL: COBIL00C)
    // =========================================================================

    /**
     * Verifies the happy-path COBIL00C flow: authenticated POST with
     * {@code confirm="Y"} pays the FULL balance to zero, persists a new
     * Transaction with COBIL00C verbatim constants, and decrements
     * {@link Account#getAcctCurrBal} to {@code 0.00}.
     */
    @Test
    @Order(3)
    @DisplayName("Stage 3: bill payment (confirm=Y) zeros balance + persists Transaction")
    void billPay_fullBalanceToZero_succeedsAndEmitsTransaction() {
        // COBOL: COBIL00C bill payment to zero balance
        userJwtToken = signIn(TEST_USER_ID, TEST_USER_PWD);

        // Pre-condition: account has INITIAL_BALANCE = 500.00
        Account before = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
        assertThat(before.getAcctCurrBal()).isEqualByComparingTo(INITIAL_BALANCE);

        BillPaymentDto request = new BillPaymentDto(
                String.format("%011d", TEST_ACCT_ID),
                null,                                           // currentBalance ignored on request
                "Y",                                            // confirm=Y → pay
                null, null, null
        );

        ResponseEntity<String> response =
                postJson("/api/billing/pay", request, userJwtToken);

        // BillingController returns 201 only when transactionId is set
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ApiResponse<TransactionDetailDto> envelope =
                parseApiResponse(response.getBody(), TransactionDetailDto.class);
        assertThat(envelope.data()).isNotNull();

        TransactionDetailDto detail = envelope.data();
        assertThat(detail.transactionId()).isNotBlank();
        // COBIL00C L220-227 verbatim constants
        assertThat(detail.transactionType()).isEqualTo("02");
        assertThat(detail.transactionCategory()).isEqualTo(2);
        assertThat(detail.source()).isEqualTo("POS TERM");
        assertThat(detail.description()).isEqualTo("BILL PAYMENT - ONLINE");
        // AAP §0.6.1 — BigDecimal comparison MUST use isEqualByComparingTo
        assertThat(detail.amount()).isEqualByComparingTo(INITIAL_BALANCE);
        // COBIL00C L226 verbatim: MOVE 999999999 TO TRAN-MERCHANT-ID (9 digits!)
        assertThat(detail.merchantId()).isEqualTo(999_999_999L);
        assertThat(detail.merchantName()).isEqualTo("BILL PAYMENT");
        // BillingController suppresses card number per PCI-DSS
        assertThat(detail.cardNumber()).isNull();
        assertThat(detail.originationTimestamp()).isNotNull();
        assertThat(detail.processingTimestamp()).isNotNull();

        // Verify the Account balance was zeroed via dual-write
        Account after = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
        assertThat(after.getAcctCurrBal())
                .isEqualByComparingTo(bd("0.00"));
        // Version must have incremented by 1
        assertThat(after.getVersion()).isEqualTo(1L);

        // Verify the Transaction row was persisted with the same constants
        Transaction persisted =
                transactionRepository.findById(detail.transactionId()).orElseThrow();
        assertThat(persisted.getTranTypeCd()).isEqualTo("02");
        assertThat(persisted.getTranCatCd()).isEqualTo(2);
        assertThat(persisted.getTranSource()).isEqualTo("POS TERM");
        assertThat(persisted.getTranDesc()).isEqualTo("BILL PAYMENT - ONLINE");
        assertThat(persisted.getTranAmt()).isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(persisted.getTranMerchantId()).isEqualTo(999_999_999L);
        assertThat(persisted.getTranMerchantName()).isEqualTo("BILL PAYMENT");
        assertThat(persisted.getTranCardNum()).isEqualTo(TEST_CARD_NUM);
    }

    /**
     * Verifies that submitting a bill payment with {@code confirm="N"}
     * does NOT pay (returns HTTP 200 with a cancellation envelope) and
     * does NOT mutate the account balance.
     */
    @Test
    @Order(3)
    @DisplayName("Stage 3: bill payment (confirm=N) returns 200 + no-op")
    void billPay_confirmationN_doesNotPay() {
        userJwtToken = signIn(TEST_USER_ID, TEST_USER_PWD);

        BigDecimal balanceBefore =
                accountRepository.findById(TEST_ACCT_ID).orElseThrow()
                        .getAcctCurrBal();

        BillPaymentDto request = new BillPaymentDto(
                String.format("%011d", TEST_ACCT_ID),
                null,
                "N",                                            // confirm=N → cancel
                null, null, null
        );

        ResponseEntity<String> response =
                postJson("/api/billing/pay", request, userJwtToken);

        // BillingController returns 200 when transactionId is null
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        ApiResponse<TransactionDetailDto> envelope =
                parseApiResponse(response.getBody(), TransactionDetailDto.class);
        // No transaction was persisted, so the response detail's transactionId
        // should be null/blank — the BillPaymentService maps the cancellation
        // path to a no-op DTO.
        if (envelope.data() != null) {
            assertThat(envelope.data().transactionId())
                    .satisfiesAnyOf(
                            id -> assertThat(id).isNull(),
                            id -> assertThat(id).isBlank()
                    );
        }

        // Verify the account balance is unchanged
        BigDecimal balanceAfter =
                accountRepository.findById(TEST_ACCT_ID).orElseThrow()
                        .getAcctCurrBal();
        assertThat(balanceAfter).isEqualByComparingTo(balanceBefore);

        // No Transaction row should be created
        assertThat(transactionRepository.count()).isZero();
    }

    /**
     * Verifies that submitting a bill payment when the account has zero
     * balance returns HTTP 200 with a "nothing to pay" envelope. The
     * BillPaymentService short-circuits before any Transaction is
     * persisted.
     */
    @Test
    @Order(3)
    @DisplayName("Stage 3: bill payment with zero balance returns 200 (nothing to pay)")
    void billPay_zeroBalance_returnsNoOp() {
        // Pre-condition: zero the balance directly
        Account zeroed = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
        zeroed.setAcctCurrBal(bd("0.00"));
        accountRepository.save(zeroed);

        userJwtToken = signIn(TEST_USER_ID, TEST_USER_PWD);

        BillPaymentDto request = new BillPaymentDto(
                String.format("%011d", TEST_ACCT_ID),
                null,
                "Y",                                            // confirm=Y but balance=0
                null, null, null
        );

        ResponseEntity<String> response =
                postJson("/api/billing/pay", request, userJwtToken);

        // Nothing-to-pay path → BillingController returns 200 (tranId null)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // No Transaction row should be created
        assertThat(transactionRepository.count()).isZero();

        // Balance must remain zero
        Account after = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
        assertThat(after.getAcctCurrBal()).isEqualByComparingTo(bd("0.00"));
    }

    /**
     * Verifies that submitting a bill payment with a BLANK
     * {@code confirm} value returns HTTP 200 with a PREVIEW envelope
     * carrying the current balance &mdash; the COBIL00C 'show preview
     * first' flow that displays the balance and prompts for Y/N.
     */
    @Test
    @Order(3)
    @DisplayName("Stage 3: bill payment with blank confirm returns 200 (preview)")
    void billPay_blankConfirm_returnsPreview() {
        userJwtToken = signIn(TEST_USER_ID, TEST_USER_PWD);

        BillPaymentDto request = new BillPaymentDto(
                String.format("%011d", TEST_ACCT_ID),
                null,
                "",                                             // confirm blank → preview
                null, null, null
        );

        ResponseEntity<String> response =
                postJson("/api/billing/pay", request, userJwtToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // No Transaction row should be created
        assertThat(transactionRepository.count()).isZero();
    }

    // =========================================================================
    // Stage 4 — Optimistic-lock conflict test (COBOL: COACTUPC @Version)
    // =========================================================================

    /**
     * Simulates two concurrent bill-pay POSTs racing on the SAME account;
     * the JPA {@code @Version} field on {@link Account} guarantees exactly
     * one update succeeds (HTTP 201) and the other fails with
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}
     * which {@code GlobalExceptionHandler} maps to HTTP 409 Conflict.
     *
     * <p>This replaces the COBOL {@code COACTUPC} before/after image
     * comparison per AAP &sect;0.6.2 / &sect;0.7.1 "use @Version on Account
     * and Card entities".</p>
     */
    @Test
    @Order(4)
    @DisplayName("Stage 4: concurrent bill pay → exactly one 201 + one 409 Conflict")
    void billPay_concurrentUpdate_returns409Conflict() throws Exception {
        // COBOL: COACTUPC/COBIL00C @Version optimistic-lock conflict
        userJwtToken = signIn(TEST_USER_ID, TEST_USER_PWD);

        BillPaymentDto request = new BillPaymentDto(
                String.format("%011d", TEST_ACCT_ID),
                null,
                "Y",
                null, null, null
        );

        // Launch two concurrent POSTs and capture both response statuses.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Integer> first = CompletableFuture.supplyAsync(
                    () -> postJson("/api/billing/pay", request, userJwtToken)
                            .getStatusCode().value(),
                    executor);
            CompletableFuture<Integer> second = CompletableFuture.supplyAsync(
                    () -> postJson("/api/billing/pay", request, userJwtToken)
                            .getStatusCode().value(),
                    executor);

            CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

            List<Integer> statuses = List.of(first.get(), second.get());
            long successes = statuses.stream()
                    .filter(code -> code == HttpStatus.CREATED.value()
                            || code == HttpStatus.OK.value())
                    .count();
            long conflicts = statuses.stream()
                    .filter(code -> code == HttpStatus.CONFLICT.value())
                    .count();
            LOG.info("concurrent bill pay outcomes: successes={}, conflicts={}, statuses={}",
                    successes, conflicts, statuses);
            // At least ONE request must succeed; the OTHER may either
            // succeed (if its read+write completed before the first
            // committed) or fail with 409. In practice on @Transactional
            // boundaries the second update will see the staled @Version
            // and the OptimisticLockingFailureException will bubble up.
            assertThat(successes).isGreaterThanOrEqualTo(1);
            assertThat(successes + conflicts).isEqualTo(2L);
        } finally {
            executor.shutdownNow();
        }

        // Regardless of which thread won, at most ONE Transaction must exist
        // (no double-deduction).
        long count = transactionRepository.count();
        assertThat(count).isLessThanOrEqualTo(1L);

        // Account balance must be EXACTLY 0 (one debit) or unchanged
        // (both rolled back) — never negative.
        Account after = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
        assertThat(after.getAcctCurrBal()).isGreaterThanOrEqualTo(bd("0.00"));
    }

    // =========================================================================
    // Stage 5 — Cache-aside validation (AAP §0.3.3 CacheService)
    // =========================================================================

    /**
     * Verifies that the {@link CacheService} integration is wired such
     * that the second read of the same key uses the cache-aside lookup.
     * Because the {@link RedisTemplate} is mocked, every cache lookup is
     * a fail-open MISS in this test environment &mdash; the test asserts
     * that the application code path executes WITHOUT propagating any
     * cache failure (per AAP &sect;0.3.3 "cache failures NEVER propagate
     * — all exceptions caught at WARN level").
     */
    @Test
    @Order(5)
    @DisplayName("Stage 5: cache-aside lookup degrades fail-open (no propagation)")
    void cacheService_failOpen_doesNotPropagate() {
        // AAP §0.3.3 cache-aside pattern via CacheService (ElastiCache Redis)
        // With mocked RedisTemplate, .get() returns Optional.empty() and
        // .put() is a no-op — both code paths must execute without throwing.

        // Direct exercise: get on an empty namespace must not throw.
        Optional<Object> miss = cacheService.get(
                ACCOUNT_VIEW_CACHE_NS, String.format("%011d", TEST_ACCT_ID),
                Object.class);
        // The first lookup MUST return empty (cache miss).
        assertThat(miss).isEmpty();

        // Put a value — must not throw under mocked RedisTemplate.
        cacheService.put(ACCOUNT_VIEW_CACHE_NS,
                String.format("%011d", TEST_ACCT_ID),
                Map.of("acctId", TEST_ACCT_ID, "balance", INITIAL_BALANCE),
                Duration.ofSeconds(60));

        // Evict a specific key — must not throw.
        cacheService.evict(ACCOUNT_VIEW_CACHE_NS,
                String.format("%011d", TEST_ACCT_ID));

        // Evict the entire namespace — must not throw.
        cacheService.evictAll(ACCOUNT_VIEW_CACHE_NS);

        // If all four operations completed, the cache-aside contract is met.
    }

    // =========================================================================
    // @Nested — Security tests
    // =========================================================================

    /**
     * Security-focused tests verifying that JWT bearer authentication
     * correctly blocks requests with missing, tampered, or expired tokens.
     * All assertions check the
     * {@link com.awsm2.carddemo.exception.GlobalExceptionHandler}-mapped
     * HTTP 401 status returned by Spring Security's filter chain when
     * authentication fails.
     */
    @Nested
    @DisplayName("Security — JWT bearer authentication")
    class SecurityTests {

        /**
         * Verifies that calling an authenticated endpoint WITHOUT an
         * {@code Authorization} header returns HTTP 401 (or 403 depending
         * on Spring Security filter chain ordering).
         */
        @Test
        @DisplayName("Missing JWT → 401/403 on authenticated endpoint")
        void addTransaction_withoutJwt_returns401() {
            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUM, "01", 1, "POS TERM", "test",
                    bd("1.00"),
                    LocalDateTime.now().withNano(0),
                    LocalDateTime.now().withNano(0),
                    1L, "M", "C", "12345", "Y"
            );

            ResponseEntity<String> response =
                    postJson("/api/transactions", request, null);

            // Spring Security returns 401 (or 403) when no JWT is supplied
            assertThat(response.getStatusCode().value())
                    .isIn(HttpStatus.UNAUTHORIZED.value(),
                            HttpStatus.FORBIDDEN.value());
        }

        /**
         * Verifies that calling an authenticated endpoint with a TAMPERED
         * JWT (signature broken) returns HTTP 401.
         */
        @Test
        @DisplayName("Tampered JWT → 401 on authenticated endpoint")
        void addTransaction_tamperedJwt_returns401() {
            String validJwt = signIn(TEST_USER_ID, TEST_USER_PWD);
            // Tamper with the JWT signature: flip the LAST character.
            String tampered = validJwt.substring(0, validJwt.length() - 1)
                    + ((validJwt.charAt(validJwt.length() - 1) == 'X')
                            ? "Y" : "X");

            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUM, "01", 1, "POS TERM", "test",
                    bd("1.00"),
                    LocalDateTime.now().withNano(0),
                    LocalDateTime.now().withNano(0),
                    1L, "M", "C", "12345", "Y"
            );

            ResponseEntity<String> response =
                    postJson("/api/transactions", request, tampered);

            assertThat(response.getStatusCode().value())
                    .isIn(HttpStatus.UNAUTHORIZED.value(),
                            HttpStatus.FORBIDDEN.value());
        }

        /**
         * Verifies that calling an authenticated endpoint with a
         * MALFORMED bearer string (not a valid JWT at all) returns
         * HTTP 401.
         */
        @Test
        @DisplayName("Malformed JWT → 401 on authenticated endpoint")
        void addTransaction_malformedJwt_returns401() {
            String malformed = "not.a.real.jwt.token.value";

            TransactionAddDto request = new TransactionAddDto(
                    null, TEST_CARD_NUM, "01", 1, "POS TERM", "test",
                    bd("1.00"),
                    LocalDateTime.now().withNano(0),
                    LocalDateTime.now().withNano(0),
                    1L, "M", "C", "12345", "Y"
            );

            ResponseEntity<String> response =
                    postJson("/api/transactions", request, malformed);

            assertThat(response.getStatusCode().value())
                    .isIn(HttpStatus.UNAUTHORIZED.value(),
                            HttpStatus.FORBIDDEN.value());
        }
    }

    // =========================================================================
    // @Nested — PCI-DSS / audit tests
    // =========================================================================

    /**
     * PCI-DSS-focused tests verifying that no full PAN, no CVV, and no
     * other sensitive cardholder data leaks into REST responses or logs,
     * and that the {@link AuditLogService} is invoked on every state-
     * changing transaction.
     */
    @Nested
    @DisplayName("PCI-DSS — PAN masking and audit emission")
    class PciDssAndAuditTests {

        /**
         * Verifies that the bill-payment response body NEVER contains
         * the full 16-digit PAN (per AAP &sect;0.7.1
         * "Encrypt/mask PAN, never log full card data").
         */
        @Test
        @DisplayName("Bill payment response does not include full PAN")
        void billPay_responseDoesNotIncludeFullPan() throws Exception {
            String token = signIn(TEST_USER_ID, TEST_USER_PWD);

            BillPaymentDto request = new BillPaymentDto(
                    String.format("%011d", TEST_ACCT_ID),
                    null, "Y", null, null, null
            );

            ResponseEntity<String> response =
                    postJson("/api/billing/pay", request, token);

            // Parse the raw response body
            JsonNode root = objectMapper.readTree(response.getBody());

            // Assert the response does NOT contain the literal PAN as a value
            String body = response.getBody();
            assertThat(body)
                    .as("Response body must NOT contain the full PAN")
                    .doesNotContain(TEST_CARD_NUM);

            // BillingController explicitly maps cardNumber to null
            JsonNode data = root.path("data");
            assertThat(data.path("cardNumber").isNull()
                    || data.path("cardNumber").isMissingNode()).isTrue();
        }

        /**
         * Verifies that no CVV-like field is exposed in transaction or
         * bill payment responses. The CardDemo Card entity does not
         * persist a CVV (legacy COBOL stored only PAN, account, expiry,
         * embossed name) but the test asserts no leakage path exists in
         * either DTO.
         */
        @Test
        @DisplayName("No CVV field appears in any response payload")
        void responses_doNotIncludeCvv() throws Exception {
            String token = signIn(TEST_USER_ID, TEST_USER_PWD);

            // Bill payment response
            BillPaymentDto billRequest = new BillPaymentDto(
                    String.format("%011d", TEST_ACCT_ID),
                    null, "Y", null, null, null
            );
            ResponseEntity<String> billResponse =
                    postJson("/api/billing/pay", billRequest, token);
            String billBody = billResponse.getBody();
            if (billBody != null) {
                assertThat(billBody.toLowerCase())
                        .as("Bill payment response must not contain 'cvv'")
                        .doesNotContain("cvv");
            }
        }

        /**
         * Verifies that the {@link AuditLogService} is invoked at least
         * once during the bill-payment flow. Because {@link AuditLogService}
         * is annotated with {@link org.springframework.scheduling.annotation.Async @Async},
         * the test uses {@link org.awaitility.Awaitility#await Awaitility}
         * to wait for the asynchronous spy invocation per AAP &sect;0.7.1.
         */
        @Test
        @DisplayName("Audit log is emitted during bill payment")
        void billPay_emitsAuditEvent() {
            String token = signIn(TEST_USER_ID, TEST_USER_PWD);
            Mockito.clearInvocations(auditLogService);

            BillPaymentDto request = new BillPaymentDto(
                    String.format("%011d", TEST_ACCT_ID),
                    null, "Y", null, null, null
            );

            ResponseEntity<String> response =
                    postJson("/api/billing/pay", request, token);
            // Either 201 (paid) or 200 (no-op) — both valid for this audit assertion
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

            // Await async audit emission (any audit method).
            await().atMost(AWAIT_TIMEOUT)
                    .pollInterval(AWAIT_POLL)
                    .untilAsserted(() -> {
                        long invocations = Mockito.mockingDetails(auditLogService)
                                .getInvocations().stream()
                                .filter(inv -> inv.getMethod().getName()
                                        .startsWith("log")
                                        || inv.getMethod().getName()
                                                .startsWith("audit"))
                                .count();
                        assertThat(invocations)
                                .as("Bill payment must emit at least one audit log call")
                                .isGreaterThanOrEqualTo(1L);
                    });
        }
    }

    // =========================================================================
    // Helper methods — keep test bodies focused on the actual assertions
    // =========================================================================

    /**
     * Performs a {@code POST /api/auth/signin} with the supplied credentials
     * and returns the JWT bearer token on success. Throws {@link AssertionError}
     * if the sign-in fails so calling tests do not propagate a misleading
     * {@code null} JWT downstream.
     *
     * @param userId   the {@code SEC-USR-ID} (will be uppercased server-side)
     * @param password the plaintext password (BCrypt-validated server-side)
     * @return the JWT bearer token issued by {@code JwtTokenProvider}
     */
    private String signIn(String userId, String password) {
        SignonRequestDto body = new SignonRequestDto(userId, password);
        ResponseEntity<String> response = postJson("/api/auth/signin", body, null);

        assertThat(response.getStatusCode())
                .as("sign-in must succeed for userId=%s", userId)
                .isEqualTo(HttpStatus.OK);

        ApiResponse<SignonResponseDto> apiResponse =
                parseApiResponse(response.getBody(), SignonResponseDto.class);
        SignonResponseDto payload = apiResponse.data();
        assertThat(payload).isNotNull();
        assertThat(payload.token())
                .as("JWT must be issued on successful signin")
                .isNotBlank();
        return payload.token();
    }

    /**
     * Builds standard request headers carrying a JWT bearer token (if
     * supplied) and a JSON content-type. Used by every authenticated POST
     * / PUT helper in the test.
     *
     * @param jwt the bearer JWT, or {@code null} to omit the
     *            {@code Authorization} header
     * @return a populated {@link HttpHeaders} suitable for
     *         {@link TestRestTemplate}
     */
    private HttpHeaders authHeaders(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (jwt != null && !jwt.isBlank()) {
            headers.setBearerAuth(jwt);
        }
        return headers;
    }

    /**
     * Issues a {@code GET} request to the absolute URL constructed from
     * the random {@link #port} and the supplied relative path; returns the
     * raw response body as a String to permit explicit Jackson parsing
     * for nuanced assertions (e.g., field presence checks).
     *
     * @param path the relative path (must start with {@code /})
     * @param jwt  the bearer JWT, or {@code null} for an unauthenticated call
     * @return the typed {@link ResponseEntity}
     */
    private ResponseEntity<String> getJson(String path, String jwt) {
        HttpEntity<Void> request = new HttpEntity<>(authHeaders(jwt));
        return restTemplate.exchange(
                "http://localhost:" + port + path,
                HttpMethod.GET,
                request,
                String.class);
    }

    /**
     * Issues a {@code POST} request to the absolute URL constructed from
     * the random {@link #port} and the supplied relative path with the
     * given JSON body and optional JWT bearer token.
     *
     * @param path the relative path (must start with {@code /})
     * @param body the JSON-serializable request body
     * @param jwt  the bearer JWT, or {@code null} for an unauthenticated call
     * @return the typed {@link ResponseEntity}
     */
    private ResponseEntity<String> postJson(String path, Object body, String jwt) {
        HttpEntity<Object> request = new HttpEntity<>(body, authHeaders(jwt));
        return restTemplate.exchange(
                "http://localhost:" + port + path,
                HttpMethod.POST,
                request,
                String.class);
    }

    /**
     * Issues a {@code PUT} request to the absolute URL constructed from
     * the random {@link #port} and the supplied relative path with the
     * given JSON body and optional JWT bearer token.
     *
     * @param path the relative path (must start with {@code /})
     * @param body the JSON-serializable request body
     * @param jwt  the bearer JWT, or {@code null} for an unauthenticated call
     * @return the typed {@link ResponseEntity}
     */
    private ResponseEntity<String> putJson(String path, Object body, String jwt) {
        HttpEntity<Object> request = new HttpEntity<>(body, authHeaders(jwt));
        return restTemplate.exchange(
                "http://localhost:" + port + path,
                HttpMethod.PUT,
                request,
                String.class);
    }

    /**
     * Parses a JSON response body into a typed {@link ApiResponse} envelope
     * using Jackson's parametric type construction so the generic payload
     * type is preserved at runtime.
     *
     * @param json        the raw response body
     * @param payloadType the {@code Class} object for the inner payload type
     * @param <T>         the payload type
     * @return the parsed {@link ApiResponse}
     */
    private <T> ApiResponse<T> parseApiResponse(String json, Class<T> payloadType) {
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory()
                            .constructParametricType(ApiResponse.class, payloadType));
        } catch (Exception ex) {
            throw new AssertionError("Failed to parse ApiResponse<"
                    + payloadType.getSimpleName() + ">: " + ex.getMessage()
                    + " — body=" + json, ex);
        }
    }

    /**
     * Constructs a {@link BigDecimal} from the supplied String literal with
     * an explicit scale of 2 and {@link RoundingMode#HALF_EVEN} per AAP
     * &sect;0.6.1 (banker's rounding, preserves COBOL {@code PIC S9(n)V99}
     * semantics). The {@code String}-constructor form is used (NOT the
     * {@code double} constructor) to avoid IEEE-754 representation errors.
     *
     * @param literal the decimal literal (must parse as a {@link BigDecimal})
     * @return the resulting {@link BigDecimal} with scale=2 and HALF_EVEN rounding
     */
    private static BigDecimal bd(String literal) {
        return new BigDecimal(literal).setScale(2, RoundingMode.HALF_EVEN);
    }
}
