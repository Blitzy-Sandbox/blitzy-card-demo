package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopRequestedException;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.transaction.TransactionPostingJob.ExecutionSummary;
import com.vsergeychik.carddemo.transaction.TransactionPostingJob.SysoutSink;
import com.vsergeychik.carddemo.transaction.model.DalyTranRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The branch-level check on {@link TransactionPostingJob}, the translation of {@code app/cbl/CBTRN01C.cbl}.
 */
@DisplayName("TransactionPostingJob - CBTRN01C, the orphan that posts nothing")
class TransactionPostingJobTest {
    private static final Charset CHARSET = StandardCharsets.US_ASCII;

    private static final String TEST_DSNAME = "TEST.CARDDEMO.DATASET";

    private static final String CARD_ONE = "4111111111111111";

    private static final String CARD_TWO = "4222222222222222";

    private static final String TRAN_ONE = "TRAN000000000001";

    private static final long ACCOUNT_ID = 99_999_999_999L;

    private static final int CUSTOMER_ID = 123_456_789;

    private static final String OTHER_STATUS = "35";

    private static final String DAILY_TRANSACTION_FIXTURE = "/fixtures/dailytran.txt";

    private static final String APPLICATION_CONFIGURATION = "/application.yml";

    private static final int FIXTURE_RECORD_COUNT = 300;

    private static final int NEGATIVE_OVERPUNCH_ROW_COUNT = 6;

    private static final int FIRST_NEGATIVE_OVERPUNCH_ROW = 2;

    private static final BigDecimal FIRST_NEGATIVE_OVERPUNCH_AMOUNT = new BigDecimal("-919.00");

    private static final char NEGATIVE_ZERO_OVERPUNCH = '}';

    private static final char POSITIVE_ZERO_OVERPUNCH = '{';

    private static final String NEGATIVE_ZERO_AMOUNT_IMAGE = "0000000000" + NEGATIVE_ZERO_OVERPUNCH;

    private static final String POSITIVE_ZERO_AMOUNT_IMAGE = "0000000000" + POSITIVE_ZERO_OVERPUNCH;

    private static final class Fixture {
        private final DalyTranRepository dalyTranRepository = mock(DalyTranRepository.class);

        private final CustomerRepository customerRepository = mock(CustomerRepository.class);

        private final CardXrefRepository cardXrefRepository = mock(CardXrefRepository.class);

        private final CardRepository cardRepository = mock(CardRepository.class);

        private final AccountRepository accountRepository = mock(AccountRepository.class);

        private final TransactionRepository transactionRepository = mock(TransactionRepository.class);

        private final DalyTranRepository.DalytranFile dalytranFile =
                mock(DalyTranRepository.DalytranFile.class);

        private final CustomerRepository.CustomerFile custfile =
                mock(CustomerRepository.CustomerFile.class);

        private final CardXrefRepository.BrowseCursor xrefCursor =
                mock(CardXrefRepository.BrowseCursor.class);

        private final CardRepository.CardBrowse cardBrowse = mock(CardRepository.CardBrowse.class);

        private final AccountRepository.AccountFile acctfile = mock(AccountRepository.AccountFile.class);

        private final TransactionRepository.InputFile tranfile =
                mock(TransactionRepository.InputFile.class);

        private final List<String> lines = new ArrayList<>();

        private Fixture() {
            when(dalyTranRepository.datasetCharset()).thenReturn(CHARSET);

            when(dalyTranRepository.open()).thenReturn(dalytranFile);
            when(dalytranFile.openStatus()).thenReturn(FileStatus.OK);
            when(dalytranFile.closeFile()).thenReturn(FileStatus.OK);
            when(dalytranFile.readNext()).thenReturn(DalyTranRepository.ReadResult.endOfFile());

            when(customerRepository.openInput()).thenReturn(custfile);
            when(custfile.openStatus()).thenReturn(FileStatus.OK);
            when(custfile.closeFile()).thenReturn(FileStatus.OK);

            when(cardXrefRepository.addressing(any(), anyString(), isNull(), anyString()))
                    .thenReturn(cardXrefRepository);
            when(cardXrefRepository.openBrowse()).thenReturn(xrefCursor);
            when(xrefCursor.openStatus()).thenReturn(FileStatus.OK);
            when(xrefCursor.closeBrowse()).thenReturn(FileStatus.OK);
            when(cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(CardXrefRepository.ReadResult.notFound(
                            TransactionPostingJob.XREFFILE_DD_NAME));

            when(cardRepository.addressing(any(), anyString())).thenReturn(cardRepository);
            when(cardRepository.openBrowse(anyString(), any())).thenReturn(cardBrowse);
            when(cardBrowse.openResp()).thenReturn(FileStatus.NORMAL);

            when(accountRepository.open(AccountRepository.OpenMode.INPUT)).thenReturn(acctfile);
            when(acctfile.openStatus()).thenReturn(FileStatus.OK);
            when(acctfile.closeFile()).thenReturn(FileStatus.OK);
            when(acctfile.readByKey(anyLong())).thenReturn(AccountRepository.ReadResult.notFound());

            when(transactionRepository.openInput(any(DatasetBinding.class))).thenReturn(tranfile);
            when(tranfile.openStatus()).thenReturn(FileStatus.OK);
            when(tranfile.closeInput()).thenReturn(FileStatus.OK);
        }

        private TransactionPostingJob job() {
            return job(validBatchConfig(), null);
        }

        private TransactionPostingJob job(BatchConfig batchConfig, SysoutSink published) {
            return new TransactionPostingJob(batchConfig, dalyTranRepository, customerRepository,
                    cardXrefRepository, cardRepository, accountRepository, transactionRepository,
                    new SuppliedProvider<>(published));
        }

        private SysoutSink sink() {
            return lines::add;
        }

        private ExecutionSummary run() {
            return job().execute(sink());
        }

        private void dailyTransactions(DalyTranRecord... records) {
            DalyTranRepository.ReadResult[] later =
                    new DalyTranRepository.ReadResult[Math.max(records.length, 1)];
            for (int index = 1; index < records.length; index++) {
                later[index - 1] = DalyTranRepository.ReadResult.found(records[index]);
            }
            later[Math.max(records.length - 1, 0)] = DalyTranRepository.ReadResult.endOfFile();
            when(dalytranFile.readNext())
                    .thenReturn(records.length == 0
                            ? DalyTranRepository.ReadResult.endOfFile()
                            : DalyTranRepository.ReadResult.found(records[0]), later);
        }
    }

    private static final class SuppliedProvider<T> implements ObjectProvider<T> {
        private final T bean;

        private SuppliedProvider(T bean) {
            this.bean = bean;
        }

        @Override
        public T getObject() {
            if (bean == null) {
                throw new NoSuchBeanDefinitionException("no bean of this type is published");
            }
            return bean;
        }

        @Override
        public T getIfAvailable() {
            return bean;
        }

        @Override
        public T getIfUnique() {
            return bean;
        }

        @Override
        public Stream<T> stream() {
            return bean == null ? Stream.empty() : Stream.of(bean);
        }
    }

    private static DatasetBinding binding(String ddName, int recordLength, String dsname) {
        return new DatasetBinding(dsname, DatasetBinding.KSDS, false, "FB", null, recordLength,
                ddName, 1, 0, null, null);
    }

    private static DatasetBindings validBindings() {
        return bindings(TransactionPostingJob.DALYTRAN_DD_NAME, DalyTranRecord.RECORD_LENGTH,
                TEST_DSNAME);
    }

    private static DatasetBindings bindings(String overriddenDd, int overriddenLength,
            String overriddenDsname) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(TransactionPostingJob.DALYTRAN_DD_NAME,
                binding(TransactionPostingJob.DALYTRAN_DD_NAME, DalyTranRecord.RECORD_LENGTH,
                        TEST_DSNAME));
        catalogue.put(TransactionPostingJob.CUSTFILE_DD_NAME,
                binding(TransactionPostingJob.CUSTFILE_DD_NAME, CustomerRecord.RECORD_LENGTH,
                        TEST_DSNAME));
        catalogue.put(TransactionPostingJob.XREFFILE_DD_NAME,
                binding(TransactionPostingJob.XREFFILE_DD_NAME, CardXrefRecord.RECORD_LENGTH,
                        TEST_DSNAME));
        catalogue.put(TransactionPostingJob.CARDFILE_DD_NAME,
                binding(TransactionPostingJob.CARDFILE_DD_NAME, CardRecord.RECORD_LENGTH, TEST_DSNAME));
        catalogue.put(TransactionPostingJob.ACCTFILE_DD_NAME,
                binding(TransactionPostingJob.ACCTFILE_DD_NAME, AccountRecord.RECORD_LENGTH,
                        TEST_DSNAME));
        catalogue.put(TransactionPostingJob.TRANFILE_DD_NAME,
                binding(TransactionPostingJob.TRANFILE_DD_NAME, TranRecord.RECORD_LENGTH, TEST_DSNAME));
        catalogue.put(overriddenDd, binding(overriddenDd, overriddenLength, overriddenDsname));
        return catalogue;
    }

    private static JobContracts contracts(String program, String stepName, boolean gated,
            List<JobParameterContract> parameters) {
        JobContracts catalogue = new JobContracts();
        catalogue.put(TransactionPostingJob.JOB_KEY, new JobContract(program, parameters,
                List.of(new StepContract(stepName, program, gated)), null, Map.of()));
        return catalogue;
    }

    private static JobContracts validContracts() {
        return contracts(TransactionPostingJob.PROGRAM_ID, TransactionPostingJob.STEP_NAME, false,
                List.of());
    }

    private static BatchConfig batchConfig(JobContracts contracts, DatasetBindings bindings) {
        return new BatchConfig(new SuppliedProvider<>(mock(JobRepository.class)),
                new SuppliedProvider<>(mock(PlatformTransactionManager.class)), contracts, bindings);
    }

    private static BatchConfig validBatchConfig() {
        return batchConfig(validContracts(), validBindings());
    }

    private static DalyTranRecord dalyTran(String id, String cardNum) {
        DalyTranRecord record = new DalyTranRecord(CHARSET);
        record.moveDalytranId(id);
        record.moveDalytranCardNum(cardNum);
        return record;
    }

    private static CardXrefRecord xref(String cardNum) {
        return new CardXrefRecord(cardNum, CUSTOMER_ID, ACCOUNT_ID);
    }

    private static CardXrefRepository.ReadResult xrefFound(String cardNum) {
        CardXrefRecord record = xref(cardNum);
        return CardXrefRepository.ReadResult.found(TransactionPostingJob.XREFFILE_DD_NAME, record,
                new String(record.encode(CHARSET), CHARSET));
    }

    private static AccountRecord account() {
        AccountRecord record = new AccountRecord(CHARSET);
        record.setAcctId(ACCOUNT_ID);
        return record;
    }

    private static String untouchedCardNumber() {
        return new DalyTranRecord(CHARSET).dalytranCardNum();
    }

    private static String untouchedTranId() {
        return new DalyTranRecord(CHARSET).dalytranId();
    }

    private static String notVerifiedLine(String cardNum, String tranId) {
        return TransactionPostingJob.CARD_NOT_VERIFIED_PREFIX + cardNum
                + TransactionPostingJob.CARD_NOT_VERIFIED_SUFFIX + tranId;
    }

    private static List<String> xrefSuccessLines(String cardNum) {
        return List.of(TransactionPostingJob.SUCCESSFUL_READ_OF_XREF,
                TransactionPostingJob.XREF_CARD_NUMBER_PREFIX + cardNum,
                TransactionPostingJob.XREF_ACCOUNT_ID_PREFIX + "99999999999",
                TransactionPostingJob.XREF_CUSTOMER_ID_PREFIX + "123456789");
    }

    private static List<String> classpathLines(String resource) {
        try (InputStream stream = TransactionPostingJobTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("classpath resource %s must exist", resource).isNotNull();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, CHARSET))) {
                return reader.lines().toList();
            }
        } catch (IOException problem) {
            throw new UncheckedIOException("could not read " + resource, problem);
        }
    }

    private static String dailyTransactionFixtureRow(int row) {
        List<String> rows = classpathLines(DAILY_TRANSACTION_FIXTURE);
        assertThat(rows).as("the fixture holds the row asked for").hasSizeGreaterThanOrEqualTo(row);
        return rows.get(row - 1);
    }

    private static DalyTranRecord dalyTranWithAmountImage(String image) {
        DalyTranRecord record = new DalyTranRecord(CHARSET);
        record.writeDalytranAmtImage(image);
        return record;
    }

    @Nested
    @DisplayName("Gate G13 - runnable, and triggered by nothing")
    class PublishedContract {
        @Test
        @DisplayName("the job bean is real, named for the contract key, and carries the single step")
        void theJobIsPublishedUnderTheContractDerivedName() {
            Fixture fixture = new Fixture();
            TransactionPostingJob subject = fixture.job();

            assertThat(TransactionPostingJob.JOB_NAME).isEqualTo("transactionPostingJob");
            assertThat(TransactionPostingJob.JOB_KEY).isEqualTo("transaction-posting-job");
            assertThat(subject.transactionPostingJob().getName())
                    .isEqualTo(TransactionPostingJob.JOB_NAME);
            assertThat(subject.transactionPostingStep().getName())
                    .isEqualTo(TransactionPostingJob.STEP_NAME);
            assertThat(subject.transactionPostingTasklet()).isNotNull();
        }

        @Test
        @DisplayName("the configuration bean name is qualified, so it cannot collide with the job bean")
        void theConfigurationBeanNameIsQualified() {
            assertThat(TransactionPostingJob.CONFIGURATION_BEAN_NAME)
                    .isEqualTo("transactionPostingJobConfiguration")
                    .isNotEqualTo(TransactionPostingJob.JOB_NAME);
        }

        @Test
        @DisplayName("no trigger of any kind: @Configuration is the only annotation and there are no "
                + "runner interfaces, scheduled methods or launcher fields")
        void nothingTriggersTheJob() {
            assertThat(TransactionPostingJob.class.getDeclaredAnnotations())
                    .as("only @Configuration; a @Scheduled or @EnableScheduling here would run the orphan")
                    .hasSize(1);
            assertThat(TransactionPostingJob.class.getInterfaces())
                    .as("no CommandLineRunner, no ApplicationRunner, no InitializingBean")
                    .isEmpty();
            assertThat(Stream.of(TransactionPostingJob.class.getDeclaredFields())
                    .map(field -> field.getType().getName()))
                    .as("no JobLauncher, runner or scheduler is held")
                    .noneMatch(name -> name.contains("JobLauncher") || name.contains("Runner")
                            || name.contains("Scheduler"));
            assertThat(Stream.of(TransactionPostingJob.class.getDeclaredMethods())
                    .flatMap(method -> Stream.of(method.getDeclaredAnnotations()))
                    .map(annotation -> annotation.annotationType().getName()))
                    .as("no method is scheduled")
                    .noneMatch(name -> name.contains("Scheduled"));
        }

        @Test
        @DisplayName("the job declares no parameters, because no JCL invokes CBTRN01C")
        void theJobDeclaresNoParameters() {
            Fixture fixture = new Fixture();

            assertThat(fixture.job().jobParameters().getParameters()).isEmpty();
        }

        @Test
        @DisplayName("GATE G13: registered, launchable, and left unlaunched - application.yml turns the "
                + "automatic job runner off, so an explicit launch is the only way CBTRN01C ever runs")
        void theOrphanIsRegisteredAndLeftUnlaunched() {
            Fixture fixture = new Fixture();

            assertThat(fixture.job().transactionPostingJob())
                    .as("the bean is real: G13 asks for a runnable job, not an absent one")
                    .isNotNull();
            assertThat(fixture.job().transactionPostingJob().isRestartable())
                    .as("a launch is a submission, not a resumption: every launch runs the single step "
                            + "from the beginning, which is what an EXEC card would have done had one "
                            + "existed")
                    .isFalse();
            assertThat(Stream.of(TransactionPostingJob.class.getDeclaredMethods())
                    .map(method -> method.getReturnType().getName()))
                    .as("this class publishes no runner, no launcher and no exit-code generator bean, so "
                            + "nothing it contributes to the context can start it either")
                    .noneMatch(name -> name.contains("Runner") || name.contains("Launcher")
                            || name.contains("Scheduler") || name.contains("ExitCodeGenerator"));

            List<String> configuration = classpathLines(APPLICATION_CONFIGURATION);
            int batchAt = configuration.indexOf("  batch:");
            assertThat(batchAt).as("application.yml declares spring.batch").isNotNegative();
            assertThat(configuration.subList(batchAt, batchAt + 3))
                    .as("spring.batch.job.enabled is false, so no Job bean - this one included - is "
                            + "launched at start-up; an explicit launch is the only way in")
                    .containsExactly("  batch:", "    job:", "      enabled: false");
        }

        @Test
        @DisplayName("the tasklet runs the program once and reports the read count as step metadata")
        void theTaskletRunsTheProgramAndReportsTheReadCount() throws Exception {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE), dalyTran("TRAN000000000002",
                    CARD_TWO));
            List<String> spooled = new ArrayList<>();
            StepExecution stepExecution =
                    new StepExecution(TransactionPostingJob.STEP_NAME, new JobExecution(1L));
            StepContribution contribution = new StepContribution(stepExecution);

            RepeatStatus status = fixture.job(validBatchConfig(), spooled::add)
                    .transactionPostingTasklet()
                    .execute(contribution, new ChunkContext(new StepContext(stepExecution)));

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            assertThat(contribution.getReadCount()).isEqualTo(2);
            assertThat(spooled).startsWith(TransactionPostingJob.START_BANNER)
                    .endsWith(TransactionPostingJob.END_BANNER);
        }

        @Test
        @DisplayName("a tasklet over an empty dataset reports no reads, and still finishes")
        void theTaskletReportsNoReadsForAnEmptyDataset() throws Exception {
            Fixture fixture = new Fixture();
            StepExecution stepExecution =
                    new StepExecution(TransactionPostingJob.STEP_NAME, new JobExecution(2L));
            StepContribution contribution = new StepContribution(stepExecution);
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            PrintStream standardOutput = System.out;
            RepeatStatus status;
            try {
                System.setOut(new PrintStream(captured, true, CHARSET));
                status = fixture.job().transactionPostingTasklet()
                        .execute(contribution, new ChunkContext(new StepContext(stepExecution)));
            } finally {
                System.setOut(standardOutput);
            }

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            assertThat(contribution.getReadCount()).isZero();
            DalyTranRecord neverRead = new DalyTranRecord(CHARSET);
            assertThat(captured.toString(CHARSET).split("\n", -1))
                    .as("the fallback sink emitted the empty pass verbatim, defect 1's extra lookup "
                            + "included, and the trailing terminator")
                    .containsExactly(TransactionPostingJob.START_BANNER,
                            TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF,
                            TransactionPostingJob.CARD_NOT_VERIFIED_PREFIX + neverRead.dalytranCardNum()
                                    + TransactionPostingJob.CARD_NOT_VERIFIED_SUFFIX
                                    + neverRead.dalytranId(),
                            TransactionPostingJob.END_BANNER,
                            "");
        }

        @Test
        @DisplayName("the validated step contract is the ungated STEP01 running CBTRN01C")
        void theStepContractIsTheOneConfigurationDeclares() {
            StepContract contract = new Fixture().job().stepContract();

            assertThat(contract.name()).isEqualTo(TransactionPostingJob.STEP_NAME);
            assertThat(contract.program()).isEqualTo(TransactionPostingJob.PROGRAM_ID);
            assertThat(contract.requirePrecedingExitCodeZero()).isFalse();
            assertThat(TransactionPostingJob.REQUIRED_STEPS).containsExactly(contract);
        }
    }

    @Nested
    @DisplayName("Constructor guards - a mis-declared contract fails at start-up")
    class ConstructorGuards {
        @Test
        @DisplayName("every collaborator is required")
        void everyCollaboratorIsRequired() {
            Fixture fixture = new Fixture();
            BatchConfig seam = validBatchConfig();
            ObjectProvider<SysoutSink> noSink = new SuppliedProvider<>(null);

            assertThatNullPointerException().isThrownBy(() -> new TransactionPostingJob(null,
                    fixture.dalyTranRepository, fixture.customerRepository, fixture.cardXrefRepository,
                    fixture.cardRepository, fixture.accountRepository, fixture.transactionRepository,
                    noSink));
            assertThatNullPointerException().isThrownBy(() -> new TransactionPostingJob(seam, null,
                    fixture.customerRepository, fixture.cardXrefRepository, fixture.cardRepository,
                    fixture.accountRepository, fixture.transactionRepository, noSink));
            assertThatNullPointerException().isThrownBy(() -> new TransactionPostingJob(seam,
                    fixture.dalyTranRepository, null, fixture.cardXrefRepository,
                    fixture.cardRepository, fixture.accountRepository, fixture.transactionRepository,
                    noSink));
            assertThatNullPointerException().isThrownBy(() -> new TransactionPostingJob(seam,
                    fixture.dalyTranRepository, fixture.customerRepository, null,
                    fixture.cardRepository, fixture.accountRepository, fixture.transactionRepository,
                    noSink));
            assertThatNullPointerException().isThrownBy(() -> new TransactionPostingJob(seam,
                    fixture.dalyTranRepository, fixture.customerRepository, fixture.cardXrefRepository,
                    null, fixture.accountRepository, fixture.transactionRepository, noSink));
            assertThatNullPointerException().isThrownBy(() -> new TransactionPostingJob(seam,
                    fixture.dalyTranRepository, fixture.customerRepository, fixture.cardXrefRepository,
                    fixture.cardRepository, null, fixture.transactionRepository, noSink));
            assertThatNullPointerException().isThrownBy(() -> new TransactionPostingJob(seam,
                    fixture.dalyTranRepository, fixture.customerRepository, fixture.cardXrefRepository,
                    fixture.cardRepository, fixture.accountRepository, null, noSink));
            assertThatNullPointerException().isThrownBy(() -> new TransactionPostingJob(seam,
                    fixture.dalyTranRepository, fixture.customerRepository, fixture.cardXrefRepository,
                    fixture.cardRepository, fixture.accountRepository, fixture.transactionRepository,
                    null));
        }

        @Test
        @DisplayName("a contract naming another program is refused, at the job and at the step")
        void aContractNamingAnotherProgramIsRefused() {
            Fixture fixture = new Fixture();

            JobContracts wrongJob = contracts("CBTRN02C", TransactionPostingJob.STEP_NAME, false,
                    List.of());
            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(wrongJob, validBindings()), null))
                    .withMessageContaining("carddemo.jobs.transaction-posting-job.program")
                    .withMessageContaining("CBTRN01C");

            JobContracts wrongStep = new JobContracts();
            wrongStep.put(TransactionPostingJob.JOB_KEY, new JobContract(
                    TransactionPostingJob.PROGRAM_ID, List.of(),
                    List.of(new StepContract(TransactionPostingJob.STEP_NAME, "CBTRN03C", false)),
                    null, Map.of()));
            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(wrongStep, validBindings()), null))
                    .withMessageContaining("].program");
        }

        @Test
        @DisplayName("a declared job parameter is refused: CBTRN01C has no EXEC card and no PARM")
        void aDeclaredParameterIsRefused() {
            Fixture fixture = new Fixture();
            JobContracts parameterised = contracts(TransactionPostingJob.PROGRAM_ID,
                    TransactionPostingJob.STEP_NAME, false,
                    List.of(new JobParameterContract("parmDate", "string", "2022071800")));

            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(parameterised, validBindings()), null))
                    .withMessageContaining("parameters declares")
                    .withMessageContaining("app/jcl/");
        }

        @Test
        @DisplayName("a gated step is refused: there is no COND to reproduce and no preceding step")
        void aGatedStepIsRefused() {
            Fixture fixture = new Fixture();
            JobContracts gated = contracts(TransactionPostingJob.PROGRAM_ID,
                    TransactionPostingJob.STEP_NAME, true, List.of());

            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(gated, validBindings()), null))
                    .withMessageContaining("require-preceding-exit-code-zero");
        }

        @Test
        @DisplayName("a renamed step, and a second step declared beside this one, are both refused")
        void theWholeStepSequenceIsCompared() {
            Fixture fixture = new Fixture();

            JobContracts renamed = contracts(TransactionPostingJob.PROGRAM_ID, "STEP15", false,
                    List.of());
            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(renamed, validBindings()), null));

            JobContracts twoSteps = new JobContracts();
            twoSteps.put(TransactionPostingJob.JOB_KEY, new JobContract(
                    TransactionPostingJob.PROGRAM_ID, List.of(),
                    List.of(new StepContract(TransactionPostingJob.STEP_NAME,
                                    TransactionPostingJob.PROGRAM_ID, false),
                            new StepContract("STEP02", TransactionPostingJob.PROGRAM_ID, false)),
                    null, Map.of()));
            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(twoSteps, validBindings()), null));
        }

        @ParameterizedTest
        @ValueSource(strings = { "DALYTRAN", "CUSTFILE", "XREFFILE", "CARDFILE", "ACCTFILE",
                "TRANFILE" })
        @DisplayName("a DD whose declared width contradicts its copybook is refused")
        void aWidthThatContradictsTheCopybookIsRefused(String ddName) {
            Fixture fixture = new Fixture();
            DatasetBindings wrongWidth = bindings(ddName, 1, TEST_DSNAME);

            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(validContracts(), wrongWidth), null))
                    .withMessageContaining("carddemo.datasets." + ddName + ".record-length");
        }

        @ParameterizedTest
        @ValueSource(strings = { "DALYTRAN", "CUSTFILE", "XREFFILE", "CARDFILE", "ACCTFILE",
                "TRANFILE" })
        @DisplayName("a DD that names no dataset is refused")
        void aBlankDatasetNameIsRefused(String ddName) {
            Fixture fixture = new Fixture();
            int width = validBindings().binding(ddName).recordLength();
            DatasetBindings blank = bindings(ddName, width, "   ");

            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(validContracts(), blank), null))
                    .withMessageContaining("carddemo.datasets." + ddName + ".dsname");
        }

        @Test
        @DisplayName("a DD that declares no dataset name at all is refused, and says so")
        void anUndeclaredDatasetNameIsRefused() {
            Fixture fixture = new Fixture();
            DatasetBindings undeclared = bindings(TransactionPostingJob.XREFFILE_DD_NAME,
                    CardXrefRecord.RECORD_LENGTH, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> fixture.job(batchConfig(validContracts(), undeclared), null))
                    .withMessageContaining("carddemo.datasets."
                            + TransactionPostingJob.XREFFILE_DD_NAME + ".dsname is not declared");
        }
    }

    @Nested
    @DisplayName("The pass - and defect 1, the extra lookup after end of file")
    class ThePass {
        @Test
        @DisplayName("an empty dataset still performs one lookup: the banners plus the post-EOF lookup")
        void anEmptyDatasetStillPerformsOneLookup() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions();

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines).containsExactly(
                    TransactionPostingJob.START_BANNER,
                    TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF,
                    notVerifiedLine(untouchedCardNumber(), untouchedTranId()),
                    TransactionPostingJob.END_BANNER);
            assertThat(summary.recordsRead()).isZero();
            assertThat(summary.xrefLookups()).isEqualTo(1);
            assertThat(summary.accountReads()).isZero();
            assertThat(summary.postEndOfFileLookups()).isEqualTo(1);
            assertThat(summary.completedCleanly()).isTrue();
        }

        @Test
        @DisplayName("one record, xref and account both found: thirteen lines, and the last five are the "
                + "post-EOF lookup repeated on the same card")
        void oneRecordFoundEverywhere() {
            Fixture fixture = new Fixture();
            DalyTranRecord record = dalyTran(TRAN_ONE, CARD_ONE);
            fixture.dailyTransactions(record);
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));

            ExecutionSummary summary = fixture.run();

            List<String> expected = new ArrayList<>();
            expected.add(TransactionPostingJob.START_BANNER);
            expected.add(record.displayImage());
            expected.addAll(xrefSuccessLines(CARD_ONE));
            expected.add(TransactionPostingJob.SUCCESSFUL_READ_OF_ACCOUNT_FILE);
            expected.addAll(xrefSuccessLines(CARD_ONE));
            expected.add(TransactionPostingJob.SUCCESSFUL_READ_OF_ACCOUNT_FILE);
            expected.add(TransactionPostingJob.END_BANNER);

            assertThat(fixture.lines).containsExactlyElementsOf(expected);
            assertThat(fixture.lines).hasSize(13);
            assertThat(summary.recordsRead()).isEqualTo(1);
            assertThat(summary.recordImageLinesDisplayed()).isEqualTo(1);
            assertThat(summary.xrefLookups()).isEqualTo(2);
            assertThat(summary.accountReads()).isEqualTo(2);
        }

        @Test
        @DisplayName("the record image is displayed once per record, never twice - unlike CBACT03C")
        void theRecordImageIsDisplayedOncePerRecord() {
            Fixture fixture = new Fixture();
            DalyTranRecord first = dalyTran(TRAN_ONE, CARD_ONE);
            DalyTranRecord second = dalyTran("TRAN000000000002", CARD_TWO);
            fixture.dailyTransactions(first, second);

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines).filteredOn(line -> line.equals(first.displayImage())).hasSize(1);
            assertThat(fixture.lines).filteredOn(line -> line.equals(second.displayImage())).hasSize(1);
            assertThat(summary.recordsRead()).isEqualTo(2);
            assertThat(summary.recordImageLinesDisplayed()).isEqualTo(2);
        }

        @Test
        @DisplayName("DEFECT 1: the post-EOF lookup fires exactly once, on the LAST record's card number")
        void thePostEndOfFileLookupUsesTheStaleCardNumber() {
            Fixture fixture = new Fixture();
            DalyTranRecord first = dalyTran(TRAN_ONE, CARD_ONE);
            DalyTranRecord second = dalyTran("TRAN000000000002", CARD_TWO);
            fixture.dailyTransactions(first, second);

            ExecutionSummary summary = fixture.run();

            verify(fixture.cardXrefRepository, times(3)).readByCardNumber(anyString());
            verify(fixture.cardXrefRepository, times(1)).readByCardNumber(CARD_ONE);
            verify(fixture.cardXrefRepository, times(2)).readByCardNumber(CARD_TWO);
            assertThat(summary.xrefLookups()).isEqualTo(3).isEqualTo(summary.recordsRead() + 1);

            assertThat(fixture.lines).filteredOn(
                    line -> line.equals(notVerifiedLine(CARD_TWO, "TRAN000000000002"))).hasSize(2);
        }

        @Test
        @DisplayName("the not-verified message is ONE line with four operands run together")
        void theNotVerifiedMessageIsASingleLine() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));

            fixture.run();

            assertThat(fixture.lines).contains("CARD NUMBER " + CARD_ONE
                    + " COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-" + TRAN_ONE);
        }

        @Test
        @DisplayName("a stop requested between records abandons the pass")
        void aStopRequestedBetweenRecordsAbandonsThePass() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            TransactionPostingJob subject = fixture.job();
            SysoutSink sink = fixture.sink();
            int[] consulted = { 0 };
            StopSignal stopAtOnce = () -> {
                if (consulted[0]++ > 0) {
                    throw new IllegalStateException("consulted more than once");
                }
                throw new StopRequestedExceptionDouble();
            };

            assertThatExceptionOfType(StopRequestedExceptionDouble.class)
                    .isThrownBy(() -> subject.execute(sink, stopAtOnce));
            assertThat(fixture.lines).containsExactly(TransactionPostingJob.START_BANNER);
            verify(fixture.dalytranFile, never()).readNext();
        }

        @Test
        @DisplayName("a null sink and a null stop signal are both refused")
        void nullArgumentsAreRefused() {
            TransactionPostingJob subject = new Fixture().job();

            assertThatNullPointerException().isThrownBy(() -> subject.execute(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> subject.execute(line -> { }, null))
                    .withMessageContaining("StopSignal.RUNNING");
        }

        @Test
        @DisplayName("the real StopSignal.RUNNING never interrupts a pass")
        void theRunningSignalNeverInterrupts() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));

            ExecutionSummary summary = fixture.job().execute(fixture.sink(), StopSignal.RUNNING);

            assertThat(summary.recordsRead()).isEqualTo(1);
        }

        @Test
        @DisplayName("why the stop refusal is stood in for: the framework's own type is unconstructible "
                + "from outside its package")
        void theFrameworkStopRefusalCannotBeConstructedHere() {
            assertThat(RuntimeException.class).isAssignableFrom(StopRequestedException.class);
            assertThat(Stream.of(StopRequestedException.class.getDeclaredConstructors())
                    .filter(constructor -> Modifier.isPublic(constructor.getModifiers())))
                    .as("no publicly accessible constructor, so the double is the only way to drive this")
                    .isEmpty();
            assertThat(StopRequestedExceptionDouble.class).isNotEqualTo(StopRequestedException.class);
        }
    }

    private static final class StopRequestedExceptionDouble extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private StopRequestedExceptionDouble() {
            super("stop requested");
        }
    }

    @Nested
    @DisplayName("The dead accesses - three files opened and closed, and nothing written anywhere")
    class DeadAccesses {
        @Test
        @DisplayName("CUSTFILE is opened and closed and NEVER read")
        void theCustomerFileIsOpenedAndClosedAndNeverRead() {
            Fixture fixture = new Fixture();

            fixture.run();

            verify(fixture.customerRepository).openInput();
            verify(fixture.custfile).openStatus();
            verify(fixture.custfile).closeFile();
            verifyNoMoreInteractions(fixture.custfile);
        }

        @Test
        @DisplayName("CARDFILE is opened and closed and NEVER read")
        void theCardFileIsOpenedAndClosedAndNeverRead() {
            Fixture fixture = new Fixture();

            fixture.run();

            verify(fixture.cardRepository).addressing(any(), anyString());
            verify(fixture.cardRepository).openBrowse(anyString(), any());
            verify(fixture.cardBrowse).openResp();
            verify(fixture.cardBrowse).endBrowse();
            verifyNoMoreInteractions(fixture.cardBrowse);
        }

        @Test
        @DisplayName("TRANFILE is opened and closed and NEVER read - the posting the name promises")
        void theTransactionFileIsOpenedAndClosedAndNeverTouched() {
            Fixture fixture = new Fixture();

            fixture.run();

            verify(fixture.transactionRepository).openInput(any(DatasetBinding.class));
            verify(fixture.tranfile).openStatus();
            verify(fixture.tranfile).closeInput();
            verifyNoMoreInteractions(fixture.tranfile);
            verifyNoMoreInteractions(fixture.transactionRepository);
        }

        @Test
        @DisplayName("the account file is opened INPUT and never for update")
        void theAccountFileIsOpenedForInputOnly() {
            Fixture fixture = new Fixture();
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));

            fixture.run();

            verify(fixture.accountRepository).open(AccountRepository.OpenMode.INPUT);
            verify(fixture.accountRepository, never()).open(AccountRepository.OpenMode.I_O);
            verifyNoMoreInteractions(fixture.accountRepository);
        }

        @Test
        @DisplayName("the cross reference is read on the CCXREF base and never on the CXACAIX path")
        void theCrossReferenceIsReadOnTheBaseOnly() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));

            fixture.run();

            verify(fixture.cardXrefRepository, never()).readByAccountIdViaAltIndex(anyLong());
            verify(fixture.cardXrefRepository, never()).readByAccountIdViaAltIndex(anyString());
            verify(fixture.xrefCursor, never()).readNext();
        }
    }

    @Nested
    @DisplayName("Gates G19 and G21 - copybook widths, with FILLER present and space-filled")
    class CopybookGeometry {
        @Test
        @DisplayName("DALYTRAN-RECORD is 350 bytes - app/cpy/CVTRA06Y.cpy, RECLN 350")
        void theDailyTransactionRecordIs350Bytes() {
            assertThat(DalyTranRecord.RECORD_LENGTH).isEqualTo(350);
            assertThat(DalyTranRecord.sumOfDeclaredSpanLengths())
                    .as("every byte of the record is claimed by a declared span, FILLER included")
                    .isEqualTo(DalyTranRecord.RECORD_LENGTH);
            assertThat(DalyTranRecord.FILLER_OFFSET).isEqualTo(330);
            assertThat(DalyTranRecord.FILLER_LENGTH).isEqualTo(20);
            assertThat(DalyTranRecord.FILLER_OFFSET + DalyTranRecord.FILLER_LENGTH)
                    .isEqualTo(DalyTranRecord.RECORD_LENGTH);

            DalyTranRecord record = dalyTran(TRAN_ONE, CARD_ONE);
            assertThat(record.encode(CHARSET)).hasSize(DalyTranRecord.RECORD_LENGTH);
            assertThat(record.displayImage()).hasSize(DalyTranRecord.RECORD_LENGTH);
            assertThat(record.filler())
                    .as("FILLER is emitted as spaces, not omitted and not zero-filled")
                    .isEqualTo(" ".repeat(DalyTranRecord.FILLER_LENGTH))
                    .isBlank();
        }

        @Test
        @DisplayName("CARD-XREF-RECORD is 50 bytes including FILLER X(14) - app/cpy/CVACT03Y.cpy, RECLN 50")
        void theCrossReferenceRecordIs50Bytes() {
            assertThat(CardXrefRecord.RECORD_LENGTH).isEqualTo(50);
            assertThat(CardXrefRecord.XREF_CARD_NUM_LENGTH + CardXrefRecord.XREF_CUST_ID_LENGTH
                    + CardXrefRecord.XREF_ACCT_ID_LENGTH).isEqualTo(36);
            assertThat(CardXrefRecord.FILLER_OFFSET).isEqualTo(36);
            assertThat(CardXrefRecord.FILLER_LENGTH).isEqualTo(14);
            assertThat(CardXrefRecord.FILLER_OFFSET + CardXrefRecord.FILLER_LENGTH)
                    .isEqualTo(CardXrefRecord.RECORD_LENGTH);

            byte[] encoded = xref(CARD_ONE).encode(CHARSET);
            assertThat(encoded).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(new String(encoded, CHARSET).substring(CardXrefRecord.FILLER_OFFSET))
                    .as("the trailing 14 bytes are spaces, which is what makes the record 50 and not 36")
                    .isEqualTo(" ".repeat(CardXrefRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("ACCOUNT-RECORD is 300 bytes including FILLER X(178) - app/cpy/CVACT01Y.cpy, RECLN 300")
        void theAccountRecordIs300Bytes() {
            assertThat(AccountRecord.RECORD_LENGTH).isEqualTo(300);
            assertThat(AccountRecord.KEY_LENGTH)
                    .as("ACCT-ID PIC 9(11) is the record key - app/cpy/CVACT01Y.cpy:5")
                    .isEqualTo(11);

            AccountRecord record = account();
            assertThat(record.toByteArray()).hasSize(AccountRecord.RECORD_LENGTH);
            assertThat(record.toFixedWidthString()).hasSize(AccountRecord.RECORD_LENGTH);
            assertThat(AccountRecord.FILLER_OFFSET).isEqualTo(122);
            assertThat(AccountRecord.FILLER_LENGTH).isEqualTo(178);
            assertThat(AccountRecord.FILLER_OFFSET + AccountRecord.FILLER_LENGTH)
                    .isEqualTo(AccountRecord.RECORD_LENGTH);
            assertThat(record.raw(AccountRecord.SPAN_FILLER))
                    .as("FILLER is emitted as spaces, which is what makes the record 300 bytes wide")
                    .isEqualTo(" ".repeat(AccountRecord.FILLER_LENGTH));
            assertThat(AccountRecord.ACCT_EXPIRAION_DATE_NAME).isEqualTo("ACCT-EXPIRAION-DATE");
        }

        @Test
        @DisplayName("the DD widths the job validates at start-up are exactly these three plus the three "
                + "it opens and never reads")
        void theJobValidatesEveryDdAgainstItsCopybook() {
            assertThat(List.of(DalyTranRecord.RECORD_LENGTH, CustomerRecord.RECORD_LENGTH,
                    CardXrefRecord.RECORD_LENGTH, CardRecord.RECORD_LENGTH, AccountRecord.RECORD_LENGTH,
                    TranRecord.RECORD_LENGTH))
                    .as("CVTRA06Y 350, CVCUS01Y 500, CVACT03Y 50, CVACT02Y 150, CVACT01Y 300, CVTRA05Y 350")
                    .containsExactly(350, 500, 50, 150, 300, 350);
        }
    }

    @Nested
    @DisplayName("1000-DALYTRAN-GET-NEXT - three statuses, two 88-levels, one abend")
    class StatusLadderAndAbend {
        @Test
        @DisplayName("ARM 1 - status '00' moves 0, APPL-AOK is TRUE, and the pass continues")
        void statusOkContinuesThePass() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(distinctRecords(2));

            ExecutionSummary summary = fixture.run();

            assertThat(FileStatus.APPL_AOK).as("88 APPL-AOK VALUE 0 - app/cbl/CBTRN01C.cbl:143").isZero();
            assertThat(FileStatus.isOk(FileStatus.OK)).isTrue();
            assertThat(summary.recordsRead()).isEqualTo(2);
            assertThat(summary.returnCode()).isEqualTo(AbendException.RETURN_CODE_OK);
            assertThat(fixture.lines).doesNotContain(TransactionPostingJob.ERROR_READING_DALYTRAN);
            assertThat(fixture.lines).doesNotContain(AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("ARM 2 - status '10' moves 16, APPL-AOK is FALSE and APPL-EOF TRUE, so the flag is "
                + "set and the loop ends without an abend")
        void endOfFileEndsTheLoopCleanly() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(distinctRecords(1));

            ExecutionSummary summary = fixture.run();

            assertThat(FileStatus.APPL_EOF).isEqualTo(16);
            assertThat(FileStatus.APPL_EOF).isNotEqualTo(FileStatus.APPL_AOK);
            assertThat(AbendException.RETURN_CODE_END_OF_FILE).isEqualTo(FileStatus.APPL_EOF);
            assertThat(FileStatus.isEndOfFile(FileStatus.END_OF_FILE)).isTrue();
            assertThat(fixture.lines).endsWith(TransactionPostingJob.END_BANNER);
            assertThat(summary.completedCleanly()).isTrue();
            assertThat(summary.returnCode()).isEqualTo(AbendException.RETURN_CODE_OK);
        }

        @ParameterizedTest(name = "status ''{0}'' is neither ''00'' nor ''10'', so APPL-RESULT becomes 12")
        @ValueSource(strings = { "35", "37", "39", "41", "92", FileStatus.NOT_FOUND, FileStatus.DUPLICATE,
            FileStatus.RECORD_LENGTH_CONFLICT })
        @DisplayName("ARM 3 - any other status moves 12, both 88-levels are FALSE, and the run abends")
        void anyOtherStatusAbends(String status) {
            Fixture fixture = new Fixture();
            when(fixture.dalytranFile.readNext())
                    .thenReturn(DalyTranRepository.ReadResult.other(status));

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(fixture::run).actual();

            assertThat(fixture.lines).containsExactly(
                    TransactionPostingJob.START_BANNER,
                    TransactionPostingJob.ERROR_READING_DALYTRAN,
                    FileStatus.toDisplayLine(status),
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode())
                    .as("MOVE 12 TO APPL-RESULT at :210 - neither 88-level is satisfied")
                    .isEqualTo(TransactionPostingJob.APPL_RESULT_FATAL)
                    .isNotEqualTo(FileStatus.APPL_AOK)
                    .isNotEqualTo(FileStatus.APPL_EOF);
        }

        @Test
        @DisplayName("GATE G35 - the abend carries ABCODE 999 and TIMING 0, exactly as Z-ABEND-PROGRAM "
                + "sets them before CALL 'CEE3ABD'")
        void theAbendCarriesTheAbendCodeAndTiming() {
            Fixture fixture = new Fixture();
            when(fixture.dalytranFile.readNext())
                    .thenReturn(DalyTranRepository.ReadResult.other(OTHER_STATUS));

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(fixture::run).actual();

            assertThat(abend.getAbendCode())
                    .as("MOVE 999 TO ABCODE - :472")
                    .hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(AbendException.STANDARD_ABEND_CODE).isEqualTo(999);
            assertThat(abend.getTiming())
                    .as("MOVE 0 TO TIMING - :471; zero means abend now rather than at the next "
                            + "synchronisation point")
                    .hasValue(AbendException.STANDARD_TIMING);
            assertThat(AbendException.STANDARD_TIMING).isZero();
            assertThat(abend.getProgram()).isEqualTo(TransactionPostingJob.PROGRAM_ID);
            assertThat(abend.hasAbendCode()).isTrue();
            assertThat(abend.hasTiming()).isTrue();
        }

        @Test
        @DisplayName("the exit code is one of the four COBOL-observable values, and on the happy path it "
                + "is 0 - CBTRN01C sets no RETURN-CODE of its own")
        void theExitCodeIsOneOfTheFourObservableValues() {
            List<Integer> observable = List.of(AbendException.RETURN_CODE_OK,
                    AbendException.RETURN_CODE_WARNING,
                    TransactionPostingJob.APPL_RESULT_ASSUMED_FAILURE,
                    TransactionPostingJob.APPL_RESULT_FATAL);
            assertThat(observable).containsExactly(0, 4, 8, 12);

            Fixture clean = new Fixture();
            clean.dailyTransactions(distinctRecords(2));
            assertThat(clean.run().returnCode()).isZero().isIn(observable);

            Fixture failing = new Fixture();
            when(failing.dalytranFile.readNext())
                    .thenReturn(DalyTranRepository.ReadResult.other(OTHER_STATUS));
            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(failing::run).actual();
            assertThat(abend.getReturnCode()).isEqualTo(12).isIn(observable);
        }

        @Test
        @DisplayName("Z-DISPLAY-IO-STATUS: both of its arms are reachable from this program - the numeric "
                + "one and the extended '9' one")
        void bothArmsOfTheStatusRendererAreReachable() {
            Fixture numeric = new Fixture();
            when(numeric.dalytranFile.readNext())
                    .thenReturn(DalyTranRepository.ReadResult.other(OTHER_STATUS));
            assertThatExceptionOfType(AbendException.class).isThrownBy(numeric::run);
            assertThat(numeric.lines).contains(FileStatus.DISPLAY_PREFIX + "0035");

            Fixture extended = new Fixture();
            when(extended.dalytranFile.readNext()).thenThrow(new IllegalStateException("driver refused"));
            assertThatExceptionOfType(AbendException.class).isThrownBy(extended::run);
            assertThat(extended.lines).contains(FileStatus.DISPLAY_PREFIX + "9000");
            assertThat(TransactionPostingJob.PERMANENT_ERROR_STATUS).startsWith("9")
                    .hasSize(FileStatus.STATUS_LENGTH);
        }

        @ParameterizedTest(name = "the {0} OPEN failure abends with ABCODE 999 and TIMING 0")
        @ValueSource(strings = { "DALYTRAN", "CUSTFILE", "XREFFILE", "ACCTFILE", "TRANFILE" })
        @DisplayName("every OPEN failure arm reaches the same Z-ABEND-PROGRAM, with its own literal")
        void everyOpenFailureArmAbendsWithItsOwnLiteral(String ddName) {
            Fixture fixture = new Fixture();
            String literal = failOpenOf(fixture, ddName);

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(fixture::run).actual();

            assertThat(fixture.lines).containsExactly(TransactionPostingJob.START_BANNER, literal,
                    FileStatus.toDisplayLine(OTHER_STATUS), AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode()).isEqualTo(TransactionPostingJob.APPL_RESULT_FATAL);
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
        }

        @ParameterizedTest(name = "the {0} CLOSE failure abends with ABCODE 999 and TIMING 0")
        @ValueSource(strings = { "CUSTFILE", "XREFFILE", "ACCTFILE", "TRANFILE" })
        @DisplayName("every CLOSE failure arm reaches the same Z-ABEND-PROGRAM, with its own literal")
        void everyCloseFailureArmAbendsWithItsOwnLiteral(String ddName) {
            Fixture fixture = new Fixture();
            String literal = failCloseOf(fixture, ddName);

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(fixture::run).actual();

            assertThat(fixture.lines).containsSequence(literal,
                    FileStatus.toDisplayLine(OTHER_STATUS), AbendException.ABEND_DISPLAY_TEXT);
            assertThat(fixture.lines)
                    .as("the closing banner at :195 is never reached - CEE3ABD does not return")
                    .doesNotContain(TransactionPostingJob.END_BANNER);
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
        }

        private String failOpenOf(Fixture fixture, String ddName) {
            switch (ddName) {
                case "DALYTRAN" -> {
                    when(fixture.dalytranFile.openStatus()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_OPENING_DALYTRAN;
                }
                case "CUSTFILE" -> {
                    when(fixture.custfile.openStatus()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_OPENING_CUSTFILE;
                }
                case "XREFFILE" -> {
                    when(fixture.xrefCursor.openStatus()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_OPENING_XREFFILE;
                }
                case "ACCTFILE" -> {
                    when(fixture.acctfile.openStatus()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_OPENING_ACCTFILE;
                }
                case "TRANFILE" -> {
                    when(fixture.tranfile.openStatus()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_OPENING_TRANFILE;
                }
                default -> throw new IllegalArgumentException("no such open paragraph: " + ddName);
            }
        }

        private String failCloseOf(Fixture fixture, String ddName) {
            switch (ddName) {
                case "CUSTFILE" -> {
                    when(fixture.custfile.closeFile()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_CLOSING_CUSTFILE;
                }
                case "XREFFILE" -> {
                    when(fixture.xrefCursor.closeBrowse()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_CLOSING_XREFFILE;
                }
                case "ACCTFILE" -> {
                    when(fixture.acctfile.closeFile()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_CLOSING_ACCTFILE;
                }
                case "TRANFILE" -> {
                    when(fixture.tranfile.closeInput()).thenReturn(OTHER_STATUS);
                    return TransactionPostingJob.ERROR_CLOSING_TRANFILE;
                }
                default -> throw new IllegalArgumentException("no such close paragraph: " + ddName);
            }
        }
    }

    @Nested
    @DisplayName("The mainline guards - all nine combinations of the two read-status flags")
    class GuardStructure {
        private static final int ITERATIONS = 2;

        @ParameterizedTest(name = "xref {0} + account {1}: reads={2} notFoundLine={3} skipLine={4}")
        @CsvSource({
            "FOUND,         FOUND,        true,          false,        false",
            "FOUND,         INVALID_KEY,  true,          true,         false",
            "FOUND,         OTHER,        true,          false,        false",
            "INVALID_KEY,   FOUND,        false,         false,        true",
            "INVALID_KEY,   INVALID_KEY,  false,         false,        true",
            "INVALID_KEY,   OTHER,        false,         false,        true",
            "OTHER,         FOUND,        true,          false,        false",
            "OTHER,         INVALID_KEY,  true,          true,         false",
            "OTHER,         OTHER,        true,          false,        false",
        })
        @DisplayName("the guards at :173, :177 and :180 select exactly these arms")
        void theGuardsSelectExactlyTheseArms(String xrefArm, String acctArm, boolean accountIsRead,
                boolean notFoundLine, boolean skipLine) {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefOutcome(xrefArm));
            when(fixture.acctfile.readByKey(anyLong())).thenReturn(accountOutcome(acctArm));

            ExecutionSummary summary = fixture.run();

            assertThat(summary.accountReads())
                    .as("3000-READ-ACCOUNT runs only when WS-XREF-READ-STATUS is still zero")
                    .isEqualTo(accountIsRead ? ITERATIONS : 0);
            verify(fixture.acctfile, times(accountIsRead ? ITERATIONS : 0)).readByKey(anyLong());

            String expectedAcctId = "FOUND".equals(xrefArm) ? "99999999999" : "00000000000";
            assertThat(countOf(fixture, TransactionPostingJob.ACCOUNT_NOT_FOUND_PREFIX + expectedAcctId
                    + TransactionPostingJob.ACCOUNT_NOT_FOUND_SUFFIX))
                    .isEqualTo(notFoundLine ? ITERATIONS : 0);

            assertThat(countOf(fixture, notVerifiedLine(CARD_ONE, TRAN_ONE)))
                    .as("the skip line is the ELSE at :180, and nothing else reaches it")
                    .isEqualTo(skipLine ? ITERATIONS : 0);

            assertThat(notFoundLine && skipLine).isFalse();
        }

        @ParameterizedTest(name = "an INVALID KEY condition of ''{0}'' skips the transaction")
        @ValueSource(strings = { FileStatus.NOT_FOUND, FileStatus.DUPLICATE })
        @DisplayName("both INVALID KEY statuses take the same guard arm, and no other status does")
        void bothInvalidKeyStatusesTakeTheSameArm(String status) {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString())).thenReturn(
                    FileStatus.NOT_FOUND.equals(status)
                            ? CardXrefRepository.ReadResult.notFound(
                                    TransactionPostingJob.XREFFILE_DD_NAME)
                            : CardXrefRepository.ReadResult.duplicate(
                                    TransactionPostingJob.XREFFILE_DD_NAME, xref(CARD_ONE),
                                    new String(xref(CARD_ONE).encode(CHARSET), CHARSET),
                                    FileStatus.DUPREC));

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines).contains(TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF);
            assertThat(countOf(fixture, notVerifiedLine(CARD_ONE, TRAN_ONE))).isEqualTo(ITERATIONS);
            assertThat(summary.accountReads())
                    .as("WS-XREF-READ-STATUS became 4, so :173's guard closed")
                    .isZero();
        }

        private CardXrefRepository.ReadResult xrefOutcome(String arm) {
            return switch (arm) {
                case "FOUND" -> xrefFound(CARD_ONE);
                case "INVALID_KEY" -> CardXrefRepository.ReadResult.notFound(
                        TransactionPostingJob.XREFFILE_DD_NAME);
                case "OTHER" -> CardXrefRepository.ReadResult.other(
                        TransactionPostingJob.XREFFILE_DD_NAME, OTHER_STATUS);
                default -> throw new IllegalArgumentException("unknown cross-reference arm: " + arm);
            };
        }

        private AccountRepository.ReadResult accountOutcome(String arm) {
            return switch (arm) {
                case "FOUND" -> AccountRepository.ReadResult.found(account());
                case "INVALID_KEY" -> AccountRepository.ReadResult.notFound();
                case "OTHER" -> AccountRepository.ReadResult.of(OTHER_STATUS);
                default -> throw new IllegalArgumentException("unknown account arm: " + arm);
            };
        }
    }

    private static long countOf(Fixture fixture, String line) {
        return fixture.lines.stream().filter(line::equals).count();
    }

    @Nested
    @DisplayName("The SYSOUT fingerprint - literals transcribed from CBTRN01C, not from the class")
    class SourceLiterals {
        @Test
        @DisplayName("the two banners: :156 at start-up and :195 at the end")
        void theBanners() {
            assertThat(TransactionPostingJob.START_BANNER)
                    .isEqualTo("START OF EXECUTION OF PROGRAM CBTRN01C");
            assertThat(TransactionPostingJob.END_BANNER)
                    .isEqualTo("END OF EXECUTION OF PROGRAM CBTRN01C");
        }

        @Test
        @DisplayName("the six OPEN literals, one per paragraph: :263, :282, :300, :318, :336, :354")
        void theSixOpenLiterals() {
            assertThat(TransactionPostingJob.ERROR_OPENING_DALYTRAN)
                    .isEqualTo("ERROR OPENING DAILY TRANSACTION FILE");
            assertThat(TransactionPostingJob.ERROR_OPENING_CUSTFILE)
                    .isEqualTo("ERROR OPENING CUSTOMER FILE");
            assertThat(TransactionPostingJob.ERROR_OPENING_XREFFILE)
                    .isEqualTo("ERROR OPENING CROSS REF FILE");
            assertThat(TransactionPostingJob.ERROR_OPENING_CARDFILE)
                    .isEqualTo("ERROR OPENING CARD FILE");
            assertThat(TransactionPostingJob.ERROR_OPENING_ACCTFILE)
                    .isEqualTo("ERROR OPENING ACCOUNT FILE");
            assertThat(TransactionPostingJob.ERROR_OPENING_TRANFILE)
                    .isEqualTo("ERROR OPENING TRANSACTION FILE");
        }

        @Test
        @DisplayName("the CLOSE literals - and the one that does not exist, because 9000 borrows the "
                + "customer file's")
        void theCloseLiterals() {
            assertThat(TransactionPostingJob.ERROR_CLOSING_CUSTFILE)
                    .isEqualTo("ERROR CLOSING CUSTOMER FILE");
            assertThat(TransactionPostingJob.ERROR_CLOSING_XREFFILE)
                    .isEqualTo("ERROR CLOSING CROSS REF FILE");
            assertThat(TransactionPostingJob.ERROR_CLOSING_CARDFILE)
                    .isEqualTo("ERROR CLOSING CARD FILE");
            assertThat(TransactionPostingJob.ERROR_CLOSING_ACCTFILE)
                    .isEqualTo("ERROR CLOSING ACCOUNT FILE");
            assertThat(TransactionPostingJob.ERROR_CLOSING_TRANFILE)
                    .isEqualTo("ERROR CLOSING TRANSACTION FILE");
            assertThat(publicConstantNames(TransactionPostingJob.class))
                    .doesNotContain("ERROR_CLOSING_DALYTRAN")
                    .contains("ERROR_CLOSING_CUSTFILE");
        }

        @Test
        @DisplayName("the read literal at :219, and the abend and file-status texts the Z-paragraphs emit")
        void theReadAndAbendLiterals() {
            assertThat(TransactionPostingJob.ERROR_READING_DALYTRAN)
                    .isEqualTo("ERROR READING DAILY TRANSACTION FILE");
            assertThat(AbendException.ABEND_DISPLAY_TEXT).isEqualTo("ABENDING PROGRAM");
            assertThat(FileStatus.DISPLAY_PREFIX).isEqualTo("FILE STATUS IS: NNNN");
        }

        @Test
        @DisplayName("2000-LOOKUP-XREF's four lines - and the column alignment that makes 'ACCOUNT ID : ' "
                + "carry a space before its colon")
        void theCrossReferenceLiterals() {
            assertThat(TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF)
                    .isEqualTo("INVALID CARD NUMBER FOR XREF");
            assertThat(TransactionPostingJob.SUCCESSFUL_READ_OF_XREF)
                    .isEqualTo("SUCCESSFUL READ OF XREF");
            assertThat(TransactionPostingJob.XREF_CARD_NUMBER_PREFIX).isEqualTo("CARD NUMBER: ");
            assertThat(TransactionPostingJob.XREF_ACCOUNT_ID_PREFIX).isEqualTo("ACCOUNT ID : ");
            assertThat(TransactionPostingJob.XREF_CUSTOMER_ID_PREFIX).isEqualTo("CUSTOMER ID: ");
            assertThat(TransactionPostingJob.XREF_ACCOUNT_ID_PREFIX.length())
                    .isEqualTo(TransactionPostingJob.XREF_CARD_NUMBER_PREFIX.length())
                    .isEqualTo(TransactionPostingJob.XREF_CUSTOMER_ID_PREFIX.length());
        }

        @Test
        @DisplayName("3000-READ-ACCOUNT's two lines at :246 and :249")
        void theAccountLiterals() {
            assertThat(TransactionPostingJob.INVALID_ACCOUNT_NUMBER_FOUND)
                    .isEqualTo("INVALID ACCOUNT NUMBER FOUND");
            assertThat(TransactionPostingJob.SUCCESSFUL_READ_OF_ACCOUNT_FILE)
                    .isEqualTo("SUCCESSFUL READ OF ACCOUNT FILE");
        }

        @Test
        @DisplayName("the mainline's two composed lines: 'ACCOUNT ... NOT FOUND' at :178 and the "
                + "four-operand skip line at :181-183")
        void theMainlineComposedLines() {
            assertThat(TransactionPostingJob.ACCOUNT_NOT_FOUND_PREFIX).isEqualTo("ACCOUNT ");
            assertThat(TransactionPostingJob.ACCOUNT_NOT_FOUND_SUFFIX).isEqualTo(" NOT FOUND");
            assertThat(TransactionPostingJob.CARD_NOT_VERIFIED_PREFIX).isEqualTo("CARD NUMBER ");
            assertThat(TransactionPostingJob.CARD_NOT_VERIFIED_SUFFIX)
                    .isEqualTo(" COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-");
            assertThat(TransactionPostingJob.CARD_NOT_VERIFIED_SUFFIX).endsWith("ID-")
                    .doesNotEndWith("ID- ");
            assertThat(notVerifiedLine(CARD_ONE, TRAN_ONE))
                    .isEqualTo("CARD NUMBER " + CARD_ONE
                            + " COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-" + TRAN_ONE)
                    .doesNotContain("\n");
        }

        @Test
        @DisplayName("a whole clean run's SYSOUT, in order, is exactly the fingerprint those literals "
                + "compose")
        void aWholeRunComposesTheFingerprint() {
            Fixture fixture = new Fixture();
            DalyTranRecord record = dalyTran(TRAN_ONE, CARD_ONE);
            fixture.dailyTransactions(record);
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));

            fixture.run();

            assertThat(fixture.lines).containsExactly(
                    "START OF EXECUTION OF PROGRAM CBTRN01C",
                    record.displayImage(),
                    "SUCCESSFUL READ OF XREF",
                    "CARD NUMBER: " + CARD_ONE,
                    "ACCOUNT ID : 99999999999",
                    "CUSTOMER ID: 123456789",
                    "SUCCESSFUL READ OF ACCOUNT FILE",
                    "SUCCESSFUL READ OF XREF",
                    "CARD NUMBER: " + CARD_ONE,
                    "ACCOUNT ID : 99999999999",
                    "CUSTOMER ID: 123456789",
                    "SUCCESSFUL READ OF ACCOUNT FILE",
                    "END OF EXECUTION OF PROGRAM CBTRN01C");
        }
    }

    private static List<String> publicConstantNames(Class<?> type) {
        return Stream.of(type.getDeclaredFields())
                .filter(field -> Modifier.isPublic(field.getModifiers())
                        && Modifier.isStatic(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers()))
                .map(Field::getName)
                .toList();
    }

    @Nested
    @DisplayName("DISPLAY DALYTRAN-RECORD - the raw 350-byte span, proven against the real fixture")
    class RecordImageIsTheRawSpan {
        @Test
        @DisplayName("the fixture itself is what the AAP says it is: 300 records of 350 bytes, 6 of them "
                + "carrying a negative overpunch in DALYTRAN-AMT")
        void theFixtureIsWhatTheAapSaysItIs() {
            List<String> rows = classpathLines(DAILY_TRANSACTION_FIXTURE);

            assertThat(rows).hasSize(FIXTURE_RECORD_COUNT);
            assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(DalyTranRecord.RECORD_LENGTH));

            int signByte = DalyTranRecord.DALYTRAN_AMT_OFFSET + DalyTranRecord.DALYTRAN_AMT_LENGTH - 1;
            assertThat(signByte).isEqualTo(142);
            assertThat(rows.stream().filter(row -> row.charAt(signByte) == NEGATIVE_ZERO_OVERPUNCH).count())
                    .isEqualTo(NEGATIVE_OVERPUNCH_ROW_COUNT);
            assertThat(rows.get(FIRST_NEGATIVE_OVERPUNCH_ROW - 1).charAt(signByte))
                    .as("row %d is the worked example used below", FIRST_NEGATIVE_OVERPUNCH_ROW)
                    .isEqualTo(NEGATIVE_ZERO_OVERPUNCH);
        }

        @Test
        @DisplayName("a real fixture row is displayed byte-for-byte, its negative overpunch included")
        void aRealFixtureRowIsDisplayedByteForByte() {
            String row = dailyTransactionFixtureRow(FIRST_NEGATIVE_OVERPUNCH_ROW);
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(DalyTranRecord.decode(row, CHARSET));

            fixture.run();

            assertThat(fixture.lines)
                    .as("the SYSOUT line is the fixture row, unchanged and untrimmed")
                    .contains(row);
            assertThat(fixture.lines).filteredOn(line -> line.length() == row.length())
                    .as("and it is the full record width, FILLER X(20) included")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("R4 and gate G22: the amount that row carries is a BigDecimal at the copybook's "
                + "scale, and it is negative")
        void theAmountIsABigDecimalAtTheDeclaredScale() {
            String row = dailyTransactionFixtureRow(FIRST_NEGATIVE_OVERPUNCH_ROW);

            DalyTranRecord record = DalyTranRecord.decode(row, CHARSET);

            assertThat(record.dalytranAmt())
                    .isEqualTo(FIRST_NEGATIVE_OVERPUNCH_AMOUNT)
                    .hasScaleOf(DalyTranRecord.DALYTRAN_AMT_SCALE);
            assertThat(record.dalytranAmt().signum()).isNegative();
            assertThat(record.dalytranAmtImage())
                    .as("the stored image keeps the overpunch rather than a leading minus")
                    .isEqualTo("0000009190" + NEGATIVE_ZERO_OVERPUNCH)
                    .hasSize(DalyTranRecord.DALYTRAN_AMT_LENGTH);
            assertThat(record.hasZeroDalytranAmt()).isFalse();
        }

        @Test
        @DisplayName("PROOF: a negative-zero amount displays as '}' although a BigDecimal round trip "
                + "renders '{' - so DISPLAY cannot be re-encoding the fields")
        void aNegativeZeroAmountProvesTheSpanIsRaw() {
            DalyTranRecord stored = dalyTranWithAmountImage(NEGATIVE_ZERO_AMOUNT_IMAGE);
            stored.moveDalytranId(TRAN_ONE);
            stored.moveDalytranCardNum(CARD_ONE);
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(stored);

            fixture.run();

            assertThat(stored.dalytranAmt().signum()).isZero();
            DalyTranRecord roundTripped = new DalyTranRecord(CHARSET);
            roundTripped.moveDalytranAmt(stored.dalytranAmt());
            assertThat(roundTripped.dalytranAmtImage()).isEqualTo(POSITIVE_ZERO_AMOUNT_IMAGE);

            String displayed = fixture.lines.stream()
                    .filter(line -> line.length() == DalyTranRecord.RECORD_LENGTH)
                    .findFirst()
                    .orElseThrow();
            assertThat(displayed).isEqualTo(stored.displayImage());
            assertThat(displayed.substring(DalyTranRecord.DALYTRAN_AMT_OFFSET,
                    DalyTranRecord.DALYTRAN_AMT_OFFSET + DalyTranRecord.DALYTRAN_AMT_LENGTH))
                    .as("the negative zero survived to SYSOUT, which a re-encode would have lost")
                    .isEqualTo(NEGATIVE_ZERO_AMOUNT_IMAGE)
                    .isNotEqualTo(POSITIVE_ZERO_AMOUNT_IMAGE);
        }

        @Test
        @DisplayName("all six overpunched rows display verbatim, not just the worked example")
        void allSixOverpunchedRowsDisplayVerbatim() {
            int signByte = DalyTranRecord.DALYTRAN_AMT_OFFSET + DalyTranRecord.DALYTRAN_AMT_LENGTH - 1;
            List<String> overpunched = classpathLines(DAILY_TRANSACTION_FIXTURE).stream()
                    .filter(row -> row.charAt(signByte) == NEGATIVE_ZERO_OVERPUNCH)
                    .toList();
            assertThat(overpunched).hasSize(NEGATIVE_OVERPUNCH_ROW_COUNT);

            for (String row : overpunched) {
                Fixture fixture = new Fixture();
                fixture.dailyTransactions(DalyTranRecord.decode(row, CHARSET));

                fixture.run();

                assertThat(fixture.lines).as("row displayed verbatim").contains(row);
                assertThat(DalyTranRecord.decode(row, CHARSET).dalytranAmt())
                        .as("every one of the six is a negative amount of non-zero magnitude")
                        .isNegative()
                        .hasScaleOf(DalyTranRecord.DALYTRAN_AMT_SCALE);
            }
        }

        @Test
        @DisplayName("a fixture row reaches the stream sink as 350 bytes plus one line feed, in the named "
                + "code page")
        void aFixtureRowReachesTheStreamSinkUnchanged() {
            String row = dailyTransactionFixtureRow(FIRST_NEGATIVE_OVERPUNCH_ROW);
            ByteArrayOutputStream captured = new ByteArrayOutputStream();

            new Fixture().job().sysoutSinkTo(captured).display(row);

            assertThat(captured.toString(CHARSET)).isEqualTo(row + "\n");
            assertThat(captured.toByteArray()).hasSize(DalyTranRecord.RECORD_LENGTH + 1);
        }
    }

    @Nested
    @DisplayName("VERIFIED NEGATIVE 1 - nothing is written, added, rewritten or deleted, anywhere")
    class NothingPosts {
        private static final int RECORDS = 5;

        private Fixture passOverManyRecords() {
            Fixture fixture = new Fixture();
            DalyTranRecord[] records = new DalyTranRecord[RECORDS];
            for (int index = 0; index < RECORDS; index++) {
                records[index] = dalyTran(String.format("TRAN%012d", index + 1), CARD_ONE);
            }
            fixture.dailyTransactions(records);
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));

            ExecutionSummary summary = fixture.run();

            assertThat(summary.recordsRead()).as("the pass really did read every record").isEqualTo(RECORDS);
            assertThat(summary.accountReads())
                    .as("and really did reach 3000-READ-ACCOUNT on every iteration, defect 1 included")
                    .isEqualTo(RECORDS + ExecutionSummary.POST_END_OF_FILE_LOOKUPS);
            return fixture;
        }

        @Test
        @DisplayName("the TRANSACTION file is never written and never even opened for output - the posting "
                + "the class name promises is exactly the WRITE that is not in the source")
        void theTransactionFileIsNeverWritten() {
            Fixture fixture = passOverManyRecords();

            verify(fixture.transactionRepository, never()).write(any(TranRecord.class));
            verify(fixture.transactionRepository, never()).openOutput();
            verify(fixture.transactionRepository, never())
                    .openOutput(any(DatasetBinding.class), anyString());
        }

        @Test
        @DisplayName("no account is rewritten - unlike CBACT04C and COBIL00C, this program's ACCTFILE is "
                + "read-only")
        void noAccountIsRewritten() {
            Fixture fixture = passOverManyRecords();

            verify(fixture.accountRepository, never()).rewrite(any(AccountRecord.class));
            verify(fixture.acctfile, never()).rewrite(any(AccountRecord.class));
            verify(fixture.accountRepository, never()).open(AccountRepository.OpenMode.I_O);
            verify(fixture.acctfile, never()).readForUpdate(anyString());
        }

        @Test
        @DisplayName("no card and no customer is rewritten either: both files are opened, closed, and "
                + "otherwise untouched")
        void noCardOrCustomerIsRewritten() {
            Fixture fixture = passOverManyRecords();

            verify(fixture.cardRepository, never()).rewrite(any(CardRecord.class));
            verify(fixture.customerRepository, never()).rewrite(any(CustomerRecord.class));
            verify(fixture.customerRepository, never()).rewrite(any(byte[].class));
            verify(fixture.customerRepository, never()).readForUpdate(anyString());
            verify(fixture.cardRepository, never()).readForUpdateByCardNumber(anyString());
        }

        @Test
        @DisplayName("there is no category-balance update to make: CBTRN01C accesses no TCATBALF, so no "
                + "such collaborator is injected at all")
        void thereIsNoCategoryBalanceUpdate() {
            assertThat(TransactionPostingJob.class.getDeclaredConstructors())
                    .singleElement()
                    .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                            .extracting(Class::getSimpleName)
                            .containsExactly("BatchConfig", "DalyTranRepository", "CustomerRepository",
                                    "CardXrefRepository", "CardRepository", "AccountRepository",
                                    "TransactionRepository", "ObjectProvider"));
        }

        @Test
        @DisplayName("the two read-only repositories publish no mutator to call: DalyTran and CardXref "
                + "expose no write, add, rewrite or delete at all")
        void theReadOnlyRepositoriesPublishNoMutator() {
            assertThat(publicMethodNames(DalyTranRepository.class))
                    .as("DALYTRAN is OPEN INPUT at app/cbl/CBTRN01C.cbl:254 and sequential; it has no "
                            + "mutating access path")
                    .noneMatch(TransactionPostingJobTest::namesAMutation);
            assertThat(publicMethodNames(CardXrefRepository.class))
                    .as("the cross reference is read by key and never updated by any of its twelve "
                            + "consumers")
                    .noneMatch(TransactionPostingJobTest::namesAMutation);
        }
    }

    private static List<String> publicMethodNames(Class<?> type) {
        return Stream.of(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .toList();
    }

    private static final List<String> MUTATING_VERBS =
            List.of("write", "rewrite", "add", "delete", "insert", "update", "put", "post", "openoutput");

    private static boolean namesAMutation(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        for (String verb : MUTATING_VERBS) {
            if (lower.startsWith(verb) && (lower.length() == verb.length()
                    || Character.isUpperCase(methodName.charAt(verb.length())))) {
                return true;
            }
        }
        return false;
    }

    @Nested
    @DisplayName("VERIFIED NEGATIVE 2 - the twelve file verbs, in source order, three files never read")
    class FileVerbOrder {
        @Test
        @DisplayName("all six files are OPENed in MAIN-PARA's order: DALYTRAN, CUSTFILE, XREFFILE, "
                + "CARDFILE, ACCTFILE, TRANFILE")
        void theSixOpensHappenInSourceOrder() {
            Fixture fixture = new Fixture();

            fixture.run();

            InOrder opens = inOrder(fixture.dalyTranRepository, fixture.customerRepository,
                    fixture.cardXrefRepository, fixture.cardRepository, fixture.accountRepository,
                    fixture.transactionRepository);
            opens.verify(fixture.dalyTranRepository).open();
            opens.verify(fixture.customerRepository).openInput();
            opens.verify(fixture.cardXrefRepository).openBrowse();
            opens.verify(fixture.cardRepository).openBrowse(anyString(), any());
            opens.verify(fixture.accountRepository).open(AccountRepository.OpenMode.INPUT);
            opens.verify(fixture.transactionRepository).openInput(any(DatasetBinding.class));
        }

        @Test
        @DisplayName("all six files are CLOSEd in the 9000-9500 order: DALYTRAN, CUSTFILE, XREFFILE, "
                + "CARDFILE, ACCTFILE, TRANFILE")
        void theSixClosesHappenInSourceOrder() {
            Fixture fixture = new Fixture();

            fixture.run();

            InOrder closes = inOrder(fixture.dalytranFile, fixture.custfile, fixture.xrefCursor,
                    fixture.cardBrowse, fixture.acctfile, fixture.tranfile);
            closes.verify(fixture.dalytranFile).closeFile();
            closes.verify(fixture.custfile).closeFile();
            closes.verify(fixture.xrefCursor).closeBrowse();
            closes.verify(fixture.cardBrowse).endBrowse();
            closes.verify(fixture.acctfile).closeFile();
            closes.verify(fixture.tranfile).closeInput();
        }

        @Test
        @DisplayName("the loop sits strictly between the ladders: every OPEN precedes the first READ and "
                + "every CLOSE follows the last one")
        void theLoopSitsBetweenTheTwoLadders() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));

            fixture.run();

            InOrder wholeRun = inOrder(fixture.transactionRepository, fixture.dalytranFile,
                    fixture.cardXrefRepository, fixture.dalyTranRepository);
            wholeRun.verify(fixture.transactionRepository).openInput(any(DatasetBinding.class));
            wholeRun.verify(fixture.dalytranFile).readNext();
            wholeRun.verify(fixture.cardXrefRepository, times(2)).readByCardNumber(anyString());
            wholeRun.verify(fixture.dalytranFile).closeFile();
        }

        @Test
        @DisplayName("exactly one OPEN and one CLOSE per file per clean run - no file is released twice")
        void eachFileIsOpenedOnceAndClosedOnce() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE), dalyTran("TRAN000000000002",
                    CARD_TWO));

            fixture.run();

            verify(fixture.dalyTranRepository, times(1)).open();
            verify(fixture.dalytranFile, times(1)).closeFile();
            verify(fixture.customerRepository, times(1)).openInput();
            verify(fixture.custfile, times(1)).closeFile();
            verify(fixture.cardXrefRepository, times(1)).openBrowse();
            verify(fixture.xrefCursor, times(1)).closeBrowse();
            verify(fixture.cardRepository, times(1)).openBrowse(anyString(), any());
            verify(fixture.cardBrowse, times(1)).endBrowse();
            verify(fixture.accountRepository, times(1)).open(AccountRepository.OpenMode.INPUT);
            verify(fixture.acctfile, times(1)).closeFile();
            verify(fixture.transactionRepository, times(1)).openInput(any(DatasetBinding.class));
            verify(fixture.tranfile, times(1)).closeInput();
        }

        @Test
        @DisplayName("the CUSTOMER file receives no read of ANY kind - not by key, not sequentially, not "
                + "for update")
        void theCustomerFileIsNeverReadByAnyPath() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));

            fixture.run();

            verify(fixture.customerRepository, never()).readByKey(anyLong());
            verify(fixture.customerRepository, never()).readByKey(anyString());
            verify(fixture.customerRepository, never()).readForUpdate(anyString());
            verify(fixture.custfile, never()).readNext();
            verify(fixture.custfile, never()).readByKey(anyLong());
            verify(fixture.custfile, never()).readByKey(anyString());
        }

        @Test
        @DisplayName("the CARD file receives no read of ANY kind - not by card number, not by the "
                + "alternate index, not through the browse it was opened with")
        void theCardFileIsNeverReadByAnyPath() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));

            fixture.run();

            verify(fixture.cardRepository, never()).readByCardNumber(anyString());
            verify(fixture.cardRepository, never()).readForUpdateByCardNumber(anyString());
            verify(fixture.cardRepository, never()).readByAccountIdViaAltIndex(anyLong());
            verify(fixture.cardRepository, never()).readByAccountIdViaAltIndex(anyString());
            verify(fixture.cardBrowse, never()).readNext();
            verify(fixture.cardBrowse, never()).readPrev();
        }

        @Test
        @DisplayName("the TRANSACTION file receives no read of ANY kind either - opened, closed, and "
                + "neither read nor written")
        void theTransactionFileIsNeverReadByAnyPath() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));

            fixture.run();

            verify(fixture.transactionRepository, never()).readByTranId(anyString());
            verify(fixture.transactionRepository, never()).readForUpdateByTranId(anyString());
            verify(fixture.transactionRepository, never()).startBrowse(any());
            verify(fixture.transactionRepository, never()).startBrowse(anyString(), any());
            verify(fixture.tranfile, never()).readNext();
            verify(fixture.transactionRepository, never()).write(any(TranRecord.class));
        }

        @Test
        @DisplayName("only three READs exist in the program, so only three datasets are read: the ones at "
                + ":203, :229 and :243")
        void exactlyThreeDatasetsAreRead() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));

            ExecutionSummary summary = fixture.run();

            verify(fixture.dalytranFile, times(2)).readNext();
            verify(fixture.cardXrefRepository, times(2)).readByCardNumber(anyString());
            verify(fixture.acctfile, times(2)).readByKey(anyLong());
            assertThat(summary.recordsRead()).isEqualTo(1);
            assertThat(summary.xrefLookups()).isEqualTo(summary.recordsRead()
                    + ExecutionSummary.POST_END_OF_FILE_LOOKUPS);
        }
    }

    @Nested
    @DisplayName("VERIFIED NEGATIVE 3 - defect 1: N records produce N+1 lookups, the last on a stale key")
    class PostEndOfFileStaleLookup {
        @ParameterizedTest(name = "{0} record(s) produce {0}+1 cross-reference lookups")
        @ValueSource(ints = { 0, 1, 2, 3, 5 })
        @DisplayName("the lookup count is always the record count plus exactly one")
        void theLookupCountIsAlwaysOneMoreThanTheRecordCount(int records) {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(distinctRecords(records));

            ExecutionSummary summary = fixture.run();

            assertThat(summary.recordsRead()).isEqualTo(records);
            assertThat(summary.xrefLookups())
                    .as("app/cbl/CBTRN01C.cbl:170-172 sit outside the end-of-file guard at :167-169")
                    .isEqualTo(records + 1);
            assertThat(summary.postEndOfFileLookups())
                    .isEqualTo(ExecutionSummary.POST_END_OF_FILE_LOOKUPS);
            verify(fixture.cardXrefRepository, times(records + 1)).readByCardNumber(anyString());
        }

        @Test
        @DisplayName("the extra lookup carries the LAST record's card number, not a fresh one and not a "
                + "blank one")
        void theExtraLookupCarriesTheStaleCardNumber() {
            Fixture fixture = new Fixture();
            DalyTranRecord[] records = distinctRecords(3);
            fixture.dailyTransactions(records);

            fixture.run();

            verify(fixture.cardXrefRepository, times(1)).readByCardNumber(cardNumber(0));
            verify(fixture.cardXrefRepository, times(1)).readByCardNumber(cardNumber(1));
            verify(fixture.cardXrefRepository, times(2)).readByCardNumber(cardNumber(2));
            verify(fixture.cardXrefRepository, never())
                    .readByCardNumber(" ".repeat(DalyTranRecord.DALYTRAN_CARD_NUM_LENGTH));
        }

        @Test
        @DisplayName("over an EMPTY dataset the stale key is the untouched record area, and the lookup "
                + "still happens")
        void anEmptyDatasetStillLooksUpTheUntouchedRecordArea() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions();

            ExecutionSummary summary = fixture.run();

            assertThat(summary.recordsRead()).isZero();
            assertThat(summary.xrefLookups()).isEqualTo(1);
            verify(fixture.cardXrefRepository, times(1)).readByCardNumber(untouchedCardNumber());
        }

        @Test
        @DisplayName("the defect is visible in SYSOUT, not just in a counter: the miss arm's line appears "
                + "TWICE for the last card")
        void theStaleIterationEmitsItsOwnOutputOnTheMissArm() {
            Fixture fixture = new Fixture();
            DalyTranRecord[] records = distinctRecords(2);
            fixture.dailyTransactions(records);

            fixture.run();

            String lastCardLine = notVerifiedLine(cardNumber(1), tranId(1));
            assertThat(fixture.lines).filteredOn(lastCardLine::equals)
                    .as("once for the second record, once more for the post-EOF iteration")
                    .hasSize(2);
            assertThat(fixture.lines).filteredOn(notVerifiedLine(cardNumber(0), tranId(0))::equals)
                    .as("the first record's line appears once - only the LAST one repeats")
                    .hasSize(1);
            assertThat(fixture.lines)
                    .filteredOn(TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF::equals)
                    .as("2000-LOOKUP-XREF ran three times for two records")
                    .hasSize(3);
        }

        @Test
        @DisplayName("on the success arm the stale iteration repeats all four XREF lines AND the account "
                + "read, on the stale key")
        void theStaleIterationRepeatsTheSuccessArmAndTheAccountRead() {
            Fixture fixture = new Fixture();
            DalyTranRecord[] records = distinctRecords(2);
            fixture.dailyTransactions(records);
            when(fixture.cardXrefRepository.readByCardNumber(cardNumber(0)))
                    .thenReturn(xrefFound(cardNumber(0)));
            when(fixture.cardXrefRepository.readByCardNumber(cardNumber(1)))
                    .thenReturn(xrefFound(cardNumber(1)));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines)
                    .filteredOn(TransactionPostingJob.SUCCESSFUL_READ_OF_XREF::equals)
                    .as("three successful lookups for two records")
                    .hasSize(3);
            assertThat(fixture.lines)
                    .filteredOn(TransactionPostingJob.SUCCESSFUL_READ_OF_ACCOUNT_FILE::equals)
                    .as("3000-READ-ACCOUNT runs on the stale iteration too, because :173's guard is "
                            + "satisfied by the stale lookup having succeeded")
                    .hasSize(3);
            assertThat(fixture.lines)
                    .filteredOn((TransactionPostingJob.XREF_CARD_NUMBER_PREFIX + cardNumber(1))::equals)
                    .as("and the card number it displays is the stale one")
                    .hasSize(2);
            assertThat(summary.accountReads()).isEqualTo(3);
            verify(fixture.acctfile, times(3)).readByKey(ACCOUNT_ID);
        }

        @Test
        @DisplayName("the record image is NOT displayed for the post-EOF iteration - that one statement "
                + "IS inside the guard")
        void theRecordImageIsNotRepeated() {
            Fixture fixture = new Fixture();
            DalyTranRecord[] records = distinctRecords(3);
            fixture.dailyTransactions(records);

            ExecutionSummary summary = fixture.run();

            assertThat(summary.recordImageLinesDisplayed()).isEqualTo(3);
            for (DalyTranRecord record : records) {
                assertThat(fixture.lines).filteredOn(record.displayImage()::equals)
                        .as("each record's image appears exactly once")
                        .hasSize(1);
            }
            assertThat(summary.xrefLookups()).isEqualTo(4);
        }
    }

    private static String cardNumber(int index) {
        return String.format("4%015d", index + 1);
    }

    private static String tranId(int index) {
        return String.format("TRAN%012d", index + 1);
    }

    private static DalyTranRecord[] distinctRecords(int count) {
        DalyTranRecord[] records = new DalyTranRecord[count];
        for (int index = 0; index < count; index++) {
            records[index] = dalyTran(tranId(index), cardNumber(index));
        }
        return records;
    }

    @Nested
    @DisplayName("1000-DALYTRAN-GET-NEXT - the three-arm status ladder")
    class DalytranRead {
        @Test
        @DisplayName("a status that is neither '00' nor '10' abends with RETURN-CODE 12")
        void anUnnamedStatusAbends() {
            Fixture fixture = new Fixture();
            when(fixture.dalytranFile.readNext())
                    .thenReturn(DalyTranRepository.ReadResult.other(OTHER_STATUS));

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(fixture::run).actual();

            assertThat(fixture.lines).containsExactly(
                    TransactionPostingJob.START_BANNER,
                    TransactionPostingJob.ERROR_READING_DALYTRAN,
                    FileStatus.toDisplayLine(OTHER_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode()).isEqualTo(TransactionPostingJob.APPL_RESULT_FATAL);
            assertThat(abend.getProgram()).isEqualTo(TransactionPostingJob.PROGRAM_ID);
        }

        @Test
        @DisplayName("a refused read is reported as a status and abends, carrying the refusal as cause")
        void aRefusedReadAbendsCarryingTheCause() {
            Fixture fixture = new Fixture();
            RuntimeException refusal = new IllegalStateException("the driver refused the read");
            when(fixture.dalytranFile.readNext()).thenThrow(refusal);

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(fixture::run).actual();

            assertThat(fixture.lines).contains(TransactionPostingJob.ERROR_READING_DALYTRAN,
                    FileStatus.toDisplayLine(TransactionPostingJob.PERMANENT_ERROR_STATUS));
            assertThat(abend).hasCause(refusal);
        }
    }

    @Nested
    @DisplayName("2000-LOOKUP-XREF - INVALID KEY, NOT INVALID KEY, and neither")
    class XrefLookup {
        @Test
        @DisplayName("NOT INVALID KEY emits exactly four lines, in source order, at declared widths")
        void aFoundCrossReferenceEmitsFourLines() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));

            fixture.run();

            assertThat(fixture.lines).containsSequence(xrefSuccessLines(CARD_ONE));
            assertThat(xrefSuccessLines(CARD_ONE)).hasSize(TransactionPostingJob.XREF_SUCCESS_LINES);
            assertThat(fixture.lines).contains("ACCOUNT ID : 99999999999", "CUSTOMER ID: 123456789");
        }

        @Test
        @DisplayName("the displayed card number is the RECORD's, because READ ... INTO overwrote the key")
        void theDisplayedCardNumberComesFromTheRecord() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_TWO));

            fixture.run();

            assertThat(fixture.lines)
                    .contains(TransactionPostingJob.XREF_CARD_NUMBER_PREFIX + CARD_TWO)
                    .doesNotContain(TransactionPostingJob.XREF_CARD_NUMBER_PREFIX + CARD_ONE);
        }

        @Test
        @DisplayName("INVALID KEY - a not-found row reports it and skips the transaction")
        void aNotFoundCrossReferenceSkipsTheTransaction() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines).containsSequence(
                    TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF,
                    notVerifiedLine(CARD_ONE, TRAN_ONE));
            assertThat(summary.accountReads()).isZero();
            verify(fixture.acctfile, never()).readByKey(anyLong());
        }

        @Test
        @DisplayName("INVALID KEY - a duplicate base key takes the same arm as a not-found row")
        void aDuplicateBaseKeyTakesTheInvalidKeyArm() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            CardXrefRecord duplicated = xref(CARD_ONE);
            when(fixture.cardXrefRepository.readByCardNumber(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.duplicate(TransactionPostingJob.XREFFILE_DD_NAME,
                            duplicated, new String(duplicated.encode(CHARSET), CHARSET),
                            FileStatus.DUPREC));

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines).contains(TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF);
            assertThat(summary.accountReads()).isZero();
        }

        @Test
        @DisplayName("NEITHER phrase - any other status displays nothing and still reads an account")
        void anUnclassifiedStatusExecutesNeitherPhrase() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString())).thenReturn(
                    CardXrefRepository.ReadResult.other(TransactionPostingJob.XREFFILE_DD_NAME,
                            OTHER_STATUS));

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines)
                    .doesNotContain(TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF)
                    .doesNotContain(TransactionPostingJob.SUCCESSFUL_READ_OF_XREF);
            assertThat(summary.accountReads()).isEqualTo(2);
            verify(fixture.acctfile, times(2))
                    .readByKey(TransactionPostingJob.UNPOPULATED_ACCOUNT_ID);
            assertThat(fixture.lines).contains("ACCOUNT 00000000000 NOT FOUND");
        }

        @Test
        @DisplayName("a refused lookup is reported as a status, which is neither phrase")
        void aRefusedLookupExecutesNeitherPhrase() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenThrow(new IllegalStateException("the driver refused the keyed read"));

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines)
                    .doesNotContain(TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF);
            assertThat(summary.completedCleanly()).isTrue();
        }

        @Test
        @DisplayName("the invalid-key condition is '22' and '23' and nothing else")
        void theInvalidKeyConditionIsExactlyTwoStatuses() {
            assertThat(TransactionPostingJob.isInvalidKeyCondition(FileStatus.NOT_FOUND)).isTrue();
            assertThat(TransactionPostingJob.isInvalidKeyCondition(FileStatus.DUPLICATE)).isTrue();
            assertThat(TransactionPostingJob.isInvalidKeyCondition(FileStatus.OK)).isFalse();
            assertThat(TransactionPostingJob.isInvalidKeyCondition(FileStatus.END_OF_FILE)).isFalse();
            assertThat(TransactionPostingJob.isInvalidKeyCondition(OTHER_STATUS)).isFalse();
        }
    }

    @Nested
    @DisplayName("3000-READ-ACCOUNT - INVALID KEY, NOT INVALID KEY, and neither")
    class AccountRead {
        @Test
        @DisplayName("NOT INVALID KEY emits the success line and no not-found line")
        void aFoundAccountEmitsItsSuccessLine() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.found(account()));

            fixture.run();

            assertThat(fixture.lines)
                    .contains(TransactionPostingJob.SUCCESSFUL_READ_OF_ACCOUNT_FILE)
                    .doesNotContain(TransactionPostingJob.INVALID_ACCOUNT_NUMBER_FOUND);
            assertThat(fixture.lines).noneMatch(line -> line.endsWith("NOT FOUND"));
            verify(fixture.acctfile, times(2)).readByKey(ACCOUNT_ID);
        }

        @Test
        @DisplayName("INVALID KEY - the cross reference's account id is what 'ACCOUNT ... NOT FOUND' shows")
        void aNotFoundAccountShowsTheCrossReferencedIdentifier() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));

            fixture.run();

            assertThat(fixture.lines).containsSequence(
                    TransactionPostingJob.INVALID_ACCOUNT_NUMBER_FOUND,
                    "ACCOUNT 99999999999 NOT FOUND");
        }

        @Test
        @DisplayName("INVALID KEY - a duplicate account key takes the same arm")
        void aDuplicateAccountKeyTakesTheInvalidKeyArm() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.of(FileStatus.DUPLICATE));

            fixture.run();

            assertThat(fixture.lines).contains(TransactionPostingJob.INVALID_ACCOUNT_NUMBER_FOUND);
        }

        @Test
        @DisplayName("NEITHER phrase - any other status displays nothing at all")
        void anUnclassifiedAccountStatusExecutesNeitherPhrase() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.of(OTHER_STATUS));

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines)
                    .doesNotContain(TransactionPostingJob.INVALID_ACCOUNT_NUMBER_FOUND)
                    .doesNotContain(TransactionPostingJob.SUCCESSFUL_READ_OF_ACCOUNT_FILE);
            assertThat(fixture.lines).noneMatch(line -> line.endsWith("NOT FOUND"));
            assertThat(summary.accountReads()).isEqualTo(2);
        }

        @Test
        @DisplayName("a refused account read is reported as a status, which is neither phrase")
        void aRefusedAccountReadExecutesNeitherPhrase() {
            Fixture fixture = new Fixture();
            fixture.dailyTransactions(dalyTran(TRAN_ONE, CARD_ONE));
            when(fixture.cardXrefRepository.readByCardNumber(anyString()))
                    .thenReturn(xrefFound(CARD_ONE));
            when(fixture.acctfile.readByKey(anyLong()))
                    .thenThrow(new IllegalStateException("the driver refused the keyed read"));

            ExecutionSummary summary = fixture.run();

            assertThat(fixture.lines)
                    .doesNotContain(TransactionPostingJob.INVALID_ACCOUNT_NUMBER_FOUND);
            assertThat(summary.completedCleanly()).isTrue();
        }
    }

    @Nested
    @DisplayName("The six OPEN paragraphs - each with its own literal and its own abend")
    class OpenFailures {
        @Test
        @DisplayName("DALYTRAN: a bad open status displays its literal, the status and the abend")
        void theDailyTransactionOpenReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.dalytranFile.openStatus()).thenReturn(OTHER_STATUS);

            assertOpenFailure(fixture, TransactionPostingJob.ERROR_OPENING_DALYTRAN, OTHER_STATUS);
        }

        @Test
        @DisplayName("CUSTFILE: a bad open status displays its literal, the status and the abend")
        void theCustomerOpenReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.custfile.openStatus()).thenReturn(OTHER_STATUS);

            assertOpenFailure(fixture, TransactionPostingJob.ERROR_OPENING_CUSTFILE, OTHER_STATUS);
        }

        @Test
        @DisplayName("XREFFILE: a bad open status displays its literal, the status and the abend")
        void theCrossReferenceOpenReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.xrefCursor.openStatus()).thenReturn(OTHER_STATUS);

            assertOpenFailure(fixture, TransactionPostingJob.ERROR_OPENING_XREFFILE, OTHER_STATUS);
        }

        @Test
        @DisplayName("CARDFILE: a CICS response with no batch equivalent lands on the permanent error")
        void theCardOpenReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.cardBrowse.openResp()).thenReturn(FileStatus.NOTOPEN);

            assertOpenFailure(fixture, TransactionPostingJob.ERROR_OPENING_CARDFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("ACCTFILE: a bad open status displays its literal, the status and the abend")
        void theAccountOpenReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.acctfile.openStatus()).thenReturn(OTHER_STATUS);

            assertOpenFailure(fixture, TransactionPostingJob.ERROR_OPENING_ACCTFILE, OTHER_STATUS);
        }

        @Test
        @DisplayName("TRANFILE: a bad open status displays its literal, the status and the abend")
        void theTransactionOpenReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.tranfile.openStatus()).thenReturn(OTHER_STATUS);

            assertOpenFailure(fixture, TransactionPostingJob.ERROR_OPENING_TRANFILE, OTHER_STATUS);
        }

        @Test
        @DisplayName("a refusal at any open is reported as the permanent-error status, cause carried")
        void aRefusedOpenIsReportedAsAStatus() {
            RuntimeException refusal = new IllegalStateException("no such dataset");

            Fixture daly = new Fixture();
            when(daly.dalyTranRepository.open()).thenThrow(refusal);
            assertThat(assertOpenFailure(daly, TransactionPostingJob.ERROR_OPENING_DALYTRAN,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS)).hasCause(refusal);

            Fixture cust = new Fixture();
            when(cust.customerRepository.openInput()).thenThrow(refusal);
            assertOpenFailure(cust, TransactionPostingJob.ERROR_OPENING_CUSTFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);

            Fixture xref = new Fixture();
            when(xref.cardXrefRepository.openBrowse()).thenThrow(refusal);
            assertOpenFailure(xref, TransactionPostingJob.ERROR_OPENING_XREFFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);

            Fixture card = new Fixture();
            when(card.cardRepository.openBrowse(anyString(), any())).thenThrow(refusal);
            assertOpenFailure(card, TransactionPostingJob.ERROR_OPENING_CARDFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);

            Fixture acct = new Fixture();
            when(acct.accountRepository.open(AccountRepository.OpenMode.INPUT)).thenThrow(refusal);
            assertOpenFailure(acct, TransactionPostingJob.ERROR_OPENING_ACCTFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);

            Fixture tran = new Fixture();
            when(tran.transactionRepository.openInput(any(DatasetBinding.class))).thenThrow(refusal);
            assertOpenFailure(tran, TransactionPostingJob.ERROR_OPENING_TRANFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("an open failure closes nothing and reads nothing: the pass never started")
        void anOpenFailureNeverReachesTheLoop() {
            Fixture fixture = new Fixture();
            when(fixture.custfile.openStatus()).thenReturn(OTHER_STATUS);

            assertThatExceptionOfType(AbendException.class).isThrownBy(fixture::run);

            verify(fixture.dalytranFile, never()).readNext();
            verify(fixture.dalytranFile, never()).closeFile();
            verify(fixture.cardXrefRepository, never()).readByCardNumber(anyString());
        }
    }

    private static AbendException assertOpenFailure(Fixture fixture, String errorText, String status) {
        AbendException abend = assertThatExceptionOfType(AbendException.class)
                .isThrownBy(fixture::run).actual();

        assertThat(fixture.lines).containsExactly(
                TransactionPostingJob.START_BANNER,
                errorText,
                FileStatus.toDisplayLine(status),
                AbendException.ABEND_DISPLAY_TEXT);
        assertThat(abend.getReturnCode()).isEqualTo(TransactionPostingJob.APPL_RESULT_FATAL);
        assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
        assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
        return abend;
    }

    @Nested
    @DisplayName("The six CLOSE paragraphs - and defect 2")
    class CloseFailures {
        @Test
        @DisplayName("DEFECT 2: the DALYTRAN close reports the CUSTOMER file's text AND its status")
        void theDailyTransactionCloseReportsTheCustomerFile() {
            Fixture fixture = new Fixture();
            when(fixture.dalytranFile.closeFile()).thenReturn(OTHER_STATUS);

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(fixture::run).actual();

            assertThat(fixture.lines).containsSequence(
                    TransactionPostingJob.ERROR_CLOSING_CUSTFILE,
                    FileStatus.toDisplayLine(FileStatus.OK),
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(fixture.lines).doesNotContain(FileStatus.toDisplayLine(OTHER_STATUS));
            assertThat(abend.getReturnCode()).isEqualTo(TransactionPostingJob.APPL_RESULT_FATAL);
            verify(fixture.custfile, never()).closeFile();
            verify(fixture.xrefCursor, never()).closeBrowse();
            verify(fixture.tranfile, never()).closeInput();
        }

        @Test
        @DisplayName("CUSTFILE: its own close reports its own literal and its own status")
        void theCustomerCloseReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.custfile.closeFile()).thenReturn(OTHER_STATUS);

            assertCloseFailure(fixture, TransactionPostingJob.ERROR_CLOSING_CUSTFILE, OTHER_STATUS);
        }

        @Test
        @DisplayName("XREFFILE: its own close reports its own literal and its own status")
        void theCrossReferenceCloseReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.xrefCursor.closeBrowse()).thenReturn(OTHER_STATUS);

            assertCloseFailure(fixture, TransactionPostingJob.ERROR_CLOSING_XREFFILE, OTHER_STATUS);
        }

        @Test
        @DisplayName("CARDFILE: a refused endBrowse becomes the permanent-error status")
        void theCardCloseReportsItself() {
            Fixture fixture = new Fixture();
            RuntimeException refusal = new IllegalStateException("the browse could not be released");
            org.mockito.Mockito.doThrow(refusal).when(fixture.cardBrowse).endBrowse();

            AbendException abend = assertCloseFailure(fixture,
                    TransactionPostingJob.ERROR_CLOSING_CARDFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);

            assertThat(abend).hasCause(refusal);
        }

        @Test
        @DisplayName("ACCTFILE: its own close reports its own literal and its own status")
        void theAccountCloseReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.acctfile.closeFile()).thenReturn(OTHER_STATUS);

            assertCloseFailure(fixture, TransactionPostingJob.ERROR_CLOSING_ACCTFILE, OTHER_STATUS);
        }

        @Test
        @DisplayName("TRANFILE: its own close reports its own literal and its own status")
        void theTransactionCloseReportsItself() {
            Fixture fixture = new Fixture();
            when(fixture.tranfile.closeInput()).thenReturn(OTHER_STATUS);

            assertCloseFailure(fixture, TransactionPostingJob.ERROR_CLOSING_TRANFILE, OTHER_STATUS);
        }

        @Test
        @DisplayName("a refusal at any close is reported as the permanent-error status")
        void aRefusedCloseIsReportedAsAStatus() {
            RuntimeException refusal = new IllegalStateException("the handle could not be released");

            Fixture daly = new Fixture();
            when(daly.dalytranFile.closeFile()).thenThrow(refusal);
            assertCloseFailure(daly, TransactionPostingJob.ERROR_CLOSING_CUSTFILE, FileStatus.OK);

            Fixture cust = new Fixture();
            when(cust.custfile.closeFile()).thenThrow(refusal);
            assertCloseFailure(cust, TransactionPostingJob.ERROR_CLOSING_CUSTFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);

            Fixture xref = new Fixture();
            when(xref.xrefCursor.closeBrowse()).thenThrow(refusal);
            assertCloseFailure(xref, TransactionPostingJob.ERROR_CLOSING_XREFFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);

            Fixture acct = new Fixture();
            when(acct.acctfile.closeFile()).thenThrow(refusal);
            assertCloseFailure(acct, TransactionPostingJob.ERROR_CLOSING_ACCTFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);

            Fixture tran = new Fixture();
            when(tran.tranfile.closeInput()).thenThrow(refusal);
            assertCloseFailure(tran, TransactionPostingJob.ERROR_CLOSING_TRANFILE,
                    TransactionPostingJob.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("an incomplete run releases the handles its CLOSE paragraphs never reached, silently")
        void anIncompleteRunReleasesWhatItOpened() {
            Fixture fixture = new Fixture();
            when(fixture.dalytranFile.closeFile()).thenReturn(OTHER_STATUS);

            assertThatExceptionOfType(AbendException.class).isThrownBy(fixture::run);

            verify(fixture.custfile).close();
            verify(fixture.xrefCursor).close();
            verify(fixture.cardBrowse).close();
            verify(fixture.acctfile).close();
            verify(fixture.tranfile).close();
            verify(fixture.dalytranFile, never()).close();
            assertThat(fixture.lines).containsExactly(
                    TransactionPostingJob.START_BANNER,
                    TransactionPostingJob.INVALID_CARD_NUMBER_FOR_XREF,
                    notVerifiedLine(untouchedCardNumber(), untouchedTranId()),
                    TransactionPostingJob.ERROR_CLOSING_CUSTFILE,
                    FileStatus.toDisplayLine(FileStatus.OK),
                    AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("a release that itself fails cannot displace the run's own failure")
        void aFailingReleaseIsSwallowed() {
            Fixture fixture = new Fixture();
            when(fixture.dalytranFile.closeFile()).thenReturn(OTHER_STATUS);
            org.mockito.Mockito.doThrow(new IllegalStateException("release refused"))
                    .when(fixture.tranfile).close();

            assertThatExceptionOfType(AbendException.class).isThrownBy(fixture::run);

            verify(fixture.custfile).close();
        }

        @Test
        @DisplayName("a clean run releases nothing: all six CLOSE paragraphs ran")
        void aCleanRunReleasesNothing() {
            Fixture fixture = new Fixture();

            fixture.run();

            verify(fixture.dalytranFile, never()).close();
            verify(fixture.custfile, never()).close();
            verify(fixture.xrefCursor, never()).close();
            verify(fixture.cardBrowse, never()).close();
            verify(fixture.acctfile, never()).close();
            verify(fixture.tranfile, never()).close();
        }

        @Test
        @DisplayName("a failed open leaves nothing to release, so no spurious release is attempted")
        void aFailedOpenLeavesNothingToRelease() {
            Fixture fixture = new Fixture();
            when(fixture.xrefCursor.openStatus()).thenReturn(OTHER_STATUS);

            assertThatExceptionOfType(AbendException.class).isThrownBy(fixture::run);

            verify(fixture.xrefCursor, never()).close();
            verify(fixture.xrefCursor, never()).closeBrowse();
            verify(fixture.dalytranFile).close();
            verify(fixture.custfile).close();
        }
    }

    private static AbendException assertCloseFailure(Fixture fixture, String errorText, String status) {
        AbendException abend = assertThatExceptionOfType(AbendException.class)
                .isThrownBy(fixture::run).actual();

        assertThat(fixture.lines)
                .startsWith(TransactionPostingJob.START_BANNER)
                .endsWith(errorText, FileStatus.toDisplayLine(status),
                        AbendException.ABEND_DISPLAY_TEXT)
                .doesNotContain(TransactionPostingJob.END_BANNER);
        assertThat(abend.getReturnCode()).isEqualTo(TransactionPostingJob.APPL_RESULT_FATAL);
        return abend;
    }

    @Nested
    @DisplayName("SYSOUT - verbatim, in the dataset code page, one line feed, flushed")
    class SysoutDestinations {
        @Test
        @DisplayName("a published sink is used, and the default is not")
        void aPublishedSinkIsHonoured() {
            Fixture fixture = new Fixture();
            List<String> published = new ArrayList<>();
            TransactionPostingJob subject = fixture.job(validBatchConfig(), published::add);

            subject.transactionPostingTasklet();
            subject.execute(published::add);

            assertThat(published).startsWith(TransactionPostingJob.START_BANNER);
        }

        @Test
        @DisplayName("the stream sink writes the line, one line feed, and flushes - and nothing else")
        void theStreamSinkWritesTheLineAndOneLineFeed() {
            Fixture fixture = new Fixture();
            ByteArrayOutputStream captured = new ByteArrayOutputStream();

            fixture.job().sysoutSinkTo(captured).display("A LINE");

            assertThat(captured.toString(CHARSET)).isEqualTo("A LINE\n");
        }

        @Test
        @DisplayName("a raw record image reaches SYSOUT untrimmed, FILLER included")
        void aRecordImageReachesSysoutUntrimmed() {
            Fixture fixture = new Fixture();
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            DalyTranRecord record = dalyTran(TRAN_ONE, CARD_ONE);

            fixture.job().sysoutSinkTo(captured).display(record.displayImage());

            assertThat(captured.toString(CHARSET))
                    .hasSize(DalyTranRecord.RECORD_LENGTH + 1)
                    .startsWith(TRAN_ONE)
                    .endsWith(" \n");
        }

        @Test
        @DisplayName("the default sink exists and is a distinct instance per call")
        void theDefaultSinkIsAvailable() {
            TransactionPostingJob subject = new Fixture().job();

            assertThat(subject.defaultSysoutSink()).isNotNull();
            assertThat(subject.defaultSysoutSink()).isNotSameAs(subject.defaultSysoutSink());
        }

        @Test
        @DisplayName("a null destination, and a null line, are both refused")
        void nullsAreRefused() {
            TransactionPostingJob subject = new Fixture().job();
            SysoutSink sink = subject.sysoutSinkTo(new ByteArrayOutputStream());

            assertThatNullPointerException().isThrownBy(() -> subject.sysoutSinkTo(null));
            assertThatNullPointerException().isThrownBy(() -> sink.display(null));
        }

        @Test
        @DisplayName("a stream that refuses the write is reported, not swallowed")
        void aRefusedWriteIsReported() {
            TransactionPostingJob subject = new Fixture().job();
            OutputStream refusing = new OutputStream() {
                @Override
                public void write(int singleByte) throws IOException {
                    throw new IOException("the spool is full");
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    throw new IOException("the spool is full");
                }
            };

            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> subject.sysoutSinkTo(refusing).display("A LINE"))
                    .withMessageContaining(TransactionPostingJob.PROGRAM_ID);
        }
    }

    @Nested
    @DisplayName("ExecutionSummary - defect 1 as a type invariant")
    class Summary {
        @Test
        @DisplayName("the lookup count must be the record count plus exactly one")
        void theLookupCountIsTheRecordCountPlusOne() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ExecutionSummary(0, 3, 3, 0))
                    .withMessageContaining("defect 1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ExecutionSummary(0, 3, 5, 0));
            assertThat(new ExecutionSummary(0, 3, 4, 0).postEndOfFileLookups())
                    .isEqualTo(ExecutionSummary.POST_END_OF_FILE_LOOKUPS);
        }

        @Test
        @DisplayName("negative counts are refused")
        void negativeCountsAreRefused() {
            assertThatIllegalArgumentException().isThrownBy(() -> new ExecutionSummary(0, -1, 0, 0));
            assertThatIllegalArgumentException().isThrownBy(() -> new ExecutionSummary(0, 0, -1, 0));
            assertThatIllegalArgumentException().isThrownBy(() -> new ExecutionSummary(0, 0, 1, -1));
        }

        @Test
        @DisplayName("more account reads than lookups is refused")
        void tooManyAccountReadsIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ExecutionSummary(0, 1, 2, 3))
                    .withMessageContaining("at most one account read per lookup");
        }

        @Test
        @DisplayName("a clean run reports return code zero; a non-zero one does not")
        void completedCleanlyReflectsTheReturnCode() {
            assertThat(new ExecutionSummary(AbendException.RETURN_CODE_OK, 1, 2, 1).completedCleanly())
                    .isTrue();
            assertThat(new ExecutionSummary(TransactionPostingJob.APPL_RESULT_FATAL, 1, 2, 1)
                    .completedCleanly()).isFalse();
        }
    }
}
