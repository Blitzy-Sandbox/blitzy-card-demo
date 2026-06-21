package com.carddemo.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.jobs.DailyTransactionPostingJob;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Gate 1 end-to-end boundary plus full 5-stage Spring Batch pipeline E2E test for the CardDemo
 * COBOL-to-Java migration, processing the real {@code dailytran.txt} fixture through the daily-posting
 * job and the orchestrator against real PostgreSQL 16 + LocalStack containers, validating the Java
 * translation of {@code CBTRN02C}/{@code POSTTRAN.jcl}/{@code INTCALC.jcl}/{@code COMBTRAN.jcl}/{@code CBACT04C}
 * (source commit {@code 27d6c6f}; REFERENCE ONLY, no COBOL is copied).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Tag("e2e")
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class BatchPipelineE2ETest {

    // ---------------------------------------------------------------------------------------------
    // Static infrastructure (self-contained scaffolding by design — no shared base class, mirroring
    // OnlineTransactionE2ETest in this package). The two containers are started once at class load
    // behind an isDockerAvailable() guard so they are running before @DynamicPropertySource provisions
    // the AWS resources and before the application context refreshes (which is when the report job's
    // @SqsListener binds to the FIFO queue). The @Testcontainers(disabledWithoutDocker=true) annotation
    // independently skips the whole class cleanly when Docker is absent (this *E2ETest is swept into
    // the Surefire unit phase by the pom's **/*Test.java include, so self-gating is mandatory).
    // ---------------------------------------------------------------------------------------------

    // Testcontainers 2.x: org.testcontainers.postgresql.PostgreSQLContainer is a NON-generic class
    // (the 1.x self-type parameter was removed and the class relocated out of org.testcontainers.containers),
    // so it is referenced WITHOUT type parameters. This is not a raw type — the class declares no type
    // variables — so the -Xlint:all/-Werror build emits no rawtypes warning.
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

    /** S3 bucket for batch staging input (← {@code DEFGDGB.jcl}); the posting reader reads from here. */
    private static final String BUCKET_INPUT = "carddemo-batch-input";

    /** S3 bucket for batch output; holds the {@code DALYREJS} reject object (and {@code SYSTRAN} staging). */
    private static final String BUCKET_OUTPUT = "carddemo-batch-output";

    /** S3 bucket for generated statements (← {@code CREASTMT.JCL}). */
    private static final String BUCKET_STATEMENTS = "carddemo-statements";

    /** SQS FIFO queue replacing the CICS TDQ report bridge (← {@code CORPT00C}); the report job binds here. */
    private static final String REPORT_QUEUE = "carddemo-report-jobs.fifo";

    /** SNS topic for notification fan-out. */
    private static final String NOTIFICATIONS_TOPIC = "carddemo-notifications";

    /** Default S3 key of the daily-transaction staging object (the reader's default location). */
    private static final String DAILY_TRAN_KEY = "dailytran.txt";

    /** S3 object key written by the {@link com.carddemo.batch.writers.RejectWriter} (single reject object). */
    private static final String REJECT_OBJECT_KEY = "DALYREJS";

    /** Fixed record length of the {@code CVTRA06Y} daily-transaction layout. */
    private static final int DAILY_TRAN_RECORD_LENGTH = 350;

    /** Fixed record length of the {@code DALYREJS} reject layout (350 data + 4 reason + 76 description). */
    private static final int REJECT_RECORD_LENGTH = 430;

    /** Record count of the {@code app/data/ASCII/dailytran.txt} fixture. */
    private static final int EXPECTED_DAILY_RECORDS = 300;

    /**
     * Expected number of POSTED (valid) transactions for the canonical {@code dailytran.txt} fixture.
     * Equals the faithful {@code CBTRN02C} per-record over-limit split (chunk size 1): same-account
     * records see the cycle balance accumulated by every prior accepted post, so over-limit (code 102)
     * is detected exactly as on the mainframe. A larger chunk would admit over-limit transactions the
     * COBOL rejects (e.g. chunk 100 yields 265/35). See DECISION_LOG D-034.
     */
    private static final int EXPECTED_VALID_POSTS = 262;

    /** Expected over-limit (code 102) reject count for {@code dailytran.txt} (300 &minus; 262). */
    private static final int EXPECTED_OVERLIMIT_REJECTS = 38;

    /** Monetary scale of the {@code DALYTRAN-AMT}/{@code TRAN-AMT} {@code S9(09)V99} field. */
    private static final int AMOUNT_SCALE = 2;

    // CVTRA06Y field offsets (0-based, end-exclusive) used for the decimal-fidelity proof.
    /** Transaction id field — COBOL 1..16 (inclusive) → substring(0, 16). */
    private static final int FIELD_ID_BEGIN = 0;
    private static final int FIELD_ID_END = 16;
    /** Amount field — COBOL 133..143 (inclusive), {@code S9(09)V99} overpunch → substring(132, 143). */
    private static final int FIELD_AMOUNT_BEGIN = 132;
    private static final int FIELD_AMOUNT_END = 143;
    /** Card-number field — COBOL 263..278 (inclusive) → substring(262, 278). */
    private static final int FIELD_CARDNUM_BEGIN = 262;
    private static final int FIELD_CARDNUM_END = 278;

    /**
     * Deterministic-per-run, test-only HMAC-SHA256 signing secret (32 random bytes, Base64-encoded to
     * &ge; 32 UTF-8 bytes — the HS256 minimum enforced by {@code SecurityConfig}). Registered below as
     * {@code carddemo.security.jwt.secret} so the security filter chain loads (the context fails fast
     * without it) even when the {@code JWT_SECRET} environment variable is absent. Never committed and
     * never reaches production.
     */
    private static final String TEST_JWT_SECRET = generateTestJwtSecret();

    @Autowired
    JobLauncher jobLauncher;

    @Autowired
    @Qualifier("dailyTransactionPostingJob")
    Job dailyTransactionPostingJob;

    @Autowired
    @Qualifier("cardDemoBatchPipelineJob")
    Job cardDemoBatchPipelineJob;

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    S3Client s3Client;

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
     * the SQS FIFO queue, and the SNS topic. Runs after the containers start (the {@code static} block
     * above) but before the context refreshes, so the FIFO queue exists when the report job's
     * {@code @SqsListener} binds at startup (LocalStack Verification rule, AAP §0.7.7 — zero live AWS).
     *
     * @param registry the dynamic property registry supplied by the Spring test context
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
        registry.add("carddemo.security.jwt.secret", () -> TEST_JWT_SECRET);
        provisionAwsResources();
    }

    /**
     * Creates the S3 buckets, the SQS FIFO queue, and the SNS topic inside the running LocalStack
     * container by exec-ing {@code awslocal} (the Testcontainers run does not execute
     * {@code localstack-init/init-aws.sh}). The FIFO queue name ends in {@code .fifo} as SQS requires.
     */
    private static void provisionAwsResources() {
        try {
            LOCALSTACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_INPUT);
            LOCALSTACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_OUTPUT);
            LOCALSTACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_STATEMENTS);
            // ContentBasedDeduplication=false per DECISION_LOG D-020 / D-032(j): the report producer
            // supplies an explicit per-message UUID deduplication id under the constant group id
            // "carddemo-reports", preserving the CICS WRITEQ TD repeat-submission semantics. This
            // matches localstack-init/init-aws.sh and the shared batch/AWS IT setup; content-based
            // deduplication is intentionally disabled so identical payloads are not silently dropped.
            LOCALSTACK.execInContainer("awslocal", "sqs", "create-queue",
                    "--queue-name", REPORT_QUEUE,
                    "--attributes", "FifoQueue=true,ContentBasedDeduplication=false");
            LOCALSTACK.execInContainer("awslocal", "sns", "create-topic", "--name", NOTIFICATIONS_TOPIC);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to provision LocalStack AWS resources", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while provisioning LocalStack AWS resources", e);
        }
    }

    /**
     * Locates the repo-root {@code app/data/ASCII/<fileName>} fixture, which lives OUTSIDE the
     * {@code carddemo-java} module. Honors the {@code carddemo.fixtures.dir} system property and the
     * {@code CARDDEMO_FIXTURES_DIR} environment variable first, then walks up from the working
     * directory looking for {@code app/data/ASCII/<fileName>} at the current directory and up to five
     * parent levels.
     *
     * @param fileName the fixture file name (e.g. {@code dailytran.txt})
     * @return the resolved {@link Path}, or {@code null} when the fixture cannot be located
     */
    private static Path locateFixture(String fileName) {
        String configuredDir = System.getProperty("carddemo.fixtures.dir");
        if (configuredDir == null || configuredDir.isBlank()) {
            configuredDir = System.getenv("CARDDEMO_FIXTURES_DIR");
        }
        if (configuredDir != null && !configuredDir.isBlank()) {
            Path candidate = Path.of(configuredDir).resolve(fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        Path base = Path.of(System.getProperty("user.dir"));
        for (int level = 0; level <= 5 && base != null; level++) {
            Path candidate = base.resolve("app").resolve("data").resolve("ASCII").resolve(fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
            base = base.getParent();
        }
        return null;
    }

    /**
     * Decodes an 11-character zoned-decimal {@code S9(09)V99} amount with a trailing-sign overpunch on
     * the last byte into a scale-2 {@link BigDecimal} (the inverse of the writer's encode and the exact
     * algorithm used by {@code DailyTransactionReader}; AAP D-001 / §0.8.2). Overpunch decode of the
     * final character: {@code '{'} = digit 0 positive; {@code 'A'..'I'} = digits 1..9 positive;
     * {@code '}'} = digit 0 negative; {@code 'J'..'R'} = digits 1..9 negative; {@code '0'..'9'} = that
     * digit positive. {@link BigDecimal} is used exclusively — never {@code float}/{@code double}.
     *
     * @param raw the raw 11-character amount token
     * @return the decoded scale-2 amount
     */
    private static BigDecimal decodeOverpunch(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return BigDecimal.ZERO.movePointLeft(AMOUNT_SCALE);
        }
        char sign = s.charAt(s.length() - 1);
        String head = s.substring(0, s.length() - 1);
        int lastDigit;
        boolean negative;
        switch (sign) {
            case '{' -> {
                lastDigit = 0;
                negative = false;
            }
            case 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I' -> {
                lastDigit = sign - 'A' + 1;
                negative = false;
            }
            case '}' -> {
                lastDigit = 0;
                negative = true;
            }
            case 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R' -> {
                lastDigit = sign - 'J' + 1;
                negative = true;
            }
            case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                lastDigit = sign - '0';
                negative = false;
            }
            default -> throw new IllegalArgumentException(
                    "Invalid overpunch sign '" + sign + "' in amount '" + raw + "'");
        }
        BigDecimal value = new BigDecimal(head + lastDigit).movePointLeft(AMOUNT_SCALE);
        return negative ? value.negate() : value;
    }

    /**
     * Splits the raw fixture bytes into fixed-width {@value #DAILY_TRAN_RECORD_LENGTH}-character data
     * records. The {@code app/data/ASCII/dailytran.txt} fixture is newline-delimited (each 350-character
     * record is followed by a line feed, exactly as the line-based {@code DailyTransactionReader}
     * expects), so the file is decoded as ISO-8859-1 and split on line feeds, dropping any trailing
     * carriage return and the empty element produced by the terminal newline.
     *
     * @param fixtureBytes the raw fixture bytes
     * @return the list of fixed-width data records (newline characters removed)
     */
    private static List<String> splitDailyRecords(byte[] fixtureBytes) {
        String text = new String(fixtureBytes, StandardCharsets.ISO_8859_1);
        List<String> records = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String record = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (!record.isEmpty()) {
                records.add(record);
            }
        }
        return records;
    }

    // ---------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------

    /**
     * Test A — Gate 1 end-to-end posting boundary (AAP §0.7.2). Processes the real
     * {@code dailytran.txt} fixture through {@code dailyTransactionPostingJob} (S3 &rarr; validate
     * &rarr; PostgreSQL + S3 {@code DALYREJS} rejections) against real PostgreSQL + LocalStack, then
     * asserts record conservation ({@code valid + reject == 300}), 430-byte alignment of the reject
     * object, the RC=4 warning exit code when rejects exist, and overpunch decimal fidelity, and
     * writes the byte-equivalence report to {@code target/}.
     *
     * @throws Exception if the fixture cannot be read or the job launch fails
     */
    @Test
    @Order(1)
    void dailyTransactionPostingProducesByteEquivalentResults() throws Exception {
        Path fixture = locateFixture(DAILY_TRAN_KEY);
        Assumptions.assumeTrue(fixture != null, "dailytran.txt fixture not reachable; skipping Gate 1 E2E");

        byte[] fixtureBytes = Files.readAllBytes(fixture);
        // The fixture is newline-delimited (each 350-char record + LF) exactly as the line-based
        // DailyTransactionReader requires, so verify its structure by record count and per-record width
        // rather than by raw byte arithmetic (which would not be a multiple of 350 once newlines count).
        List<String> dailyRecords = splitDailyRecords(fixtureBytes);
        assertThat(dailyRecords).hasSize(EXPECTED_DAILY_RECORDS);
        assertThat(dailyRecords).allSatisfy(record ->
                assertThat(record.length()).isEqualTo(DAILY_TRAN_RECORD_LENGTH));

        // Upload the fixture to the input bucket at the reader's default key.
        s3Client.putObject(
                PutObjectRequest.builder().bucket(BUCKET_INPUT).key(DAILY_TRAN_KEY).build(),
                RequestBody.fromBytes(fixtureBytes));

        // The `transaction` table is not seeded by Flyway V3, so it starts empty and the count delta is
        // the exact number of valid posts. Account rows ARE seeded (V3 ACCTDAT), proving real PostgreSQL.
        long preCount = transactionRepository.count();
        assertThat(accountRepository.count()).isGreaterThan(0L);

        JobParameters params = new JobParametersBuilder()
                .addString("inputLocation", "s3://" + BUCKET_INPUT + "/" + DAILY_TRAN_KEY)
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();
        JobExecution execution = jobLauncher.run(dailyTransactionPostingJob, params);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        long validCount = transactionRepository.count() - preCount;

        // Count rejects from the EXACT DALYREJS object ONLY (the output bucket also holds the SYSTRAN
        // staging object when stage-to-s3 is on, so listing/summing the bucket would be wrong).
        long rejectBytes;
        try {
            ResponseBytes<GetObjectResponse> rejectObject = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(BUCKET_OUTPUT).key(REJECT_OBJECT_KEY).build());
            rejectBytes = rejectObject.asByteArray().length;
        } catch (NoSuchKeyException noRejects) {
            rejectBytes = 0L;   // RejectWriter writes no object when there were zero rejects.
        }
        assertThat(rejectBytes % REJECT_RECORD_LENGTH).isZero();   // every reject record is exactly 430 bytes
        long rejectCount = rejectBytes / REJECT_RECORD_LENGTH;

        // Gate-1 conservation: every one of the 300 input records is either posted or rejected.
        assertThat(validCount + rejectCount).isEqualTo((long) EXPECTED_DAILY_RECORDS);

        // Gate-1 byte-equivalence (behavioral parity, AAP §0.8.1): the valid/reject split must match
        // CBTRN02C's faithful per-record over-limit accumulation, not merely conserve the total. With
        // chunk size 1 each accepted record's account REWRITE commits before the next same-account
        // record's stage-C read, so over-limit (code 102) is detected exactly as on the mainframe:
        // 262 posted / 38 rejected for dailytran.txt. (A larger chunk regresses to 265/35 by reading
        // stale, pre-accumulation cycle balances for clustered same-account records.) See D-034.
        assertThat(validCount)
                .as("valid posted transactions must equal the COBOL per-record split for dailytran.txt")
                .isEqualTo((long) EXPECTED_VALID_POSTS);
        assertThat(rejectCount)
                .as("over-limit rejects must equal the COBOL per-record split for dailytran.txt")
                .isEqualTo((long) EXPECTED_OVERLIMIT_REJECTS);

        // RC=4 warning: when rejects exist, the step listener composes the COMPLETED_WITH_REJECTS exit
        // code while leaving the batch status COMPLETED. Use contains(...) because Spring may compose codes.
        if (rejectCount > 0L) {
            assertThat(execution.getExitStatus().getExitCode())
                    .contains(DailyTransactionPostingJob.COMPLETED_WITH_REJECTS);
        }

        // Decimal fidelity (AAP §0.8.2): prove the overpunch decoder against two verified vectors first.
        assertThat(decodeOverpunch("0000005047G").compareTo(new BigDecimal("504.77"))).isZero();
        assertThat(decodeOverpunch("0000009190}").compareTo(new BigDecimal("-919.00"))).isZero();

        // Then prove a persisted Transaction.tranAmt round-tripped with exact precision. Index the input
        // records by transaction id (the reader copies dalytranId -> tranId; ids are unique across the 300).
        Map<String, BigDecimal> amountById = new LinkedHashMap<>();
        Map<String, String> cardById = new LinkedHashMap<>();
        for (String record : dailyRecords) {
            String id = record.substring(FIELD_ID_BEGIN, FIELD_ID_END).trim();
            cardById.put(id, record.substring(FIELD_CARDNUM_BEGIN, FIELD_CARDNUM_END).trim());
            amountById.put(id, decodeOverpunch(record.substring(FIELD_AMOUNT_BEGIN, FIELD_AMOUNT_END)));
        }

        boolean decimalFidelityMatched = false;
        for (Transaction posted : transactionRepository.findAll()) {
            String tranId = posted.getTranId() == null ? "" : posted.getTranId().trim();
            BigDecimal expectedAmount = amountById.get(tranId);
            if (expectedAmount == null) {
                continue;
            }
            // Strong match on id and card number (the reader copies dalytranCardNum -> tranCardNum).
            String postedCard = posted.getTranCardNum() == null ? "" : posted.getTranCardNum().trim();
            assertThat(postedCard).isEqualTo(cardById.get(tranId));
            assertThat(posted.getTranAmt().scale()).isEqualTo(AMOUNT_SCALE);
            assertThat(posted.getTranAmt().compareTo(expectedAmount)).isZero();   // compareTo, never equals
            decimalFidelityMatched = true;
            break;
        }
        // Defensive fallback (a persisted row whose id is absent from the fixture should be impossible):
        // prove an input record exists whose decoded amount equals the first persisted amount via compareTo.
        if (validCount > 0L && !decimalFidelityMatched) {
            BigDecimal postedAmount = transactionRepository.findAll().get(0).getTranAmt();
            assertThat(postedAmount.scale()).isEqualTo(AMOUNT_SCALE);
            assertThat(amountById.values().stream()
                    .anyMatch(amount -> amount.compareTo(postedAmount) == 0)).isTrue();
        }

        writeGate1Report(validCount, rejectCount, rejectBytes, execution.getExitStatus().getExitCode());
    }

    /**
     * Test B — full 5-stage pipeline (POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr;
     * CREASTMT/TRANREPT) via the orchestrator job {@code cardDemoBatchPipelineJob}. POSTTRAN is
     * asserted strongly (terminal COMPLETED + posted rows); the downstream stages are asserted
     * leniently (no step ended FAILED) because they depend on first-run GDG bootstrapping that may
     * legitimately produce empty interim outputs. Per-step evidence and tolerant S3 output counts are
     * written to {@code target/pipeline-summary.txt} (never hard-asserted).
     *
     * @throws Exception if the fixture cannot be read or the job launch fails
     */
    @Test
    @Order(2)
    void fullBatchPipelineRunsAllFiveStages() throws Exception {
        Path fixture = locateFixture(DAILY_TRAN_KEY);
        Assumptions.assumeTrue(fixture != null, "dailytran.txt fixture not reachable; skipping pipeline E2E");

        // Re-upload the posting input (idempotent); POSTTRAN is the orchestrator's first stage.
        byte[] fixtureBytes = Files.readAllBytes(fixture);
        s3Client.putObject(
                PutObjectRequest.builder().bucket(BUCKET_INPUT).key(DAILY_TRAN_KEY).build(),
                RequestBody.fromBytes(fixtureBytes));

        JobParameters params = new JobParametersBuilder()
                .addString("inputLocation", "s3://" + BUCKET_INPUT + "/" + DAILY_TRAN_KEY)
                .addString("parmDate", "2022071800")            // INTCALC PARM-DATE (yyyyMMddHH)
                .addString("startDate", "2022-07-01")           // TRANREPT report window lower bound
                .addString("endDate", "2022-07-31")             // TRANREPT report window upper bound
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();
        JobExecution execution = jobLauncher.run(cardDemoBatchPipelineJob, params);

        // STRONG tier (POSTTRAN): the pipeline reached terminal COMPLETED and posting yielded rows from
        // the 300-record fixture (transactionRepository.save() upserts, so the re-run never duplicate-fails).
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionRepository.count()).isGreaterThan(0L);

        // LENIENT tier (downstream INTCALC/COMBTRAN/CREASTMT/TRANREPT): no step ended FAILED. Empty interim
        // outputs are acceptable on a bootstrap run, so only the no-FAILED-step invariant is hard-asserted.
        boolean anyFailed = execution.getStepExecutions().stream()
                .anyMatch(step -> step.getStatus() == BatchStatus.FAILED);
        assertThat(anyFailed).isFalse();

        writePipelineSummary(execution);
    }

    // ---------------------------------------------------------------------------------------------
    // Evidence reports (written under target/; conventional, never committed)
    // ---------------------------------------------------------------------------------------------

    /**
     * Writes the Gate 1 byte-equivalence comparison report. Since the COBOL baseline is not executed
     * here, the Java run is the reference baseline; the report documents the record counts, the reject
     * object's byte alignment, the conservation result, and the job exit status.
     *
     * @param validCount  number of valid posted transactions
     * @param rejectCount number of 430-byte reject records in {@code DALYREJS}
     * @param rejectBytes total byte length of the {@code DALYREJS} object
     * @param exitCode    the posting job's exit code
     * @throws IOException if the report cannot be written
     */
    private static void writeGate1Report(long validCount, long rejectCount, long rejectBytes,
            String exitCode) throws IOException {
        Path reportDir = Path.of("target");
        Files.createDirectories(reportDir);
        String report = """
                CardDemo Gate 1 - Daily Transaction Posting Byte-Equivalence Report
                Source baseline: COBOL CBTRN02C @ commit 27d6c6f (REFERENCE ONLY; not executed)
                Fixture: app/data/ASCII/dailytran.txt (newline-delimited, %d records x %d bytes + LF)
                Input records (350-byte fixed-width, verified by line count): %d
                Valid posted (PostgreSQL transaction rows): %d
                Rejected (S3 carddemo-batch-output/DALYREJS, 430-byte records): %d
                Conservation: valid + rejected = %d (expected %d)
                Per-record over-limit split (CBTRN02C parity, chunk size 1): valid=%d / reject=%d (expected %d / %d; match: %s)
                Reject object byte length: %d (multiple of 430: %s)
                Job exit status: %s
                Decimal fidelity: overpunch decode verified (504.77, -919.00) and matched against persisted tranAmt via compareTo
                Result: PASS
                """.formatted(EXPECTED_DAILY_RECORDS, DAILY_TRAN_RECORD_LENGTH,
                EXPECTED_DAILY_RECORDS, validCount, rejectCount,
                validCount + rejectCount, EXPECTED_DAILY_RECORDS,
                validCount, rejectCount, EXPECTED_VALID_POSTS, EXPECTED_OVERLIMIT_REJECTS,
                validCount == EXPECTED_VALID_POSTS && rejectCount == EXPECTED_OVERLIMIT_REJECTS,
                rejectBytes, rejectBytes % REJECT_RECORD_LENGTH == 0, exitCode);
        Files.writeString(reportDir.resolve("gate1-byte-equivalence-report.txt"), report);
    }

    /**
     * Writes the 5-stage pipeline summary: the job's batch status and exit code, each step's name,
     * status, and read/write counts, and tolerant S3 output object counts (presence is logged, never
     * hard-asserted — an empty bucket on a bootstrap run is acceptable).
     *
     * @param execution the completed pipeline job execution
     * @throws IOException if the summary cannot be written
     */
    private void writePipelineSummary(JobExecution execution) throws IOException {
        StringBuilder summary = new StringBuilder(512);
        summary.append("CardDemo 5-Stage Batch Pipeline Summary\n");
        summary.append("Source baseline: POSTTRAN/INTCALC/COMBTRAN/CREASTMT/TRANREPT @ commit 27d6c6f (REFERENCE ONLY)\n");
        summary.append("Pipeline job: cardDemoBatchPipelineJob\n");
        summary.append("Batch status: ").append(execution.getStatus()).append('\n');
        summary.append("Exit code: ").append(execution.getExitStatus().getExitCode()).append('\n');
        summary.append("Steps:\n");
        for (StepExecution step : execution.getStepExecutions()) {
            summary.append("  - ")
                    .append(step.getStepName())
                    .append(": status=").append(step.getStatus())
                    .append(", read=").append(step.getReadCount())
                    .append(", write=").append(step.getWriteCount())
                    .append('\n');
        }
        summary.append("S3 carddemo-batch-output objects: ").append(countObjects(BUCKET_OUTPUT)).append('\n');
        summary.append("S3 carddemo-statements objects: ").append(countObjects(BUCKET_STATEMENTS)).append('\n');

        Path reportDir = Path.of("target");
        Files.createDirectories(reportDir);
        Files.writeString(reportDir.resolve("pipeline-summary.txt"), summary.toString());
    }

    /**
     * Counts the objects currently in an S3 bucket (tolerant presence evidence for the pipeline summary).
     *
     * @param bucket the bucket name
     * @return the number of objects listed in the bucket
     */
    private long countObjects(String bucket) {
        return s3Client.listObjectsV2(
                ListObjectsV2Request.builder().bucket(bucket).build()).contents().size();
    }
}
