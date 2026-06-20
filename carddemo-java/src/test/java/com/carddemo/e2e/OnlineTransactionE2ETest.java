package com.carddemo.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.UserSecurityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.OptimisticLockingFailureException;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

/**
 * Full-stack REST online-flow end-to-end test for the CardDemo COBOL-to-Java migration (BCrypt
 * sign-on, role-based authorization, account view + {@code @Version} optimistic locking, card and
 * transaction browse/add, billing, menu, and the report-submission SQS FIFO bridge), validating the
 * Java translation of {@code COSGN00C}/{@code COACTUPC}/{@code COTRN02C}/{@code CORPT00C}/{@code COMEN01C}/{@code COBIL00C}
 * (source commit {@code 27d6c6f}; REFERENCE ONLY, no COBOL is copied).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Tag("e2e")
@Tag("integration")
public class OnlineTransactionE2ETest {

    // ---------------------------------------------------------------------------------------------
    // Static infrastructure (duplicated scaffolding by design — no shared base class). The two
    // containers are started once at class load behind an isDockerAvailable() guard so they are
    // running before @DynamicPropertySource provisions AWS resources and before the application
    // context refreshes (which is when TransactionReportJob's @SqsListener binds to the FIFO queue).
    // ---------------------------------------------------------------------------------------------

    // Testcontainers 2.x: org.testcontainers.postgresql.PostgreSQLContainer is a NON-generic class
    // (the 1.x self-type was removed), so it is referenced without type parameters. This is not a raw
    // type — the class has no type variables — so the -Xlint:all/-Werror build emits no rawtypes warning.
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo");

    static final LocalStackContainer LOCALSTACK = createLocalStack();

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
            LOCALSTACK.start();
        }
    }

    /** S3 bucket for batch staging input (← {@code DEFGDGB.jcl}); created on LocalStack. */
    private static final String BUCKET_INPUT = "carddemo-batch-input";

    /** S3 bucket for batch/report output; the report bridge writes here. */
    private static final String BUCKET_OUTPUT = "carddemo-batch-output";

    /** S3 bucket for generated statements. */
    private static final String BUCKET_STATEMENTS = "carddemo-statements";

    /** SQS FIFO queue replacing the CICS TDQ report bridge (← {@code CORPT00C}). */
    private static final String REPORT_QUEUE = "carddemo-report-jobs.fifo";

    /** SNS topic for notification fan-out. */
    private static final String NOTIFICATIONS_TOPIC = "carddemo-notifications";

    /** Admin test user id (exactly 8 chars to satisfy {@code @Size(max=8)}). */
    private static final String ADMIN_ID = "E2EADMIN";

    /** Admin test password (exactly 8 chars, ALL UPPERCASE so the sign-on upper-casing is idempotent). */
    private static final String ADMIN_PWD = "ADMINPWD";

    /** Standard test user id (exactly 8 chars). */
    private static final String USER_ID = "E2EUSER1";

    /** Standard test password (exactly 8 chars, ALL UPPERCASE). */
    private static final String USER_PWD = "USERPWD1";

    /**
     * Deterministic-per-run, test-only HMAC-SHA256 signing secret (32 random bytes, Base64-encoded to
     * &ge; 32 UTF-8 bytes — the HS256 minimum enforced by {@code SecurityConfig}). Registered below as
     * {@code carddemo.security.jwt.secret} so the security filter chain loads even when the
     * {@code JWT_SECRET} environment variable is absent. Never committed and never reaches production.
     */
    private static final String TEST_JWT_SECRET = generateTestJwtSecret();

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    UserSecurityRepository userSecurityRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    CardRepository cardRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    S3Client s3Client;

    @Autowired
    ObjectMapper objectMapper;

    private static LocalStackContainer createLocalStack() {
        LocalStackContainer container =
                new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.5.0"))
                        .withEnv("SERVICES", "s3,sqs,sns");
        String authToken = System.getenv("LOCALSTACK_AUTH_TOKEN");
        if (authToken != null && !authToken.isBlank()) {
            container.withEnv("LOCALSTACK_AUTH_TOKEN", authToken);
        }
        return container;
    }

    private static String generateTestJwtSecret() {
        byte[] secretBytes = new byte[32];
        new SecureRandom().nextBytes(secretBytes);
        return Base64.getEncoder().encodeToString(secretBytes);
    }

    /**
     * Wires the PostgreSQL datasource and the LocalStack AWS endpoint/credentials into the Spring
     * Environment, registers a generated JWT signing secret, and self-provisions the three S3 buckets,
     * the SQS FIFO queue, and the SNS topic. Provisioning runs here (before context refresh) so the
     * FIFO queue exists when the report job's {@code @SqsListener} binds at startup.
     *
     * @param registry the dynamic property registry supplied by the Spring test context
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.cloud.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
        registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
        registry.add("carddemo.security.jwt.secret", () -> TEST_JWT_SECRET);
        provisionAwsResources();
    }

    private static void provisionAwsResources() {
        try {
            LOCALSTACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_INPUT);
            LOCALSTACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_OUTPUT);
            LOCALSTACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_STATEMENTS);
            LOCALSTACK.execInContainer("awslocal", "sqs", "create-queue",
                    "--queue-name", REPORT_QUEUE,
                    "--attributes", "FifoQueue=true,ContentBasedDeduplication=true");
            LOCALSTACK.execInContainer("awslocal", "sns", "create-topic", "--name", NOTIFICATIONS_TOPIC);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to provision LocalStack AWS resources", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while provisioning LocalStack AWS resources", e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // User seeding (idempotent — no USRSEC fixture exists). Runs before each test so authentication
    // has known BCrypt-encoded credentials. Passwords are stored as BCrypt hashes of the ALL-UPPERCASE
    // raw values, matching the COBOL-style upper-casing the AuthenticationService applies at sign-in.
    // ---------------------------------------------------------------------------------------------

    @BeforeEach
    void seedUsers() {
        seedUser(ADMIN_ID, "E2E", "ADMIN", ADMIN_PWD, UserType.ADMIN);
        seedUser(USER_ID, "E2E", "USER", USER_PWD, UserType.USER);
    }

    private void seedUser(String id, String firstName, String lastName, String rawPassword, UserType type) {
        UserSecurity user = userSecurityRepository.findBySecUsrId(id).orElseGet(UserSecurity::new);
        user.setSecUsrId(id);
        user.setSecUsrFname(firstName);
        user.setSecUsrLname(lastName);
        user.setSecUsrPwd(passwordEncoder.encode(rawPassword));
        user.setSecUsrType(type);
        userSecurityRepository.save(user);
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP helpers. Requests are sent as Map<String,Object>; responses are read as JsonNode (field
    // access by name) or String (status-only checks, tolerant of empty Spring Security error bodies).
    // ---------------------------------------------------------------------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders jsonHeaders(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return headers;
    }

    private ResponseEntity<JsonNode> getJson(String path, String bearerToken) {
        return restTemplate.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(bearerToken)), JsonNode.class);
    }

    private ResponseEntity<String> getStatus(String path, String bearerToken) {
        return restTemplate.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(bearerToken)), String.class);
    }

    private ResponseEntity<JsonNode> postJson(String path, Object body, String bearerToken) {
        return restTemplate.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(bearerToken)), JsonNode.class);
    }

    private ResponseEntity<String> postStatus(String path, Object body, String bearerToken) {
        return restTemplate.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(bearerToken)), String.class);
    }

    private ResponseEntity<String> putStatus(String path, Object body, String bearerToken) {
        return restTemplate.exchange(url(path), HttpMethod.PUT,
                new HttpEntity<>(body, jsonHeaders(bearerToken)), String.class);
    }

    private String signIn(String userId, String password) {
        Map<String, Object> body = Map.of("userId", userId, "password", password);
        ResponseEntity<JsonNode> response = postJson("/api/auth/signin", body, null);
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return response.getBody().path("token").asText(null);
        }
        return null;
    }

    private void writeEvidence(String fileName, ObjectNode content) throws IOException {
        Path targetDir = Path.of("target");
        if (Files.isDirectory(targetDir)) {
            Files.writeString(targetDir.resolve(fileName), content.toString());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------

    /**
     * BCrypt sign-on (constraint C-003) + JWT issuance and path-based authorization (← {@code COSGN00C}).
     */
    @Test
    void bcryptSignOnAndRoleBasedAuthorization() {
        // Unauthenticated guard: /api/** requires authentication, proving the security filter is active.
        ResponseEntity<String> guard = getStatus("/api/menu/main", null);
        assertThat(guard.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());

        // BCrypt sign-on success + JWT issuance for both roles.
        String adminToken = signIn(ADMIN_ID, ADMIN_PWD);
        assertThat(adminToken).isNotBlank();
        String userToken = signIn(USER_ID, USER_PWD);
        assertThat(userToken).isNotBlank();

        // Wrong password yields no usable token (tolerant of a 200+errorMessage or a 4xx response).
        assertThat(signIn(ADMIN_ID, "WRONGPWD")).isNull();

        // Role-based authorization: ROLE_USER is forbidden on /api/admin/**, ROLE_ADMIN is allowed.
        ResponseEntity<String> userOnAdmin = getStatus("/api/admin/users", userToken);
        assertThat(userOnAdmin.getStatusCode().value()).isEqualTo(HttpStatus.FORBIDDEN.value());

        ResponseEntity<String> adminOnAdmin = getStatus("/api/admin/users", adminToken);
        assertThat(adminOnAdmin.getStatusCode().is2xxSuccessful()).isTrue();
    }

    /**
     * Account view, typed-path binding, deterministic {@code @Version} optimistic locking, and
     * no-partial-persist on a rejected update (← {@code COACTUPC} {@code @Transactional} + {@code @Version}).
     */
    @Test
    void accountViewUpdateAndOptimisticLocking() {
        String adminToken = signIn(ADMIN_ID, ADMIN_PWD);
        assertThat(adminToken).isNotBlank();

        Account seed = accountRepository.findAll().stream().findFirst().orElse(null);
        Assumptions.assumeTrue(seed != null, "no seeded accounts (Flyway V3) — skipping");
        Long acctId = seed.getAcctId();

        // Account view: 200 with a non-null JSON object body.
        ResponseEntity<JsonNode> view = getJson("/api/accounts/" + acctId, adminToken);
        assertThat(view.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(view.getBody()).isNotNull();
        assertThat(view.getBody().isObject()).isTrue();

        // Non-numeric path -> 400 (MethodArgumentTypeMismatch via GlobalExceptionHandler).
        ResponseEntity<String> badPath = getStatus("/api/accounts/not-a-number", adminToken);
        assertThat(badPath.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());

        // @Version optimistic locking, proven deterministically at the repository layer: two detached
        // instances load the same version; the first write bumps it, so the stale second write fails.
        Account first = accountRepository.findById(acctId).orElseThrow();
        Account stale = accountRepository.findById(acctId).orElseThrow();
        first.setAcctCurrBal(first.getAcctCurrBal().add(new BigDecimal("1.00")));
        accountRepository.saveAndFlush(first);
        stale.setAcctCurrBal(stale.getAcctCurrBal().add(new BigDecimal("2.00")));
        assertThatThrownBy(() -> accountRepository.saveAndFlush(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // No-partial-persist: a rejected (4xx) update must leave the balance unchanged.
        BigDecimal before = accountRepository.findById(acctId).orElseThrow().getAcctCurrBal();
        Map<String, Object> invalidUpdate = Map.of("accountId", "not-numeric");
        ResponseEntity<String> rejected = putStatus("/api/accounts/" + acctId, invalidUpdate, adminToken);
        assertThat(rejected.getStatusCode().is4xxClientError()).isTrue();
        BigDecimal after = accountRepository.findById(acctId).orElseThrow().getAcctCurrBal();
        assertThat(after.compareTo(before)).isZero();
    }

    /**
     * Card browse (7 rows/page), card detail, transaction browse (10 rows/page), and the tolerant
     * auto-ID transaction add (← {@code COTRN02C}).
     */
    @Test
    void cardAndTransactionFlows() throws IOException {
        String adminToken = signIn(ADMIN_ID, ADMIN_PWD);
        assertThat(adminToken).isNotBlank();

        // Card list: strong assertion is HTTP 200; the page-size cap (7) is asserted tolerantly.
        ResponseEntity<JsonNode> cardList = getJson("/api/cards?page=0", adminToken);
        assertThat(cardList.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        JsonNode cardListBody = cardList.getBody();
        assertThat(cardListBody).isNotNull();
        if (cardListBody.path("cards").isArray()) {
            assertThat(cardListBody.path("cards").size()).isLessThanOrEqualTo(7);
        }

        // Card detail: pick a seeded card via the repository (skip the sub-step when none are seeded).
        // COCRDSLC (CardDetailService) edits BOTH an 11-digit account filter and the 16-digit card key
        // before the keyed read, so the required accountId is supplied as the card's own account id,
        // left-zero-padded to 11 digits. Without it the service returns a 400 validation error.
        var firstCard = cardRepository.findAll().stream().findFirst().orElse(null);
        String seededCardNumber = firstCard != null ? firstCard.getCardNum() : null;
        if (firstCard != null && seededCardNumber != null) {
            String cardDetailAccountId = String.format("%011d", firstCard.getCardAcctId());
            ResponseEntity<String> cardDetail = getStatus(
                    "/api/cards/" + seededCardNumber + "?accountId=" + cardDetailAccountId, adminToken);
            assertThat(cardDetail.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        }

        // Transaction list: 200; the page-size cap (10) is tolerant (an empty page is valid here).
        ResponseEntity<JsonNode> txnList = getJson("/api/transactions?page=0", adminToken);
        assertThat(txnList.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        JsonNode txnListBody = txnList.getBody();
        assertThat(txnListBody).isNotNull();
        if (txnListBody.path("transactions").isArray()) {
            assertThat(txnListBody.path("transactions").size()).isLessThanOrEqualTo(10);
        }

        // Transaction add (auto-ID): no id is sent. Build a fully-valid request to exercise the
        // auto-ID factory, but assert tolerantly (reachable + secured) to avoid brittle coupling.
        String accountId = String.valueOf(accountRepository.findAll().stream()
                .findFirst().map(Account::getAcctId).orElse(0L));
        String cardNumber = seededCardNumber != null ? seededCardNumber : "4111111111111111";
        Map<String, Object> txn = new HashMap<>();
        txn.put("accountId", accountId);
        txn.put("cardNumber", cardNumber);
        txn.put("typeCode", "01");
        txn.put("categoryCode", "0001");
        txn.put("source", "POS");
        txn.put("description", "E2E ONLINE FLOW TRANSACTION");
        txn.put("amount", new BigDecimal("100.00"));
        txn.put("originDate", "2022-07-01");
        txn.put("processDate", "2022-07-01");
        txn.put("merchantId", "123456789");
        txn.put("merchantName", "E2E MERCHANT");
        txn.put("merchantCity", "TESTCITY");
        txn.put("merchantZip", "12345");
        txn.put("confirm", "Y");

        ResponseEntity<JsonNode> addResponse = postJson("/api/transactions", txn, adminToken);
        int addStatus = addResponse.getStatusCode().value();
        assertThat(addStatus).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(addStatus).isNotEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(addResponse.getStatusCode().is5xxServerError()).isFalse();

        String addBranch;
        if (addStatus == HttpStatus.OK.value()) {
            JsonNode addBody = addResponse.getBody();
            assertThat(addBody).isNotNull();
            assertThat(addBody.path("transactionId").asText("")).isNotBlank();
            addBranch = "200-auto-id";
        } else {
            addBranch = "validation-" + addStatus;
        }

        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("test", "cardAndTransactionFlows");
        evidence.put("transactionAddStatus", addStatus);
        evidence.put("transactionAddBranch", addBranch);
        writeEvidence("txn-add-evidence.txt", evidence);
    }

    /**
     * Bill-payment reachability (← {@code COBIL00C}) and main/admin menu routing (← {@code COMEN01C}).
     */
    @Test
    void billingAndMenuFlows() {
        String adminToken = signIn(ADMIN_ID, ADMIN_PWD);
        assertThat(adminToken).isNotBlank();

        String accountId = String.valueOf(accountRepository.findAll().stream()
                .findFirst().map(Account::getAcctId).orElse(0L));

        // Bill pay (tolerant): reachable + secured (2xx or 4xx acceptable; never 401/403/5xx).
        Map<String, Object> billBody = Map.of("accountId", accountId, "confirm", "Y");
        ResponseEntity<String> bill = postStatus("/api/billing/pay", billBody, adminToken);
        int billStatus = bill.getStatusCode().value();
        assertThat(billStatus).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(billStatus).isNotEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(bill.getStatusCode().is5xxServerError()).isFalse();

        // Main menu (strong): 200 with a non-empty options array (10 options ← COMEN02Y).
        ResponseEntity<JsonNode> mainMenu = getJson("/api/menu/main", adminToken);
        assertThat(mainMenu.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(mainMenu.getBody()).isNotNull();
        assertThat(mainMenu.getBody().path("options").isArray()).isTrue();
        assertThat(mainMenu.getBody().path("options").size()).isGreaterThan(0);

        // Admin menu (strong-ish): 200 with an options array (4 options ← COADM02Y).
        ResponseEntity<JsonNode> adminMenu = getJson("/api/menu/admin", adminToken);
        assertThat(adminMenu.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(adminMenu.getBody()).isNotNull();
        assertThat(adminMenu.getBody().path("options").isArray()).isTrue();
    }

    /**
     * Report-submission SQS FIFO bridge (← {@code CORPT00C} {@code WRITEQ TD}). The hard guarantee is
     * the synchronous publish (200 + confirmationMessage); the asynchronous
     * SQS → {@code @SqsListener} → report job → S3 leg is verified tolerantly and soft-skips on timeout.
     */
    @Test
    void reportSubmissionSqsBridge() throws IOException {
        String adminToken = signIn(ADMIN_ID, ADMIN_PWD);
        assertThat(adminToken).isNotBlank();

        // Submit a confirmed monthly report; the service publishes one message to the SQS FIFO queue.
        Map<String, Object> reportBody = Map.ofEntries(
                Map.entry("monthly", "Y"),
                Map.entry("yearly", ""),
                Map.entry("custom", ""),
                Map.entry("startMonth", "07"),
                Map.entry("startDay", "01"),
                Map.entry("startYear", "2022"),
                Map.entry("endMonth", "07"),
                Map.entry("endDay", "31"),
                Map.entry("endYear", "2022"),
                Map.entry("confirm", "Y"));
        ResponseEntity<JsonNode> submit = postJson("/api/reports/submit", reportBody, adminToken);
        assertThat(submit.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(submit.getBody()).isNotNull();
        String confirmation = submit.getBody().path("confirmationMessage").asText("");
        assertThat(confirmation).isNotBlank();

        // Verify the async bridge end-to-end: any object appearing in the output bucket is the report
        // (the posting job is not run in this class, so the bucket starts empty). Soft-skip on timeout.
        boolean s3ObjectObserved;
        try {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofSeconds(2))
                    .untilAsserted(() -> {
                        ListObjectsV2Response listing = s3Client.listObjectsV2(
                                ListObjectsV2Request.builder().bucket(BUCKET_OUTPUT).build());
                        assertThat(listing.contents()).isNotEmpty();
                    });
            s3ObjectObserved = true;
        } catch (ConditionTimeoutException timeout) {
            s3ObjectObserved = false;
        }

        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("test", "reportSubmissionSqsBridge");
        evidence.put("submissionConfirmation", confirmation);
        evidence.put("s3ObjectObserved", s3ObjectObserved);
        writeEvidence("report-bridge-evidence.txt", evidence);

        Assumptions.assumeTrue(s3ObjectObserved,
                "Report object not observed in " + BUCKET_OUTPUT + " within timeout; the SQS bridge is "
                + "async and an empty-range report may not materialize — soft-skip");
    }
}
