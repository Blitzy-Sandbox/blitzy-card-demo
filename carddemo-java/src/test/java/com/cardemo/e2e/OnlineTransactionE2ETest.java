/*
 * CardDemo COBOL-to-Java migration — online (CICS) REST API end-to-end parity test.
 *
 * Traceability: original COBOL/CICS baseline at commit 27d6c6f (AAP §0.7.2 — COBOL sources are
 * NEVER copied into this repository; this test references them only by program name). No COBOL,
 * BMS, copybook, or JCL text is reproduced here.
 */
package com.cardemo.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import com.cardemo.config.AwsConfig;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Card;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.UserSecurityRepository;

/**
 * End-to-end behavioral-parity test for the migrated CardDemo <strong>online (CICS) REST API</strong>.
 *
 * <p>This test drives the application from its outer HTTP boundary (real servlet container on a
 * random port via {@link TestRestTemplate}) through controller &rarr; service &rarr; repository
 * &rarr; entity &rarr; PostgreSQL, plus the one AWS side-effect of the online estate (SQS publish on
 * the report-submission bridge). It runs against <strong>Testcontainers</strong> (PostgreSQL&nbsp;16
 * + LocalStack) seeded by Flyway with the real nine-fixture ASCII dataset, proving 100% behavioral
 * parity (AAP §0.7.2) of the eighteen migrated {@code CO*.cbl} CICS programs:</p>
 * <ul>
 *   <li>{@code COSGN00C} sign-on; {@code COMEN01C}/{@code COADM01C} menus;</li>
 *   <li>{@code COACTVWC}/{@code COACTUPC} account view/update (dual {@code ACCTDAT}+{@code CUSTDAT}
 *       update with {@code SYNCPOINT ROLLBACK} &rarr; {@code @Transactional}, and the
 *       {@code @Version} optimistic-lock site);</li>
 *   <li>{@code COCRDLIC}/{@code COCRDSLC}/{@code COCRDUPC} card list/detail/update (the second
 *       {@code @Version} site);</li>
 *   <li>{@code COTRN00C}/{@code COTRN01C}/{@code COTRN02C} transaction list/detail/add (auto-id
 *       factory);</li>
 *   <li>{@code COBIL00C} bill payment; {@code CORPT00C} report submission (the sole online&rarr;batch
 *       bridge, CICS TDQ &rarr; SQS, AAP §0.6.3);</li>
 *   <li>{@code COUSR00C}–{@code COUSR03C} user administration (admin-only CRUD).</li>
 * </ul>
 *
 * <p>The only permitted behavioral deviation from the COBOL baseline is the BCrypt password upgrade
 * (constraint C-003): sign-on succeeds against BCrypt-hashed seed passwords and passwords are NEVER
 * echoed in any response. All monetary assertions use {@link BigDecimal#compareTo(BigDecimal)} (never
 * {@code equals}, per AAP §0.7.3). Expected data is derived from the seeded fixtures at runtime (read
 * from the repositories) rather than transcribed as magic numbers.</p>
 *
 * <h2>Runner</h2>
 * <p>The class name ends in {@code Test} and lives under {@code **}/{@code e2e}/{@code **}; the Surefire
 * plugin excludes {@code **}/{@code e2e}/{@code **}, so it is executed by the Failsafe plugin under the
 * Maven {@code integration} profile ({@code mvn -P integration verify}).</p>
 *
 * <h2>Container lifecycle</h2>
 * <p>Following the proven repository convention (see {@code integration/aws/AbstractLocalStackIntegrationTest}),
 * the PostgreSQL and LocalStack containers use the Testcontainers <em>singleton</em> pattern (manual,
 * one-time {@code start()} in a static initializer) rather than {@code @Container}-managed lifecycle;
 * the {@code @Testcontainers} annotation is retained as an intent marker. {@code @DynamicPropertySource}
 * wires the JDBC datasource and the Spring Cloud AWS endpoint/region/credentials from the running
 * containers. This test is deliberately <strong>standalone</strong> (it does not extend the MOCK-environment
 * base class) because it requires a real servlet container ({@code WebEnvironment.RANDOM_PORT}).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers // Intent marker: the containers below use the singleton pattern, NOT @Container.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Online (CICS) REST API — end-to-end behavioral parity")
class OnlineTransactionE2ETest {

    // =============================================================================================
    // Container images (mirrors the cached, version-pinned images used across the test suite).
    // =============================================================================================

    /** LocalStack community image — S3/SQS/SNS surfaces; no auth token required. */
    private static final String LOCALSTACK_IMAGE = "localstack/localstack:3";

    /** PostgreSQL 16 image — the VSAM&rarr;relational target database. */
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    /**
     * LocalStack container exposing the S3, SQS, and SNS surfaces. Started once in the static
     * initializer below (singleton pattern). The report-submission bridge ({@code CORPT00C}) publishes
     * to the SQS FIFO queue provisioned in {@link #setUpAwsResources()}.
     */
    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
                    // Testcontainers 2.x: withServices takes service NAMES as Strings.
                    .withServices("s3", "sqs", "sns");

    /**
     * PostgreSQL 16 container. Flyway ({@code V1}/{@code V2}/{@code V3}) creates the schema, indexes,
     * and seeds the nine ASCII fixtures (including the {@code USRSEC} users) on application startup.
     */
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo");

    static {
        // Manual, one-time start of the singleton containers (NOT @Container-managed) so they are
        // resolvable by the time the @DynamicPropertySource suppliers and the test-side SQS client run.
        LOCALSTACK.start();
        POSTGRES.start();
    }

    /**
     * Wires the JDBC datasource and Spring Cloud AWS (3.3.0) client configuration from the running
     * containers into the Spring {@link org.springframework.core.env.Environment}. Invoked statically
     * before any test instance exists. The property keys match the {@code test} profile and the proven
     * {@code AbstractLocalStackIntegrationTest} wiring exactly.
     *
     * @param registry the dynamic property registry supplied by the Spring TestContext framework
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // --- Spring Cloud AWS -> LocalStack ---
        registry.add("spring.cloud.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
        registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");

        // --- Datasource -> PostgreSQL 16 (explicit @Container: override the jdbc:tc: URL/driver) ---
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // --- Guards: keep batch jobs from auto-running and disable trace sampling in this test. ---
        registry.add("spring.batch.job.enabled", () -> "false");
        registry.add("management.tracing.sampling.probability", () -> "0.0");
    }

    // =============================================================================================
    // Seed credentials (USRSEC fixture). All ten seeded users share the plaintext password
    // "PASSWORD"; Flyway stores it BCrypt-hashed (constraint C-003), so a successful sign-on with the
    // plaintext below proves the BCrypt upgrade end-to-end.
    // =============================================================================================

    /** A seeded administrator user id (SEC-USR-TYPE 'A'). */
    private static final String ADMIN_USER_ID = "ADMIN001";

    /** A seeded regular user id (SEC-USR-TYPE 'U'). */
    private static final String REGULAR_USER_ID = "USER0001";

    /** The plaintext password every seeded {@code USRSEC} user shares; stored BCrypt-hashed. */
    private static final String SEED_PASSWORD = "PASSWORD";

    // =============================================================================================
    // Injected collaborators and HTTP plumbing.
    // =============================================================================================

    /** The random port the embedded servlet container bound to. */
    @LocalServerPort
    private int port;

    /** Boot-configured REST client; its default error handler does NOT throw on 4xx/5xx statuses. */
    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private UserSecurityRepository userSecurityRepository;

    /** Bound report-jobs queue name ({@code carddemo-report-jobs.fifo}) from the test profile. */
    @Autowired
    private AwsConfig.AwsResourceProperties awsResourceProperties;

    /** JSON mapper for parsing responses and SQS message bodies (java.time aware). */
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** Test-side SQS client pointed at LocalStack; owns the report-jobs queue lifecycle. */
    private SqsClient sqs;

    /** The resolved URL of the report-jobs FIFO queue created in {@link #setUpAwsResources()}. */
    private String reportQueueUrl;

    /** Account ids reserved by a mutating test so concurrent scenarios never collide on the same row. */
    private final Set<Long> reservedAccounts = ConcurrentHashMap.newKeySet();

    /** Lazily-cached bearer tokens so each role signs on at most once. */
    private volatile String adminTokenCache;
    private volatile String userTokenCache;

    // =============================================================================================
    // AWS resource lifecycle — create the SQS FIFO queue before the suite, delete it afterwards.
    // The test creates and destroys its own resources with zero live credentials (AAP §0.7.7).
    // =============================================================================================

    /**
     * Builds the test-side SQS client and creates the report-jobs FIFO queue that {@code CORPT00C}'s
     * migrated {@code ReportSubmissionService} publishes to. {@code @TestInstance(PER_CLASS)} makes this
     * non-static {@code @BeforeAll} legal so it can read the injected {@link #awsResourceProperties}.
     */
    @BeforeAll
    void setUpAwsResources() {
        sqs = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
        String queueName = awsResourceProperties.getSqs().getReportJobsQueue();
        reportQueueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName(queueName)
                .attributes(Map.of(
                        QueueAttributeName.FIFO_QUEUE, "true",
                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false"))
                .build())
                .queueUrl();
    }

    /** Tears down the report-jobs queue and closes the SQS client. */
    @AfterAll
    void tearDownAwsResources() {
        if (sqs != null) {
            try {
                if (reportQueueUrl != null) {
                    sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(reportQueueUrl).build());
                }
            } finally {
                sqs.close();
            }
        }
    }

    // =============================================================================================
    // HTTP + auth helpers (shared by every nested scenario class via the enclosing instance).
    // =============================================================================================

    /** Absolute base URL for the embedded servlet container. */
    private String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * Issues an HTTP request and returns the raw response (body as {@code String}). The body is read as
     * a String — never a typed DTO — so error envelopes are inspected uniformly and, critically, so a
     * {@code WRITE_ONLY} password field can never be re-serialized onto an outbound request.
     *
     * @param method the HTTP method
     * @param path   the request path (appended to {@link #baseUrl()})
     * @param body   the request body (a {@code Map} or {@code ObjectNode}); {@code null} for no body
     * @param token  the bearer token, or {@code null} for an unauthenticated request
     * @return the raw response entity (status + String body)
     */
    private ResponseEntity<String> exchange(HttpMethod method, String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(baseUrl() + path, method, new HttpEntity<>(body, headers), String.class);
    }

    /** Parses a response body to a {@link JsonNode}, failing fast on malformed JSON. */
    private JsonNode json(ResponseEntity<String> response) {
        return parseJson(response.getBody());
    }

    /** Parses an arbitrary JSON string (HTTP body or SQS message body) to a {@link JsonNode}. */
    private JsonNode parseJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Body was not valid JSON: " + raw, e);
        }
    }

    /** GETs a JSON object and returns it as a mutable {@link ObjectNode} for round-trip updates. */
    private ObjectNode getObject(String path, String token) {
        ResponseEntity<String> response = exchange(HttpMethod.GET, path, null, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (ObjectNode) json(response);
    }

    /** Builds an ordered request-body map from alternating key/value pairs (null values allowed). */
    private static Map<String, Object> body(Object... keyValuePairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }

    /**
     * Signs on through the real {@code POST /api/auth/signin} endpoint and returns the issued token.
     * Sends the credentials as a raw map (NOT a {@code SignOnRequest}) because the DTO's password is
     * {@code @JsonProperty(access = WRITE_ONLY)} and would be dropped on serialization.
     */
    private String signIn(String userId, String password) {
        ResponseEntity<String> response =
                exchange(HttpMethod.POST, "/api/auth/signin", body("userId", userId, "password", password), null);
        assertThat(response.getStatusCode())
                .as("sign-on for %s should succeed", userId)
                .isEqualTo(HttpStatus.OK);
        return json(response).path("token").asText();
    }

    /** Lazily-cached admin bearer token. */
    private String adminToken() {
        if (adminTokenCache == null) {
            adminTokenCache = signIn(ADMIN_USER_ID, SEED_PASSWORD);
        }
        return adminTokenCache;
    }

    /** Lazily-cached regular-user bearer token. */
    private String userToken() {
        if (userTokenCache == null) {
            userTokenCache = signIn(REGULAR_USER_ID, SEED_PASSWORD);
        }
        return userTokenCache;
    }

    /**
     * Reserves and returns a distinct seeded {@link Account} matching {@code predicate}, ensuring two
     * mutating scenarios never operate on the same row regardless of execution order.
     */
    private synchronized Account reserveAccount(Predicate<Account> predicate) {
        for (Account account : accountRepository.findAll()) {
            if (predicate.test(account) && reservedAccounts.add(account.getAcctId())) {
                return account;
            }
        }
        throw new IllegalStateException("No available seeded account matched the reservation predicate");
    }

    /** Predicate: an account carrying a strictly-positive current balance (eligible for bill payment). */
    private static boolean hasPositiveBalance(Account account) {
        return account.getAcctCurrBal() != null
                && account.getAcctCurrBal().compareTo(BigDecimal.ZERO) > 0;
    }

    /** Returns an 11-digit account id guaranteed absent from the seed (for 404 assertions). */
    private String missingAccountId() {
        Set<Long> existing = ConcurrentHashMap.newKeySet();
        accountRepository.findAll().forEach(a -> existing.add(a.getAcctId()));
        long candidate = 99_999_999_999L; // eleven nines — valid 11-digit non-zero key
        while (existing.contains(candidate)) {
            candidate--;
        }
        return Long.toString(candidate);
    }

    /** Receives (long-poll) any messages currently visible on the report-jobs queue. */
    private List<Message> receiveReportMessages() {
        return sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(reportQueueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(10)
                .messageAttributeNames("All")
                .build())
                .messages();
    }

    /** Removes every message currently on the report-jobs queue so a scenario starts from empty. */
    private void drainReportQueue() {
        while (true) {
            List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(reportQueueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(1)
                    .build())
                    .messages();
            if (messages.isEmpty()) {
                return;
            }
            for (Message message : messages) {
                sqs.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(reportQueueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build());
            }
        }
    }

    /**
     * Asserts that no {@code password} field appears anywhere in the response tree (objects or array
     * elements) — the BCrypt/privacy contract (constraint C-003): passwords are never echoed.
     */
    private void assertNoPasswordEchoed(JsonNode node) {
        assertThat(containsPasswordField(node))
                .as("no response may ever echo a password field")
                .isFalse();
    }

    /** Recursively detects a {@code password} property anywhere in a JSON tree. */
    private boolean containsPasswordField(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject() && node.has("password")) {
            return true;
        }
        for (JsonNode child : node) { // iterates object values or array elements
            if (containsPasswordField(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Overwrites every {@code COACTUPC}-edited customer/account field on a freshly-viewed
     * {@link com.cardemo.model.dto.AccountDto} JSON node with values that are KNOWN-VALID against the
     * <em>real</em> validation lookups, leaving the date/balance fields supplied by the view untouched.
     *
     * <p>The nine ASCII fixtures were randomly generated, so many account/customer rows carry values the
     * strict {@code COACTUPC} edit cascade rejects — FICO scores outside the inclusive 300..850 band,
     * telephone area codes absent from the NANPA table ({@code CSLKPCDY VALID-GENERAL-PURP-CODE}), and
     * state/ZIP combinations absent from {@code CSLKPCDY VALID-US-STATE-ZIP-CD2-COMBO}. A blind
     * view&rarr;update round-trip would therefore fail validation for most reserved accounts. Normalizing
     * the edited fields here lets the happy-path test prove the 200 + {@code @Version} bump deterministically,
     * independent of which seed account {@link #reserveAccount(Predicate)} hands out. Every literal below is
     * verified against the migrated lookup resources: {@code 201} is a NANPA area code, {@code NC} is a US
     * state whose valid ZIP prefixes include {@code 27}, and the SSN uses the {@code XXX-XX-XXXX} shape that
     * {@code 1265-EDIT-US-SSN} accepts.</p>
     *
     * @param dto the viewed account DTO node, mutated in place
     * @return the same node, for fluent chaining
     */
    private ObjectNode normalizeForValidUpdate(ObjectNode dto) {
        dto.put("accountStatus", "Y");                 // 1220-EDIT-YESNO
        dto.put("ssn", "020-97-3888");                 // 1265-EDIT-US-SSN (XXX-XX-XXXX)
        dto.put("ficoScore", "750");                   // 1275-EDIT-FICO-SCORE (300..850)
        dto.put("firstName", "Immanuel");              // 1225-EDIT-ALPHA-REQD
        dto.put("middleName", "Madeline");             // 1235-EDIT-ALPHA-OPT
        dto.put("lastName", "Kessler");                // 1225-EDIT-ALPHA-REQD
        dto.put("addressLine1", "100 Main Street");    // 1215-EDIT-MANDATORY
        dto.put("city", "Raleigh");                    // 1225-EDIT-ALPHA-REQD
        dto.put("state", "NC");                        // 1270-EDIT-US-STATE-CD
        dto.put("zipCode", "27101");                   // prefix 27 -> valid NC ZIP (1280 state/ZIP combo)
        dto.put("countryCode", "USA");                 // 1225-EDIT-ALPHA-REQD
        dto.put("phoneNumber1", "(201)555-0100");      // 1260-EDIT-US-PHONE-NUM (area 201 is NANPA-valid)
        dto.put("phoneNumber2", "(201)555-0101");      // 1260-EDIT-US-PHONE-NUM
        dto.put("eftAccountId", "0053581756");         // 1245-EDIT-NUM-REQD (length 10)
        dto.put("primaryCardHolderIndicator", "Y");    // 1220-EDIT-YESNO
        return dto;
    }

    // =============================================================================================
    // 1. Authentication & security — COSGN00C (txn CC00) + SecurityConfig route protection.
    // =============================================================================================

    /**
     * Sign-on parity ({@code COSGN00C}). The endpoint deliberately omits {@code @Valid} so the migrated
     * {@code AuthenticationService} emits the verbatim, ORDERED COBOL validation prompts; the
     * {@code GlobalExceptionHandler} surfaces a single-argument {@link com.cardemo.exception.ValidationException}'s
     * message verbatim in the {@code message} field. BCrypt (constraint C-003) is proven by a successful
     * sign-on against the hashed seed plus a direct inspection of the stored hash.
     */
    @Nested
    @DisplayName("1. Authentication & security (COSGN00C / SecurityConfig)")
    class AuthenticationAndSecurity {

        @Test
        @DisplayName("Valid admin sign-on returns 200 with an ADMIN token and routing targets")
        void validAdminSignOnReturnsAdminToken() {
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/auth/signin",
                    body("userId", ADMIN_USER_ID, "password", SEED_PASSWORD), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode signOn = json(response);
            assertThat(signOn.path("token").asText()).isNotBlank();
            assertThat(signOn.path("userId").asText()).isEqualTo(ADMIN_USER_ID);
            assertThat(signOn.path("userType").asText()).isEqualTo("ADMIN");
            // COBOL routing targets are preserved (admin sign-on routes to the admin menu transaction).
            assertThat(signOn.path("toTranId").asText()).isNotBlank();
            assertThat(signOn.path("toProgram").asText()).isNotBlank();
            // Privacy (C-003): the password is never echoed back.
            assertNoPasswordEchoed(signOn);
        }

        @Test
        @DisplayName("Valid regular-user sign-on returns 200 with a USER token")
        void validRegularUserSignOnReturnsUserToken() {
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/auth/signin",
                    body("userId", REGULAR_USER_ID, "password", SEED_PASSWORD), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode signOn = json(response);
            assertThat(signOn.path("token").asText()).isNotBlank();
            assertThat(signOn.path("userType").asText()).isEqualTo("USER");
            assertNoPasswordEchoed(signOn);
        }

        @Test
        @DisplayName("BCrypt upgrade (C-003): seed password is stored hashed yet a plaintext sign-on works")
        void seedPasswordIsBcryptHashedYetUsable() {
            // The stored secret is a BCrypt hash, never the plaintext (the single permitted deviation).
            UserSecurity admin = userSecurityRepository.findById(ADMIN_USER_ID).orElseThrow();
            assertThat(admin.getSecUsrPwd()).startsWith("$2");
            assertThat(admin.getSecUsrPwd()).isNotEqualTo(SEED_PASSWORD);
            // Yet a plaintext sign-on still succeeds end-to-end — BCrypt verification in the login flow.
            assertThat(signIn(ADMIN_USER_ID, SEED_PASSWORD)).isNotBlank();
        }

        @Test
        @DisplayName("Wrong password returns 400 (ValidationException) with the verbatim COBOL prompt")
        void wrongPasswordReturns400() {
            // Parity: COSGN00C's wrong-password branch is a validation prompt (HTTP 400), NOT a 404.
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/auth/signin",
                    body("userId", ADMIN_USER_ID, "password", "WRONGPWD"), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(response).path("message").asText()).containsIgnoringCase("Wrong Password");
        }

        @Test
        @DisplayName("Unknown user returns 404 (RecordNotFoundException) with the verbatim COBOL prompt")
        void unknownUserReturns404() {
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/auth/signin",
                    body("userId", "NOSUCHUS", "password", SEED_PASSWORD), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(json(response).path("message").asText()).containsIgnoringCase("User not found");
        }

        @Test
        @DisplayName("Ordered validation: empty input reports the User ID prompt FIRST (400)")
        void emptyInputReportsUserIdPromptFirst() {
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/auth/signin",
                    body("userId", "", "password", ""), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            String message = json(response).path("message").asText();
            // The first edit in the cascade is the User ID prompt; the Password prompt must NOT win here.
            assertThat(message).containsIgnoringCase("User ID");
            assertThat(message).doesNotContainIgnoringCase("Password");
        }

        @Test
        @DisplayName("Ordered validation: a present User ID with empty password reports the Password prompt (400)")
        void emptyPasswordReportsPasswordPromptSecond() {
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/auth/signin",
                    body("userId", ADMIN_USER_ID, "password", ""), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            // With a valid User ID the cascade advances to the second edit — the Password prompt.
            assertThat(json(response).path("message").asText()).containsIgnoringCase("Password");
        }

        @Test
        @DisplayName("Ordered validation: an over-length (>8) User ID reports the length guard (400)")
        void overLengthUserIdReportsLengthGuard() {
            // "TOOLONG12" is nine characters; the length edit precedes the user lookup, so we get the
            // length prompt (not "User not found").
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/auth/signin",
                    body("userId", "TOOLONG12", "password", SEED_PASSWORD), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(response).path("message").asText()).containsIgnoringCase("8 characters");
        }

        @Test
        @DisplayName("Admin-only route rejects an anonymous caller with 401")
        void adminRouteRejectsAnonymous() {
            ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/admin/users", null, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Admin-only route rejects an authenticated non-admin with 403")
        void adminRouteRejectsNonAdmin() {
            ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/admin/users", null, userToken());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // =============================================================================================
    // 2. Menu — COMEN01C (main, 10 options) / COADM01C (admin, 4 options).
    // =============================================================================================

    /**
     * Menu parity. {@code GET /api/menu/main} yields the ten-option main menu ({@code CM00}); the
     * {@code admin} type yields the four-option admin menu ({@code CA00}) and is additionally gated to
     * the {@code ADMIN} role by {@code SecurityConfig}. Any other type is a validation error (400).
     */
    @Nested
    @DisplayName("2. Menu (COMEN01C / COADM01C)")
    class Menu {

        @Test
        @DisplayName("Main menu returns 200 with exactly 10 options")
        void mainMenuReturnsTenOptions() {
            ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/menu/main", null, userToken());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode menu = json(response);
            assertThat(menu.path("menuType").asText()).isNotBlank();
            assertThat(menu.path("options")).hasSize(10);
        }

        @Test
        @DisplayName("Admin menu returns 200 with exactly 4 options (admin token)")
        void adminMenuReturnsFourOptions() {
            ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/menu/admin", null, adminToken());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(response).path("options")).hasSize(4);
        }

        @Test
        @DisplayName("Admin menu is forbidden (403) for a non-admin caller")
        void adminMenuForbiddenForNonAdmin() {
            // SecurityConfig gates GET /api/menu/admin to ROLE_ADMIN at the filter chain (before the
            // controller runs), so a regular user is rejected with 403.
            ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/menu/admin", null, userToken());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("An unknown menu type returns 400 (the 'user' alias is not accepted)")
        void unknownMenuTypeReturns400() {
            // Parity correction: only {main, admin} are accepted; any other value (including the legacy
            // "user" alias) is a ValidationException -> 400.
            ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/menu/user", null, userToken());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Menu requires authentication (401 without a token)")
        void menuRequiresAuthentication() {
            ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/menu/main", null, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // =============================================================================================
    // 3. Account view/update — COACTVWC (multi-read) / COACTUPC (@Transactional dual update + @Version).
    // =============================================================================================

    /**
     * Account parity. The view is the {@code ACCTDAT}+{@code CUSTDAT}+{@code CXACAIX} multi-read; the
     * update is the dual {@code ACCTDAT}+{@code CUSTDAT} rewrite — the estate's sole
     * {@code SYNCPOINT ROLLBACK}, mapped to {@code @Transactional(rollbackFor = Exception.class)} — with
     * the {@code @Version} optimistic-lock check. The COBOL two-step PF5 confirm collapses to one PUT.
     */
    @Nested
    @DisplayName("3. Account view/update (COACTVWC / COACTUPC)")
    class AccountViewUpdate {

        @Test
        @DisplayName("View returns 200 with fixture-consistent account + customer data")
        void viewReturnsFixtureData() {
            Account account = reserveAccount(a -> true);
            String id = Long.toString(account.getAcctId());

            ObjectNode dto = getObject("/api/accounts/" + id, userToken());
            // COACTVWC preserves the 11-digit COBOL ACCT-ID width (PIC 9(11)), so the response echoes a
            // zero-padded id (e.g. "00000000006"); compare by numeric identity to the seeded key.
            assertThat(Long.parseLong(dto.path("accountId").asText())).isEqualTo(account.getAcctId());
            assertThat(dto.has("version")).isTrue();
            // currentBalance must round-trip to the seeded value with scale-insensitive comparison.
            assertThat(new BigDecimal(dto.path("currentBalance").asText()))
                    .isEqualByComparingTo(account.getAcctCurrBal());
            // Customer side of the multi-read is populated.
            assertThat(dto.path("firstName").asText()).isNotBlank();
        }

        @Test
        @DisplayName("Unknown account id returns 404")
        void unknownAccountReturns404() {
            ResponseEntity<String> response =
                    exchange(HttpMethod.GET, "/api/accounts/" + missingAccountId(), null, userToken());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Valid update returns 200 and bumps @Version; a stale resubmit returns 409")
        void validUpdateBumpsVersionThenStaleResubmitReturns409() {
            Account account = reserveAccount(a -> true);
            String id = Long.toString(account.getAcctId());

            ObjectNode dto = getObject("/api/accounts/" + id, userToken());
            long originalVersion = dto.path("version").asLong();
            BigDecimal originalLimit = new BigDecimal(dto.path("creditLimit").asText());
            BigDecimal firstNewLimit = originalLimit.add(new BigDecimal("100.00"));

            // Normalize the COACTUPC-edited customer fields to values that pass the REAL validation
            // lookups (the randomly-generated fixtures carry many rows the strict edits reject — FICO
            // out of 300..850, non-NANPA area codes, state/ZIP combos absent from CSLKPCDY), then change
            // one ACCOUNT-side field (credit limit). The body echoes the version seen at view time, so
            // this is a valid mutation -> 200 with a bumped @Version (the PF5 confirm collapses to one PUT).
            normalizeForValidUpdate(dto);
            dto.put("creditLimit", firstNewLimit);
            ResponseEntity<String> firstPut =
                    exchange(HttpMethod.PUT, "/api/accounts/" + id, dto, userToken());
            assertThat(firstPut.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode updated = json(firstPut);
            assertThat(updated.path("version").asLong()).isGreaterThan(originalVersion);
            assertThat(new BigDecimal(updated.path("creditLimit").asText()))
                    .isEqualByComparingTo(firstNewLimit);

            // The same form still carries the now-STALE original version. Make a fresh field change so
            // the no-change short-circuit does not fire, then resubmit -> optimistic-lock conflict (409).
            dto.put("creditLimit", originalLimit.add(new BigDecimal("200.00")));
            ResponseEntity<String> stalePut =
                    exchange(HttpMethod.PUT, "/api/accounts/" + id, dto, userToken());
            assertThat(stalePut.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("Invalid customer field returns 400 and rolls back BOTH records (dual-update atomicity)")
        void invalidUpdateReturns400AndRollsBackBothRecords() {
            Account account = reserveAccount(a -> true);
            String id = Long.toString(account.getAcctId());

            ObjectNode dto = getObject("/api/accounts/" + id, userToken());
            String originalFico = dto.path("ficoScore").asText();
            BigDecimal originalLimit = new BigDecimal(dto.path("creditLimit").asText());

            // Change an ACCOUNT-side field (credit limit) AND set an INVALID customer-side field (FICO
            // 999 is outside the 300..850 contract). The dual write must be atomic: the field-edit
            // cascade rejects the request (400) before any row is written; the @Transactional boundary
            // guarantees that even a mid-transaction failure would roll back both ACCTDAT and CUSTDAT.
            dto.put("creditLimit", originalLimit.add(new BigDecimal("100.00")));
            dto.put("ficoScore", "999");
            ResponseEntity<String> response =
                    exchange(HttpMethod.PUT, "/api/accounts/" + id, dto, userToken());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(response).path("fieldErrors").isArray()).isTrue();
            assertThat(json(response).path("fieldErrors")).isNotEmpty();

            // Re-read: NEITHER the account credit limit NOR the customer FICO changed.
            ObjectNode after = getObject("/api/accounts/" + id, userToken());
            assertThat(new BigDecimal(after.path("creditLimit").asText()))
                    .isEqualByComparingTo(originalLimit);
            assertThat(after.path("ficoScore").asText()).isEqualTo(originalFico);
        }
    }

    // =============================================================================================
    // 4. Cards — COCRDLIC (list, 7/page) / COCRDSLC (detail) / COCRDUPC (update + @Version).
    // =============================================================================================

    /**
     * Card parity. The list browses {@code CARDDAT} at exactly seven rows per page (1-based); the detail
     * is a single keyed read; the update is the second {@code @Version} optimistic-lock site
     * ({@code COCRDUPC}).
     */
    @Nested
    @DisplayName("4. Cards (COCRDLIC / COCRDSLC / COCRDUPC)")
    class Cards {

        @Test
        @DisplayName("Unfiltered list returns exactly 7 rows on page 1 (1-based)")
        void listFirstPageHasSevenRows() {
            ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/cards?page=1", null, adminToken());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode dto = json(response);
            assertThat(dto.path("cards")).hasSize(7); // CardDto.ROWS_PER_PAGE
            assertThat(dto.path("pageNumber").asText()).isEqualTo("1");
        }

        @Test
        @DisplayName("List filtered by accountId returns only that account's cards")
        void listFilteredByAccount() {
            Card card = cardRepository.findAll().stream()
                    .min(Comparator.comparing(Card::getCardNum)).orElseThrow();
            String accountId = Long.toString(card.getCardAcctId());

            ResponseEntity<String> response =
                    exchange(HttpMethod.GET, "/api/cards?accountId=" + accountId + "&page=1", null, adminToken());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode cards = json(response).path("cards");
            assertThat(cards).isNotEmpty();
            long expectedAccountId = card.getCardAcctId();
            for (JsonNode row : cards) {
                // COCRDLIC preserves the 11-digit COBOL account-id width; compare by numeric identity.
                assertThat(Long.parseLong(row.path("accountId").asText())).isEqualTo(expectedAccountId);
            }
        }

        @Test
        @DisplayName("Detail returns 200 for a known card; unknown returns 404")
        void detailReturns200AndUnknown404() {
            Card card = cardRepository.findAll().stream()
                    .min(Comparator.comparing(Card::getCardNum)).orElseThrow();
            String accountId = Long.toString(card.getCardAcctId());

            ResponseEntity<String> found = exchange(HttpMethod.GET,
                    "/api/cards/" + card.getCardNum() + "?accountId=" + accountId, null, adminToken());
            assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(found).path("cardNumber").asText()).isEqualTo(card.getCardNum());

            ResponseEntity<String> missing = exchange(HttpMethod.GET,
                    "/api/cards/9999999999999999?accountId=" + accountId, null, adminToken());
            assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Valid update returns 200 and bumps @Version; a stale resubmit returns 409")
        void validUpdateBumpsVersionThenStaleResubmitReturns409() {
            // Use the highest card number so this mutation never collides with the detail/list reads.
            Card card = cardRepository.findAll().stream()
                    .max(Comparator.comparing(Card::getCardNum)).orElseThrow();
            String cardNumber = card.getCardNum();
            String accountId = Long.toString(card.getCardAcctId());
            long originalVersion = card.getVersion();

            // Construct a fully valid update payload (all COCRDUPC edits satisfied) rather than
            // round-tripping the detail, so every required field is present and well-formed.
            Map<String, Object> request = body(
                    "accountId", accountId,
                    "cardNumber", cardNumber,
                    "embossedName", "ALPHA NAME ONE",
                    "activeStatus", "Y",
                    "expiryMonth", "12",
                    "expiryYear", "2031",
                    "version", originalVersion);

            ResponseEntity<String> firstPut =
                    exchange(HttpMethod.PUT, "/api/cards/" + cardNumber, request, adminToken());
            assertThat(firstPut.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(firstPut).path("version").asLong()).isGreaterThan(originalVersion);

            // Resubmit with the now-stale original version and a fresh field change -> 409.
            request.put("embossedName", "ALPHA NAME TWO");
            request.put("version", originalVersion);
            ResponseEntity<String> stalePut =
                    exchange(HttpMethod.PUT, "/api/cards/" + cardNumber, request, adminToken());
            assertThat(stalePut.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    // =============================================================================================
    // 5. Transactions — COTRN00C (list, 10/page) / COTRN01C (detail) / COTRN02C (add, auto-id).
    // =============================================================================================

    /**
     * Transaction parity. The list browses {@code TRANSACT} at ten rows per page (1-based); the detail
     * is a single keyed read; the add resolves the card cross-reference, applies the field-edit cascade,
     * and auto-generates the next 16-digit transaction id (browse-to-end + increment, {@code COTRN02C}).
     * The {@code transaction} table is NOT seeded, so the first add receives id
     * {@code 0000000000000001}. The {@code amount} is a signed scale-2 {@code BigDecimal}.
     */
    @Nested
    @DisplayName("5. Transactions (COTRN00C / COTRN01C / COTRN02C)")
    class Transactions {

        /** Posts a fully valid transaction (confirm = Y) and returns its auto-generated id. */
        private String addTransaction(String accountId) {
            Map<String, Object> request = body(
                    "accountId", accountId,
                    "typeCode", "01",
                    "categoryCode", "1",
                    "source", "POS",
                    "description", "E2E parity transaction",
                    "amount", new BigDecimal("123.45"),
                    "originationDate", "2025-01-15",
                    "processingDate", "2025-01-15",
                    "merchantId", "123456789",
                    "merchantName", "E2E Merchant",
                    "merchantCity", "Seattle",
                    "merchantZip", "98101",
                    "confirm", "Y");
            ResponseEntity<String> response =
                    exchange(HttpMethod.POST, "/api/transactions", request, userToken());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            return json(response).path("transactionId").asText();
        }

        /** Resolves a seeded account id that the card cross-reference can map to a card. */
        private String resolvableAccountId() {
            Card card = cardRepository.findAll().stream()
                    .min(Comparator.comparing(Card::getCardNum)).orElseThrow();
            return Long.toString(card.getCardAcctId());
        }

        @Test
        @DisplayName("Add returns 201 with an auto-generated 16-digit id; detail then returns 200 with the amount")
        void addReturns201WithAutoIdAndDetailRoundTrips() {
            String id = addTransaction(resolvableAccountId());
            assertThat(id).matches("\\d{16}");

            ResponseEntity<String> detail = exchange(HttpMethod.GET, "/api/transactions/" + id, null, userToken());
            assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode txn = json(detail);
            assertThat(txn.path("transactionId").asText()).isEqualTo(id);
            // Decimal fidelity (§0.7.3): compare via compareTo, never equals.
            assertThat(new BigDecimal(txn.path("amount").asText()))
                    .isEqualByComparingTo(new BigDecimal("123.45"));
        }

        @Test
        @DisplayName("List paginates at 10 rows per page, 1-based")
        void listPaginatesTenPerPageOneBased() {
            // Guarantee more than one full page exists, independent of execution order.
            String accountId = resolvableAccountId();
            for (int i = 0; i < 11; i++) {
                addTransaction(accountId);
            }

            ResponseEntity<String> page1 = exchange(HttpMethod.GET, "/api/transactions?page=1", null, userToken());
            assertThat(page1.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode page1Dto = json(page1);
            assertThat(page1Dto.path("transactions")).hasSize(10); // TransactionDto.ROWS_PER_PAGE
            // COTRN00C preserves the COBOL page-number field width (PIC 9(08)), so the response echoes a
            // zero-padded value (e.g. "00000001"); compare numerically against the 1-based page index.
            assertThat(Integer.parseInt(page1Dto.path("pageNumber").asText())).isEqualTo(1);

            ResponseEntity<String> page2 = exchange(HttpMethod.GET, "/api/transactions?page=2", null, userToken());
            assertThat(page2.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode page2Dto = json(page2);
            assertThat(page2Dto.path("transactions").size()).isGreaterThanOrEqualTo(1);
            assertThat(Integer.parseInt(page2Dto.path("pageNumber").asText())).isEqualTo(2);
        }

        @Test
        @DisplayName("Unknown transaction id returns 404")
        void unknownTransactionReturns404() {
            ResponseEntity<String> response =
                    exchange(HttpMethod.GET, "/api/transactions/9999999999999999", null, userToken());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        // NOTE: the duplicate-id -> 409 (DuplicateRecordException) path of COTRN02C is not REST-inducible:
        // the auto-id factory (browse-to-end + increment) makes a natural duplicate impossible through the
        // public endpoint. That branch is exercised by the service-level unit test, not this e2e boundary.
    }

    // =============================================================================================
    // 6. Billing — COBIL00C (txn CB00): full-balance bill payment (Y/N/preview tri-state).
    // =============================================================================================

    /**
     * Bill-payment parity. {@code confirm = "Y"} clears the FULL current balance and writes a payment
     * transaction in one {@code @Transactional} unit; {@code "N"} cancels with no side-effect (the
     * account is never even read); a blank/absent confirm is a non-mutating preview. All monetary
     * assertions use {@code compareTo}.
     */
    @Nested
    @DisplayName("6. Billing (COBIL00C)")
    class Billing {

        @Test
        @DisplayName("Preview (blank confirm) returns 200 with the balance and does NOT mutate")
        void previewReturnsBalanceWithoutMutating() {
            Account account = reserveAccount(OnlineTransactionE2ETest::hasPositiveBalance);
            String id = Long.toString(account.getAcctId());
            BigDecimal originalBalance = account.getAcctCurrBal();

            ResponseEntity<String> response =
                    exchange(HttpMethod.POST, "/api/billing/pay", body("accountId", id), userToken());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(new BigDecimal(json(response).path("currentBalance").asText()))
                    .isEqualByComparingTo(originalBalance);
            // No mutation: the stored balance is unchanged.
            assertThat(accountRepository.findById(account.getAcctId()).orElseThrow().getAcctCurrBal())
                    .isEqualByComparingTo(originalBalance);
        }

        @Test
        @DisplayName("Confirm Y pays the full balance (zeroed) and creates a payment transaction, atomically")
        void confirmYesZeroesBalanceAndCreatesTransaction() {
            Account account = reserveAccount(OnlineTransactionE2ETest::hasPositiveBalance);
            String id = Long.toString(account.getAcctId());
            BigDecimal originalBalance = account.getAcctCurrBal();

            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/billing/pay",
                    body("accountId", id, "confirm", "Y"), userToken());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode payment = json(response);
            assertThat(new BigDecimal(payment.path("currentBalance").asText()))
                    .isEqualByComparingTo(originalBalance);
            // Full payment zeroes the balance and yields a generated payment transaction id.
            assertThat(new BigDecimal(payment.path("newBalance").asText()))
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(payment.path("transactionId").asText()).matches("\\d{16}");
            // The balance-update + transaction-create committed together: the stored balance is now zero.
            assertThat(accountRepository.findById(account.getAcctId()).orElseThrow().getAcctCurrBal())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Confirm N cancels with no side-effect (200, balance unchanged)")
        void confirmNoCancelsWithoutSideEffect() {
            Account account = reserveAccount(OnlineTransactionE2ETest::hasPositiveBalance);
            String id = Long.toString(account.getAcctId());
            BigDecimal originalBalance = account.getAcctCurrBal();

            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/billing/pay",
                    body("accountId", id, "confirm", "N"), userToken());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(accountRepository.findById(account.getAcctId()).orElseThrow().getAcctCurrBal())
                    .isEqualByComparingTo(originalBalance);
        }

        @Test
        @DisplayName("An invalid confirm value returns 400 with the verbatim (Y/N) prompt")
        void invalidConfirmReturns400() {
            Account account = reserveAccount(a -> true);
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/billing/pay",
                    body("accountId", Long.toString(account.getAcctId()), "confirm", "X"), userToken());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(response).path("message").asText()).containsIgnoringCase("Y/N");
        }

        @Test
        @DisplayName("An unknown account returns 404")
        void unknownAccountReturns404() {
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/billing/pay",
                    body("accountId", missingAccountId(), "confirm", "Y"), userToken());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // =============================================================================================
    // 7. Report submission bridge — CORPT00C (txn CR00): the SOLE online->batch coupling (TDQ -> SQS).
    // =============================================================================================

    /**
     * Report-submission parity — the single most important integration-bridge assertion in this file
     * (AAP §0.6.3). {@code CORPT00C} wrote a report request to the CICS TDQ {@code JOBS}; the migrated
     * {@code ReportSubmissionService} publishes it to the SQS FIFO queue {@code carddemo-report-jobs.fifo}.
     * A confirmed submission returns <strong>202 Accepted</strong> AND a well-formed message appears on
     * the LocalStack queue; a declined submission returns <strong>200 OK</strong> with NO message. The
     * YEARLY report type is used (its date range derives from the clock, needing no user-entered dates).
     */
    @Nested
    @DisplayName("7. Report submission bridge (CORPT00C — TDQ -> SQS)")
    class ReportBridge {

        @Test
        @DisplayName("Confirmed submit returns 202 AND publishes a well-formed message to the FIFO queue")
        void confirmedSubmitReturns202AndPublishes() {
            drainReportQueue();

            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/reports/submit",
                    body("yearly", "Y", "confirm", "Y"), userToken());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            JsonNode result = json(response);
            assertThat(result.path("submitted").asBoolean()).isTrue();
            assertThat(result.path("reportType").asText()).isEqualTo("YEARLY");

            // The bridge fired: exactly the report request is observable on the LocalStack FIFO queue.
            List<Message> messages = receiveReportMessages();
            assertThat(messages).isNotEmpty();
            JsonNode payload = parseJson(messages.get(0).body());
            assertThat(payload.path("reportType").asText()).isEqualTo("YEARLY");
            assertThat(payload.path("startDate").asText()).isNotBlank();
            assertThat(payload.path("endDate").asText()).isNotBlank();
        }

        @Test
        @DisplayName("Declined submit (confirm N) returns 200 and publishes NO message")
        void declinedSubmitReturns200NoPublish() {
            drainReportQueue();

            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/reports/submit",
                    body("yearly", "Y", "confirm", "N"), userToken());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(response).path("submitted").asBoolean()).isFalse();

            // No message was published on the cancel path.
            List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(reportQueueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(3)
                    .build())
                    .messages();
            assertThat(messages).isEmpty();
        }

        @Test
        @DisplayName("Blank confirm returns 400 (must confirm to print)")
        void blankConfirmReturns400() {
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/reports/submit",
                    body("yearly", "Y", "confirm", ""), userToken());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("No report type selected returns 400 (select a report type)")
        void noTypeSelectedReturns400() {
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/reports/submit",
                    body("confirm", "Y"), userToken());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // =============================================================================================
    // 8. User administration — COUSR00C-COUSR03C: admin-only CRUD, BCrypt on add, no password echo.
    // =============================================================================================

    /**
     * User-administration parity ({@code COUSR00C}–{@code COUSR03C}). All routes are gated to the
     * {@code ADMIN} role by {@code SecurityConfig}. The list browses {@code USRSEC} at ten rows per page
     * (1-based); add hashes the password with BCrypt (constraint C-003) and a duplicate id is a 409;
     * passwords are NEVER echoed in any response.
     */
    @Nested
    @DisplayName("8. User administration (COUSR00C-COUSR03C)")
    class UserAdmin {

        @Test
        @DisplayName("List returns 200, 10 rows per page (1-based), and never echoes a password")
        void listUsersTenPerPage() {
            ResponseEntity<String> response =
                    exchange(HttpMethod.GET, "/api/admin/users?page=1", null, adminToken());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode dto = json(response);
            assertThat(dto.path("users")).hasSize(10); // 10 seeded USRSEC users fill page 1
            assertThat(dto.path("pageNumber").asText()).isEqualTo("1");
            assertNoPasswordEchoed(dto);
        }

        @Test
        @DisplayName("Get returns 200 for a known user (no password); unknown returns 404")
        void getUserReturns200AndUnknown404() {
            ResponseEntity<String> found =
                    exchange(HttpMethod.GET, "/api/admin/users/" + ADMIN_USER_ID, null, adminToken());
            assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode user = json(found);
            assertThat(user.path("userId").asText()).isEqualTo(ADMIN_USER_ID);
            assertNoPasswordEchoed(user);

            ResponseEntity<String> missing =
                    exchange(HttpMethod.GET, "/api/admin/users/NOSUCHID", null, adminToken());
            assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Create returns 201, BCrypt-hashes the password, never echoes it, and the new login works")
        void createUserHashesPasswordAndIsUsable() {
            String newUserId = "E2EU0001";
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/admin/users",
                    body("userId", newUserId, "firstName", "Test", "lastName", "User",
                            "password", SEED_PASSWORD, "userType", "USER"),
                    adminToken());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            JsonNode created = json(response);
            assertThat(created.path("userId").asText()).isEqualTo(newUserId);
            assertNoPasswordEchoed(created);

            // BCrypt (C-003): the stored secret is a hash, not the plaintext, yet the new login works.
            UserSecurity stored = userSecurityRepository.findById(newUserId).orElseThrow();
            assertThat(stored.getSecUsrPwd()).startsWith("$2");
            assertThat(stored.getSecUsrPwd()).isNotEqualTo(SEED_PASSWORD);
            assertThat(signIn(newUserId, SEED_PASSWORD)).isNotBlank();
        }

        @Test
        @DisplayName("Create with an existing id returns 409")
        void createDuplicateReturns409() {
            // ADMIN001 is a seeded id, so this is a deterministic duplicate regardless of test order.
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/admin/users",
                    body("userId", ADMIN_USER_ID, "firstName", "Dup", "lastName", "User",
                            "password", SEED_PASSWORD, "userType", "ADMIN"),
                    adminToken());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("Update returns 200 and never echoes a password")
        void updateUserReturns200() {
            // Update a seed user that is NOT used to mint tokens (USER0001/ADMIN001).
            String targetId = "USER0005";
            ResponseEntity<String> response = exchange(HttpMethod.PUT, "/api/admin/users/" + targetId,
                    body("userId", targetId, "firstName", "Updated", "lastName", "Name",
                            "password", SEED_PASSWORD, "userType", "USER"),
                    adminToken());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertNoPasswordEchoed(json(response));
        }

        @Test
        @DisplayName("Delete returns 200 echoing the deleted user (no password)")
        void deleteUserReturns200Echo() {
            // Create a throwaway user, then delete it, so no seed user is removed.
            String deletableId = "E2EDEL01";
            ResponseEntity<String> create = exchange(HttpMethod.POST, "/api/admin/users",
                    body("userId", deletableId, "firstName", "Delete", "lastName", "Me",
                            "password", SEED_PASSWORD, "userType", "USER"),
                    adminToken());
            assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            ResponseEntity<String> response =
                    exchange(HttpMethod.DELETE, "/api/admin/users/" + deletableId, null, adminToken());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode deleted = json(response);
            assertThat(deleted.path("userId").asText()).isEqualTo(deletableId);
            assertNoPasswordEchoed(deleted);
        }
    }
}
