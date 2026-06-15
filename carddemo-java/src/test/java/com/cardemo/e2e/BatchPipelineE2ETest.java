/*
 * ============================================================================
 *  CardDemo — Greenfield Java 25 LTS + Spring Boot 3.x Migration
 *  End-to-End (e2e) Parity Test — Full 5-Stage Spring Batch Pipeline
 * ============================================================================
 *
 *  PROVENANCE & TRACEABILITY (AAP §0.7.1 / §0.7.2)
 *  Net-new greenfield test with NO COBOL source equivalent. The observable
 *  behaviour replayed here is translated from the frozen AWS CardDemo COBOL/JCL
 *  baseline at commit SHA 27d6c6f; the COBOL/JCL sources and the
 *  app/data/ASCII/*.txt fixtures are READ-ONLY reference and are NEVER copied
 *  into this repository. Base package is com.cardemo (decision D-006 —
 *  deliberately NOT com.carddemo), matching <groupId>com.cardemo</groupId> in
 *  carddemo-java/pom.xml.
 *
 *  Source artifacts whose behaviour this test pins (reference only):
 *    JCL : app/jcl/POSTTRAN.jcl, INTCALC.jcl, COMBTRAN.jcl, CREASTMT.JCL,
 *          TRANREPT.jcl  (the 5-stage pipeline)
 *    COBOL: app/cbl/CBTRN02C.cbl (posting), CBACT04C.cbl (interest),
 *          CBTRN03C.cbl (report), CBSTM03A.CBL/CBSTM03B (statements)
 *    DATA : app/data/ASCII/dailytran.txt (300 unposted transactions),
 *          discgrp.txt (interest rates incl. DEFAULT), tcatbal.txt
 *          (category balances), acctdata.txt (accounts) and the other Flyway
 *          V3-seeded fixtures.
 * ============================================================================
 */
package com.cardemo.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.batch.jobs.DailyTransactionPostingJob;
import com.cardemo.config.AwsConfig;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.repository.DisclosureGroupRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.DeleteTopicRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Full-pipeline end-to-end parity test for the migrated AWS CardDemo Spring Batch estate — the Java
 * realization of the legacy JCL/JES 5-stage batch pipeline
 * {@code POSTTRAN → INTCALC → COMBTRAN → (CREASTMT ‖ TRANREPT)} (AAP §0.3.1, §0.7.6).
 *
 * <p>This test drives the <strong>real</strong> production {@code Job} beans from their outer boundary (a
 * launched {@code Job}) through processor → reader/writer → repository → entity → <strong>PostgreSQL 16</strong>,
 * plus the AWS <strong>S3</strong> side-effects, against <strong>Testcontainers</strong> (PostgreSQL +
 * LocalStack) using the <strong>9 canonical ASCII fixtures</strong> seeded by Flyway {@code V3} as ground
 * truth. It proves 100% behavioral parity (AAP §0.7.2) of the batch estate with the COBOL/JCL baseline.</p>
 *
 * <h2>Ground-truth principle (CRITICAL — read before editing any assertion)</h2>
 * <p>"9 ASCII fixtures" means <strong>9 FILES, not 9 records</strong>. The tech-spec's inline Gate examples
 * (e.g. "9 account records", "20 transactions") are <em>illustrative</em> and do <strong>not</strong> match
 * the real fixture sizes. Every expectation in this class is therefore <strong>derived from the seeded data
 * and the COBOL semantics</strong>, never transcribed from an illustrative constant:</p>
 * <ul>
 *   <li>Counts are obtained by querying repositories ({@code dailyTransactionRepository.count()} = 300,
 *       {@code cardCrossReferenceRepository} distinct-account count = 50, …), never hardcoded.</li>
 *   <li>Conservation invariants are asserted ({@code posted + rejected == processed}; the per-account
 *       balance increase equals the sum of posted amounts), so the assertion is self-consistent with the
 *       fixtures regardless of their exact contents.</li>
 *   <li>Where a golden monetary value is asserted (e.g. a post-interest balance) it is computed <em>in-test</em>
 *       from the account's seeded/arranged balance and the seeded rate using the same
 *       {@code (bal × rate) / 1200} HALF_EVEN formula CBACT04C uses, so the expectation is derived, not
 *       transcribed.</li>
 * </ul>
 *
 * <h2>Decimal fidelity (AAP §0.7.3)</h2>
 * <p>ALL monetary assertions use {@link BigDecimal} compared with {@link BigDecimal#compareTo(BigDecimal)}
 * (via AssertJ {@code isEqualByComparingTo}) and <strong>never</strong> {@code equals} (which is
 * scale-sensitive). No {@code float}/{@code double} appears anywhere. The interest formula is reproduced
 * WITHOUT algebraic rearrangement as {@code balance.multiply(rate).divide(1200, 2, HALF_EVEN)} (banker's
 * rounding), exactly as CBACT04C {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}.</p>
 *
 * <h2>Why a self-contained harness (NOT extending the sibling {@code AbstractBatchJobIT})</h2>
 * <p>{@code AbstractBatchJobIT} is not part of this file's declared dependency set, so — per the migration's
 * dependency-whitelist discipline and the "self-contained {@code @SpringBootTest}" directive — this class
 * replicates the proven Testcontainers/AWS wiring directly rather than inheriting it. The wiring choices
 * mirror the established sibling convention so the cached Spring context and the AWS surface behave
 * identically.</p>
 *
 * <h2>Why NOT {@code @SpringBatchTest}</h2>
 * <p>The CardDemo context defines <strong>six</strong> {@code Job} beans
 * ({@code dailyTransactionPostingJob}, {@code interestCalculationJob}, {@code combineTransactionsJob},
 * {@code statementGenerationJob}, {@code transactionReportJob}, {@code cardDemoBatchPipelineJob}).
 * {@code @SpringBatchTest} registers a {@code JobLauncherTestUtils} whose {@code setJob(...)} is autowired
 * and only resolves when the context holds a <em>single</em> {@code Job} bean; with six it would fail context
 * load with {@code NoUniqueBeanDefinitionException: ... expected single matching bean but found 6}. Instead
 * each launch builds a {@code JobLauncherTestUtils} <strong>manually</strong> (see
 * {@link #launchJob(Job, JobParameters)}) and the specific job is autowired by bean name. This is a
 * documented technology-specific decision (Minimal Change Clause, AAP §0.7.1).</p>
 *
 * <h2>Why the Testcontainers SINGLETON pattern (NOT {@code @Container})</h2>
 * <p>Both containers are {@code static} singletons started once in a {@code static} initializer and never
 * explicitly stopped (the Testcontainers <em>Ryuk</em> sidecar reaps them at JVM exit). This keeps the
 * mapped ports — and hence the datasource URL / AWS endpoint registered by {@link #registerProperties} —
 * stable, allowing one cached context and one Flyway migration. {@code @Testcontainers} is retained as an
 * intent marker only.</p>
 *
 * <h2>Data source: repository reader mode</h2>
 * <p>The {@code test} profile here selects the repository-backed daily-transaction reader
 * ({@code carddemo.batch.daily-transaction.reader=repository}), so {@code POSTTRAN} reads the 300
 * Flyway-V3-seeded {@code DailyTransaction} rows directly from PostgreSQL — self-contained, with no fragile
 * filesystem path and no 350-byte re-serialization. {@code DailyTransactionPostingJob} injects the single
 * active {@code ItemReader<DailyTransaction>} by type, so this selection flows through BOTH the standalone
 * posting job AND the orchestrator's reused {@code dailyTransactionPostingStep} bean.</p>
 *
 * <h2>Isolation strategy</h2>
 * <p>{@code @TestInstance(PER_CLASS)} + {@code @TestMethodOrder(OrderAnnotation)}. {@link #snapshotCanonicalState()}
 * (in {@code @BeforeAll}) records every account's balances/cycle fields/group id, every category balance,
 * and the canonical set of 300 daily-transaction ids. {@link #restoreCanonicalState()} runs at the start of
 * each scenario to re-establish that exact seed state (delete the transaction master, delete any
 * test-injected daily-transaction rows, restore account + category-balance values, empty the S3 buckets,
 * clear the Spring Batch job repository), so every scenario is independent and order-tolerant.</p>
 *
 * <h2>LocalStack verification (AAP §0.7.7)</h2>
 * <p>Every AWS interaction is verified against LocalStack with zero live credentials; the test creates its
 * own buckets/queue/topic in {@code @BeforeAll} and destroys them in {@code @AfterAll}.</p>
 *
 * @see com.cardemo.CardDemoApplication
 * @see com.cardemo.batch.jobs.BatchPipelineOrchestrator
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        // Flyway provisions only the business tables; the Spring Batch BATCH_* metadata tables must be
        // created by Spring Batch's own initializer (default 'embedded' is a no-op on real PostgreSQL).
        "spring.batch.jdbc.initialize-schema=always"
})
@Import(BatchPipelineE2ETest.SecurityCorsTestConfig.class)
@DisplayName("Batch pipeline e2e — POSTTRAN→INTCALC→COMBTRAN→(CREASTMT‖TRANREPT) parity (CBTRN02C/CBACT04C/CBTRN03C/CBSTM03A)")
class BatchPipelineE2ETest {

    /** Logger used only for non-fatal AWS teardown diagnostics. */
    private static final Logger LOG = LoggerFactory.getLogger(BatchPipelineE2ETest.class);

    /** Pinned LocalStack image tag for reproducible runs (matches the sibling batch IT harness). */
    private static final String LOCALSTACK_IMAGE = "localstack/localstack:3";

    // -------------------------------------------------------------------------
    // Shared, singleton containers (started once for the whole class).
    // -------------------------------------------------------------------------

    /** Singleton PostgreSQL 16 — the relational replacement for the legacy z/OS VSAM KSDS data layer. */
    @SuppressWarnings("resource") // Singleton container intentionally never closed; reaped by Ryuk at JVM exit.
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo");

    /** Singleton LocalStack — S3 (GDG replacement), SQS FIFO (CICS TDQ), SNS (notifications). */
    @SuppressWarnings("resource") // Singleton container intentionally never closed; reaped by Ryuk at JVM exit.
    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
                    .withServices("s3", "sqs", "sns");

    /** S3 client targeting LocalStack (path-style), used purely for out-of-band object assertions. */
    @SuppressWarnings("resource") // Shared static client intentionally not closed; released at JVM exit.
    private static final S3Client S3;

    /** SQS client targeting LocalStack, used for FIFO queue provisioning/teardown. */
    @SuppressWarnings("resource") // Shared static client intentionally not closed; released at JVM exit.
    private static final SqsClient SQS;

    /** SNS client targeting LocalStack, used for topic provisioning/teardown. */
    @SuppressWarnings("resource") // Shared static client intentionally not closed; released at JVM exit.
    private static final SnsClient SNS;

    static {
        // Start the singletons FIRST so the AWS client builders below can read the mapped endpoint.
        POSTGRES.start();
        LOCALSTACK.start();

        S3 = S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                // Path-style is REQUIRED for LocalStack S3 (http://host:port/bucket/key).
                .forcePathStyle(true)
                .build();

        SQS = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();

        SNS = SnsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    // -------------------------------------------------------------------------
    // AWS resource names — MUST match application-test.yml (carddemo.aws.*) and
    // com.cardemo.config.AwsConfig.AwsResourceProperties EXACTLY.
    // -------------------------------------------------------------------------

    /** Batch staging INPUT bucket (sequential PS / GDG input replacement). */
    private static final String BATCH_INPUT_BUCKET = "carddemo-batch-input";

    /** Batch staging OUTPUT bucket (rejects/, systran/, tranrept/ — GDG output replacement). */
    private static final String BATCH_OUTPUT_BUCKET = "carddemo-batch-output";

    /** Generated-statement bucket (CBSTM03A/CBSTM03B → text + HTML statements). */
    private static final String STATEMENTS_BUCKET = "carddemo-statements";

    /** Report-job FIFO queue (CICS TDQ WRITEQ 'JOBS' → SQS FIFO trigger); ".fifo" suffix is mandatory. */
    private static final String REPORT_JOBS_QUEUE = "carddemo-report-jobs.fifo";

    /** Notifications topic (CICS notification messaging → SNS fan-out). */
    private static final String NOTIFICATIONS_TOPIC = "carddemo-notifications";

    /** Resolved URL of the FIFO queue, captured at provisioning for idempotent teardown. */
    private static String reportQueueUrl;

    /** Resolved ARN of the notifications topic, captured at provisioning for idempotent teardown. */
    private static String notificationsTopicArn;

    // -------------------------------------------------------------------------
    // S3 key prefixes written by the production batch writers (derived contracts).
    // -------------------------------------------------------------------------

    /** Reject records (CBTRN02C 2500-WRITE-REJECT-REC → DALYREJS GDG): 430-byte fixed records. */
    private static final String REJECTS_PREFIX = "rejects/";

    /** Stage-2 interest transactions (CBACT04C → SYSTRAN GDG): 350-byte CVTRA05Y records. */
    private static final String SYSTRAN_PREFIX = "systran/";

    /** Stage-4a statement objects (CBSTM03A): statements/<gen>/<accountId>.txt|.html. */
    private static final String STATEMENTS_PREFIX = "statements/";

    /** Stage-4b transaction report (CBTRN03C): 133-char-wide report records. */
    private static final String TRANREPT_PREFIX = "tranrept/";

    // -------------------------------------------------------------------------
    // Decimal / parity constants.
    // -------------------------------------------------------------------------

    /** COBOL monetary scale (PIC ...V99). */
    private static final int MONETARY_SCALE = 2;

    /** Interest-formula divisor — COBOL literal 1200 held as an exact-precision BigDecimal. */
    private static final BigDecimal INTEREST_DIVISOR = new BigDecimal("1200");

    /** The reference transaction type ('Purchase') and category ('Regular Sales Draft') seeded by V3. */
    private static final String REF_TYPE = "01";

    /** The reference transaction category code seeded by V3 for type 01. */
    private static final int REF_CATEGORY = 1;

    /** The literal DEFAULT disclosure group (CBACT04C 1200-A-GET-DEFAULT-INT-RATE fallback). */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /** A disclosure group deliberately ABSENT from the seed, used to force the DEFAULT fallback. */
    private static final String ABSENT_GROUP_ID = "NOSUCHGRP0";

    /** A card number guaranteed absent from CARDXREF → CBTRN02C reject 100 (INVALID CARD NUMBER). */
    private static final String ABSENT_CARD_NUM = "9999999999999999";

    /** 430-byte reject record contract (350 original DALYTRAN payload + 80-byte trailer). */
    private static final int REJECT_RECORD_LENGTH = 430;

    /** Width of the original record payload preceding the reject trailer. */
    private static final int DALYTRAN_PAYLOAD_LENGTH = 350;

    /** Width of the zero-padded reject code at the start of the trailer (String.format("%04d", code)). */
    private static final int REJECT_CODE_WIDTH = 4;

    /** CBTRN03C report record width (PIC X(133); TRANREPT.jcl STEP10R LRECL=133). */
    private static final int REPORT_RECORD_WIDTH = 133;

    /** INTCALC parmDate parameter default (INTCALC.jcl PARM); CBACT04C run date. */
    private static final String PARM_DATE = "2022071800";

    /** A scale-2 zero used by report-total parsing. */
    private static final BigDecimal ZERO_SCALE2 = BigDecimal.ZERO.setScale(MONETARY_SCALE);

    // Report-seeding card numbers (Flyway-seeded CARDXREF rows) and their accounts. Lexically
    // "0500024453765740" < "0923877193247330", so CARD_ACCT_50 sorts BEFORE CARD_ACCT_2 under the report
    // reader's ORDER BY t.tranCardNum — i.e. account 2's card is the LAST card group (the EOF record).
    /** CARDXREF card number that resolves to account 50 (the first card in reader sort order). */
    private static final String CARD_ACCT_50 = "0500024453765740";

    /** CARDXREF card number that resolves to account 2 (the last card in reader sort order). */
    private static final String CARD_ACCT_2 = "0923877193247330";

    /** Account id backing {@link #CARD_ACCT_50}. */
    private static final long ACCT_50 = 50L;

    /** Account id backing {@link #CARD_ACCT_2}. */
    private static final long ACCT_2 = 2L;

    // -------------------------------------------------------------------------
    // Dynamic property wiring (highest precedence; overrides application-test.yml).
    // -------------------------------------------------------------------------

    /**
     * Registers the singleton containers' coordinates so JPA/Flyway target the Testcontainer PostgreSQL
     * and the Spring Cloud AWS clients target LocalStack. Mirrors the proven sibling batch-IT wiring, plus
     * two additions specific to this e2e test: the repository-backed daily-transaction reader selection and
     * an explicit S3 path-style flag.
     *
     * @param registry the Spring-provided registry into which properties are added; never {@code null}.
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // Datasource -> Testcontainer PostgreSQL (override the yml jdbc:tc: defaults). The driver is also
        // overridden because the test profile may pair jdbc:tc: with the Testcontainers driver.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // Disable batch auto-run so THIS test controls launch timing. In Spring Boot 3.5.x the
        // JobLauncherApplicationRunner is gated by spring.batch.job.enabled (matchIfMissing=true); forcing
        // it false guarantees no Job auto-executes on context startup.
        registry.add("spring.batch.job.enabled", () -> "false");

        // POSTTRAN reads the 300 Flyway-V3-seeded DailyTransaction rows from the DB (self-contained); the
        // reader is injected by type, so this flows through the standalone job and the orchestrator alike.
        registry.add("carddemo.batch.daily-transaction.reader", () -> "repository");

        // Spring Cloud AWS -> LocalStack (global endpoint + region + credentials + per-service overrides).
        registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
        registry.add("spring.cloud.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.s3.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
        registry.add("spring.cloud.aws.sqs.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.sns.endpoint", () -> LOCALSTACK.getEndpoint().toString());

        // Keep tracing silent under test (no OTLP collector running).
        registry.add("management.tracing.sampling.probability", () -> "0.0");
    }

    // -------------------------------------------------------------------------
    // Injected production beans. Field injection is acceptable in tests.
    // The six Job beans are autowired BY BEAN NAME (field name = bean name) so the
    // six-Job context resolves each unambiguously (see class Javadoc, "Why NOT @SpringBatchTest").
    // -------------------------------------------------------------------------

    /** Stage-0 master orchestrator (BatchPipelineOrchestrator) — the headline e2e job. */
    @Autowired
    private Job cardDemoBatchPipelineJob;

    /** Stage-1 standalone job (POSTTRAN / CBTRN02C). */
    @Autowired
    private Job dailyTransactionPostingJob;

    /** Stage-2 standalone job (INTCALC / CBACT04C). */
    @Autowired
    private Job interestCalculationJob;

    /** Stage-3 standalone job (COMBTRAN / DFSORT + IDCAMS REPRO). */
    @Autowired
    private Job combineTransactionsJob;

    /** Stage-4a standalone job (CREASTMT / CBSTM03A+CBSTM03B). */
    @Autowired
    private Job statementGenerationJob;

    /** Stage-4b standalone job (TRANREPT / CBTRN03C). */
    @Autowired
    private Job transactionReportJob;

    /** Auto-configured synchronous launcher used to run every job under test. */
    @Autowired
    private JobLauncher jobLauncher;

    /** Persistent (JDBC) job repository backing Spring Batch metadata. */
    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /** Resolves the production bucket/queue/topic names (kept in lock-step with the provisioned resources). */
    @Autowired
    private AwsConfig.AwsResourceProperties awsResourceProperties;

    // -------------------------------------------------------------------------
    // Canonical-state snapshot (taken once, after Flyway has seeded the fixtures).
    // -------------------------------------------------------------------------

    /** Per-account snapshot of the four mutable fields the batch jobs touch. */
    private record AccountSnapshot(BigDecimal currBal, BigDecimal cycCredit, BigDecimal cycDebit, String groupId) {
    }

    /** account id -> canonical mutable-field snapshot. */
    private final Map<Long, AccountSnapshot> accountSnapshots = new HashMap<>();

    /** category-balance id -> canonical balance. */
    private final Map<TransactionCategoryBalanceId, BigDecimal> categoryBalanceSnapshots = new HashMap<>();

    /** The canonical set of Flyway-V3 daily-transaction ids (any row not in this set is test-injected). */
    private final Set<String> canonicalDailyTranIds = new HashSet<>();

    // -------------------------------------------------------------------------
    // AWS resource provisioning / teardown (AAP §0.7.7) + canonical snapshot.
    // -------------------------------------------------------------------------

    /**
     * Provisions the S3 buckets, the SQS FIFO queue, and the SNS topic in LocalStack, then snapshots the
     * Flyway-seeded canonical state. Non-static (legal under {@code @TestInstance(PER_CLASS)}) so it can
     * read the injected repositories for the snapshot. Provisioning is idempotent.
     */
    @BeforeAll
    void provisionAwsAndSnapshot() {
        createBucketIfAbsent(BATCH_INPUT_BUCKET);
        createBucketIfAbsent(BATCH_OUTPUT_BUCKET);
        createBucketIfAbsent(STATEMENTS_BUCKET);

        reportQueueUrl = SQS.createQueue(CreateQueueRequest.builder()
                .queueName(REPORT_JOBS_QUEUE)
                .attributes(Map.of(
                        QueueAttributeName.FIFO_QUEUE, "true",
                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"))
                .build()).queueUrl();

        notificationsTopicArn = SNS.createTopic(CreateTopicRequest.builder()
                .name(NOTIFICATIONS_TOPIC)
                .build()).topicArn();

        snapshotCanonicalState();
    }

    /**
     * Destroys every AWS resource created by {@link #provisionAwsAndSnapshot()}. Each step is wrapped so a
     * teardown failure can never mask a test result. Containers are reaped by Ryuk at JVM exit.
     */
    @AfterAll
    void teardownAws() {
        for (String bucket : new String[] {BATCH_INPUT_BUCKET, BATCH_OUTPUT_BUCKET, STATEMENTS_BUCKET}) {
            try {
                clearBucketObjects(bucket);
                S3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            } catch (RuntimeException e) {
                LOG.warn("AWS teardown: could not delete S3 bucket '{}' (ignored)", bucket, e);
            }
        }
        try {
            if (reportQueueUrl != null) {
                SQS.deleteQueue(DeleteQueueRequest.builder().queueUrl(reportQueueUrl).build());
            }
        } catch (RuntimeException e) {
            LOG.warn("AWS teardown: could not delete SQS queue '{}' (ignored)", REPORT_JOBS_QUEUE, e);
        }
        try {
            if (notificationsTopicArn != null) {
                SNS.deleteTopic(DeleteTopicRequest.builder().topicArn(notificationsTopicArn).build());
            }
        } catch (RuntimeException e) {
            LOG.warn("AWS teardown: could not delete SNS topic '{}' (ignored)", NOTIFICATIONS_TOPIC, e);
        }
    }

    /** Captures the canonical (Flyway-seeded) account, category-balance, and daily-transaction-id state. */
    private void snapshotCanonicalState() {
        accountSnapshots.clear();
        for (Account account : accountRepository.findAll()) {
            accountSnapshots.put(account.getAcctId(), new AccountSnapshot(
                    account.getAcctCurrBal(),
                    account.getAcctCurrCycCredit(),
                    account.getAcctCurrCycDebit(),
                    account.getAcctGroupId()));
        }
        categoryBalanceSnapshots.clear();
        for (TransactionCategoryBalance balance : categoryBalanceRepository.findAll()) {
            categoryBalanceSnapshots.put(balance.getId(), balance.getTranCatBal());
        }
        canonicalDailyTranIds.clear();
        for (DailyTransaction daily : dailyTransactionRepository.findAll()) {
            canonicalDailyTranIds.add(daily.getDalytranId());
        }
    }

    /**
     * Re-establishes the exact canonical seed state before a scenario runs: empties the (unseeded)
     * transaction master, removes any test-injected daily-transaction rows, restores every account's and
     * category balance's mutable fields, empties the three S3 buckets, and clears the Spring Batch job
     * repository. Account/category restores re-read the current managed row (current {@code @Version})
     * before assigning, so a prior run's optimistic-lock version never causes a stale-version save failure.
     */
    private void restoreCanonicalState() {
        // 1) The transaction master is NOT Flyway-seeded; it is owned entirely by these tests.
        transactionRepository.deleteAll();

        // 2) Remove any daily-transaction rows injected by a prior scenario (reject crafts / corrupt rows).
        List<DailyTransaction> injected = new ArrayList<>();
        for (DailyTransaction daily : dailyTransactionRepository.findAll()) {
            if (!canonicalDailyTranIds.contains(daily.getDalytranId())) {
                injected.add(daily);
            }
        }
        if (!injected.isEmpty()) {
            dailyTransactionRepository.deleteAll(injected);
        }

        // 3) Restore account mutable fields (read-current -> set -> save to respect @Version).
        for (Map.Entry<Long, AccountSnapshot> entry : accountSnapshots.entrySet()) {
            accountRepository.findById(entry.getKey()).ifPresent(account -> {
                AccountSnapshot snap = entry.getValue();
                account.setAcctCurrBal(snap.currBal());
                account.setAcctCurrCycCredit(snap.cycCredit());
                account.setAcctCurrCycDebit(snap.cycDebit());
                account.setAcctGroupId(snap.groupId());
                accountRepository.save(account);
            });
        }

        // 4) Category balances. CRITICAL for hermeticity: TransactionWriter's 2700-UPDATE-TCATBAL upsert
        //    (2700-A-CREATE-TCATBAL-REC) CREATES a brand-new category-balance row whenever a posted
        //    transaction's (acct, type, cat) key is absent from the seed (the fixtures seed only the
        //    (acct, 01, 0001) keys, while daily transactions carry many other type/category combinations).
        //    Those created rows are NOT in the canonical snapshot, so a balance-only restore would leave
        //    them behind and they would accumulate across scenarios — inflating categoryBalanceRepository
        //    .count() (observed: 99 instead of 50) and polluting the TCATBAL conservation total. We must
        //    therefore (a) DELETE every row whose key is not in the snapshot, then (b) restore the snapshot
        //    rows' balances. After this the table holds EXACTLY the Flyway-seeded rows at their seeded values.
        final List<TransactionCategoryBalance> createdByPosting = new ArrayList<>();
        for (TransactionCategoryBalance balance : categoryBalanceRepository.findAll()) {
            if (!categoryBalanceSnapshots.containsKey(balance.getId())) {
                createdByPosting.add(balance);
            }
        }
        if (!createdByPosting.isEmpty()) {
            categoryBalanceRepository.deleteAll(createdByPosting);
        }
        for (Map.Entry<TransactionCategoryBalanceId, BigDecimal> entry : categoryBalanceSnapshots.entrySet()) {
            categoryBalanceRepository.findById(entry.getKey()).ifPresent(balance -> {
                balance.setTranCatBal(entry.getValue());
                categoryBalanceRepository.save(balance);
            });
        }

        // 5) Empty the S3 buckets so per-run object counts are unambiguous.
        clearBucketObjects(BATCH_INPUT_BUCKET);
        clearBucketObjects(BATCH_OUTPUT_BUCKET);
        clearBucketObjects(STATEMENTS_BUCKET);

        // 6) Clear Spring Batch metadata so a re-launch cannot collide with a prior JobInstance.
        clearJobRepository();
    }

    // -------------------------------------------------------------------------
    // Spring Batch launch helpers (manual JobLauncherTestUtils — see class Javadoc).
    // -------------------------------------------------------------------------

    /**
     * Launches a fully-wired {@code Job} with the given parameters and returns its terminal execution.
     *
     * @param job    the {@code Job} bean to run; must not be {@code null}.
     * @param params the parameters for this launch (use {@link #uniqueParams(Consumer)} for uniqueness).
     * @return the completed {@link JobExecution} (inspect {@code getStatus()} / {@code getExitStatus()}).
     * @throws Exception if the launch fails.
     */
    private JobExecution launchJob(Job job, JobParameters params) throws Exception {
        JobLauncherTestUtils utils = new JobLauncherTestUtils();
        utils.setJob(job);
        utils.setJobLauncher(jobLauncher);
        utils.setJobRepository(jobRepository);
        return utils.launchJob(params);
    }

    /**
     * Builds a {@link JobParameters} with caller-supplied parameters plus uniqueness keys so each launch
     * yields a distinct {@code JobInstance} (avoiding {@code JobInstanceAlreadyCompleteException}).
     *
     * @param customizer optional callback to add job-specific parameters; may be {@code null}.
     * @return the assembled, always-unique parameters.
     */
    private JobParameters uniqueParams(Consumer<JobParametersBuilder> customizer) {
        JobParametersBuilder builder = new JobParametersBuilder();
        if (customizer != null) {
            customizer.accept(builder);
        }
        builder.addLong("run.id", System.nanoTime());
        builder.addString("requestedAt", Instant.now().toString());
        return builder.toJobParameters();
    }

    /** Removes all Spring Batch execution metadata (spring-batch-test 5.x: constructed from the repository). */
    private void clearJobRepository() {
        new JobRepositoryTestUtils(jobRepository).removeJobExecutions();
    }

    // -------------------------------------------------------------------------
    // S3 helpers (out-of-band object assertions against LocalStack).
    // -------------------------------------------------------------------------

    /** Lists object keys in a bucket filtered by an optional prefix, paginating through all results. */
    private List<String> listKeys(String bucket, String prefix) {
        List<String> keys = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder().bucket(bucket);
            if (prefix != null) {
                request.prefix(prefix);
            }
            if (continuationToken != null) {
                request.continuationToken(continuationToken);
            }
            ListObjectsV2Response response = S3.listObjectsV2(request.build());
            for (S3Object object : response.contents()) {
                keys.add(object.key());
            }
            continuationToken = Boolean.TRUE.equals(response.isTruncated())
                    ? response.nextContinuationToken()
                    : null;
        } while (continuationToken != null);
        return keys;
    }

    /** Counts objects in a bucket whose keys match a prefix. */
    private long countObjects(String bucket, String prefix) {
        return listKeys(bucket, prefix).size();
    }

    /** Fetches an S3 object's bytes (statements/reject records may not be valid UTF-8 if padded). */
    private byte[] getObjectBytes(String bucket, String key) {
        return S3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    /** Fetches an S3 object decoded as UTF-8 (used for human-readable reports/statements). */
    private String getObjectAsString(String bucket, String key) {
        return S3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asUtf8String();
    }

    /** Creates a bucket, tolerating prior existence (idempotent provisioning). */
    private static void createBucketIfAbsent(String bucket) {
        try {
            S3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException alreadyExists) {
            LOG.debug("S3 bucket '{}' already exists; reusing it.", bucket);
        }
    }

    /** Deletes every object in a bucket, paginating so multi-page buckets are fully emptied. */
    private static void clearBucketObjects(String bucket) {
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder().bucket(bucket);
            if (continuationToken != null) {
                request.continuationToken(continuationToken);
            }
            ListObjectsV2Response response = S3.listObjectsV2(request.build());
            for (S3Object object : response.contents()) {
                S3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(object.key()).build());
            }
            continuationToken = Boolean.TRUE.equals(response.isTruncated())
                    ? response.nextContinuationToken()
                    : null;
        } while (continuationToken != null);
    }

    // =====================================================================================================
    // Scenario F (HEADLINE) — full pipeline orchestration: cardDemoBatchPipelineJob (BatchPipelineOrchestrator).
    // Runs FIRST (@Order 1) on the freshly-restored canonical seed. This is the sole coverage of the
    // orchestrator job, so it asserts the four defining behaviours: sequential order, the RC=4
    // "success-with-rejects" PROCEED decider, the Stage-4 parallel split, and overall COMPLETED.
    // =====================================================================================================

    @Test
    @Order(1)
    @DisplayName("Scenario F: cardDemoBatchPipelineJob runs all 5 stages in order; POSTTRAN RC=4 (rejects) PROCEEDs; 4a‖4b split after COMBTRAN")
    void fullPipelineOrchestratesAllStagesAndProceedsThroughRejects() throws Exception {
        restoreCanonicalState();

        // Arrange exactly one GUARANTEED reject (card absent from CARDXREF -> CBTRN02C reject 100), so the
        // POSTTRAN step deterministically completes with the custom COMPLETED_WITH_REJECTS exit (RC=4),
        // which is precisely the condition the postingDecider must treat as PROCEED. The processed count is
        // DERIVED from the seeded rows (300) + this single injected row, never hardcoded.
        dailyTransactionRepository.save(craftDailyTransaction(
                "9000000000000001", ABSENT_CARD_NUM, new BigDecimal("10.00"),
                LocalDate.of(2022, 6, 10).atTime(12, 0)));

        JobExecution execution = launchJob(cardDemoBatchPipelineJob, uniqueParams(b -> b
                .addString("parmDate", PARM_DATE)
                .addString("startDate", "2022-01-01")
                .addString("endDate", "2022-07-06")));

        // Overall pipeline success.
        assertThat(execution.getStatus())
                .as("the full pipeline completes successfully end-to-end").isEqualTo(BatchStatus.COMPLETED);

        // RC=4 PROCEED proof: the POSTTRAN step exited COMPLETED_WITH_REJECTS yet the pipeline proceeded.
        StepExecution posting = stepByName(execution, "dailyTransactionPostingStep");
        assertThat(posting.getExitStatus().getExitCode())
                .as("POSTTRAN reports RC=4 (success-with-rejects) because a reject was arranged")
                .isEqualTo(DailyTransactionPostingJob.EXIT_CODE_COMPLETED_WITH_REJECTS);
        assertThat(posting.getExecutionContext().getLong(DailyTransactionPostingJob.REJECT_COUNT_KEY))
                .as("at least the one arranged reject was counted").isGreaterThanOrEqualTo(1L);

        // All five stages executed (the decider returned PROCEED, not STOP). Stage 4a is two steps.
        assertThat(ranStep(execution, "dailyTransactionPostingStep")).as("Stage 1 ran").isTrue();
        assertThat(ranStep(execution, "interestCalculationStep")).as("Stage 2 ran after PROCEED").isTrue();
        assertThat(ranStep(execution, "combineTransactionsStep")).as("Stage 3 ran").isTrue();
        assertThat(ranStep(execution, "prepareStatementsStep")).as("Stage 4a step 1 ran").isTrue();
        assertThat(ranStep(execution, "generateStatementsStep")).as("Stage 4a step 2 ran").isTrue();
        assertThat(ranStep(execution, "transactionReportStep")).as("Stage 4b ran").isTrue();

        // Sequential dependency chain (§0.7.6 L1067): each stage starts only after its predecessor ends.
        StepExecution interest = stepByName(execution, "interestCalculationStep");
        StepExecution combine = stepByName(execution, "combineTransactionsStep");
        StepExecution prepare = stepByName(execution, "prepareStatementsStep");
        StepExecution generate = stepByName(execution, "generateStatementsStep");
        StepExecution report = stepByName(execution, "transactionReportStep");

        assertThat(posting.getEndTime())
                .as("POSTTRAN completes before INTCALC starts").isBeforeOrEqualTo(interest.getStartTime());
        assertThat(interest.getEndTime())
                .as("INTCALC completes before COMBTRAN starts").isBeforeOrEqualTo(combine.getStartTime());

        // Stage-4 parallel split (§0.7.6 L1073): BOTH 4a and 4b begin only after COMBTRAN completes.
        assertThat(combine.getEndTime())
                .as("COMBTRAN completes before Stage-4a (statements) starts")
                .isBeforeOrEqualTo(prepare.getStartTime());
        assertThat(combine.getEndTime())
                .as("COMBTRAN completes before Stage-4b (report) starts")
                .isBeforeOrEqualTo(report.getStartTime());

        // Within Stage 4a, prepare precedes generate (CREASTMT COND=(0,NE) -> sequential .next()).
        assertThat(prepare.getEndTime())
                .as("prepareStatementsStep precedes generateStatementsStep").isBeforeOrEqualTo(generate.getStartTime());

        // Every stage step completed successfully.
        for (StepExecution step : execution.getStepExecutions()) {
            assertThat(step.getStatus())
                    .as("step '%s' completed", step.getStepName()).isEqualTo(BatchStatus.COMPLETED);
        }
    }

    // =====================================================================================================
    // Scenario A — Stage 1: DailyTransactionPostingJob (POSTTRAN / CBTRN02C).
    // =====================================================================================================

    @Test
    @Order(2)
    @DisplayName("Scenario A: POSTTRAN posts/rejects with conservation; reject codes 100/102/103; RC=4→COMPLETED_WITH_REJECTS; 430-byte S3 rejects; posted side-effects")
    void postingConservesCountsTagsRejectCodesAndUpdatesBalances() throws Exception {
        restoreCanonicalState();

        // Arrange three guaranteed, distinct rejects derived from real fixture data:
        //   100 — card absent from CARDXREF (xref miss, CBTRN02C 1500-A).
        //   102 — valid card, amount far above the account's credit limit (cyc=0 -> creditLimit < amount).
        //   103 — valid card, small amount (no 102), origination AFTER the account's expiration date.
        CardCrossReference xref = anyCrossReference();
        Account account = accountRepository.findById(xref.getXrefAcctId()).orElseThrow();
        BigDecimal overLimitAmount = account.getAcctCreditLimit().add(new BigDecimal("100000.00"));
        LocalDateTime inRangeOrig = LocalDate.of(2022, 6, 10).atTime(12, 0);
        LocalDateTime afterExpiryOrig = account.getAcctExpirationDate().plusDays(1).atTime(12, 0);

        dailyTransactionRepository.save(craftDailyTransaction(
                "9000000000000100", ABSENT_CARD_NUM, new BigDecimal("10.00"), inRangeOrig));
        dailyTransactionRepository.save(craftDailyTransaction(
                "9000000000000102", xref.getXrefCardNum(), overLimitAmount, inRangeOrig));
        dailyTransactionRepository.save(craftDailyTransaction(
                "9000000000000103", xref.getXrefCardNum(), new BigDecimal("1.00"), afterExpiryOrig));

        // GROUND TRUTH: the processed count is DERIVED (300 seeded + 3 arranged), not a magic number.
        long processedCount = dailyTransactionRepository.count();

        JobExecution execution = launchJob(dailyTransactionPostingJob, uniqueParams(null));

        assertThat(execution.getStatus())
                .as("POSTTRAN completes (rejects are NOT failures)").isEqualTo(BatchStatus.COMPLETED);

        StepExecution posting = execution.getStepExecutions().iterator().next();
        long rejectCount = posting.getExecutionContext().getLong(DailyTransactionPostingJob.REJECT_COUNT_KEY);
        long postedCount = transactionRepository.count(); // master was emptied by restoreCanonicalState

        // Conservation (CBTRN02C main loop): every read record is either posted or rejected, exactly once.
        assertThat(postedCount + rejectCount)
                .as("posted + rejected == processed (derived from the seeded+arranged daily transactions)")
                .isEqualTo(processedCount);
        assertThat(rejectCount).as("the three arranged rejects guarantee at least 3 rejects")
                .isGreaterThanOrEqualTo(3L);

        // RC=4: rejects present -> the step exit is COMPLETED_WITH_REJECTS while BatchStatus stays COMPLETED.
        assertThat(posting.getExitStatus().getExitCode())
                .as("WS-REJECT-COUNT > 0 -> MOVE 4 TO RETURN-CODE -> COMPLETED_WITH_REJECTS")
                .isEqualTo(DailyTransactionPostingJob.EXIT_CODE_COMPLETED_WITH_REJECTS);

        // Reject codes (CBTRN02C 1500): 100/102/103 are reachable and correctly tagged; 101 is UNREACHABLE
        // under the FK-consistent seed (every CARDXREF row points at an existing account), so it must NOT
        // appear. Codes are DERIVED by parsing the 430-byte reject trailers.
        Set<Integer> codes = rejectCodesPresent();
        assertThat(codes)
                .as("reject codes 100 (invalid card), 102 (over limit), 103 (after expiration) are all present")
                .contains(RejectCode.INVALID_CARD_NUMBER.getCode(),
                        RejectCode.OVERLIMIT_TRANSACTION.getCode(),
                        RejectCode.TRANSACTION_AFTER_EXPIRATION.getCode());
        assertThat(codes)
                .as("reject 101 (ACCOUNT NOT FOUND) is unreachable under the FK-consistent seed")
                .doesNotContain(RejectCode.ACCOUNT_NOT_FOUND.getCode());

        // 430-byte reject layout (CBTRN02C 2500-WRITE-REJECT-REC = 350 payload + 80 trailer). Validate every
        // record's width, and confirm the invalid-card record carries code 0100 and the absent card number.
        List<String> rejectRecords = readAllRejectRecords();
        assertThat(rejectRecords).as("at least the three arranged rejects were written to S3").isNotEmpty();
        for (String record : rejectRecords) {
            assertThat(record.length())
                    .as("every reject record is 430 bytes (350 payload + 80 trailer): [%s]", record)
                    .isEqualTo(REJECT_RECORD_LENGTH);
        }
        String invalidCardRecord = rejectRecords.stream()
                .filter(r -> rejectCodeOf(r) == RejectCode.INVALID_CARD_NUMBER.getCode())
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a code-100 reject record"));
        assertThat(invalidCardRecord.substring(0, DALYTRAN_PAYLOAD_LENGTH))
                .as("the 350-byte payload of the invalid-card reject carries the absent card number")
                .contains(ABSENT_CARD_NUM);

        // Posted side-effects (CBTRN02C 2700-UPDATE-TCATBAL + 2800-UPDATE-ACCOUNT) asserted as exact
        // conservation invariants derived from the posted master — never illustrative constants:
        //   (i)  Σ(account balance increase) == Σ(posted amounts)   [2800: ADD DALYTRAN-AMT TO ACCT-CURR-BAL]
        //   (ii) Σ(category-balance increase) == Σ(posted amounts)  [2700: ADD DALYTRAN-AMT TO TRAN-CAT-BAL]
        List<BigDecimal> postedAmounts = new ArrayList<>();
        for (Transaction posted : transactionRepository.findAll()) {
            postedAmounts.add(posted.getTranAmt());
        }
        BigDecimal totalPosted = sumScale2(postedAmounts);
        assertThat(postedCount).as("at least one of the 300 seeded daily transactions posted").isPositive();

        BigDecimal accountIncrease = currentAccountBalanceTotal().subtract(canonicalAccountBalanceTotal());
        assertThat(accountIncrease)
                .as("2800-UPDATE-ACCOUNT: total account-balance increase equals the total posted amount")
                .isEqualByComparingTo(totalPosted);

        BigDecimal categoryIncrease = currentCategoryBalanceTotal().subtract(canonicalCategoryBalanceTotal());
        assertThat(categoryIncrease)
                .as("2700-UPDATE-TCATBAL: total category-balance increase equals the total posted amount")
                .isEqualByComparingTo(totalPosted);
    }

    // =====================================================================================================
    // Scenario B — Stage 2: InterestCalculationJob (INTCALC / CBACT04C).
    // The seeded TCATBAL balances are all 0.00, so a raw run yields zero interest. This scenario therefore
    // ARRANGES two non-zero category balances (one on an own-group account, one on a DEFAULT-fallback
    // account) and DERIVES the expected interest from the seeded rate using the exact COBOL formula.
    // =====================================================================================================

    @Test
    @Order(3)
    @DisplayName("Scenario B: INTCALC applies (bal×rate)/1200 HALF_EVEN, uses the DEFAULT-group fallback, rolls up to the account zeroing cycle buckets, generates no fees, and stages SYSTRAN to S3")
    void interestCalculationAppliesFormulaDefaultFallbackAndRollup() throws Exception {
        restoreCanonicalState();

        List<TransactionCategoryBalance> categoryBalances = categoryBalanceRepository.findAll();
        assertThat(categoryBalances).as("TCATBAL must be Flyway-seeded").hasSizeGreaterThanOrEqualTo(2);

        // --- Own-group account A: set a known non-zero category balance and prove the cycle buckets zero.
        TransactionCategoryBalance tcatA = categoryBalances.get(0);
        Long acctIdA = tcatA.getId().getAcctId();
        tcatA.setTranCatBal(new BigDecimal("1000.00"));
        categoryBalanceRepository.save(tcatA);

        Account accountA = accountRepository.findById(acctIdA).orElseThrow();
        BigDecimal balanceBeforeA = accountA.getAcctCurrBal();
        // Seed non-zero cycle buckets so the 1050-UPDATE-ACCOUNT zeroing is observable (they are NOT folded
        // into the balance — CBACT04C only adds WS-TOTAL-INT and MOVEs 0 to the two cycle fields).
        accountA.setAcctCurrCycCredit(new BigDecimal("50.00"));
        accountA.setAcctCurrCycDebit(new BigDecimal("20.00"));
        accountRepository.save(accountA);
        BigDecimal rateA = resolveInterestRate(accountA.getAcctGroupId());
        BigDecimal interestA = expectedMonthlyInterest(new BigDecimal("1000.00"), rateA);

        // --- DEFAULT-fallback account D: reassign to a group ABSENT from DISCGRP so CBACT04C
        // 1200-A-GET-DEFAULT-INT-RATE fires; set a different non-zero balance.
        TransactionCategoryBalance tcatD = categoryBalances.get(1);
        Long acctIdD = tcatD.getId().getAcctId();
        assertThat(acctIdD).as("the fixture provides distinct accounts per TCATBAL row").isNotEqualTo(acctIdA);
        tcatD.setTranCatBal(new BigDecimal("2000.00"));
        categoryBalanceRepository.save(tcatD);

        Account accountD = accountRepository.findById(acctIdD).orElseThrow();
        BigDecimal balanceBeforeD = accountD.getAcctCurrBal();
        accountD.setAcctGroupId(ABSENT_GROUP_ID);
        accountRepository.save(accountD);
        BigDecimal rateD = resolveInterestRate(ABSENT_GROUP_ID); // resolves via DEFAULT
        BigDecimal interestD = expectedMonthlyInterest(new BigDecimal("2000.00"), rateD);

        long categoryBalanceCount = categoryBalanceRepository.count();

        JobExecution execution = launchJob(interestCalculationJob, uniqueParams(b -> b.addString("parmDate", PARM_DATE)));
        assertThat(execution.getStatus()).as("INTCALC completes").isEqualTo(BatchStatus.COMPLETED);

        // Interest formula fidelity (own group): balance increased by exactly (1000 × rate)/1200 HALF_EVEN.
        Account postA = accountRepository.findById(acctIdA).orElseThrow();
        assertThat(postA.getAcctCurrBal())
                .as("CBACT04C 1050: ACCT-CURR-BAL += WS-TOTAL-INT (= (1000.00 × %s)/1200 HALF_EVEN = %s)", rateA, interestA)
                .isEqualByComparingTo(balanceBeforeA.add(interestA));
        // Roll-up zeroing of the cycle buckets (MOVE 0 TO ACCT-CURR-CYC-CREDIT / ACCT-CURR-CYC-DEBIT).
        assertThat(postA.getAcctCurrCycCredit())
                .as("1050-UPDATE-ACCOUNT zeroes ACCT-CURR-CYC-CREDIT").isEqualByComparingTo(ZERO_SCALE2);
        assertThat(postA.getAcctCurrCycDebit())
                .as("1050-UPDATE-ACCOUNT zeroes ACCT-CURR-CYC-DEBIT").isEqualByComparingTo(ZERO_SCALE2);

        // DEFAULT-group fallback: account D used the DEFAULT rate (a non-zero, correctly-derived interest;
        // had the fallback NOT fired, CBACT04C would abend and the job would FAIL, not COMPLETE).
        Account postD = accountRepository.findById(acctIdD).orElseThrow();
        assertThat(postD.getAcctCurrBal())
                .as("CBACT04C 1200-A: DEFAULT-group rate used -> balance += (2000.00 × %s)/1200 = %s", rateD, interestD)
                .isEqualByComparingTo(balanceBeforeD.add(interestD));

        // Whole-portfolio conservation: total balance increase equals the sum of the two computed interests
        // (the other 48 accounts have a 0.00 category balance -> 0.00 interest, derived not assumed).
        BigDecimal expectedTotalInterest = interestA.add(interestD);
        assertThat(currentAccountBalanceTotal().subtract(canonicalAccountBalanceTotal()))
                .as("total interest rolled into accounts equals interestA + interestD")
                .isEqualByComparingTo(expectedTotalInterest);

        // No fees (CBACT04C 1400-COMPUTE-FEES is an empty stub): the ONLY S3 SYSTRAN objects are the interest
        // transactions — exactly one per category-balance row (rate is non-zero for every seeded row), so the
        // SYSTRAN object count equals the TCATBAL row count, with no extra fee objects.
        assertThat(countObjects(BATCH_OUTPUT_BUCKET, SYSTRAN_PREFIX))
                .as("one SYSTRAN interest object per TCATBAL row, no fee objects (1400 is a stub)")
                .isEqualTo(categoryBalanceCount);

        // Interest transactions are STAGED to S3 only — they are NOT merged into the master until COMBTRAN.
        assertThat(transactionRepository.count())
                .as("INTCALC stages to SYSTRAN/S3; the transaction master stays empty until Stage 3 (COMBTRAN)")
                .isZero();
    }

    // =====================================================================================================
    // Scenario C — Stage 3: CombineTransactionsJob (COMBTRAN / DFSORT + IDCAMS REPRO).
    // Runs INTCALC first to stage SYSTRAN, then COMBTRAN to merge it into the master. Asserts the merge is
    // complete, by-primary-key (no duplicates), and idempotent on re-run (REPRO last-write-wins).
    // =====================================================================================================

    @Test
    @Order(4)
    @DisplayName("Scenario C: COMBTRAN merges the Stage-2 SYSTRAN interest transactions into the master by primary key — complete, ascending, with no duplicate keys and idempotent on re-run")
    void combineMergesSystranIntoMasterByKeyWithoutDuplicates() throws Exception {
        restoreCanonicalState();

        // Stage 2 produces the SYSTRAN half of the COMBTRAN SORTIN; the master (BKUP half) is empty here.
        JobExecution interest = launchJob(interestCalculationJob, uniqueParams(b -> b.addString("parmDate", PARM_DATE)));
        assertThat(interest.getStatus()).as("INTCALC (prerequisite) completes").isEqualTo(BatchStatus.COMPLETED);

        Set<String> systranIds = systranTranIds();
        assertThat(systranIds)
                .as("INTCALC staged one SYSTRAN object per TCATBAL row").hasSize((int) categoryBalanceRepository.count());
        assertThat(transactionRepository.count())
                .as("interest transactions are not in the master before COMBTRAN").isZero();

        JobExecution combine = launchJob(combineTransactionsJob, uniqueParams(null));
        assertThat(combine.getStatus()).as("COMBTRAN completes").isEqualTo(BatchStatus.COMPLETED);

        // Merge completeness: every SYSTRAN transaction id is now present in the master, and the master holds
        // exactly that set — no extra rows, no dropped rows (IDCAMS REPRO of the sorted SORTIN union).
        List<String> masterIds = new ArrayList<>();
        for (Transaction tran : transactionRepository.findAll()) {
            masterIds.add(tran.getTranId());
        }
        assertThat(masterIds)
                .as("the master contains exactly the merged SYSTRAN ids (no duplicates, none dropped)")
                .containsExactlyInAnyOrderElementsOf(systranIds)
                .doesNotHaveDuplicates();

        // Ascending-by-id fidelity (SORT FIELDS=(TRAN-ID,A)): the merged id set sorted ascending equals the
        // natural-ordering of the SYSTRAN ids (the comparator COMBTRAN applies is String-ascending on tranId).
        List<String> ascendingExpected = new ArrayList<>(systranIds);
        Collections.sort(ascendingExpected);
        List<String> ascendingActual = new ArrayList<>(masterIds);
        Collections.sort(ascendingActual);
        assertThat(ascendingActual)
                .as("COMBTRAN orders the union ascending by the 16-char TRAN-ID").isEqualTo(ascendingExpected);

        // REPRO idempotence: re-running COMBTRAN re-reads the same SYSTRAN (it is not deleted) and re-merges
        // by primary key, so the master row count is unchanged — proving merge-by-PK (no duplicate inserts).
        long countAfterFirstMerge = transactionRepository.count();
        JobExecution combineAgain = launchJob(combineTransactionsJob, uniqueParams(null));
        assertThat(combineAgain.getStatus()).as("re-run COMBTRAN completes").isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionRepository.count())
                .as("saveAll merges by primary key: a second COMBTRAN adds no duplicate rows")
                .isEqualTo(countAfterFirstMerge);
    }

    // =====================================================================================================
    // Scenario D — Stage 4a: StatementGenerationJob (CREASTMT / CBSTM03A + CBSTM03B).
    // Posts transactions first so statements carry content, then asserts the two-step sequence and the dual
    // text+HTML S3 output — one object per DISTINCT cross-reference account (the statement S3 key stem).
    // =====================================================================================================

    @Test
    @Order(5)
    @DisplayName("Scenario D: CREASTMT runs prepare→generate and emits a dual text (LRECL 80) + HTML (LRECL 100) statement per distinct XREF account to S3")
    void statementGenerationEmitsDualFormatPerXrefAccount() throws Exception {
        restoreCanonicalState();

        // Post the daily transactions so the generated statements carry transaction content.
        JobExecution posting = launchJob(dailyTransactionPostingJob, uniqueParams(null));
        assertThat(posting.getStatus()).as("POSTTRAN (prerequisite) completes").isEqualTo(BatchStatus.COMPLETED);

        JobExecution execution = launchJob(statementGenerationJob, uniqueParams(null));
        assertThat(execution.getStatus()).as("CREASTMT completes").isEqualTo(BatchStatus.COMPLETED);

        // Two-step sequence (COND=(0,NE) -> default .next()): prepare cleanup precedes generation.
        StepExecution prepare = stepByName(execution, "prepareStatementsStep");
        StepExecution generate = stepByName(execution, "generateStatementsStep");
        assertThat(prepare.getEndTime())
                .as("prepareStatementsStep runs before generateStatementsStep").isBeforeOrEqualTo(generate.getStartTime());

        // One statement per CARD in the XREF, keyed in S3 by account id -> object count == DISTINCT accounts.
        long distinctXrefAccounts = cardCrossReferenceRepository.findAll().stream()
                .map(CardCrossReference::getXrefAcctId).distinct().count();
        List<String> textKeys = listKeys(STATEMENTS_BUCKET, STATEMENTS_PREFIX).stream()
                .filter(k -> k.endsWith(".txt")).collect(Collectors.toList());
        List<String> htmlKeys = listKeys(STATEMENTS_BUCKET, STATEMENTS_PREFIX).stream()
                .filter(k -> k.endsWith(".html")).collect(Collectors.toList());
        assertThat((long) textKeys.size())
                .as("one .txt statement per distinct XREF account").isEqualTo(distinctXrefAccounts);
        assertThat((long) htmlKeys.size())
                .as("one .html statement per distinct XREF account").isEqualTo(distinctXrefAccounts);

        // Content shape: sample the statement of an account that received a posted transaction.
        Transaction samplePosted = transactionRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new AssertionError("expected at least one posted transaction"));
        Long sampleAccountId = cardToAccountMap().get(samplePosted.getTranCardNum());
        assertThat(sampleAccountId).as("the posted transaction's card resolves to an account").isNotNull();

        String textKey = textKeys.stream().filter(k -> k.endsWith("/" + sampleAccountId + ".txt")).findFirst()
                .orElseThrow(() -> new AssertionError("expected a .txt statement for account " + sampleAccountId));
        String textBody = getObjectAsString(STATEMENTS_BUCKET, textKey);
        assertThat(textBody).as("statement carries the account id header").contains(String.valueOf(sampleAccountId));
        assertThat(textBody.split("\n", -1).length)
                .as("a statement with transactions spans multiple lines (header + transaction lines)").isGreaterThan(1);
        for (String line : textBody.split("\n", -1)) {
            assertThat(line.length()).as("text statement records are LRECL 80 (FD-STMTFILE-REC PIC X(80)): [%s]", line)
                    .isEqualTo(80);
        }

        String htmlKey = htmlKeys.stream().filter(k -> k.endsWith("/" + sampleAccountId + ".html")).findFirst()
                .orElseThrow(() -> new AssertionError("expected a .html statement for account " + sampleAccountId));
        String htmlBody = getObjectAsString(STATEMENTS_BUCKET, htmlKey);
        assertThat(htmlBody).as("HTML statement contains markup").contains("<");
        for (String line : htmlBody.split("\n", -1)) {
            assertThat(line.length()).as("HTML statement records are LRECL 100 (FD-HTMLFILE-REC PIC X(100)): [%s]", line)
                    .isEqualTo(100);
        }
    }

    // =====================================================================================================
    // Scenario E — Stage 4b: TransactionReportJob (TRANREPT / CBTRN03C). The single most parity-sensitive
    // stage. Posting stamps tranProcTs = now() (~run time), which is OUTSIDE the report window, so these
    // tests SEED their own in-range transactions (reportTxn) and assert the subtle CBTRN03C closing edges:
    // inclusive date filter, card-ordered details, page totals every 20 lines, the EOF retained-record
    // re-add (double-count), and the ABSENCE of a final account-totals line at EOF.
    // =====================================================================================================

    @Test
    @Order(6)
    @DisplayName("Scenario E1: report date filter is inclusive on BOTH boundaries; out-of-range rows are excluded")
    void reportDateFilterIsInclusiveOnBothBoundaries() throws Exception {
        restoreCanonicalState();
        transactionRepository.saveAll(List.of(
                reportTxn(1, CARD_ACCT_50, "2022-02-28", "999.99"),  // one day BEFORE start -> EXCLUDED
                reportTxn(2, CARD_ACCT_50, "2022-03-01", "100.00"),  // exactly ON start     -> INCLUDED
                reportTxn(3, CARD_ACCT_50, "2022-03-15", "200.00"),  // mid-window           -> INCLUDED
                reportTxn(4, CARD_ACCT_2, "2022-03-31", "50.00"),    // exactly ON end       -> INCLUDED (last card)
                reportTxn(5, CARD_ACCT_2, "2022-04-01", "888.88"))); // one day AFTER end    -> EXCLUDED

        JobExecution execution = launchReport("2022-03-01", "2022-03-31");
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readReportLines();
        assertThat(collect(lines, BatchPipelineE2ETest::isDetail))
                .as("only the three in-range transactions produce detail lines (both boundaries inclusive)")
                .hasSize(3);
        String joined = String.join("\n", lines);
        assertThat(joined).as("the before-window amount never appears").doesNotContain("999.99");
        assertThat(joined).as("the after-window amount never appears").doesNotContain("888.88");
        // Grand total = sum(in-range 100+200+50) + EOF re-add of the last in-range amount (50.00, card 2).
        assertThat(grandTotalValue(lines))
                .as("grand total reflects only in-range amounts plus the EOF retained-record re-add")
                .isEqualByComparingTo(new BigDecimal("400.00"));
    }

    @Test
    @Order(7)
    @DisplayName("Scenario E2: details are ordered by card number; exactly one account-break subtotal that resets (1120)")
    void reportOrdersByCardAndEmitsResettingAccountBreakTotal() throws Exception {
        restoreCanonicalState();
        // Interleaved insertion order to prove the reader's ORDER BY card (not insertion order) governs.
        transactionRepository.saveAll(List.of(
                reportTxn(10, CARD_ACCT_2, "2022-06-15", "10.00"),
                reportTxn(11, CARD_ACCT_50, "2022-06-15", "100.00"),
                reportTxn(12, CARD_ACCT_2, "2022-06-15", "20.00"),
                reportTxn(13, CARD_ACCT_50, "2022-06-15", "200.00"),
                reportTxn(14, CARD_ACCT_2, "2022-06-15", "30.00")));

        JobExecution execution = launchReport("2022-01-01", "2022-12-31");
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readReportLines();
        // Card-sorted order: card 0500...(acct 50) precedes card 0923...(acct 2) -> [50,50,2,2,2].
        assertThat(detailAccountIds(lines))
                .as("detail lines ordered by ascending card number (reader ORDER BY t.tranCardNum)")
                .containsExactly(ACCT_50, ACCT_50, ACCT_2, ACCT_2, ACCT_2);

        List<String> accountTotals = collect(lines, BatchPipelineE2ETest::isAccountTotal);
        assertThat(accountTotals)
                .as("exactly one account-totals line, on the single 50 -> 2 card break (1120)").hasSize(1);
        assertThat(parseTotalAmount(accountTotals.get(0)))
                .as("the account subtotal equals card 50's running total (100.00 + 200.00); it then RESETS")
                .isEqualByComparingTo(new BigDecimal("300.00"));

        // Positional proof the subtotal RESETS at the break: every detail before the account line is card 50,
        // every detail after it is card 2 (so the subtotal carried only card 50's rows, never 360.00).
        int accountTotalIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (isAccountTotal(lines.get(i))) {
                accountTotalIdx = i;
                break;
            }
        }
        assertThat(accountTotalIdx).as("the account-totals line must be present").isGreaterThanOrEqualTo(0);
        for (int i = 0; i < lines.size(); i++) {
            if (isDetail(lines.get(i))) {
                long acct = detailAccountId(lines.get(i));
                if (i < accountTotalIdx) {
                    assertThat(acct).as("detail before the break belongs to card 50").isEqualTo(ACCT_50);
                } else {
                    assertThat(acct).as("detail after the break belongs to card 2").isEqualTo(ACCT_2);
                }
            }
        }
    }

    @Test
    @Order(8)
    @DisplayName("Scenario E3: page totals flush every 20 detail lines (WS-PAGE-SIZE) and roll up into the grand total")
    void reportFlushesPageTotalsEveryTwentyLines() throws Exception {
        restoreCanonicalState();
        // 25 single-card in-range rows of equal amount -> a mid-run page flush at line 20, then 5 more.
        List<Transaction> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rows.add(reportTxn(100 + i, CARD_ACCT_50, "2022-06-15", "10.00"));
        }
        transactionRepository.saveAll(rows);

        JobExecution execution = launchReport("2022-01-01", "2022-12-31");
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readReportLines();
        assertThat(collect(lines, BatchPipelineE2ETest::isDetail))
                .as("all 25 in-range rows produce detail lines").hasSize(25);
        // The EOF flush always yields one page-totals line; >= 2 proves the 20-line boundary flush also fired.
        assertThat(collect(lines, BatchPipelineE2ETest::isPageTotal))
                .as("page-totals line at the 20-line boundary plus the EOF page-totals flush")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(collect(lines, BatchPipelineE2ETest::isNameHeader))
                .as("headers re-emitted at the page boundary (initial block + boundary block)")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(collect(lines, BatchPipelineE2ETest::isAccountTotal))
                .as("a single card produces no card break, hence no account-totals line").isEmpty();
        // grand = sum(25 × 10.00) + EOF re-add of the last 10.00 = 250.00 + 10.00 = 260.00.
        assertThat(grandTotalValue(lines))
                .as("page totals reset after each flush and accumulate into the grand total")
                .isEqualByComparingTo(new BigDecimal("260.00"));
    }

    @Test
    @Order(9)
    @DisplayName("Scenario E4: EOF re-adds the last in-range amount, double-counting it in the grand total (CBTRN03C READ INTO quirk)")
    void reportEofReAddDoubleCountsLastAmount() throws Exception {
        restoreCanonicalState();
        // The last card (0923..., highest) holds a single distinctive amount, so the double-count is unambiguous.
        transactionRepository.saveAll(List.of(
                reportTxn(30, CARD_ACCT_50, "2022-06-15", "100.00"),
                reportTxn(31, CARD_ACCT_50, "2022-06-15", "150.00"),
                reportTxn(32, CARD_ACCT_50, "2022-06-15", "250.00"),
                reportTxn(33, CARD_ACCT_2, "2022-06-15", "333.33"))); // LAST record in reader order

        JobExecution execution = launchReport("2022-01-01", "2022-12-31");
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readReportLines();
        assertThat(collect(lines, BatchPipelineE2ETest::isDetail))
                .as("all four in-range rows produce detail lines").hasSize(4);
        // sum(all in-range) = 833.33; EOF re-add adds the last amount (333.33) once more => 1166.66.
        BigDecimal expectedGrand = sum("100.00", "150.00", "250.00", "333.33").add(new BigDecimal("333.33"));
        assertThat(expectedGrand)
                .as("self-check: 833.33 + 333.33 (EOF re-add of the last record) = 1166.66")
                .isEqualByComparingTo(new BigDecimal("1166.66"));
        assertThat(grandTotalValue(lines))
                .as("grand total double-counts the last in-range amount per the CBTRN03C EOF ADD TRAN-AMT")
                .isEqualByComparingTo(expectedGrand);
    }

    @Test
    @Order(10)
    @DisplayName("Scenario E5: EOF writes page+grand totals but NO final account-totals line (missing 1120 at EOF)")
    void reportWritesNoFinalAccountTotalsLineAtEof() throws Exception {
        restoreCanonicalState();
        transactionRepository.saveAll(List.of(
                reportTxn(40, CARD_ACCT_50, "2022-06-15", "40.00"),
                reportTxn(41, CARD_ACCT_50, "2022-06-15", "60.00"),
                reportTxn(42, CARD_ACCT_2, "2022-06-15", "25.00"))); // last card; no account line at EOF

        JobExecution execution = launchReport("2022-01-01", "2022-12-31");
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readReportLines();
        // Exactly one account-totals line, for the non-last card (account 50), value 40.00 + 60.00 = 100.00.
        List<String> accountTotals = collect(lines, BatchPipelineE2ETest::isAccountTotal);
        assertThat(accountTotals)
                .as("only the non-last card (account 50) emits an account-totals line, at its break").hasSize(1);
        assertThat(parseTotalAmount(accountTotals.get(0)))
                .as("the single account-totals line is account 50's subtotal").isEqualByComparingTo(new BigDecimal("100.00"));
        // The last card's would-be subtotal (raw 25.00 or EOF-re-added 50.00) is NEVER written as a total line.
        assertThat(parseTotalAmount(accountTotals.get(0))).isNotEqualByComparingTo(new BigDecimal("25.00"));
        assertThat(parseTotalAmount(accountTotals.get(0))).isNotEqualByComparingTo(new BigDecimal("50.00"));

        // After the final detail line there must be NO account-totals line — only EOF page+grand totals.
        int lastDetailIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (isDetail(lines.get(i))) {
                lastDetailIdx = i;
            }
        }
        assertThat(lastDetailIdx).as("at least one detail line must exist").isGreaterThanOrEqualTo(0);
        for (int i = lastDetailIdx + 1; i < lines.size(); i++) {
            assertThat(isAccountTotal(lines.get(i)))
                    .as("no account-totals line may follow the final detail at EOF (missing 1120): [%s]", lines.get(i))
                    .isFalse();
        }
        assertThat(collect(lines, BatchPipelineE2ETest::isGrandTotal))
                .as("EOF still writes the grand-totals line (1110-WRITE-GRAND-TOTALS)").hasSize(1);
    }

    @Test
    @Order(11)
    @DisplayName("Scenario E6: an empty in-range result completes with an empty report (no detail, no totals)")
    void reportEmptyRangeCompletesWithEmptyReport() throws Exception {
        restoreCanonicalState();
        transactionRepository.saveAll(List.of(
                reportTxn(50, CARD_ACCT_50, "2021-12-31", "111.00"),  // before window
                reportTxn(51, CARD_ACCT_2, "2022-12-25", "222.00"))); // after window

        JobExecution execution = launchReport("2022-03-01", "2022-03-31");
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(countObjects(BATCH_OUTPUT_BUCKET, TRANREPT_PREFIX))
                .as("exactly one report object is written even for an empty in-range result").isEqualTo(1L);

        List<String> lines = readReportLines();
        assertThat(collect(lines, BatchPipelineE2ETest::isDetail))
                .as("no in-range rows => no detail lines (WS-FIRST-TIME stays 'Y')").isEmpty();
        assertThat(collect(lines, BatchPipelineE2ETest::isGrandTotal))
                .as("no in-range rows => no grand-total line").isEmpty();
    }

    @Test
    @Order(12)
    @DisplayName("Scenario E7: one 133-char-wide report object in S3; the report job publishes no SQS message")
    void reportShapeIs133CharsWideWithNoSqsMessage() throws Exception {
        restoreCanonicalState();
        transactionRepository.saveAll(List.of(
                reportTxn(60, CARD_ACCT_50, "2022-06-15", "12.34"),
                reportTxn(61, CARD_ACCT_2, "2022-06-15", "56.78")));

        JobExecution execution = launchReport("2022-01-01", "2022-12-31");
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(countObjects(BATCH_OUTPUT_BUCKET, TRANREPT_PREFIX))
                .as("exactly one report object per run").isEqualTo(1L);
        List<String> lines = readReportLines();
        assertThat(lines).as("a populated report must contain lines").isNotEmpty();
        for (String line : lines) {
            assertThat(line.length())
                    .as("each report record must be %d characters wide (PIC X(133)): [%s]", REPORT_RECORD_WIDTH, line)
                    .isEqualTo(REPORT_RECORD_WIDTH);
        }

        // The report job writes only to S3 and never publishes to the report-jobs SQS queue.
        String approximateMessages = SQS.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(reportQueueUrl)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                        .build())
                .attributes()
                .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
        assertThat(approximateMessages)
                .as("transactionReportJob must not publish any SQS message").isEqualTo("0");
    }

    // =====================================================================================================
    // Negative scenario — Stage-1 FAILURE halts the pipeline. A corrupt daily transaction (null amount on a
    // valid card) causes the non-fault-tolerant POSTTRAN step to NPE during the credit-limit computation,
    // so the step FAILS. The orchestrator therefore never PROCEEDs: the job ends FAILED and Stages 2–4 do
    // NOT run. This is the condition-code parity counterpart to the RC=4 PROCEED proof in Scenario F.
    // =====================================================================================================

    @Test
    @Order(13)
    @DisplayName("Negative: a Stage-1 failure halts the pipeline — the orchestrator FAILS and Stages 2–4 never run")
    void stageOneFailureHaltsTheWholePipeline() throws Exception {
        restoreCanonicalState();

        // A valid, non-expired card with a NULL amount: passes the card/account lookups, then the
        // credit-limit computation (cycCredit - cycDebit + amount) dereferences the null amount -> NPE.
        // POSTTRAN is not fault-tolerant (no skip), so the step FAILS rather than rejecting the record.
        CardCrossReference xref = anyCrossReference();
        dailyTransactionRepository.save(craftDailyTransaction(
                "9000000000000999", xref.getXrefCardNum(), null,
                LocalDate.of(2022, 6, 10).atTime(12, 0)));

        JobExecution execution = launchJob(cardDemoBatchPipelineJob, uniqueParams(b -> b
                .addString("parmDate", PARM_DATE)
                .addString("startDate", "2022-01-01")
                .addString("endDate", "2022-07-06")));

        // The pipeline halts: overall FAILED (the decider never returns PROCEED on a non-completed Stage 1).
        assertThat(execution.getStatus())
                .as("a corrupt-input Stage-1 failure fails the whole pipeline").isEqualTo(BatchStatus.FAILED);

        StepExecution posting = stepByName(execution, "dailyTransactionPostingStep");
        assertThat(posting.getStatus())
                .as("the non-fault-tolerant POSTTRAN step itself FAILED").isEqualTo(BatchStatus.FAILED);

        // Stages 2–4 must NOT have executed (the pipeline did not PROCEED past the failed Stage 1).
        assertThat(ranStep(execution, "interestCalculationStep")).as("INTCALC must NOT run after a failed Stage 1").isFalse();
        assertThat(ranStep(execution, "combineTransactionsStep")).as("COMBTRAN must NOT run").isFalse();
        assertThat(ranStep(execution, "prepareStatementsStep")).as("statement prepare must NOT run").isFalse();
        assertThat(ranStep(execution, "generateStatementsStep")).as("statement generate must NOT run").isFalse();
        assertThat(ranStep(execution, "transactionReportStep")).as("TRANREPT must NOT run").isFalse();
    }

    // =====================================================================================================
    // Parity / domain helpers — all monetary math is BigDecimal at scale 2 with HALF_EVEN; compareTo only.
    // =====================================================================================================

    /**
     * Resolves the interest rate for {@code (groupId, REF_TYPE, REF_CATEGORY)} exactly as CBACT04C
     * {@code 1200-GET-INTEREST-RATE}: a primary keyed read on the account's own disclosure group, falling
     * back to the literal {@code DEFAULT} group when the primary read finds nothing (COBOL DISCGRP-STATUS
     * '23' → {@code 1200-A-GET-DEFAULT-INT-RATE}).
     *
     * @param groupId the account's disclosure group id
     * @return the resolved interest rate (never {@code null}; fixture guarantees a DEFAULT row exists)
     */
    private BigDecimal resolveInterestRate(String groupId) {
        Optional<DisclosureGroup> own =
                disclosureGroupRepository.findById(new DisclosureGroupId(groupId, REF_TYPE, REF_CATEGORY));
        if (own.isPresent()) {
            return own.get().getDisIntRate();
        }
        return disclosureGroupRepository.findById(new DisclosureGroupId(DEFAULT_GROUP_ID, REF_TYPE, REF_CATEGORY))
                .map(DisclosureGroup::getDisIntRate)
                .orElseThrow(() -> new IllegalStateException(
                        "Fixture precondition violated: neither group '" + groupId + "' nor DEFAULT has a rate for ("
                                + REF_TYPE + "," + REF_CATEGORY + ")"));
    }

    /**
     * Reproduces the CBACT04C monthly-interest formula WITHOUT algebraic rearrangement:
     * {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} — multiply first, then divide by the literal 1200 at
     * scale 2 with HALF_EVEN (banker's rounding). No {@code float}/{@code double}.
     *
     * @param balance the category balance (TRAN-CAT-BAL)
     * @param rate    the disclosure-group rate (DIS-INT-RATE)
     * @return the expected monthly interest at scale 2
     */
    private static BigDecimal expectedMonthlyInterest(BigDecimal balance, BigDecimal rate) {
        return balance.multiply(rate).divide(INTEREST_DIVISOR, MONETARY_SCALE, RoundingMode.HALF_EVEN);
    }

    /** Builds a card-number → account-id map from CARDXREF (the link CBTRN02C 1500-A resolves). */
    private Map<String, Long> cardToAccountMap() {
        Map<String, Long> map = new HashMap<>();
        for (CardCrossReference xref : cardCrossReferenceRepository.findAll()) {
            map.put(xref.getXrefCardNum(), xref.getXrefAcctId());
        }
        return map;
    }

    /** Returns one CARDXREF row whose card number drives a valid xref→account lookup (the first seeded). */
    private CardCrossReference anyCrossReference() {
        List<CardCrossReference> all = cardCrossReferenceRepository.findAll();
        assertThat(all).as("CARDXREF must be Flyway-seeded").isNotEmpty();
        return all.get(0);
    }

    /** Sum of a list of amounts at scale 2 (no float/double). */
    private static BigDecimal sumScale2(List<BigDecimal> amounts) {
        BigDecimal total = ZERO_SCALE2;
        for (BigDecimal amount : amounts) {
            total = total.add(amount);
        }
        return total.setScale(MONETARY_SCALE, RoundingMode.HALF_EVEN);
    }

    /** Live sum of every account's current balance (ACCT-CURR-BAL) — the post-run side of conservation. */
    private BigDecimal currentAccountBalanceTotal() {
        List<BigDecimal> balances = new ArrayList<>();
        for (Account account : accountRepository.findAll()) {
            balances.add(account.getAcctCurrBal());
        }
        return sumScale2(balances);
    }

    /** Canonical (seeded) sum of every account's balance, taken from the {@code @BeforeAll} snapshot. */
    private BigDecimal canonicalAccountBalanceTotal() {
        List<BigDecimal> balances = new ArrayList<>();
        for (AccountSnapshot snap : accountSnapshots.values()) {
            balances.add(snap.currBal());
        }
        return sumScale2(balances);
    }

    /** Live sum of every category balance (TRAN-CAT-BAL) — the post-run side of the TCATBAL conservation. */
    private BigDecimal currentCategoryBalanceTotal() {
        List<BigDecimal> balances = new ArrayList<>();
        for (TransactionCategoryBalance balance : categoryBalanceRepository.findAll()) {
            balances.add(balance.getTranCatBal());
        }
        return sumScale2(balances);
    }

    /** Canonical (seeded) sum of every category balance, taken from the {@code @BeforeAll} snapshot. */
    private BigDecimal canonicalCategoryBalanceTotal() {
        return sumScale2(new ArrayList<>(categoryBalanceSnapshots.values()));
    }

    /**
     * Extracts the set of SYSTRAN transaction ids staged by Stage&nbsp;2 (INTCALC) from the S3 object keys
     * under {@link #SYSTRAN_PREFIX} (key shape {@code systran/<yyyyMMdd>/<tranId>.dat}). The id is the
     * key's file-name stem (between the last {@code '/'} and the {@code .dat} suffix).
     *
     * @return the distinct staged interest-transaction ids (never {@code null})
     */
    private Set<String> systranTranIds() {
        Set<String> ids = new HashSet<>();
        for (String key : listKeys(BATCH_OUTPUT_BUCKET, SYSTRAN_PREFIX)) {
            String fileName = key.substring(key.lastIndexOf('/') + 1);
            if (fileName.endsWith(".dat")) {
                ids.add(fileName.substring(0, fileName.length() - ".dat".length()));
            }
        }
        return ids;
    }

    /**
     * Builds an unsaved {@link DailyTransaction} for arrangement. Used both for guaranteed-reject crafts and
     * for the corrupt-input failure lever. Every field is set except — deliberately — when a {@code null}
     * amount is requested for the corrupt-input case.
     *
     * @param id       the 16-char DALYTRAN-ID primary key (use a synthetic high-range id, never a seeded id)
     * @param cardNum  the DALYTRAN-CARD-NUM (a CARDXREF-backed number to pass the xref lookup, or
     *                 {@link #ABSENT_CARD_NUM} to force reject 100)
     * @param amount   the DALYTRAN-AMT (may be {@code null} to drive the corrupt-input NPE failure path)
     * @param origTs   the DALYTRAN-ORIG-TS (drives the reject-103 expiration comparison)
     * @return a fully-populated (or intentionally null-amount) unsaved daily transaction
     */
    private DailyTransaction craftDailyTransaction(String id, String cardNum, BigDecimal amount, LocalDateTime origTs) {
        DailyTransaction daily = new DailyTransaction();
        daily.setDalytranId(id);
        daily.setDalytranTypeCd(REF_TYPE);
        daily.setDalytranCatCd(REF_CATEGORY);
        daily.setDalytranSource("E2E");
        daily.setDalytranDesc("E2E ARRANGED " + id);
        daily.setDalytranAmt(amount);
        daily.setDalytranMerchantId(900000000L);
        daily.setDalytranMerchantName("E2E MERCHANT");
        daily.setDalytranMerchantCity("SEATTLE");
        daily.setDalytranMerchantZip("98101");
        daily.setDalytranCardNum(cardNum);
        daily.setDalytranOrigTs(origTs);
        daily.setDalytranProcTs(origTs);
        return daily;
    }

    /**
     * Parses every 430-byte reject record across all objects under {@link #REJECTS_PREFIX}. Records are
     * framed by {@code '\n'} and encoded ISO-8859-1 (the production {@code RejectWriter} contract). Each
     * returned record is the raw 430-character line (350-byte payload + 80-byte trailer).
     *
     * @return all reject records (possibly empty, never {@code null})
     */
    private List<String> readAllRejectRecords() {
        List<String> records = new ArrayList<>();
        for (String key : listKeys(BATCH_OUTPUT_BUCKET, REJECTS_PREFIX)) {
            String body = new String(getObjectBytes(BATCH_OUTPUT_BUCKET, key), java.nio.charset.StandardCharsets.ISO_8859_1);
            for (String line : body.split("\n", -1)) {
                if (!line.isEmpty()) {
                    records.add(line);
                }
            }
        }
        return records;
    }

    /** Extracts the zero-padded reject code from a 430-byte reject record's trailer (chars [350,354)). */
    private static int rejectCodeOf(String rejectRecord) {
        String trailer = rejectRecord.substring(DALYTRAN_PAYLOAD_LENGTH);
        return Integer.parseInt(trailer.substring(0, REJECT_CODE_WIDTH).trim());
    }

    /** The set of distinct reject codes present across all reject records (derived, not assumed). */
    private Set<Integer> rejectCodesPresent() {
        Set<Integer> codes = new HashSet<>();
        for (String record : readAllRejectRecords()) {
            codes.add(rejectCodeOf(record));
        }
        return codes;
    }

    /** Finds the single step execution with the given name, asserting it exists. */
    private static StepExecution stepByName(JobExecution execution, String stepName) {
        return execution.getStepExecutions().stream()
                .filter(s -> stepName.equals(s.getStepName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a step execution named '" + stepName
                        + "' but found: " + stepNames(execution)));
    }

    /** Whether a step with the given name executed in this job execution. */
    private static boolean ranStep(JobExecution execution, String stepName) {
        return execution.getStepExecutions().stream().anyMatch(s -> stepName.equals(s.getStepName()));
    }

    /** Ordered list of executed step names (for diagnostics and ordering assertions). */
    private static List<String> stepNames(JobExecution execution) {
        List<String> names = new ArrayList<>();
        for (StepExecution step : execution.getStepExecutions()) {
            names.add(step.getStepName());
        }
        return names;
    }

    // ----- CBTRN03C report-line classifiers / parsers (mirroring the production writer's column-0 labels) -----

    private static final String MARK_NAME_HEADER = "DALYREPT";
    private static final String MARK_HEADER1 = "Transaction ID";
    private static final String MARK_PAGE_TOTAL = "Page Total";
    private static final String MARK_ACCOUNT_TOTAL = "Account Total";
    private static final String MARK_GRAND_TOTAL = "Grand Total";

    /** The dotted leader that precedes every total amount; the amount is everything after the dot-run. */
    private static final Pattern DOT_RUN = Pattern.compile("\\.{10,}");

    /**
     * Launches {@code transactionReportJob} with explicit inclusive {@code startDate}/{@code endDate}
     * job parameters (both {@code yyyy-MM-dd}) plus the unique run identifiers, returning the execution.
     *
     * @param startDate inclusive lower bound of the report's processing-date window
     * @param endDate   inclusive upper bound of the report's processing-date window
     * @return the terminal {@link JobExecution}
     * @throws Exception if the launch fails
     */
    private JobExecution launchReport(String startDate, String endDate) throws Exception {
        return launchJob(transactionReportJob,
                uniqueParams(b -> b.addString("startDate", startDate).addString("endDate", endDate)));
    }

    /** Reads the single report object under {@link #TRANREPT_PREFIX} and returns its lines (empty if 0-length). */
    private List<String> readReportLines() {
        List<String> keys = listKeys(BATCH_OUTPUT_BUCKET, TRANREPT_PREFIX);
        assertThat(keys)
                .as("exactly one report object expected under '%s' in '%s'", TRANREPT_PREFIX, BATCH_OUTPUT_BUCKET)
                .hasSize(1);
        String body = getObjectAsString(BATCH_OUTPUT_BUCKET, keys.get(0));
        List<String> lines = new ArrayList<>();
        if (body.isEmpty()) {
            return lines;
        }
        for (String line : body.split("\n", -1)) {
            lines.add(line);
        }
        return lines;
    }

    private static List<String> collect(List<String> lines, Predicate<String> predicate) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (predicate.test(line)) {
                out.add(line);
            }
        }
        return out;
    }

    private static boolean isAllHyphens(String line) {
        if (line.isEmpty()) {
            return false;
        }
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlankLine(String line) {
        return !line.isEmpty() && line.trim().isEmpty();
    }

    private static boolean isNameHeader(String line) {
        return line.startsWith(MARK_NAME_HEADER);
    }

    private static boolean isHeader1(String line) {
        return line.startsWith(MARK_HEADER1);
    }

    private static boolean isPageTotal(String line) {
        return line.startsWith(MARK_PAGE_TOTAL);
    }

    private static boolean isAccountTotal(String line) {
        return line.startsWith(MARK_ACCOUNT_TOTAL);
    }

    private static boolean isGrandTotal(String line) {
        return line.startsWith(MARK_GRAND_TOTAL);
    }

    /** A detail line is any populated report line that is not a header, separator, blank, or total line. */
    private static boolean isDetail(String line) {
        if (line.isEmpty() || isBlankLine(line) || isAllHyphens(line)) {
            return false;
        }
        return !isNameHeader(line) && !isHeader1(line)
                && !isPageTotal(line) && !isAccountTotal(line) && !isGrandTotal(line);
    }

    /**
     * Parses the numeric value from a total line. The amount follows a dotted leader ({@link #DOT_RUN});
     * spaces and grouping commas are stripped, and a blank/sign-only field (the writer's zero rendering)
     * parses to {@code 0.00}.
     */
    private static BigDecimal parseTotalAmount(String totalLine) {
        Matcher matcher = DOT_RUN.matcher(totalLine);
        assertThat(matcher.find())
                .as("total line must contain the dotted leader before its amount: [%s]", totalLine)
                .isTrue();
        String cleaned = totalLine.substring(matcher.end()).replace(" ", "").replace(",", "");
        if (cleaned.isEmpty() || "+".equals(cleaned) || "-".equals(cleaned)) {
            return ZERO_SCALE2;
        }
        return new BigDecimal(cleaned).setScale(MONETARY_SCALE, RoundingMode.HALF_EVEN);
    }

    /** Returns the single grand-total value from the report (asserts exactly one grand-total line). */
    private static BigDecimal grandTotalValue(List<String> lines) {
        List<String> grand = collect(lines, BatchPipelineE2ETest::isGrandTotal);
        assertThat(grand).as("exactly one grand-total line expected in the report").hasSize(1);
        return parseTotalAmount(grand.get(0));
    }

    /** Sum of decimal-string amounts at scale 2 (no float/double) — for deriving expected report totals. */
    private static BigDecimal sum(String... amounts) {
        BigDecimal total = ZERO_SCALE2;
        for (String amount : amounts) {
            total = total.add(new BigDecimal(amount));
        }
        return total.setScale(MONETARY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Parses the account id from a CBTRN03C detail line. The account id renders right-justified in columns
     * {@code [17,28)} of the 133-char report record (mirrors the production report-line layout the sibling
     * {@code TransactionReportJobIT} asserts against).
     */
    private static long detailAccountId(String detailLine) {
        return Long.parseLong(detailLine.substring(17, 28).trim());
    }

    /** Ordered list of account ids extracted from the report's detail lines. */
    private static List<Long> detailAccountIds(List<String> lines) {
        List<Long> ids = new ArrayList<>();
        for (String line : lines) {
            if (isDetail(line)) {
                ids.add(detailAccountId(line));
            }
        }
        return ids;
    }

    /**
     * Builds a report-seeding {@link Transaction} with the processing timestamp fixed at noon on
     * {@code procDate} so the reader's inclusive {@code SUBSTRING(tranProcTs,1,10)} date filter yields
     * exactly {@code procDate}. Uses V3-seeded reference values so CBTRN03C's mandatory lookups succeed.
     *
     * @param seq        a unique sequence rendered to the 16-digit TRAN-ID primary key
     * @param cardNumber a CARDXREF-backed card number (drives the reader sort and the xref lookup)
     * @param procDate   the yyyy-MM-dd processing date placed into TRAN-PROC-TS
     * @param amount     the TRAN-AMT as a decimal string
     * @return a fully-populated, unsaved report transaction
     */
    private Transaction reportTxn(int seq, String cardNumber, String procDate, String amount) {
        Transaction t = new Transaction();
        t.setTranId(String.format("%016d", seq));
        t.setTranTypeCd(REF_TYPE);
        t.setTranCatCd(REF_CATEGORY);
        t.setTranSource("REPORT");
        t.setTranDesc("RPT-" + seq);
        t.setTranAmt(new BigDecimal(amount));
        t.setTranMerchantId(800000000L);
        t.setTranMerchantName("ACME MERCHANT");
        t.setTranMerchantCity("SEATTLE");
        t.setTranMerchantZip("98101");
        t.setTranCardNum(cardNumber);
        LocalDateTime ts = LocalDate.parse(procDate).atTime(12, 0, 0);
        t.setTranOrigTs(ts);
        t.setTranProcTs(ts);
        return t;
    }

    /**
     * Supplies the {@link CorsConfigurationSource} bean that the production {@code SecurityConfig}/
     * {@code WebConfig} graph delegates to. Under {@code webEnvironment=NONE} the MVC
     * {@code HandlerMappingIntrospector} that would otherwise provide it is unavailable, so the context
     * fails to start without this bean. Mirrors the sibling batch ITs.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityCorsTestConfig {

        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            return new UrlBasedCorsConfigurationSource();
        }
    }
}
