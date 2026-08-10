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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * The branch-level check on {@link TransactionPostingJob}, the translation of
 * {@code app/cbl/CBTRN01C.cbl}.
 *
 * <p>Four things this suite exists to pin, beyond ordinary coverage:
 *
 * <ul>
 *   <li><strong>Gate G13.</strong> The job bean is real and launchable, and nothing triggers it: the
 *       class carries no {@code @Scheduled}, no runner and no {@code JobLauncher}.</li>
 *   <li><strong>Defect 1.</strong> Exactly one cross-reference lookup happens after end of file, on the
 *       previous record's card number.</li>
 *   <li><strong>Defect 2.</strong> A failure closing the daily transaction file reports the
 *       <em>customer</em> file's text and renders the <em>customer</em> file's status.</li>
 *   <li><strong>The dead accesses.</strong> {@code CUSTFILE}, {@code CARDFILE} and {@code TRANFILE} are
 *       opened and closed and never read; nothing is ever written anywhere.</li>
 * </ul>
 *
 * <p>Everything runs through {@link TransactionPostingJob#execute(SysoutSink)} with stubbed
 * repositories, so no launcher, no application context and no backend is in the path.
 */
@DisplayName("TransactionPostingJob - CBTRN01C, the orphan that posts nothing")
class TransactionPostingJobTest {

    /** The code page every stubbed repository reports and every record is built in. */
    private static final Charset CHARSET = StandardCharsets.US_ASCII;

    /** A dataset name that is not a real one; no test reaches a backend. */
    private static final String TEST_DSNAME = "TEST.CARDDEMO.DATASET";

    /** A sixteen-character card number, as {@code DALYTRAN-CARD-NUM PIC X(16)} holds it. */
    private static final String CARD_ONE = "4111111111111111";

    /** A second card number, so a stale value is distinguishable from a fresh one. */
    private static final String CARD_TWO = "4222222222222222";

    /** A sixteen-character transaction id, as {@code DALYTRAN-ID PIC X(16)} holds it. */
    private static final String TRAN_ONE = "TRAN000000000001";

    /** The account id the cross reference returns. */
    private static final long ACCOUNT_ID = 99_999_999_999L;

    /** The customer id the cross reference returns. */
    private static final int CUSTOMER_ID = 123_456_789;

    /** A status that is neither {@code '00'}, {@code '10'}, {@code '22'} nor {@code '23'}. */
    private static final String OTHER_STATUS = "35";

    // =================================================================================================
    // Fixture.
    // =================================================================================================

    /**
     * A stubbed world in which the job runs: six repositories, six handles, and a sink that records every
     * line in order.
     *
     * <p>Every handle answers {@code '00'} to its open and its close, and the daily transaction pass is
     * empty, so a freshly built fixture runs cleanly. Each test adjusts exactly the one stub its arm needs.
     */
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

        /** Every line the run displayed, in emission order. */
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

        /** @return the job over this fixture's stubs, with no published {@code SYSOUT} bean */
        private TransactionPostingJob job() {
            return job(validBatchConfig(), null);
        }

        /**
         * @param batchConfig the batch seam to build over
         * @param published   a {@code SYSOUT} sink the container publishes, or {@code null}
         * @return the job
         */
        private TransactionPostingJob job(BatchConfig batchConfig, SysoutSink published) {
            return new TransactionPostingJob(batchConfig, dalyTranRepository, customerRepository,
                    cardXrefRepository, cardRepository, accountRepository, transactionRepository,
                    new SuppliedProvider<>(published));
        }

        /** @return the sink that records into {@link #lines} */
        private SysoutSink sink() {
            return lines::add;
        }

        /** @return what a clean run of the job over this fixture produced */
        private ExecutionSummary run() {
            return job().execute(sink());
        }

        /**
         * Stubs the daily transaction pass to return the given records and then end of file.
         *
         * @param records the records to return in order
         */
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

    /**
     * An {@link ObjectProvider} over one optional bean, which is what the container hands a constructor.
     *
     * @param <T> the bean type
     */
    private static final class SuppliedProvider<T> implements ObjectProvider<T> {

        /** The bean, or {@code null} when the container publishes none. */
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

    // =================================================================================================
    // Catalogue builders, in the shape application.yml declares.
    // =================================================================================================

    /**
     * @param ddName       the DD name
     * @param recordLength the width to declare
     * @param dsname       the dataset name to declare
     * @return one binding entry
     */
    private static DatasetBinding binding(String ddName, int recordLength, String dsname) {
        return new DatasetBinding(dsname, DatasetBinding.KSDS, false, "FB", null, recordLength,
                ddName, 1, 0, null, null);
    }

    /** @return the six-entry catalogue this job resolves, all at their copybook widths */
    private static DatasetBindings validBindings() {
        return bindings(TransactionPostingJob.DALYTRAN_DD_NAME, DalyTranRecord.RECORD_LENGTH,
                TEST_DSNAME);
    }

    /**
     * The six-entry catalogue with one entry overridden, which is how each geometry rejection is driven.
     *
     * @param overriddenDd     the DD to override
     * @param overriddenLength the width to declare for it
     * @param overriddenDsname the dataset name to declare for it
     * @return the catalogue
     */
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

    /**
     * @param program    the program to declare on the job and its step
     * @param stepName   the step name to declare
     * @param gated      whether the step declares {@code COND=(0,NE)} gating
     * @param parameters the declared parameters
     * @return a catalogue holding just that contract
     */
    private static JobContracts contracts(String program, String stepName, boolean gated,
            List<JobParameterContract> parameters) {
        JobContracts catalogue = new JobContracts();
        catalogue.put(TransactionPostingJob.JOB_KEY, new JobContract(program, parameters,
                List.of(new StepContract(stepName, program, gated)), null, Map.of()));
        return catalogue;
    }

    /** @return the contract catalogue exactly as {@code application.yml:1035-1041} declares it */
    private static JobContracts validContracts() {
        return contracts(TransactionPostingJob.PROGRAM_ID, TransactionPostingJob.STEP_NAME, false,
                List.of());
    }

    /**
     * @param contracts the job contracts
     * @param bindings  the dataset catalogue
     * @return a seam over both, with batch plumbing that is present but touched only by a bean method
     */
    private static BatchConfig batchConfig(JobContracts contracts, DatasetBindings bindings) {
        return new BatchConfig(new SuppliedProvider<>(mock(JobRepository.class)),
                new SuppliedProvider<>(mock(PlatformTransactionManager.class)), contracts, bindings);
    }

    /** @return a seam over the catalogues configuration actually declares */
    private static BatchConfig validBatchConfig() {
        return batchConfig(validContracts(), validBindings());
    }

    // =================================================================================================
    // Record builders.
    // =================================================================================================

    /**
     * @param id       the sixteen-character {@code DALYTRAN-ID}
     * @param cardNum  the sixteen-character {@code DALYTRAN-CARD-NUM}
     * @return a daily transaction record carrying just those two fields
     */
    private static DalyTranRecord dalyTran(String id, String cardNum) {
        DalyTranRecord record = new DalyTranRecord(CHARSET);
        record.moveDalytranId(id);
        record.moveDalytranCardNum(cardNum);
        return record;
    }

    /**
     * @param cardNum the sixteen-character {@code XREF-CARD-NUM}
     * @return a cross-reference record over the fixed customer and account identifiers
     */
    private static CardXrefRecord xref(String cardNum) {
        return new CardXrefRecord(cardNum, CUSTOMER_ID, ACCOUNT_ID);
    }

    /**
     * @param cardNum the card number the record carries
     * @return a found outcome over that record and the bytes it encodes to
     */
    private static CardXrefRepository.ReadResult xrefFound(String cardNum) {
        CardXrefRecord record = xref(cardNum);
        return CardXrefRepository.ReadResult.found(TransactionPostingJob.XREFFILE_DD_NAME, record,
                new String(record.encode(CHARSET), CHARSET));
    }

    /** @return an account record carrying {@link #ACCOUNT_ID} */
    private static AccountRecord account() {
        AccountRecord record = new AccountRecord(CHARSET);
        record.setAcctId(ACCOUNT_ID);
        return record;
    }

    /** @return the {@code DALYTRAN-CARD-NUM} of a record area nothing has been moved into */
    private static String untouchedCardNumber() {
        return new DalyTranRecord(CHARSET).dalytranCardNum();
    }

    /** @return the {@code DALYTRAN-ID} of a record area nothing has been moved into */
    private static String untouchedTranId() {
        return new DalyTranRecord(CHARSET).dalytranId();
    }

    /**
     * @param cardNum the card number the message names
     * @param tranId  the transaction id the message names
     * @return the single line {@code app/cbl/CBTRN01C.cbl:181-183} emits
     */
    private static String notVerifiedLine(String cardNum, String tranId) {
        return TransactionPostingJob.CARD_NOT_VERIFIED_PREFIX + cardNum
                + TransactionPostingJob.CARD_NOT_VERIFIED_SUFFIX + tranId;
    }

    /**
     * @param cardNum the card number the cross reference returned
     * @return the four lines the {@code NOT INVALID KEY} arm emits, in order
     */
    private static List<String> xrefSuccessLines(String cardNum) {
        return List.of(TransactionPostingJob.SUCCESSFUL_READ_OF_XREF,
                TransactionPostingJob.XREF_CARD_NUMBER_PREFIX + cardNum,
                TransactionPostingJob.XREF_ACCOUNT_ID_PREFIX + "99999999999",
                TransactionPostingJob.XREF_CUSTOMER_ID_PREFIX + "123456789");
    }

    // =================================================================================================
    // Gate G13 - a real, launchable job that nothing triggers.
    // =================================================================================================

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
            // Nothing publishes a sink on this path, so the tasklet has to fall back to
            // defaultSysoutSink(), which targets the process's standard output. Standard output is
            // redirected for the duration of the call so that the fallback is asserted rather than merely
            // tolerated, and so the lines it emits land in this assertion instead of in the build's spool.
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

    // =================================================================================================
    // Constructor guards.
    // =================================================================================================

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

    // =================================================================================================
    // The clean pass, and defect 1.
    // =================================================================================================

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

            // Two records, three lookups: one per record and one after end of file.
            verify(fixture.cardXrefRepository, times(3)).readByCardNumber(anyString());
            verify(fixture.cardXrefRepository, times(1)).readByCardNumber(CARD_ONE);
            // CARD_TWO twice: once for its own record, and once more for the stale post-EOF lookup.
            verify(fixture.cardXrefRepository, times(2)).readByCardNumber(CARD_TWO);
            assertThat(summary.xrefLookups()).isEqualTo(3).isEqualTo(summary.recordsRead() + 1);

            // The stale lookup's message names the last record's card and transaction id, not a fresh one.
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
    }

    /** A stand-in for the framework's stop refusal, which a test cannot construct directly. */
    private static final class StopRequestedExceptionDouble extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private StopRequestedExceptionDouble() {
            super("stop requested");
        }
    }

    // =================================================================================================
    // The dead accesses.
    // =================================================================================================

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

    // =================================================================================================
    // The read, and the two keyed reads.
    // =================================================================================================

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
            // The row the read returns carries a different card number from the key that was searched for,
            // which is impossible for an exact-match read and is exactly what makes the ordering visible.
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
            // WS-XREF-READ-STATUS was left at zero, so the mainline read an account anyway - and, because
            // the record area was never populated, it read account zero.
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

    // =================================================================================================
    // The twelve file verbs.
    // =================================================================================================

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

    /**
     * Asserts that a run abends on an open, having displayed exactly the four lines that open emits.
     *
     * @param fixture   the stubbed world, with one open already made to fail
     * @param errorText the literal the failing paragraph displays
     * @param status    the status it renders
     * @return the abend, for a caller that wants to assert on its cause
     */
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

            // The customer file's literal, not a daily-transaction one - the program has no such literal.
            assertThat(fixture.lines).containsSequence(
                    TransactionPostingJob.ERROR_CLOSING_CUSTFILE,
                    // The CUSTOMER file's status, which its own successful open left at '00' - so the
                    // status that actually failed ('35') is never shown anywhere.
                    FileStatus.toDisplayLine(FileStatus.OK),
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(fixture.lines).doesNotContain(FileStatus.toDisplayLine(OTHER_STATUS));
            // The return code is unaffected by the defect: the ladder had already moved 12.
            assertThat(abend.getReturnCode()).isEqualTo(TransactionPostingJob.APPL_RESULT_FATAL);
            // And the five later closes never happen, exactly as CALL 'CEE3ABD' means they do not.
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
            // Defect 2 again: this arm renders the customer file's '00', not the refusal's status.
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

            // Released, because their own CLOSE paragraph never ran.
            verify(fixture.custfile).close();
            verify(fixture.xrefCursor).close();
            verify(fixture.cardBrowse).close();
            verify(fixture.acctfile).close();
            verify(fixture.tranfile).close();
            // Not released: its CLOSE paragraph did run, and failed.
            verify(fixture.dalytranFile, never()).close();
            // And the release is silent: the run's lines are the empty pass's three plus the failing
            // paragraph's three, and the five releases added none of their own.
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

            // The cross-reference cursor never became this run's handle, because its open failed.
            verify(fixture.xrefCursor, never()).close();
            verify(fixture.xrefCursor, never()).closeBrowse();
            // The two files opened before it are released, silently.
            verify(fixture.dalytranFile).close();
            verify(fixture.custfile).close();
        }
    }

    /**
     * Asserts that a run abends on a close, having displayed the three lines that close emits after the
     * banner and the whole pass.
     *
     * @param fixture   the stubbed world, with one close already made to fail
     * @param errorText the literal the failing paragraph displays
     * @param status    the status it renders
     * @return the abend, for a caller that wants to assert on its cause
     */
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

    // =================================================================================================
    // SYSOUT, and the summary.
    // =================================================================================================

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
