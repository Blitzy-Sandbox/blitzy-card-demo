/*
 ******************************************************************
 * Program     : OnlineTransactionE2ETest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 end-to-end test (Failsafe tier)
 * Function    : Drives all seventeen online REST operations through a real application context.
 * Source      : app/csd/CARDDEMO.CSD:306-480 @ 7756d89 (the eighteen CSD pairs; CDV1 is orphaned)
 * Source      : app/cbl/COSGN00C.cbl, app/cbl/COMEN01C.cbl, app/cbl/COADM01C.cbl
 * Source      : app/cbl/COACTVWC.cbl, app/cbl/COACTUPC.cbl
 * Source      : app/cbl/COCRDLIC.cbl, app/cbl/COCRDSLC.cbl, app/cbl/COCRDUPC.cbl
 * Source      : app/cbl/COTRN00C.cbl, app/cbl/COTRN01C.cbl, app/cbl/COTRN02C.cbl
 * Source      : app/cbl/COBIL00C.cbl, app/cbl/CORPT00C.cbl
 * Source      : app/cbl/COUSR00C.cbl, app/cbl/COUSR01C.cbl
 * Source      : app/cbl/COUSR02C.cbl, app/cbl/COUSR03C.cbl
 * Note        : COCRDSEC is Not available; no endpoint is invented for CDV1.
 ******************************************************************
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
 * language governing permissions and limitations under the License
 ******************************************************************
 */
package com.cardemo.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.controller.AccountController;
import com.cardemo.controller.AdminController;
import com.cardemo.controller.AuthController;
import com.cardemo.controller.BillingController;
import com.cardemo.controller.CardController;
import com.cardemo.controller.MenuController;
import com.cardemo.controller.ReportController;
import com.cardemo.controller.TransactionController;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.model.dto.BillPaymentRequest;
import com.cardemo.model.dto.PageResponse;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.security.JwtTokenProvider;
import com.cardemo.config.WebConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.micrometer.core.instrument.MeterRegistry;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * End-to-end verification of the CardDemo online migration, from sign-on through all seventeen operations
 * declared by {@code app/csd/CARDDEMO.CSD:306-480}. The tests bind to the authored controllers rather than
 * secondary prose and preserve the decisive source behaviours: the customer-lock fall-through at
 * {@code app/cbl/COACTUPC.cbl:2606-2615}, the compact snapshot date at
 * {@code app/cbl/COACTUPC.cbl:746-751} and its component comparison at {@code :4174-4179}, the sign-on folds
 * at {@code app/cbl/COSGN00C.cbl:132-136}, the full-month report range at
 * {@code app/cbl/CORPT00C.cbl:212-238}, the five-way payment gate at
 * {@code app/cbl/COBIL00C.cbl:173-191}, and the absent self-delete guard in
 * {@code app/cbl/COUSR03C.cbl}.
 *
 * <p><strong>Run and build.</strong> Run
 * {@code ./mvnw -B -ntp -Ddependency-check.skip=true -Dit.test=OnlineTransactionE2ETest verify}.
 * Failsafe, not Surefire, binds this exact {@code com/cardemo/e2e} tree during
 * {@code integration-test}/{@code verify} despite the {@code Test} suffix. A reachable Docker socket is a
 * hard prerequisite because the tier starts real PostgreSQL and LocalStack containers; skipping the class
 * when the socket is absent would create an untested pass.
 *
 * <p><strong>Configuration and defaults.</strong> The {@code test} profile runs against a digest-pinned
 * PostgreSQL 16 image and LocalStack 4.14.0, with connection properties obtained only from container
 * accessors. A primary UTC clock is fixed at {@code 2022-06-10T19:27:53Z}. The application remains
 * {@code STATELESS}; Actuator exposes only health, info and prometheus; BCrypt strength is 10; and the JWT
 * signing key is generated in memory for the run and registered in place of the production
 * {@code ${JWT_SIGNING_KEY}} property, which deliberately has no default.
 *
 * <p><strong>Failure modes and troubleshooting.</strong> A missing Docker socket blocks the gate. A
 * Testcontainers resolution error usually means the required 2.0.3 prefixed coordinates were replaced by
 * legacy bare module names or a second BOM. Compilation warnings are fatal under {@code -Xlint:all -Werror}.
 * Moving this class out of the exact e2e tree silently removes it from both test plugins. An account update
 * that fails on every request usually indicates a whole-string DOB comparison was introduced instead of the
 * live 1/6/9 versus compact-snapshot 1/5/7 component comparison.
 *
 * <p><strong>Evidence register.</strong> Inventing a CDV1 route or widening the five-claim JWT is Blocker;
 * remediation is to keep the orphan route absent and the claim set closed. Collapsing the five
 * account-update outcomes is High; remediation is the authored 423/409/412/500/428 mapping. The corrected
 * BMS total of 441 and the CR00-versus-CB00 invalid-confirmation attribution are Medium. The two-locator
 * transaction-page proof and the delete path's {@code 'Unable to Update User...'} wording are Low.
 * COCRDSEC source is <strong>Not available</strong>; an actual source member would be needed before a route
 * could be justified. {@link PageResponse} total counts are <strong>Not available</strong>; a source
 * counting operation and explicit response members would be needed before totals could be supplied.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(OnlineTransactionE2ETest.FixedClockConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("the seventeen-operation online CardDemo REST surface")
public class OnlineTransactionE2ETest {

    private static final String POSTGRES_IMAGE =
            "postgres@sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20";
    private static final String POSTGRES_INIT_ARGUMENTS = "--encoding=UTF8 --locale=C";
    private static final String LOCALSTACK_IMAGE = "localstack/localstack:4.14.0";
    private static final int SIGNING_KEY_BYTES = 48;
    private static final long CONTAINER_STARTUP_TIMEOUT_SECONDS = 300L;
    private static final Instant FIXED_INSTANT = Instant.parse("2022-06-10T19:27:53Z");
    /**
     * A ten-digit telephone number whose area code {@code 202} is listed in
     * {@code VALID-GENERAL-PURP-CODE} ({@code app/cpy/CSLKPCDY.cpy}), so
     * {@code 1260-EDIT-US-PHONE-NUM} accepts it. See {@link #accountUpdateBody(Map)} for why the seeded
     * value cannot be echoed.
     */
    private static final String EDIT_ACCEPTED_PHONE_1 = "2025550100";

    /** A second such number, area code {@code 212}, for {@code ACSPH2AI}. */
    private static final String EDIT_ACCEPTED_PHONE_2 = "2125550199";

    /**
     * A state and postal code whose two-character combination {@code NC27} is listed in
     * {@code VALID-US-STATE-ZIP-CD2-COMBO} ({@code app/cpy/CSLKPCDY.cpy}), so
     * {@code 1280-EDIT-US-STATE-ZIP-CD} accepts the pair. The seeded state of account one is retained;
     * only its postal code changes, because {@code NC12} is not a listed combination.
     */
    private static final String EDIT_ACCEPTED_STATE_CODE = "NC";

    /** The postal code paired with {@link #EDIT_ACCEPTED_STATE_CODE}. */
    private static final String EDIT_ACCEPTED_ZIP = "27601";

    private static final String REPORT_QUEUE_NAME = "carddemo-report-jobs.fifo";
    private static final String CORRELATION_VALUE = "e2e-online-surface";
    private static final String TRACE_PARENT_VALUE =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final Set<String> LEGACY_ONLY_FIELDS = Set.of(
            "fromTransactionId", "toTransactionId", "fromProgram", "toProgram", "programContext",
            "lastMap", "lastMapset");
    private static final String EPHEMERAL_SIGNING_KEY = generateEphemeralSigningKey();

    /**
     * The relational substrate reference is final and is the first of two narrowly scoped lifecycle
     * exceptions to the no-global-mutable-business-state rule. Spring needs it before the context exists;
     * tests never mutate it after the bounded static startup completes.
     */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withEnv("POSTGRES_INITDB_ARGS", POSTGRES_INIT_ARGUMENTS);

    /**
     * The emulator substrate, governed by the same lifecycle exception as {@link #POSTGRES}. All mutable
     * application and assertion state remains per test instance or per method.
     */
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
                    .withServices("s3", "sqs", "sns");

    static {
        try {
            Startables.deepStart(POSTGRES, LOCALSTACK)
                    .get(CONTAINER_STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final TimeoutException expired) {
            throw new IllegalStateException(
                    "The online end-to-end containers did not become ready within the bounded startup time.",
                    expired);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting the online end-to-end containers.",
                    interrupted);
        } catch (final ExecutionException | RuntimeException startupFailure) {
            throw new IllegalStateException(
                    "The online end-to-end containers could not start; a reachable container runtime is "
                            + "required and no in-memory substitute is valid.",
                    startupFailure);
        }
    }

    /**
     * Supplies every address and throwaway credential from the running containers and provisions the
     * emulator resources before the application context refreshes.
     *
     * @param registry Spring's dynamic property registry
     */
    @DynamicPropertySource
    static void registerContainerProperties(final DynamicPropertyRegistry registry) {
        final URI endpoint = LOCALSTACK.getEndpoint();
        final String region = LOCALSTACK.getRegion();
        final String accessKey = LOCALSTACK.getAccessKey();
        final String secretKey = LOCALSTACK.getSecretKey();
        final String inputBucket = "carddemo-batch-input";
        final String outputBucket = "carddemo-batch-output";
        final String statementsBucket = "carddemo-statements";
        final String notificationTopic = "carddemo-notifications";

        provisionCloudResources(endpoint, region, accessKey, secretKey, inputBucket, outputBucket,
                statementsBucket, REPORT_QUEUE_NAME, notificationTopic);

        registry.add("spring.cloud.aws.region.static", () -> region);
        registry.add("spring.cloud.aws.credentials.access-key", () -> accessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", () -> secretKey);
        registry.add("spring.cloud.aws.s3.endpoint", endpoint::toString);
        registry.add("spring.cloud.aws.sqs.endpoint", endpoint::toString);
        registry.add("spring.cloud.aws.sns.endpoint", endpoint::toString);
        registry.add("carddemo.aws.s3.batch-input-bucket", () -> inputBucket);
        registry.add("carddemo.aws.s3.batch-output-bucket", () -> outputBucket);
        registry.add("carddemo.aws.s3.statements-bucket", () -> statementsBucket);
        registry.add("carddemo.aws.sqs.report-queue", () -> REPORT_QUEUE_NAME);
        registry.add("carddemo.aws.sns.notification-topic", () -> notificationTopic);
        registry.add("carddemo.security.jwt.signing-key", () -> EPHEMERAL_SIGNING_KEY);
    }

    private static void provisionCloudResources(final URI endpoint, final String region,
            final String accessKey, final String secretKey, final String inputBucket,
            final String outputBucket, final String statementsBucket, final String reportQueue,
            final String notificationTopic) {

        final StaticCredentialsProvider credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));

        try (S3Client s3 = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .forcePathStyle(Boolean.TRUE)
                .build()) {
            createBucket(s3, inputBucket);
            createBucket(s3, outputBucket);
            createBucket(s3, statementsBucket);
            s3.putBucketVersioning(PutBucketVersioningRequest.builder()
                    .bucket(outputBucket)
                    .versioningConfiguration(VersioningConfiguration.builder()
                            .status(BucketVersioningStatus.ENABLED)
                            .build())
                    .build());
        }

        try (SqsClient sqs = SqsClient.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .build()) {
            sqs.createQueue(CreateQueueRequest.builder()
                    .queueName(reportQueue)
                    .attributesWithStrings(Map.of(
                            QueueAttributeName.FIFO_QUEUE.toString(), "true",
                            QueueAttributeName.CONTENT_BASED_DEDUPLICATION.toString(), "false"))
                    .build());
        }

        try (SnsClient sns = SnsClient.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .build()) {
            sns.createTopic(CreateTopicRequest.builder().name(notificationTopic).build());
        }
    }

    private static void createBucket(final S3Client s3, final String bucket) {
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    }

    private static String generateEphemeralSigningKey() {
        final byte[] material = new byte[SIGNING_KEY_BYTES];
        new SecureRandom().nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    private static SqsClient queueClient() {
        return SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserSecurityRepository userSecurityRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private Clock clock;

    @Autowired
    private TransactionController transactionController;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @LocalServerPort
    private int port;

    private TestIdentity administrator;
    private TestIdentity standardUser;

    /**
     * Creates the two runtime-only principals used by the surface tests.
     */
    @BeforeAll
    void provisionPrincipals() {
        this.administrator = newIdentity(UserType.ADMIN);
        this.standardUser = newIdentity(UserType.USER);
        saveIdentity(this.administrator, UserType.ADMIN);
        saveIdentity(this.standardUser, UserType.USER);
        clearTransactions();
        drainReportQueue();
    }

    /**
     * Removes every row and message created by this class.
     */
    @AfterAll
    void removeCreatedResources() {
        clearTransactions();
        deleteIdentity(this.administrator);
        deleteIdentity(this.standardUser);
        drainReportQueue();
    }

    private TestIdentity newIdentity(final UserType userType) {
        Objects.requireNonNull(userType, "userType must not be null");
        String userId;
        do {
            userId = randomUppercaseText(8);
        } while (this.userSecurityRepository.existsById(userId));
        return new TestIdentity(userId, randomMixedCaseCredential());
    }

    private void saveIdentity(final TestIdentity identity, final UserType userType) {
        final String digest = this.passwordEncoder.encode(identity.credential().toUpperCase(Locale.ROOT));
        this.userSecurityRepository.saveAndFlush(new UserSecurity(
                identity.userId(), "E2E", userType == UserType.ADMIN ? "ADMIN" : "USER", digest, userType));
    }

    private void deleteIdentity(final TestIdentity identity) {
        if (identity != null && this.userSecurityRepository.existsById(identity.userId())) {
            this.userSecurityRepository.deleteById(identity.userId());
            this.userSecurityRepository.flush();
        }
    }

    private static String randomUppercaseText(final int length) {
        final SecureRandom random = new SecureRandom();
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        final StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    private static String randomMixedCaseCredential() {
        final SecureRandom random = new SecureRandom();
        final String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        final String lower = upper.toLowerCase(Locale.ROOT);
        final String alphabet = upper + lower;
        final StringBuilder value = new StringBuilder(8);
        value.append(lower.charAt(random.nextInt(lower.length())));
        value.append(upper.charAt(random.nextInt(upper.length())));
        while (value.length() < 8) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    private AuthenticatedSession signOn(final TestIdentity identity) {
        final ObjectNode body = this.objectMapper.createObjectNode()
                .put("userId", identity.userId().toLowerCase(Locale.ROOT))
                .put("password", identity.credential());
        final ResponseEntity<String> response = request(HttpMethod.POST, "/api/auth/signon", body, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        final JsonNode payload = responseBody(response);
        assertThat(payload.path("userId").asText()).isEqualTo(identity.userId());
        assertThat(payload.path("token").asText()).isNotBlank();
        return new AuthenticatedSession(
                payload.path("token").asText(),
                payload.path("userId").asText(),
                payload.path("userType").asText());
    }

    private ResponseEntity<String> request(final HttpMethod method, final String path, final Object body,
            final AuthenticatedSession session) {
        return request(method, path, body, session, Map.of());
    }

    private ResponseEntity<String> request(final HttpMethod method, final String path, final Object body,
            final AuthenticatedSession session, final Map<String, String> additionalHeaders) {
        final HttpHeaders headers = requestHeaders(session);
        additionalHeaders.forEach(headers::set);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        final ResponseEntity<String> response =
                this.http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        assertCorrelationAndConversationHygiene(response);
        return response;
    }

    private HttpHeaders requestHeaders(final AuthenticatedSession session) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set(CorrelationIdFilter.CORRELATION_ID_HEADER, CORRELATION_VALUE);
        headers.set(CorrelationIdFilter.TRACE_PARENT_HEADER, TRACE_PARENT_VALUE);
        if (session != null) {
            headers.setBearerAuth(session.token());
        }
        return headers;
    }

    private void assertCorrelationAndConversationHygiene(final ResponseEntity<String> response) {
        assertThat(response.getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo(CORRELATION_VALUE);
        final String body = response.getBody();
        if (body == null || body.isBlank()) {
            return;
        }
        final MediaType contentType = response.getHeaders().getContentType();
        if (contentType != null && MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            assertNoLegacyConversationFields(parseJson(body));
        }
    }

    private void assertNoLegacyConversationFields(final JsonNode root) {
        final List<String> fieldNames = new ArrayList<>();
        collectFieldNames(root, fieldNames);
        assertThat(fieldNames).doesNotContainAnyElementsOf(LEGACY_ONLY_FIELDS);
    }

    private static void collectFieldNames(final JsonNode node, final List<String> destination) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(destination::add);
            node.elements().forEachRemaining(child -> collectFieldNames(child, destination));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectFieldNames(child, destination));
        }
    }

    private JsonNode responseBody(final ResponseEntity<String> response) {
        assertThat(response.getBody()).isNotNull().isNotBlank();
        return parseJson(response.getBody());
    }

    private JsonNode parseJson(final String body) {
        try {
            return this.objectMapper.readTree(body);
        } catch (final java.io.IOException malformedResponse) {
            throw new IllegalStateException("The application returned a body that was not valid JSON.",
                    malformedResponse);
        }
    }

    private JsonNode jwtClaims(final String token) {
        final String[] segments = token.split("\\.");
        assertThat(segments.length).isEqualTo(3);
        final byte[] payload = Base64.getUrlDecoder().decode(segments[1]);
        try {
            return this.objectMapper.readTree(payload);
        } catch (final java.io.IOException malformedClaims) {
            throw new IllegalStateException("The issued token did not contain a valid JSON claim set.",
                    malformedClaims);
        }
    }

    private String expiredToken(final AuthenticatedSession session) {
        final JsonNode issuedClaims = jwtClaims(session.token());
        final JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuedClaims.path("iss").asText())
                .subject(session.userId())
                .issuedAt(FIXED_INSTANT.minusSeconds(7_200L))
                .expiresAt(FIXED_INSTANT.minusSeconds(3_600L))
                .claim(JwtTokenProvider.ROLE_CLAIM_NAME, JwtTokenProvider.USER_AUTHORITY)
                .build();
        final JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return this.jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static String tamperedToken(final String token) {
        final String[] segments = token.split("\\.");
        if (segments.length != 3 || segments[2].isEmpty()) {
            throw new IllegalArgumentException("A signed token must contain three non-empty compact segments.");
        }
        final char replacement = segments[2].charAt(0) == 'A' ? 'B' : 'A';
        return segments[0] + '.' + segments[1] + '.' + replacement + segments[2].substring(1);
    }

    private static String accountId(final long numericId) {
        return String.format(Locale.ROOT, "%011d", numericId);
    }

    private static String text(final Map<String, Object> row, final String key) {
        final Object value = row.get(key);
        if (value == null) {
            throw new IllegalStateException("The database projection omitted required column " + key + '.');
        }
        return value.toString().stripTrailing();
    }

    private static String money(final Map<String, Object> row, final String key) {
        final Object value = row.get(key);
        if (value instanceof BigDecimal decimal) {
            return decimal.setScale(2).toPlainString();
        }
        return new BigDecimal(text(row, key)).setScale(2).toPlainString();
    }

    private static String digits(final String value) {
        return value.replaceAll("[^0-9]", "");
    }

    private static String compactDate(final String date) {
        return date.replace("-", "");
    }

    private static void putDateParts(final ObjectNode target, final String prefix, final String date) {
        final String[] parts = date.split("-");
        if (parts.length != 3) {
            throw new IllegalArgumentException("A persisted date did not have the required three components.");
        }
        target.put(prefix + "Year", parts[0]);
        target.put(prefix + "Month", parts[1]);
        target.put(prefix + "Day", parts[2]);
    }

    private Map<String, Object> accountState(final long numericAccountId) {
        return this.jdbcTemplate.queryForMap("""
                SELECT a.acct_id, a.acct_active_status, a.acct_curr_bal, a.acct_credit_limit,
                       a.acct_cash_credit_limit, a.acct_open_date, a.acct_expiraion_date,
                       a.acct_reissue_date, a.acct_curr_cyc_credit, a.acct_curr_cyc_debit,
                       a.acct_addr_zip, a.acct_group_id, a.version AS acct_version,
                       c.cust_id, c.cust_first_name, c.cust_middle_name, c.cust_last_name,
                       c.cust_addr_line_1, c.cust_addr_line_2, c.cust_addr_line_3,
                       c.cust_addr_state_cd, c.cust_addr_country_cd, c.cust_addr_zip,
                       c.cust_phone_num_1, c.cust_phone_num_2, c.cust_ssn,
                       c.cust_govt_issued_id, c.cust_dob_yyyy_mm_dd, c.cust_eft_account_id,
                       c.cust_pri_card_holder_ind, c.cust_fico_credit_score,
                       c.version AS cust_version
                  FROM account a
                  JOIN card_cross_reference x ON x.xref_acct_id = a.acct_id
                  JOIN customer c ON c.cust_id = x.xref_cust_id
                 WHERE a.acct_id = ?
                 ORDER BY x.xref_card_num
                 LIMIT 1
                """, numericAccountId);
    }

    private void restoreAccountState(final Map<String, Object> state) {
        executeCommittedUpdate("""
                UPDATE account
                   SET acct_active_status = ?, acct_curr_bal = ?, acct_credit_limit = ?,
                       acct_cash_credit_limit = ?, acct_open_date = ?, acct_expiraion_date = ?,
                       acct_reissue_date = ?, acct_curr_cyc_credit = ?, acct_curr_cyc_debit = ?,
                       acct_addr_zip = ?, acct_group_id = ?, version = ?
                 WHERE acct_id = ?
                """,
                state.get("acct_active_status"), state.get("acct_curr_bal"), state.get("acct_credit_limit"),
                state.get("acct_cash_credit_limit"), state.get("acct_open_date"),
                state.get("acct_expiraion_date"), state.get("acct_reissue_date"),
                state.get("acct_curr_cyc_credit"), state.get("acct_curr_cyc_debit"),
                state.get("acct_addr_zip"), state.get("acct_group_id"), state.get("acct_version"),
                state.get("acct_id"));
        executeCommittedUpdate("""
                UPDATE customer
                   SET cust_first_name = ?, cust_middle_name = ?, cust_last_name = ?,
                       cust_addr_line_1 = ?, cust_addr_line_2 = ?, cust_addr_line_3 = ?,
                       cust_addr_state_cd = ?, cust_addr_country_cd = ?, cust_addr_zip = ?,
                       cust_phone_num_1 = ?, cust_phone_num_2 = ?, cust_ssn = ?,
                       cust_govt_issued_id = ?, cust_dob_yyyy_mm_dd = ?, cust_eft_account_id = ?,
                       cust_pri_card_holder_ind = ?, cust_fico_credit_score = ?, version = ?
                 WHERE cust_id = ?
                """,
                state.get("cust_first_name"), state.get("cust_middle_name"), state.get("cust_last_name"),
                state.get("cust_addr_line_1"), state.get("cust_addr_line_2"), state.get("cust_addr_line_3"),
                state.get("cust_addr_state_cd"), state.get("cust_addr_country_cd"),
                state.get("cust_addr_zip"), state.get("cust_phone_num_1"), state.get("cust_phone_num_2"),
                state.get("cust_ssn"), state.get("cust_govt_issued_id"),
                state.get("cust_dob_yyyy_mm_dd"), state.get("cust_eft_account_id"),
                state.get("cust_pri_card_holder_ind"), state.get("cust_fico_credit_score"),
                state.get("cust_version"), state.get("cust_id"));
    }

    /**
     * Builds the fifty-four-field {@code COACTUP} map from one account's live state.
     *
     * <p><strong>Four members are deliberately not the seeded values.</strong> {@code PUT /api/accounts}
     * runs {@code 1200-EDIT-MAP-INPUTS} - the twenty-four field edits of {@code 1210} through {@code 1280} -
     * before it writes, and the corpus fixtures were generated without regard for those edits, so a
     * byte-faithful echo of the seeded row is a payload the <em>source</em> would refuse:
     * <ul>
     *   <li>{@code app/data/ASCII/custdata.txt} carries area codes such as {@code 002}, {@code 034},
     *       {@code 179} and {@code 373} that {@code 1260-EDIT-US-PHONE-NUM} rejects at
     *       {@code app/cbl/COACTUPC.cbl:2297-2310}, because they are absent from
     *       {@code VALID-GENERAL-PURP-CODE} in {@code app/cpy/CSLKPCDY.cpy}. Only 15 of the 50 seeded
     *       customers carry two acceptable area codes.</li>
     *   <li>The seeded state and postal code of account one, {@code NC} with {@code 12546}, form the
     *       combination {@code NC12}, which {@code 1280-EDIT-US-STATE-ZIP-CD} rejects at
     *       {@code app/cbl/COACTUPC.cbl:2536-2547} because {@code VALID-US-STATE-ZIP-CD2-COMBO} lists only
     *       {@code NC27} and {@code NC28}.</li>
     * </ul>
     * The substitutes below are values those two lookup tables do contain, and the FICO score is already
     * {@code 300} for the same reason - the seeded score of account one is {@code 274}, outside the
     * {@code 300}-to-{@code 850} range {@code 1275-EDIT-FICO-SCORE} enforces. Nothing else is substituted,
     * and the caller restores the row afterwards.
     *
     * @param state the live account and customer state
     * @return the map to submit
     */
    private ObjectNode accountUpdateBody(final Map<String, Object> state) {
        final String account = accountId(new BigDecimal(state.get("acct_id").toString()).longValueExact());
        final String customer =
                String.format(Locale.ROOT, "%09d", new BigDecimal(state.get("cust_id").toString()).longValueExact());
        final String openDate = text(state, "acct_open_date");
        final String expiryDate = text(state, "acct_expiraion_date");
        final String reissueDate = text(state, "acct_reissue_date");
        final String birthDate = text(state, "cust_dob_yyyy_mm_dd");
        final String ssn = digits(text(state, "cust_ssn"));
        final String phone1 = EDIT_ACCEPTED_PHONE_1;
        final String phone2 = EDIT_ACCEPTED_PHONE_2;
        assertThat(ssn.length()).isEqualTo(9);
        assertThat(phone1.length()).isEqualTo(10);
        assertThat(phone2.length()).isEqualTo(10);

        final ObjectNode body = this.objectMapper.createObjectNode()
                .put("accountId", account)
                .put("accountStatus", text(state, "acct_active_status"))
                .put("creditLimit", money(state, "acct_credit_limit"))
                .put("cashCreditLimit", money(state, "acct_cash_credit_limit"))
                .put("currentBalance", money(state, "acct_curr_bal"))
                .put("currentCycleCredit", money(state, "acct_curr_cyc_credit"))
                .put("accountGroupId", "e2egrp")
                .put("currentCycleDebit", money(state, "acct_curr_cyc_debit"))
                .put("customerId", customer)
                .put("customerSsnPart1", ssn.substring(0, 3))
                .put("customerSsnPart2", ssn.substring(3, 5))
                .put("customerSsnPart3", ssn.substring(5))
                .put("customerFicoScore", "300")
                .put("customerFirstName", text(state, "cust_first_name"))
                .put("customerMiddleName", text(state, "cust_middle_name"))
                .put("customerLastName", text(state, "cust_last_name"))
                .put("addressLine1", text(state, "cust_addr_line_1"))
                .put("addressStateCode", EDIT_ACCEPTED_STATE_CODE)
                .put("addressLine2", text(state, "cust_addr_line_2"))
                .put("addressZip", EDIT_ACCEPTED_ZIP)
                .put("addressCity", text(state, "cust_addr_line_3"))
                .put("addressCountryCode", text(state, "cust_addr_country_cd"))
                .put("phone1AreaCode", phone1.substring(0, 3))
                .put("phone1Prefix", phone1.substring(3, 6))
                .put("phone1LineNumber", phone1.substring(6))
                .put("governmentIssuedId", text(state, "cust_govt_issued_id"))
                .put("phone2AreaCode", phone2.substring(0, 3))
                .put("phone2Prefix", phone2.substring(3, 6))
                .put("phone2LineNumber", phone2.substring(6))
                .put("eftAccountId", text(state, "cust_eft_account_id"))
                .put("primaryCardHolderIndicator", text(state, "cust_pri_card_holder_ind"));
        putDateParts(body, "openDate", openDate);
        putDateParts(body, "expiryDate", expiryDate);
        putDateParts(body, "reissueDate", reissueDate);
        putDateParts(body, "dateOfBirth", birthDate);

        final ObjectNode newDetails = this.objectMapper.createObjectNode()
                .put("accountId", account)
                .put("activeStatus", text(state, "acct_active_status"))
                .put("currentBalance", money(state, "acct_curr_bal"))
                .put("creditLimit", money(state, "acct_credit_limit"))
                .put("cashCreditLimit", money(state, "acct_cash_credit_limit"))
                .put("openDate", compactDate(openDate))
                .put("expiraionDate", compactDate(expiryDate))
                .put("reissueDate", compactDate(reissueDate))
                .put("currentCycleCredit", money(state, "acct_curr_cyc_credit"))
                .put("currentCycleDebit", money(state, "acct_curr_cyc_debit"))
                .put("groupId", "e2egrp")
                .put("customerId", customer)
                .put("firstName", text(state, "cust_first_name"))
                .put("middleName", text(state, "cust_middle_name"))
                .put("lastName", text(state, "cust_last_name"))
                .put("addressLine1", text(state, "cust_addr_line_1"))
                .put("addressLine2", text(state, "cust_addr_line_2"))
                .put("addressLine3", text(state, "cust_addr_line_3"))
                .put("addressStateCode", EDIT_ACCEPTED_STATE_CODE)
                .put("addressCountryCode", text(state, "cust_addr_country_cd"))
                .put("addressZip", EDIT_ACCEPTED_ZIP)
                .put("phoneNumber1AreaCode", phone1.substring(0, 3))
                .put("phoneNumber1Prefix", phone1.substring(3, 6))
                .put("phoneNumber1LineNumber", phone1.substring(6))
                .put("phoneNumber2AreaCode", phone2.substring(0, 3))
                .put("phoneNumber2Prefix", phone2.substring(3, 6))
                .put("phoneNumber2LineNumber", phone2.substring(6))
                .put("ssnPart1", ssn.substring(0, 3))
                .put("ssnPart2", ssn.substring(3, 5))
                .put("ssnPart3", ssn.substring(5))
                .put("governmentIssuedId", text(state, "cust_govt_issued_id"))
                .put("dateOfBirth", compactDate(birthDate))
                .put("eftAccountId", text(state, "cust_eft_account_id"))
                .put("primaryCardHolderIndicator", text(state, "cust_pri_card_holder_ind"))
                .put("ficoScore", "300");
        body.set("newDetails", newDetails);
        return body;
    }

    private Map<String, Object> cardState(final long numericAccountId) {
        return this.jdbcTemplate.queryForMap("""
                SELECT card_num, card_acct_id, card_cvv_cd, card_embossed_name,
                       card_expiraion_date, card_active_status, version
                  FROM card
                 WHERE card_acct_id = ?
                 ORDER BY card_num
                 LIMIT 1
                """, numericAccountId);
    }

    private void restoreCardState(final Map<String, Object> state) {
        executeCommittedUpdate("""
                UPDATE card
                   SET card_acct_id = ?, card_cvv_cd = ?, card_embossed_name = ?,
                       card_expiraion_date = ?, card_active_status = ?, version = ?
                 WHERE card_num = ?
                """,
                state.get("card_acct_id"), state.get("card_cvv_cd"), state.get("card_embossed_name"),
                state.get("card_expiraion_date"), state.get("card_active_status"), state.get("version"),
                state.get("card_num"));
    }

    private ObjectNode cardUpdateBody(final Map<String, Object> state) {
        final String account =
                accountId(new BigDecimal(state.get("card_acct_id").toString()).longValueExact());
        final String cardNumber = text(state, "card_num");
        final String expiry = text(state, "card_expiraion_date");
        final String[] date = expiry.split("-");
        final String currentStatus = text(state, "card_active_status");
        final String updatedStatus = "Y".equals(currentStatus) ? "N" : "Y";

        final ObjectNode body = this.objectMapper.createObjectNode()
                .put("accountId", account)
                .put("cardNumber", cardNumber)
                .put("cardholderName", text(state, "card_embossed_name"))
                .put("cardStatusCode", updatedStatus)
                .put("expiryMonth", date[1])
                .put("expiryYear", date[0])
                .put("expiryDay", date[2]);
        final ObjectNode newDetails = this.objectMapper.createObjectNode()
                .put("accountId", account)
                .put("cardNumber", cardNumber);
        final ObjectNode cardData = this.objectMapper.createObjectNode()
                .put("cardholderName", text(state, "card_embossed_name"))
                .put("cardStatusCode", updatedStatus);
        cardData.set("expiraionDate", this.objectMapper.createObjectNode()
                .put("expiryYear", date[0])
                .put("expiryMonth", date[1])
                .put("expiryDay", date[2]));
        newDetails.set("cardData", cardData);
        body.set("newDetails", newDetails);
        return body;
    }

    private ObjectNode transactionRequest(final long numericAccountId) {
        final Map<String, Object> card = cardState(numericAccountId);
        final Map<String, Object> category = this.jdbcTemplate.queryForMap("""
                SELECT tran_type_cd, tran_cat_cd
                  FROM transaction_category
                 ORDER BY tran_type_cd, tran_cat_cd
                 LIMIT 1
                """);
        final int categoryCode = new BigDecimal(category.get("tran_cat_cd").toString()).intValueExact();
        return this.objectMapper.createObjectNode()
                .put("accountId", accountId(numericAccountId))
                .put("cardNumber", text(card, "card_num"))
                .put("typeCode", text(category, "tran_type_cd"))
                .put("categoryCode", String.format(Locale.ROOT, "%04d", categoryCode))
                .put("source", "POS")
                .put("description", "E2E PURCHASE")
                .put("amount", "+00001234.56")
                .put("originatingDate", "2022-06-09")
                .put("processingDate", "2022-06-10")
                .put("merchantId", "000000001")
                .put("merchantName", "E2E MERCHANT")
                .put("merchantCity", "TEST CITY")
                .put("merchantZip", "00000")
                .put("confirmation", "Y");
    }

    private void clearTransactions() {
        executeCommittedUpdate("TRUNCATE TABLE \"transaction\"");
        assertThat(queryCommittedLong("SELECT count(*) FROM \"transaction\"")).isZero();
    }

    private static int executeCommittedUpdate(final String sql, final Object... parameters) {
        try (Connection connection = POSTGRES.createConnection("");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(true);
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement.executeUpdate();
        } catch (final SQLException databaseFailure) {
            throw new IllegalStateException("A deterministic end-to-end fixture update failed.",
                    databaseFailure);
        }
    }

    private static long queryCommittedLong(final String sql, final Object... parameters) {
        try (Connection connection = POSTGRES.createConnection("");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(true);
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("A deterministic fixture query returned no row.");
                }
                return rows.getLong(1);
            }
        } catch (final SQLException databaseFailure) {
            throw new IllegalStateException("A deterministic end-to-end fixture query failed.",
                    databaseFailure);
        }
    }

    private static BigDecimal queryCommittedDecimal(final String sql, final Object... parameters) {
        try (Connection connection = POSTGRES.createConnection("");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(true);
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("A deterministic fixture query returned no row.");
                }
                return rows.getBigDecimal(1);
            }
        } catch (final SQLException databaseFailure) {
            throw new IllegalStateException("A deterministic end-to-end fixture query failed.",
                    databaseFailure);
        }
    }

    private ObjectNode billPaymentRequest(final long numericAccountId, final String confirmation) {
        final Map<String, Object> state = accountState(numericAccountId);
        return this.objectMapper.createObjectNode()
                .put("accountId", accountId(numericAccountId))
                .put("currentBalance", money(state, "acct_curr_bal"))
                .put("confirmation", confirmation);
    }

    private ObjectNode reportRequest(final String period, final String confirmation) {
        final ObjectNode request = this.objectMapper.createObjectNode().put("confirmation", confirmation);
        if ("monthly".equals(period)) {
            request.put("monthlySelected", "Y");
        } else if ("yearly".equals(period)) {
            request.put("yearlySelected", "Y");
        } else if ("custom".equals(period)) {
            request.put("customSelected", "Y")
                    .put("startDateMonth", "05")
                    .put("startDateDay", "01")
                    .put("startDateYear", "2022")
                    .put("endDateMonth", "05")
                    .put("endDateDay", "31")
                    .put("endDateYear", "2022");
        } else {
            throw new IllegalArgumentException("Unknown report period.");
        }
        return request;
    }

    private JsonNode receiveOneReportMessage() {
        try (SqsClient sqs = queueClient()) {
            final String queueUrl = sqs.getQueueUrl(builder -> builder.queueName(REPORT_QUEUE_NAME)).queueUrl();
            final List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(5)
                    .build()).messages();
            assertThat(messages).hasSize(1);
            final Message message = messages.getFirst();
            sqs.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build());
            return parseJson(message.body());
        }
    }

    private void drainReportQueue() {
        try (SqsClient sqs = queueClient()) {
            final String queueUrl = sqs.getQueueUrl(builder -> builder.queueName(REPORT_QUEUE_NAME)).queueUrl();
            for (int attempt = 0; attempt < 5; attempt++) {
                final List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(0)
                        .build()).messages();
                if (messages.isEmpty()) {
                    return;
                }
                for (final Message message : messages) {
                    sqs.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());
                }
            }
            final List<Message> survivors = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(0)
                    .build()).messages();
            assertThat(survivors).as("the shared report queue must be empty after bounded cleanup").isEmpty();
        }
    }

    private static String sourceText(final String relativePath) {
        try {
            return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r", "");
        } catch (final java.io.IOException readFailure) {
            throw new IllegalStateException("Unable to read required frozen source evidence.", readFailure);
        }
    }

    private static String sourceLines(final String relativePath, final int startInclusive,
            final int endInclusive) {
        final List<String> lines = sourceText(relativePath).lines().toList();
        return String.join("\n", lines.subList(startInclusive - 1, endInclusive));
    }

    @Test
    @Order(1)
    @DisplayName("app/csd/CARDDEMO.CSD:306-480 exposes exactly 17 sourced operations across 8 controllers")
    void endpointInventoryMatchesTheCsdAndTheRuntimePortIsFrameworkAssigned() {
        assertThat(this.port).isPositive();
        assertThat(this.clock.instant()).isEqualTo(FIXED_INSTANT);

        final Map<Class<?>, Long> actualDistribution = this.handlerMapping.getHandlerMethods().entrySet()
                .stream()
                .filter(entry -> entry.getKey().getPatternValues().stream()
                        .anyMatch(pattern -> pattern.startsWith("/api/")))
                .collect(Collectors.groupingBy(entry -> entry.getValue().getBeanType(), Collectors.counting()));

        final Map<Class<?>, Long> expectedDistribution = Map.of(
                AuthController.class, 1L,
                MenuController.class, 2L,
                AccountController.class, 2L,
                CardController.class, 3L,
                TransactionController.class, 3L,
                BillingController.class, 1L,
                ReportController.class, 1L,
                AdminController.class, 4L);
        assertThat(actualDistribution).containsExactlyInAnyOrderEntriesOf(expectedDistribution);
        assertThat(actualDistribution.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(17L);

        final Set<String> operations = this.handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> expectedDistribution.containsKey(entry.getValue().getBeanType()))
                .flatMap(entry -> entry.getKey().getPatternValues().stream()
                        .flatMap(path -> entry.getKey().getMethodsCondition().getMethods().stream()
                                .map(method -> method.name() + " " + path)))
                .collect(Collectors.toSet());
        assertThat(operations).containsExactlyInAnyOrder(
                "POST /api/auth/signon",
                "GET /api/menu/main", "GET /api/menu/admin",
                "GET /api/accounts/{accountId}", "PUT /api/accounts",
                "GET /api/cards", "GET /api/cards/detail", "PUT /api/cards",
                "GET /api/transactions", "GET /api/transactions/detail", "POST /api/transactions",
                "POST /api/billing/payments", "POST /api/reports",
                "GET /api/admin/users", "POST /api/admin/users",
                "PUT /api/admin/users/{userId}", "DELETE /api/admin/users/{userId}");
        assertThat(operations.stream().noneMatch(operation -> operation.contains("CDV1"))).isTrue();
        assertThat(operations.stream().noneMatch(operation -> Set.of(
                "TCATBALF", "DISCGRP", "TRANCATG", "TRANTYPE").stream().anyMatch(operation::contains)))
                .isTrue();

        final String csd = sourceText("app/csd/CARDDEMO.CSD");
        assertThat(csd.lines().filter(line -> line.matches("\\s+DEFINE TRANSACTION\\(.*")).count())
                .isEqualTo(18L);
        assertThat(csd).contains("DEFINE TRANSACTION(CDV1)", "PROGRAM(COCRDSEC)");
        assertThat(sourceText("app/cbl/COCRDSLC.cbl")).isNotBlank();
        assertThat(Arrays.stream(PageResponse.class.getMethods())
                .map(Method::getName)
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("total"))).isTrue();

        final int bmsInputFields = Map.ofEntries(
                Map.entry("COACTUP", 54), Map.entry("COACTVW", 37), Map.entry("COADM01", 20),
                Map.entry("COBIL00", 10), Map.entry("COCRDLI", 45), Map.entry("COCRDSL", 15),
                Map.entry("COCRDUP", 17), Map.entry("COMEN01", 20), Map.entry("CORPT00", 17),
                Map.entry("COSGN00", 11), Map.entry("COTRN00", 59), Map.entry("COTRN01", 21),
                Map.entry("COTRN02", 21), Map.entry("COUSR00", 59), Map.entry("COUSR01", 12),
                Map.entry("COUSR02", 12), Map.entry("COUSR03", 11))
                .values().stream().mapToInt(Integer::intValue).sum();
        assertThat(bmsInputFields).isEqualTo(441);
    }

    @Test
    @Order(2)
    @DisplayName("app/cbl/COSGN00C.cbl:132-136 upper-cases both credential fields and emits five JWT claims")
    void signOnUppercasesBothInputsAndEmitsOnlyTheFiveIdentityClaims() {
        assertThat(this.administrator.credential().chars().anyMatch(Character::isUpperCase)).isTrue();
        assertThat(this.administrator.credential().chars().anyMatch(Character::isLowerCase)).isTrue();

        final AuthenticatedSession session = signOn(this.administrator);
        assertThat(session.userType()).isEqualTo("A");
        final JsonNode claims = jwtClaims(session.token());
        final List<String> claimNames = new ArrayList<>();
        claims.fieldNames().forEachRemaining(claimNames::add);
        assertThat(claimNames).containsExactlyInAnyOrder("iss", "sub", "iat", "exp",
                JwtTokenProvider.ROLE_CLAIM_NAME);
        assertThat(claims.path("sub").asText()).isEqualTo(this.administrator.userId());
        assertThat(claims.path(JwtTokenProvider.ROLE_CLAIM_NAME).asText())
                .isEqualTo(JwtTokenProvider.ADMIN_AUTHORITY);
        assertThat(claims.path("exp").asLong()).isGreaterThan(claims.path("iat").asLong());

        final List<UserSecurity> seeded = this.userSecurityRepository
                .findAllByOrderBySecUsrIdAsc(PageRequest.of(0, 20))
                .getContent().stream()
                .filter(user -> !user.getSecUsrId().strip().equals(this.administrator.userId()))
                .filter(user -> !user.getSecUsrId().strip().equals(this.standardUser.userId()))
                .toList();
        assertThat(seeded.size()).isEqualTo(10);
        assertThat(seeded.stream().allMatch(user -> user.getPasswordHash() != null
                && user.getPasswordHash().matches("\\$2[aby]\\$10\\$[./A-Za-z0-9]{53}")))
                .as("all ten seeded credentials are represented only by strength-10 BCrypt digests")
                .isTrue();
        assertThat(seeded.stream().filter(user -> user.getSecUsrType() == UserType.ADMIN).count())
                .isEqualTo(5L);
        assertThat(seeded.stream().filter(user -> user.getSecUsrType() == UserType.USER).count())
                .isEqualTo(5L);
    }

    @Test
    @Order(3)
    @DisplayName("unknown-user and refused-credential sign-on responses are indistinguishable")
    void authenticationFailuresAreIndistinguishableOnTheWire() {
        final String refusedCredential = randomMixedCaseCredential();
        final ObjectNode knownUser = this.objectMapper.createObjectNode()
                .put("userId", this.standardUser.userId())
                .put("password", refusedCredential);
        final ObjectNode unknownUser = this.objectMapper.createObjectNode()
                .put("userId", randomUppercaseText(8))
                .put("password", refusedCredential);

        final ResponseEntity<String> knownResponse =
                request(HttpMethod.POST, "/api/auth/signon", knownUser, null);
        final ResponseEntity<String> unknownResponse =
                request(HttpMethod.POST, "/api/auth/signon", unknownUser, null);

        assertThat(knownResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(knownResponse.getBody()).isEqualTo(unknownResponse.getBody());
    }

    @Test
    @Order(4)
    @DisplayName("CM00 and CA00 bind option lists by populated count rather than OCCURS capacity")
    void menusPreserveCountsRoleGatesAndDistinctComingSoonMessages() {
        final AuthenticatedSession user = signOn(this.standardUser);
        final AuthenticatedSession admin = signOn(this.administrator);

        final ResponseEntity<String> mainResponse =
                request(HttpMethod.GET, "/api/menu/main", null, user);
        assertThat(mainResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        final JsonNode main = responseBody(mainResponse);
        assertThat(main.path("menuType").asText()).isEqualTo("MAIN");
        assertThat(main.path("optionCount").asInt()).isEqualTo(10);
        assertThat(main.path("options")).hasSize(10);

        final ResponseEntity<String> adminResponse =
                request(HttpMethod.GET, "/api/menu/admin", null, admin);
        assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        final JsonNode adminMenu = responseBody(adminResponse);
        assertThat(adminMenu.path("menuType").asText()).isEqualTo("ADMIN");
        assertThat(adminMenu.path("optionCount").asInt()).isEqualTo(4);
        assertThat(adminMenu.path("options")).hasSize(4);

        assertThat(request(HttpMethod.GET, "/api/menu/admin", null, user).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        final String mainSource = sourceLines("app/cbl/COMEN01C.cbl", 136, 165);
        final String adminSource = sourceLines("app/cbl/COADM01C.cbl", 138, 154);
        assertThat(mainSource).contains("No access - Admin Only option... ",
                "CDEMO-MENU-OPT-NAME");
        assertThat(adminSource).contains("This option ", "is coming soon ...");
        assertThat(adminSource.lines().filter(line -> line.contains("CDEMO-ADMIN-OPT-NAME"))
                .allMatch(line -> line.stripLeading().startsWith("*"))).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("CAVW and CAUP use a sealed ETag and accept a valid new FICO over an out-of-band old value")
    void accountViewAndUpdateUseTheSealedSnapshotAndNewDetailsValidation() {
        final AuthenticatedSession user = signOn(this.standardUser);
        final Map<String, Object> original = accountState(1L);
        assertThat(Integer.parseInt(text(original, "cust_fico_credit_score")) < 300).isTrue();

        try {
            final ResponseEntity<String> view =
                    request(HttpMethod.GET, "/api/accounts/" + accountId(1L), null, user);
            assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
            final String etag = view.getHeaders().getETag();
            assertThat(etag).isNotBlank();
            final JsonNode account = responseBody(view);
            assertThat(account.path("snapshotToken").asText()).isNotBlank();
            assertThat(etag.equals('"' + account.path("snapshotToken").asText() + '"')).isTrue();
            assertThat(account.fieldNames()).toIterable().doesNotContain(
                    "customerSsn", "customerDateOfBirth", "customerFirstName", "customerMiddleName",
                    "customerLastName", "phoneNumber1", "phoneNumber2", "governmentIssuedId",
                    "eftAccountId");

            final ObjectNode update = accountUpdateBody(original);
            assertThat(update.path("newDetails").path("dateOfBirth").asText().length()).isEqualTo(8);
            final ResponseEntity<String> updated = request(
                    HttpMethod.PUT,
                    "/api/accounts?confirm=true",
                    update,
                    user,
                    Map.of(HttpHeaders.IF_MATCH, etag));
            assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
            final JsonNode outcome = responseBody(updated);
            assertThat(outcome.path("applied").asBoolean()).isTrue();
            assertThat(outcome.path("changeAction").asText()).isEqualTo("CHANGES_OKAYED_AND_DONE");
            assertThat(this.jdbcTemplate.queryForObject(
                    "SELECT trim(acct_group_id) FROM account WHERE acct_id = ?", String.class, 1L))
                    .isEqualTo("e2egrp");
            assertThat(this.jdbcTemplate.queryForObject(
                    "SELECT trim(cust_fico_credit_score) FROM customer WHERE cust_id = ?",
                    String.class, original.get("cust_id")).equals("300")).isTrue();
        } finally {
            restoreAccountState(original);
        }
    }

    @Test
    @Order(6)
    @DisplayName("CAUP exposes five distinguishable outcomes and preserves the customer-lock success fall-through")
    void accountUpdateOutcomesDobOffsetsAndCaseAsymmetryRemainDistinguishable()
            throws ReflectiveOperationException {
        final AuthenticatedSession user = signOn(this.standardUser);
        final Map<String, Object> state = accountState(1L);
        final ObjectNode body = accountUpdateBody(state);
        final ResponseEntity<String> view =
                request(HttpMethod.GET, "/api/accounts/" + accountId(1L), null, user);
        final String etag = view.getHeaders().getETag();

        assertThat(request(HttpMethod.PUT, "/api/accounts", body, user).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(request(HttpMethod.PUT, "/api/accounts?confirm=true", body, user).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(request(HttpMethod.PUT, "/api/accounts?confirm=true", body, user,
                Map.of(HttpHeaders.IF_MATCH, etag + "x")).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_FAILED);

        final ObjectNode bodyCarriedSnapshot = body.deepCopy();
        bodyCarriedSnapshot.set("oldDetails", this.objectMapper.createObjectNode());
        assertThat(request(HttpMethod.PUT, "/api/accounts?confirm=true", bodyCarriedSnapshot, user,
                Map.of(HttpHeaders.IF_MATCH, etag)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        final Method statusFor = AccountController.class.getDeclaredMethod(
                "statusFor", ConcurrentUpdateException.Outcome.class);
        statusFor.setAccessible(true);
        final Map<ConcurrentUpdateException.Outcome, HttpStatus> expected =
                new EnumMap<>(ConcurrentUpdateException.Outcome.class);
        expected.put(ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_ACCOUNT, HttpStatus.LOCKED);
        expected.put(ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_CUSTOMER, HttpStatus.CONFLICT);
        expected.put(ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE,
                HttpStatus.PRECONDITION_FAILED);
        expected.put(ConcurrentUpdateException.Outcome.LOCKED_BUT_UPDATE_FAILED,
                HttpStatus.INTERNAL_SERVER_ERROR);
        expected.put(ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED,
                HttpStatus.PRECONDITION_REQUIRED);
        final Map<ConcurrentUpdateException.Outcome, HttpStatus> actual =
                new EnumMap<>(ConcurrentUpdateException.Outcome.class);
        for (final ConcurrentUpdateException.Outcome outcome : ConcurrentUpdateException.Outcome.values()) {
            actual.put(outcome, (HttpStatus) statusFor.invoke(null, outcome));
        }
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(actual.values()).doesNotHaveDuplicates();

        assertThat(ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_ACCOUNT.getLegacyMessage())
                .isEqualTo("Could not lock account record for update");
        assertThat(ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_CUSTOMER.getLegacyMessage())
                .isEqualTo("Could not lock customer record for update");
        assertThat(ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE.getLegacyMessage())
                .isEqualTo("Record changed by some one else. Please review");
        assertThat(ConcurrentUpdateException.Outcome.LOCKED_BUT_UPDATE_FAILED.getLegacyMessage())
                .isEqualTo("Update of record failed");
        assertThat(ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED.getLegacyMessage()).isEmpty();

        final String lockWrite = sourceLines("app/cbl/COACTUPC.cbl", 3931, 3942);
        final String outcomeDecision = sourceLines("app/cbl/COACTUPC.cbl", 2606, 2615);
        assertThat(lockWrite).contains("SET COULD-NOT-LOCK-CUST-FOR-UPDATE  TO TRUE");
        assertThat(outcomeDecision).doesNotContain("WHEN COULD-NOT-LOCK-CUST-FOR-UPDATE");
        assertThat(outcomeDecision).contains("WHEN OTHER", "ACUP-CHANGES-OKAYED-AND-DONE");

        final String compactDob = sourceLines("app/cbl/COACTUPC.cbl", 744, 751);
        final String comparison = sourceLines("app/cbl/COACTUPC.cbl", 4137, 4181);
        assertThat(compactDob).contains("PIC X(08)", "ACUP-OLD-CUST-DOB-YEAR",
                "ACUP-OLD-CUST-DOB-MON", "ACUP-OLD-CUST-DOB-DAY");
        assertThat(comparison).contains(
                "FUNCTION LOWER-CASE (ACCT-GROUP-ID)",
                "CUST-DOB-YYYY-MM-DD (1:4)",
                "CUST-DOB-YYYY-MM-DD (6:2)",
                "ACUP-OLD-CUST-DOB-YYYY-MM-DD (5:2)",
                "CUST-DOB-YYYY-MM-DD (9:2)",
                "ACUP-OLD-CUST-DOB-YYYY-MM-DD (7:2)");
        assertThat(comparison.lines().filter(line -> line.contains("FUNCTION LOWER-CASE")).count())
                .isEqualTo(2L);
        assertThat(comparison.lines().filter(line -> line.contains("FUNCTION UPPER-CASE")).count())
                .isEqualTo(18L);
    }

    @Test
    @Order(7)
    @DisplayName("CCLI, CCDL and CCUP preserve seven-row paging, three-state filters and update literals")
    void cardListDetailAndUpdateExerciseAllThreeCardOperations() {
        final AuthenticatedSession user = signOn(this.standardUser);
        final ResponseEntity<String> listResponse =
                request(HttpMethod.GET, "/api/cards", null, user);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        final JsonNode listing = responseBody(listResponse);
        assertThat(listing.path("pageSize").asInt()).isEqualTo(PageResponse.PAGE_SIZE_CARD_LIST);
        assertThat(listing.path("rows").size()).isLessThanOrEqualTo(7);

        final Map<String, Object> original = cardState(3L);
        final String cardNumber = text(original, "card_num");
        try {
            final String detailPath = "/api/cards/detail?accountFilter=" + accountId(3L)
                    + "&cardFilter=" + cardNumber;
            final ResponseEntity<String> detailResponse =
                    request(HttpMethod.GET, detailPath, null, user);
            assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            final String etag = detailResponse.getHeaders().getETag();
            assertThat(etag).isNotBlank();
            final JsonNode detail = responseBody(detailResponse);
            assertThat(detail.path("snapshotToken").asText()).isNotBlank();
            assertThat(detail.path("maskedCardNumber").asText().equals(cardNumber)).isFalse();

            final ResponseEntity<String> updateResponse = request(
                    HttpMethod.PUT,
                    "/api/cards",
                    cardUpdateBody(original),
                    user,
                    Map.of(HttpHeaders.IF_MATCH, etag));
            assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(responseBody(updateResponse).path("snapshotToken").isNull()).isTrue();
            assertThat(this.jdbcTemplate.queryForObject(
                    "SELECT trim(card_active_status) FROM card WHERE card_num = ?",
                    String.class, original.get("card_num")))
                    .isNotEqualTo(text(original, "card_active_status"));
        } finally {
            restoreCardState(original);
        }

        final String filterModel = sourceLines("app/cbl/COCRDLIC.cbl", 62, 68);
        assertThat(filterModel).contains(
                "FLG-ACCTFILTER-NOT-OK", "FLG-ACCTFILTER-ISVALID", "FLG-ACCTFILTER-BLANK",
                "FLG-CARDFILTER-NOT-OK", "FLG-CARDFILTER-ISVALID", "FLG-CARDFILTER-BLANK");
        final String literals = sourceLines("app/cbl/COCRDUPC.cbl", 178, 200);
        assertThat(literals).contains(
                "Card number not provided",
                "Card name not provided",
                "Card name can only contain alphabets and spaces",
                "Card number if supplied must be a 16 digit number",
                "Card Active Status must be Y or N",
                "Card expiry month must be between 1 and 12");
    }

    @Test
    @Order(8)
    @DisplayName("CT02 uses NUMVAL-C for the amount and generates identifier one on an empty table")
    void transactionAddUsesTheCurrencyParserAndStartsAtIdentifierOne() {
        final AuthenticatedSession user = signOn(this.standardUser);
        clearTransactions();
        try {
            final ResponseEntity<String> created = request(
                    HttpMethod.POST, "/api/transactions", transactionRequest(4L), user);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(created.getHeaders().getLocation()).isNotNull();
            final JsonNode transaction = responseBody(created);
            assertThat(new BigDecimal(transaction.path("transactionId").asText().strip()).longValueExact())
                    .isEqualTo(1L);
            assertThat(transaction.path("amount").asText()).isBlank();
            assertThat(WebConfig.AMOUNT_EDITED_MASK).isEqualTo("+99999999.99");
            assertThat(this.jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM \"transaction\"", Long.class)).isEqualTo(1L);
            assertThat(this.jdbcTemplate.queryForObject(
                    "SELECT tran_amt FROM \"transaction\" WHERE tran_id = ?",
                    BigDecimal.class, transaction.path("transactionId").asText()))
                    .isEqualByComparingTo("1234.56");

            final IllegalStateException rootCause = new IllegalStateException("retained collision cause");
            final DuplicateRecordException collision =
                    new DuplicateRecordException("generated identifier collision", "TRANSACT", null, rootCause);
            assertThat(collision).hasMessage("generated identifier collision").hasCause(rootCause);
            assertThat(this.transactionController.handleDuplicateRecord(collision).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);

            final String parsers = sourceLines("app/cbl/COTRN02C.cbl", 58, 59)
                    + sourceLines("app/cbl/COTRN02C.cbl", 200, 220)
                    + sourceLines("app/cbl/COTRN02C.cbl", 379, 388)
                    + sourceLines("app/cbl/COTRN02C.cbl", 452, 458);
            assertThat(parsers).contains("FUNCTION NUMVAL", "FUNCTION NUMVAL-C", "+99999999.99");
            assertThat(sourceLines("app/cbl/COTRN02C.cbl", 444, 451))
                    .contains("HIGH-VALUES", "STARTBR", "READPREV", "ENDBR", "ADD 1");
        } finally {
            clearTransactions();
        }
    }

    @Test
    @Order(9)
    @DisplayName("CT00 returns ten rows per page, proved by the cursor and the tenth BMS row")
    void transactionListUsesTheTenRowScreenDepthAndTwoLocatorProof() {
        final AuthenticatedSession user = signOn(this.standardUser);
        clearTransactions();
        try {
            assertThat(request(HttpMethod.POST, "/api/transactions", transactionRequest(4L), user)
                    .getStatusCode()).isEqualTo(HttpStatus.CREATED);
            final ResponseEntity<String> list =
                    request(HttpMethod.GET, "/api/transactions", null, user);
            assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
            final JsonNode body = responseBody(list);
            assertThat(body.path("pageSize").asInt()).isEqualTo(PageResponse.PAGE_SIZE_TRANSACTION_LIST);
            assertThat(body.path("rows").size()).isBetween(1, 10);
            assertThat(body.path("rows").findValuesAsText("amount"))
                    .allMatch(amount -> amount.matches("[+-][0-9]{8}\\.[0-9]{2}"));
            assertThat(sourceLines("app/cbl/COTRN00C.cbl", 65, 68))
                    .contains("CDEMO-CT00-PAGE-NUM", "CDEMO-CT00-NEXT-PAGE-FLG");
            assertThat(sourceLines("app/cpy-bms/COTRN00.CPY", 348, 360))
                    .contains("TRNID10I", "TDESC10I");
        } finally {
            clearTransactions();
        }
    }

    @Test
    @Order(10)
    @DisplayName("CT01 retrieves the transaction CT02 created and preserves the edited amount mask")
    void transactionDetailReturnsTheCreatedRecordWithoutInventingASecondParser() {
        final AuthenticatedSession user = signOn(this.standardUser);
        clearTransactions();
        try {
            final ResponseEntity<String> created = request(
                    HttpMethod.POST, "/api/transactions", transactionRequest(4L), user);
            final JsonNode createdBody = responseBody(created);
            final String transactionId = createdBody.path("transactionId").asText();
            final ResponseEntity<String> detail = request(
                    HttpMethod.GET,
                    "/api/transactions/detail?transactionId=" + transactionId,
                    null,
                    user);
            assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
            final JsonNode body = responseBody(detail);
            assertThat(body.path("transactionId").asText()).isEqualTo(transactionId);
            assertThat(body.path("amount").asText()).matches("[+-][0-9]{8}\\.[0-9]{2}");
            assertThat(sourceLines("app/cbl/COTRN01C.cbl", 269, 277)).contains("UPDATE");
        } finally {
            clearTransactions();
        }
    }

    @Test
    @Order(11)
    @DisplayName("CB00 preserves all five confirmation arms and settles the entire positive balance")
    void billPaymentPreservesTheFiveWayGateAndPaysTheFullBalanceToZero() {
        final AuthenticatedSession user = signOn(this.standardUser);
        final Map<String, Object> original = accountState(2L);
        clearTransactions();
        try {
            final ResponseEntity<String> blank = request(
                    HttpMethod.POST, "/api/billing/payments", billPaymentRequest(2L, ""), user);
            assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(responseBody(blank).path("outcome").asText()).isEqualTo("CONFIRMATION_REQUIRED");

            final ResponseEntity<String> lowValues = request(
                    HttpMethod.POST, "/api/billing/payments", billPaymentRequest(2L, "\u0000"), user);
            assertThat(lowValues.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(responseBody(lowValues).path("outcome").asText())
                    .isEqualTo("CONFIRMATION_REQUIRED");

            final ResponseEntity<String> declined = request(
                    HttpMethod.POST, "/api/billing/payments", billPaymentRequest(2L, "n"), user);
            assertThat(declined.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(responseBody(declined).path("outcome").asText()).isEqualTo("CANCELLED");

            final ResponseEntity<String> invalid = request(
                    HttpMethod.POST, "/api/billing/payments", billPaymentRequest(2L, "?"), user);
            assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(responseBody(invalid).path("detail").asText())
                    .isEqualTo("Invalid value. Valid values are (Y/N)...");

            final ObjectNode unknownAmount = billPaymentRequest(2L, "");
            unknownAmount.put("amount", "1.00");
            assertThat(request(HttpMethod.POST, "/api/billing/payments", unknownAmount, user).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(List.of(BillPaymentRequest.class.getRecordComponents()).stream()
                    .noneMatch(component -> component.getName().equals("amount"))).isTrue();

            final ResponseEntity<String> settled = request(
                    HttpMethod.POST, "/api/billing/payments", billPaymentRequest(2L, "Y"), user);
            assertThat(settled.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            final JsonNode receipt = responseBody(settled);
            assertThat(receipt.path("outcome").asText()).isEqualTo("SETTLED");
            assertThat(receipt.path("currentBalance").asText()).isBlank();
            assertThat(this.jdbcTemplate.queryForObject(
                    "SELECT acct_curr_bal FROM account WHERE acct_id = ?",
                    BigDecimal.class, 2L).signum() == 0).isTrue();

            final Map<String, Object> transaction = this.jdbcTemplate.queryForMap("""
                    SELECT tran_orig_ts, tran_proc_ts, tran_amt
                      FROM "transaction"
                     ORDER BY tran_id DESC
                     LIMIT 1
                    """);
            assertThat(text(transaction, "tran_orig_ts")).isEqualTo("2022-06-10 19:27:53.000000");
            assertThat(text(transaction, "tran_proc_ts")).isEqualTo(text(transaction, "tran_orig_ts"));
            assertThat(((BigDecimal) transaction.get("tran_amt"))
                    .compareTo((BigDecimal) original.get("acct_curr_bal")) == 0).isTrue();
            assertThat(sourceLines("app/cbl/COBIL00C.cbl", 173, 191))
                    .contains("WHEN 'Y'", "WHEN 'y'", "WHEN 'N'", "WHEN 'n'",
                            "WHEN SPACES", "WHEN LOW-VALUES", "WHEN OTHER");
        } finally {
            restoreAccountState(original);
            clearTransactions();
        }
    }

    @Test
    @Order(12)
    @DisplayName("CB00 rejects a non-positive balance before creating a synthetic transaction")
    void billPaymentRejectsAtOrBelowZero() {
        final AuthenticatedSession user = signOn(this.standardUser);
        final Map<String, Object> original = accountState(3L);
        clearTransactions();
        try {
            assertThat(executeCommittedUpdate(
                    "UPDATE account SET acct_curr_bal = 0, version = version + 1 WHERE acct_id = 3"))
                    .isEqualTo(1);
            assertThat(queryCommittedDecimal(
                    "SELECT acct_curr_bal FROM account WHERE acct_id = 3").signum() == 0).isTrue();
            final ResponseEntity<String> response = request(
                    HttpMethod.POST, "/api/billing/payments", billPaymentRequest(3L, "Y"), user);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(responseBody(response).path("detail").asText())
                    .isEqualTo("You have nothing to pay...");
            assertThat(this.jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM \"transaction\"", Long.class)).isZero();
        } finally {
            restoreAccountState(original);
            clearTransactions();
        }
    }

    @Test
    @Order(13)
    @DisplayName("CR00 publishes Monthly, Yearly and custom periods and preserves the four confirmation arms")
    void reportSubmissionPublishesTheThreePeriodsWithFullMonthBoundaries() {
        final AuthenticatedSession user = signOn(this.standardUser);
        drainReportQueue();

        final ResponseEntity<String> monthly = request(
                HttpMethod.POST, "/api/reports", reportRequest("monthly", "Y"), user);
        assertThat(monthly.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(responseBody(monthly).path("published").asBoolean()).isTrue();
        final JsonNode monthlyMessage = receiveOneReportMessage();
        assertThat(monthlyMessage.path("reportName").asText()).isEqualTo("Monthly");
        assertThat(monthlyMessage.path("startDate").asText()).isEqualTo("2022-06-01");
        assertThat(monthlyMessage.path("endDate").asText()).isEqualTo("2022-06-30");

        final ResponseEntity<String> yearly = request(
                HttpMethod.POST, "/api/reports", reportRequest("yearly", "y"), user);
        assertThat(yearly.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final JsonNode yearlyMessage = receiveOneReportMessage();
        assertThat(yearlyMessage.path("reportName").asText()).isEqualTo("Yearly");
        assertThat(yearlyMessage.path("startDate").asText()).isEqualTo("2022-01-01");
        assertThat(yearlyMessage.path("endDate").asText()).isEqualTo("2022-12-31");

        final ResponseEntity<String> custom = request(
                HttpMethod.POST, "/api/reports", reportRequest("custom", "Y"), user);
        assertThat(custom.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final JsonNode customMessage = receiveOneReportMessage();
        assertThat(customMessage.path("reportName").asText()).isEqualTo("Custom");
        assertThat(customMessage.path("startDate").asText()).isEqualTo("2022-05-01");
        assertThat(customMessage.path("endDate").asText()).isEqualTo("2022-05-31");

        // The prompt arm answers 200 with nothing published, exactly as the decline arm below and as the
        // billing and transaction-add confirmation gates do. CORPT00C grades none of its three
        // non-publishing arms differently - each sets WS-ERR-FLG and performs SEND-TRNRPT-SCREEN
        // (:464-474, :480-483, :484-493) - so only the arm carrying a value the one-byte CONFIRMI field
        // cannot accept is a malformed request. See finding M-17.
        final ResponseEntity<String> prompt = request(
                HttpMethod.POST, "/api/reports", reportRequest("monthly", ""), user);
        assertThat(prompt.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseBody(prompt).path("published").asBoolean()).isFalse();
        assertThat(responseBody(prompt).path("message").asText())
                .isEqualTo("Please confirm to print the Monthly report...");

        final ResponseEntity<String> declined = request(
                HttpMethod.POST, "/api/reports", reportRequest("monthly", "N"), user);
        assertThat(declined.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseBody(declined).path("published").asBoolean()).isFalse();

        final ResponseEntity<String> invalid = request(
                HttpMethod.POST, "/api/reports", reportRequest("monthly", "?"), user);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(responseBody(invalid).path("detail").asText())
                .isEqualTo("\"?\" is not a valid value to confirm...");

        final String reportSource = sourceLines("app/cbl/CORPT00C.cbl", 212, 255)
                + sourceLines("app/cbl/CORPT00C.cbl", 462, 531);
        assertThat(reportSource).contains(
                "MOVE 'Monthly'", "MOVE 'Yearly'", "INTEGER-OF-DATE", "DATE-OF-INTEGER",
                "Please confirm to print the ", "WIRTE-JOBSUB-TDQ",
                "Unable to Write TDQ (JOBS)...");
    }

    @Test
    @Order(14)
    @DisplayName("CU00 lists ten rows per page without exposing any credential member")
    void adminListUsesTheTenRowPageContract() {
        final AuthenticatedSession admin = signOn(this.administrator);
        final ResponseEntity<String> response =
                request(HttpMethod.GET, "/api/admin/users", null, admin);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        final JsonNode screen = responseBody(response);
        assertThat(screen.path("pageSize").asInt()).isEqualTo(PageResponse.PAGE_SIZE_USER_LIST);
        assertThat(screen.path("rows").size()).isBetween(1, 10);
        assertNoCredentialFields(screen);
        assertThat(sourceLines("app/cbl/COUSR00C.cbl", 55, 59)).contains("OCCURS 10 TIMES");
    }

    @Test
    @Order(15)
    @DisplayName("CU01 adds a user, preserves the duplicate literal and never returns a credential")
    void adminAddCreatesOneUserAndSurfacesTheDuplicateLiteral() {
        final AuthenticatedSession admin = signOn(this.administrator);
        final TestIdentity createdIdentity = newIdentity(UserType.USER);
        final ObjectNode body = this.objectMapper.createObjectNode()
                .put("firstName", "E2E")
                .put("lastName", "CREATED")
                .put("userId", createdIdentity.userId())
                .put("password", createdIdentity.credential())
                .put("userType", "U");
        try {
            final ResponseEntity<String> created =
                    request(HttpMethod.POST, "/api/admin/users", body, admin);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(this.userSecurityRepository.existsById(createdIdentity.userId())).isTrue();
            assertNoCredentialFields(responseBody(created));

            final ResponseEntity<String> duplicate =
                    request(HttpMethod.POST, "/api/admin/users", body, admin);
            assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(responseBody(duplicate).path("detail").asText())
                    .isEqualTo("User ID already exist...");
            assertThat(sourceLines("app/cbl/COUSR01C.cbl", 257, 263))
                    .contains("DUPKEY", "DUPREC", "User ID already exist...");
        } finally {
            deleteIdentity(createdIdentity);
        }
    }

    @Test
    @Order(16)
    @DisplayName("CU02 is the single update operation reached by both PF3 and PF5")
    void adminUpdateCoversBothLegacySaveKeysWithoutReturningTheCredential() {
        final AuthenticatedSession admin = signOn(this.administrator);
        final TestIdentity updatedIdentity = newIdentity(UserType.USER);
        saveIdentity(updatedIdentity, UserType.USER);
        final ObjectNode body = this.objectMapper.createObjectNode()
                .put("userId", updatedIdentity.userId())
                .put("firstName", "E2EUPDATED")
                .put("lastName", "TEST")
                .put("password", updatedIdentity.credential())
                .put("userType", "U");
        try {
            final ResponseEntity<String> response = request(
                    HttpMethod.PUT,
                    "/api/admin/users/" + updatedIdentity.userId(),
                    body,
                    admin);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            final JsonNode update = responseBody(response);
            assertThat(update.path("updateApplied").asBoolean()).isTrue();
            assertNoCredentialFields(update);
            assertThat(this.userSecurityRepository.findById(updatedIdentity.userId())
                    .orElseThrow().getSecUsrFname().strip()).isEqualTo("E2EUPDATED");

            final String dispatcher = sourceLines("app/cbl/COUSR02C.cbl", 108, 124);
            assertThat(dispatcher).contains(
                    "WHEN DFHPF3", "WHEN DFHPF5", "PERFORM UPDATE-USER-INFO");
            assertThat(dispatcher.lines().filter(line -> line.contains("PERFORM UPDATE-USER-INFO")).count())
                    .isEqualTo(2L);
        } finally {
            deleteIdentity(updatedIdentity);
        }
    }

    @Test
    @Order(17)
    @DisplayName("CU03 permits self-delete because COUSR03C carries no signed-on-identifier guard")
    void adminDeletePreservesTheAbsentSelfDeleteGuardAndWrongVerbLiteral() {
        final AuthenticatedSession admin = signOn(this.administrator);
        try {
            final ResponseEntity<String> response = request(
                    HttpMethod.DELETE,
                    "/api/admin/users/" + this.administrator.userId() + "?confirmed=true",
                    null,
                    admin);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(this.userSecurityRepository.existsById(this.administrator.userId())).isFalse();
            assertNoCredentialFields(responseBody(response));
            final String deleteSource = sourceText("app/cbl/COUSR03C.cbl");
            assertThat(deleteSource.lines().filter(line -> line.contains("CDEMO-USER-ID")).count())
                    .isZero();
            assertThat(deleteSource).contains(
                    " has been deleted ...", "User ID NOT found...", "Unable to Update User...");
        } finally {
            if (!this.userSecurityRepository.existsById(this.administrator.userId())) {
                saveIdentity(this.administrator, UserType.ADMIN);
            }
        }
    }

    @Test
    @Order(18)
    @DisplayName("only sign-on is anonymous; missing, expired, tampered and under-privileged tokens are refused")
    void securityBoundaryRejectsEveryHostileCredentialAndMalformedBody() {
        final ObjectNode emptyBody = this.objectMapper.createObjectNode();
        final List<EndpointProbe> protectedOperations = List.of(
                new EndpointProbe(HttpMethod.GET, "/api/menu/main", null),
                new EndpointProbe(HttpMethod.GET, "/api/menu/admin", null),
                new EndpointProbe(HttpMethod.GET, "/api/accounts/" + accountId(1L), null),
                new EndpointProbe(HttpMethod.PUT, "/api/accounts", emptyBody),
                new EndpointProbe(HttpMethod.GET, "/api/cards", null),
                new EndpointProbe(HttpMethod.GET, "/api/cards/detail", null),
                new EndpointProbe(HttpMethod.PUT, "/api/cards", emptyBody),
                new EndpointProbe(HttpMethod.GET, "/api/transactions", null),
                new EndpointProbe(HttpMethod.GET, "/api/transactions/detail", null),
                new EndpointProbe(HttpMethod.POST, "/api/transactions", emptyBody),
                new EndpointProbe(HttpMethod.POST, "/api/billing/payments", emptyBody),
                new EndpointProbe(HttpMethod.POST, "/api/reports", emptyBody),
                new EndpointProbe(HttpMethod.GET, "/api/admin/users", null),
                new EndpointProbe(HttpMethod.POST, "/api/admin/users", emptyBody),
                new EndpointProbe(HttpMethod.PUT, "/api/admin/users/" + this.standardUser.userId(), emptyBody),
                new EndpointProbe(HttpMethod.DELETE,
                        "/api/admin/users/" + this.standardUser.userId() + "?confirmed=true", null));
        for (final EndpointProbe probe : protectedOperations) {
            assertThat(request(probe.method(), probe.path(), probe.body(), null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        final AuthenticatedSession user = signOn(this.standardUser);
        final AuthenticatedSession expired = new AuthenticatedSession(
                expiredToken(user), user.userId(), user.userType());
        assertThat(request(HttpMethod.GET, "/api/menu/main", null, expired).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        final AuthenticatedSession tampered = new AuthenticatedSession(
                tamperedToken(user.token()), user.userId(), user.userType());
        assertThat(request(HttpMethod.GET, "/api/menu/main", null, tampered).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(request(HttpMethod.GET, "/api/admin/users", null, user).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(request(HttpMethod.POST, "/api/auth/signon", "{", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        final ObjectNode overlong = this.objectMapper.createObjectNode()
                .put("userId", "X".repeat(9))
                .put("password", randomMixedCaseCredential());
        assertThat(request(HttpMethod.POST, "/api/auth/signon", overlong, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @Order(19)
    @DisplayName("stateless correlation, actuator groups and the four bounded metrics are observable")
    void observabilityAndActuatorConfigurationAreExact() {
        assertThat(CorrelationIdFilter.MDC_KEY_CORRELATION_ID).isEqualTo("correlationId");
        assertThat(CorrelationIdFilter.MDC_KEY_TRACE_ID).isEqualTo("traceId");
        assertThat(CorrelationIdFilter.MDC_KEY_SPAN_ID).isEqualTo("spanId");

        assertThat(this.environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,prometheus");
        assertThat(this.environment.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("never");
        assertThat(this.environment.getProperty("management.endpoint.health.group.liveness.include"))
                .isEqualTo("livenessState");
        assertThat(this.environment.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db,s3,sqs");

        assertThat(request(HttpMethod.GET, "/actuator/health", null, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(request(HttpMethod.GET, "/actuator/health/liveness", null, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(request(HttpMethod.GET, "/actuator/health/readiness", null, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(request(HttpMethod.GET, "/actuator/info", null, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(request(HttpMethod.GET, "/actuator/prometheus", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(this.meterRegistry.find("carddemo.batch.records.processed").meters()).isNotEmpty();
        assertThat(this.meterRegistry.find("carddemo.batch.records.rejected").meters()).isNotEmpty();
        assertThat(this.meterRegistry.find("carddemo.auth.attempts").meters()).isNotEmpty();
        assertThat(this.meterRegistry.find("carddemo.transaction.amount.total").meters()).isNotEmpty();

        final String security = sourceText("src/main/java/com/cardemo/config/SecurityConfig.java");
        assertThat(security).contains(
                "SessionCreationPolicy.STATELESS",
                "\"/api/auth/signon\"",
                "anyRequest().denyAll()");
        assertThat(sourceText("app/cpy/COCOM01Y.cpy")).contains(
                "CDEMO-FROM-TRANID", "CDEMO-TO-TRANID", "CDEMO-PGM-CONTEXT",
                "CDEMO-LAST-MAP", "CDEMO-LAST-MAPSET");
    }

    /**
     * Every media type the eight body operations cannot read is refused with {@code 415} in this
     * application's own envelope, and only JSON is admitted.
     *
     * <p>Regression cover for the runtime finding that a wildcard or multipart {@code Content-Type} reached
     * {@code org.springframework.http.HttpHeaders#setContentType} during argument resolution, where the
     * resulting {@code IllegalArgumentException} was claimed by no {@code @ExceptionHandler} and escaped to
     * the container as a {@code 500} carrying the framework's default error body - on eight routes, one of
     * which is the anonymous sign-on. The three parsable-but-unreadable types were already answered
     * {@code 415} but in that same default body, so no client-side handler written against the envelope
     * could read them. All ten shapes are now one answer.
     *
     * <p>The sign-on route is used deliberately: it is the only body operation reachable without a
     * credential, so a refusal there proves the screen runs ahead of authentication.
     */
    @Test
    @Order(20)
    @DisplayName("every unreadable media type is refused 415 in the authored envelope, JSON alone is admitted")
    void unreadableMediaTypesAreRefusedWithTheAuthoredEnvelope() {
        final String credentials = "{\"userId\":\"" + this.administrator.userId()
                + "\",\"password\":\"" + this.administrator.credential() + "\"}";

        for (final String declared : List.of("*/*", "application/*", "multipart/form-data",
                "multipart/mixed", "text/plain", "application/xml", "foo/bar",
                "application/x-www-form-urlencoded")) {

            final ResponseEntity<String> refusal = postWithRawContentType(declared, credentials);

            assertThat(refusal.getStatusCode())
                    .describedAs("media type %s", declared)
                    .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            final JsonNode problem = responseBody(refusal);
            assertThat(problem.path("errorCode").asText()).isEqualTo("CARDDEMO-UNSUPPORTED-MEDIA-TYPE");
            assertThat(problem.path("correlationId").asText()).isEqualTo(CORRELATION_VALUE);
            // Nothing the caller sent is echoed back: neither the header nor the credential it carried.
            assertThat(refusal.getBody()).doesNotContain(declared)
                    .doesNotContain(this.administrator.credential());
        }

        // Two headers the media-type grammar cannot parse go over a raw socket, because the HTTP client this
        // test uses parses the header itself before it sends and would refuse to transmit either.
        for (final String declared : List.of("application/", "application/json, application/xml")) {
            final String unparsable = exchangeRaw("POST /api/auth/signon HTTP/1.1\r\n"
                    + "Host: localhost:" + this.port + "\r\n"
                    + "Content-Type: " + declared + "\r\n"
                    + "Content-Length: " + credentials.length() + "\r\n"
                    + "Connection: close\r\n\r\n" + credentials);
            assertThat(statusLineOf(unparsable))
                    .describedAs("media type %s", declared)
                    .startsWith("HTTP/1.1 415");
            assertThat(unparsable).contains("CARDDEMO-UNSUPPORTED-MEDIA-TYPE");
        }

        // Bytes sent under no declared media type at all are refused too - a request that describes its body
        // as nothing is not the same as the body-less request the deletion operation legitimately sends.
        final String undeclared = exchangeRaw("POST /api/auth/signon HTTP/1.1\r\n"
                + "Host: localhost:" + this.port + "\r\n"
                + "Content-Length: " + credentials.length() + "\r\n"
                + "Connection: close\r\n\r\n" + credentials);
        assertThat(statusLineOf(undeclared)).startsWith("HTTP/1.1 415");
        assertThat(undeclared).contains("CARDDEMO-UNSUPPORTED-MEDIA-TYPE");

        // JSON, and the structured suffix form of JSON, are both still read.
        for (final String declared : List.of("application/json", "application/json;charset=UTF-8",
                "application/merge-patch+json")) {
            assertThat(postWithRawContentType(declared, credentials).getStatusCode())
                    .describedAs("media type %s", declared)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    /**
     * A chunked request body is ordinary HTTP/1.1 and is processed normally, and a badly framed one is a
     * client error rather than a server failure.
     *
     * <p>Regression cover for the runtime finding that reported {@code 500} for every chunked body. The
     * screen this asserts is the one the security chain applies: the request-body bound reads a length-less
     * body itself, which is where the container decodes the chunk sizes, the terminating zero chunk and any
     * trailer fields, so a framing failure surfaces inside the filter chain where no
     * {@code @ExceptionHandler} can see it. Four framings are exercised through a raw socket, because no
     * HTTP client abstraction lets a test send a deliberately broken chunk.
     *
     * <p>The assertion on the malformed framings is deliberately {@code 4xx} rather than one exact status:
     * the container may itself refuse and commit a {@code 400} before the application sees the read failure,
     * and which of the two answers first is a container detail. What matters, and what is asserted, is that
     * neither is a {@code 5xx}.
     */
    @Test
    @Order(21)
    @DisplayName("chunked request bodies are read normally and a broken framing is a 4xx, never a 5xx")
    void chunkedRequestBodiesAreReadAndBrokenFramingIsAClientError() {
        final byte[] credentials = ("{\"userId\":\"" + this.administrator.userId()
                + "\",\"password\":\"" + this.administrator.credential() + "\"}")
                .getBytes(StandardCharsets.UTF_8);

        final String wellFramed = exchangeRaw(chunkedSignOn(credentials, "0\r\n\r\n"));
        assertThat(statusLineOf(wellFramed)).startsWith("HTTP/1.1 200");
        assertThat(wellFramed).contains("\"token\"");

        // A trailer section is legal and must not change the outcome.
        final String withTrailers =
                exchangeRaw(chunkedSignOn(credentials, "0\r\nX-Qa-Trailer: present\r\n\r\n"));
        assertThat(statusLineOf(withTrailers)).startsWith("HTTP/1.1 200");

        // A chunk extension is legal and must not change the outcome either.
        final String withExtension = exchangeRaw("POST /api/auth/signon HTTP/1.1\r\n"
                + "Host: localhost:" + this.port + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n"
                + Integer.toHexString(credentials.length) + ";qa=1\r\n"
                + new String(credentials, StandardCharsets.UTF_8) + "\r\n0\r\n\r\n");
        assertThat(statusLineOf(withExtension)).startsWith("HTTP/1.1 200");

        // A chunk size that is not hexadecimal is a client error and must never be a server failure.
        final String brokenSize = exchangeRaw("POST /api/auth/signon HTTP/1.1\r\n"
                + "Host: localhost:" + this.port + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\nzz\r\nabcd\r\n0\r\n\r\n");
        assertThat(statusLineOf(brokenSize)).doesNotContain(" 5");
        assertThat(statusLineOf(brokenSize)).startsWith("HTTP/1.1 4");
    }

    /**
     * A C0 or DEL control character is refused wherever it arrives, and never reaches the database.
     *
     * <p>Regression cover for the runtime finding that a {@code U+0000} inside untrusted input reached
     * PostgreSQL, which refuses the bind with {@code SQLSTATE 22021}, and surfaced as an input-output failure
     * on two operations and as an abend on a third - a client error reported as a store failure. The two
     * halves of the screen are asserted separately because they answer in the two different shapes this
     * application publishes: the request line yields a field-level refusal naming the parameter, and the body
     * yields the read-failure envelope, whose property path goes to the log rather than to the response.
     */
    @Test
    @Order(22)
    @DisplayName("a control character in the request line or the body is refused, and no row is written")
    void controlCharactersAreRefusedOnEveryInboundStringAndNothingIsWritten() {
        final AuthenticatedSession admin = signOn(this.administrator);

        // Sent over a raw socket because the HTTP client this test uses re-encodes a percent escape it is
        // handed, which would deliver the literal text %00 rather than the character under test.
        final String queryRefusal = exchangeRaw("GET /api/transactions/detail?transactionId=%00 HTTP/1.1\r\n"
                + "Host: localhost:" + this.port + "\r\n"
                + "Authorization: Bearer " + admin.token() + "\r\n"
                + "Connection: close\r\n\r\n");
        assertThat(statusLineOf(queryRefusal)).startsWith("HTTP/1.1 400");
        assertThat(queryRefusal)
                .contains("\"field\":\"transactionId\"")
                .contains("\"failureKind\":\"INVALID\"")
                .contains(WebConfig.CONTROL_CHARACTER_REJECTION_MESSAGE)
                .contains("CARDDEMO-VALIDATION-REJECTED");

        final String contaminated = "AB\u0000CD";
        final TestIdentity refusedIdentity = newIdentity(UserType.USER);
        final ObjectNode body = this.objectMapper.createObjectNode()
                .put("firstName", contaminated)
                .put("lastName", "E2ECTRL")
                .put("userId", refusedIdentity.userId())
                .put("password", refusedIdentity.credential())
                .put("userType", "U");

        final ResponseEntity<String> bodyRefusal =
                request(HttpMethod.POST, "/api/admin/users", body, admin);
        assertThat(bodyRefusal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Not 409: a control character is invalid field content, never a duplicate identifier.
        assertThat(responseBody(bodyRefusal).path("errorCode").asText())
                .isNotEqualTo("CARDDEMO-DUPLICATE-RECORD");
        assertThat(this.userSecurityRepository.existsById(refusedIdentity.userId())).isFalse();

        // The same character in a path variable is refused too. Sent over a raw socket because the HTTP
        // client this test uses re-encodes a percent escape in a path it is given, which would deliver the
        // literal text rather than the character under test.
        final String pathRefusal = exchangeRaw("GET /api/accounts/A%00B HTTP/1.1\r\n"
                + "Host: localhost:" + this.port + "\r\n"
                + "Authorization: Bearer " + admin.token() + "\r\n"
                + "Connection: close\r\n\r\n");
        assertThat(statusLineOf(pathRefusal)).startsWith("HTTP/1.1 4");

        // And the corpus's LOW-VALUES sentinel is still admitted in a body, because an untransmitted 3270
        // field arrived as NUL characters and app/cbl/COBIL00C.cbl gates its confirmation on exactly that.
        // Screening it out would break the field contract the seventeen operations are built on.
        final ObjectNode lowValues = billPaymentRequest(2L, "\u0000");
        final ResponseEntity<String> sentinel =
                request(HttpMethod.POST, "/api/billing/payments", lowValues, signOn(this.standardUser));
        assertThat(sentinel.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseBody(sentinel).path("outcome").asText()).isEqualTo("CONFIRMATION_REQUIRED");
    }

    /**
     * Posts a body under a caller-chosen {@code Content-Type} that the framework's header accessor would
     * refuse to set.
     *
     * <p>{@code HttpHeaders#setContentType} rejects a wildcard, and a raw set is the only way a test can send
     * one - which is the whole point, because that rejection is the defect being regression-tested.
     *
     * @param declared the exact header value to send
     * @param body the request body, sent verbatim
     * @return the response, with the correlation and conversation hygiene already asserted
     */
    private ResponseEntity<String> postWithRawContentType(final String declared, final String body) {
        final HttpHeaders headers = requestHeaders(null);
        headers.set(HttpHeaders.CONTENT_TYPE, declared);
        final ResponseEntity<String> response = this.http.exchange("/api/auth/signon", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertCorrelationAndConversationHygiene(response);
        return response;
    }

    /**
     * Composes one chunked sign-on request whose body is sent as a single chunk.
     *
     * @param body the body bytes to frame
     * @param terminator the terminating chunk and any trailer section, written verbatim
     * @return the complete request text
     */
    private String chunkedSignOn(final byte[] body, final String terminator) {
        return "POST /api/auth/signon HTTP/1.1\r\n"
                + "Host: localhost:" + this.port + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n"
                + Integer.toHexString(body.length) + "\r\n"
                + new String(body, StandardCharsets.UTF_8) + "\r\n"
                + terminator;
    }

    /**
     * Sends one raw HTTP request over a socket and returns everything the server wrote back.
     *
     * <p>Necessary because no HTTP client abstraction in the test classpath will send a deliberately broken
     * chunk, and the defect being regression-tested lives in how the container decodes chunked framing.
     *
     * @param request the complete request text, including its terminating sequence
     * @return the raw response text, headers and body together
     */
    private String exchangeRaw(final String request) {
        try (Socket socket = new Socket("localhost", this.port)) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (final java.io.IOException transportFailure) {
            throw new IllegalStateException("The raw HTTP exchange could not be completed.",
                    transportFailure);
        }
    }

    /**
     * Extracts the status line from a raw response.
     *
     * @param rawResponse everything the server wrote back
     * @return the first line, without its terminating sequence
     */
    private static String statusLineOf(final String rawResponse) {
        final int end = rawResponse.indexOf("\r\n");
        return end < 0 ? rawResponse : rawResponse.substring(0, end);
    }

    private void assertNoCredentialFields(final JsonNode payload) {
        final List<String> names = new ArrayList<>();
        collectFieldNames(payload, names);
        assertThat(names.stream().noneMatch(name -> name.equalsIgnoreCase("password")
                || name.equalsIgnoreCase("passwordHash"))).isTrue();
    }

    private record TestIdentity(String userId, String credential) {

        private TestIdentity {
            Objects.requireNonNull(userId, "userId must not be null");
            Objects.requireNonNull(credential, "credential must not be null");
        }

        @Override
        public String toString() {
            return "TestIdentity[userIdLength=" + this.userId.length() + ", credential=<withheld>]";
        }
    }

    private record AuthenticatedSession(String token, String userId, String userType) {

        private AuthenticatedSession {
            Objects.requireNonNull(token, "token must not be null");
            Objects.requireNonNull(userId, "userId must not be null");
            Objects.requireNonNull(userType, "userType must not be null");
        }

        @Override
        public String toString() {
            return "AuthenticatedSession[userIdLength=" + this.userId.length()
                    + ", userType=" + this.userType + ", token=<withheld>]";
        }
    }

    private record EndpointProbe(HttpMethod method, String path, Object body) {

        private EndpointProbe {
            Objects.requireNonNull(method, "method must not be null");
            Objects.requireNonNull(path, "path must not be null");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock carddemoFixedE2eClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        JwtDecoder carddemoFixedE2eJwtDecoder(
                @Value("${carddemo.security.jwt.issuer}") final String issuer) {
            final SecretKeySpec verificationKey = new SecretKeySpec(
                    EPHEMERAL_SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            final NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(verificationKey)
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
            final JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
            timestampValidator.setClock(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                    timestampValidator, new JwtIssuerValidator(issuer)));
            return decoder;
        }

        @Bean
        JwtEncoder carddemoFixedE2eJwtEncoder() {
            final byte[] keyMaterial = EPHEMERAL_SIGNING_KEY.getBytes(StandardCharsets.UTF_8);
            try {
                return new NimbusJwtEncoder(new ImmutableSecret<>(keyMaterial));
            } finally {
                Arrays.fill(keyMaterial, (byte) 0);
            }
        }
    }
}
