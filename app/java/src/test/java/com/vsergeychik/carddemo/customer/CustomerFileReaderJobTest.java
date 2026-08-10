package com.vsergeychik.carddemo.customer;

import com.vsergeychik.carddemo.CardDemoApplication;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.customer.CustomerRepository.CustomerFile;
import com.vsergeychik.carddemo.customer.CustomerService.Execution;
import com.vsergeychik.carddemo.customer.CustomerService.Sysout;
import com.vsergeychik.carddemo.customer.CustomerService.SysoutSink;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Tests for {@link CustomerFileReaderJob}, the Spring Batch wiring of {@code app/jcl/READCUST.jcl}.
 *
 * <h2>What this suite guards</h2>
 *
 * <p>The job is deliberately thin, so this suite asserts the four things thinness does not excuse:
 * that the JCL's shape reached the batch metadata (one step named {@code STEP05}, no parameters, no
 * gate), that a mis-declared contract stops the context rather than producing a job that runs against
 * the wrong program, that every {@code DISPLAY} the program emitted reaches the {@code SYSOUT}
 * destination <strong>including on the abend path</strong> - because {@code CEE3ABD} does not retract
 * lines already spooled - and that an {@link AbendException} still reaches the framework so the
 * step's exit status carries the COBOL {@code RETURN-CODE} (gate G35).
 *
 * <p>What it deliberately does <em>not</em> re-assert: the program's own branches, statuses and
 * literals. Those belong to {@link CustomerServiceTest}, and duplicating them here would say nothing
 * about the wiring.
 *
 * <h2>How it runs</h2>
 *
 * <p>Plain JUnit 5 with Mockito and AssertJ over an in-memory relation, plus one nested
 * {@code @SpringBootTest} slice that proves the two bean names coexist. No platform default charset and
 * no locale anywhere.
 */
@DisplayName("CustomerFileReaderJob - READCUST.jcl STEP05, the wiring around CBCUS01C")
class CustomerFileReaderJobTest {

    /** The code page of the ASCII fixtures, named explicitly and never taken from the platform. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The dataset name both bindings resolve to in these tests. */
    private static final String TEST_DSNAME = "TEST.CUSTOMER.KSDS";

    /** The shipped customer fixture, on the test classpath. */
    private static final String FIXTURE = "/fixtures/custdata.txt";

    /** The declared record width, {@code CVCUS01Y}. */
    private static final int FIVE_HUNDRED = CustomerRecord.RECORD_LENGTH;

    /** The declared key width, {@code CUST-ID PIC 9(09)}. */
    private static final int NINE = CustomerRepository.KEY_LENGTH;

    /** Keeps every test's in-memory database private to it. */
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    // =============================================================================================
    // Test doubles and fixtures.
    // =============================================================================================

    /** A collecting {@code SYSOUT}: records the lines verbatim, trailing spaces included. */
    private static final class CapturedSysout implements SysoutSink {

        /** The lines written so far, in write order. */
        private final List<String> lines = new ArrayList<>();

        @Override
        public void write(String line) {
            lines.add(line);
        }

        /**
         * The captured sequence.
         *
         * @return the lines, in emission order
         */
        private List<String> lines() {
            return lines;
        }
    }

    /**
     * An {@link ObjectProvider} reporting the bean as absent, so the job falls back to its default sink.
     *
     * @param <T> the bean type
     */
    private static final class AbsentBean<T> implements ObjectProvider<T> {

        @Override
        public T getObject() {
            throw new NoSuchBeanDefinitionException("no bean of this type is declared in this test");
        }
    }

    /**
     * An {@link ObjectProvider} that always yields the given bean.
     *
     * @param bean the bean to yield
     * @param <T>  the bean type
     */
    private record PresentBean<T>(T bean) implements ObjectProvider<T> {

        @Override
        public T getObject() {
            return bean;
        }
    }

    /**
     * The dataset catalogue the repository resolves, at the copybook geometry.
     *
     * @return a catalogue naming {@link #TEST_DSNAME} under both the CICS file name and the batch DD name
     */
    private static DatasetBindings bindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(CustomerRepository.CICS_FILE_NAME, new DatasetBinding(TEST_DSNAME, "ksds", false,
                "FB", null, FIVE_HUNDRED, "CVCUS01Y", NINE, null, null, null));
        catalogue.put(CustomerRepository.BATCH_DD_NAME, new DatasetBinding(TEST_DSNAME, "ksds", false,
                "FB", null, FIVE_HUNDRED, "CVCUS01Y", NINE, null, null, null));
        return catalogue;
    }

    /**
     * The {@code carddemo.jobs} contract for this job, exactly as {@code application.yml} declares it.
     *
     * @return the catalogue containing that one contract
     */
    private static JobContracts jobContracts() {
        return jobContracts(new StepContract(CustomerService.STEP_NAME, CustomerService.PROGRAM_ID,
                false));
    }

    /**
     * The {@code carddemo.jobs} catalogue carrying one deliberately chosen step contract.
     *
     * @param step the step contract to declare
     * @return the catalogue
     */
    private static JobContracts jobContracts(StepContract step) {
        return jobContracts(List.of(step));
    }

    /**
     * The {@code carddemo.jobs} catalogue carrying a deliberately chosen step sequence.
     *
     * @param steps the sequence to declare, in order
     * @return the catalogue
     */
    private static JobContracts jobContracts(List<StepContract> steps) {
        JobContracts catalogue = new JobContracts();
        catalogue.put(CustomerFileReaderJob.JOB_KEY, new JobContract(CustomerService.PROGRAM_ID,
                List.of(), steps, null, Map.of()));
        return catalogue;
    }

    /**
     * The batch scaffolding, with a mocked job repository and transaction manager so a step and a job can
     * be built without an application context.
     *
     * @param contracts the {@code carddemo.jobs} catalogue to bind
     * @return the scaffolding
     */
    private static BatchConfig scaffolding(JobContracts contracts) {
        return new BatchConfig(new PresentBean<>(Mockito.mock(JobRepository.class)),
                new PresentBean<>(Mockito.mock(PlatformTransactionManager.class)),
                contracts, bindings());
    }

    /**
     * A private in-memory relation with one record-image column, seeded with the given rows.
     *
     * @param rows the record images to insert, in order
     * @return a template over the seeded relation
     */
    private static JdbcTemplate seeded(List<String> rows) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:custjob" + DATABASE_SEQUENCE.incrementAndGet()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.execute("CREATE TABLE \"" + TEST_DSNAME + "\" (REC VARCHAR(" + FIVE_HUNDRED + "))");
        for (String row : rows) {
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", row);
        }
        return template;
    }

    /**
     * Record images taken from the shipped fixture, so every numeric field holds real zoned digits.
     *
     * <p>A hand-built image would have to satisfy {@code CUST-ID PIC 9(09)},
     * {@code CUST-SSN PIC 9(09)} and {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at their exact offsets;
     * taking rows from {@code app/data/ASCII/custdata.txt} instead means the fixture's own bytes are what
     * the browse yields.
     *
     * @param count how many of the fixture's rows to take, from the first
     * @return that many 500-character record images, in fixture order
     */
    private static List<String> images(int count) {
        return fixtureRows().subList(0, count);
    }

    /**
     * The shipped customer fixture's rows.
     *
     * @return all 50 record images, in stored order
     */
    private static List<String> fixtureRows() {
        try (java.io.InputStream stream =
                CustomerFileReaderJobTest.class.getResourceAsStream(FIXTURE)) {
            if (stream == null) {
                throw new IllegalStateException("The customer fixture " + FIXTURE + " is absent from the "
                        + "test classpath; the record images in this class are seeded from it");
            }
            return new String(stream.readAllBytes(), ASCII).lines().toList();
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    /**
     * The job over a seeded relation, with the given {@code SYSOUT} destination.
     *
     * @param rows the record images the browse yields
     * @param sink where the displayed lines go, or {@code null} to leave the default resolved
     * @return the subject
     */
    private static CustomerFileReaderJob job(List<String> rows, SysoutSink sink) {
        CustomerRepository repository = new CustomerRepository(seeded(rows), bindings(), ASCII,
                RecordImageForm.CHARACTER);
        return new CustomerFileReaderJob(scaffolding(jobContracts()), new CustomerService(repository),
                sink == null ? new AbsentBean<>() : new PresentBean<>(sink));
    }

    /**
     * A job whose service is a stub reporting the given read statuses, so a fatal arm can be driven.
     *
     * @param sink     where the displayed lines go
     * @param statuses the statuses successive reads report
     * @return the subject
     */
    private static CustomerFileReaderJob jobReporting(SysoutSink sink, String... statuses) {
        CustomerRepository repository = Mockito.mock(CustomerRepository.class);
        CustomerFile file = Mockito.mock(CustomerFile.class);
        Mockito.when(repository.datasetCharset()).thenReturn(ASCII);
        Mockito.when(repository.openInput()).thenReturn(file);
        Mockito.when(file.openStatus()).thenReturn(FileStatus.OK);
        Mockito.when(file.closeFile()).thenReturn(FileStatus.OK);
        CustomerRepository.ReadResult first = CustomerRepository.ReadResult.of(statuses[0]);
        CustomerRepository.ReadResult[] rest =
                new CustomerRepository.ReadResult[Math.max(statuses.length - 1, 0)];
        for (int index = 1; index < statuses.length; index++) {
            rest[index - 1] = CustomerRepository.ReadResult.of(statuses[index]);
        }
        Mockito.when(file.readNext()).thenReturn(first, rest);
        return new CustomerFileReaderJob(scaffolding(jobContracts()), new CustomerService(repository),
                new PresentBean<>(sink));
    }

    /**
     * A step contribution and chunk context pair for a tasklet call.
     *
     * @return a fresh contribution
     */
    private static StepContribution contribution() {
        return new StepContribution(new StepExecution(CustomerService.STEP_NAME,
                new JobExecution(41L)));
    }

    /**
     * @return a fresh chunk context
     */
    private static ChunkContext chunkContext() {
        return new ChunkContext(new StepContext(new StepExecution(CustomerService.STEP_NAME,
                new JobExecution(42L))));
    }

    // =============================================================================================
    // Identity and wiring.
    // =============================================================================================

    @Nested
    @DisplayName("Wiring - one job, one step named for the JCL, no parameters, no gate")
    class Wiring {

        @Test
        @DisplayName("the job and its single step carry the names READCUST.jcl gives them")
        void theJobAndStepAreNamed() {
            CustomerFileReaderJob subject = job(List.of(), new CapturedSysout());

            Job built = subject.customerFileReaderJob();
            Step step = subject.customerFileReaderStep();

            assertThat(built.getName()).isEqualTo(CustomerFileReaderJob.JOB_NAME);
            assertThat(built).isInstanceOf(SimpleJob.class);
            assertThat(((SimpleJob) built).getStepNames())
                    .containsExactly(CustomerService.STEP_NAME);
            assertThat(step.getName()).isEqualTo(CustomerService.STEP_NAME);
        }

        @Test
        @DisplayName("the step name comes from the contract, not from the constant")
        void theStepNameComesFromTheContract() {
            CustomerFileReaderJob subject = job(List.of(), new CapturedSysout());

            assertThat(subject.stepContract().name()).isEqualTo(CustomerService.STEP_NAME);
            assertThat(subject.stepContract().program()).isEqualTo(CustomerService.PROGRAM_ID);
            assertThat(subject.stepContract().requirePrecedingExitCodeZero()).isFalse();
        }

        @Test
        @DisplayName("no job parameter is declared - READCUST.jcl:L6 is a bare EXEC PGM=")
        void noJobParameterIsDeclared() {
            assertThat(job(List.of(), new CapturedSysout()).jobParameters().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("the configuration bean name is not the job bean name, so both can be registered")
        void theTwoBeanNamesDiffer() {
            assertThat(CustomerFileReaderJob.CONFIGURATION_BEAN_NAME)
                    .isNotEqualTo(CustomerFileReaderJob.JOB_NAME);
            assertThat(CustomerFileReaderJob.JOB_KEY).isEqualTo("customer-file-reader-job");
        }

        @Test
        @DisplayName("every collaborator is required")
        void everyCollaboratorIsRequired() {
            CustomerRepository repository = new CustomerRepository(seeded(List.of()), bindings(), ASCII,
                    RecordImageForm.CHARACTER);
            CustomerService service = new CustomerService(repository);
            BatchConfig batch = scaffolding(jobContracts());

            assertThatNullPointerException().isThrownBy(() ->
                    new CustomerFileReaderJob(null, service, new AbsentBean<>()));
            assertThatNullPointerException().isThrownBy(() ->
                    new CustomerFileReaderJob(batch, null, new AbsentBean<>()));
            assertThatNullPointerException().isThrownBy(() ->
                    new CustomerFileReaderJob(batch, service, null));
        }

        @Test
        @DisplayName("an injected SYSOUT sink is the one in use; otherwise the default is resolved")
        void theSinkIsInjectableAndDefaulted() {
            CapturedSysout published = new CapturedSysout();

            assertThat(job(List.of(), published).sysoutSink()).isSameAs(published);
            assertThat(job(List.of(), null).sysoutSink())
                    .as("with no sink bean declared, the standard-output sink is resolved")
                    .isNotNull();
        }

        @Test
        @DisplayName("the default SYSOUT is the service's own, and the job declares no sink of its own")
        void theDefaultSinkIsTheServices() {
            SysoutSink resolved = job(List.of(), null).sysoutSink();

            assertThat(resolved)
                    .as("CBCUS01C's SYSOUT belongs to the translation of CBCUS01C, so the job "
                            + "defaults to the service's sink rather than building one")
                    .isInstanceOf(CustomerService.PrintStreamSysoutSink.class);
            assertThat(((CustomerService.PrintStreamSysoutSink) resolved).stream())
                    .isSameAs(System.out);
            assertThat(CustomerFileReaderJob.class.getDeclaredClasses())
                    .as("a sink type declared here too would be a second definition of one "
                            + "program's output, and a spooled line could drift from an asserted one")
                    .isEmpty();
        }

        @Test
        @DisplayName("a sink is a functional interface, so a list's add method is one")
        void aSinkCanBeALambda() {
            List<String> captured = new ArrayList<>();
            SysoutSink sink = captured::add;

            sink.write(CustomerService.START_OF_EXECUTION);

            assertThat(captured).containsExactly(CustomerService.START_OF_EXECUTION);
        }

        @Test
        @DisplayName("no field of the job is a mutable static")
        void nothingMutableIsStatic() {
            for (Field field : CustomerFileReaderJob.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isStatic(field.getModifiers())
                        && !Modifier.isFinal(field.getModifiers()))
                        .as("mutable static %s", field.getName())
                        .isFalse();
            }
        }
    }

    // =============================================================================================
    // The startup guards.
    // =============================================================================================

    @Nested
    @DisplayName("Startup guards - the contract must still say what READCUST.jcl says")
    class StartupGuards {

        @Test
        @DisplayName("a step naming another program is refused")
        void anotherProgramIsRefused() {
            CustomerRepository repository = new CustomerRepository(seeded(List.of()), bindings(), ASCII,
                    RecordImageForm.CHARACTER);
            BatchConfig wrong = scaffolding(jobContracts(
                    new StepContract(CustomerService.STEP_NAME, "CBACT01C", false)));

            assertThatIllegalStateException()
                    .isThrownBy(() -> new CustomerFileReaderJob(wrong,
                            new CustomerService(repository), new AbsentBean<>()))
                    .withMessageContaining(CustomerService.PROGRAM_ID)
                    .withMessageContaining("READCUST.jcl");
        }

        @Test
        @DisplayName("a gated step is refused - READCUST.jcl carries no COND")
        void aGatedStepIsRefused() {
            CustomerRepository repository = new CustomerRepository(seeded(List.of()), bindings(), ASCII,
                    RecordImageForm.CHARACTER);
            BatchConfig gated = scaffolding(jobContracts(
                    new StepContract(CustomerService.STEP_NAME, CustomerService.PROGRAM_ID, true)));

            assertThatIllegalStateException()
                    .isThrownBy(() -> new CustomerFileReaderJob(gated,
                            new CustomerService(repository), new AbsentBean<>()))
                    .withMessageContaining("COND")
                    .withMessageContaining("bypass");
        }

        @Test
        @DisplayName("a second step declared beside STEP05 is refused - READCUST.jcl has one EXEC")
        void anAddedStepIsRefused() {
            // Both guards above resolve STEP05 by name, so both find it whether it stands alone or first
            // of two. A second step would read CUSTFILE twice and emit two passes of the DISPLAY output
            // the parity harness compares line for line.
            CustomerRepository repository = new CustomerRepository(seeded(List.of()), bindings(), ASCII,
                    RecordImageForm.CHARACTER);
            BatchConfig extra = scaffolding(jobContracts(List.of(
                    new StepContract(CustomerService.STEP_NAME, CustomerService.PROGRAM_ID, false),
                    new StepContract("STEP06", CustomerService.PROGRAM_ID, false))));

            assertThatIllegalStateException()
                    .isThrownBy(() -> new CustomerFileReaderJob(extra,
                            new CustomerService(repository), new AbsentBean<>()))
                    .withMessageContaining("does not declare the step sequence of "
                            + "app/jcl/READCUST.jcl:L6")
                    .withMessageContaining("configured: [STEP05/CBCUS01C, STEP06/CBCUS01C]")
                    .withMessageContaining("required:   [STEP05/CBCUS01C]");
        }

        @Test
        @DisplayName("the shipped single-step sequence is what the class requires")
        void theShippedSequenceIsRequired() {
            assertThat(CustomerFileReaderJob.REQUIRED_STEPS)
                    .containsExactly(new StepContract(CustomerService.STEP_NAME,
                            CustomerService.PROGRAM_ID, false));
            assertThat(jobContracts().get(CustomerFileReaderJob.JOB_KEY).steps())
                    .isEqualTo(CustomerFileReaderJob.REQUIRED_STEPS);
        }

        @Test
        @DisplayName("an absent contract is refused by the scaffolding itself")
        void anAbsentContractIsRefused() {
            CustomerRepository repository = new CustomerRepository(seeded(List.of()), bindings(), ASCII,
                    RecordImageForm.CHARACTER);
            BatchConfig empty = new BatchConfig(
                    new PresentBean<>(Mockito.mock(JobRepository.class)),
                    new PresentBean<>(Mockito.mock(PlatformTransactionManager.class)),
                    new JobContracts(), bindings());

            assertThatIllegalStateException()
                    .isThrownBy(() -> new CustomerFileReaderJob(empty,
                            new CustomerService(repository), new AbsentBean<>()));
        }
    }

    // =============================================================================================
    // The step body.
    // =============================================================================================

    @Nested
    @DisplayName("The tasklet - one pass, every DISPLAY spooled, the abend not swallowed")
    class TheTasklet {

        @Test
        @DisplayName("the tasklet finishes once and reports one read per record")
        void theTaskletFinishes() throws Exception {
            CapturedSysout sysout = new CapturedSysout();
            Tasklet tasklet = job(images(2), sysout)
                    .customerFileDisplayTasklet();
            StepContribution contribution = contribution();

            assertThat(tasklet.execute(contribution, chunkContext())).isEqualTo(RepeatStatus.FINISHED);

            assertThat(contribution.getReadCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("every DISPLAY reaches the spool, in order, two identical images per record")
        void everyDisplayIsSpooled() throws Exception {
            CapturedSysout sysout = new CapturedSysout();
            String only = images(1).get(0);

            job(List.of(only), sysout).customerFileDisplayTasklet()
                    .execute(contribution(), chunkContext());

            assertThat(sysout.lines()).containsExactly(CustomerService.START_OF_EXECUTION, only, only,
                    CustomerService.END_OF_EXECUTION);
            assertThat(sysout.lines().get(1)).hasSize(FIVE_HUNDRED);
        }

        @Test
        @DisplayName("an empty dataset spools the two banners and nothing else")
        void anEmptyDatasetSpoolsTwoBanners() throws Exception {
            CapturedSysout sysout = new CapturedSysout();

            job(List.of(), sysout).customerFileDisplayTasklet()
                    .execute(contribution(), chunkContext());

            assertThat(sysout.lines()).containsExactly(CustomerService.START_OF_EXECUTION,
                    CustomerService.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("an abend reaches the framework and its lines still reach the spool")
        void anAbendIsNotSwallowedAndItsLinesAreSpooled() {
            CapturedSysout sysout = new CapturedSysout();
            Tasklet tasklet = jobReporting(sysout, "35").customerFileDisplayTasklet();
            StepContribution contribution = contribution();
            ChunkContext chunkContext = chunkContext();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> tasklet.execute(contribution, chunkContext))
                    .satisfies(abend -> {
                        assertThat(abend.getProgram()).isEqualTo(CustomerService.PROGRAM_ID);
                        assertThat(abend.getReturnCode())
                                .isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
                    });

            assertThat(sysout.lines())
                    .as("CEE3ABD does not retract lines already spooled")
                    .containsSubsequence(CustomerService.START_OF_EXECUTION,
                            CustomerService.ERROR_READING_CUSTOMER_FILE,
                            CustomerService.ABENDING_PROGRAM)
                    .doesNotContain(CustomerService.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("the program is runnable from this job with no launcher in the path (gate G51)")
        void theProgramIsDirectlyRunnable() {
            CapturedSysout spool = new CapturedSysout();
            CustomerFileReaderJob subject = job(images(1), spool);
            Sysout own = new Sysout();

            Execution execution = subject.readAndPrintCustomerFile(own);

            assertThat(execution.recordsRead()).isEqualTo(1);
            assertThat(execution.returnCode()).isEqualTo(AbendException.RETURN_CODE_OK);
            assertThat(own.lines()).hasSize(4);
            assertThat(spool.lines())
                    .as("a direct call spools nothing: only the tasklet writes to SYSOUT")
                    .isEmpty();
            assertThatNullPointerException()
                    .isThrownBy(() -> subject.readAndPrintCustomerFile(null));
        }
    }

    // =============================================================================================
    // The context slice: the two bean names must coexist.
    // =============================================================================================

    @Nested
    @SpringBootTest(classes = CardDemoApplication.class)
    @ActiveProfiles("test")
    @DisplayName("Context wiring - the configuration bean and the job bean coexist")
    class ContextWiring {

        /** The started context, injected so the beans can be looked up by name and by type. */
        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("the configuration bean and the job bean are both registered, under distinct names")
        void bothBeansAreRegistered() {
            assertThat(context.containsBean(CustomerFileReaderJob.CONFIGURATION_BEAN_NAME)).isTrue();
            assertThat(context.containsBean(CustomerFileReaderJob.JOB_NAME)).isTrue();
        }

        @Test
        @DisplayName("the job bean is a Job whose name is the one a launcher looks up")
        void theJobBeanResolves() {
            Job published = context.getBean(CustomerFileReaderJob.JOB_NAME, Job.class);

            assertThat(published.getName()).isEqualTo(CustomerFileReaderJob.JOB_NAME);
            assertThat(context.getBeanNamesForType(Job.class))
                    .contains(CustomerFileReaderJob.JOB_NAME);
        }

        @Test
        @DisplayName("the job class wires with the configured contract, no parameters and a sink")
        void theJobClassWires() {
            CustomerFileReaderJob subject = context.getBean(CustomerFileReaderJob.class);

            assertThat(subject.stepContract().name()).isEqualTo(CustomerService.STEP_NAME);
            assertThat(subject.stepContract().program()).isEqualTo(CustomerService.PROGRAM_ID);
            assertThat(subject.jobParameters().isEmpty()).isTrue();
            assertThat(subject.sysoutSink()).isNotNull();
        }
    }
}
