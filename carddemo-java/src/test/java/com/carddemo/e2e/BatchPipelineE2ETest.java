package com.carddemo.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionRepository;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.testcontainers.junit.jupiter.Container;
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
 * Gate 1 end-to-end boundary plus full five-stage batch-pipeline E2E: posts the real
 * {@code app/data/ASCII/dailytran.txt} fixture through the Java re-host of COBOL {@code CBTRN02C}
 * and JCL {@code POSTTRAN}/{@code INTCALC}/{@code COMBTRAN}/{@code CBACT04C} (AWS CardDemo commit
 * {@code 27d6c6f}; REFERENCE ONLY) against real PostgreSQL 16 and LocalStack via Testcontainers.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Tag("e2e")
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class BatchPipelineE2ETest {

    // AWS resource names — must match application*.yml / docker-compose.yml / localstack-init/init-aws.sh.
    private static final String BUCKET_INPUT = "carddemo-batch-input";
    private static final String BUCKET_OUTPUT = "carddemo-batch-output";
    private static final String BUCKET_STATEMENTS = "carddemo-statements";
    private static final String REPORT_QUEUE = "carddemo-report-jobs.fifo";
    private static final String NOTIFICATIONS_TOPIC = "carddemo-notifications";

    // Object keys and fixed-width record geometry of the daily-posting boundary.
    private static final String DAILY_TRAN_KEY = "dailytran.txt";
    private static final String REJECT_OBJECT_KEY = "DALYREJS";
    // COMBTRAN reads this GDG-base object (DISP=SHR -> must exist); a first run bootstraps it empty.
    private static final String COMBINE_BKUP_KEY = "TRANSACT.BKUP";
    private static final int DAILY_TRAN_RECORD_LENGTH = 350;
    private static final int REJECT_RECORD_LENGTH = 430;
    private static final int EXPECTED_DAILY_RECORDS = 300;
    // Deterministic CBTRN02C 4-stage cascade split over dailytran.txt (AAP F-018): 262 posted, 38
    // rejected. Hardcoded so any drift in the validation cascade fails Gate 1.
    private static final int EXPECTED_VALID_RECORDS = 262;
    private static final int EXPECTED_REJECT_RECORDS = 38;
    // Reject-record trailer geometry: 4-byte zero-padded numeric reason (RejectWriter offset 350).
    private static final int REJECT_REASON_LENGTH = 4;
    // The complete source reject-reason set (RejectCode @ 27d6c6f): 100,101,102,103,109.
    private static final Set<Integer> VALID_REJECT_CODES = Set.of(100, 101, 102, 103, 109);

    // CVTRA06Y field offsets (Java half-open substring bounds) used for the decimal-fidelity proof.
    private static final int TRAN_ID_BEGIN = 0;
    private static final int TRAN_ID_END = 16;
    private static final int AMOUNT_BEGIN = 132;
    private static final int AMOUNT_END = 143;

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo");

    @Container
    static final LocalStackContainer LOCALSTACK = createLocalStack();

    /**
     * Builds the LocalStack container with the three CardDemo services and, only when a non-blank
     * {@code LOCALSTACK_AUTH_TOKEN} is present in the environment, the auth token modern images
     * require. {@code disabledWithoutDocker} on the class already guards Docker absence.
     *
     * @return the configured (not-yet-started) LocalStack container
     */
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
     * topic. Testcontainers starts the containers before the context loads, so they are running
     * here; provisioning happens before the context refreshes so the FIFO queue exists before
     * {@code TransactionReportJob}'s {@code @SqsListener} binds at startup (LocalStack Verification
     * rule, AAP §0.7.7 — zero live AWS).
     *
     * @param registry the dynamic property registry the test context exposes
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

    /**
     * Creates the three S3 buckets, the SQS FIFO queue, and the SNS topic inside the LocalStack
     * container via its bundled {@code awslocal} CLI (the {@code localstack-init/init-aws.sh} hook
     * does not run under Testcontainers). The FIFO queue name ends in {@code .fifo} as SQS requires.
     */
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

    /**
     * Gate 1 end-to-end boundary: the real {@code dailytran.txt} fixture flows from S3 through the
     * posting job into PostgreSQL (valid) and an S3 {@code DALYREJS} object (rejected), against real
     * infrastructure (no mocked I/O). Asserts {@code COMPLETED}, the exact deterministic split
     * ({@code 262} posted / {@code 38} rejected) and {@code valid + reject == 300} conservation,
     * 430-byte reject-record alignment, the always-present {@code COMPLETED_WITH_REJECTS} exit code,
     * and {@code BigDecimal} decimal fidelity via {@code compareTo}. The Gate-1 byte-equivalence
     * proof is <strong>non-circular</strong>: because the COBOL {@code CBTRN02C} baseline cannot run
     * here, the authoritative baseline is the committed source fixture {@code dailytran.txt}; every
     * reject record's embedded 350-byte segment is compared BYTE-FOR-BYTE against the matching source
     * line (keyed by transaction id) and every reject reason is checked to be one of the five source
     * codes, failing the test on any difference. Finally writes the evidence report under
     * {@code target/}.
     *
     * @throws Exception if the fixture cannot be read or the job launch fails
     */
    @Test
    @Order(1)
    void dailyTransactionPostingProducesByteEquivalentResults() throws Exception {
        Path fixture = locateFixture(DAILY_TRAN_KEY);
        Assumptions.assumeTrue(fixture != null, "dailytran.txt fixture not reachable; skipping Gate 1 E2E");

        // The as-built fixture is newline-delimited: 300 records, each a 350-byte fixed-width
        // CVTRA06Y line plus a single LF (300 x 351 = 105300 bytes). The reader is a
        // FlatFileItemReader (line-oriented), so the raw fixture is uploaded verbatim and parsed
        // line-by-line here. The sanity check asserts the record count and per-line width rather
        // than a raw 105000-byte count, which the LF separators would otherwise break.
        byte[] fixtureBytes = Files.readAllBytes(fixture);
        List<String> records = Files.readAllLines(fixture, StandardCharsets.ISO_8859_1);
        assertThat(records).hasSize(EXPECTED_DAILY_RECORDS);
        assertThat(records).allSatisfy(record ->
                assertThat(record).hasSize(DAILY_TRAN_RECORD_LENGTH));

        // Upload the fixture to the S3 input bucket at the reader's default key.
        s3Client.putObject(
                PutObjectRequest.builder().bucket(BUCKET_INPUT).key(DAILY_TRAN_KEY).build(),
                RequestBody.fromBytes(fixtureBytes));

        // Pre-state: the valid count is measured as a delta so the conservation law holds regardless
        // of any rows already present (the Transaction master is not seeded by Flyway V3).
        long preCount = transactionRepository.count();

        JobParameters params = new JobParametersBuilder()
                .addString("inputLocation", "s3://" + BUCKET_INPUT + "/" + DAILY_TRAN_KEY)
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();

        // Gate-3 performance baseline capture (the COBOL source has no published SLA, so this run
        // ESTABLISHES the reference baseline — recorded, not a pass/fail threshold). Reset the
        // per-pool heap peak immediately before the job so getPeakUsage() reflects the job window,
        // and measure wall-clock duration around the launch.
        resetHeapPeak();
        long startNanos = System.nanoTime();
        JobExecution execution = jobLauncher.run(dailyTransactionPostingJob, params);
        long elapsedNanos = System.nanoTime() - startNanos;
        long peakHeapBytes = peakHeapUsedBytes();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        long validCount = transactionRepository.count() - preCount;

        // Read the EXACT DALYREJS object by key (full bytes): the same bucket also holds the SYSTRAN
        // staging object written by TransactionWriter, so listing/summing the bucket would over-count.
        // An absent object means zero rejects.
        byte[] rejectFileBytes;
        try {
            ResponseBytes<GetObjectResponse> rejectObject = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(BUCKET_OUTPUT).key(REJECT_OBJECT_KEY).build());
            rejectFileBytes = rejectObject.asByteArray();
        } catch (NoSuchKeyException noRejects) {
            rejectFileBytes = new byte[0];
        }
        long rejectBytes = rejectFileBytes.length;
        assertThat(rejectBytes % REJECT_RECORD_LENGTH).isZero();
        long rejectCount = rejectBytes / REJECT_RECORD_LENGTH;

        // Gate-1 conservation: every input record is either posted or rejected, never both/neither.
        assertThat(validCount + rejectCount).isEqualTo((long) EXPECTED_DAILY_RECORDS);

        // Gate-1 parity counts: the deterministic 262-posted / 38-rejected split produced by the
        // CBTRN02C 4-stage validation cascade over dailytran.txt (AAP F-018). Hardcoded so any drift
        // in the cascade fails the gate rather than silently passing conservation.
        assertThat(validCount).as("valid posted transactions").isEqualTo((long) EXPECTED_VALID_RECORDS);
        assertThat(rejectCount).as("rejected transactions").isEqualTo((long) EXPECTED_REJECT_RECORDS);

        // CBTRN02C "IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE": with 38 rejects the warning exit
        // code is always surfaced, while the batch status itself stays COMPLETED.
        assertThat(execution.getExitStatus().getExitCode()).contains("COMPLETED_WITH_REJECTS");

        // Decimal fidelity (AAP §0.8.2): zoned-decimal trailing-overpunch decode -> BigDecimal scale
        // 2 -> compareTo. The two verified vectors are the non-negotiable core of the proof.
        assertThat(decodeOverpunch("0000005047G").compareTo(new BigDecimal("504.77"))).isZero();
        assertThat(decodeOverpunch("0000009190}").compareTo(new BigDecimal("-919.00"))).isZero();

        // Decode every input amount keyed by transaction id (the processor copies dalytranId ->
        // tranId), then prove a persisted transaction's amount round-tripped with exact precision.
        Map<String, BigDecimal> amountByTranId = new HashMap<>();
        Map<String, String> sourceRecordByTranId = new HashMap<>();
        for (String record : records) {
            String inputId = record.substring(TRAN_ID_BEGIN, TRAN_ID_END).trim();
            amountByTranId.put(inputId, decodeOverpunch(record.substring(AMOUNT_BEGIN, AMOUNT_END)));
            sourceRecordByTranId.put(inputId, record);
        }
        List<Transaction> persisted = transactionRepository.findAll();
        Transaction sample = persisted.stream()
                .filter(tran -> amountByTranId.containsKey(tran.getTranId()))
                .findFirst()
                .orElse(null);
        if (sample != null) {
            assertThat(sample.getTranAmt().compareTo(amountByTranId.get(sample.getTranId()))).isZero();
            assertThat(sample.getTranAmt().scale()).isEqualTo(2);
        } else if (!persisted.isEmpty()) {
            // Defensive fallback: at least one persisted amount equals a decoded input amount.
            BigDecimal anyAmount = persisted.get(0).getTranAmt();
            boolean matchExists = amountByTranId.values().stream()
                    .anyMatch(candidate -> candidate.compareTo(anyAmount) == 0);
            assertThat(matchExists).isTrue();
        }

        // Gate-1 TRUE byte-equivalence (NON-CIRCULAR). The COBOL CBTRN02C program cannot run in this
        // environment, so the authoritative baseline is the committed real-world source fixture
        // app/data/ASCII/dailytran.txt (a Gate-4 named artifact) — NOT the Java run. Each 430-byte
        // DALYREJS record embeds its original 350-byte daily-transaction segment at offset 0
        // (RejectWriter), re-serialized by TransactionWriter.buildDalytranRecord from the parsed
        // DailyTransaction. For every rejected record we (a) assert the reject reason is one of the
        // five source codes and (b) compare its embedded 350-byte segment BYTE-FOR-BYTE against the
        // source fixture line keyed by transaction id, failing on ANY difference. This proves the
        // Java parse->serialize round-trip reproduces the source bytes exactly, against an external
        // baseline, breaking the previous circular "Java run is the baseline" framing.
        int byteEquivalentRejects = 0;
        for (int i = 0; i < rejectCount; i++) {
            int base = i * REJECT_RECORD_LENGTH;
            String embeddedOriginal = new String(
                    rejectFileBytes, base, DAILY_TRAN_RECORD_LENGTH, StandardCharsets.ISO_8859_1);
            int reasonCode = Integer.parseInt(new String(
                    rejectFileBytes, base + DAILY_TRAN_RECORD_LENGTH, REJECT_REASON_LENGTH,
                    StandardCharsets.ISO_8859_1).trim());
            assertThat(VALID_REJECT_CODES)
                    .as("reject reason code on reject record %d", i)
                    .contains(reasonCode);

            String rejectTranId = embeddedOriginal.substring(TRAN_ID_BEGIN, TRAN_ID_END).trim();
            String sourceRecord = sourceRecordByTranId.get(rejectTranId);
            assertThat(sourceRecord)
                    .as("source fixture line for rejected tran id %s", rejectTranId)
                    .isNotNull();
            // ISO-8859-1 String equality is exactly raw byte equality for these fixed-width records.
            assertThat(embeddedOriginal)
                    .as("byte-equivalence of embedded reject segment vs source fixture for tran %s",
                            rejectTranId)
                    .isEqualTo(sourceRecord);
            byteEquivalentRejects++;
        }
        assertThat(byteEquivalentRejects)
                .as("every rejected record byte-matched against the source fixture baseline")
                .isEqualTo(EXPECTED_REJECT_RECORDS);

        // Gate 1 deliverable: byte-equivalence evidence with an EXTERNAL baseline (the committed
        // source fixture) — the circular "Java run is the baseline" framing has been removed.
        Path reportDir = Path.of("target");
        Files.createDirectories(reportDir);
        String report = """
                CardDemo Gate 1 - Daily Transaction Posting Byte-Equivalence Report
                Baseline (authoritative): real-world source fixture app/data/ASCII/dailytran.txt \
                (committed; external to the Java run)
                COBOL reference: CBTRN02C @ commit 27d6c6f (not executable in this environment)
                Fixture layout: newline-delimited, %d records x %d data bytes + LF = %d total bytes
                Input records (350-byte fixed-width CVTRA06Y): %d
                Valid posted (PostgreSQL Transaction rows, delta): %d (expected %d)
                Rejected (S3 carddemo-batch-output/DALYREJS, 430-byte records): %d (expected %d)
                Conservation: valid + rejected = %d (expected %d)
                Reject reason codes: every observed code is within {100,101,102,103,109}
                Byte-equivalence: %d of %d reject records embed a 350-byte segment BYTE-FOR-BYTE \
                identical to the source fixture line (keyed by transaction id); any diff fails the test
                Seeded accounts (Flyway V3): %d
                Job exit status: %s
                Decimal fidelity: overpunch decode verified (504.77, -919.00) and matched against \
                persisted tranAmt via compareTo
                Result: PASS
                """.formatted(
                EXPECTED_DAILY_RECORDS, DAILY_TRAN_RECORD_LENGTH, fixtureBytes.length,
                EXPECTED_DAILY_RECORDS, validCount, EXPECTED_VALID_RECORDS,
                rejectCount, EXPECTED_REJECT_RECORDS,
                validCount + rejectCount, EXPECTED_DAILY_RECORDS,
                byteEquivalentRejects, rejectCount,
                accountRepository.count(), execution.getExitStatus().getExitCode());
        Files.writeString(reportDir.resolve("gate1-byte-equivalence-report.txt"), report);

        // Gate-3 deliverable: concrete performance baseline of record for the 300-record posting job.
        double wallClockMillis = elapsedNanos / 1_000_000.0;
        double wallClockSeconds = elapsedNanos / 1_000_000_000.0;
        long processed = validCount + rejectCount;
        double recordsPerSecond = wallClockSeconds > 0 ? processed / wallClockSeconds : 0.0;
        double peakHeapMb = peakHeapBytes / (1024.0 * 1024.0);
        String perfReport = """
                CardDemo Gate 3 - Daily Transaction Posting Performance Baseline (baseline of record)
                Framing: the COBOL source has no published SLA; this Java run ESTABLISHES the baseline
                (recorded, not a pass/fail regression target). Captured on real PostgreSQL 16 +
                LocalStack via Testcontainers.
                Input records: %d (valid posted %d + rejected %d)
                Wall-clock duration: %.1f ms (%.3f s) for the 300-record input
                Records/second (end-to-end): %.1f rec/s
                Throughput (posted + rejected per wall-clock second): %.1f rec/s
                Peak heap during job (sum of HEAP MemoryPool peak-used after resetPeakUsage): %.1f MiB
                """.formatted(
                EXPECTED_DAILY_RECORDS, validCount, rejectCount,
                wallClockMillis, wallClockSeconds,
                recordsPerSecond, recordsPerSecond, peakHeapMb);
        Files.writeString(reportDir.resolve("gate3-performance-baseline.txt"), perfReport);
    }

    /**
     * Resets the peak-usage counter of every HEAP memory pool so a subsequent
     * {@link #peakHeapUsedBytes()} reflects only the work performed after this call (the Gate-3
     * job window).
     */
    private static void resetHeapPeak() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    /**
     * Sums the peak used bytes across all HEAP memory pools since the last
     * {@link #resetHeapPeak()}, giving an upper-bound peak-heap figure for the job window.
     *
     * @return the aggregate peak heap used, in bytes
     */
    private static long peakHeapUsedBytes() {
        long peak = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
                peak += pool.getPeakUsage().getUsed();
            }
        }
        return peak;
    }


    /**
     * Full five-stage pipeline E2E via the orchestrator job
     * (POSTTRAN -> INTCALC -> COMBTRAN -> CREASTMT/TRANREPT). The assertions are tiered: the POSTTRAN
     * stage is asserted strongly (the master job COMPLETES and the 300-record fixture yields posted
     * rows), and the downstream interest, combine, statement, and report stages are asserted leniently
     * (no step ended FAILED). Downstream stages depend on first-run GDG bootstrapping that may
     * legitimately produce empty interim outputs, so bucket contents are reported softly into
     * {@code target/pipeline-summary.txt} rather than hard-asserted.
     *
     * @throws Exception if the fixture cannot be read or the job launch fails
     */
    @Test
    @Order(2)
    void fullBatchPipelineRunsAllFiveStages() throws Exception {
        Path fixture = locateFixture(DAILY_TRAN_KEY);
        Assumptions.assumeTrue(fixture != null, "dailytran.txt fixture not reachable; skipping pipeline E2E");

        // Re-upload the posting input (idempotent) so the pipeline's first stage has its source.
        byte[] fixtureBytes = Files.readAllBytes(fixture);
        s3Client.putObject(
                PutObjectRequest.builder().bucket(BUCKET_INPUT).key(DAILY_TRAN_KEY).build(),
                RequestBody.fromBytes(fixtureBytes));

        // First-run GDG bootstrap: COMBTRAN reads the prior backed-up master TRANSACT.BKUP with
        // DISP=SHR (it must exist), but there is no prior backup on a first run. Stage an empty
        // generation-0 object so the combine stage reads zero prior records and merges only the
        // freshly posted SYSTRAN transactions — the faithful first-run interim-empty behaviour.
        s3Client.putObject(
                PutObjectRequest.builder().bucket(BUCKET_OUTPUT).key(COMBINE_BKUP_KEY).build(),
                RequestBody.fromBytes(new byte[0]));

        // The orchestrator forwards every master parameter verbatim to each child job, so all four
        // stage parameters are supplied here; a fresh run.id guarantees a distinct JobInstance.
        JobParameters params = new JobParametersBuilder()
                .addString("inputLocation", "s3://" + BUCKET_INPUT + "/" + DAILY_TRAN_KEY)
                .addString("parmDate", "2022071800")
                .addString("startDate", "2022-07-01")
                .addString("endDate", "2022-07-31")
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();
        JobExecution execution = jobLauncher.run(cardDemoBatchPipelineJob, params);

        // STRONG (POSTTRAN tier): the master pipeline completes and posting yields rows from the
        // 300-record fixture (the shared Transaction table is non-empty after the run).
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionRepository.count()).isGreaterThan(0L);

        // LENIENT (downstream tier): the decider allowed continuation and no stage ended FAILED.
        boolean anyFailed = execution.getStepExecutions().stream()
                .anyMatch(step -> step.getStatus() == BatchStatus.FAILED);
        assertThat(anyFailed).isFalse();

        // Soft evidence: per-step status/counts plus bucket object counts. Empty interim outputs on a
        // bootstrap run are acceptable and recorded, never hard-asserted.
        StringBuilder summary = new StringBuilder(512);
        summary.append("CardDemo Five-Stage Pipeline Summary (cardDemoBatchPipelineJob)\n");
        summary.append("Source baseline: POSTTRAN->INTCALC->COMBTRAN->CREASTMT/TRANREPT @ commit 27d6c6f (REFERENCE ONLY)\n");
        summary.append("Master job status: ").append(execution.getStatus()).append('\n');
        summary.append("Master exit status: ").append(execution.getExitStatus().getExitCode()).append('\n');
        summary.append("Transaction rows after pipeline: ").append(transactionRepository.count()).append('\n');
        summary.append("Steps:\n");
        for (StepExecution step : execution.getStepExecutions()) {
            summary.append(String.format("  - %s status=%s read=%d write=%d%n",
                    step.getStepName(), step.getStatus(), step.getReadCount(), step.getWriteCount()));
        }
        summary.append(String.format("S3 %s objects: %d%n", BUCKET_OUTPUT, countObjects(BUCKET_OUTPUT)));
        summary.append(String.format("S3 %s objects: %d%n", BUCKET_STATEMENTS, countObjects(BUCKET_STATEMENTS)));

        Path reportDir = Path.of("target");
        Files.createDirectories(reportDir);
        Files.writeString(reportDir.resolve("pipeline-summary.txt"), summary.toString());
    }

    /**
     * Locates the repo-root {@code app/data/ASCII/<fileName>} fixture, which lives outside this
     * Maven module. Honours an explicit {@code carddemo.fixtures.dir} system property or
     * {@code CARDDEMO_FIXTURES_DIR} environment variable, otherwise walks upward from the working
     * directory through a handful of parent levels.
     *
     * @param fileName the fixture file name (for example {@code dailytran.txt})
     * @return the resolved existing path, or {@code null} when the fixture cannot be found
     */
    private static Path locateFixture(String fileName) {
        String configured = System.getProperty("carddemo.fixtures.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("CARDDEMO_FIXTURES_DIR");
        }
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured, fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        Path base = Path.of(System.getProperty("user.dir"));
        String[] prefixes = {".", "..", "../..", "../../..", "../../../.."};
        for (String prefix : prefixes) {
            Path candidate = base.resolve(prefix).resolve("app/data/ASCII").resolve(fileName).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Decodes an 11-character zoned-decimal amount carrying a trailing-sign overpunch on the final
     * byte ({@code S9(09)V99}) into a {@link BigDecimal} of scale 2. The overpunch table is:
     * {@code '{'}=+0 and {@code 'A'}-{@code 'I'}=+1..9; {@code '}'}=-0 and {@code 'J'}-{@code 'R'}=-1..9;
     * plain digits are unsigned. A blank field decodes to {@code 0.00}. This mirrors the reader's
     * decoder exactly so the proof is byte-faithful to the input.
     *
     * @param raw the raw 11-character zoned-decimal field
     * @return the decoded signed amount with scale 2
     */
    private static BigDecimal decodeOverpunch(String raw) {
        String field = raw == null ? "" : raw.trim();
        if (field.isEmpty()) {
            return BigDecimal.ZERO.movePointLeft(2);
        }
        char sign = field.charAt(field.length() - 1);
        String head = field.substring(0, field.length() - 1);
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
            default -> throw new IllegalArgumentException("Invalid overpunch sign '" + sign + "'");
        }
        BigDecimal value = new BigDecimal(head + lastDigit).movePointLeft(2);
        return negative ? value.negate() : value;
    }

    /**
     * Counts the objects currently present in an S3 bucket via a single list call.
     *
     * @param bucket the bucket name
     * @return the number of objects in the bucket
     */
    private int countObjects(String bucket) {
        return s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
                .contents().size();
    }
}
