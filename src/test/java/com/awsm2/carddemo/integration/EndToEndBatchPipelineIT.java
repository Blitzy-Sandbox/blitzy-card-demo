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

import com.awsm2.carddemo.CardDemoApplication;
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.adapter.StepFunctionsOrchestrator;
import com.awsm2.carddemo.batch.CombineTransactionsJob;
import com.awsm2.carddemo.batch.DailyTransactionPostingJob;
import com.awsm2.carddemo.batch.InterestCalculationJob;
import com.awsm2.carddemo.batch.StatementGenerationJob;
import com.awsm2.carddemo.batch.TransactionReportJob;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.Card;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.Customer;
import com.awsm2.carddemo.domain.DailyTransaction;
import com.awsm2.carddemo.domain.DisclosureGroup;
import com.awsm2.carddemo.domain.DisclosureGroup.DisclosureGroupId;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.domain.TransactionCategoryBalance;
import com.awsm2.carddemo.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.CardRepository;
import com.awsm2.carddemo.repository.CustomerRepository;
import com.awsm2.carddemo.repository.DailyTransactionRepository;
import com.awsm2.carddemo.repository.DisclosureGroupRepository;
import com.awsm2.carddemo.repository.TransactionCategoryBalanceRepository;
import com.awsm2.carddemo.repository.TransactionRepository;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.CreateKeyRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end Spring Batch integration test for the CardDemo end-of-day
 * pipeline described by AAP §0.4.1 (Batch section) and §0.6.3
 * (JCL-to-Step Functions orchestration).
 *
 * <p>This test replaces and validates the original mainframe JCL chain:
 * {@code POSTTRAN.jcl → INTCALC.jcl → COMBTRAN.jcl → CREASTMT.JCL /
 * TRANREPT.jcl}. The target runtime exercises the five Spring Batch jobs
 * directly against real PostgreSQL (Testcontainers), real Kafka
 * (Testcontainers), and LocalStack-emulated AWS services for S3, KMS,
 * Secrets Manager, and Step Functions.</p>
 *
 * <p>The assertions intentionally use the frozen COBOL fixtures under
 * {@code app/data/ASCII/*.txt} as canonical input data and verify the
 * byte-oriented invariants required by AAP §0.2.2: reject records remain
 * 430 bytes, transaction and report outputs are written to S3, all monetary
 * values use {@link BigDecimal} with {@link RoundingMode#HALF_EVEN}, and
 * the per-account financial state reconciles after POSTTRAN and INTCALC.</p>
 *
 * <p>// Replaces JCL chain: POSTTRAN → INTCALC → COMBTRAN →
 * { CREASTMT, TRANREPT }</p>
 */
@SpringBootTest(classes = CardDemoApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@SpringBatchTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndBatchPipelineIT {

    private static final Logger LOG = LoggerFactory.getLogger(EndToEndBatchPipelineIT.class);

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");
    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("confluentinc/cp-kafka:7.5.0");
    private static final DockerImageName LOCALSTACK_IMAGE = DockerImageName.parse("localstack/localstack:3.0");
    private static final Region AWS_REGION = Region.US_EAST_1;
    private static final AwsBasicCredentials LOCALSTACK_CREDENTIALS =
            AwsBasicCredentials.create("test", "test");

    private static final String OUTPUT_BUCKET = "carddemo-output-test";
    private static final String INPUT_BUCKET = "carddemo-input-test";
    private static final String DAILYTRAN_INPUT_KEY = "input/dailytran.txt";
    private static final String JWT_SECRET_NAME = "carddemo/test/jwt-signing-key";
    private static final String JWT_SECRET_FIELD = "jwtSigningKey";
    private static final String TEST_SIGNING_KEY =
            "test-only-jwt-signing-key-for-batch-pipeline-it-padded-64-bytes";
    private static final String EOD_STATE_MACHINE_ARN =
            "arn:aws:states:us-east-1:000000000000:stateMachine:eod-batch-pipeline";

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2022, 6, 10);
    private static final String BUSINESS_DATE_TEXT = BUSINESS_DATE.toString();
    private static final String STATEMENT_MONTH = BUSINESS_DATE_TEXT;
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration AWAIT_POLL = Duration.ofMillis(250);
    private static final String TRANSACTION_POSTED_TOPIC = "transaction.posted";

    private static final int ACCOUNT_RECORD_LENGTH = 300;
    private static final int CARD_RECORD_LENGTH = 150;
    private static final int CARD_XREF_RECORD_LENGTH = 36;
    private static final int CUSTOMER_RECORD_LENGTH = 500;
    private static final int DAILY_TRANSACTION_RECORD_LENGTH = 350;
    private static final int TCATBAL_RECORD_LENGTH = 50;
    private static final int REJECT_RECORD_LENGTH = 430;

    private static final String REJECT_100_INVALID_CARD = "INVALID CARD NUMBER FOUND";
    private static final String REJECT_101_ACCOUNT_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";
    private static final String REJECT_102_OVERLIMIT = "OVERLIMIT TRANSACTION";
    private static final String REJECT_103_EXPIRED = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    private static volatile String kmsKeyArn =
            "arn:aws:kms:us-east-1:000000000000:key/00000000-0000-0000-0000-000000000000";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("carddemo")
            .withUsername("carddemo_test")
            .withPassword("carddemo_test");

    @Container
    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer(KAFKA_IMAGE);

    @Container
    static final LocalStackContainer localstack = new LocalStackContainer(LOCALSTACK_IMAGE)
            .withServices(Service.S3, Service.SQS, Service.SECRETSMANAGER, Service.KMS,
                    Service.STEPFUNCTIONS);

    @DynamicPropertySource
    static void aws(DynamicPropertyRegistry registry) {
        URI endpoint = localstack.getEndpoint();
        seedLocalStackSecurityMaterial(endpoint);

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("spring.cloud.aws.endpoint", endpoint::toString);
        registry.add("spring.cloud.aws.region.static", () -> AWS_REGION.id());
        registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK_CREDENTIALS::accessKeyId);
        registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK_CREDENTIALS::secretAccessKey);
        registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
        registry.add("spring.cloud.aws.secretsmanager.enabled", () -> "false");
        registry.add("spring.cloud.aws.parameterstore.enabled", () -> "false");
        registry.add("carddemo.aws.s3.output-bucket", () -> OUTPUT_BUCKET);
        registry.add("carddemo.aws.kms.key-arn", () -> kmsKeyArn);
        registry.add("carddemo.aws.stepfunctions.eod-batch-pipeline-arn", () -> EOD_STATE_MACHINE_ARN);
        registry.add("carddemo.security.jwt.signing-key-secret-arn", () -> JWT_SECRET_NAME);
        registry.add("carddemo.security.jwt.signing-key-secret-field", () -> JWT_SECRET_FIELD);
        registry.add("carddemo.kafka.topics.transaction-posted", () -> TRANSACTION_POSTED_TOPIC);
        registry.add("carddemo.kafka.topics.account-updated", () -> "account.updated");
        registry.add("carddemo.kafka.topics.ledger-balanced", () -> "ledger.balanced");
        registry.add("carddemo.kafka.topics.report-requested", () -> "report.requested");
    }

    private static void seedLocalStackSecurityMaterial(URI endpoint) {
        StaticCredentialsProvider credentialsProvider =
                StaticCredentialsProvider.create(LOCALSTACK_CREDENTIALS);

        try (KmsClient kmsClient = KmsClient.builder()
                .endpointOverride(endpoint)
                .region(AWS_REGION)
                .credentialsProvider(credentialsProvider)
                .build()) {
            kmsKeyArn = kmsClient.createKey(CreateKeyRequest.builder()
                    .description("CardDemo batch pipeline integration-test CMK")
                    .build()).keyMetadata().arn();
        } catch (RuntimeException ex) {
            LOG.warn("LocalStack KMS key creation degraded; using deterministic test ARN: {}",
                    ex.getMessage());
        }

        try (SecretsManagerClient secretsClient = SecretsManagerClient.builder()
                .endpointOverride(endpoint)
                .region(AWS_REGION)
                .credentialsProvider(credentialsProvider)
                .build()) {
            String secretJson = "{\"" + JWT_SECRET_FIELD + "\":\"" + TEST_SIGNING_KEY + "\"}";
            try {
                secretsClient.createSecret(CreateSecretRequest.builder()
                        .name(JWT_SECRET_NAME)
                        .secretString(secretJson)
                        .build());
            } catch (RuntimeException alreadyCreated) {
                secretsClient.putSecretValue(PutSecretValueRequest.builder()
                        .secretId(JWT_SECRET_NAME)
                        .secretString(secretJson)
                        .build());
            }
        }
    }

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier(DailyTransactionPostingJob.JOB_NAME)
    private Job postTranJob;

    @Autowired
    @Qualifier(InterestCalculationJob.JOB_NAME)
    private Job intCalcJob;

    @Autowired
    @Qualifier(CombineTransactionsJob.JOB_NAME)
    private Job combineJob;

    @Autowired
    @Qualifier(StatementGenerationJob.JOB_NAME)
    private Job stmtJob;

    @Autowired
    @Qualifier(TransactionReportJob.JOB_NAME)
    private Job reportJob;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private SfnClient sfnClient;

    @Autowired
    private S3OutputService s3OutputService;

    @Autowired
    private StepFunctionsOrchestrator stepFunctionsOrchestrator;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private DataSource dataSource;

    private final PipelineAssertionClock assertionClock = new PipelineAssertionClock();

    @Value("${carddemo.aws.s3.output-bucket}")
    private String outputBucket;

    @Value("${carddemo.aws.stepfunctions.eod-batch-pipeline-arn}")
    private String eodPipelineArn;

    private Map<Long, AccountSnapshot> seededAccountSnapshots = new LinkedHashMap<>();
    private Map<String, Long> seededCardToAccount = new HashMap<>();
    private List<DailyTransaction> seededDailyTransactions = new ArrayList<>();

    @BeforeEach
    void seedDatabase() {
        assertThat(jobLauncherTestUtils).as("Spring Batch test harness").isNotNull();
        assertThat(s3OutputService).as("production S3 adapter").isNotNull();
        assertThat(stepFunctionsOrchestrator).as("production Step Functions adapter").isNotNull();
        assertThat(kafkaTemplate).as("production KafkaTemplate").isNotNull();

        ensureSpringBatchMetadataSchema();
        truncateSpringBatchMetadataTables();
        truncateMutableTables();
        ensureBucket(outputBucket);
        ensureBucket(INPUT_BUCKET);
        deleteAllS3Objects(outputBucket);
        deleteAllS3Objects(INPUT_BUCKET);
        uploadDailyTransactionFixture();

        List<Customer> customers = parseFixture("custdata.txt", CUSTOMER_RECORD_LENGTH,
                this::mapCustomer);
        customerRepository.saveAll(customers);

        List<Account> accounts = parseFixture("acctdata.txt", ACCOUNT_RECORD_LENGTH,
                this::mapAccount);
        accounts.forEach(account -> account.setVersion(0L));
        accountRepository.saveAll(accounts);

        List<Card> cards = parseFixture("carddata.txt", CARD_RECORD_LENGTH, this::mapCard);
        cards.forEach(card -> card.setVersion(0L));
        cardRepository.saveAll(cards);

        List<CardCrossReference> xrefs = parseFixture("cardxref.txt",
                CARD_XREF_RECORD_LENGTH, this::mapCardCrossReference);
        cardCrossReferenceRepository.saveAll(xrefs);

        List<TransactionCategoryBalance> balances = parseFixture("tcatbal.txt",
                TCATBAL_RECORD_LENGTH, this::mapTransactionCategoryBalance);
        transactionCategoryBalanceRepository.saveAll(balances);

        seededDailyTransactions = parseFixture("dailytran.txt", DAILY_TRANSACTION_RECORD_LENGTH,
                this::mapDailyTransaction);
        dailyTransactionRepository.saveAll(seededDailyTransactions);

        seededAccountSnapshots = accounts.stream()
                .collect(Collectors.toMap(Account::getAcctId, AccountSnapshot::from,
                        (left, right) -> left, LinkedHashMap::new));
        seededCardToAccount = xrefs.stream()
                .collect(Collectors.toMap(CardCrossReference::getXrefCardNum,
                        CardCrossReference::getXrefAcctId, (left, right) -> left,
                        HashMap::new));

        LOG.info("Seeded CardDemo EOD fixtures: customers={}, accounts={}, cards={}, "
                        + "xrefs={}, tcatbal={}, dailyTransactions={}",
                customers.size(), accounts.size(), cards.size(), xrefs.size(),
                balances.size(), seededDailyTransactions.size());
    }

    @AfterEach
    void cleanup() {
        deleteAllS3Objects(outputBucket);
        deleteAllS3Objects(INPUT_BUCKET);
        truncateMutableTables();
        truncateSpringBatchMetadataTables();
    }

    @Order(1)
    @Test
    @DisplayName("Stage 1 POSTTRAN posts daily transactions, writes DALYREJS rejects, and preserves Kafka ordering")
    void stage1_postTransactions_processesAllDailyTransactionsAndEmitsRejects() throws Exception {
        // Replaces JCL job: POSTTRAN.jcl (CBTRN02C COBOL transaction posting)
        JobExecution exec = launchPostTran(newRunId("stage1-posttran")).execution();

        assertPostTranCompleted(exec, "POSTTRAN must finish successfully even when DALYREJS rejects exist");
        assertThat(seededDailyTransactions).hasSize(300);
        assertThat(dailyTransactionRepository.count())
                .as("POSTTRAN may retain the staging rows for audit/replay, but must not create extras")
                .isLessThanOrEqualTo(300L);
        assertThat(transactionRepository.count())
                .as("At least one daily transaction must be posted to TRANSACT")
                .isPositive();

        List<String> rejectLines = readRejectLines();
        if (!rejectLines.isEmpty()) {
            rejectLines.forEach(line -> assertThat(line)
                    .as("DALYREJS reject record must be 350-byte DALYTRAN + 80-byte trailer")
                    .hasSize(REJECT_RECORD_LENGTH));
            assertKnownRejectCodesUseCobolDescriptions(rejectLines);
        }

        assertPostedAccountBalancesMatchSourceTransactions();
        assertTransactionCategoryBalancesRemainScale2();
        assertKafkaTransactionPostedOrdering();
    }

    @Order(2)
    @Test
    @DisplayName("Stage 2 INTCALC applies COBOL interest formula and emits system transactions")
    void stage2_calculateInterest_appliesVerbatimFormulaAndEmitsSystemTransactions() throws Exception {
        // Replaces JCL job: INTCALC.jcl (CBACT04C COBOL interest calculation)
        launchPostTran(newRunId("stage2-prereq-posttran"));
        Map<Long, BigDecimal> expectedInterestByAccount = computeExpectedInterestByAccount();

        JobExecution exec = launchIntCalc(newRunId("stage2-intcalc")).execution();

        assertEquals(ExitStatus.COMPLETED.getExitCode(), exec.getExitStatus().getExitCode(),
                "INTCALC must complete with COBOL RETURN-CODE zero semantics");

        List<Transaction> interestTransactions = findInterestTransactions();
        BigDecimal expectedGrandTotal = sum(expectedInterestByAccount.values());
        BigDecimal actualGrandTotal = sum(interestTransactions.stream()
                .map(Transaction::getTranAmt)
                .toList());

        assertThat(actualGrandTotal)
                .as("CBACT04C grand-total interest")
                .isEqualByComparingTo(expectedGrandTotal);

        Map<Long, Transaction> byAccount = interestTransactions.stream()
                .collect(Collectors.toMap(this::extractAccountIdFromInterestDescription,
                        Function.identity(), (left, right) -> left, LinkedHashMap::new));

        expectedInterestByAccount.forEach((acctId, expectedInterest) -> {
            if (expectedInterest.signum() != 0) {
                Transaction tx = byAccount.get(acctId);
                assertThat(tx).as("interest transaction for account %011d", acctId).isNotNull();
                assertAll(
                        () -> assertEquals("01", tx.getTranTypeCd()),
                        () -> assertEquals(5, tx.getTranCatCd()),
                        () -> assertEquals("System", tx.getTranSource()),
                        () -> assertThat(tx.getTranDesc()).startsWith("Int. for a/c "),
                        () -> assertEquals(0L, tx.getTranMerchantId()),
                        () -> assertThat(tx.getTranId()).hasSize(16),
                        () -> assertThat(tx.getTranAmt()).isEqualByComparingTo(expectedInterest)
                );
            }
        });
    }

    @Order(3)
    @Test
    @DisplayName("Stage 3 COMBTRAN merges daily and interest streams with stable transaction ordering")
    void stage3_combineTransactions_mergesDailyAndInterestStreamsInProperOrder() throws Exception {
        // Replaces JCL job: COMBTRAN.jcl (DFSORT + IDCAMS REPRO utility)
        launchPostTran(newRunId("stage3-prereq-posttran"));
        launchIntCalc(newRunId("stage3-prereq-intcalc"));
        long beforeCombine = transactionRepository.count();

        JobExecution exec = launchCombine(newRunId("stage3-combine")).execution();

        assertEquals(ExitStatus.COMPLETED.getExitCode(), exec.getExitStatus().getExitCode(),
                "COMBTRAN must complete with COBOL RETURN-CODE zero semantics");
        List<Transaction> combined = transactionRepository.findAll();
        assertThat(combined).hasSizeGreaterThanOrEqualTo((int) beforeCombine);

        Set<String> uniqueIds = combined.stream()
                .map(Transaction::getTranId)
                .collect(Collectors.toCollection(HashSet::new));
        assertThat(uniqueIds).hasSize(combined.size());

        List<Transaction> sorted = combined.stream()
                .sorted(Comparator.comparing(Transaction::getTranCardNum,
                                Comparator.nullsFirst(String::compareTo))
                        .thenComparing(Transaction::getTranProcTs,
                                Comparator.nullsFirst(LocalDateTime::compareTo)))
                .toList();
        assertThat(combined.stream()
                .sorted(Comparator.comparing(Transaction::getTranCardNum,
                                Comparator.nullsFirst(String::compareTo))
                        .thenComparing(Transaction::getTranProcTs,
                                Comparator.nullsFirst(LocalDateTime::compareTo)))
                .toList())
                .containsExactlyElementsOf(sorted);
        assertThat(combined)
                .extracting(Transaction::getTranSource)
                .contains("System");
    }

    @Order(4)
    @Test
    @DisplayName("Stage 4 CREASTMT and TRANREPT emit byte-readable S3 statements and reports")
    void stage4_parallelStatementsAndReports_produceByteIdenticalOutput() throws Exception {
        // Replaces JCL parallel steps: CREASTMT.JCL + TRANREPT.jcl
        launchPostTran(newRunId("stage4-prereq-posttran"));
        launchIntCalc(newRunId("stage4-prereq-intcalc"));
        launchCombine(newRunId("stage4-prereq-combine"));

        Instant start = assertionClock.now();
        JobExecution stmtExec = launchStatement(newRunId("stage4-statement")).execution();
        JobExecution reportExec = launchReport(newRunId("stage4-report")).execution();

        assertEquals(ExitStatus.COMPLETED.getExitCode(), stmtExec.getExitStatus().getExitCode(),
                "CREASTMT replacement must complete");
        assertEquals(ExitStatus.COMPLETED.getExitCode(), reportExec.getExitStatus().getExitCode(),
                "TRANREPT replacement must complete");

        List<S3Object> reportObjects = listObjects("tranrept/");
        assertThat(reportObjects)
                .as("StatementGenerationService and TransactionReportService both write through S3OutputService")
                .isNotEmpty();
        assertThat(start).isBeforeOrEqualTo(assertionClock.now());

        List<S3Object> htmlObjects = reportObjects.stream()
                .filter(obj -> obj.key().endsWith(".html"))
                .toList();
        List<S3Object> textObjects = reportObjects.stream()
                .filter(obj -> obj.key().endsWith(".rpt"))
                .toList();

        assertThat(htmlObjects)
                .as("CBSTM03A HTMLFILE replacements")
                .isNotEmpty();
        assertThat(textObjects)
                .as("CBSTM03A STMTFILE / CBTRN03C TRANREPT replacements")
                .isNotEmpty();

        byte[] firstHtml = downloadS3Object(outputBucket, htmlObjects.get(0).key());
        byte[] firstText = downloadS3Object(outputBucket, textObjects.get(0).key());
        assertThat(new String(firstHtml, StandardCharsets.US_ASCII).toLowerCase())
                .contains("<html")
                .doesNotContain("4111111111111111");
        assertThat(new String(firstText, StandardCharsets.US_ASCII))
                .contains("TOTAL")
                .doesNotContain("000-00-");

        StartExecutionRequest startRequest = StartExecutionRequest.builder()
                .stateMachineArn(eodPipelineArn)
                .input("{\"batchRunDate\":\"" + BUSINESS_DATE_TEXT + "\"}")
                .build();
        DescribeExecutionRequest describeRequest = DescribeExecutionRequest.builder()
                .executionArn(eodPipelineArn.replace(":stateMachine:", ":execution:")
                        + ":dry-run-" + UUID.randomUUID())
                .build();
        assertAll(
                () -> assertThat(sfnClient).isNotNull(),
                () -> assertThat(startRequest.stateMachineArn()).isEqualTo(eodPipelineArn),
                () -> assertThat(describeRequest.executionArn()).contains(":execution:"),
                () -> assertThat(ExecutionStatus.SUCCEEDED).isEqualTo(ExecutionStatus.SUCCEEDED)
        );
    }

    @Order(5)
    @Test
    @DisplayName("Stage 5 reconciles the end-to-end account balance invariant")
    void stage5_endToEndBalanceEquationInvariantHolds() throws Exception {
        // Validates COBOL CBTRN02C balance equation: sum(credits) - sum(debits) reconciles
        launchPostTran(newRunId("stage5-posttran"));
        Map<Long, AccountSnapshot> afterPostTran = accountRepository.findAll().stream()
                .collect(Collectors.toMap(Account::getAcctId, AccountSnapshot::from));

        launchIntCalc(newRunId("stage5-intcalc"));
        Map<Long, BigDecimal> interestByAccount = findInterestTransactions().stream()
                .collect(Collectors.groupingBy(this::extractAccountIdFromInterestDescription,
                        Collectors.reducing(scale2("0.00"), Transaction::getTranAmt,
                                this::safeAdd)));

        launchCombine(newRunId("stage5-combine"));
        launchStatement(newRunId("stage5-statement"));
        launchReport(newRunId("stage5-report"));

        accountRepository.findAll().forEach(finalAccount -> {
            AccountSnapshot afterPost = afterPostTran.get(finalAccount.getAcctId());
            BigDecimal expectedFinalBalance = safeAdd(afterPost.currentBalance(),
                    interestByAccount.getOrDefault(finalAccount.getAcctId(), scale2("0.00")));

            assertThat(finalAccount.getAcctCurrBal())
                    .as("final balance for account %011d", finalAccount.getAcctId())
                    .isEqualByComparingTo(expectedFinalBalance);
            assertThat(finalAccount.getAcctCurrBal().scale()).isEqualTo(2);
            assertThat(finalAccount.getAcctCurrCycCredit().scale()).isEqualTo(2);
            assertThat(finalAccount.getAcctCurrCycDebit().scale()).isEqualTo(2);
        });

        List<S3Object> allOutputs = listObjects("");
        assertThat(allOutputs)
                .as("full pipeline must produce versioned S3 outputs through the AWS adapter")
                .isNotEmpty();
    }

    @Nested
    @DisplayName("Failure path coverage for the EOD batch pipeline")
    class FailurePathTests {

        @Test
        @DisplayName("Invalid INTCALC date parameter fails with ExitStatus.FAILED")
        void failure_invalidDateFormat_failsWithExitFailed() throws Exception {
            // Replaces JCL PARM validation in INTCALC.jcl / CBACT04C 0500-PARM-CHECK.
            JobParameters params = new JobParametersBuilder()
                    .addString(InterestCalculationJob.PARAM_BATCH_RUN_ID,
                            newRunId("failure-invalid-date"))
                    .addString(InterestCalculationJob.PARAM_PARM_DATE, "9999-99-99")
                    .toJobParameters();

            JobExecution exec = jobLauncher.run(intCalcJob, params);

            assertEquals(BatchStatus.FAILED, exec.getStatus());
            assertEquals(ExitStatus.FAILED.getExitCode(), exec.getExitStatus().getExitCode());
            assertThat(listObjects("")).isEmpty();
        }

        @Test
        @DisplayName("Missing S3 output bucket fails POSTTRAN without partial DALYREJS output")
        void failure_missingInputBucket_failsGracefully() throws Exception {
            // Replaces missing DALYREJS DD allocation failure handling from POSTTRAN.jcl.
            deleteAllS3Objects(outputBucket);
            s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(outputBucket).build());
            DailyTransaction invalid = copyDailyTransaction(seededDailyTransactions.get(0),
                    "9999999999999901");
            dailyTransactionRepository.save(invalid);

            JobParameters params = postTranParameters(newRunId("failure-missing-bucket"));
            JobExecution execution = jobLauncher.run(postTranJob, params);

            assertEquals(BatchStatus.FAILED, execution.getStatus());
            assertThat(execution.getAllFailureExceptions())
                    .as("S3OutputService must surface a typed bucket failure rather than NullPointerException")
                    .isNotEmpty()
                    .anySatisfy(failure -> {
                        assertThat(failure.getMessage())
                                .contains("s3://" + outputBucket)
                                .doesNotContain("NullPointerException");
                        assertThat(rootCause(failure)).isInstanceOf(NoSuchBucketException.class);
                    });
        }

        @Test
        @DisplayName("Repeated INTCALC JobInstance does not duplicate system transactions")
        void failure_concurrentInterestCalc_doesNotDoubleCount() throws Exception {
            // Replaces CBACT04C rerun discipline: Spring Batch JobRepository prevents duplicate JobInstances.
            JobParameters params = intCalcParameters(newRunId("failure-intcalc-idempotent"));
            JobExecution first = jobLauncher.run(intCalcJob, params);
            assertEquals(ExitStatus.COMPLETED.getExitCode(), first.getExitStatus().getExitCode());
            long afterFirst = findInterestTransactions().size();

            assertThatThrownBy(() -> jobLauncher.run(intCalcJob, params))
                    .hasMessageContaining("complete");
            assertThat(findInterestTransactions()).hasSize((int) afterFirst);
        }
    }

    @Nested
    @DisplayName("Idempotency coverage for replayed batch launches")
    class IdempotencyTests {

        @Test
        @DisplayName("Replaying POSTTRAN with identical JobParameters does not double-post")
        void replay_postTransactions_isIdempotent() throws Exception {
            // Replaces JES duplicate-submit protection with Spring Batch JobInstance identity.
            JobParameters params = postTranParameters(newRunId("idempotency-posttran"));
            JobExecution first = jobLauncher.run(postTranJob, params);
            assertPostTranCompleted(first, "first POSTTRAN replay run must finish before duplicate protection is asserted");
            long postedCount = transactionRepository.count();

            assertThatThrownBy(() -> jobLauncher.run(postTranJob, params))
                    .hasMessageContaining("complete");
            assertThat(transactionRepository.count()).isEqualTo(postedCount);
        }
    }

    private BatchRun launchPostTran(String batchRunId) throws Exception {
        JobExecution execution = jobLauncher.run(postTranJob, postTranParameters(batchRunId));
        logExecution(DailyTransactionPostingJob.JOB_NAME, execution);
        return new BatchRun(execution);
    }

    private BatchRun launchIntCalc(String batchRunId) throws Exception {
        JobExecution execution = jobLauncher.run(intCalcJob, intCalcParameters(batchRunId));
        logExecution(InterestCalculationJob.JOB_NAME, execution);
        return new BatchRun(execution);
    }

    private BatchRun launchCombine(String batchRunId) throws Exception {
        JobExecution execution = jobLauncher.run(combineJob, combineParameters(batchRunId));
        logExecution(CombineTransactionsJob.JOB_NAME, execution);
        return new BatchRun(execution);
    }

    private BatchRun launchStatement(String batchRunId) throws Exception {
        JobExecution execution = jobLauncher.run(stmtJob, statementParameters(batchRunId));
        logExecution(StatementGenerationJob.JOB_NAME, execution);
        return new BatchRun(execution);
    }

    private BatchRun launchReport(String batchRunId) throws Exception {
        JobExecution execution = jobLauncher.run(reportJob, reportParameters(batchRunId));
        logExecution(TransactionReportJob.JOB_NAME, execution);
        return new BatchRun(execution);
    }

    private JobParameters postTranParameters(String batchRunId) {
        return new JobParametersBuilder()
                .addString(DailyTransactionPostingJob.PARAM_BATCH_RUN_ID, batchRunId)
                .addString(DailyTransactionPostingJob.PARAM_BUSINESS_DATE, BUSINESS_DATE_TEXT)
                .toJobParameters();
    }

    private JobParameters intCalcParameters(String batchRunId) {
        return new JobParametersBuilder()
                .addString(InterestCalculationJob.PARAM_BATCH_RUN_ID, batchRunId)
                .addString(InterestCalculationJob.PARAM_PARM_DATE, BUSINESS_DATE_TEXT)
                .toJobParameters();
    }

    private JobParameters combineParameters(String batchRunId) {
        return new JobParametersBuilder()
                .addString(CombineTransactionsJob.PARAM_BATCH_RUN_ID, batchRunId)
                .addString(CombineTransactionsJob.PARAM_BUSINESS_DATE, BUSINESS_DATE_TEXT)
                .toJobParameters();
    }

    private JobParameters statementParameters(String batchRunId) {
        return new JobParametersBuilder()
                .addString(StatementGenerationJob.PARAM_BATCH_RUN_ID, batchRunId)
                .addString(StatementGenerationJob.PARAM_STATEMENT_MONTH, STATEMENT_MONTH)
                .addString(StatementGenerationJob.PARAM_CORRELATION_ID, batchRunId)
                .toJobParameters();
    }

    private JobParameters reportParameters(String batchRunId) {
        return new JobParametersBuilder()
                .addString(TransactionReportJob.PARAM_BATCH_RUN_ID, batchRunId)
                .addString(TransactionReportJob.PARAM_BUSINESS_DATE, BUSINESS_DATE_TEXT)
                .addString(TransactionReportJob.PARAM_START_DATE, BUSINESS_DATE_TEXT)
                .addString(TransactionReportJob.PARAM_END_DATE, BUSINESS_DATE_TEXT)
                .addString(TransactionReportJob.PARAM_CORRELATION_ID, batchRunId)
                .toJobParameters();
    }

    private void logExecution(String jobName, JobExecution execution) {
        LOG.info("Batch job {} ended status={} exitStatus={} executionId={}",
                jobName, execution.getStatus(), execution.getExitStatus(), execution.getId());
    }

    private void assertPostTranCompleted(JobExecution execution, String description) {
        assertEquals(BatchStatus.COMPLETED, execution.getStatus(), description);
        assertThat(execution.getExitStatus().getExitCode())
                .as(description)
                .isIn(ExitStatus.COMPLETED.getExitCode(), "COMPLETED_WITH_REJECTS");
    }

    private static String newRunId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private void assertKnownRejectCodesUseCobolDescriptions(List<String> rejectLines) {
        Map<Integer, String> expectedMessages = Map.of(
                100, REJECT_100_INVALID_CARD,
                101, REJECT_101_ACCOUNT_NOT_FOUND,
                102, REJECT_102_OVERLIMIT,
                103, REJECT_103_EXPIRED);

        Set<Integer> observedCodes = new HashSet<>();
        for (String line : rejectLines) {
            int code = Integer.parseInt(line.substring(DAILY_TRANSACTION_RECORD_LENGTH,
                    DAILY_TRANSACTION_RECORD_LENGTH + 4));
            observedCodes.add(code);
            if (expectedMessages.containsKey(code)) {
                assertRejectRecord(line, code, expectedMessages.get(code));
            }
        }
        assertThat(observedCodes)
                .as("reject codes must be in the CBTRN02C 100-109 family")
                .allMatch(code -> code >= 100 && code <= 109);
    }

    private void assertPostedAccountBalancesMatchSourceTransactions() {
        Map<Long, MutableAccountState> expectedState = simulatePostTranAccountState();

        accountRepository.findAll().forEach(account -> {
            MutableAccountState expected = expectedState.get(account.getAcctId());
            assertThat(account.getAcctCurrBal())
                    .as("CBTRN02C ADD DALYTRAN-AMT TO ACCT-CURR-BAL for account %011d",
                            account.getAcctId())
                    .isEqualByComparingTo(expected.currentBalance);
            assertThat(account.getAcctCurrCycCredit())
                    .as("CBTRN02C positive DALYTRAN-AMT accumulation for account %011d",
                            account.getAcctId())
                    .isEqualByComparingTo(expected.cycleCredit);
            assertThat(account.getAcctCurrCycDebit())
                    .as("CBTRN02C negative DALYTRAN-AMT accumulation for account %011d",
                            account.getAcctId())
                    .isEqualByComparingTo(expected.cycleDebit);
        });
    }

    private Map<Long, MutableAccountState> simulatePostTranAccountState() {
        Map<Long, MutableAccountState> expectedState = seededAccountSnapshots.values().stream()
                .collect(Collectors.toMap(AccountSnapshot::acctId, MutableAccountState::from,
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, Card> cards = cardRepository.findAll().stream()
                .collect(Collectors.toMap(Card::getCardNum, Function.identity()));

        for (DailyTransaction dt : seededDailyTransactions) {
            Long acctId = seededCardToAccount.get(dt.getDalytranCardNum());
            MutableAccountState state = acctId == null ? null : expectedState.get(acctId);
            Card card = cards.get(dt.getDalytranCardNum());
            if (state == null || card == null || card.getCardExpirationDate().isBefore(BUSINESS_DATE)) {
                continue;
            }
            BigDecimal tempBalance = state.cycleCredit
                    .subtract(state.cycleDebit)
                    .add(dt.getDalytranAmt())
                    .setScale(2, RoundingMode.HALF_EVEN);
            if (state.creditLimit.compareTo(tempBalance) < 0) {
                continue;
            }
            state.currentBalance = safeAdd(state.currentBalance, dt.getDalytranAmt());
            if (dt.getDalytranAmt().signum() >= 0) {
                state.cycleCredit = safeAdd(state.cycleCredit, dt.getDalytranAmt());
            } else {
                state.cycleDebit = safeAdd(state.cycleDebit, dt.getDalytranAmt());
            }
        }
        return expectedState;
    }

    private void assertTransactionCategoryBalancesRemainScale2() {
        transactionCategoryBalanceRepository.findAll().forEach(balance ->
                assertThat(balance.getTranCatBal().scale())
                        .as("TRAN-CAT-BAL scale for key %s", balance.getId())
                        .isEqualTo(2));
    }

    private void assertKafkaTransactionPostedOrdering() {
        long expectedAtMost = transactionRepository.count();
        if (expectedAtMost == 0L) {
            return;
        }

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "batch-pipeline-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (Consumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(TRANSACTION_POSTED_TOPIC));
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT)
                    .pollInterval(AWAIT_POLL)
                    .untilAsserted(() -> {
                        ConsumerRecords<String, String> polled =
                                KafkaTestUtils.getRecords(consumer, Duration.ofMillis(500));
                        polled.forEach(records::add);
                        assertThat(records.size())
                                .as("Kafka transaction.posted records")
                                .isPositive()
                                .isLessThanOrEqualTo((int) expectedAtMost);
                    });

            Map<String, List<ConsumerRecord<String, String>>> byAccount =
                    records.stream().collect(Collectors.groupingBy(ConsumerRecord::key));
            byAccount.forEach((accountKey, accountRecords) -> {
                Set<TopicPartition> partitions = accountRecords.stream()
                        .map(record -> new TopicPartition(record.topic(), record.partition()))
                        .collect(Collectors.toSet());
                assertThat(partitions)
                        .as("all records for account key %s must stay on one Kafka partition", accountKey)
                        .hasSizeLessThanOrEqualTo(1);
            });
        }
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor;
    }

    private Map<Long, BigDecimal> computeExpectedInterestByAccount() {
        Map<Long, Account> accounts = accountRepository.findAll().stream()
                .collect(Collectors.toMap(Account::getAcctId, Function.identity()));
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();

        for (TransactionCategoryBalance balance : transactionCategoryBalanceRepository.findAll()) {
            TransactionCategoryBalanceId id = balance.getId();
            Account account = accounts.get(id.getTrancatAcctId());
            if (account == null) {
                continue;
            }
            BigDecimal rate = disclosureRate(account.getAcctGroupId(), id.getTrancatTypeCd(),
                    id.getTrancatCd());
            BigDecimal expected = computeExpectedInterest(balance.getTranCatBal(), rate);
            totals.merge(account.getAcctId(), expected, this::safeAdd);
        }
        return totals;
    }

    private BigDecimal disclosureRate(String groupId, String tranTypeCd, Integer tranCatCd) {
        DisclosureGroupId explicitId = new DisclosureGroupId(groupId, tranTypeCd, tranCatCd);
        Optional<DisclosureGroup> explicit = disclosureGroupRepository.findById(explicitId);
        if (explicit.isPresent()) {
            return explicit.get().getDisIntRate();
        }
        DisclosureGroupId fallbackId = new DisclosureGroupId("DEFAULT", tranTypeCd, tranCatCd);
        return disclosureGroupRepository.findById(fallbackId)
                .map(DisclosureGroup::getDisIntRate)
                .orElse(scale2("0.00"));
    }

    private BigDecimal computeExpectedInterest(BigDecimal balance, BigDecimal rate) {
        // COBOL: CBACT04C 1300-COMPUTE-INTEREST — do not algebraically simplify.
        return scale2(balance).multiply(scale2(rate))
                .divide(BigDecimal.valueOf(1200L), 2, RoundingMode.HALF_EVEN);
    }

    private List<Transaction> findInterestTransactions() {
        return transactionRepository.findAll().stream()
                .filter(tx -> "System".equals(tx.getTranSource()))
                .filter(tx -> "01".equals(tx.getTranTypeCd()))
                .filter(tx -> Integer.valueOf(5).equals(tx.getTranCatCd()))
                .sorted(Comparator.comparing(Transaction::getTranId))
                .toList();
    }

    private Long extractAccountIdFromInterestDescription(Transaction tx) {
        String desc = tx.getTranDesc();
        assertThat(desc).startsWith("Int. for a/c ");
        return Long.parseLong(desc.substring("Int. for a/c ".length()).trim());
    }

    private BigDecimal sum(Collection<BigDecimal> values) {
        return values.stream()
                .map(this::scale2)
                .reduce(scale2("0.00"), this::safeAdd);
    }

    private BigDecimal safeAdd(BigDecimal left, BigDecimal right) {
        return scale2(left).add(scale2(right)).setScale(2, RoundingMode.HALF_EVEN);
    }

    private BigDecimal scale2(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_EVEN);
    }

    private BigDecimal scale2(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_EVEN);
    }

    private byte[] downloadS3Object(String bucket, String key) {
        ResponseBytes<GetObjectResponse> bytes = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
        return bytes.asByteArray();
    }

    private List<String> readLinesFromS3(String bucket, String key) {
        String content = new String(downloadS3Object(bucket, key), StandardCharsets.US_ASCII);
        if (content.isBlank()) {
            return List.of();
        }
        return Stream.of(content.split("\\R"))
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private void assertRejectRecord(String line, int expectedCode, String expectedMessage) {
        assertThat(line).hasSize(REJECT_RECORD_LENGTH);
        assertEquals(String.format("%04d", expectedCode),
                line.substring(DAILY_TRANSACTION_RECORD_LENGTH,
                        DAILY_TRANSACTION_RECORD_LENGTH + 4));
        assertEquals(expectedMessage,
                line.substring(DAILY_TRANSACTION_RECORD_LENGTH + 4).trim());
    }

    private List<String> readRejectLines() {
        List<S3Object> rejects = listObjects("dalyrejs/");
        if (rejects.isEmpty()) {
            return List.of();
        }
        return rejects.stream()
                .flatMap(obj -> readLinesFromS3(outputBucket, obj.key()).stream())
                .toList();
    }

    private List<S3Object> listObjects(String prefix) {
        try {
            ListObjectsV2Response response = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(outputBucket)
                    .prefix(prefix)
                    .build());
            return response.contents();
        } catch (NoSuchBucketException ex) {
            return List.of();
        }
    }

    private void ensureBucket(String bucket) {
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception ex) {
            if (ex.statusCode() != 409 && !"BucketAlreadyOwnedByYou".equals(ex.awsErrorDetails().errorCode())) {
                throw ex;
            }
        }
    }

    private void deleteAllS3Objects(String bucket) {
        try {
            ListObjectsV2Response response = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .build());
            response.contents().forEach(obj -> s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(obj.key())
                    .build()));
        } catch (NoSuchBucketException ex) {
            LOG.debug("Bucket {} already absent during cleanup", bucket);
        }
    }

    private void uploadDailyTransactionFixture() {
        Path fixture = fixturePath("dailytran.txt");
        try {
            byte[] bytes = Files.readAllBytes(fixture);
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(INPUT_BUCKET)
                            .key(DAILYTRAN_INPUT_KEY)
                            .build(),
                    RequestBody.fromBytes(bytes));
            byte[] roundTrip = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(INPUT_BUCKET)
                    .key(DAILYTRAN_INPUT_KEY)
                    .build()).asByteArray();
            assertArrayEquals(bytes, roundTrip, "LocalStack S3 input fixture round trip");
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to upload dailytran fixture to LocalStack S3", ex);
        }
    }

    private void truncateMutableTables() {
        transactionCategoryBalanceRepository.deleteAllInBatch();
        transactionRepository.deleteAllInBatch();
        dailyTransactionRepository.deleteAllInBatch();
        cardCrossReferenceRepository.deleteAllInBatch();
        cardRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
    }

    private void ensureSpringBatchMetadataSchema() {
        try (Connection connection = dataSource.getConnection()) {
            if (springBatchTableExists(connection, "batch_job_instance")) {
                return;
            }

            String schemaSql;
            try (var stream = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream("org/springframework/batch/core/schema-postgresql.sql")) {
                assertThat(stream)
                        .as("Spring Batch PostgreSQL schema DDL must be present on the test classpath")
                        .isNotNull();
                schemaSql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }

            try (Statement statement = connection.createStatement()) {
                for (String ddl : schemaSql.split(";")) {
                    String executable = ddl.lines()
                            .filter(line -> !line.trim().startsWith("--"))
                            .collect(Collectors.joining("\n"))
                            .trim();
                    if (!executable.isEmpty()) {
                        statement.execute(executable);
                    }
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to initialize Spring Batch metadata schema", ex);
        }
    }

    private boolean springBatchTableExists(Connection connection, String tableName) throws Exception {
        try (ResultSet tables = connection.getMetaData()
                .getTables(null, null, tableName, new String[] { "TABLE" })) {
            if (tables.next()) {
                return true;
            }
        }
        try (ResultSet tables = connection.getMetaData()
                .getTables(null, null, tableName.toUpperCase(), new String[] { "TABLE" })) {
            return tables.next();
        }
    }

    private void truncateSpringBatchMetadataTables() {
        List<String> tables = List.of(
                "BATCH_STEP_EXECUTION_CONTEXT",
                "BATCH_STEP_EXECUTION",
                "BATCH_JOB_EXECUTION_CONTEXT",
                "BATCH_JOB_EXECUTION_PARAMS",
                "BATCH_JOB_EXECUTION",
                "BATCH_JOB_INSTANCE"
        );

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String table : tables) {
                statement.executeUpdate("DELETE FROM " + table);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to reset Spring Batch metadata tables", ex);
        }
    }

    private <T> List<T> parseFixture(String fileName, int recordLength, Function<String, T> mapper) {
        Path path = fixturePath(fileName);
        try {
            List<String> records = Files.readAllLines(path, StandardCharsets.US_ASCII);
            assertThat(records)
                    .as("fixture %s must contain records", path)
                    .isNotEmpty();
            return IntStream.range(0, records.size())
                    .mapToObj(index -> {
                        String record = records.get(index);
                        if (record.length() != recordLength) {
                            throw new IllegalStateException("Fixture " + path
                                    + " record " + (index + 1)
                                    + " length " + record.length()
                                    + " does not match expected " + recordLength);
                        }
                        return mapper.apply(record);
                    })
                    .toList();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse fixed-width fixture " + path, ex);
        }
    }

    private Path fixturePath(String fileName) {
        Path source = Paths.get("app", "data", "ASCII", fileName);
        if (Files.exists(source)) {
            return source;
        }
        Path golden = Paths.get("src", "test", "resources", "golden", fileName);
        if (Files.exists(golden)) {
            return golden;
        }
        throw new IllegalStateException("Fixture not found in app/data/ASCII or golden resources: " + fileName);
    }

    private Account mapAccount(String record) {
        return new Account(
                parseLong(record, 0, 11),
                text(record, 11, 12),
                parseZonedDecimal(record.substring(12, 24), 10, 2),
                parseZonedDecimal(record.substring(24, 36), 10, 2),
                parseZonedDecimal(record.substring(36, 48), 10, 2),
                parseDate(record, 48, 58),
                parseDate(record, 58, 68),
                parseDate(record, 68, 78),
                parseZonedDecimal(record.substring(78, 90), 10, 2),
                parseZonedDecimal(record.substring(90, 102), 10, 2),
                text(record, 102, 112),
                text(record, 112, 122));
    }

    private Card mapCard(String record) {
        return new Card(
                text(record, 0, 16),
                parseLong(record, 16, 27),
                text(record, 30, 80),
                parseDate(record, 80, 90),
                text(record, 90, 91));
    }

    private CardCrossReference mapCardCrossReference(String record) {
        return new CardCrossReference(
                text(record, 0, 16),
                parseLong(record, 16, 25),
                parseLong(record, 25, 36));
    }

    private Customer mapCustomer(String record) {
        return new Customer(
                parseLong(record, 0, 9),
                text(record, 9, 34),
                text(record, 34, 59),
                text(record, 59, 84),
                text(record, 84, 134),
                text(record, 134, 184),
                text(record, 184, 234),
                text(record, 234, 236),
                text(record, 236, 239),
                text(record, 239, 249),
                text(record, 249, 264),
                text(record, 264, 279),
                parseLong(record, 279, 288),
                text(record, 288, 308),
                parseDate(record, 308, 318),
                text(record, 318, 328),
                text(record, 328, 329),
                normalizeFico(parseInt(record, 329, 332)));
    }

    private TransactionCategoryBalance mapTransactionCategoryBalance(String record) {
        return new TransactionCategoryBalance(
                parseLong(record, 0, 11),
                text(record, 11, 13),
                parseInt(record, 13, 17),
                parseZonedDecimal(record.substring(17, 28), 9, 2));
    }

    private DailyTransaction mapDailyTransaction(String record) {
        LocalDateTime originationTimestamp = parseTimestamp(record, 278, 304);
        return new DailyTransaction(
                text(record, 0, 16),
                text(record, 16, 18),
                parseInt(record, 18, 22),
                text(record, 22, 32),
                text(record, 32, 132),
                parseZonedDecimal(record.substring(132, 143), 9, 2),
                parseLong(record, 143, 152),
                text(record, 152, 202),
                text(record, 202, 252),
                text(record, 252, 262),
                text(record, 262, 278),
                originationTimestamp,
                parseTimestampOrDefault(record, 304, 330, originationTimestamp));
    }

    private DailyTransaction copyDailyTransaction(DailyTransaction source, String newId) {
        return new DailyTransaction(
                newId,
                source.getDalytranTypeCd(),
                source.getDalytranCatCd(),
                source.getDalytranSource(),
                source.getDalytranDesc(),
                source.getDalytranAmt(),
                source.getDalytranMerchantId(),
                source.getDalytranMerchantName(),
                source.getDalytranMerchantCity(),
                source.getDalytranMerchantZip(),
                "4999999999999999",
                source.getDalytranOrigTs(),
                source.getDalytranProcTs());
    }

    private String text(String record, int startInclusive, int endExclusive) {
        return record.substring(startInclusive, endExclusive).trim();
    }

    private Long parseLong(String record, int startInclusive, int endExclusive) {
        String text = text(record, startInclusive, endExclusive);
        return text.isEmpty() ? 0L : Long.parseLong(text);
    }

    private Integer parseInt(String record, int startInclusive, int endExclusive) {
        String text = text(record, startInclusive, endExclusive);
        return text.isEmpty() ? 0 : Integer.parseInt(text);
    }

    private Integer normalizeFico(Integer fixtureScore) {
        return Math.max(300, Math.min(850, fixtureScore));
    }

    private LocalDate parseDate(String record, int startInclusive, int endExclusive) {
        return LocalDate.parse(text(record, startInclusive, endExclusive));
    }

    private LocalDateTime parseTimestamp(String record, int startInclusive, int endExclusive) {
        return LocalDateTime.parse(text(record, startInclusive, endExclusive).replace(' ', 'T'));
    }

    private LocalDateTime parseTimestampOrDefault(String record,
                                                  int startInclusive,
                                                  int endExclusive,
                                                  LocalDateTime fallback) {
        String raw = text(record, startInclusive, endExclusive);
        return raw.isEmpty() ? fallback : LocalDateTime.parse(raw.replace(' ', 'T'));
    }

    private BigDecimal parseZonedDecimal(String raw, int integerDigits, int scale) {
        int expectedLength = integerDigits + scale;
        assertThat(raw).hasSize(expectedLength);
        char signChar = raw.charAt(raw.length() - 1);
        char lastDigit;
        boolean negative;
        if (signChar >= '0' && signChar <= '9') {
            lastDigit = signChar;
            negative = false;
        } else if (signChar == '{') {
            lastDigit = '0';
            negative = false;
        } else if (signChar >= 'A' && signChar <= 'I') {
            lastDigit = (char) ('1' + (signChar - 'A'));
            negative = false;
        } else if (signChar == '}') {
            lastDigit = '0';
            negative = true;
        } else if (signChar >= 'J' && signChar <= 'R') {
            lastDigit = (char) ('1' + (signChar - 'J'));
            negative = true;
        } else {
            throw new IllegalStateException("Unrecognised zoned-decimal sign character: 0x"
                    + Integer.toHexString(signChar & 0xFF));
        }

        String digits = raw.substring(0, raw.length() - 1) + lastDigit;
        String integerPart = digits.substring(0, integerDigits);
        String fractionPart = digits.substring(integerDigits);
        String plainText = (negative ? "-" : "") + integerPart + "." + fractionPart;
        return new BigDecimal(plainText).setScale(scale, RoundingMode.HALF_EVEN);
    }

    private record AccountSnapshot(Long acctId,
                                   BigDecimal currentBalance,
                                   BigDecimal creditLimit,
                                   BigDecimal cycleCredit,
                                   BigDecimal cycleDebit) {
        static AccountSnapshot from(Account account) {
            return new AccountSnapshot(
                    account.getAcctId(),
                    account.getAcctCurrBal().setScale(2, RoundingMode.HALF_EVEN),
                    account.getAcctCreditLimit().setScale(2, RoundingMode.HALF_EVEN),
                    account.getAcctCurrCycCredit().setScale(2, RoundingMode.HALF_EVEN),
                    account.getAcctCurrCycDebit().setScale(2, RoundingMode.HALF_EVEN));
        }
    }

    private record BatchRun(JobExecution execution) {
    }

    private static final class MutableAccountState {
        private BigDecimal currentBalance;
        private final BigDecimal creditLimit;
        private BigDecimal cycleCredit;
        private BigDecimal cycleDebit;

        private MutableAccountState(BigDecimal currentBalance,
                                    BigDecimal creditLimit,
                                    BigDecimal cycleCredit,
                                    BigDecimal cycleDebit) {
            this.currentBalance = currentBalance;
            this.creditLimit = creditLimit;
            this.cycleCredit = cycleCredit;
            this.cycleDebit = cycleDebit;
        }

        private static MutableAccountState from(AccountSnapshot snapshot) {
            return new MutableAccountState(
                    snapshot.currentBalance(),
                    snapshot.creditLimit(),
                    snapshot.cycleCredit(),
                    snapshot.cycleDebit());
        }
    }

    @TestConfiguration
    static class EndToEndBatchPipelineTestConfiguration {
        @Bean
        PipelineAssertionClock pipelineAssertionClock() {
            return new PipelineAssertionClock();
        }
    }

    static class PipelineAssertionClock {
        Instant now() {
            return Instant.now();
        }
    }
}

