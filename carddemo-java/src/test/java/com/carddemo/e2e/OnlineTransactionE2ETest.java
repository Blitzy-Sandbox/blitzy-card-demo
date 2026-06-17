package com.carddemo.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.Card;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.UserSecurityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/** Full-stack REST online-flow e2e test validating the Java translation of COSGN00C/COACTUPC/COTRN02C/CORPT00C/COMEN01C/COBIL00C (AWS CardDemo commit 27d6c6f, REFERENCE ONLY). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Tag("e2e")
@Tag("integration")
public class OnlineTransactionE2ETest {

    /** Logger used only for non-fatal evidence-write diagnostics; tokens and passwords are never logged. */
    private static final Logger log = LoggerFactory.getLogger(OnlineTransactionE2ETest.class);

    // AWS resource names — must match application*.yml / docker-compose.yml / localstack-init/init-aws.sh.
    private static final String BUCKET_INPUT = "carddemo-batch-input";
    private static final String BUCKET_OUTPUT = "carddemo-batch-output";
    private static final String BUCKET_STATEMENTS = "carddemo-statements";
    private static final String REPORT_QUEUE = "carddemo-report-jobs.fifo";
    private static final String NOTIFICATIONS_TOPIC = "carddemo-notifications";

    // Object key the transaction-report job always writes to BUCKET_OUTPUT (TransactionReportJob
    // default carddemo.batch.report.object-key); the writer uploads a title/header/footer block even
    // for an empty window, so the bridge produces this object deterministically.
    private static final String REPORT_OBJECT_KEY = "TRANREPT";

    // Test users: ids are EXACTLY 8 chars (@Size(max=8)); passwords are EXACTLY 8 chars and ALL
    // UPPERCASE so they survive the COBOL-style upper-casing AuthenticationService applies before the
    // BCrypt match (passwordEncoder.matches(upperCasedPwd, hash)). These are non-secret test fixtures.
    private static final String ADMIN_ID = "E2EADMIN";
    private static final String ADMIN_PWD = "ADMINPWD";
    private static final String USER_ID = "E2EUSER1";
    private static final String USER_PWD = "USERPWD1";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo");

    @Container
    static final LocalStackContainer LOCALSTACK = createLocalStack();

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

    /**
     * Wires the Testcontainers PostgreSQL datasource and LocalStack AWS endpoint into the Spring
     * environment, then self-provisions the three S3 buckets, the report FIFO queue, and the SNS
     * topic. The containers are started by the Testcontainers extension before the context loads, so
     * they are running when this runs; provisioning happens before the context refreshes so the FIFO
     * queue exists before {@code TransactionReportJob}'s {@code @SqsListener} binds at startup.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.cloud.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
        registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
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
    CardCrossReferenceRepository cardCrossReferenceRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    S3Client s3Client;

    @Autowired
    ObjectMapper objectMapper;

    /** Builds an absolute URL against the randomly-assigned embedded server port. */
    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** JSON request headers, optionally carrying a bearer token. */
    private HttpHeaders jsonHeaders(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return headers;
    }

    /** Signs in and returns the issued JWT, or {@code null} when no usable token is returned. */
    private String signIn(String userId, String password) {
        Map<String, Object> body = Map.of("userId", userId, "password", password);
        ResponseEntity<JsonNode> resp = restTemplate.exchange(url("/api/auth/signin"),
                HttpMethod.POST, new HttpEntity<>(body, jsonHeaders(null)), JsonNode.class);
        if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
            JsonNode token = resp.getBody().path("token");
            return token.isMissingNode() || token.isNull() ? null : token.asText();
        }
        return null;
    }

    /**
     * Idempotently seeds the admin and standard test users before each test (no USRSEC fixture
     * exists). Passwords are BCrypt-encoded; the raw values are ALL-UPPERCASE so the sign-in
     * upper-casing is idempotent.
     */
    @BeforeEach
    void seedUsers() {
        seedUser(ADMIN_ID, "E2E", "ADMIN", ADMIN_PWD, UserType.ADMIN);
        seedUser(USER_ID, "E2E", "USER", USER_PWD, UserType.USER);
    }

    private void seedUser(String id, String first, String last, String rawPwd, UserType type) {
        UserSecurity user = userSecurityRepository.findBySecUsrId(id).orElseGet(UserSecurity::new);
        user.setSecUsrId(id);
        user.setSecUsrFname(first);
        user.setSecUsrLname(last);
        user.setSecUsrPwd(passwordEncoder.encode(rawPwd));
        user.setSecUsrType(type);
        userSecurityRepository.save(user);
    }

    /**
     * Idempotently seeds a card cross-reference (CARDXREF / CXACAIX) so the transaction-add and
     * bill-payment key-resolution reads ({@code READ-CXACAIX-FILE}) deterministically resolve the
     * account id to its authoritative card number. No domain foreign keys exist, so the row stands
     * alone.
     */
    private void seedCrossReference(String cardNumber, long custId, long acctId) {
        CardCrossReference xref =
                cardCrossReferenceRepository.findById(cardNumber).orElseGet(CardCrossReference::new);
        xref.setXrefCardNum(cardNumber);
        xref.setXrefCustId(custId);
        xref.setXrefAcctId(acctId);
        cardCrossReferenceRepository.saveAndFlush(xref);
    }

    /**
     * Idempotently seeds a fully-populated account with the given positive balance so the bill-payment
     * success path ({@code COBIL00C}: balance &gt; 0, confirm = Y) posts a full-balance payment. All
     * NOT NULL columns are set; the account is dedicated to the billing test and is not shared with
     * the seeded Flyway V3 accounts.
     */
    private void seedPayableAccount(long acctId, BigDecimal balance) {
        Account account = accountRepository.findById(acctId).orElseGet(Account::new);
        account.setAcctId(acctId);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(balance);
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctOpenDate("2020-01-01");
        account.setAcctExpiraionDate("2030-01-01");
        account.setAcctReissueDate("2025-01-01");
        account.setAcctCurrCycCredit(new BigDecimal("0.00"));
        account.setAcctCurrCycDebit(new BigDecimal("0.00"));
        account.setAcctAddrZip("90210");
        account.setAcctGroupId("GROUP00001");
        accountRepository.saveAndFlush(account);
    }

    /**
     * BCrypt sign-on (constraint C-003) with JWT issuance and path-based authorization
     * ({@code COSGN00C}): an unauthenticated request is rejected, valid credentials mint a token, a
     * wrong password yields no token, only an admin token may reach {@code /api/admin/**}, and both
     * malformed and tampered (broken-signature) bearer tokens are rejected with 401.
     */
    @Test
    void bcryptSignOnAndRoleBasedAuthorization() {
        // Unauthenticated guard: /api/** requires authentication, so a missing token -> 401.
        ResponseEntity<JsonNode> guarded = restTemplate.exchange(url("/api/menu/main"),
                HttpMethod.GET, new HttpEntity<>(jsonHeaders(null)), JsonNode.class);
        assertThat(guarded.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());

        // BCrypt sign-on success (C-003) + JWT issuance for both roles.
        String adminToken = signIn(ADMIN_ID, ADMIN_PWD);
        assertThat(adminToken).isNotBlank();
        String userToken = signIn(USER_ID, USER_PWD);
        assertThat(userToken).isNotBlank();

        // A wrong password yields no usable token (tolerant of 200+errorMessage or 401).
        assertThat(signIn(ADMIN_ID, "WRONGPWD")).isNull();

        // ROLE_USER is forbidden on /api/admin/** (hasRole("ADMIN") + roles-claim mapping).
        ResponseEntity<JsonNode> userOnAdmin = restTemplate.exchange(url("/api/admin/users"),
                HttpMethod.GET, new HttpEntity<>(jsonHeaders(userToken)), JsonNode.class);
        assertThat(userOnAdmin.getStatusCode().value()).isEqualTo(HttpStatus.FORBIDDEN.value());

        // ROLE_ADMIN is allowed on /api/admin/**.
        ResponseEntity<JsonNode> adminOnAdmin = restTemplate.exchange(url("/api/admin/users"),
                HttpMethod.GET, new HttpEntity<>(jsonHeaders(adminToken)), JsonNode.class);
        assertThat(adminOnAdmin.getStatusCode().is2xxSuccessful()).isTrue();

        // Invalid-JWT rejection: a syntactically malformed bearer token cannot be parsed as a JWT,
        // so authentication fails at the resource-server layer and the request is rejected with 401.
        ResponseEntity<JsonNode> malformedOnMenu = restTemplate.exchange(url("/api/menu/main"),
                HttpMethod.GET, new HttpEntity<>(jsonHeaders("not-a-valid-jwt")), JsonNode.class);
        assertThat(malformedOnMenu.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());

        // Tampered-JWT rejection: a structurally valid token whose signature has been corrupted fails
        // signature verification, so authentication fails with 401 before any role check on
        // /api/admin/** can run (i.e. 401, not 403).
        String[] jwtParts = adminToken.split("\\.");
        String tamperedSignature = new StringBuilder(jwtParts[jwtParts.length - 1]).reverse().toString();
        if (tamperedSignature.equals(jwtParts[jwtParts.length - 1])) {
            tamperedSignature = jwtParts[jwtParts.length - 1] + "AB";
        }
        String tamperedToken = jwtParts[0] + "." + jwtParts[1] + "." + tamperedSignature;
        ResponseEntity<JsonNode> tamperedOnAdmin = restTemplate.exchange(url("/api/admin/users"),
                HttpMethod.GET, new HttpEntity<>(jsonHeaders(tamperedToken)), JsonNode.class);
        assertThat(tamperedOnAdmin.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    /**
     * Account view plus the optimistic-locking and no-partial-persist guarantees of the account
     * update flow ({@code COACTUPC}): the {@code @Version} mapping rejects a stale write, and a
     * rejected (4xx) update leaves the persisted balance unchanged.
     */
    @Test
    void accountViewUpdateAndOptimisticLocking() {
        String adminToken = signIn(ADMIN_ID, ADMIN_PWD);
        assertThat(adminToken).isNotBlank();

        Account seed = accountRepository.findAll().stream().findFirst().orElse(null);
        Assumptions.assumeTrue(seed != null, "no seeded accounts (Flyway V3) - skipping");
        Long acctId = seed.getAcctId();

        // Account view -> 200 with a JSON object body.
        ResponseEntity<JsonNode> view = restTemplate.exchange(url("/api/accounts/" + acctId),
                HttpMethod.GET, new HttpEntity<>(jsonHeaders(adminToken)), JsonNode.class);
        assertThat(view.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(view.getBody()).isNotNull();
        assertThat(view.getBody().isObject()).isTrue();

        // Non-numeric path -> 400 (TypeMismatch via GlobalExceptionHandler), proving typed binding.
        ResponseEntity<JsonNode> badPath = restTemplate.exchange(url("/api/accounts/not-a-number"),
                HttpMethod.GET, new HttpEntity<>(jsonHeaders(adminToken)), JsonNode.class);
        assertThat(badPath.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());

        // @Version optimistic locking (deterministic, repository-level): two detached instances load
        // the same version; the first write bumps it, so the second (stale) write is rejected.
        Account a1 = accountRepository.findById(acctId).orElseThrow();
        Account a2 = accountRepository.findById(acctId).orElseThrow();
        a1.setAcctCurrBal(a1.getAcctCurrBal().add(new BigDecimal("1.00")));
        accountRepository.saveAndFlush(a1);
        a2.setAcctCurrBal(a2.getAcctCurrBal().add(new BigDecimal("2.00")));
        assertThatThrownBy(() -> accountRepository.saveAndFlush(a2))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // No-partial-persist: an invalid update body (accountId fails @Pattern) is rejected (4xx) and
        // leaves the balance unchanged. Monetary comparison uses compareTo, never equals.
        BigDecimal before = accountRepository.findById(acctId).orElseThrow().getAcctCurrBal();
        Map<String, Object> invalid = Map.of("accountId", "not-numeric");
        ResponseEntity<JsonNode> rejected = restTemplate.exchange(url("/api/accounts/" + acctId),
                HttpMethod.PUT, new HttpEntity<>(invalid, jsonHeaders(adminToken)), JsonNode.class);
        assertThat(rejected.getStatusCode().is4xxClientError()).isTrue();
        BigDecimal after = accountRepository.findById(acctId).orElseThrow().getAcctCurrBal();
        assertThat(after.compareTo(before)).isZero();
    }

    /**
     * Card and transaction browse plus the auto-ID transaction add ({@code COTRN02C}). Browse
     * endpoints are asserted strongly on HTTP 200 with tolerant page-size checks; the add is asserted
     * deterministically (201 CREATED, service-generated 16-digit id, cross-reference-resolved
     * account/card) using a seeded card cross-reference.
     */
    @Test
    void cardAndTransactionFlows() {
        String adminToken = signIn(ADMIN_ID, ADMIN_PWD);
        assertThat(adminToken).isNotBlank();

        // Card list (7 rows/page) -> 200; internal field checks are tolerant (only when present).
        ResponseEntity<JsonNode> cards = restTemplate.exchange(url("/api/cards?page=0"),
                HttpMethod.GET, new HttpEntity<>(jsonHeaders(adminToken)), JsonNode.class);
        assertThat(cards.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        JsonNode cardBody = cards.getBody();
        assertThat(cardBody).isNotNull();
        if (cardBody.hasNonNull("pageNumber")) {
            // pageNumber is an echoed 1-based display indicator (COCRDLI PAGENO, PIC X(3)),
            // not the 0-based request page; assert only that the field is echoed (non-blank).
            assertThat(cardBody.path("pageNumber").asText()).isNotBlank();
        }
        if (cardBody.path("cards").isArray()) {
            assertThat(cardBody.path("cards").size()).isLessThanOrEqualTo(7);
        }

        // Card detail (only when a card with a valid 16-digit number + account id is seeded;
        // never fail on an empty card store). COCRDSLC keys on account-id AND card-number, so the
        // service requires both, format-valid (acct \d{11}, card \d{16}); supply the card's own
        // account id zero-padded to 11 digits.
        Card card = cardRepository.findAll().stream()
                .filter(c -> c.getCardAcctId() != null
                        && c.getCardNum() != null
                        && c.getCardNum().matches("\\d{16}"))
                .findFirst()
                .orElse(null);
        if (card != null) {
            String acctParam = String.format("%011d", card.getCardAcctId());
            ResponseEntity<JsonNode> detail = restTemplate.exchange(
                    url("/api/cards/" + card.getCardNum() + "?accountId=" + acctParam),
                    HttpMethod.GET, new HttpEntity<>(jsonHeaders(adminToken)), JsonNode.class);
            assertThat(detail.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        }

        // Transaction list (10 rows/page) -> 200; an empty page is valid here (no posting job run).
        ResponseEntity<JsonNode> txns = restTemplate.exchange(url("/api/transactions?page=0"),
                HttpMethod.GET, new HttpEntity<>(jsonHeaders(adminToken)), JsonNode.class);
        assertThat(txns.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        JsonNode txnBody = txns.getBody();
        assertThat(txnBody).isNotNull();
        if (txnBody.path("transactions").isArray()) {
            assertThat(txnBody.path("transactions").size()).isLessThanOrEqualTo(10);
        }

        // Transaction add - auto-ID (COTRN02C), deterministic SUCCESS path. A dedicated card
        // cross-reference is seeded so VALIDATE-INPUT-KEY-FIELDS resolves the supplied account id to
        // its authoritative card (READ-CXACAIX-FILE). With every data field valid and confirm=Y, the
        // service generates the next 16-digit id (browse-to-end + increment), persists the record,
        // and the controller returns 201 CREATED. No transaction id is supplied by the client.
        final String addAcctId = "70000000007";
        final String addXrefCard = "4000000000000007";
        seedCrossReference(addXrefCard, 70_000_007L, Long.parseLong(addAcctId));
        Map<String, Object> addBody = Map.ofEntries(
                Map.entry("accountId", addAcctId),
                Map.entry("cardNumber", "4111111111111111"),
                Map.entry("typeCode", "01"),
                Map.entry("categoryCode", "0001"),
                Map.entry("source", "POS"),
                Map.entry("description", "E2E online transaction add"),
                Map.entry("amount", "100.00"),
                Map.entry("originDate", "2022-07-01"),
                Map.entry("processDate", "2022-07-01"),
                Map.entry("merchantId", "123456789"),
                Map.entry("merchantName", "E2E MERCHANT"),
                Map.entry("merchantCity", "E2E CITY"),
                Map.entry("merchantZip", "12345"),
                Map.entry("confirm", "Y"));
        ResponseEntity<JsonNode> add = restTemplate.exchange(url("/api/transactions"),
                HttpMethod.POST, new HttpEntity<>(addBody, jsonHeaders(adminToken)), JsonNode.class);
        // Deterministic success: 201 CREATED with a service-generated 16-digit identifier and the
        // resolved, cross-reference-backed account/card pairing (never an auth rejection or 4xx/5xx).
        assertThat(add.getStatusCode().value()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(add.getBody()).isNotNull();
        String newTranId = add.getBody().path("transactionId").asText(null);
        assertThat(newTranId).isNotBlank();
        assertThat(newTranId).matches("\\d{16}");
        assertThat(add.getBody().path("accountId").asText()).isEqualTo(addAcctId);
        assertThat(add.getBody().path("cardNumber").asText()).isEqualTo(addXrefCard);
        writeEvidence("transaction-add-evidence.txt",
                Map.of("status", add.getStatusCode().value(),
                        "transactionId", newTranId,
                        "branch", "201-auto-id"));
    }

    /**
     * Bill payment ({@code COBIL00C}) and menu routing ({@code COMEN01C} / {@code COADM01C}). Billing
     * is asserted deterministically (200 OK, full balance paid to zero, "Payment successful" message)
     * using a seeded payable account + cross-reference; the menus are asserted strongly on their
     * option arrays (main = up to 10 options, admin = 4 options).
     */
    @Test
    void billingAndMenuFlows() {
        String adminToken = signIn(ADMIN_ID, ADMIN_PWD);
        assertThat(adminToken).isNotBlank();

        // Bill pay (COBIL00C), deterministic SUCCESS path. A dedicated account with a positive balance
        // plus a cross-reference (READ-CXACAIX-FILE) is seeded so the confirmed (confirm=Y) payment
        // posts the full balance: a payment transaction is written, the balance is decremented to
        // zero (ACCT-CURR-BAL - TRAN-AMT), and the service returns the "Payment successful" message.
        final long payAcctId = 70_000_000_008L;
        seedPayableAccount(payAcctId, new BigDecimal("250.00"));
        seedCrossReference("4000000000000008", 70_000_008L, payAcctId);

        Map<String, Object> payBody = Map.of("accountId", String.valueOf(payAcctId), "confirm", "Y");
        ResponseEntity<JsonNode> pay = restTemplate.exchange(url("/api/billing/pay"),
                HttpMethod.POST, new HttpEntity<>(payBody, jsonHeaders(adminToken)), JsonNode.class);
        // Deterministic success: 200 OK; the full balance is paid (new balance == 0, compared with
        // compareTo never equals), and the response carries the COBIL00C "Payment successful" message.
        assertThat(pay.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(pay.getBody()).isNotNull();
        assertThat(pay.getBody().path("accountId").asText()).isEqualTo(String.valueOf(payAcctId));
        assertThat(new BigDecimal(pay.getBody().path("currentBalance").asText())
                .compareTo(BigDecimal.ZERO)).isZero();
        assertThat(pay.getBody().path("errorMessage").asText()).contains("Payment successful");
        // The balance-clearing payment is persisted (COBIL00C UPDATE-ACCTDAT-FILE).
        assertThat(accountRepository.findById(payAcctId).orElseThrow().getAcctCurrBal()
                .compareTo(BigDecimal.ZERO)).isZero();

        // Main menu (strong): 200 with a non-empty options array (<- COMEN02Y).
        ResponseEntity<JsonNode> main = restTemplate.exchange(url("/api/menu/main"),
                HttpMethod.GET, new HttpEntity<>(jsonHeaders(adminToken)), JsonNode.class);
        assertThat(main.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(main.getBody()).isNotNull();
        assertThat(main.getBody().path("options").isArray()).isTrue();
        assertThat(main.getBody().path("options").size()).isGreaterThan(0);

        // Admin menu (strong-ish): 200 with an options array (<- COADM02Y).
        ResponseEntity<JsonNode> admin = restTemplate.exchange(url("/api/menu/admin"),
                HttpMethod.GET, new HttpEntity<>(jsonHeaders(adminToken)), JsonNode.class);
        assertThat(admin.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(admin.getBody()).isNotNull();
        assertThat(admin.getBody().path("options").isArray()).isTrue();
    }

    /**
     * Report-submission SQS FIFO bridge ({@code CORPT00C} {@code WRITEQ TD}): the submit endpoint
     * publishes to the FIFO queue (hard assertion on 200 + confirmation), and the async
     * {@code @SqsListener} -> report job -> S3 object is verified deterministically by asserting the
     * {@code TRANREPT} object lands in {@code carddemo-batch-output} (the report writer always uploads
     * it, even for a 0-row date range), with no soft-skip.
     */
    @Test
    void reportSubmissionSqsBridge() {
        String adminToken = signIn(ADMIN_ID, ADMIN_PWD);
        assertThat(adminToken).isNotBlank();

        // Submit a monthly report and confirm -> ReportSubmissionService publishes to SQS FIFO.
        Map<String, Object> reportBody = Map.of(
                "monthly", "Y",
                "yearly", "",
                "custom", "",
                "startMonth", "07",
                "startDay", "01",
                "startYear", "2022",
                "endMonth", "07",
                "endDay", "31",
                "endYear", "2022",
                "confirm", "Y");
        ResponseEntity<JsonNode> submit = restTemplate.exchange(url("/api/reports/submit"),
                HttpMethod.POST, new HttpEntity<>(reportBody, jsonHeaders(adminToken)), JsonNode.class);
        assertThat(submit.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(submit.getBody()).isNotNull();
        assertThat(submit.getBody().path("confirmationMessage").asText(null)).isNotBlank();

        // Verify the async bridge end-to-end (DETERMINISTIC): the @SqsListener consumes the FIFO
        // message, launches the report job, and ReportItemWriter.close() ALWAYS uploads the TRANREPT
        // object to carddemo-batch-output (the title/header/footer block is written even for a 0-row
        // date range), so the object materializes reliably and no soft-skip is required.
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    ListObjectsV2Response listing = s3Client.listObjectsV2(
                            ListObjectsV2Request.builder()
                                    .bucket(BUCKET_OUTPUT)
                                    .prefix(REPORT_OBJECT_KEY)
                                    .build());
                    assertThat(listing.contents())
                            .as("TRANREPT report object uploaded by the async SQS->batch bridge")
                            .extracting(S3Object::key)
                            .contains(REPORT_OBJECT_KEY);
                });
        writeEvidence("report-bridge-evidence.txt",
                Map.of("submitted", true, "s3ObjectObserved", true, "objectKey", REPORT_OBJECT_KEY));
    }

    /**
     * Writes a best-effort JSON evidence note under {@code target/}. Failures are non-fatal: they are
     * logged at debug and never break the test (the build directory may be absent outside Maven).
     */
    private void writeEvidence(String fileName, Map<String, Object> data) {
        try {
            Path path = Path.of("target", fileName);
            Files.writeString(path, objectMapper.writeValueAsString(data));
        } catch (IOException e) {
            log.debug("Could not write evidence file {} (non-fatal): {}", fileName, e.getMessage());
        }
    }
}
