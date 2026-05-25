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
// Replaces CICS flow: COSGN00C → COMEN01C → COACTVWC → COACTUPC
// =====================================================================
// AAP §0.3.4 — REST endpoint inventory:
//   POST /api/auth/signin       (← COSGN00C  / Tran-ID CC00)
//   GET  /api/menu/main         (← COMEN01C  / Tran-ID CM00)
//   GET  /api/menu/admin        (← COADM01C  / Tran-ID CA00)
//   GET  /api/accounts/{id}     (← COACTVWC  / Tran-ID CAVW)
//   PUT  /api/accounts/{id}     (← COACTUPC  / Tran-ID CAUP)
// AAP §0.4.1 — AccountUpdateService (← COACTUPC) is the ONLY program in
//   the source codebase that uses EXEC CICS SYNCPOINT ROLLBACK. The Java
//   target wraps the dual Account+Customer update in
//   @Transactional(rollbackFor = Exception.class) and uses JPA @Version
//   for optimistic locking, replacing the COBOL before/after image
//   comparison from paragraph 9700-CHECK-CHANGE-IN-REC.
// AAP §0.6.1 — All monetary fields (creditLimit, currentBalance, etc.)
//   use BigDecimal with HALF_EVEN rounding and scale=2.
// AAP §0.7.1 — PCI-DSS PII discipline: SSN/PAN masked at DTO toString()
//   layer; no CVV anywhere in responses.
// =====================================================================
import com.awsm2.carddemo.CardDemoApplication;
import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.adapter.SecretsManagerService;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.Customer;
import com.awsm2.carddemo.domain.UserSecurity;
import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.AccountViewDto;
import com.awsm2.carddemo.dto.AdminMenuDto;
import com.awsm2.carddemo.dto.ApiResponse;
import com.awsm2.carddemo.dto.MainMenuDto;
import com.awsm2.carddemo.dto.MenuOptionDto;
import com.awsm2.carddemo.dto.SignonRequestDto;
import com.awsm2.carddemo.dto.SignonResponseDto;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.CustomerRepository;
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
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end integration test for the CardDemo online account workflow:
 * sign-in → menu navigation → account view → account update.  Mirrors the
 * CICS pseudo-conversational flow {@code COSGN00C → COMEN01C → COACTVWC →
 * COACTUPC} described in AAP &sect;0.3.4 and exercises every layer of the
 * Spring Boot target stack end-to-end against the production REST contract.
 *
 * <p><b>What this test validates (AAP &sect;0.3.4 + &sect;0.4.1):</b></p>
 * <ol>
 *   <li>JWT-based sign-on against {@code USRSEC} (UserSecurity) with
 *       BCrypt password matching and {@code SEC-USR-TYPE}-driven role
 *       mapping ({@code 'A'} &rarr; {@code ROLE_ADMIN},
 *       {@code 'U'} &rarr; {@code ROLE_USER}).</li>
 *   <li>Main menu rendering ({@code COMEN02Y.cpy} literal storage) for
 *       authenticated regular and admin users.</li>
 *   <li>Admin menu rendering ({@code COADM02Y.cpy} literal storage) and
 *       Spring Security 6 {@code @PreAuthorize("hasRole('ADMIN')")} gate
 *       returning HTTP 403 for non-admin callers.</li>
 *   <li>Account-view multi-entity join (Account + Customer +
 *       CardCrossReference via the {@code CXACAIX} AIX) with cache-aside
 *       through {@link CacheService}, masked PII in the DTO
 *       {@code toString()} layer, and HTTP 404 / 401 negative paths.</li>
 *   <li>Account-update dual-write inside a single
 *       {@code @Transactional(rollbackFor = Exception.class)} method,
 *       JPA {@code @Version} optimistic-locking with concurrent-update
 *       409 Conflict semantics, {@code ValidationLookupService}
 *       (port of {@code CSLKPCDY.cpy}) integration for state-code and
 *       NANPA area-code validation, and SYNCPOINT ROLLBACK behavior on
 *       partial save failure.</li>
 *   <li>PCI-DSS / audit guarantees from the
 *       {@link PciDssAndAuditTests} nested class: SSN masking, PAN
 *       masking, no CVV in responses, and verified
 *       {@code AuditLogService} emission on account-update.</li>
 * </ol>
 *
 * <h2>Test isolation strategy</h2>
 * <ul>
 *   <li>{@link PostgreSQLContainer} ({@code postgres:16-alpine}, pinned
 *       per AAP &sect;0.5.1 "no {@code latest} tags") satisfies the
 *       Spring context's JPA / Flyway requirements via
 *       {@link ServiceConnection &#64;ServiceConnection}.</li>
 *   <li>{@link ConfluentKafkaContainer} ({@code cp-kafka:7.5.0}, pinned)
 *       is required because {@link com.awsm2.carddemo.service.AccountUpdateService}
 *       publishes {@code account.updated} events via
 *       {@link com.awsm2.carddemo.adapter.KafkaEventPublisher} after
 *       every successful update (AAP &sect;0.4.1).  Without a real
 *       broker the publish would hang or fail and the test could not
 *       assert end-to-end success.</li>
 *   <li>All non-Kafka AWS clients ({@code S3Client}, {@code SfnClient},
 *       {@code SecretsManagerClient}, {@code CloudWatchClient},
 *       {@code GlueClient}, {@code OpenSearchClient}) are replaced with
 *       {@link MockBean &#64;MockBean} stubs.</li>
 *   <li>{@link RedisTemplate} is mocked: the {@link CacheService}
 *       degrades fail-open under mock, so the account-view path exercises
 *       the cache-aside code path even though no real Redis is present.</li>
 *   <li>{@link SecretsManagerService} is overridden via
 *       {@link TestSecretsManagerConfiguration} so the
 *       {@code JwtTokenProvider} {@code @PostConstruct} HS256 key
 *       resolution succeeds offline.</li>
 *   <li>{@link AuditLogService} is wrapped with {@link SpyBean} so the
 *       PCI-DSS / audit nested class can verify emission without
 *       requiring an OpenSearch broker.</li>
 *   <li>Per-test data isolation via {@code @BeforeEach}-driven truncation
 *       and re-seeding of the four relevant tables (accounts, customer,
 *       card_xref, user_security) plus per-test cache evict-all so a
 *       leaked CACHE entry from a prior test cannot mask a DB-state
 *       regression.</li>
 * </ul>
 *
 * <h2>Operational constraints honored by this test (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>No hardcoded credentials, ARNs, or endpoint URLs — all values come
 *       from {@code application-test.yml}, the Testcontainers JDBC URL,
 *       or the {@link DynamicPropertySource}.</li>
 *   <li>No inline AWS SDK calls in business logic — the test exercises
 *       the {@link CacheService}, {@link AuditLogService}, and
 *       {@link JwtTokenProvider} adapters only via Spring DI.</li>
 *   <li>No {@code Thread.sleep()} — asynchronous waits use
 *       {@link org.awaitility.Awaitility#await()} with bounded timeouts.</li>
 *   <li>No {@code System.out.println()} — all diagnostics route through
 *       {@link Logger SLF4J Logger}.</li>
 *   <li>{@code IT.java} suffix routes execution to the Failsafe phase per
 *       {@code pom.xml} plugin configuration.</li>
 *   <li>Package-private class — JUnit 5 does not require {@code public}
 *       visibility, and minimizing access keeps the test internal to
 *       {@code com.awsm2.carddemo.integration}.</li>
 *   <li>All monetary fields use {@link BigDecimal} with
 *       {@code isEqualByComparingTo} — never {@code float}/{@code double},
 *       never {@code .equals(...)}.</li>
 *   <li>BCrypt password seeding uses the production {@link PasswordEncoder}
 *       bean (BCrypt strength=4 under the test profile per
 *       {@code application-test.yml}) so the signon path matches the
 *       production hashing scheme.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.controller.AuthController
 * @see com.awsm2.carddemo.controller.MenuController
 * @see com.awsm2.carddemo.controller.AccountController
 * @see com.awsm2.carddemo.service.SignonService
 * @see com.awsm2.carddemo.service.MenuService
 * @see com.awsm2.carddemo.service.AccountViewService
 * @see com.awsm2.carddemo.service.AccountUpdateService
 */
@SpringBootTest(classes = CardDemoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(EndToEndAccountWorkflowIT.TestSecretsManagerConfiguration.class)
class EndToEndAccountWorkflowIT {

    // -------------------------------------------------------------------------
    // SLF4J Logger — replaces System.out.println per AAP §0.7.1 logging rules
    // -------------------------------------------------------------------------

    /** SLF4J logger for test diagnostics. */
    private static final Logger LOG =
            LoggerFactory.getLogger(EndToEndAccountWorkflowIT.class);

    // -------------------------------------------------------------------------
    // Test constants — seed values for the four reference rows
    // -------------------------------------------------------------------------

    /** Test account ID — 11 digits matching {@code ACCT-ID PIC 9(11)}. */
    private static final Long TEST_ACCT_ID = 99_999_999_999L;

    /** Test customer ID — 9 digits matching {@code CUST-ID PIC 9(09)}. */
    private static final Long TEST_CUST_ID = 999_999_999L;

    /** Test card number — 16 digits matching {@code CARD-NUM PIC X(16)}. */
    private static final String TEST_CARD_NUM = "4111111111111111";

    /** Test customer SSN — 9 digits matching {@code CUST-SSN PIC 9(09)}. */
    private static final Long TEST_CUST_SSN = 123_456_789L;

    /** Test regular-user identifier (USRSEC primary key, 8 chars). */
    private static final String TEST_USER_ID = "TESTUSER";

    /** Test admin-user identifier (USRSEC primary key, 8 chars). */
    private static final String TEST_ADMIN_ID = "TESTADMN";

    /** Plaintext password for {@link #TEST_USER_ID}; BCrypt-hashed at seed. */
    private static final String TEST_USER_PWD = "TESTPASS";

    /** Plaintext password for {@link #TEST_ADMIN_ID}; BCrypt-hashed at seed. */
    private static final String TEST_ADMIN_PWD = "ADMNPASS";

    /** Cache namespace used by AccountViewService for cache-aside. */
    private static final String ACCOUNT_VIEW_CACHE_NS = "account-view";

    /** Default Awaitility bounded timeout for async assertions. */
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);

    /** Default Awaitility per-poll interval. */
    private static final Duration AWAIT_POLL = Duration.ofMillis(200);

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
     * Provides a stubbed {@link SecretsManagerService} bean for this test.
     * The stub returns a deterministic 60-byte ASCII placeholder for any
     * {@code (secretArn, fieldName)} pair so {@code JwtTokenProvider}'s
     * HS256 key-length precondition is satisfied during context refresh.
     */
    @TestConfiguration
    static class TestSecretsManagerConfiguration {

        /** 60-byte test signing key (well above the 32-byte HS256 minimum). */
        private static final String TEST_SIGNING_KEY =
                "test-only-jwt-signing-key-for-account-workflow-it-padded60!";

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
            Mockito.when(stub.getSecretJsonField(Mockito.anyString(), Mockito.anyString()))
                    .thenReturn(Optional.of(TEST_SIGNING_KEY));
            Mockito.when(stub.getSecret(Mockito.anyString()))
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
     * Required because {@code AccountUpdateService} publishes
     * {@code account.updated} events to MSK on every successful update;
     * the test must provide a real broker so the synchronous-send
     * completion does not hang the {@code @Transactional} method.
     *
     * <p>Bound through {@link #registerDynamicProperties(DynamicPropertyRegistry)}
     * rather than {@link ServiceConnection &#64;ServiceConnection} because
     * the application's custom {@code KafkaConfig} reads
     * {@code spring.kafka.bootstrap-servers} directly from
     * {@code KafkaProperties}, not from
     * {@code KafkaConnectionDetails}.</p>
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
     * {@code auditEvent(...)} was invoked during the update flow without
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
     * Spring Boot's test-aware {@link TestRestTemplate} — automatically
     * wired with the random port and configured to NOT throw on
     * 4xx/5xx so we can assert error status codes explicitly.
     */
    @Autowired
    private TestRestTemplate restTemplate;

    /** Account repository — seed + verify {@code Account} rows. */
    @Autowired
    private AccountRepository accountRepository;

    /** Customer repository — seed + verify {@code Customer} rows. */
    @Autowired
    private CustomerRepository customerRepository;

    /**
     * Card cross-reference repository — seeds the
     * {@code CXACAIX}-replacement index row joining account &harr;
     * customer &harr; card so the {@code AccountViewService} join
     * succeeds.
     */
    @Autowired
    private CardCrossReferenceRepository xrefRepository;

    /** User-security repository — seed BCrypt-hashed credential rows. */
    @Autowired
    private UserSecurityRepository userRepository;

    /**
     * Cache adapter — used to evict between tests so cached views from a
     * prior test cannot mask a current test's DB regression.
     */
    @Autowired
    private CacheService cacheService;

    /**
     * JWT issuer/validator — used to decode the sign-in response token
     * and assert the {@code sub} / {@code usrType} claim values
     * (AAP &sect;0.4.1 SignonService).
     */
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /**
     * Spring's BCrypt {@link PasswordEncoder} bean — used to hash test
     * passwords during seed; matches the production hashing scheme so
     * the sign-on path BCrypt comparison succeeds.
     */
    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * Jackson {@link ObjectMapper} — used to parse raw response bodies
     * into {@link JsonNode} for the PCI-DSS PII-leak assertions in the
     * nested class.
     */
    @Autowired
    private ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Per-test state — the JWT bearer token from the most-recent signin
    // -------------------------------------------------------------------------

    /**
     * Bearer JWT for {@link #TEST_USER_ID}; populated by
     * {@link #signIn(String, String)} and consumed by every authenticated
     * test method's {@code Authorization: Bearer …} header.
     */
    private String userJwtToken;

    /** Bearer JWT for {@link #TEST_ADMIN_ID}. */
    private String adminJwtToken;

    // -------------------------------------------------------------------------
    // @BeforeEach — truncate then seed the four reference rows
    // -------------------------------------------------------------------------

    /**
     * Resets the database to a clean per-test state and seeds the four
     * rows the workflow tests rely on: one {@link Customer}, one
     * {@link Account}, one {@link CardCrossReference} (replacing the
     * {@code CXACAIX} alternate index), and two {@link UserSecurity}
     * rows (regular + admin). BCrypt hashing uses the production
     * {@link PasswordEncoder} bean configured with strength=4 under the
     * test profile per {@code application-test.yml}.
     *
     * <p>The {@link CacheService} is evicted across the account-view
     * namespace to defeat any cross-test cache leakage; mocked
     * {@code RedisTemplate} means evict reduces to a no-op but the
     * documented contract still holds for the production code path.</p>
     */
    @BeforeEach
    void seedDatabase() {
        // ---- Truncate in FK-safe order ---------------------------------
        // CardCrossReference references account and customer indirectly
        // via the application logic but the schema does not declare FK
        // constraints between accounts / customer / card_xref — the
        // ordering here is defensive (parent-after-child) to remain
        // safe under future schema tightening.
        xrefRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();
        userRepository.deleteAll();

        // ---- Seed Customer ---------------------------------------------
        // Per AAP §0.4.1 the Customer entity ports CVCUS01Y.cpy.  The
        // 18-arg constructor mirrors the COBOL declaration order; the
        // values exercise the COACTVWC join and the COACTUPC validation
        // paths (state code "CA" — valid via CSLKPCDY.cpy; phone area
        // code "212" — valid NANPA).
        Customer customer = new Customer(
                TEST_CUST_ID,
                "TEST",            // custFirstName
                "T",               // custMiddleName
                "CUSTOMER",        // custLastName
                "1 TEST ST",       // custAddrLine1
                "APT 1",           // custAddrLine2
                "TEST CITY",       // custAddrLine3
                "CA",              // custAddrStateCd (valid US state)
                "USA",             // custAddrCountryCd
                "90210",           // custAddrZip (valid CA ZIP prefix 9)
                "2125551234",      // custPhoneNum1 (NANPA area 212)
                "2125555678",      // custPhoneNum2
                TEST_CUST_SSN,     // custSsn
                "DLTEST0000",      // custGovtIssuedId
                LocalDate.of(1980, 1, 1), // custDobYyyyMmDd
                "EFTACCT001",      // custEftAccountId
                "Y",               // custPriCardHolderInd
                720                // custFicoCreditScore
        );
        customerRepository.save(customer);

        // ---- Seed Account ----------------------------------------------
        // Monetary values built via the String constructor + setScale to
        // guarantee scale=2 HALF_EVEN per AAP §0.6.1 — never via
        // BigDecimal.valueOf(double) which can introduce binary-floating
        // point drift.
        Account account = new Account(
                TEST_ACCT_ID,
                "Y",                                           // acctActiveStatus
                bd("1500.00"),                                 // acctCurrBal
                bd("5000.00"),                                 // acctCreditLimit
                bd("1000.00"),                                 // acctCashCreditLimit
                LocalDate.of(2020, 1, 1),                      // acctOpenDate
                LocalDate.of(2099, 12, 31),                    // acctExpirationDate
                LocalDate.of(2020, 1, 1),                      // acctReissueDate
                bd("0.00"),                                    // acctCurrCycCredit
                bd("0.00"),                                    // acctCurrCycDebit
                "90210",                                       // acctAddrZip
                "DEFAULT"                                      // acctGroupId
        );
        // Explicit version=0 so optimistic-lock assertions have a
        // deterministic baseline on the first save.
        account.setVersion(0L);
        accountRepository.save(account);

        // ---- Seed CardCrossReference -----------------------------------
        // Replaces the COBOL CXACAIX VSAM alternate index — one card
        // points to one customer + one account, and
        // CardCrossReferenceRepository.findByXrefAcctId returns the row
        // for AccountViewService's join + AccountUpdateService's customer
        // lookup.
        CardCrossReference xref = new CardCrossReference(
                TEST_CARD_NUM, TEST_CUST_ID, TEST_ACCT_ID);
        xrefRepository.save(xref);

        // ---- Seed UserSecurity rows (BCrypt-hashed) --------------------
        // SEC-USR-TYPE 'U' → regular user;
        // SEC-USR-TYPE 'A' → admin.
        // Password is encoded by the production BCrypt PasswordEncoder
        // bean (strength=4 under the test profile) so signon's
        // PasswordEncoder.matches(plain, hashed) succeeds end-to-end.
        UserSecurity regularUser = new UserSecurity(
                TEST_USER_ID,
                "Regular",                              // secUsrFname
                "User",                                 // secUsrLname
                passwordEncoder.encode(TEST_USER_PWD),  // secUsrPwd (BCrypt)
                "U"                                     // secUsrType
        );
        UserSecurity adminUser = new UserSecurity(
                TEST_ADMIN_ID,
                "Admin",                                 // secUsrFname
                "User",                                  // secUsrLname
                passwordEncoder.encode(TEST_ADMIN_PWD),  // secUsrPwd (BCrypt)
                "A"                                      // secUsrType
        );
        userRepository.save(regularUser);
        userRepository.save(adminUser);

        // ---- Evict cache to defeat cross-test leakage ------------------
        cacheService.evictAll(ACCOUNT_VIEW_CACHE_NS);

        // ---- Reset audit-log spy interactions per test -----------------
        // The spy bean preserves its production behavior; we only reset
        // the recorded interactions so individual tests can assert
        // emission deltas without prior-test interference.
        Mockito.clearInvocations(auditLogService);

        // ---- Reset per-test cached JWT tokens --------------------------
        userJwtToken = null;
        adminJwtToken = null;

        LOG.info("Seeded test fixture: account={} customer={} card={} users=[{} (U), {} (A)]",
                TEST_ACCT_ID, TEST_CUST_ID, TEST_CARD_NUM,
                TEST_USER_ID, TEST_ADMIN_ID);
    }

    /**
     * Truncates the four reference tables after each test for a
     * symmetric clean-up alongside {@link #seedDatabase()}.  Evicts the
     * account-view cache so background tests cannot read stale data.
     */
    @AfterEach
    void cleanupDatabase() {
        try {
            xrefRepository.deleteAll();
            accountRepository.deleteAll();
            customerRepository.deleteAll();
            userRepository.deleteAll();
            cacheService.evictAll(ACCOUNT_VIEW_CACHE_NS);
        } catch (RuntimeException re) {
            // Defensive — if cleanup throws (e.g., container shutdown
            // race), log at WARN but do not fail subsequent tests.
            LOG.warn("Cleanup encountered exception: {}", re.toString());
        }
    }

    // =========================================================================
    // Stage 1 — Sign-in tests (Order = 1)
    // =========================================================================

    /**
     * Validates the regular-user sign-on path end-to-end:
     * POST {@code /api/auth/signin} with valid TESTUSER credentials,
     * 200 OK, a non-blank JWT, and decoded claims that route the caller
     * to the main-menu (USER role).
     */
    // Replaces CICS flow: COSGN00C / Tran-ID CC00
    @Test
    @Order(1)
    @DisplayName("POST /api/auth/signin with valid USER credentials returns JWT + USER routing")
    void signin_validUser_returnsJwtAndRoutesToMainMenu() {
        // COBOL: COSGN00C:PROCESS-ENTER-KEY + READ-USER-SEC-FILE
        SignonRequestDto request = new SignonRequestDto(TEST_USER_ID, TEST_USER_PWD);

        ResponseEntity<String> response =
                postJson("/api/auth/signin", request, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<SignonResponseDto> body =
                parseApiResponse(response.getBody(), SignonResponseDto.class);
        assertThat(body).as("ApiResponse envelope").isNotNull();
        assertThat(body.code()).isEqualTo("OK");
        assertThat(body.data()).as("Signon response payload").isNotNull();

        SignonResponseDto signon = body.data();
        assertThat(signon.userId()).isEqualTo(TEST_USER_ID);
        assertThat(signon.userType()).as("USER routing per SEC-USR-TYPE 'U'")
                .isEqualTo("U");
        assertThat(signon.token()).as("JWT bearer token").isNotBlank();

        // Decode the JWT and assert the COBOL-derived claims:
        // sub = userId, userType = 'U', firstName/lastName populated.
        Claims claims = jwtTokenProvider.validateToken(signon.token());
        assertThat(claims.getSubject()).isEqualTo(TEST_USER_ID);
        assertThat(claims.get("userType", String.class)).isEqualTo("U");
        assertThat(claims.get("firstName", String.class)).isEqualTo("Regular");
        assertThat(claims.get("lastName", String.class)).isEqualTo("User");
        assertThat(claims.getExpiration()).as("JWT expiration claim")
                .isAfter(new java.util.Date());

        // Persist for downstream tests inside the same instance (each
        // test method gets a fresh instance under JUnit's default
        // PER_METHOD lifecycle, so this is per-method state).
        userJwtToken = signon.token();
        LOG.info("Sign-in succeeded for userId={} usrType=U", TEST_USER_ID);
    }

    /**
     * Validates the admin sign-on path: POST {@code /api/auth/signin}
     * with TESTADMIN credentials returns a JWT whose {@code userType}
     * claim is {@code 'A'} so the client routes to the admin menu
     * ({@code COADM01C}).
     */
    // Replaces CICS flow: COSGN00C → CDEMO-USRTYP-ADMIN → COADM01C
    @Test
    @Order(1)
    @DisplayName("POST /api/auth/signin with valid ADMIN credentials returns JWT + ADMIN routing")
    void signin_admin_routesToAdminMenu() {
        // COBOL: COSGN00C — when SEC-USR-TYPE = 'A', the source XCTLs to
        //        COADM01C; the Java target encodes the user-type into
        //        the JWT and the client decides routing.
        SignonRequestDto request = new SignonRequestDto(TEST_ADMIN_ID, TEST_ADMIN_PWD);

        ResponseEntity<String> response =
                postJson("/api/auth/signin", request, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<SignonResponseDto> body =
                parseApiResponse(response.getBody(), SignonResponseDto.class);
        SignonResponseDto signon = body.data();
        assertThat(signon.userId()).isEqualTo(TEST_ADMIN_ID);
        assertThat(signon.userType()).as("ADMIN routing per SEC-USR-TYPE 'A'")
                .isEqualTo("A");
        assertThat(signon.token()).isNotBlank();

        Claims claims = jwtTokenProvider.validateToken(signon.token());
        assertThat(claims.getSubject()).isEqualTo(TEST_ADMIN_ID);
        assertThat(claims.get("userType", String.class)).isEqualTo("A");
        assertThat(claims.get("firstName", String.class)).isEqualTo("Admin");
        assertThat(claims.get("lastName", String.class)).isEqualTo("User");

        adminJwtToken = signon.token();
        LOG.info("Sign-in succeeded for adminId={} usrType=A", TEST_ADMIN_ID);
    }

    // =========================================================================
    // Stage 2 — Menu navigation tests (Order = 2)
    // =========================================================================

    /**
     * Validates that an authenticated regular user can retrieve the main
     * menu populated from the COBOL {@code COMEN02Y.cpy} literal-storage
     * table. Asserts presence of menu options for the {@code USER}
     * role; admin-only options must not appear.
     */
    // COBOL: COMEN01C main menu via COMEN02Y.cpy menu literal storage
    @Test
    @Order(2)
    @DisplayName("GET /api/menu/main as USER returns main menu options from COMEN02Y.cpy")
    void getMenu_asUser_returnsMainMenu() {
        String jwt = signIn(TEST_USER_ID, TEST_USER_PWD);

        ResponseEntity<String> response =
                getJson("/api/menu/main", jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<MainMenuDto> body =
                parseApiResponse(response.getBody(), MainMenuDto.class);
        assertThat(body.code()).isEqualTo("OK");

        MainMenuDto menu = body.data();
        assertThat(menu).isNotNull();
        assertThat(menu.userType()).as("Main menu rendered for USER")
                .isEqualTo("U");
        assertThat(menu.title()).isNotBlank();
        assertThat(menu.options()).as("Main menu options from COMEN02Y.cpy")
                .isNotEmpty();

        // Every option must be visible to a USER (userType "U" or blank).
        // The COBOL filter is "CDEMO-MENU-OPT-USRTYPE = CDEMO-USRTYPE OR
        // LOW-VALUES" (COMEN01C); the Java port mirrors this in
        // MenuService.getMainMenu.
        for (MenuOptionDto option : menu.options()) {
            assertThat(option.optionNumber()).as("option number positive")
                    .isPositive();
            assertThat(option.label()).as("option label populated")
                    .isNotBlank();
            String userType = option.userType();
            assertThat(userType == null || userType.equals("U") || userType.isBlank())
                    .as("option userType is U or blank for main menu (got %s)", userType)
                    .isTrue();
        }
        LOG.info("Main menu returned {} options for userType=U",
                menu.options().size());
    }

    /**
     * Validates that an authenticated admin user can retrieve the admin
     * menu populated from the COBOL {@code COADM02Y.cpy} literal-storage
     * table. Asserts presence of admin-only options matching the COBOL
     * {@code CARDDEMO-ADMIN-MENU-OPTIONS} table.
     */
    // COBOL: COADM01C admin menu via COADM02Y.cpy menu literal storage
    @Test
    @Order(2)
    @DisplayName("GET /api/menu/admin as ADMIN returns admin menu options from COADM02Y.cpy")
    void getMenu_asAdmin_returnsAdminMenu() {
        String jwt = signIn(TEST_ADMIN_ID, TEST_ADMIN_PWD);

        ResponseEntity<String> response =
                getJson("/api/menu/admin", jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<AdminMenuDto> body =
                parseApiResponse(response.getBody(), AdminMenuDto.class);
        assertThat(body.code()).isEqualTo("OK");

        AdminMenuDto menu = body.data();
        assertThat(menu).isNotNull();
        assertThat(menu.title()).isNotBlank();
        assertThat(menu.options()).as("Admin menu options from COADM02Y.cpy")
                .isNotEmpty();

        // Admin menu options carry SEC-USR-TYPE='A' in the COBOL source;
        // verify the gate condition holds in the response.
        for (MenuOptionDto option : menu.options()) {
            assertThat(option.optionNumber()).isPositive();
            assertThat(option.label()).isNotBlank();
            String userType = option.userType();
            // Admin menu may include either 'A' (admin-only) or blank
            // (universal) entries; never 'U' user-only entries.
            assertThat(userType == null || !userType.equals("U"))
                    .as("admin menu must not include USER-only options (got %s)", userType)
                    .isTrue();
        }
        LOG.info("Admin menu returned {} options", menu.options().size());
    }

    /**
     * Validates Spring Security 6's {@code @PreAuthorize("hasRole('ADMIN')")}
     * gate by attempting to retrieve the admin menu with a regular-user
     * JWT.  Must yield HTTP 403 Forbidden (translated by
     * {@code GlobalExceptionHandler}'s {@code AccessDeniedException}
     * handler).
     */
    // AAP §0.4.1 — MenuController.getAdminMenu @PreAuthorize("hasRole('ADMIN')")
    @Test
    @Order(2)
    @DisplayName("GET /api/menu/admin as USER returns 403 Forbidden")
    void getAdminMenu_asUser_returns403() {
        String jwt = signIn(TEST_USER_ID, TEST_USER_PWD);

        ResponseEntity<String> response =
                getJson("/api/menu/admin", jwt);

        assertThat(response.getStatusCode()).as("USER cannot access admin menu")
                .isEqualTo(HttpStatus.FORBIDDEN);
        LOG.info("Admin menu correctly rejected for USER role with HTTP 403");
    }

    // =========================================================================
    // Stage 3 — View account tests (Order = 3)
    // =========================================================================

    /**
     * Validates the COACTVWC multi-entity join path: GET
     * {@code /api/accounts/{id}} returns a 200 OK with an
     * {@link AccountViewDto} carrying the joined Account + Customer +
     * CardCrossReference data, monetary fields scaled (12,2) per AAP
     * &sect;0.6.1, and PII fields rendered through the masked
     * {@code toString()} layer for log discipline.
     */
    // COBOL: COACTVWC multi-entity join with XREF AIX lookup
    @Test
    @Order(3)
    @DisplayName("GET /api/accounts/{id} returns joined Account+Customer+XREF data")
    void viewAccount_validId_returnsJoinedDataWithMaskedPII() {
        String jwt = signIn(TEST_USER_ID, TEST_USER_PWD);

        ResponseEntity<String> response =
                getJson("/api/accounts/" + TEST_ACCT_ID, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<AccountViewDto> body =
                parseApiResponse(response.getBody(), AccountViewDto.class);
        assertThat(body.code()).isEqualTo("OK");

        AccountViewDto view = body.data();
        assertThat(view).as("AccountViewDto").isNotNull();
        // ---- Account fields (CVACT01Y.cpy) ----
        assertThat(view.accountId()).isEqualTo(TEST_ACCT_ID);
        assertThat(view.activeStatus()).isEqualTo("Y");
        // BigDecimal isEqualByComparingTo (NEVER .equals) per AAP §0.6.1
        assertThat(view.currentBalance())
                .as("Account current balance scaled (12,2)")
                .isEqualByComparingTo(bd("1500.00"));
        assertThat(view.creditLimit())
                .isEqualByComparingTo(bd("5000.00"));
        assertThat(view.cashCreditLimit())
                .isEqualByComparingTo(bd("1000.00"));
        assertThat(view.openDate()).isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(view.expirationDate()).isEqualTo(LocalDate.of(2099, 12, 31));
        assertThat(view.reissueDate()).isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(view.accountGroupId()).isEqualTo("DEFAULT");

        // ---- Customer fields (CVCUS01Y.cpy via XREF AIX join) ----
        assertThat(view.customerId()).isEqualTo(TEST_CUST_ID);
        assertThat(view.firstName()).isEqualTo("TEST");
        assertThat(view.lastName()).isEqualTo("CUSTOMER");
        assertThat(view.stateCode()).isEqualTo("CA");
        assertThat(view.zipCode()).isEqualTo("90210");
        assertThat(view.countryCode()).isEqualTo("USA");
        assertThat(view.dateOfBirth()).isEqualTo(LocalDate.of(1980, 1, 1));
        assertThat(view.phoneNumber1()).isEqualTo("2125551234");
        // SSN is a Long on the wire — the toString() mask is enforced on
        // log lines (AAP §0.6.6) and exhaustively asserted in
        // PciDssAndAuditTests below. Equality of the numeric is still
        // verifiable from the typed DTO.
        assertThat(view.customerSsn()).isEqualTo(TEST_CUST_SSN);

        // Verify the DTO's toString() masks the SSN per the PII contract.
        String dtoString = view.toString();
        assertThat(dtoString).as("DTO toString masks SSN per AAP §0.6.6")
                .contains("***-**-")
                .doesNotContain(String.valueOf(TEST_CUST_SSN));

        // Second GET — must succeed (verifies the cache-aside path does
        // not corrupt the view; with a mocked RedisTemplate the cache is
        // always a miss but the code path still flows through correctly).
        ResponseEntity<String> repeat =
                getJson("/api/accounts/" + TEST_ACCT_ID, jwt);
        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.OK);
        LOG.info("Account view returned acctId={} (joined Account+Customer+XREF)",
                TEST_ACCT_ID);
    }

    /**
     * Validates that requesting a non-existent account ID yields HTTP
     * 404, mapped from {@code RecordNotFoundException} by
     * {@code GlobalExceptionHandler}.
     */
    // AAP §0.4.1 — RecordNotFoundException → HTTP 404
    @Test
    @Order(3)
    @DisplayName("GET /api/accounts/{id} with unknown ID returns 404")
    void viewAccount_unknownId_returns404() {
        String jwt = signIn(TEST_USER_ID, TEST_USER_PWD);
        // 11 digits but distinct from the seeded TEST_ACCT_ID.
        long unknownAcctId = 12_345_678_901L;

        ResponseEntity<String> response =
                getJson("/api/accounts/" + unknownAcctId, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        LOG.info("Unknown account correctly returned 404 acctId={}", unknownAcctId);
    }

    /**
     * Validates that a missing {@code Authorization} header yields HTTP
     * 401, enforced by the JWT filter in {@code SecurityConfig}.
     */
    // AAP §0.7.2 — JWT filter rejects unauthenticated requests with 401
    @Test
    @Order(3)
    @DisplayName("GET /api/accounts/{id} without JWT returns 401")
    void viewAccount_withoutJwt_returns401() {
        // No bearer token — request must be rejected at the security
        // filter chain before reaching the controller.
        ResponseEntity<String> response =
                getJson("/api/accounts/" + TEST_ACCT_ID, null);

        assertThat(response.getStatusCode())
                .as("Missing JWT yields 401 Unauthorized")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        LOG.info("Account view without JWT correctly returned 401");
    }

    // =========================================================================
    // Stage 4 — Update account tests (Order = 4)
    // =========================================================================

    /**
     * Happy-path account update: PUT {@code /api/accounts/{id}} with a
     * fully-populated request body succeeds with HTTP 200, persists both
     * the {@link Account} (credit-limit change) and {@link Customer}
     * (name + address change) inside the single
     * {@code @Transactional} method, and increments the JPA
     * {@code @Version} stamp from 0 to 1.
     */
    // COBOL: COACTUPC dual-write Account+Customer with @Transactional rollback
    @Test
    @Order(4)
    @DisplayName("PUT /api/accounts/{id} valid request persists and increments version")
    void updateAccount_validRequest_persistsAndIncrementsVersion() {
        String jwt = signIn(TEST_USER_ID, TEST_USER_PWD);

        AccountUpdateDto request = buildBaselineUpdateRequest()
                .withCreditLimit(bd("7500.00"))
                .withFirstName("UPDATED")
                .withLastName("CUSTOMER")
                .withAddressLine1("123 NEW ST")
                .withStateCode("TX")
                .withZipCode("75001")
                .build();

        ResponseEntity<String> response =
                putJson("/api/accounts/" + TEST_ACCT_ID, request, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<AccountViewDto> body =
                parseApiResponse(response.getBody(), AccountViewDto.class);
        assertThat(body.code()).isEqualTo("OK");
        AccountViewDto view = body.data();
        assertThat(view.creditLimit()).isEqualByComparingTo(bd("7500.00"));
        assertThat(view.firstName()).isEqualTo("UPDATED");

        // Reload from the DB and verify the version stamp incremented.
        Account persistedAccount = accountRepository.findById(TEST_ACCT_ID)
                .orElseThrow(() -> new AssertionError("Account vanished after update"));
        assertThat(persistedAccount.getAcctCreditLimit())
                .as("Account credit limit persisted")
                .isEqualByComparingTo(bd("7500.00"));
        assertThat(persistedAccount.getVersion())
                .as("JPA @Version incremented from 0 to 1")
                .isEqualTo(1L);

        // Verify the Customer dual-write also committed.
        Customer persistedCustomer = customerRepository.findById(TEST_CUST_ID)
                .orElseThrow(() -> new AssertionError("Customer vanished after update"));
        assertThat(persistedCustomer.getCustFirstName()).isEqualTo("UPDATED");
        assertThat(persistedCustomer.getCustLastName()).isEqualTo("CUSTOMER");
        assertThat(persistedCustomer.getCustAddrLine1()).isEqualTo("123 NEW ST");
        assertThat(persistedCustomer.getCustAddrStateCd()).isEqualTo("TX");
        assertThat(persistedCustomer.getCustAddrZip()).isEqualTo("75001");

        LOG.info("Update succeeded acctId={} newVersion={} creditLimit=7500.00",
                TEST_ACCT_ID, persistedAccount.getVersion());
    }

    /**
     * Validates optimistic-lock semantics: two concurrent PUTs racing
     * against the same {@code @Version=0} snapshot — exactly one
     * succeeds with HTTP 200 (version → 1), the other is rejected with
     * HTTP 409 Conflict by {@code GlobalExceptionHandler}'s
     * {@code ConcurrentModificationException} / {@code OptimisticLockingFailureException}
     * handlers.  The database must reflect EXACTLY ONE update.
     */
    // AAP §0.4.1 ConcurrentModificationException (JPA OptimisticLockException)
    @Test
    @Order(4)
    @DisplayName("PUT /api/accounts/{id} concurrent update returns 409 Conflict on one branch")
    void updateAccount_concurrentUpdate_returns409Conflict() {
        String jwt = signIn(TEST_USER_ID, TEST_USER_PWD);
        // Two distinct requests, both claiming version=0 — only one can win.
        AccountUpdateDto requestA = buildBaselineUpdateRequest()
                .withCreditLimit(bd("6000.00"))
                .withFirstName("RACEONE")
                .build();
        AccountUpdateDto requestB = buildBaselineUpdateRequest()
                .withCreditLimit(bd("7000.00"))
                .withFirstName("RACETWO")
                .build();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<ResponseEntity<String>> futureA = CompletableFuture
                    .supplyAsync(() -> putJson("/api/accounts/" + TEST_ACCT_ID,
                            requestA, jwt), pool);
            CompletableFuture<ResponseEntity<String>> futureB = CompletableFuture
                    .supplyAsync(() -> putJson("/api/accounts/" + TEST_ACCT_ID,
                            requestB, jwt), pool);
            CompletableFuture.allOf(futureA, futureB).join();

            ResponseEntity<String> responseA = futureA.get(30, TimeUnit.SECONDS);
            ResponseEntity<String> responseB = futureB.get(30, TimeUnit.SECONDS);
            List<HttpStatus> statuses = List.of(
                    HttpStatus.resolve(responseA.getStatusCode().value()),
                    HttpStatus.resolve(responseB.getStatusCode().value()));

            // Exactly one OK and one CONFLICT — order is non-deterministic
            // due to true concurrency, so assert the multiset.
            long okCount = statuses.stream().filter(s -> s == HttpStatus.OK).count();
            long conflictCount = statuses.stream()
                    .filter(s -> s == HttpStatus.CONFLICT).count();
            assertThat(okCount).as("Exactly one 200 OK").isEqualTo(1L);
            assertThat(conflictCount).as("Exactly one 409 Conflict").isEqualTo(1L);

            // Verify the database reflects ONLY one update.
            Account persisted = accountRepository.findById(TEST_ACCT_ID)
                    .orElseThrow(() -> new AssertionError("Account missing"));
            assertThat(persisted.getVersion())
                    .as("Version advances exactly once under contention")
                    .isEqualTo(1L);
            // The credit limit must be one of the two requested values
            // (whichever request won) — never an interleaved partial.
            assertThat(persisted.getAcctCreditLimit())
                    .isIn(bd("6000.00"), bd("7000.00"));
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Concurrent update test failed", e);
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("Concurrent update test confirmed exactly one 200 + one 409");
    }

    /**
     * Validates that an invalid US state code (not in the
     * {@code CSLKPCDY.cpy} lookup) yields HTTP 400 via
     * {@code ValidationException}. The Java target's
     * {@code ValidationLookupService} ports the COBOL valid-state list
     * from {@code CSLKPCDY.cpy} (50 states + DC + territories).
     */
    // COBOL: COACTUPC validation via CSLKPCDY.cpy lookup
    @Test
    @Order(4)
    @DisplayName("PUT /api/accounts/{id} with invalid state code returns 400")
    void updateAccount_invalidStateCode_returns400() {
        String jwt = signIn(TEST_USER_ID, TEST_USER_PWD);
        AccountUpdateDto request = buildBaselineUpdateRequest()
                .withStateCode("ZZ")    // not a valid US state/territory
                .withZipCode("99999")   // satisfy @Pattern, fail state check
                .build();

        ResponseEntity<String> response =
                putJson("/api/accounts/" + TEST_ACCT_ID, request, jwt);

        assertThat(response.getStatusCode())
                .as("Invalid state code → 400 Bad Request")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // Account must NOT have been updated (the rollback boundary
        // unwinds the transaction before any save commits).
        Account persisted = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
        assertThat(persisted.getVersion()).isEqualTo(0L);
        LOG.info("Invalid state code 'ZZ' correctly rejected with HTTP 400");
    }

    /**
     * Validates that a non-NANPA area code in {@code phoneNumber1} yields
     * HTTP 400 via {@code ValidationLookupService.isValidAreaCode} (port
     * of the {@code CSLKPCDY.cpy} {@code WS-US-PHONE-AREA-CODE-TO-EDIT}
     * 88-level list).  Area code 111 is not in the NANPA-allocated set.
     */
    // COBOL: CSLKPCDY.cpy NANPA area-code lookup
    @Test
    @Order(4)
    @DisplayName("PUT /api/accounts/{id} with non-NANPA phone area code returns 400")
    void updateAccount_invalidPhoneAreaCode_returns400() {
        String jwt = signIn(TEST_USER_ID, TEST_USER_PWD);
        // Area code 111 is reserved (not allocated by NANPA) so the
        // ValidationLookupService.isValidAreaCode predicate must reject.
        AccountUpdateDto request = buildBaselineUpdateRequest()
                .withPhoneNumber1("1110000000")
                .build();

        ResponseEntity<String> response =
                putJson("/api/accounts/" + TEST_ACCT_ID, request, jwt);

        assertThat(response.getStatusCode())
                .as("Non-NANPA area code → 400 Bad Request")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        Account persisted = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
        assertThat(persisted.getVersion()).isEqualTo(0L);
        LOG.info("Non-NANPA area code '111' correctly rejected with HTTP 400");
    }

    /**
     * Validates the SYNCPOINT ROLLBACK contract: if the Customer save
     * fails after the Account save has been applied to the JPA persistence
     * context, the {@code @Transactional(rollbackFor = Exception.class)}
     * boundary unwinds BOTH writes so neither change persists.
     *
     * <p>This test triggers the partial failure by submitting a request
     * with an invalid customer record (state code "ZZ") AFTER deleting
     * the customer row.  The chain is:
     * <ol>
     *   <li>Service finds the Account (success).</li>
     *   <li>Service tries to load the Customer via XREF lookup, raises
     *       {@code RecordNotFoundException} after the account has been
     *       modified in the persistence context.</li>
     *   <li>{@code @Transactional} rolls back; neither table changes.</li>
     * </ol>
     * </p>
     */
    // COBOL: COACTUPC SYNCPOINT ROLLBACK via @Transactional(rollbackFor=Exception.class)
    @Test
    @Order(4)
    @DisplayName("PUT /api/accounts/{id} partial failure rolls back Account+Customer atomically")
    void updateAccount_rollbackOnPartialFailure_revertsAccount() {
        String jwt = signIn(TEST_USER_ID, TEST_USER_PWD);

        // Capture the pre-update Account state for the assertion.
        BigDecimal originalCreditLimit = accountRepository.findById(TEST_ACCT_ID)
                .orElseThrow().getAcctCreditLimit();
        long originalVersion = accountRepository.findById(TEST_ACCT_ID)
                .orElseThrow().getVersion();

        // Pre-condition: remove the Customer + XREF rows so the
        // AccountUpdateService XREF lookup raises RecordNotFoundException
        // AFTER the Account validation has succeeded.  This deterministically
        // triggers the SYNCPOINT ROLLBACK code path: any RuntimeException
        // thrown inside the @Transactional method must unwind every prior
        // write made in the same transaction.
        xrefRepository.deleteAll();
        customerRepository.deleteAll();

        AccountUpdateDto request = buildBaselineUpdateRequest()
                .withCreditLimit(bd("9999.00"))
                .withFirstName("WILLROLLBACK")
                .build();

        ResponseEntity<String> response =
                putJson("/api/accounts/" + TEST_ACCT_ID, request, jwt);

        // RecordNotFoundException → 404 (CardCrossReference missing)
        assertThat(response.getStatusCode())
                .as("Missing XREF → RecordNotFoundException → 404")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // The CRITICAL assertion: the Account must NOT have been updated,
        // even though the update path attempted to apply the credit-limit
        // change before the XREF lookup failed.
        Account postRollback = accountRepository.findById(TEST_ACCT_ID)
                .orElseThrow(() -> new AssertionError("Account vanished"));
        assertThat(postRollback.getAcctCreditLimit())
                .as("Account credit limit preserved by SYNCPOINT ROLLBACK")
                .isEqualByComparingTo(originalCreditLimit);
        assertThat(postRollback.getVersion())
                .as("@Version NOT advanced because transaction rolled back")
                .isEqualTo(originalVersion);
        LOG.info("Partial-failure rollback confirmed: Account unchanged "
                + "creditLimit={} version={}", originalCreditLimit, originalVersion);
    }

    // =========================================================================
    // @Nested — PCI-DSS PII masking + audit emission tests
    // =========================================================================

    /**
     * PCI-DSS and audit assertions grouped in a nested class per AAP
     * &sect;0.6.6.  These tests assert that:
     * <ul>
     *   <li>The {@link AccountViewDto#toString()} mask is applied at the
     *       DTO log-emission boundary so accidental {@code log.info(dto)}
     *       calls do not leak SSN values.</li>
     *   <li>No CVV-equivalent field is present in any response body.</li>
     *   <li>{@code AuditLogService} receives an audit event whenever
     *       an account update commits.</li>
     * </ul>
     */
    @Nested
    @DisplayName("PCI-DSS PII masking and audit emission assertions")
    class PciDssAndAuditTests {

        /**
         * Verifies that the response body does not include the SSN in a
         * dotted/dashed pattern characteristic of a leaked PII value
         * (e.g., "123-45-6789").  The raw 9-digit number on the
         * {@code customerSsn} field is sent as a Long literal under
         * Jackson's default Long serializer; the test asserts that no
         * formatted SSN pattern appears in the response.
         */
        @Test
        @DisplayName("Account view response does not include formatted SSN string")
        void viewAccount_responseDoesNotIncludeFullSsnString() {
            String jwt = signIn(TEST_USER_ID, TEST_USER_PWD);
            ResponseEntity<String> response =
                    getJson("/api/accounts/" + TEST_ACCT_ID, jwt);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            String raw = response.getBody();
            assertThat(raw).isNotNull();
            // No "123-45-6789"-style formatted SSN strings — the wire
            // format never assembles the SSN into a dashed pattern.
            assertThat(raw)
                    .as("Response must not contain a formatted SSN pattern (123-45-6789)")
                    .doesNotContainPattern("\\b\\d{3}-\\d{2}-\\d{4}\\b");
            // No CVV field — the response must never expose a 3- or
            // 4-digit CVV value adjacent to a "cvv" key.
            assertThat(raw.toLowerCase(java.util.Locale.US))
                    .as("Response must not contain a 'cvv' field name")
                    .doesNotContain("\"cvv\"")
                    .doesNotContain("cvv2")
                    .doesNotContain("cvc");
        }

        /**
         * Verifies that the {@link AccountViewDto#toString()} mask is
         * the actual production behavior, defending against future PII
         * leakage if the DTO is accidentally interpolated into a log
         * line (AAP &sect;0.6.6).
         */
        @Test
        @DisplayName("AccountViewDto.toString() masks SSN per AAP §0.6.6")
        void viewAccount_dtoToStringMasksSsn() {
            String jwt = signIn(TEST_USER_ID, TEST_USER_PWD);
            ResponseEntity<String> response =
                    getJson("/api/accounts/" + TEST_ACCT_ID, jwt);
            AccountViewDto view = parseApiResponse(
                    response.getBody(), AccountViewDto.class).data();
            String dtoString = view.toString();
            assertThat(dtoString)
                    .as("toString masks SSN to ***-**-XXXX")
                    .contains("***-**-")
                    .doesNotContain(String.valueOf(TEST_CUST_SSN));
        }

        /**
         * Verifies that no PAN-like 16-digit number appears in a JSON
         * field literally named {@code "cardNumber"} or {@code "pan"} in
         * the account view response body.  The {@link AccountViewDto}
         * itself does not expose cards directly (it is a view of the
         * Account + Customer + XREF join), so this is a defense-in-depth
         * assertion that the response body does not accidentally surface
         * the PAN through some other field projection.
         */
        @Test
        @DisplayName("Account view response does not include PAN field")
        void viewAccount_responseDoesNotIncludeFullPan() {
            String jwt = signIn(TEST_USER_ID, TEST_USER_PWD);
            ResponseEntity<String> response =
                    getJson("/api/accounts/" + TEST_ACCT_ID, jwt);
            String raw = response.getBody();
            assertThat(raw).isNotNull();
            // Parse the response as a JSON tree and assert that no leaf
            // value is a 16-digit number string equal to the seeded PAN.
            try {
                JsonNode root = objectMapper.readTree(raw);
                assertJsonHasNoFullPan(root, TEST_CARD_NUM);
            } catch (Exception e) {
                throw new AssertionError("Failed to parse view response JSON", e);
            }
        }

        /**
         * Verifies that the response body never includes a {@code cvv}
         * field, irrespective of casing.  CVV values must be neither
         * stored at rest in the source CardDemo system per AAP &sect;0.6.6
         * (and consequently never present in JPA entities) NOR present in
         * the response wire format.
         */
        @Test
        @DisplayName("Account view response contains no CVV field")
        void viewAccount_responseDoesNotIncludeCvv() {
            String jwt = signIn(TEST_USER_ID, TEST_USER_PWD);
            ResponseEntity<String> response =
                    getJson("/api/accounts/" + TEST_ACCT_ID, jwt);
            String raw = response.getBody();
            assertThat(raw).isNotNull();
            try {
                JsonNode root = objectMapper.readTree(raw);
                assertJsonHasNoCvv(root);
            } catch (Exception e) {
                throw new AssertionError("Failed to parse view response JSON", e);
            }
        }

        /**
         * Verifies that an audit event is emitted to
         * {@link AuditLogService} when the account update commits.  The
         * production {@code AccountUpdateService} calls
         * {@code auditLogService.auditEvent("account.updated", ...)}
         * after the dual-write succeeds; this assertion uses the
         * {@link SpyBean} spy to confirm that emission occurs end-to-end.
         */
        @Test
        @DisplayName("Account update emits an audit event via AuditLogService")
        void updateAccount_auditLogEmitted() {
            String jwt = signIn(TEST_USER_ID, TEST_USER_PWD);
            // Reset interactions before invoking so the audit event
            // recorded here is the one emitted by the update path.
            Mockito.clearInvocations(auditLogService);

            AccountUpdateDto request = buildBaselineUpdateRequest()
                    .withCreditLimit(bd("8888.00"))
                    .build();

            ResponseEntity<String> response =
                    putJson("/api/accounts/" + TEST_ACCT_ID, request, jwt);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            // The audit emission may be @Async — use Awaitility to bound
            // the wait deterministically rather than Thread.sleep.
            await().atMost(AWAIT_TIMEOUT)
                    .pollInterval(AWAIT_POLL)
                    .untilAsserted(() -> Mockito.verify(auditLogService,
                            Mockito.atLeastOnce()).auditEvent(
                                    Mockito.anyString(),
                                    Mockito.anyString(),
                                    Mockito.anyMap()));
            LOG.info("Audit log emission confirmed for account update");
        }

        /**
         * Recursively walks the JSON tree asserting that no leaf is the
         * full 16-digit PAN value.
         */
        private void assertJsonHasNoFullPan(JsonNode node, String fullPan) {
            if (node == null || node.isNull()) {
                return;
            }
            if (node.isTextual()) {
                String text = node.asText();
                assertThat(text)
                        .as("Response JSON leaf must not contain full PAN")
                        .isNotEqualTo(fullPan);
                return;
            }
            if (node.isObject()) {
                node.fields().forEachRemaining(entry -> {
                    String name = entry.getKey().toLowerCase(java.util.Locale.US);
                    // Defense in depth — no CVV-ish key name in any object.
                    assertThat(name).as("No CVV-like field name in response")
                            .doesNotContain("cvv")
                            .doesNotContain("cvc")
                            .doesNotContain("cardverification");
                    assertJsonHasNoFullPan(entry.getValue(), fullPan);
                });
                return;
            }
            if (node.isArray()) {
                node.forEach(child -> assertJsonHasNoFullPan(child, fullPan));
            }
        }

        /**
         * Recursively walks the JSON tree asserting that no field name
         * contains "cvv", "cvc", or "cardverification" (case-insensitive).
         */
        private void assertJsonHasNoCvv(JsonNode node) {
            if (node == null || node.isNull()) {
                return;
            }
            if (node.isObject()) {
                node.fields().forEachRemaining(entry -> {
                    String name = entry.getKey().toLowerCase(java.util.Locale.US);
                    assertThat(name)
                            .as("No CVV-like field name in response (saw '%s')", name)
                            .doesNotContain("cvv")
                            .doesNotContain("cvc")
                            .doesNotContain("cardverification");
                    assertJsonHasNoCvv(entry.getValue());
                });
                return;
            }
            if (node.isArray()) {
                node.forEach(this::assertJsonHasNoCvv);
            }
        }
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    /**
     * Convenience method for signing in via the public {@code /api/auth/signin}
     * endpoint and returning the raw JWT bearer token, used by every
     * authenticated test method to obtain a usable token.  Caches into the
     * appropriate per-test field ({@link #userJwtToken} or
     * {@link #adminJwtToken}) so callers can also reach the token through
     * the field if needed.
     *
     * @param userId   the {@code SEC-USR-ID} (USRSEC primary key)
     * @param password the plaintext password — BCrypt-matched server-side
     * @return the JWT bearer token issued by {@code SignonService}
     * @throws AssertionError if signin fails for any reason
     */
    private String signIn(String userId, String password) {
        SignonRequestDto request = new SignonRequestDto(userId, password);
        ResponseEntity<String> response =
                postJson("/api/auth/signin", request, null);
        assertThat(response.getStatusCode())
                .as("signIn(%s) must succeed", userId)
                .isEqualTo(HttpStatus.OK);
        ApiResponse<SignonResponseDto> body =
                parseApiResponse(response.getBody(), SignonResponseDto.class);
        assertThat(body).as("signIn body").isNotNull();
        assertThat(body.data()).as("signIn payload").isNotNull();
        String token = body.data().token();
        assertThat(token).as("Issued JWT").isNotBlank();
        if (userId.equals(TEST_USER_ID)) {
            userJwtToken = token;
        } else if (userId.equals(TEST_ADMIN_ID)) {
            adminJwtToken = token;
        }
        return token;
    }

    /**
     * Builds an {@link HttpHeaders} instance carrying the
     * {@code Authorization: Bearer <jwt>} and JSON-content-type headers.
     * Passing {@code null} for {@code jwt} produces an unauthenticated
     * header set so the test can exercise the 401 negative path.
     *
     * @param jwt the JWT bearer token, or {@code null} for unauthenticated
     * @return populated {@link HttpHeaders}
     */
    private HttpHeaders authHeaders(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        if (jwt != null && !jwt.isBlank()) {
            headers.setBearerAuth(jwt);
        }
        return headers;
    }

    /**
     * Issues a GET request to the application under test at the random
     * {@link #port} and returns the response with the body as
     * {@link String} (so callers can parse the
     * {@link ApiResponse}&lt;T&gt; envelope via Jackson manually).
     *
     * @param path the URL path beginning with "/"
     * @param jwt  the JWT bearer token, or {@code null} for
     *             unauthenticated requests
     * @return the {@link ResponseEntity} with raw response body
     */
    private ResponseEntity<String> getJson(String path, String jwt) {
        String url = "http://localhost:" + port + path;
        return restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(authHeaders(jwt)), String.class);
    }

    /**
     * Issues a POST request with the given request body as JSON and
     * returns the response with the body as raw {@link String}.
     *
     * @param path the URL path beginning with "/"
     * @param body the request body to serialize as JSON
     * @param jwt  the JWT bearer token, or {@code null}
     * @return the {@link ResponseEntity} with raw response body
     */
    private ResponseEntity<String> postJson(String path, Object body, String jwt) {
        String url = "http://localhost:" + port + path;
        return restTemplate.exchange(
                url, HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(jwt)),
                String.class);
    }

    /**
     * Issues a PUT request with the given request body as JSON and
     * returns the response with the body as raw {@link String}.
     *
     * @param path the URL path beginning with "/"
     * @param body the request body to serialize as JSON
     * @param jwt  the JWT bearer token, or {@code null}
     * @return the {@link ResponseEntity} with raw response body
     */
    private ResponseEntity<String> putJson(String path, Object body, String jwt) {
        String url = "http://localhost:" + port + path;
        return restTemplate.exchange(
                url, HttpMethod.PUT,
                new HttpEntity<>(body, authHeaders(jwt)),
                String.class);
    }

    /**
     * Parses a raw JSON response body into an
     * {@link ApiResponse}&lt;T&gt; envelope using Jackson.  Centralizes
     * the Jackson {@code TypeFactory} call site so the generic-type
     * construction is explicit and reusable.
     *
     * @param <T>         the payload type
     * @param json        the raw JSON response body
     * @param payloadType the {@code Class} object for the payload type
     * @return the deserialized {@code ApiResponse<T>}
     */
    private <T> ApiResponse<T> parseApiResponse(String json, Class<T> payloadType) {
        assertThat(json).as("Response body must not be null").isNotNull();
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructParametricType(
                            ApiResponse.class, payloadType));
        } catch (Exception e) {
            throw new AssertionError(
                    "Failed to parse ApiResponse<" + payloadType.getSimpleName()
                            + ">: " + json, e);
        }
    }

    /**
     * Constructs a {@link BigDecimal} via the {@link String} constructor
     * and applies scale=2 with HALF_EVEN rounding (banker's rounding) per
     * AAP &sect;0.6.1.  This is the only way the test introduces
     * monetary values — never via {@code BigDecimal.valueOf(double)} or
     * via the {@code BigDecimal} double-constructor, both of which can
     * inject binary-floating-point drift.
     *
     * @param value the decimal value as a string (e.g., "1500.00")
     * @return a {@link BigDecimal} with scale=2 and HALF_EVEN rounding
     */
    private static BigDecimal bd(String value) {
        return new BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_EVEN);
    }

    /**
     * Builds an {@link AccountUpdateDto} baseline matching the seeded
     * test fixture state.  Tests then mutate specific fields via the
     * {@link AccountUpdateBuilder} fluent API to exercise the field
     * under test while keeping the rest of the request valid.
     *
     * @return a new {@link AccountUpdateBuilder} pre-populated with
     *         valid baseline values matching the seed Customer + Account
     */
    private AccountUpdateBuilder buildBaselineUpdateRequest() {
        return new AccountUpdateBuilder()
                .withAccountId(TEST_ACCT_ID)
                .withActiveStatus("Y")
                .withCurrentBalance(bd("1500.00"))
                .withCreditLimit(bd("5000.00"))
                .withCashCreditLimit(bd("1000.00"))
                .withOpenDate(LocalDate.of(2020, 1, 1))
                .withExpirationDate(LocalDate.of(2099, 12, 31))
                .withReissueDate(LocalDate.of(2020, 1, 1))
                .withCurrentCycleCredit(bd("0.00"))
                .withCurrentCycleDebit(bd("0.00"))
                .withAddressZip("90210")
                .withAccountGroupId("DEFAULT")
                .withCustomerId(TEST_CUST_ID)
                .withFirstName("TEST")
                .withMiddleName("T")
                .withLastName("CUSTOMER")
                .withCustomerSsn(TEST_CUST_SSN)
                .withPhoneNumber1("2125551234")
                .withPhoneNumber2("2125555678")
                .withAddressLine1("1 TEST ST")
                .withAddressLine2("APT 1")
                .withAddressLine3("TEST CITY")
                .withStateCode("CA")
                .withCountryCode("USA")
                .withZipCode("90210")
                .withDateOfBirth(LocalDate.of(1980, 1, 1))
                .withGovernmentIssuedId("DLTEST0000")
                .withEftAccountId("EFTACCT001")
                .withPrimaryCardHolderIndicator("Y")
                .withFicoCreditScore(720)
                .withVersion(0L);
    }

    /**
     * Fluent builder for {@link AccountUpdateDto} so tests can produce
     * field-specific variants without restating the full 30-field
     * constructor each time.  The builder mirrors the DTO's record
     * component order verbatim.
     */
    private static final class AccountUpdateBuilder {
        private Long accountId;
        private String activeStatus;
        private BigDecimal currentBalance;
        private BigDecimal creditLimit;
        private BigDecimal cashCreditLimit;
        private LocalDate openDate;
        private LocalDate expirationDate;
        private LocalDate reissueDate;
        private BigDecimal currentCycleCredit;
        private BigDecimal currentCycleDebit;
        private String addressZip;
        private String accountGroupId;
        private Long customerId;
        private String firstName;
        private String middleName;
        private String lastName;
        private Long customerSsn;
        private String phoneNumber1;
        private String phoneNumber2;
        private String addressLine1;
        private String addressLine2;
        private String addressLine3;
        private String stateCode;
        private String countryCode;
        private String zipCode;
        private LocalDate dateOfBirth;
        private String governmentIssuedId;
        private String eftAccountId;
        private String primaryCardHolderIndicator;
        private Integer ficoCreditScore;
        private Long version;

        AccountUpdateBuilder withAccountId(Long v) { this.accountId = v; return this; }
        AccountUpdateBuilder withActiveStatus(String v) { this.activeStatus = v; return this; }
        AccountUpdateBuilder withCurrentBalance(BigDecimal v) { this.currentBalance = v; return this; }
        AccountUpdateBuilder withCreditLimit(BigDecimal v) { this.creditLimit = v; return this; }
        AccountUpdateBuilder withCashCreditLimit(BigDecimal v) { this.cashCreditLimit = v; return this; }
        AccountUpdateBuilder withOpenDate(LocalDate v) { this.openDate = v; return this; }
        AccountUpdateBuilder withExpirationDate(LocalDate v) { this.expirationDate = v; return this; }
        AccountUpdateBuilder withReissueDate(LocalDate v) { this.reissueDate = v; return this; }
        AccountUpdateBuilder withCurrentCycleCredit(BigDecimal v) { this.currentCycleCredit = v; return this; }
        AccountUpdateBuilder withCurrentCycleDebit(BigDecimal v) { this.currentCycleDebit = v; return this; }
        AccountUpdateBuilder withAddressZip(String v) { this.addressZip = v; return this; }
        AccountUpdateBuilder withAccountGroupId(String v) { this.accountGroupId = v; return this; }
        AccountUpdateBuilder withCustomerId(Long v) { this.customerId = v; return this; }
        AccountUpdateBuilder withFirstName(String v) { this.firstName = v; return this; }
        AccountUpdateBuilder withMiddleName(String v) { this.middleName = v; return this; }
        AccountUpdateBuilder withLastName(String v) { this.lastName = v; return this; }
        AccountUpdateBuilder withCustomerSsn(Long v) { this.customerSsn = v; return this; }
        AccountUpdateBuilder withPhoneNumber1(String v) { this.phoneNumber1 = v; return this; }
        AccountUpdateBuilder withPhoneNumber2(String v) { this.phoneNumber2 = v; return this; }
        AccountUpdateBuilder withAddressLine1(String v) { this.addressLine1 = v; return this; }
        AccountUpdateBuilder withAddressLine2(String v) { this.addressLine2 = v; return this; }
        AccountUpdateBuilder withAddressLine3(String v) { this.addressLine3 = v; return this; }
        AccountUpdateBuilder withStateCode(String v) { this.stateCode = v; return this; }
        AccountUpdateBuilder withCountryCode(String v) { this.countryCode = v; return this; }
        AccountUpdateBuilder withZipCode(String v) { this.zipCode = v; return this; }
        AccountUpdateBuilder withDateOfBirth(LocalDate v) { this.dateOfBirth = v; return this; }
        AccountUpdateBuilder withGovernmentIssuedId(String v) { this.governmentIssuedId = v; return this; }
        AccountUpdateBuilder withEftAccountId(String v) { this.eftAccountId = v; return this; }
        AccountUpdateBuilder withPrimaryCardHolderIndicator(String v) { this.primaryCardHolderIndicator = v; return this; }
        AccountUpdateBuilder withFicoCreditScore(Integer v) { this.ficoCreditScore = v; return this; }
        AccountUpdateBuilder withVersion(Long v) { this.version = v; return this; }

        /**
         * Returns the underlying {@link AccountUpdateDto} populated with
         * the current builder state.  Order matches the record component
         * declaration in {@link AccountUpdateDto}.
         */
        AccountUpdateDto build() {
            return new AccountUpdateDto(
                    accountId,
                    activeStatus,
                    currentBalance,
                    creditLimit,
                    cashCreditLimit,
                    openDate,
                    expirationDate,
                    reissueDate,
                    currentCycleCredit,
                    currentCycleDebit,
                    addressZip,
                    accountGroupId,
                    customerId,
                    firstName,
                    middleName,
                    lastName,
                    customerSsn,
                    phoneNumber1,
                    phoneNumber2,
                    addressLine1,
                    addressLine2,
                    addressLine3,
                    stateCode,
                    countryCode,
                    zipCode,
                    dateOfBirth,
                    governmentIssuedId,
                    eftAccountId,
                    primaryCardHolderIndicator,
                    ficoCreditScore,
                    version);
        }
    }
}
