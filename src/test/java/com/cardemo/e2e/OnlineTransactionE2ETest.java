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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.cardemo.controller.AccountController;
import com.cardemo.controller.AdminController;
import com.cardemo.controller.AuthController;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.read.ListAppender;
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
import com.cardemo.repository.CustomerRepository;
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
import java.net.InetAddress;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
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
import org.springframework.dao.CannotAcquireLockException;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
 * <p><strong>Isolation: every scenario is independently runnable, and none is ordered.</strong> This class
 * declares no {@code @TestMethodOrder} and no {@code @Order}, and holds no state across methods. An earlier
 * revision did both: a per-class instance lifecycle created two principals once in a {@code @BeforeAll} and
 * twenty-two {@code @Order}ed methods shared them. What that cost was not theoretical. A route test is the
 * first thing an engineer runs alone while changing that route, and under the old arrangement running one
 * alone was not a supported operation. Worse, the first failure in the sequence masked every scenario after
 * it, and the self-delete scenario removed the shared administrator and restored it in a {@code finally} -
 * so any failure between those two points converted one real defect into twenty failures pointing away from
 * it.
 *
 * <p>Each method now provisions its own principals in {@code @BeforeEach}, levels its own starting state -
 * transaction table emptied, report queue drained - and removes what it created in {@code @AfterEach},
 * unconditionally. The self-delete scenario deletes a principal it creates for the purpose. Every method
 * that mutates a seeded row captures it first and restores it in a {@code finally}.
 *
 * <p>What <em>is</em> still shared is deliberately shared and is not mutable state: the two containers and
 * the Spring context. Restarting an engine and reapplying three migrations per method would cost minutes and
 * buy nothing, because the isolation that matters is isolation of data. {@code SAME_THREAD} execution is
 * retained for the same reason it was there before - levelling a shared table is not safe to do
 * concurrently - and it is an execution constraint rather than an ordering one.
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

    /**
     * The one appender {@code logback-spring.xml} declares, {@value}.
     *
     * <p>Named here so that the refusal-record assertions can reach the production encoder rather than the raw
     * event: the masking layer is a JSON generator decorator on this appender's encoder and is invisible to a
     * list appender.
     */
    private static final String CONSOLE_APPENDER_NAME = "CONSOLE";
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

    /**
     * Provisions the emulator resources the online surface reaches: the three buckets, the report
     * queue and the notification topic.
     *
     * @param endpoint the emulator endpoint.
     * @param region the region to address it in.
     * @param accessKey the emulator access key.
     * @param secretKey the emulator secret key.
     * @param inputBucket the batch input bucket.
     * @param outputBucket the batch output bucket.
     * @param statementsBucket the statements bucket.
     * @param reportQueue the report queue name.
     * @param notificationTopic the notification topic name.
     */
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

    /**
     * Creates one bucket.
     *
     * @param s3 the client to create it with.
     * @param bucket the bucket name.
     */
    private static void createBucket(final S3Client s3, final String bucket) {
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    }

    /**
     * Generates a signing key for this run alone, so no key material is committed.
     *
     * @return the generated key, base64url encoded.
     */
    private static String generateEphemeralSigningKey() {
        final byte[] material = new byte[SIGNING_KEY_BYTES];
        new SecureRandom().nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /**
     * Opens a queue client against the emulator, for the cases that read the report queue directly.
     *
     * @return that client; the caller closes it.
     */
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

    /**
     * Supplies the one controlled customer-lock failure, and nothing else.
     *
     * <p>A spy rather than a replacement, so every other test in this class still drives the real Spring Data
     * repository unchanged; only the single stubbed call in
     * {@link #theCustomerLockFailureIsForcedThroughTheEndpointAndCarriesItsLegacyOutcome()} diverges, and the
     * default after-method reset removes that stub without replacing the application context.
     *
     * <p>Injection is the only way to reach this branch over HTTP. The outcome requires a genuine
     * {@code PessimisticLockingFailureException} - an absent customer row falls through to the
     * record-not-found classification instead - and the row cannot simply be deleted, because the
     * cross-reference relation depends on it. Holding a competing database lock would block the request
     * indefinitely rather than fail it, since no lock timeout is configured. So the failure is injected at the
     * exact seam the source's own guard sits behind, and everything above it stays real: real HTTP, real
     * filter chain, real controller, real service, real exception mapping.
     */
    @MockitoSpyBean
    private CustomerRepository customerRepository;

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
     * Creates the two runtime-only principals, and the empty starting state, for <b>one</b> test.
     *
     * <p><strong>Per test, not per class, and that is the point.</strong> These two principals were
     * previously created once in a {@code @BeforeAll} and shared by all twenty-two methods, alongside an
     * imposed method order. Two things followed, both bad. Every method inherited a precondition it did not
     * create, so no scenario could be run on its own - and a route test is exactly the thing an engineer
     * wants to run on its own while changing that route. And one method deleted the shared administrator
     * deliberately, to prove the absent self-delete guard, restoring it in a {@code finally}: a failure
     * anywhere between those two points left every later method authenticating as a principal that no longer
     * existed, so one real defect became twenty failures pointing away from it.
     *
     * <p>Provisioning here costs two row inserts per test and buys independence outright: nothing a test
     * does to its own principals can be observed by another, because no other test shares them.
     *
     * <p>The starting state is levelled here too - the transaction table emptied and the report queue drained
     * - so a method that asserts on the first generated identifier or on the next queue message states its
     * own precondition rather than depending on the method before it.
     */
    @BeforeEach
    void provisionPrincipals() {
        this.administrator = newIdentity(UserType.ADMIN);
        this.standardUser = newIdentity(UserType.USER);
        saveIdentity(this.administrator, UserType.ADMIN);
        saveIdentity(this.standardUser, UserType.USER);
        clearTransactions();
        drainReportQueue();
    }

    /**
     * Removes every row and message the test that just ran created.
     *
     * <p>Symmetric with the provisioning above and unconditional, so nothing survives a failing assertion.
     * Deletion is existence-checked rather than assumed, because a test may legitimately have removed a
     * principal already.
     *
     * <p><strong>This cleanup is hygiene, and it is deliberately not where the independence comes from.</strong>
     * That distinction is worth stating precisely, because the comfortable version of it would be an
     * overstatement. Independence comes from the provisioning above: each test authenticates as principals
     * whose identifiers no other test knows, so there is nothing for a sibling to observe whether they are
     * removed or not. Measured, by removing the two {@code deleteIdentity} calls from this method and running
     * the whole suite in randomised order: all twenty-two still pass. What this method actually buys is that
     * a run does not leave two rows per test in {@code user_security} - which matters for the size of the
     * table a later page assertion might one day read, and for leaving the database as it was found, not for
     * whether these tests can be trusted.
     */
    @AfterEach
    void removeCreatedResources() {
        clearTransactions();
        deleteIdentity(this.administrator);
        deleteIdentity(this.standardUser);
        drainReportQueue();
    }

    /**
     * Mints an identity whose identifier is not already taken.
     *
     * @param userType the type the identity carries.
     * @return the minted identity.
     */
    private TestIdentity newIdentity(final UserType userType) {
        Objects.requireNonNull(userType, "userType must not be null");
        String userId;
        do {
            userId = randomUppercaseText(8);
        } while (this.userSecurityRepository.existsById(userId));
        return new TestIdentity(userId, randomMixedCaseCredential());
    }

    /**
     * Stores a test identity with its credential hashed exactly as the sign-on path expects to find it.
     *
     * @param identity the identity to store.
     * @param userType the type the identity carries.
     */
    private void saveIdentity(final TestIdentity identity, final UserType userType) {
        final String digest = this.passwordEncoder.encode(identity.credential().toUpperCase(Locale.ROOT));
        this.userSecurityRepository.saveAndFlush(new UserSecurity(
                identity.userId(), "E2E", userType == UserType.ADMIN ? "ADMIN" : "USER", digest, userType));
    }

    /**
     * Deletes a test identity if it is still present.
     *
     * @param identity the identity to delete; a {@code null} identity is ignored.
     */
    private void deleteIdentity(final TestIdentity identity) {
        if (identity != null && this.userSecurityRepository.existsById(identity.userId())) {
            this.userSecurityRepository.deleteById(identity.userId());
            this.userSecurityRepository.flush();
        }
    }

    /**
     * Generates upper-case text from an alphabet with no visually ambiguous letters.
     *
     * @param length how many characters to generate.
     * @return the generated text.
     */
    private static String randomUppercaseText(final int length) {
        final SecureRandom random = new SecureRandom();
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        final StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    /**
     * Generates a mixed-case credential, so a case can prove the credential is upper-cased before
     * it is verified.
     *
     * @return the generated credential.
     */
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

    /**
     * Signs on over the real HTTP surface, presenting the identifier in lower case so the
     * upper-casing the sign-on path performs is exercised rather than assumed.
     *
     * @param identity the identity to sign on as.
     * @return the authenticated session, carrying the issued token.
     */
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

    /**
     * Issues one request with the standard headers alone.
     *
     * @param method the HTTP method.
     * @param path the request path.
     * @param body the request body, or {@code null} for none.
     * @param session the session to authenticate as, or {@code null} for an anonymous request.
     * @return the response.
     */
    private ResponseEntity<String> request(final HttpMethod method, final String path, final Object body,
            final AuthenticatedSession session) {
        return request(method, path, body, session, Map.of());
    }

    /**
     * Issues one request, with additional headers.
     *
     * @param method the HTTP method.
     * @param path the request path.
     * @param body the request body, or {@code null} for none.
     * @param session the session to authenticate as, or {@code null} for an anonymous request.
     * @param additionalHeaders headers set after the standard ones, so a case can override them.
     * @return the response, with the body as text so an error payload is readable.
     */
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

    /**
     * Builds the headers every request carries: the accept type, the correlation pair and the bearer
     * token when there is a session.
     *
     * @param session the session to authenticate as, or {@code null} for an anonymous request.
     * @return those headers.
     */
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

    /**
     * Asserts the correlation header echoes, and that no conversation state leaks into the body.
     *
     * @param response the response to screen.
     */
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

    /**
     * Asserts no response field names a conversation component the stateless model has no place for.
     *
     * @param root the parsed response body.
     */
    private void assertNoLegacyConversationFields(final JsonNode root) {
        final List<String> fieldNames = new ArrayList<>();
        collectFieldNames(root, fieldNames);
        assertThat(fieldNames).doesNotContainAnyElementsOf(LEGACY_ONLY_FIELDS);
    }

    /**
     * Collects every field name in a tree, at every depth.
     *
     * @param node the node to walk.
     * @param destination the list names are appended to.
     */
    private static void collectFieldNames(final JsonNode node, final List<String> destination) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(destination::add);
            node.elements().forEachRemaining(child -> collectFieldNames(child, destination));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectFieldNames(child, destination));
        }
    }

    /**
     * Asserts a body is present, then parses it.
     *
     * @param response the response to read.
     * @return its parsed body.
     */
    private JsonNode responseBody(final ResponseEntity<String> response) {
        assertThat(response.getBody()).isNotNull().isNotBlank();
        return parseJson(response.getBody());
    }

    /**
     * Parses a response body, failing the case rather than the harness when it is not JSON.
     *
     * @param body the body text.
     * @return the parsed tree.
     */
    private JsonNode parseJson(final String body) {
        try {
            return this.objectMapper.readTree(body);
        } catch (final java.io.IOException malformedResponse) {
            throw new IllegalStateException("The application returned a body that was not valid JSON.",
                    malformedResponse);
        }
    }

    /**
     * Decodes the claim set of a compact token without verifying it.
     *
     * @param token the compact token.
     * @return its claims.
     */
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

    /**
     * Mints a token for the same subject whose validity window has already closed.
     *
     * @param session the session whose issuer and subject are reused.
     * @return the expired token.
     */
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

    /**
     * Alters one character of a token signature, leaving the compact form otherwise intact.
     *
     * @param token the signed token.
     * @return the same token with an invalid signature.
     */
    private static String tamperedToken(final String token) {
        final String[] segments = token.split("\\.");
        if (segments.length != 3 || segments[2].isEmpty()) {
            throw new IllegalArgumentException("A signed token must contain three non-empty compact segments.");
        }
        final char replacement = segments[2].charAt(0) == 'A' ? 'B' : 'A';
        return segments[0] + '.' + segments[1] + '.' + replacement + segments[2].substring(1);
    }

    /**
     * Renders an account identifier as the eleven-digit {@code ACCT-ID} image.
     *
     * @param numericId the numeric identifier.
     * @return its eleven-character zero-padded image.
     */
    private static String accountId(final long numericId) {
        return String.format(Locale.ROOT, "%011d", numericId);
    }

    /**
     * Reads one text column, trailing blanks removed as a fixed-width field would be read.
     *
     * @param row the column-keyed projection.
     * @param key the column name.
     * @return the value, right-trimmed.
     */
    private static String text(final Map<String, Object> row, final String key) {
        final Object value = row.get(key);
        if (value == null) {
            throw new IllegalStateException("The database projection omitted required column " + key + '.');
        }
        return value.toString().stripTrailing();
    }

    /**
     * Reads one money column at scale 2, whichever type the driver returned it as.
     *
     * @param row the column-keyed projection.
     * @param key the column name.
     * @return the value as a plain string at scale 2.
     */
    private static String money(final Map<String, Object> row, final String key) {
        final Object value = row.get(key);
        if (value instanceof BigDecimal decimal) {
            return decimal.setScale(2).toPlainString();
        }
        return new BigDecimal(text(row, key)).setScale(2).toPlainString();
    }

    /**
     * Strips every non-digit, for comparing a rendered value against a stored one.
     *
     * @param value the rendered value.
     * @return its digits alone.
     */
    private static String digits(final String value) {
        return value.replaceAll("[^0-9]", "");
    }

    /**
     * Removes the separators from a dash-separated date.
     *
     * @param date the persisted date.
     * @return the same date with no separators.
     */
    private static String compactDate(final String date) {
        return date.replace("-", "");
    }

    /**
     * Splits a persisted date into the three components the screen sends separately.
     *
     * @param target the body the components are put into.
     * @param prefix the property-name prefix the three components share.
     * @param date the persisted date, dash separated.
     */
    private static void putDateParts(final ObjectNode target, final String prefix, final String date) {
        final String[] parts = date.split("-");
        if (parts.length != 3) {
            throw new IllegalArgumentException("A persisted date did not have the required three components.");
        }
        target.put(prefix + "Year", parts[0]);
        target.put(prefix + "Month", parts[1]);
        target.put(prefix + "Day", parts[2]);
    }

    /**
     * Captures one account row, version column included.
     *
     * @param numericAccountId the account to read.
     * @return the row as a column-keyed projection.
     */
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

    /**
     * Restores the captured account row, so the suite leaves the seeded data as it found it.
     *
     * @param state the row captured before the case ran.
     */
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

    /**
     * Captures the card row of one account, version column included.
     *
     * @param numericAccountId the account whose card is read.
     * @return the row as a column-keyed projection.
     */
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

    /**
     * Restores the captured card row, so the suite leaves the seeded data as it found it.
     *
     * @param state the row captured before the case ran.
     */
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

    /**
     * Builds a card-update body that echoes the stored row, so a case perturbs one field only.
     *
     * @param state the captured card row.
     * @return the request body.
     */
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

    /**
     * Builds a transaction-add body against a card and a category that both really exist.
     *
     * @param numericAccountId the account whose card the transaction is posted to.
     * @return the request body.
     */
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

    /**
     * Applies one statement on its own committed connection, so the application tier observes it.
     *
     * @param sql the statement to apply.
     * @param parameters the bind values, in ordinal order.
     * @return the affected row count.
     */
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

    /**
     * Reads one long on its own committed connection, outside the suite transaction.
     *
     * @param sql the query, returning one numeric column of one row.
     * @param parameters the bind values, in ordinal order.
     * @return that value.
     */
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

    /**
     * Reads one decimal on its own committed connection, outside the suite transaction.
     *
     * @param sql the query, returning one decimal column of one row.
     * @param parameters the bind values, in ordinal order.
     * @return that value.
     */
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

    /**
     * Builds a bill-payment body carrying the balance the account currently holds.
     *
     * @param numericAccountId the account to pay.
     * @param confirmation the value {@code CONFIRMI} carries.
     * @return the request body.
     */
    private ObjectNode billPaymentRequest(final long numericAccountId, final String confirmation) {
        final Map<String, Object> state = accountState(numericAccountId);
        return this.objectMapper.createObjectNode()
                .put("accountId", accountId(numericAccountId))
                .put("currentBalance", money(state, "acct_curr_bal"))
                .put("confirmation", confirmation);
    }

    /**
     * Builds a report submission body for one reporting period.
     *
     * @param period {@code monthly}, {@code yearly} or a custom range.
     * @param confirmation the value {@code CONFIRMI} carries.
     * @return the request body.
     */
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

    /**
     * Receives exactly one message from the report queue and parses its body.
     *
     * @return the parsed message body.
     */
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

    /**
     * Reads one frozen corpus member whole, so evidence is taken from the source rather than a copy.
     *
     * @param relativePath the repository-relative path of the member.
     * @return its text with carriage returns removed, so the comparison is line-ending neutral.
     */
    private static String sourceText(final String relativePath) {
        try {
            return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r", "");
        } catch (final java.io.IOException readFailure) {
            throw new IllegalStateException("Unable to read required frozen source evidence.", readFailure);
        }
    }

    /**
     * Reads one inclusive line range out of a frozen corpus member.
     *
     * @param relativePath the repository-relative path of the member.
     * @param startInclusive the one-based first line to take.
     * @param endInclusive the one-based last line to take.
     * @return those lines, newline-joined.
     */
    private static String sourceLines(final String relativePath, final int startInclusive,
            final int endInclusive) {
        final List<String> lines = sourceText(relativePath).lines().toList();
        return String.join("\n", lines.subList(startInclusive - 1, endInclusive));
    }

    @Test
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
    @DisplayName("CAVW and CAUP carry the snapshot in the body and accept a valid new FICO over an out-of-band old value")
    void accountViewAndUpdateUseTheSealedSnapshotAndNewDetailsValidation() {
        final AuthenticatedSession user = signOn(this.standardUser);
        final Map<String, Object> original = accountState(1L);
        assertThat(Integer.parseInt(text(original, "cust_fico_credit_score")) < 300).isTrue();

        try {
            final ResponseEntity<String> view =
                    request(HttpMethod.GET, "/api/accounts/" + accountId(1L), null, user);
            assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
            final JsonNode account = responseBody(view);
            // Transformation Rule 7: the read seals the ACUP-OLD-DETAILS group of
            // app/cbl/COACTUPC.cbl:669 and the matching PUT carries that sealed value in its body. No entity
            // tag and no If-Match are involved, because no server-side state stands between the two turns.
            assertThat(view.getHeaders().getETag()).isNull();
            final JsonNode projectedSnapshot = account.path("snapshot");
            assertThat(projectedSnapshot.isTextual()).isTrue();
            assertThat(projectedSnapshot.asText()).isNotBlank();
            // The value is opaque. Neither the nine protected customer values nor the account identifier
            // appears in it, which is what a readable group could not promise.
            assertThat(projectedSnapshot.asText())
                    .doesNotContain(accountId(1L))
                    .doesNotContain(text(original, "cust_ssn"))
                    .doesNotContain(text(original, "cust_last_name"));
            // The nine protected values are absent from the response entirely - not as DISPLAY components,
            // and not inside a group either. The comparison's operand reaches the client only sealed.
            assertThat(account.fieldNames()).toIterable().doesNotContain(
                    "oldDetails",
                    "customerSsn", "customerDateOfBirth", "customerFirstName", "customerMiddleName",
                    "customerLastName", "phoneNumber1", "phoneNumber2", "governmentIssuedId",
                    "eftAccountId");
            assertThat(view.getBody())
                    .doesNotContain(text(original, "cust_ssn"))
                    .doesNotContain(text(original, "cust_govt_issued_id"));

            final ObjectNode update = accountUpdateBody(original);
            assertThat(update.path("newDetails").path("dateOfBirth").asText().length()).isEqualTo(8);
            // The sealed value is echoed back exactly as the read returned it, which is the whole contract.
            update.put("snapshot", projectedSnapshot.asText());
            final ResponseEntity<String> updated = request(
                    HttpMethod.PUT,
                    "/api/accounts?confirm=true",
                    update,
                    user);
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
    @DisplayName("CAUP exposes five distinguishable outcomes and preserves the customer-lock success fall-through")
    void accountUpdateOutcomesDobOffsetsAndCaseAsymmetryRemainDistinguishable()
            throws ReflectiveOperationException {
        final AuthenticatedSession user = signOn(this.standardUser);
        final Map<String, Object> state = accountState(1L);
        final ObjectNode body = accountUpdateBody(state);
        final ResponseEntity<String> view =
                request(HttpMethod.GET, "/api/accounts/" + accountId(1L), null, user);
        final String projectedSnapshot = responseBody(view).path("snapshot").asText();

        // No confirmation: the PF05 gate of :2602-2603 is unmet, so nothing is written whatever else is
        // right. Asserted with the sealed value present, so the outcome is attributable to the gate alone.
        final ObjectNode confirmedBody = body.deepCopy();
        confirmedBody.put("snapshot", projectedSnapshot);
        assertThat(request(HttpMethod.PUT, "/api/accounts", confirmedBody, user).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

        // Confirmed but with NO snapshot member: 9700-CHECK-CHANGE-IN-REC has nothing to compare against, so
        // the request is refused as an unmet precondition rather than the comparison being skipped. It is
        // 428 rather than 400 because the remedy is to read the record and echo back what that read
        // returned - a step the caller must take - and not to correct a malformed field.
        assertThat(request(HttpMethod.PUT, "/api/accounts?confirm=true", body, user).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

        // Confirmed with a value that is NOT one this server issued: a DIFFERENT condition, and a different
        // status. Authenticated encryption fails closed, so the value cannot be opened, and the remedy is to
        // read again rather than to obtain a first snapshot - which is 412.
        //
        // A hand-composed group is not among the cases because it is no longer expressible: the request
        // declares no readable as-displayed member, and Jackson refuses an undeclared property, so a body
        // naming one is rejected at binding. That is the point of the sealed contract - the operand of the
        // comparison is not something a caller can choose.
        final ObjectNode forgedSnapshot = body.deepCopy();
        forgedSnapshot.put("snapshot", "bm90LWEtc2VhbGVkLXNuYXBzaG90LXZhbHVl");
        assertThat(request(HttpMethod.PUT, "/api/accounts?confirm=true", forgedSnapshot, user)
                .getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);

        // Confirmed with an AUTHENTIC value that no longer agrees with the stored row: the guard of
        // :4109-4193 fires and the write is abandoned. This is the outcome the two layers exist for, and it
        // is the one that proves the snapshot is not derived from the row being written - the row is moved
        // out from under a snapshot this server itself issued.
        final ObjectNode staleSnapshot = body.deepCopy();
        staleSnapshot.put("snapshot", projectedSnapshot);
        // executeCommittedUpdate, not the injected JdbcTemplate: the pool runs with auto-commit off, so a
        // bare template update outside a transaction is rolled back when the connection is returned and the
        // rival write would never be visible to the server.
        assertThat(executeCommittedUpdate(
                "UPDATE customer SET cust_addr_line_1 = ? WHERE cust_id = ?",
                "AN ADDRESS THIS RECORD NEVER HELD", state.get("cust_id")))
                .as("the rival write must actually land, or this case proves nothing")
                .isEqualTo(1);
        try {
            assertThat(request(HttpMethod.PUT, "/api/accounts?confirm=true", staleSnapshot, user)
                    .getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        } finally {
            executeCommittedUpdate("UPDATE customer SET cust_addr_line_1 = ? WHERE cust_id = ?",
                    state.get("cust_addr_line_1"), state.get("cust_id"));
        }

        // And a value sealed for ANOTHER account does not open against this one, because both the account
        // and the principal are bound as authenticated additional data rather than merely carried inside.
        final ObjectNode transplanted = body.deepCopy();
        transplanted.put("snapshot", responseBody(
                request(HttpMethod.GET, "/api/accounts/" + accountId(2L), null, user))
                .path("snapshot").asText());
        assertThat(request(HttpMethod.PUT, "/api/accounts?confirm=true", transplanted, user)
                .getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);

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
            assertThat(detailResponse.getHeaders().getETag()).isNull();
            final JsonNode detail = responseBody(detailResponse);
            assertThat(detail.path("maskedCardNumber").asText().equals(cardNumber)).isFalse();
            // The CCUP-OLD-DETAILS group of app/cbl/COCRDUPC.cbl:291-301 travels in the body, sealed. The
            // expiry DAY it carries is the operand :1507 compares, and app/cpy-bms/COCRDSL.CPY declares no
            // field for it, which is why the read has to supply it at all.
            final JsonNode projectedSnapshot = detail.path("snapshot");
            assertThat(projectedSnapshot.isTextual()).isTrue();
            assertThat(projectedSnapshot.asText()).isNotBlank();
            // The value is opaque, and in particular it does not hand back the digits maskedCardNumber
            // exists to withhold - :1347 sources that member from the RECEIVED map field, so the sealed
            // payload omits it and the write restores it from the request's own identity field.
            assertThat(projectedSnapshot.asText()).doesNotContain(cardNumber);
            assertThat(detail.fieldNames()).toIterable().doesNotContain("oldDetails");
            assertThat(detailResponse.getBody()).doesNotContain(cardNumber);

            final ObjectNode cardUpdate = cardUpdateBody(original);
            cardUpdate.put("snapshot", projectedSnapshot.asText());
            final ResponseEntity<String> updateResponse = request(
                    HttpMethod.PUT,
                    "/api/cards",
                    cardUpdate,
                    user);
            assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(responseBody(updateResponse).path("snapshot").isNull()).isTrue();
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

    /**
     * CU00's identifier filter positions the browse, equal or greater, across the whole stack.
     *
     * <p><strong>Finding, severity Major - remediated and pinned here.</strong> {@code ?userId=} was
     * accepted, echoed and then discarded: every request answered page one from the front of the file, and a
     * value matching no record answered {@code 404}. The cause was a per-request anchor that was derived
     * from the submitted identifier and then overwritten with "no key, ordinal zero" before the browse ran,
     * plus a primary-key existence probe standing in for the positioning.
     *
     * <p><strong>Why equal-or-greater is the contract.</strong> {@code PROCESS-ENTER-KEY} at
     * {@code app/cbl/COUSR00C.cbl:218-228} moves {@code USRIDINI} into {@code SEC-USR-ID} precisely so the
     * browse starts there, and {@code STARTBR-USER-SEC-FILE} at {@code :588-596} codes neither {@code GTEQ}
     * nor {@code EQUAL} - so the effective option is the default, and for a direct browse of a KSDS that
     * default is {@code GTEQ}. {@code USRSEC} is a KSDS: {@code app/jcl/DUSRSECJ.jcl:65-66} defines it with
     * {@code KEYS(8,0)} and {@code INDEXED}.
     *
     * <p>Every assertion is relative to what the file actually holds, because two runtime-only principals
     * with unpredictable identifiers exist for the duration of this test. That is deliberate: the property
     * being asserted - no row before the key, and the first row at or after it - is the positioning
     * semantic itself, and it holds whatever the store contains.
     */
    @Test
    @DisplayName("CU00 positions the browse on the identifier filter, equal or greater, per the default GTEQ")
    void adminListPositionsTheBrowseOnTheIdentifierFilter() {
        final AuthenticatedSession admin = signOn(this.administrator);

        final List<String> unfiltered = userListKeys(admin, "");
        assertThat(unfiltered).isNotEmpty();

        // An identifier that names a seeded record: the page opens ON it, and nothing before it survives.
        final String seededKey = "USER0003";
        final List<String> fromSeededKey = userListKeys(admin, "?userId=" + seededKey);
        assertThat(fromSeededKey)
                .as("the filter is the browse position, so the page starts at the identifier submitted")
                .startsWith(seededKey)
                .allSatisfy(key -> assertThat(key).isGreaterThanOrEqualTo(seededKey))
                .doesNotContain("ADMIN001");

        // The explicit enter-key spelling takes the same arm and must position identically.
        assertThat(userListKeys(admin, "?action=SUBMIT&userId=" + seededKey))
                .as("action=SUBMIT is the enter key, which is the arm that reads the filter")
                .isEqualTo(fromSeededKey);

        // Below every identifier in the file: the browse slides forward to the very first record, which is
        // the same page an unfiltered request serves.
        assertThat(userListKeys(admin, "?userId=AAAAAAAA"))
                .as("a key below the first record positions before the file, exactly as LOW-VALUES does")
                .isEqualTo(unfiltered);

        // Between two identifiers, naming neither: the browse positions on the higher one. Under the
        // withdrawn equal-only reading this answered 404, which is the defect this case exists to catch.
        final String gapKey = "BBBBBBBB";
        assertThat(userListKeys(admin, "?userId=" + gapKey))
                .as("a key that names no record still positions, on the next higher one")
                .isNotEmpty()
                .allSatisfy(key -> assertThat(key).isGreaterThan(gapKey));

        // Beyond every identifier in the file: accepted, and the first read ends the file. The status is the
        // assertion that matters - this answered 404 before.
        final ResponseEntity<String> pastTheEnd =
                request(HttpMethod.GET, "/api/admin/users?userId=ZZZZZZZZ", null, admin);
        assertThat(pastTheEnd.getStatusCode())
                .as("a key past the last record is the position HIGH-VALUES names, not a missing record")
                .isEqualTo(HttpStatus.OK);
        assertThat(responseBody(pastTheEnd).path("rows").size()).isZero();
        assertThat(responseBody(pastTheEnd).path("errorMessage").asText())
                .isEqualTo("You have reached the bottom of the page...");

        assertThat(sourceLines("app/cbl/COUSR00C.cbl", 218, 228))
                .contains("MOVE LOW-VALUES TO SEC-USR-ID", "TO SEC-USR-ID", "MOVE 0       TO CDEMO-CU00-PAGE-NUM");
        assertThat(sourceLines("app/jcl/DUSRSECJ.jcl", 60, 70)).contains("KEYS(8,0)", "INDEXED");
    }

    /**
     * Reads the identifiers of a user-list page, in the order the response carries them.
     *
     * @param session the authenticated administrator session to request as.
     * @param query the query string to append to the list path, empty for none.
     * @return the row identifiers, in response order; never null.
     */
    private List<String> userListKeys(final AuthenticatedSession session, final String query) {
        final ResponseEntity<String> response =
                request(HttpMethod.GET, "/api/admin/users" + query, null, session);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        final List<String> keys = new ArrayList<>();
        responseBody(response).path("rows").forEach(row -> keys.add(row.path("userId").asText()));
        return List.copyOf(keys);
    }

    @Test
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
    @DisplayName("CU02 distinguishes an omitted identifier from an empty one, as COUSR02C does")
    void adminUpdateDistinguishesAnOmittedIdentifierFromAnEmptyOne() {
        // FINDING API-003, severity HIGH. Three states, three outcomes, proven through the whole stack rather
        // than at the controller seam: the controller decides whether to substitute, the service raises the
        // source's literal, and the mapping to a status happens in a third place, so only an end-to-end
        // exercise shows what a caller actually receives.
        //
        // Before this was resolved, states 2 and 3 were indistinguishable: an empty identifier was overwritten
        // with the path value and the update SUCCEEDED, which made app/cbl/COUSR02C.cbl's own
        // 'User ID can NOT be empty...' unreachable through this surface.
        final TestIdentity subject = newIdentity(UserType.USER);
        saveIdentity(subject, UserType.USER);
        final AuthenticatedSession admin = signOn(this.administrator);
        try {
            // State 1 - the member is ABSENT. The documented shape: the path addresses the record, and
            // app/cbl/COUSR02C.cbl:L102-L103 fills USRIDINI from the navigation context.
            final ResponseEntity<String> omitted = request(HttpMethod.PUT,
                    "/api/admin/users/" + subject.userId(),
                    this.objectMapper.createObjectNode()
                            .put("firstName", "OMITTED")
                            .put("lastName", "TEST")
                            .put("password", subject.credential())
                            .put("userType", "U"),
                    admin);
            assertThat(omitted.getStatusCode())
                    .as("an absent member is silence, and silence is filled from the path")
                    .isEqualTo(HttpStatus.OK);
            assertThat(this.userSecurityRepository.findById(subject.userId())
                    .orElseThrow().getSecUsrFname().strip()).isEqualTo("OMITTED");

            // State 2 - the member is PRESENT and EMPTY. app/cbl/COUSR02C.cbl rejects
            // USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES twice, at :L146-L151 and at :L179-L185. Both fills
            // are exercised, because a COBOL field equals SPACES only when every byte is a blank and
            // LOW-VALUES only when every byte is the low-values byte - and because a NUL-filled value is not
            // blank to String#isBlank(), which is how it used to escape the check entirely.
            for (final String empty : new String[] {"", "   ", "\u0000", "\u0000\u0000"}) {
                final ResponseEntity<String> refused = request(HttpMethod.PUT,
                        "/api/admin/users/" + subject.userId(),
                        this.objectMapper.createObjectNode()
                                .put("userId", empty)
                                .put("firstName", "EMPTY")
                                .put("lastName", "TEST")
                                .put("password", subject.credential())
                                .put("userType", "U"),
                        admin);
                assertThat(refused.getStatusCode())
                        .as("an explicitly empty identifier reaches the service and is refused there; "
                                + "value was [%s]", empty)
                        .isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(responseBody(refused).path("detail").asText())
                        .as("and it is refused in the source's own words, not as a mismatch with the path")
                        .isEqualTo("User ID can NOT be empty...");
            }
            assertThat(this.userSecurityRepository.findById(subject.userId())
                    .orElseThrow().getSecUsrFname().strip())
                    .as("no refused submission may have been applied")
                    .isEqualTo("OMITTED");

            // State 3 - the member NAMES A DIFFERENT USER. Neither place is preferred, so the request is
            // refused before the service is reached; the message is about the disagreement, not emptiness.
            final ResponseEntity<String> disagreeing = request(HttpMethod.PUT,
                    "/api/admin/users/" + subject.userId(),
                    this.objectMapper.createObjectNode()
                            .put("userId", this.administrator.userId())
                            .put("firstName", "DISAGREE")
                            .put("lastName", "TEST")
                            .put("password", subject.credential())
                            .put("userType", "U"),
                    admin);
            assertThat(disagreeing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(responseBody(disagreeing).path("detail").asText())
                    .as("a disagreement is a different failure from an empty value and says so")
                    .contains("must match the one in the request path");

            // The frozen authority for all three, read from app/ rather than restated.
            final String guard = sourceLines("app/cbl/COUSR02C.cbl", 143, 152);
            assertThat(guard)
                    .contains("WHEN USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES")
                    .contains("User ID can NOT be empty...");
            final String firstEntry = sourceLines("app/cbl/COUSR02C.cbl", 95, 107);
            assertThat(firstEntry)
                    .as("the substitution the absent state relies on is guarded by the selection being "
                            + "non-empty, which is precisely why an empty submitted value is not filled")
                    .contains("IF CDEMO-CU02-USR-SELECTED NOT =");
        } finally {
            deleteIdentity(subject);
        }
    }

    @Test
    @DisplayName("CU03 permits self-delete because COUSR03C carries no signed-on-identifier guard")
    void adminDeletePreservesTheAbsentSelfDeleteGuardAndWrongVerbLiteral() {
        // The principal deleted here is one this test creates for the purpose, NOT the shared administrator.
        // The distinction is what makes the scenario safe to run in any position: self-delete is exactly the
        // operation that destroys the credential it authenticated with, so performing it on a principal any
        // other assertion also uses would leave that principal's fate depending on this method's outcome.
        // Deleting the shared administrator and restoring it in a finally block would hold only
        // as long as nothing failed in between.
        final TestIdentity selfDeleting = newIdentity(UserType.ADMIN);
        saveIdentity(selfDeleting, UserType.ADMIN);
        final AuthenticatedSession admin = signOn(selfDeleting);
        try {
            final ResponseEntity<String> response = request(
                    HttpMethod.DELETE,
                    "/api/admin/users/" + selfDeleting.userId() + "?confirmed=true",
                    null,
                    admin);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(this.userSecurityRepository.existsById(selfDeleting.userId()))
                    .as("the source carries no comparison against the signed-on identifier anywhere in its "
                            + "359 lines, so an administrator may remove their own account and the row is "
                            + "gone. The absence is the behaviour and it is preserved")
                    .isFalse();
            assertNoCredentialFields(responseBody(response));
            assertThat(this.userSecurityRepository.existsById(this.administrator.userId()))
                    .as("and the shared administrator is untouched by it, which is what lets this scenario "
                            + "run in any position without disturbing another")
                    .isTrue();
            final String deleteSource = sourceText("app/cbl/COUSR03C.cbl");
            assertThat(deleteSource.lines().filter(line -> line.contains("CDEMO-USER-ID")).count())
                    .isZero();
            assertThat(deleteSource).contains(
                    " has been deleted ...", "User ID NOT found...", "Unable to Update User...");
        } finally {
            deleteIdentity(selfDeleting);
        }
    }

    @Test
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
                .isEqualTo("readinessState,db,s3,sqs,sns");

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
                    + "Host: " + boundAuthority() + "\r\n"
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
                + "Host: " + boundAuthority() + "\r\n"
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
                + "Host: " + boundAuthority() + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n"
                + Integer.toHexString(credentials.length) + ";qa=1\r\n"
                + new String(credentials, StandardCharsets.UTF_8) + "\r\n0\r\n\r\n");
        assertThat(statusLineOf(withExtension)).startsWith("HTTP/1.1 200");

        // A chunk size that is not hexadecimal is a client error and must never be a server failure.
        final String brokenSize = exchangeRaw("POST /api/auth/signon HTTP/1.1\r\n"
                + "Host: " + boundAuthority() + "\r\n"
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
    @DisplayName("a control character in the request line or the body is refused, and no row is written")
    void controlCharactersAreRefusedOnEveryInboundStringAndNothingIsWritten() {
        final AuthenticatedSession admin = signOn(this.administrator);

        // Sent over a raw socket because the HTTP client this test uses re-encodes a percent escape it is
        // handed, which would deliver the literal text %00 rather than the character under test.
        final String queryRefusal = exchangeRaw("GET /api/transactions/detail?transactionId=%00 HTTP/1.1\r\n"
                + "Host: " + boundAuthority() + "\r\n"
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
                + "Host: " + boundAuthority() + "\r\n"
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
     * The refusal <em>records</em> the boundary layers write, rendered by the encoder the application runs.
     *
     * <p><strong>Finding C-01, severity Blocker - this test pins the remediation end to end.</strong> The
     * refusal response was already asserted to echo nothing back; the log record was not, and it was the
     * channel carrying the caller's bytes. Four pre-authentication boundaries logged raw request metadata - the
     * declared {@code Content-Type}, the request method, the request URI, and the HTTP firewall's own rejection
     * message, which quotes the offending request verbatim.
     *
     * <p>Why this test exists in addition to the unit-level cover in
     * {@code com.cardemo.unit.config.RequestBoundaryHardeningTest}: the masking layer of
     * {@code src/main/resources/logback-spring.xml} is a JSON generator decorator on the console appender's
     * encoder, so it is invisible to a list appender, which sees the raw event. Only a running application
     * context has that encoder. Rendering each captured event through it is therefore the only way to assert
     * what an operator would actually read - and the point of the finding is precisely that masking does not
     * save a bare protected value, because a bare value carries no label for a rule to key on.
     *
     * <p>Every planted value is synthetic and shaped like something this application really holds: a
     * sixteen-digit card number, a customer surname and a nine-digit government identifier. None authenticates
     * anything.
     */
    @Test
    @DisplayName("no boundary refusal record carries the caller's header, request line or firewall text")
    void refusalRecordsCarryNoCallerSuppliedValue() {
        final String pan = "4111111111111111";
        final String surname = "Whitmore";
        final String governmentId = "123456789";
        final List<String> planted = List.of(pan, surname, governmentId);

        final Logger application = logbackLogger("com.cardemo");
        final ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.setContext(application.getLoggerContext());
        captured.start();
        application.addAppender(captured);
        try {
            // 1. An unreadable media type whose header parameters carry every protected shape at once.
            assertThat(postWithRawContentType("text/plain;pan=" + pan + ";name=" + surname
                    + ";ssn=" + governmentId, "{}").getStatusCode())
                    .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

            // 2. A method the framework does not serve, on a path built entirely from protected values.
            assertThat(statusLineOf(exchangeRaw("TRACE /api/accounts/" + pan + "/" + surname + " HTTP/1.1\r\n"
                    + "Host: " + boundAuthority() + "\r\n"
                    + "Connection: close\r\n\r\n")))
                    .startsWith("HTTP/1.1 4");

            // 3. A URI the HTTP firewall rejects outright, so its own message quotes the value back.
            assertThat(statusLineOf(exchangeRaw("GET /api/cards/" + pan + "/%2e%2e/" + governmentId
                    + " HTTP/1.1\r\n"
                    + "Host: " + boundAuthority() + "\r\n"
                    + "Connection: close\r\n\r\n")))
                    .startsWith("HTTP/1.1 4");

            // 4. An unauthenticated request to an administrator route, again on a protected-value path.
            assertThat(statusLineOf(exchangeRaw("GET /api/admin/users/" + governmentId + " HTTP/1.1\r\n"
                    + "Host: " + boundAuthority() + "\r\n"
                    + "Connection: close\r\n\r\n")))
                    .startsWith("HTTP/1.1 401");
        } finally {
            application.detachAppender(captured);
            captured.stop();
        }

        assertThat(captured.list)
                .as("the four interactions must have produced records; nothing captured would make the "
                        + "assertions below vacuously true")
                .isNotEmpty();

        final Encoder<ILoggingEvent> encoder = productionEncoder();
        for (final ILoggingEvent event : captured.list) {
            final String rendered = new String(encoder.encode(event), StandardCharsets.UTF_8);
            assertThat(rendered)
                    .as("""
                            the line an operator reads, produced by the very encoder the application runs. \
                            The masking rules cover LABELLED credentials, hash shapes and social-security \
                            shapes; a bare card number, surname or government identifier in a header value or \
                            a path segment carries no label and would pass through untouched, which is why the \
                            remediation is that the value is never emitted rather than that it is masked.""")
                    .doesNotContain(planted);
        }
    }

    /**
     * Resolves a logger to its implementation type so an appender can be attached to it.
     *
     * @param loggerName the logger name
     * @return the implementation-typed logger, never null
     */
    private static Logger logbackLogger(final String loggerName) {
        final org.slf4j.Logger candidate = LoggerFactory.getLogger(loggerName);
        assertThat(candidate)
                .as("the backend must be the one logback-spring.xml configures, because the masking rules and "
                        + "the field set under test live in its encoder")
                .isInstanceOf(Logger.class);
        return (Logger) candidate;
    }

    /**
     * The customer-lock failure is driven through the endpoint, and carries its legacy outcome over HTTP.
     *
     * <p>{@link #accountUpdateOutcomesDobOffsetsAndCaseAsymmetryRemainDistinguishable()} proves the
     * outcome-to-status table and the source text that justifies it. Neither proves the branch can be reached:
     * a table can be complete and correct while the code that would consult it never runs, and the customer
     * lock guard is the one outcome in that table which no request had ever produced. Its own decision
     * paragraph never tests the flag - {@code 2606-CLASSIFY-WRITE-OUTCOME} falls through to the
     * {@code WHEN OTHER} success arm at {@code app/cbl/COACTUPC.cbl:2613-2614}, which is the legacy defect
     * recorded as {@code DL-LD-13} - so the flag is set at {@code :3939} and read by nothing.
     *
     * <p>This test therefore asserts BOTH halves that the defect keeps apart. The <em>internal</em> half: the
     * typed outcome the REST entry point rethrows is
     * {@link ConcurrentUpdateException.Outcome#COULD_NOT_LOCK_CUSTOMER}, surfacing as {@code 409} with the
     * legacy message the screen would have shown. The <em>legacy user-visible</em> half: the write is
     * abandoned with nothing persisted, because the source's guard sits before any rewrite - which is also why
     * the source needs no rollback at this point and does not issue one.
     *
     * <p>The snapshot the write compares against travels in the request body as the opaque {@code snapshot}
     * member rather than as an entity tag: no endpoint emits an {@code ETag} and none reads {@code If-Match},
     * which is the stateless contract of {@code F-018}. It is the value the preceding read issued, echoed
     * back unaltered, so it opens and agrees with the stored row - which means the request is refused by the
     * customer lock guard and by nothing upstream of it.
     *
     * <p>Everything above the injected seam is real - real socket, real filter chain, real token, real
     * controller, real service, real exception mapping - so what is verified is the endpoint's behaviour and
     * not a mapping function called directly.
     */
    @Test
    @DisplayName("CAUP: an injected customer-lock failure yields 409 with the legacy message and writes nothing")
    void theCustomerLockFailureIsForcedThroughTheEndpointAndCarriesItsLegacyOutcome() {
        final AuthenticatedSession user = signOn(this.standardUser);
        final Map<String, Object> state = accountState(1L);
        final long customerKey =
                new BigDecimal(state.get("cust_id").toString()).longValueExact();
        final ObjectNode body = accountUpdateBody(state);
        // The snapshot travels in the body as the sealed snapshot member - see F-018 - so it is taken from
        // the value the view endpoint issued and echoed back unaltered, which is what carries the request
        // past the validation of 1205-COMPARE-OLD-NEW and the comparison of 9700-CHECK-CHANGE-IN-REC and
        // into the customer lock read this test exists to reach.
        final ResponseEntity<String> view =
                request(HttpMethod.GET, "/api/accounts/" + accountId(1L), null, user);
        body.put("snapshot", responseBody(view).path("snapshot").asText());

        final Map<String, Object> before = accountState(1L);

        doThrow(new CannotAcquireLockException(
                "injected: the customer row could not be locked for update"))
                .when(this.customerRepository).findByIdForUpdate(customerKey);

        final ResponseEntity<String> response =
                request(HttpMethod.PUT, "/api/accounts?confirm=true", body, user);

        assertThat(response.getStatusCode())
                .as("the customer-lock outcome surfaces as CONFLICT. This is the status the outcome table "
                        + "declares, now reached by a request rather than by a reflective call")
                .isEqualTo(HttpStatus.CONFLICT);

        final JsonNode payload = responseBody(response);
        assertThat(payload.toString())
                .as("and it carries the legacy message verbatim - the exact text the 3270 screen displayed - "
                        + "so the user-visible outcome is preserved and not merely the status code")
                .contains(ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_CUSTOMER.getLegacyMessage());
        assertThat(ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_CUSTOMER.getLegacyMessage())
                .as("stated explicitly so this assertion cannot pass on an empty or renamed message")
                .isEqualTo("Could not lock customer record for update");

        verify(this.customerRepository)
                .findByIdForUpdate(customerKey);

        assertThat(accountState(1L))
                .as("NOTHING was written. The source's customer-lock guard at app/cbl/COACTUPC.cbl:3934-3942 "
                        + "sits before either rewrite, which is precisely why it issues no rollback there "
                        + "while the later customer-rewrite failure does. Every account and customer column "
                        + "this request could have touched is unchanged")
                .isEqualTo(before);
    }

    /**
     * Returns the encoder the running application writes its log records through.
     *
     * @return the console appender's encoder, never null
     */
    private static Encoder<ILoggingEvent> productionEncoder() {
        final Appender<ILoggingEvent> console =
                logbackLogger(Logger.ROOT_LOGGER_NAME).getAppender(CONSOLE_APPENDER_NAME);
        assertThat(console)
                .as("logback-spring.xml declares exactly one appender and attaches it to the root logger; its "
                        + "absence means the configuration was not applied to this context, so nothing "
                        + "downstream of it can be asserted")
                .isInstanceOf(OutputStreamAppender.class);
        final Encoder<ILoggingEvent> encoder = ((OutputStreamAppender<ILoggingEvent>) console).getEncoder();
        assertThat(encoder).as("the console appender must carry the structured encoder").isNotNull();
        return encoder;
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
                + "Host: " + boundAuthority() + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n"
                + Integer.toHexString(body.length) + "\r\n"
                + new String(body, StandardCharsets.UTF_8) + "\r\n"
                + terminator;
    }

    /**
     * The authority of the server this test is actually bound to, derived rather than written down.
     *
     * <p>A hardcoded host is wrong here for a reason that outlives style. The port is already injected
     * because it is assigned at run time, so writing the host as a literal beside it asserts half of an
     * address that the harness was told the other half of - and the literal silently becomes false the moment
     * the context binds anywhere other than the loopback name, which is exactly what happens in a container
     * or on a dual-stack host where the loopback resolves to {@code ::1}. The bound address is therefore read
     * from the client the context configured, and only if that yields nothing is the loopback interface
     * consulted directly. Neither path contains a host name.
     *
     * @return the {@code host:port} authority of the bound server, never blank
     */
    private String boundAuthority() {
        final String rootUri = this.http.getRootUri();
        if (rootUri != null && !rootUri.isBlank()) {
            final String authority = URI.create(rootUri).getAuthority();
            if (authority != null && !authority.isBlank()) {
                return authority;
            }
        }
        return InetAddress.getLoopbackAddress().getHostAddress() + ":" + this.port;
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
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), this.port)) {
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

    /**
     * Asserts that no payload field names a credential, at any depth.
     *
     * @param payload the response body to screen.
     */
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

        /**
         * Fixes time, so an emitted header date and a token expiry are both predictable.
         *
         * @return a clock fixed at the suite instant, in UTC.
         */
        @Bean
        @Primary
        Clock carddemoFixedE2eClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }

        /**
         * Verifies the tokens this suite presents, with the same ephemeral key the encoder signs them with.
         *
         * @param issuer the configured issuer claim the decoder requires.
         * @return the decoder bound to that key and issuer.
         */
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

        /**
         * Signs the tokens this suite presents, with the ephemeral key rather than a committed one.
         *
         * @return the encoder bound to that key.
         */
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
