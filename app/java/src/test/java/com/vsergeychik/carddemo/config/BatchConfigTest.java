package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContractValidator;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopRequestedException;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.support.DefaultBatchConfiguration;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.ExitCodeExceptionMapper;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Tests for {@link BatchConfig}: the module's one transaction manager, the builder seams the batch
 * job classes are assembled from, the JCL {@code COND=(0,NE)} step-gating policy, the COBOL
 * {@code RETURN-CODE} contract, and - just as load-bearing - every bean this class deliberately
 * refuses to declare.
 *
 * <h2>SPRING BATCH 5.2.6 - READ THIS BEFORE ADDING A BUILDER CALL</h2>
 * <p><strong>{@code JobBuilderFactory} and {@code StepBuilderFactory} do not exist on this
 * classpath.</strong> They were Spring Batch 4 API and were removed in 5. The correct - and only -
 * form is {@code new JobBuilder(name, jobRepository)} and
 * {@code new StepBuilder(name, jobRepository)}, which is what {@link BatchConfig#job(String)} and
 * {@link BatchConfig#step(String)} do and what
 * {@link TransactionManagerAndBuilderSeams#theSeamsBuildNamedJobsAndSteps()} exercises. Reaching
 * for either factory is a compile error, not a deprecation warning.
 *
 * <p>This is not a stylistic note, it is a version constraint with a reason. Spring Boot 4.1.0 and
 * Spring Batch 6.0.4 are both published, and this module is on Boot 3.5.16 with Batch 5.2.6 because
 * the migration plan pins Boot 3.x - constraint fidelity outranks recency (practice B2). The
 * absence of the two factory types is therefore <em>asserted</em> rather than assumed, in
 * {@link TheContractItRefusesToDeclare#theRemovedBatch4BuilderFactoriesAreAbsent()}, so that a
 * future upgrade to Batch 6 fails here with an explanation instead of somewhere obscure.
 *
 * <h2>What this file tests, and what it leaves alone</h2>
 * <p>Wiring, gating policy, return-code translation and the negative contract. It does <em>not</em>
 * start the whole application - {@code CardDemoApplicationTest} owns that graph - and it does not
 * launch a job or test any job's business logic, which belong to the job classes' own tests and to
 * the parity harness. Almost every assertion here is a plain method call with no application
 * context at all, which is what makes the branch-coverage gate reachable deterministically and
 * keeps the suite fast (practice B7).
 *
 * <p>Three assertions do build a context, and each needs to: the single transaction manager over
 * the module's own {@code DataSource}, the fact that the job repository is Spring Boot's rather than
 * this class's, and the effect of {@code spring.batch.job.enabled}. Those use
 * {@link ApplicationContextRunner} over the <em>shipped</em> configuration documents, so what is
 * asserted is the configuration this module actually ships. No test document is authored here:
 * there is exactly one {@code application-test.yml} in this module and this file adds none
 * (practice B4).
 *
 * <p>One boundary is worth naming because it looks like an omission and is not. The statement job's
 * two output widths are <em>not</em> asserted here - they belong to the statement writers, which own
 * them. They are also the estate's one genuine JCL conflict, so it is recorded rather than quietly
 * settled (practice B4): {@code app/jcl/CREASTMT.JCL}'s pre-delete step declares the HTML statement
 * as eighty bytes at L69 while the step that actually creates it declares one hundred at L94. The
 * creating step is authoritative, so the width is one hundred, and the text statement is eighty
 * (L89). What this file does assert about dataset geometry is the record width a <em>job</em>
 * resolves for a DD name, which is a different question and carries no conflict.
 *
 * <h2>Batch 5 requires a DataSource-backed job repository - documented, not worked around</h2>
 * <p>Spring Batch 5 dropped the in-memory map repository, so the real job repository needs a
 * database; the {@code test} profile's in-memory instance supplies one, and H2 is a test-scope
 * dependency that is never promoted (practice B12). The builder-seam assertions below deliberately
 * do <em>not</em> need it: they use the framework's own {@link ResourcelessJobRepository} and
 * {@link ResourcelessTransactionManager}, because what is under test is whether the seam hands a
 * step its repository and its commit boundary, not what either one persists. Where the identity of
 * the real repository matters, the document-backed context supplies the real thing.
 *
 * <h2>{@code COND=(0,NE)} is not the {@code COND=} in a sort control statement</h2>
 * <p>Two unrelated constructs share the keyword, and conflating them would invent step gating the
 * mainframe does not perform:
 * <ul>
 *   <li><strong>JCL step gating.</strong> {@code COND=(0,NE)} on an {@code EXEC} statement means
 *       <em>bypass this step unless every preceding step returned zero</em>. Verified by reading
 *       {@code app/jcl/CREASTMT.JCL}: it appears on exactly three of that job's five steps -
 *       {@code STEP020} (L56), {@code STEP030} (L66) and {@code STEP040} (L79) - and on
 *       <strong>neither</strong> {@code DELDEF01} (L22) nor {@code STEP010} (L44). Those three are
 *       the only step-level {@code COND=(0,NE)} in the whole estate.</li>
 *   <li><strong>A sort utility's record filter.</strong> {@code app/jcl/TRANREPT.jcl:L47} is
 *       {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)} -
 *       a control statement read by DFSORT itself, selecting <em>records</em>. It is not step
 *       gating, that job has no step-level {@code COND} at all, and no step transition may be
 *       modelled from it. The same filter appears in the cataloged procedure form at
 *       {@code app/proc/TRANREPT.prc:L45-L46}.</li>
 * </ul>
 *
 * <p>One further form exists in the estate and is out of scope for the same reason:
 * {@code app/jcl/TRANBKP.jcl:L51} carries {@code COND=(4,LT)}, on a job whose steps are
 * {@code PROC=REPROC} and {@code PGM=IDCAMS} - utilities, not migrated programs. So
 * {@code COND=(0,NE)} is the only gating form any of the nine migrated jobs carries, and
 * {@link CondZeroNotEqualGate#onlyTheStatementJobGatesAnyStep()} asserts exactly that against the
 * shipped contracts.
 *
 * <h2>User-specified rules</h2>
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that single
 * line is the whole document, so <strong>no user rule governs this file</strong>. Its absence is
 * not licence to lower the bar: the migration plan's twelve enterprise practices stand in their
 * place, and the ones bearing on this file are B1 (nothing is added to {@code app/java/pom.xml} -
 * every type used here already arrives with the batch, JDBC and test starters), B2 (Batch 5.2.6 API
 * only, asserted rather than assumed), B3 (the JCL, procedure and control files cited throughout
 * are read-only and appear here purely as provenance), B4 (no configuration document is authored
 * here, and the one genuine JCL conflict is recorded rather than normalised), B7 (no wall clock, no
 * sleep, no launcher, no ordering dependence between methods), B8 (explicit imports, named exit
 * codes and named statuses rather than incidental text), B9 (no mutable static state - every static
 * member is an immutable constant or a side-effect-free function) and B12 (the environment's limits
 * are documented above rather than absorbed).
 *
 * @see BatchConfig
 * @see AbendException
 */
@DisplayName("BatchConfig - the COND=(0,NE) gate, the RETURN-CODE contract and the Batch 5 seams")
class BatchConfigTest {

    /**
     * The configuration key of the only job with gated steps: {@code CBSTM03A}, from
     * {@code app/jcl/CREASTMT.JCL}.
     */
    private static final String STATEMENT_JOB = "statement-generation-job-a";

    /**
     * The configuration key of the only job with a parameter: {@code CBACT04C}, from
     * {@code app/jcl/INTCALC.jcl}.
     */
    private static final String INTEREST_JOB = "account-interest-calc-job";

    /**
     * The configuration key of the {@code POSTTRAN} poster, {@code CBTRN02C}, whose JCL step at
     * {@code app/jcl/POSTTRAN.jcl:L23} is a bare {@code EXEC PGM=} with no {@code PARM}.
     */
    private static final String POSTTRAN_JOB = "transaction-validation-job";

    /**
     * The configuration key of the report job, {@code CBTRN03C}, whose date range is a dataset
     * rather than a parameter.
     */
    private static final String REPORT_JOB = "transaction-report-job";

    /** The configuration key of {@code CBACT02C}, whose JCL DD is {@code CARDFILE}. */
    private static final String READCARD_JOB = "account-balance-reader-job";

    /** The configuration key of {@code CBACT03C}, whose JCL DD is {@code XREFFILE}. */
    private static final String READXREF_JOB = "account-balance-update-job";

    /**
     * The four single-DD readers, each a single ungated {@code STEP05} with no {@code PARM}:
     * {@code app/jcl/READACCT.jcl:L22}, {@code READCARD.jcl:L22}, {@code READXREF.jcl:L22} and
     * {@code READCUST.jcl:L6}. Immutable, so it introduces no shared mutable state.
     */
    private static final List<String> READER_JOBS = List.of(
            "account-balance-job",
            "account-balance-reader-job",
            "account-balance-update-job",
            "customer-file-reader-job");

    /**
     * The {@code PARM} of {@code app/jcl/INTCALC.jcl:L22}, verbatim.
     *
     * <p>Ten characters of <em>character data</em>. {@code CBACT04C} concatenates it straight into
     * the transaction identifiers it generates - {@code STRING PARM-DATE, WS-TRANID-SUFFIX} at
     * {@code app/cbl/CBACT04C.cbl:L476-L477} - so it must round-trip unaltered: no parsing, no
     * reformatting, no revalidation.
     */
    private static final String JCL_PARM_DATE = "2022071800";

    /**
     * The DD name {@code CBTRN03C} reads its reporting date range from
     * ({@code app/jcl/TRANREPT.jcl:L73-L74}), which must never appear as a job parameter.
     */
    private static final String DATE_RANGE_DD = "DATEPARM";

    /**
     * Activates the fixture-backed profile for every document-backed slice in this file.
     *
     * <p>Stated inline on every runner rather than inherited. An {@link ApplicationContextRunner}
     * builds its environment on the surrounding JVM's system properties and process environment, so
     * a build invoked with a different profile would otherwise change which document a slice reads.
     * An inline value is installed as the first property source and outranks both.
     */
    private static final String ACTIVATE_TEST_PROFILE = "spring.profiles.active=test";

    /**
     * A tasklet that does nothing and reports completion, for a step whose <em>body</em> is not
     * what is being asserted.
     *
     * <p>A stateless lambda held in an immutable constant: no shared mutable state, and safe to
     * hand to any number of builders (practice B9).
     */
    private static final Tasklet NO_OP_TASKLET = (contribution, chunkContext) -> RepeatStatus.FINISHED;

    /**
     * An item reader that is immediately exhausted, for the chunk seam's shape assertions.
     *
     * <p>Returning {@code null} on the first read is Spring Batch's own end-of-input signal, so the
     * step is well formed without any data behind it.
     */
    private static final ItemReader<String> EXHAUSTED_READER = () -> null;

    /** An item writer that discards, for the same reason. */
    private static final ItemWriter<String> DISCARDING_WRITER = chunk -> { };

    /**
     * The {@code RETURN-CODE} values an abend can carry, as {@link AbendException} declares them:
     * the {@code APPL-RESULT} set {@code 0}, {@code 4}, {@code 8} and {@code 12}, plus {@code 16}
     * for {@code 88 APPL-EOF}. Immutable.
     */
    private static final List<Integer> CARRIED_RETURN_CODES = List.of(
            AbendException.RETURN_CODE_OK,
            AbendException.RETURN_CODE_WARNING,
            AbendException.RETURN_CODE_ASSUMED_FAILURE,
            AbendException.RETURN_CODE_IO_ERROR,
            AbendException.RETURN_CODE_END_OF_FILE);

    /**
     * A {@link BatchConfig} over the framework's resourceless repository and transaction manager,
     * with empty catalogues.
     *
     * <p>Empty catalogues on purpose: the seam assertions this instance serves are about the
     * builders, and a catalogue they never consult would only obscure that. The contract assertions
     * that <em>do</em> need a catalogue read the shipped documents instead.
     *
     * @return a configuration whose seams can build a step without a database
     */
    private static BatchConfig resourcelessConfig() {
        return configOver(new ResourcelessJobRepository(), new ResourcelessTransactionManager());
    }

    /**
     * A {@link BatchConfig} whose two providers resolve the supplied collaborators.
     *
     * <p>The providers are <em>real</em> ones, obtained from a bean factory holding the two
     * singletons, rather than hand-written stand-ins. That matters: {@link ObjectProvider} is how
     * this class breaks a genuine circular reference - Boot's batch configuration needs the
     * transaction manager to build the job repository, and the transaction manager is a method on
     * this very class - so resolution has to stay deferred to the first seam call, and a real
     * provider is what proves it is.
     *
     * @param repository         the job repository the seams should bind steps to
     * @param transactionManager the commit boundary the seams should hand a step
     * @return the configuration
     */
    private static BatchConfig configOver(JobRepository repository,
            PlatformTransactionManager transactionManager) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("jobRepository", repository);
        factory.registerSingleton("transactionManager", transactionManager);
        return configOver(factory);
    }

    /**
     * A {@link BatchConfig} whose providers resolve against the supplied factory, whatever it holds
     * - including nothing at all.
     *
     * @param factory the factory the two providers resolve through
     * @return the configuration
     */
    private static BatchConfig configOver(DefaultListableBeanFactory factory) {
        ObjectProvider<JobRepository> repositories = factory.getBeanProvider(JobRepository.class);
        ObjectProvider<PlatformTransactionManager> managers =
                factory.getBeanProvider(PlatformTransactionManager.class);
        return new BatchConfig(repositories, managers, new JobContracts(), new DatasetBindings());
    }

    /**
     * A job execution carrying one recorded step per supplied exit code.
     *
     * <p>This is the {@code COND} gate's whole input: JCL evaluates {@code COND} against the return
     * codes of <em>every</em> preceding step, and a job execution's recorded step executions are
     * where those live.
     *
     * @param exitCodes the exit codes to record, in execution order
     * @return the job execution
     */
    private static JobExecution jobExecutionWithStepExitCodes(String... exitCodes) {
        JobExecution jobExecution = new JobExecution(1L);
        for (int index = 0; index < exitCodes.length; index++) {
            StepExecution stepExecution = jobExecution.createStepExecution("STEP" + index);
            stepExecution.setExitStatus(new ExitStatus(exitCodes[index]));
        }
        return jobExecution;
    }

    /**
     * Wraps a failure in {@code depth} plain exceptions, so the outermost is {@code depth} links
     * above it.
     *
     * @param innermost the failure to bury
     * @param depth     how many wrappers to place above it; {@code 0} returns it unwrapped
     * @return the outermost wrapper
     */
    private static Throwable wrapped(Throwable innermost, int depth) {
        Throwable outermost = innermost;
        for (int link = 0; link < depth; link++) {
            outermost = new IllegalStateException("wrapper " + link, outermost);
        }
        return outermost;
    }

    /**
     * A runner over the <strong>shipped</strong> configuration documents with the two configuration
     * classes a batch slice needs.
     *
     * <p>{@code DataSourceConfig} is loaded alongside {@code BatchConfig} because it registers the
     * DD-name catalogue that {@code BatchConfig}'s constructor takes, and publishes the
     * {@code DataSource} the transaction manager wraps. No auto-configuration beyond placeholder
     * resolution is present, so no schema initializer runs and no connection is opened - the slice
     * stays cheap.
     *
     * @return the runner
     */
    private static ApplicationContextRunner documentBackedRunner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> RandomValuePropertySource
                        .addToEnvironment(context.getEnvironment()))
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(DataSourceConfig.class, BatchConfig.class)
                .withPropertyValues(ACTIVATE_TEST_PROFILE);
    }

    /**
     * The same runner with Spring Boot's batch auto-configuration added, so the beans this class
     * refuses to declare are present and their provenance can be asserted.
     *
     * @return the runner
     */
    private static ApplicationContextRunner runnerWithBatchAutoConfiguration() {
        return documentBackedRunner()
                .withConfiguration(AutoConfigurations.of(BatchAutoConfiguration.class));
    }

    /**
     * The return type of every {@code @Bean} method {@link BatchConfig} declares.
     *
     * <p>Read from the class rather than from a list kept by hand, so a bean added later appears
     * here whether or not anyone remembers to record it.
     *
     * @return the declared bean types, in no particular order
     */
    private static List<Class<?>> declaredBeanTypes() {
        return Arrays.stream(BatchConfig.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .map(Method::getReturnType)
                .toList();
    }

    /**
     * The {@code COND=(0,NE)} gate: {@code app/jcl/CREASTMT.JCL}'s "bypass unless every preceding
     * step returned zero", expressed as a predicate and published as a decider.
     *
     * <p>Every arm is driven by direct call, with no application context and no launcher, which is
     * why the gate's branches can be covered exhaustively and cheaply.
     */
    @Nested
    @DisplayName("The COND=(0,NE) gate - bypass unless every preceding step returned zero")
    class CondZeroNotEqualGate {

        @Test
        @DisplayName("COMPLETED and NOOP are both return code zero")
        void completedAndNoopAreZero() {
            // NOOP is a step that did not execute. Treating it as zero is what reproduces the
            // mainframe: a step flushed by COND contributes no return code of its own, while the
            // step whose non-zero code caused the flush is still recorded and still blocks.
            assertThat(BatchConfig.returnCodeOf(ExitStatus.COMPLETED))
                    .isEqualTo(BatchConfig.JCL_RETURN_CODE_ZERO);
            assertThat(BatchConfig.returnCodeOf(ExitStatus.NOOP))
                    .isEqualTo(BatchConfig.JCL_RETURN_CODE_ZERO);
        }

        @ParameterizedTest(name = "the numeric exit code {0} is read as return code {1}")
        @CsvSource({ "0,0", "4,4", "8,8", "12,12", "16,16" })
        @DisplayName("a numeric exit code is the return code it spells, which is how a COBOL code "
                + "travels between steps")
        void aNumericExitCodeIsThatReturnCode(String exitCode, int expected) {
            assertThat(BatchConfig.returnCodeOf(new ExitStatus(exitCode))).isEqualTo(expected);
        }

        @Test
        @DisplayName("surrounding whitespace is tolerated on a numeric exit code")
        void whitespaceAroundANumberIsTolerated() {
            assertThat(BatchConfig.returnCodeOf(new ExitStatus("  8 "))).isEqualTo(8);
        }

        @ParameterizedTest(name = "the framework exit code {0} carries no JCL return code")
        @ValueSource(strings = { "FAILED", "STOPPED", "UNKNOWN", "EXECUTING", "PROCEED", "SKIP",
                "12A", "", "   " })
        @DisplayName("a non-numeric exit code is reported as no return code at all, which is "
                + "non-zero and therefore blocking")
        void aNonNumericExitCodeIsNoReturnCode(String exitCode) {
            assertThat(BatchConfig.returnCodeOf(new ExitStatus(exitCode)))
                    .isEqualTo(BatchConfig.NO_JCL_RETURN_CODE)
                    .isNotEqualTo(BatchConfig.JCL_RETURN_CODE_ZERO);
        }

        @Test
        @DisplayName("a null exit code is folded into the same case rather than refused")
        void aNullExitCodeIsFoldedIn() {
            // The exit status of a step is framework-owned. Refusing to evaluate a gate because of
            // a null exit code would turn a diagnostic edge case into a job failure.
            assertThat(BatchConfig.returnCodeOf(new ExitStatus(null)))
                    .isEqualTo(BatchConfig.NO_JCL_RETURN_CODE);
        }

        @Test
        @DisplayName("a negative exit code is a real number, and is non-zero, so it blocks")
        void aNegativeExitCodeBlocks() {
            assertThat(BatchConfig.returnCodeOf(new ExitStatus("-4"))).isEqualTo(-4);
            assertThat(BatchConfig.precedingStepReturnedZero(new ExitStatus("-4"))).isFalse();
        }

        @Test
        @DisplayName("no exit status at all is a programming error, not an edge case")
        void aNullExitStatusIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> BatchConfig.returnCodeOf(null))
                    .withMessageContaining("exit status is required");
        }

        @Test
        @DisplayName("the proceed branch: a preceding step that returned zero does not block")
        void aStepThatReturnedZeroDoesNotBlock() {
            assertThat(BatchConfig.precedingStepReturnedZero(ExitStatus.COMPLETED)).isTrue();
            assertThat(BatchConfig.precedingStepReturnedZero(new ExitStatus("0"))).isTrue();
        }

        @ParameterizedTest(name = "the skip branch: a preceding step that returned {0} blocks")
        @ValueSource(ints = { 4, 8, 12, 16 })
        @DisplayName("any non-zero return code blocks, one assertion per code so a failure names it")
        void anyNonZeroReturnCodeBlocks(int returnCode) {
            ExitStatus reported = new ExitStatus(Integer.toString(returnCode));

            assertThat(BatchConfig.precedingStepReturnedZero(reported)).isFalse();
        }

        @Test
        @DisplayName("a job execution with nothing recorded yet trivially satisfies the gate")
        void nothingRecordedYetSatisfiesTheGate() {
            // Which matches the mainframe: a COND test with nothing before it to examine does not
            // bypass the step.
            assertThat(BatchConfig.allPrecedingStepsReturnedZero(jobExecutionWithStepExitCodes()))
                    .isTrue();
        }

        @Test
        @DisplayName("every recorded step returning zero satisfies the gate")
        void everyStepReturningZeroSatisfiesTheGate() {
            JobExecution execution = jobExecutionWithStepExitCodes("COMPLETED", "0", "NOOP");

            assertThat(BatchConfig.allPrecedingStepsReturnedZero(execution)).isTrue();
        }

        @Test
        @DisplayName("one non-zero step anywhere in the history blocks, not merely the last one")
        void oneNonZeroStepAnywhereBlocks() {
            // This is the substantive reading of COND=(0,NE): JCL tests it against EVERY preceding
            // step. A gate that looked only at the step it arrived from would run STEP040 after
            // STEP020 failed and STEP030 succeeded, which app/jcl/CREASTMT.JCL does not do.
            JobExecution earlierFailure = jobExecutionWithStepExitCodes("8", "COMPLETED");

            assertThat(BatchConfig.allPrecedingStepsReturnedZero(earlierFailure)).isFalse();
        }

        @Test
        @DisplayName("no job execution at all is a programming error")
        void aNullJobExecutionIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> BatchConfig.allPrecedingStepsReturnedZero(null))
                    .withMessageContaining("COND");
        }

        @Test
        @DisplayName("the decision is PROCEED when everything returned zero and SKIP otherwise")
        void theDecisionFollowsTheHistory() {
            JobExecution clean = jobExecutionWithStepExitCodes("COMPLETED", "COMPLETED");
            JobExecution dirty = jobExecutionWithStepExitCodes("COMPLETED", "12");

            assertThat(BatchConfig.decidePrecedingExitCodeZero(clean, null))
                    .isEqualTo(BatchConfig.PROCEED);
            assertThat(BatchConfig.decidePrecedingExitCodeZero(dirty, null))
                    .isEqualTo(BatchConfig.SKIP);
        }

        @Test
        @DisplayName("the step the flow arrived from is deliberately not consulted")
        void theArrivingStepIsNotConsulted() {
            // Passing the step that arrived changes nothing, because the whole history is what the
            // gate reads. Here the arriving step returned zero and an earlier one did not.
            JobExecution execution = jobExecutionWithStepExitCodes("8", "COMPLETED");
            StepExecution arrivedFrom = execution.getStepExecutions().stream()
                    .filter(step -> "COMPLETED".equals(step.getExitStatus().getExitCode()))
                    .findFirst()
                    .orElseThrow();

            assertThat(BatchConfig.decidePrecedingExitCodeZero(execution, arrivedFrom))
                    .isEqualTo(BatchConfig.SKIP);
            assertThat(BatchConfig.decidePrecedingExitCodeZero(execution, null))
                    .isEqualTo(BatchConfig.SKIP);
        }

        @Test
        @DisplayName("the published decider is the same policy, reachable from a job flow")
        void thePublishedDeciderIsTheSamePolicy() {
            JobExecutionDecider decider = resourcelessConfig().precedingExitCodeZeroDecider();
            JobExecution clean = jobExecutionWithStepExitCodes("COMPLETED");
            JobExecution dirty = jobExecutionWithStepExitCodes("4");

            assertThat(decider.decide(clean, null)).isEqualTo(BatchConfig.PROCEED);
            assertThat(decider.decide(dirty, null)).isEqualTo(BatchConfig.SKIP);
        }

        @Test
        @DisplayName("the two outcomes are distinct, named as a flow wires them, and neither is a "
                + "failure")
        void theTwoOutcomesAreDistinctAndNeitherIsAFailure() {
            // Bypassing is deliberately not a failure: on the mainframe a step flushed by COND does
            // not itself fail the job, and the non-zero code that caused the bypass is already
            // recorded on the step that produced it.
            assertThat(BatchConfig.PROCEED.getName()).isEqualTo("PROCEED");
            assertThat(BatchConfig.SKIP.getName()).isEqualTo("SKIP");
            assertThat(BatchConfig.PROCEED).isNotEqualTo(BatchConfig.SKIP);
            assertThat(BatchConfig.SKIP).isNotEqualTo(FlowExecutionStatus.FAILED);
            assertThat(BatchConfig.PROCEED).isNotEqualTo(FlowExecutionStatus.FAILED);
        }

        @Test
        @DisplayName("the statement job's five steps, gated on exactly the three the JCL gates")
        void theStatementJobGatesExactlyThreeOfFiveSteps() {
            documentBackedRunner().run(context -> {
                List<StepContract> steps = context.getBean(JobContracts.class)
                        .contract(STATEMENT_JOB).steps();

                // app/jcl/CREASTMT.JCL, read directly: DELDEF01 L22 (IDCAMS delete and define),
                // STEP010 L44 (SORT), STEP020 L56 (IDCAMS REPRO), STEP030 L66 (IEFBR14 pre-delete),
                // STEP040 L79 (CBSTM03A). COND=(0,NE) appears on the last three and on neither of
                // the first two.
                assertThat(steps).extracting(StepContract::name)
                        .containsExactly("DELDEF01", "STEP010", "STEP020", "STEP030", "STEP040");
                assertThat(steps).hasSize(5);
                assertThat(steps.stream()
                        .filter(StepContract::requirePrecedingExitCodeZero)
                        .map(StepContract::name)
                        .toList())
                        .containsExactly("STEP020", "STEP030", "STEP040")
                        .hasSize(3);
                assertThat(steps.stream()
                        .filter(step -> !step.requirePrecedingExitCodeZero())
                        .map(StepContract::name)
                        .toList())
                        .containsExactly("DELDEF01", "STEP010");
            });
        }

        @Test
        @DisplayName("only the statement job gates any step at all, across all nine jobs")
        void onlyTheStatementJobGatesAnyStep() {
            documentBackedRunner().run(context -> {
                JobContracts contracts = context.getBean(JobContracts.class);

                assertThat(contracts).hasSize(JobContracts.REQUIRED_JOBS.size());
                assertThat(contracts.entrySet().stream()
                        .filter(job -> job.getValue().steps().stream()
                                .anyMatch(StepContract::requirePrecedingExitCodeZero))
                        .map(Map.Entry::getKey)
                        .toList())
                        .containsExactly(STATEMENT_JOB);
            });
        }

        @Test
        @DisplayName("the report job models no gating, because its COND= is a sort record filter")
        void theReportJobModelsNoGating() {
            documentBackedRunner().run(context -> {
                JobContract report = context.getBean(JobContracts.class).contract(REPORT_JOB);

                // app/jcl/TRANREPT.jcl's only COND= is the DFSORT INCLUDE filter at L47, which
                // selects records rather than gating steps. Its three steps take the unambiguous
                // names from app/proc/TRANREPT.prc, because the JCL labels its first two STEP05R
                // twice (L23 and L37).
                assertThat(report.steps()).extracting(StepContract::name)
                        .containsExactly("STEP01R", "STEP05R", "STEP10R");
                assertThat(report.steps())
                        .noneMatch(StepContract::requirePrecedingExitCodeZero);
            });
        }

        @Test
        @DisplayName("the four single-DD readers are one ungated step each, on their own program")
        void theFourReadersAreOneUngatedStepEach() {
            documentBackedRunner().run(context -> {
                JobContracts contracts = context.getBean(JobContracts.class);

                for (String jobKey : READER_JOBS) {
                    JobContract reader = contracts.contract(jobKey);
                    assertThat(reader.steps()).as("%s has one step", jobKey).hasSize(1);
                    assertThat(reader.steps().get(0).name()).isEqualTo("STEP05");
                    assertThat(reader.steps().get(0).requirePrecedingExitCodeZero())
                            .as("%s gates nothing", jobKey).isFalse();
                    assertThat(reader.steps().get(0).program())
                            .isEqualTo(JobContracts.REQUIRED_JOBS.get(jobKey));
                }
            });
        }
    }

    /**
     * The {@code RETURN-CODE} contract: an abend's code reaches both the Spring Batch exit status,
     * where a step transition can test it, and the process exit code, where a shell or scheduler
     * sees what JCL would have seen.
     *
     * <p>The codes are never re-derived. {@link AbendException} carries the value the COBOL placed
     * in {@code APPL-RESULT} - the nine {@code CALL 'CEE3ABD'} sites move {@code 8} or {@code 12}
     * into it, and {@code CSUTLDTC} ends with {@code MOVE WS-SEVERITY-N TO RETURN-CODE} - and these
     * listeners only transcribe it.
     */
    @Nested
    @DisplayName("The RETURN-CODE contract - 0, 4, 8, 12 reach the exit status and the process")
    class ReturnCodeContract {

        @ParameterizedTest(name = "an abend carrying RETURN-CODE {0} maps to exit code {0}")
        @ValueSource(ints = { 0, 4, 8, 12, 16 })
        @DisplayName("the carried code is transcribed unchanged, including zero")
        void theCarriedCodeIsTranscribedUnchanged(int returnCode) {
            AbendException abend = AbendException.standard("CBACT04C", returnCode);

            // Zero is transcribed rather than "corrected" to a failure: the value is the COBOL's,
            // and re-deriving it here would be this class inventing a return code.
            assertThat(BatchConfig.exitCodeFor(abend)).isEqualTo(returnCode);
            assertThat(BatchConfig.withAbendExitCode(ExitStatus.FAILED, List.<Throwable>of(abend))
                    .getExitCode()).isEqualTo(Integer.toString(returnCode));
        }

        @ParameterizedTest(name = "the CBSTM03A shape carrying RETURN-CODE {0} maps the same way")
        @ValueSource(ints = { 0, 4, 8, 12, 16 })
        @DisplayName("an abend that set neither ABCODE nor TIMING maps identically - the "
                + "CBSTM03A.CBL:923 shape")
        void theShapeWithoutAbendParametersMapsIdentically(int returnCode) {
            // app/cbl/CBSTM03A.CBL:921-923 is the one abend paragraph that performs no
            // MOVE 999 TO ABCODE and no MOVE 0 TO TIMING. The absence must not change the
            // return-code translation, and it must stay representable rather than be normalised.
            AbendException diverging = AbendException.withoutAbendParameters("CBSTM03A", returnCode);
            AbendException standard = AbendException.standard("CBSTM03A", returnCode);

            assertThat(diverging.hasAbendCode()).isFalse();
            assertThat(diverging.hasTiming()).isFalse();
            assertThat(standard.hasAbendCode()).isTrue();
            assertThat(standard.hasTiming()).isTrue();
            assertThat(BatchConfig.exitCodeFor(diverging))
                    .isEqualTo(returnCode)
                    .isEqualTo(BatchConfig.exitCodeFor(standard));
        }

        @Test
        @DisplayName("every carried code is mapped, so the translation is total")
        void everyCarriedCodeIsMapped() {
            assertThat(CARRIED_RETURN_CODES)
                    .allSatisfy(returnCode -> assertThat(BatchConfig
                            .exitCodeFor(AbendException.standard("CBTRN02C", returnCode)))
                            .isEqualTo(returnCode));
        }

        @Test
        @DisplayName("the fallback arm: a failure this migration has no opinion about claims nothing")
        void anUnrelatedFailureClaimsNothing() {
            // Zero is Spring Boot's own "no opinion" signal - its exit-code aggregation keeps the
            // highest positive value any contributor offers - so an unrelated failure is left to
            // whatever else the application configures. This is the WHEN OTHER arm, and it is last.
            assertThat(BatchConfig.exitCodeFor(new IllegalStateException("unrelated")))
                    .isEqualTo(BatchConfig.NO_MAPPED_EXIT_CODE);
            assertThat(BatchConfig.exitCodeFor(new RuntimeException(new IllegalArgumentException())))
                    .isEqualTo(BatchConfig.NO_MAPPED_EXIT_CODE);
        }

        @Test
        @DisplayName("no failure at all is the same arm, not a dereference")
        void aNullFailureIsTheSameArm() {
            assertThat(BatchConfig.exitCodeFor(null)).isEqualTo(BatchConfig.NO_MAPPED_EXIT_CODE);
            assertThat(BatchConfig.findAbend((Throwable) null)).isEmpty();
            assertThat(BatchConfig.findAbend((List<Throwable>) null)).isEmpty();
        }

        @Test
        @DisplayName("an abend that arrived wrapped is still the abend the COBOL raised")
        void aWrappedAbendIsStillFound() {
            // Spring Batch may hand a listener the exception it caught rather than the one the
            // tasklet threw - a rollback or transaction wrapper sits in between.
            AbendException abend = AbendException.standard("CBTRN03C",
                    AbendException.RETURN_CODE_IO_ERROR, "ERROR READING TRANFILE");

            assertThat(BatchConfig.exitCodeFor(wrapped(abend, 1))).isEqualTo(12);
            assertThat(BatchConfig.exitCodeFor(wrapped(abend, 3))).isEqualTo(12);
            assertThat(BatchConfig.findAbend(wrapped(abend, 2))).containsSame(abend);
        }

        @Test
        @DisplayName("the cause walk is bounded, so a pathological chain can never hang a build")
        void theCauseWalkIsBounded() {
            AbendException abend = AbendException.standard("CBACT01C",
                    AbendException.RETURN_CODE_ASSUMED_FAILURE);

            // Sixteen links are walked, so an abend fifteen wrappers deep is found and one sixteen
            // deep is not. Far beyond anything the framework produces - a tasklet failure reaches
            // the listener wrapped once or twice - and the bound is what makes the walk safe.
            assertThat(BatchConfig.findAbend(wrapped(abend, 15))).containsSame(abend);
            assertThat(BatchConfig.findAbend(wrapped(abend, 16))).isEmpty();
            assertThat(BatchConfig.exitCodeFor(wrapped(abend, 16)))
                    .isEqualTo(BatchConfig.NO_MAPPED_EXIT_CODE);
        }

        @Test
        @DisplayName("a cause chain that loops back on itself terminates rather than spinning")
        void aCyclicCauseChainTerminates() {
            RuntimeException outer = new RuntimeException("outer");
            RuntimeException inner = new RuntimeException("inner");
            outer.initCause(inner);
            inner.initCause(outer);

            assertThat(BatchConfig.findAbend(outer)).isEmpty();
            assertThat(BatchConfig.exitCodeFor(outer))
                    .isEqualTo(BatchConfig.NO_MAPPED_EXIT_CODE);
        }

        @Test
        @DisplayName("the first abend among several failures is the one reported")
        void theFirstAbendAmongSeveralIsReported() {
            AbendException first = AbendException.standard("CBTRN02C",
                    AbendException.RETURN_CODE_ASSUMED_FAILURE);
            AbendException second = AbendException.standard("CBTRN02C",
                    AbendException.RETURN_CODE_IO_ERROR);

            assertThat(BatchConfig.findAbend(List.<Throwable>of(first, second))).containsSame(first);
            // A non-abend ahead of it is skipped rather than claimed, which is what makes the
            // fallback arm genuinely last.
            assertThat(BatchConfig.findAbend(
                    List.<Throwable>of(new IllegalStateException("noise"), second)))
                    .containsSame(second);
            assertThat(BatchConfig.findAbend(
                    List.<Throwable>of(new IllegalStateException("noise")))).isEmpty();
            assertThat(BatchConfig.findAbend(List.<Throwable>of())).isEmpty();
        }

        @Test
        @DisplayName("the exit description is preserved, so the text the COBOL displayed survives")
        void theExitDescriptionIsPreserved() {
            // The description is Spring Batch's own record of the failure and is not composed here.
            // Preserving it is what keeps the DISPLAY text that precedes CALL 'CEE3ABD' visible.
            AbendException abend = AbendException.standard("CBACT01C",
                    AbendException.RETURN_CODE_IO_ERROR, "ERROR OPENING ACCTFILE");
            ExitStatus asTheFrameworkLeftIt = new ExitStatus("FAILED", abend.getMessage());

            ExitStatus reported = BatchConfig.withAbendExitCode(asTheFrameworkLeftIt,
                    List.<Throwable>of(abend));

            assertThat(reported.getExitCode()).isEqualTo("12");
            assertThat(reported.getExitDescription())
                    .isEqualTo(asTheFrameworkLeftIt.getExitDescription())
                    .contains(AbendException.ABEND_DISPLAY_TEXT)
                    .contains("CBACT01C")
                    .contains("ERROR OPENING ACCTFILE");
        }

        @Test
        @DisplayName("a status with no abend behind it is returned untouched, not rewritten")
        void aStatusWithNoAbendIsUntouched() {
            assertThat(BatchConfig.withAbendExitCode(ExitStatus.COMPLETED, List.<Throwable>of()))
                    .isSameAs(ExitStatus.COMPLETED);
            assertThat(BatchConfig.withAbendExitCode(ExitStatus.COMPLETED, null))
                    .isSameAs(ExitStatus.COMPLETED);
            assertThat(BatchConfig.withAbendExitCode(ExitStatus.COMPLETED,
                    List.<Throwable>of(new IllegalStateException("unrelated"))))
                    .isSameAs(ExitStatus.COMPLETED);
        }

        @Test
        @DisplayName("no current status is read as UNKNOWN rather than refused")
        void noCurrentStatusIsReadAsUnknown() {
            assertThat(BatchConfig.withAbendExitCode(null, List.<Throwable>of()))
                    .isEqualTo(ExitStatus.UNKNOWN);
            assertThat(BatchConfig.withAbendExitCode(null,
                    List.<Throwable>of(AbendException.standard("CBCUS01C", 12))).getExitCode())
                    .isEqualTo("12");
        }

        @Test
        @DisplayName("the step listener puts the abend's code on a failed step's exit status")
        void theStepListenerCarriesTheCodeOntoTheStep() {
            StepExecutionListener listener = resourcelessConfig().abendExitStatusStepListener();
            JobExecution jobExecution = new JobExecution(1L);
            StepExecution stepExecution = jobExecution.createStepExecution("STEP15");
            stepExecution.setExitStatus(ExitStatus.FAILED);
            stepExecution.addFailureException(AbendException.standard("CBTRN02C",
                    AbendException.RETURN_CODE_ASSUMED_FAILURE));

            assertThat(listener).isInstanceOf(BatchConfig.AbendExitStatusStepListener.class);
            assertThat(listener.afterStep(stepExecution).getExitCode()).isEqualTo("8");
        }

        @Test
        @DisplayName("a step that did not fail is left exactly as the framework left it")
        void aSuccessfulStepIsLeftAlone() {
            StepExecutionListener listener = resourcelessConfig().abendExitStatusStepListener();
            JobExecution jobExecution = new JobExecution(1L);
            StepExecution stepExecution = jobExecution.createStepExecution("STEP05");
            stepExecution.setExitStatus(ExitStatus.COMPLETED);

            assertThat(listener.afterStep(stepExecution)).isSameAs(stepExecution.getExitStatus());
        }

        @Test
        @DisplayName("the job listener states the code a following JCL step would have tested")
        void theJobListenerCarriesTheCodeOntoTheJob() {
            JobExecutionListener listener = resourcelessConfig().abendExitStatusJobListener();
            JobExecution jobExecution = new JobExecution(1L);
            jobExecution.setExitStatus(ExitStatus.FAILED);
            // The abend is raised inside a step, and the job records the failure that ended it, so
            // the listener has to read the steps' failures as well as the job's own.
            jobExecution.createStepExecution("STEP040").addFailureException(
                    AbendException.withoutAbendParameters("CBSTM03A",
                            AbendException.RETURN_CODE_IO_ERROR));

            listener.afterJob(jobExecution);

            assertThat(listener).isInstanceOf(BatchConfig.AbendExitStatusJobListener.class);
            assertThat(jobExecution.getExitStatus().getExitCode()).isEqualTo("12");
        }

        @Test
        @DisplayName("a job listener reading a job's own failure reports it too")
        void theJobListenerReadsTheJobsOwnFailure() {
            JobExecutionListener listener = resourcelessConfig().abendExitStatusJobListener();
            JobExecution jobExecution = new JobExecution(1L);
            jobExecution.setExitStatus(ExitStatus.FAILED);
            jobExecution.addFailureException(AbendException.standard("CBACT04C",
                    AbendException.RETURN_CODE_WARNING));

            listener.afterJob(jobExecution);

            assertThat(jobExecution.getExitStatus().getExitCode()).isEqualTo("4");
        }

        @Test
        @DisplayName("a job that did not fail keeps the status it already had")
        void aSuccessfulJobKeepsItsStatus() {
            JobExecutionListener listener = resourcelessConfig().abendExitStatusJobListener();
            JobExecution jobExecution = new JobExecution(1L);
            jobExecution.setExitStatus(ExitStatus.COMPLETED);

            listener.afterJob(jobExecution);

            assertThat(jobExecution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        }

        @ParameterizedTest(name = "the mapper carries RETURN-CODE {0} out to the process")
        @ValueSource(ints = { 0, 4, 8, 12, 16 })
        @DisplayName("the exit-code mapper is the second half of the contract, and carries the same "
                + "codes")
        void theMapperCarriesTheSameCodes(int returnCode) {
            ExitCodeExceptionMapper mapper = resourcelessConfig().abendExitCodeMapper();

            assertThat(mapper.getExitCode(AbendException.standard("CBACT01C", returnCode)))
                    .isEqualTo(returnCode);
            assertThat(mapper.getExitCode(wrapped(
                    AbendException.standard("CBACT01C", returnCode), 2))).isEqualTo(returnCode);
        }

        @Test
        @DisplayName("the mapper claims nothing for an unrelated failure")
        void theMapperClaimsNothingForAnUnrelatedFailure() {
            ExitCodeExceptionMapper mapper = resourcelessConfig().abendExitCodeMapper();

            assertThat(mapper.getExitCode(new IllegalStateException("unrelated")))
                    .isEqualTo(BatchConfig.NO_MAPPED_EXIT_CODE);
        }

        @Test
        @DisplayName("a code written by a listener is readable by the gate, which is how it travels")
        void aCodeWrittenByAListenerIsReadableByTheGate() {
            // The two halves of this class meet here: the listener writes a numeric exit code and
            // returnCodeOf reads it back, which is exactly how a COBOL return code reaches the next
            // step's COND test.
            for (int returnCode : CARRIED_RETURN_CODES) {
                ExitStatus written = BatchConfig.withAbendExitCode(ExitStatus.FAILED,
                        List.<Throwable>of(AbendException.standard("CBTRN01C", returnCode)));

                assertThat(BatchConfig.returnCodeOf(written)).isEqualTo(returnCode);
                assertThat(BatchConfig.precedingStepReturnedZero(written))
                        .isEqualTo(returnCode == BatchConfig.JCL_RETURN_CODE_ZERO);
            }
        }
    }

    /**
     * The one transaction manager, and the four builder seams that keep Spring Batch plumbing out of
     * the job classes.
     *
     * <p>Every seam is built with the Spring Batch 5 constructors - see this class's documentation
     * for why the Batch 4 factories cannot be used - so these assertions are simultaneously a
     * compile-time guarantee that the module is on the API it claims to be on.
     */
    @Nested
    @DisplayName("The transaction manager and the Batch 5 builder seams")
    class TransactionManagerAndBuilderSeams {

        /** An in-memory URL, enough for the pool to derive a driver from the test classpath. */
        private static final String H2_URL = "jdbc:h2:mem:batchconfigtest";

        /**
         * The {@code DataSource} {@code DataSourceConfig} publishes, built the same way the context
         * builds it.
         *
         * @return a pooled data source; no connection is opened by constructing it
         */
        private static DataSource pooledDataSource() {
            DataSourceProperties properties = new DataSourceProperties();
            properties.setUrl(H2_URL);
            return new DataSourceConfig().dataSource(properties);
        }

        @Test
        @DisplayName("the transaction manager is a JdbcTransactionManager over the supplied "
                + "DataSource, with nothing else configured on it")
        void theTransactionManagerWrapsTheSuppliedDataSource() {
            DataSource dataSource = pooledDataSource();

            PlatformTransactionManager manager = resourcelessConfig().transactionManager(dataSource);

            // JdbcTransactionManager rather than the plain data-source manager it extends: it adds
            // SQL exception translation. Nothing else is set - no timeout, no isolation override, no
            // propagation default - because commit boundaries belong to the step and the service
            // layer, where they mirror the point at which the COBOL performs its REWRITE.
            assertThat(manager).isInstanceOf(JdbcTransactionManager.class);
            assertThat(((JdbcTransactionManager) manager).getDataSource()).isSameAs(dataSource);
            JdbcTransactionManager untouched = new JdbcTransactionManager(dataSource);
            assertThat(((JdbcTransactionManager) manager).getDefaultTimeout())
                    .isEqualTo(untouched.getDefaultTimeout());
            assertThat(((JdbcTransactionManager) manager).isEnforceReadOnly())
                    .isEqualTo(untouched.isEnforceReadOnly());
        }

        @Test
        @DisplayName("the shipped documents publish exactly one transaction manager, over the "
                + "module's own DataSource")
        void theShippedDocumentsPublishExactlyOneTransactionManager() {
            // Exactly one, on purpose: Spring Batch resolves a transaction manager by type when it
            // builds the job repository, and so does every step this module assembles, so a second
            // definition anywhere would make that resolution ambiguous and stop the context from
            // starting. That is also why there is no primary-bean marker to be found.
            documentBackedRunner().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(PlatformTransactionManager.class);
                assertThat(context).hasBean("transactionManager");

                PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
                assertThat(manager).isInstanceOf(JdbcTransactionManager.class);
                assertThat(((JdbcTransactionManager) manager).getDataSource())
                        .isSameAs(context.getBean(DataSource.class));
            });
        }

        @Test
        @DisplayName("the seams build named jobs and steps - and do it with the Batch 5 API")
        void theSeamsBuildNamedJobsAndSteps() {
            BatchConfig config = resourcelessConfig();

            Step tasklet = config.taskletStep("STEP040", NO_OP_TASKLET).build();
            Step chunk = config.<String, String>chunkStep("STEP15", 1)
                    .reader(EXHAUSTED_READER)
                    .writer(DISCARDING_WRITER)
                    .build();
            Step bare = config.step("STEP05")
                    .tasklet(NO_OP_TASKLET, new ResourcelessTransactionManager())
                    .build();
            Job job = config.job("statementGenerationJobA").start(tasklet).build();

            // Step names are transcribed from the JCL step they replace: STEP040 is
            // app/jcl/CREASTMT.JCL:L79, STEP15 is app/jcl/POSTTRAN.jcl:L23 and STEP05 is
            // app/jcl/READACCT.jcl:L22.
            assertThat(tasklet.getName()).isEqualTo("STEP040");
            assertThat(chunk.getName()).isEqualTo("STEP15");
            assertThat(bare.getName()).isEqualTo("STEP05");
            assertThat(job.getName()).isEqualTo("statementGenerationJobA");
        }

        @Test
        @DisplayName("every job the seam builds carries a run identity and refuses to be restarted")
        void everyJobIsAFreshRunAndNeverARestart() {
            BatchConfig config = resourcelessConfig();

            Job job = config.job("accountBalanceJob")
                    .start(config.taskletStep("STEP05", NO_OP_TASKLET).build())
                    .build();

            // A JCL job is submitted, in full, whenever the work needs doing again. Eight of the nine
            // jobs here take no parameter at all, so without an identity of their own each would have
            // exactly one instance for all time and a second submission would be refused as already
            // complete. And nothing in the estate stores a checkpoint - CBACT04C commits per chunk while
            // accumulating per account and records nothing - so resuming a failed execution would
            // re-apply work, while skipping a completed step in TRANREPT or CREASTMT would report a
            // fresh run over stale intermediate data.
            assertThat(job.getJobParametersIncrementer())
                    .as("the run identity is what makes a parameterless job submittable twice")
                    .isSameAs(config.jclRunIdentityIncrementer());
            assertThat(job.isRestartable())
                    .as("a resubmission re-runs every step; it never resumes a failed execution")
                    .isFalse();
        }

        @Test
        @DisplayName("the run identity is one identifying Long and nothing that resembles a business "
                + "value")
        void theRunIdentityIsOnlyARunIdentity() {
            JobParametersIncrementer incrementer = resourcelessConfig().jclRunIdentityIncrementer();

            JobParameters first = incrementer.getNext(new JobParameters());
            JobParameters second = incrementer.getNext(first);
            JobParameters besideAParm = incrementer.getNext(new JobParametersBuilder()
                    .addString(BatchConfig.PARM_DATE_PARAMETER, "2022071800")
                    .toJobParameters());

            assertThat(first.getParameters().keySet())
                    .containsExactly(BatchConfig.RUN_IDENTITY_PARAMETER);
            assertThat(second.getLong(BatchConfig.RUN_IDENTITY_PARAMETER))
                    .isGreaterThan(first.getLong(BatchConfig.RUN_IDENTITY_PARAMETER));
            assertThat(first.getParameters().get(BatchConfig.RUN_IDENTITY_PARAMETER).isIdentifying())
                    .as("a non-identifying value would not produce a new job instance, which is the "
                            + "whole purpose")
                    .isTrue();
            // The one genuine parameter in the estate travels beside the identity, exactly as
            // PARM='2022071800' travels on the EXEC statement.
            assertThat(besideAParm.getString(BatchConfig.PARM_DATE_PARAMETER))
                    .isEqualTo("2022071800");
            assertThat(besideAParm.getParameters().keySet())
                    .containsExactlyInAnyOrder(BatchConfig.PARM_DATE_PARAMETER,
                            BatchConfig.RUN_IDENTITY_PARAMETER);
        }

        @Test
        @DisplayName("a fresh builder on every call, because builders are single-use and mutable")
        void everyCallReturnsAFreshBuilder() {
            BatchConfig config = resourcelessConfig();

            assertThat(config.job("accountBalanceJob")).isNotSameAs(config.job("accountBalanceJob"));
            assertThat(config.step("STEP05")).isNotSameAs(config.step("STEP05"));
            assertThat(config.taskletStep("STEP05", NO_OP_TASKLET))
                    .isNotSameAs(config.taskletStep("STEP05", NO_OP_TASKLET));
            assertThat(config.chunkStep("STEP15", 1)).isNotSameAs(config.chunkStep("STEP15", 1));
        }

        @ParameterizedTest(name = "a job name of [{0}] is refused")
        @ValueSource(strings = { "", " ", "\t" })
        @DisplayName("a job needs a name, because the name is its identity in the batch metadata")
        void aJobNeedsAName(String blank) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> resourcelessConfig().job(blank))
                    .withMessageContaining("job name is required");
        }

        @Test
        @DisplayName("a null job name is refused the same way")
        void aNullJobNameIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> resourcelessConfig().job(null))
                    .withMessageContaining("job name is required");
        }

        @ParameterizedTest(name = "a step name of [{0}] is refused")
        @ValueSource(strings = { "", " ", "\t" })
        @DisplayName("a step needs a name, because a step is addressed by name")
        void aStepNeedsAName(String blank) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> resourcelessConfig().step(blank))
                    .withMessageContaining("step name is required");
        }

        @Test
        @DisplayName("a null step name is refused, through the convenience seams as well")
        void aNullStepNameIsRefused() {
            BatchConfig config = resourcelessConfig();

            assertThatIllegalArgumentException().isThrownBy(() -> config.step(null))
                    .withMessageContaining("step name is required");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> config.taskletStep(null, NO_OP_TASKLET))
                    .withMessageContaining("step name is required");
            assertThatIllegalArgumentException().isThrownBy(() -> config.chunkStep("", 1))
                    .withMessageContaining("step name is required");
        }

        @Test
        @DisplayName("a tasklet step needs a tasklet")
        void aTaskletStepNeedsATasklet() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> resourcelessConfig().taskletStep("STEP05", null))
                    .withMessageContaining("tasklet is required");
        }

        @ParameterizedTest(name = "a chunk size of {0} is refused")
        @ValueSource(ints = { 0, -1, Integer.MIN_VALUE })
        @DisplayName("a chunk size must be positive, and is never a module-wide default")
        void aChunkSizeMustBePositive(int chunkSize) {
            // The chunk size is a property of the COBOL loop being translated - chunk orientation is
            // correct for only two of the nine jobs - so this class holds no opinion about its value
            // beyond it being a real commit interval.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> resourcelessConfig().chunkStep("STEP15", chunkSize))
                    .withMessageContaining("chunk size must be positive");
        }

        @Test
        @DisplayName("a chunk size of one is accepted, which is the smallest real commit interval")
        void aChunkSizeOfOneIsAccepted() {
            assertThatNoException().isThrownBy(() -> resourcelessConfig()
                    .<String, String>chunkStep("STEP15", 1)
                    .reader(EXHAUSTED_READER)
                    .writer(DISCARDING_WRITER)
                    .build());
        }

        @Test
        @DisplayName("the repository and the transaction manager are resolved when a seam is used, "
                + "not when this class is constructed")
        void collaboratorsAreResolvedLazily() {
            // This is what breaks the circular reference described on BatchConfig: Boot's batch
            // configuration needs the transaction manager to build the job repository, and the
            // transaction manager is a method on this very class. Construction must therefore
            // resolve neither.
            DefaultListableBeanFactory nothingRegistered = new DefaultListableBeanFactory();

            assertThatNoException().isThrownBy(() -> configOver(nothingRegistered));

            BatchConfig config = configOver(nothingRegistered);
            assertThatExceptionOfType(NoSuchBeanDefinitionException.class)
                    .isThrownBy(() -> config.job("accountBalanceJob"));
            assertThatExceptionOfType(NoSuchBeanDefinitionException.class)
                    .isThrownBy(() -> config.step("STEP05"));
        }

        @Test
        @DisplayName("a step built through a seam carries the return-code contract without opting "
                + "in - gate G35, by construction")
        void aStepBuiltThroughASeamCarriesTheReturnCodeContract() throws JobInterruptedException {
            // One step, executed directly through the framework's own Step API. No job is launched,
            // no launcher is involved and no database is touched: the framework's resourceless
            // repository and transaction manager are enough to prove that the listener the seam
            // attaches is really attached, which is the difference between the contract holding by
            // construction and holding only when an author remembers to opt in.
            ResourcelessJobRepository repository = new ResourcelessJobRepository();
            BatchConfig config = configOver(repository, new ResourcelessTransactionManager());
            Tasklet abends = (contribution, chunkContext) -> {
                throw AbendException.standard("CBSTM03A", AbendException.RETURN_CODE_IO_ERROR,
                        "ERROR OPENING STMTFILE");
            };
            Step step = config.taskletStep("STEP040", abends).build();

            JobExecution jobExecution = repository.createJobExecution("statementGenerationJobA",
                    new JobParameters());
            StepExecution stepExecution = jobExecution.createStepExecution("STEP040");
            step.execute(stepExecution);

            assertThat(stepExecution.getExitStatus().getExitCode()).isEqualTo("12");
            assertThat(stepExecution.getExitStatus().getExitDescription())
                    .contains(AbendException.ABEND_DISPLAY_TEXT)
                    .contains("CBSTM03A");
            assertThat(stepExecution.getFailureExceptions())
                    .singleElement()
                    .isInstanceOf(AbendException.class);
            // And the code it wrote is exactly what the COND gate reads back.
            assertThat(BatchConfig.returnCodeOf(stepExecution.getExitStatus())).isEqualTo(12);
            assertThat(BatchConfig.allPrecedingStepsReturnedZero(jobExecution)).isFalse();
        }
    }

    /**
     * {@link StopSignal} and {@link StopRequestedException} - the between-record cancellation probe.
     *
     * <p>Two things have to hold, and they are asserted separately because they fail separately: the
     * probe must recognise a stop on the same terms the framework's own interruption policy does, and an
     * abandoned pass must be reported by the framework as <em>stopped</em> rather than failed. The second
     * is asserted by running a real step, because it is a property of how {@code AbstractStep} reads the
     * exception rather than of anything this module can assert about itself.
     */
    @Nested
    @DisplayName("StopSignal - the between-record cancellation probe (N-02)")
    class TheStopSignal {

        /** The step name every probe here reports under. */
        private static final String STEP_NAME = "STEP040";

        /** @return a step execution not asked to stop */
        private StepExecution stepExecution() {
            return new StepExecution(STEP_NAME, new JobExecution(50L));
        }

        @Test
        @DisplayName("RUNNING never stops anything, however many times it is asked")
        void theRunningSignalNeverStops() {
            assertThatNoException().isThrownBy(() -> {
                for (int consultation = 0; consultation < 1_000; consultation++) {
                    StopSignal.RUNNING.checkStopRequested();
                }
            });
        }

        @Test
        @DisplayName("a step that has not been asked to stop is not stopped")
        void anUnstoppedStepIsNotStopped() {
            StopSignal signal = StopSignal.of(stepExecution());

            assertThatNoException().isThrownBy(signal::checkStopRequested);
        }

        @Test
        @DisplayName("terminateOnly - the flag JobOperator.stop() sets - stops the pass, naming the step "
                + "and stating that no write is retried")
        void terminateOnlyStopsThePass() {
            StepExecution stepExecution = stepExecution();
            stepExecution.setTerminateOnly();
            StopSignal signal = StopSignal.of(stepExecution);

            assertThatExceptionOfType(StopRequestedException.class)
                    .isThrownBy(signal::checkStopRequested)
                    .withMessageContaining(STEP_NAME)
                    .withMessageContaining("between records")
                    .withMessageContaining("NO write is retried")
                    .withMessageContaining("not restartable")
                    .withCauseInstanceOf(JobInterruptedException.class);
        }

        @Test
        @DisplayName("a thread interruption stops the pass too, because that is the framework's other "
                + "condition - and the flag is left where the framework can still see it")
        void aThreadInterruptionStopsThePass() {
            StopSignal signal = StopSignal.of(stepExecution());
            Thread.currentThread().interrupt();
            try {
                assertThatExceptionOfType(StopRequestedException.class)
                        .isThrownBy(signal::checkStopRequested)
                        .withCauseInstanceOf(JobInterruptedException.class);

                // Not cleared by the probe. The framework's own policy tests the same flag at the step's
                // repeat boundary, and a probe that consumed it would hide the interruption from it.
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                // Cleared here so the flag does not leak into whatever test runs next on this thread.
                assertThat(Thread.interrupted()).isTrue();
            }
        }

        @Test
        @DisplayName("the chunk context form reads through to the step execution the framework supplied")
        void theChunkContextFormReadsThroughToTheStepExecution() {
            StepExecution stepExecution = stepExecution();
            ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));
            StopSignal signal = StopSignal.of(chunkContext);

            assertThatNoException().isThrownBy(signal::checkStopRequested);

            // The same probe, over the same execution: the stop is observed when it is requested, not
            // when the probe was created, which is what makes it usable inside a running loop.
            stepExecution.setTerminateOnly();
            assertThatExceptionOfType(StopRequestedException.class)
                    .isThrownBy(signal::checkStopRequested);
        }

        @Test
        @DisplayName("neither factory accepts null, because a probe that could not observe anything "
                + "would silently never stop")
        void neitherFactoryAcceptsNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> StopSignal.of((ChunkContext) null))
                    .withMessageContaining("chunk context");
            assertThatNullPointerException()
                    .isThrownBy(() -> StopSignal.of((StepExecution) null))
                    .withMessageContaining("step execution");
        }

        @Test
        @DisplayName("a real step whose tasklet reports a stop MID-PASS ends STOPPED, not FAILED - and "
                + "the COND gate reads it as blocking")
        void aRealStepEndsStopped() throws JobInterruptedException {
            // The load-bearing assertion of the whole design. StopRequestedException is unchecked, so it
            // reaches AbstractStep as an ordinary failure; what makes the framework read it as a stop is
            // its CAUSE being the framework's own JobInterruptedException. That is a property of the
            // framework rather than of this module, so it is proved by running a real step through the
            // real Step API - no launcher, no database, no job.
            //
            // The stop is requested from INSIDE the tasklet, on purpose. A stop already pending when the
            // step begins is caught by TaskletStep's own interruption policy at the repeat boundary,
            // before the tasklet is ever invoked - which is precisely the case that was already bounded
            // and is not what N-02 is about. Flipping the flag while the pass is running is the case
            // this probe exists for, and it is the only way to reach it.
            ResourcelessJobRepository repository = new ResourcelessJobRepository();
            BatchConfig config = configOver(repository, new ResourcelessTransactionManager());
            Tasklet stops = (contribution, chunkContext) -> {
                StopSignal signal = StopSignal.of(chunkContext);
                signal.checkStopRequested();
                chunkContext.getStepContext().getStepExecution().setTerminateOnly();
                signal.checkStopRequested();
                return RepeatStatus.FINISHED;
            };
            Step step = config.taskletStep(STEP_NAME, stops).build();

            JobExecution jobExecution = repository.createJobExecution("statementGenerationJobA",
                    new JobParameters());
            StepExecution stepExecution = jobExecution.createStepExecution(STEP_NAME);
            step.execute(stepExecution);

            assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.STOPPED);
            assertThat(stepExecution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.STOPPED.getExitCode());
            assertThat(stepExecution.getFailureExceptions())
                    .singleElement()
                    .isInstanceOf(StopRequestedException.class);

            // STOPPED carries no numeric return code, so the COND gate treats it as blocking: a stopped
            // step can never be mistaken for one that returned zero.
            assertThat(BatchConfig.returnCodeOf(stepExecution.getExitStatus()))
                    .isEqualTo(BatchConfig.NO_JCL_RETURN_CODE);
            assertThat(BatchConfig.allPrecedingStepsReturnedZero(jobExecution)).isFalse();
        }

        @Test
        @DisplayName("a stop already pending when the step begins is caught by the framework itself, "
                + "before the tasklet is invoked - so the two mechanisms compose")
        void aStopPendingAtEntryIsCaughtByTheFramework() throws JobInterruptedException {
            // Stated as a test rather than left implicit, because it is the reason the probe is scoped to
            // record loops and to nothing else: the step boundary is already covered.
            ResourcelessJobRepository repository = new ResourcelessJobRepository();
            BatchConfig config = configOver(repository, new ResourcelessTransactionManager());
            boolean[] invoked = { false };
            Tasklet neverReached = (contribution, chunkContext) -> {
                invoked[0] = true;
                return RepeatStatus.FINISHED;
            };
            Step step = config.taskletStep(STEP_NAME, neverReached).build();

            JobExecution jobExecution = repository.createJobExecution("statementGenerationJobA",
                    new JobParameters());
            StepExecution stepExecution = jobExecution.createStepExecution(STEP_NAME);
            stepExecution.setTerminateOnly();
            step.execute(stepExecution);

            assertThat(invoked[0]).isFalse();
            assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.STOPPED);
            assertThat(stepExecution.getFailureExceptions())
                    .singleElement()
                    .isInstanceOf(JobInterruptedException.class);
        }

        @Test
        @DisplayName("a step whose tasklet is not stopped still completes, so the probe costs a "
                + "well-behaved step nothing")
        void anUnstoppedStepStillCompletes() throws JobInterruptedException {
            ResourcelessJobRepository repository = new ResourcelessJobRepository();
            BatchConfig config = configOver(repository, new ResourcelessTransactionManager());
            Tasklet probes = (contribution, chunkContext) -> {
                StopSignal signal = StopSignal.of(chunkContext);
                for (int record = 0; record < 100; record++) {
                    signal.checkStopRequested();
                }
                return RepeatStatus.FINISHED;
            };
            Step step = config.taskletStep(STEP_NAME, probes).build();

            JobExecution jobExecution = repository.createJobExecution("statementGenerationJobA",
                    new JobParameters());
            StepExecution stepExecution = jobExecution.createStepExecution(STEP_NAME);
            step.execute(stepExecution);

            assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(BatchConfig.returnCodeOf(stepExecution.getExitStatus()))
                    .isEqualTo(BatchConfig.JCL_RETURN_CODE_ZERO);
        }
    }

    /**
     * What {@link BatchConfig} refuses to declare, which is as much of its contract as what it does.
     *
     * <p>Two things go wrong at once if any of these ever appears here. Spring Boot's batch
     * auto-configuration already declares the job repository, launcher, explorer, operator and
     * registry - its nested configuration extends the framework base class that supplies them - so a
     * second definition of the same name is refused outright with bean-definition overriding
     * disabled, and a second definition under another name makes by-type injection ambiguous in
     * every job class at once. Worse, switching that auto-configuration off - by the activating
     * annotation or by extending the framework base class here - takes Spring Boot's own batch
     * database initializer with it, and this module would then be pushed into authoring the
     * data-definition statements it is expressly forbidden to author.
     *
     * <p>These are reflective assertions on purpose, and the choice is worth defending. Loading this
     * class alone in a context would be the obvious alternative, but it cannot be done cleanly - its
     * constructor takes the DD-name catalogue that the sibling configuration registers, so a
     * single-class slice would fail for a reason unrelated to what is being asserted. Reflection is
     * also the <em>stronger</em> instrument here: stating the whole set of declared beans is a total
     * assertion, whereas asking a context whether four particular types are absent leaves every
     * other unwanted bean unexamined. It holds whether or not a context starts, it names the
     * offending bean if one appears, and it needs no database.
     */
    @Nested
    @DisplayName("The contract it refuses to declare - the absences are load-bearing")
    class TheContractItRefusesToDeclare {

        @Test
        @DisplayName("eight bean methods, and these exactly - so nothing else can have crept in")
        void exactlyEightBeansAndTheseExactly() {
            // Read from the class rather than from a list kept by hand, so a bean added later shows
            // up here whether or not anyone remembered to record it. Stating the whole set is what
            // makes this a total assertion: no initialisation script, no script populator, no
            // database initializer, no table-prefix arrangement, no task executor and no job or step
            // bean can be present without failing this.
            //
            // Two of the eight carry the lifecycle contract that a JCL submission implies. The
            // incrementer supplies a per-launch run identity, without which the eight parameterless
            // jobs would each have exactly one instance for all time; and the launcher is the only
            // thing that submits a job at all, which is why it is conditional on being asked for.
            // parmDateValidator() is deliberately NOT a bean - a validator is attached to one job
            // builder, and a shared instance would invite a second job to pick up INTCALC's contract.
            assertThat(declaredBeanTypes()).containsExactlyInAnyOrder(
                    PlatformTransactionManager.class,
                    JobExecutionDecider.class,
                    StepExecutionListener.class,
                    JobExecutionListener.class,
                    JobParametersIncrementer.class,
                    ExitCodeExceptionMapper.class,
                    BatchConfig.JclJobLauncher.class,
                    JobContractValidator.class);
        }

        @Test
        @DisplayName("no job repository, launcher, explorer, operator or registry is declared here")
        void noBatchInfrastructureBeanIsDeclaredHere() {
            List<Class<?>> beanTypes = declaredBeanTypes();

            for (Class<?> ownedByBoot : List.of(JobRepository.class, JobLauncher.class,
                    JobExplorer.class, JobOperator.class, JobRegistry.class)) {
                assertThat(beanTypes)
                        .as("BatchConfig must receive %s from Spring Boot, never manufacture one",
                                ownedByBoot.getSimpleName())
                        .noneMatch(ownedByBoot::isAssignableFrom);
            }
        }

        @Test
        @DisplayName("no job and no step bean is declared here either - those belong to the domain "
                + "packages")
        void noJobOrStepBeanIsDeclaredHere() {
            List<Class<?>> beanTypes = declaredBeanTypes();

            assertThat(beanTypes).noneMatch(Job.class::isAssignableFrom);
            assertThat(beanTypes).noneMatch(Step.class::isAssignableFrom);
        }

        @Test
        @DisplayName("the batch auto-configuration is never switched off - neither by annotation nor "
                + "by base class")
        void theBatchAutoConfigurationIsNeverSwitchedOff() {
            // Named here, and only here, inside a negative assertion: this is the one place where
            // writing the two identifiers down is the point rather than the violation.
            assertThat(BatchConfig.class.getAnnotation(EnableBatchProcessing.class)).isNull();
            assertThat(DefaultBatchConfiguration.class.isAssignableFrom(BatchConfig.class)).isFalse();
            assertThat(BatchConfig.class.getSuperclass()).isEqualTo(Object.class);
            assertThat(BatchConfig.class.getInterfaces()).isEmpty();
        }

        @Test
        @DisplayName("the removed Batch 4 builder factories really are absent from this classpath")
        void theRemovedBatch4BuilderFactoriesAreAbsent() {
            // Spring Batch 5.2.6, because the plan pins Spring Boot 3.x (practice B2). Asserted
            // rather than assumed, so an upgrade that reintroduced either type would be noticed
            // here, with an explanation, instead of somewhere obscure. The correct form is
            // new JobBuilder(name, jobRepository) / new StepBuilder(name, jobRepository), which
            // TransactionManagerAndBuilderSeams builds with.
            for (String batch4Only : List.of(
                    "org.springframework.batch.core.configuration.annotation.JobBuilderFactory",
                    "org.springframework.batch.core.configuration.annotation.StepBuilderFactory")) {
                assertThatExceptionOfType(ClassNotFoundException.class)
                        .as("%s was removed in Spring Batch 5 and must not be reachable", batch4Only)
                        .isThrownBy(() -> Class.forName(batch4Only));
            }
        }

        @Test
        @DisplayName("no persistence annotation can exist anywhere in this module, because the "
                + "annotations themselves are not on the classpath")
        void noPersistenceAnnotationIsEvenReachable() {
            // Gate G44 forbids entity mappings, generated table definitions and version columns.
            // The strongest available statement of that is structural: the mandated dependency set
            // excludes JPA entirely, so no such annotation is resolvable in the first place.
            for (String excluded : List.of("jakarta.persistence.Entity", "jakarta.persistence.Table",
                    "jakarta.persistence.Version", "org.flywaydb.core.Flyway",
                    "liquibase.Liquibase")) {
                assertThatExceptionOfType(ClassNotFoundException.class)
                        .as("%s is outside the closed dependency set", excluded)
                        .isThrownBy(() -> Class.forName(excluded));
            }
        }

        @Test
        @DisplayName("what it does enable: configuration properties for the job catalogue, and "
                + "nothing more")
        void whatItDoesEnable() {
            assertThat(BatchConfig.class.getAnnotation(Configuration.class)).isNotNull();
            assertThat(BatchConfig.class.getAnnotation(EnableConfigurationProperties.class).value())
                    .containsExactly(JobContracts.class);
        }
    }

    /**
     * Nothing here starts a job, which is what preserves the program no JCL invokes.
     *
     * <p>{@code CBTRN01C} is invoked by no JCL anywhere in {@code app/jcl} or {@code app/proc}, yet
     * it is one of the in-scope programs, so it migrates as a fully runnable job with no trigger.
     * Wiring it into a schedule would invent behaviour the COBOL estate does not have; deleting it
     * would discard behaviour the estate does have. {@code spring.batch.job.enabled: false} is the
     * mechanism, and this class is where it would silently be undone.
     */
    @Nested
    @DisplayName("Nothing runs at startup - gate G13, the untriggered job")
    class NothingRunsAtStartup {

        @Test
        @DisplayName("both shipped profiles state that no job runs on context refresh")
        void bothShippedProfilesDisableAutomaticLaunching() {
            documentBackedRunner().run(context -> assertThat(context.getEnvironment()
                    .getProperty("spring.batch.job.enabled")).isEqualTo("false"));
        }

        @Test
        @DisplayName("with that property false, no start-up runner exists to launch anything")
        void noStartUpRunnerExistsWithThePropertyFalse() {
            runnerWithBatchAutoConfiguration().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(JobLauncherApplicationRunner.class);
            });
        }

        @Test
        @DisplayName("and the property is the mechanism: flipping it produces the runner, which is "
                + "why it must stay false")
        void thePropertyIsTheMechanism() {
            // The counter-assertion matters. Without it, "no runner" could be true for some
            // unrelated reason and the gate would be resting on a coincidence.
            runnerWithBatchAutoConfiguration()
                    .withPropertyValues("spring.batch.job.enabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(JobLauncherApplicationRunner.class);
                    });
        }

        @Test
        @DisplayName("this class is no runner, schedules nothing, and its one runner bean exists only "
                + "when a job is explicitly asked for")
        void thisClassIsNoRunnerAndSchedulesNothing() {
            assertThat(ApplicationRunner.class.isAssignableFrom(BatchConfig.class)).isFalse();
            assertThat(CommandLineRunner.class.isAssignableFrom(BatchConfig.class)).isFalse();
            assertThat(BatchConfig.class.getAnnotation(EnableScheduling.class)).isNull();
            assertThat(Arrays.stream(BatchConfig.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Scheduled.class))
                    .toList()).isEmpty();

            // One bean method does return a runner - the launcher that turns a process invocation into
            // a JCL submission and its RETURN-CODE into the process exit code. What keeps "nothing runs
            // at startup" true is that it is @ConditionalOnProperty on the job name, so a context that
            // was not asked to submit a job does not have it at all. That is asserted rather than
            // assumed, on the method itself, and the property is the one the launcher publishes.
            List<Method> runnerBeans = Arrays.stream(BatchConfig.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Bean.class))
                    .filter(method -> ApplicationRunner.class.isAssignableFrom(method.getReturnType())
                            || CommandLineRunner.class.isAssignableFrom(method.getReturnType()))
                    .toList();

            assertThat(runnerBeans).hasSize(1);
            ConditionalOnProperty condition =
                    runnerBeans.get(0).getAnnotation(ConditionalOnProperty.class);
            assertThat(condition)
                    .as("a runner that existed unconditionally would launch a job on every start-up")
                    .isNotNull();
            assertThat(condition.name())
                    .containsExactly(BatchConfig.JclJobLauncher.JOB_NAME_PROPERTY);
        }

        @Test
        @DisplayName("the job repository is Spring Boot's, not this class's")
        void theJobRepositoryIsSpringBoots() {
            runnerWithBatchAutoConfiguration().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(JobRepository.class);

                // Provenance, not merely presence: the definition's factory is Spring Boot's own
                // batch configuration, so this module receives the repository rather than
                // manufacturing one - which is exactly what keeps the framework's jar-supplied
                // metadata schema initializer in play.
                String[] repositoryBeans = context.getBeanNamesForType(JobRepository.class);
                String[] thisConfiguration = context.getBeanNamesForType(BatchConfig.class);
                assertThat(repositoryBeans).hasSize(1);
                assertThat(thisConfiguration).hasSize(1);
                String declaredBy = context.getBeanFactory()
                        .getBeanDefinition(repositoryBeans[0]).getFactoryBeanName();

                assertThat(declaredBy)
                        .as("Spring Boot's batch auto-configuration declares the repository")
                        .isNotNull()
                        .startsWith(BatchAutoConfiguration.class.getName());
                // Compared by bean identity rather than by text, deliberately: Spring Boot's own
                // nested class is BatchAutoConfiguration$SpringBootBatchConfiguration, whose name
                // contains this class's simple name as a substring, so a textual "does not contain"
                // check here reports a violation that is not one.
                assertThat(declaredBy).isNotEqualTo(thisConfiguration[0]);
            });
        }
    }

    /**
     * The job-parameter contracts, taken from the JCL: one parameter in the whole estate, and it is
     * character data.
     */
    /**
     * The launcher that turns one process invocation into one JCL submission, and its
     * {@code RETURN-CODE} into the process exit code.
     *
     * <p>Driven by direct call against a stub launcher, with no application context and no database:
     * what has to be proved is the mapping from a job's exit status to the process exit code, and that
     * is a pure function of the execution the launcher was handed. Spring Boot's own batch runner
     * reports {@code BatchStatus.ordinal()} - {@code 5} for a failure - and the whole point of this
     * launcher is that {@code 0}, {@code 4}, {@code 8} and {@code 12} survive instead (gate G35).
     */
    @Nested
    @DisplayName("The JCL job launcher - a submission in, a RETURN-CODE out")
    class TheJclJobLauncher {

        /** The bean name of a job whose contract exists, and the key that contract is declared under. */
        private static final String JOB_BEAN_NAME = "accountBalanceJob";

        /**
         * A launcher over a job whose run reports the given exit status.
         *
         * @param exitStatus what the stubbed launcher's execution carries
         * @return the launcher, ready to run
         */
        private BatchConfig.JclJobLauncher launcherReporting(ExitStatus exitStatus) {
            return launcherReporting(exitStatus, JOB_BEAN_NAME);
        }

        /**
         * A launcher over a named job whose run reports the given exit status.
         *
         * @param exitStatus what the stubbed launcher's execution carries
         * @param jobName    the job bean's name
         * @return the launcher, ready to run
         */
        private BatchConfig.JclJobLauncher launcherReporting(ExitStatus exitStatus, String jobName) {
            BatchConfig config = configWithContracts();
            Job job = mock(Job.class);
            when(job.getName()).thenReturn(jobName);
            JobLauncher launcher = (submitted, parameters) -> {
                JobExecution execution = new JobExecution(1L, parameters);
                execution.setExitStatus(exitStatus);
                return execution;
            };
            return new BatchConfig.JclJobLauncher(providerOf(Job.class, job),
                    providerOf(JobLauncher.class, launcher), config, jobName);
        }

        /** A {@link BatchConfig} whose contract catalogue is the shipped one. */
        private BatchConfig configWithContracts() {
            DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
            factory.registerSingleton("jobRepository", new ResourcelessJobRepository());
            factory.registerSingleton("transactionManager", new ResourcelessTransactionManager());
            JobContracts contracts = new JobContracts();
            contracts.put("account-balance-job", new JobContract("CBACT01C", List.of(),
                    List.of(new StepContract("STEP05", "CBACT01C", false)), null, Map.of()));
            return new BatchConfig(factory.getBeanProvider(JobRepository.class),
                    factory.getBeanProvider(PlatformTransactionManager.class), contracts,
                    new DatasetBindings());
        }

        /**
         * A real {@link ObjectProvider} over one singleton, so the launcher's lazy resolution is
         * exercised rather than bypassed.
         *
         * @param type      the singleton's type
         * @param singleton the singleton
         * @param <T>       the type
         * @return the provider
         */
        private <T> ObjectProvider<T> providerOf(Class<T> type, T singleton) {
            DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
            factory.registerSingleton(type.getSimpleName(), singleton);
            return factory.getBeanProvider(type);
        }

        @ParameterizedTest(name = "an exit status of {0} leaves the process exit code 0")
        @ValueSource(strings = { "COMPLETED", "NOOP", "0" })
        @DisplayName("a job that returned zero completes quietly - nothing is thrown and the exit code "
                + "stays zero")
        void aZeroReturnCodeThrowsNothing(final String exitCode) throws Exception {
            BatchConfig.JclJobLauncher launcher = launcherReporting(new ExitStatus(exitCode));

            launcher.run(new DefaultApplicationArguments());

            assertThat(launcher.getExitCode()).isZero();
            assertThat(launcher.jobName()).isEqualTo(JOB_BEAN_NAME);
        }

        @ParameterizedTest(name = "RETURN-CODE {0} reaches the process as {0}")
        @ValueSource(strings = { "4", "8", "12" })
        @DisplayName("a non-zero RETURN-CODE is raised as an ExitCodeGenerator, so the shell sees the "
                + "number the COBOL set")
        void aNonZeroReturnCodeBecomesTheProcessExitCode(final String exitCode) {
            BatchConfig.JclJobLauncher launcher = launcherReporting(new ExitStatus(exitCode));

            // Spring Boot registers the code carried by an ExitCodeGenerator exception while it
            // propagates out of SpringApplication.run, which is the same seam abendExitCodeMapper uses.
            // BatchStatus.ordinal() - Boot's own batch runner's answer - would report 5 for a failure,
            // and 5 is not a JCL return code.
            assertThatExceptionOfType(BatchConfig.JclReturnCodeException.class)
                    .isThrownBy(() -> launcher.run(new DefaultApplicationArguments()))
                    .withMessageContaining("RETURN-CODE " + exitCode)
                    .satisfies(raised -> assertThat(raised.getExitCode())
                            .isEqualTo(Integer.parseInt(exitCode)));
            assertThat(launcher.getExitCode()).isEqualTo(Integer.parseInt(exitCode));
        }

        @Test
        @DisplayName("a framework exit status that carries no return code is still a failure, and is "
                + "reported as one")
        void aFrameworkFailureIsStillAFailure() {
            BatchConfig.JclJobLauncher launcher = launcherReporting(ExitStatus.FAILED);

            assertThatExceptionOfType(BatchConfig.JclReturnCodeException.class)
                    .isThrownBy(() -> launcher.run(new DefaultApplicationArguments()))
                    .withMessageContaining("exit status 'FAILED'");
        }

        @Test
        @DisplayName("the submitted parameters are the job's declared contract plus the run identity, "
                + "and nothing else")
        void theSubmittedParametersAreTheContractPlusTheRunIdentity() throws Exception {
            BatchConfig config = configWithContracts();
            Job job = mock(Job.class);
            when(job.getName()).thenReturn(JOB_BEAN_NAME);
            List<JobParameters> submitted = new ArrayList<>();
            JobLauncher recording = (requested, parameters) -> {
                submitted.add(parameters);
                JobExecution execution = new JobExecution(1L, parameters);
                execution.setExitStatus(ExitStatus.COMPLETED);
                return execution;
            };

            new BatchConfig.JclJobLauncher(providerOf(Job.class, job),
                    providerOf(JobLauncher.class, recording), config, JOB_BEAN_NAME)
                    .run(new DefaultApplicationArguments("--ignored=value"));

            // READACCT.jcl passes no PARM, so the only parameter is the run identity. Process arguments
            // are deliberately not read: a job's parameters are its contract in carddemo.jobs, never
            // free text from a command line.
            assertThat(submitted).hasSize(1);
            assertThat(submitted.get(0).getParameters().keySet())
                    .containsExactly(BatchConfig.RUN_IDENTITY_PARAMETER);
        }

        @Test
        @DisplayName("a job with no declared contract is refused, because a job's parameters ARE its "
                + "contract")
        void aJobWithNoContractIsRefused() {
            BatchConfig.JclJobLauncher launcher =
                    launcherReporting(ExitStatus.COMPLETED, "someUndeclaredJob");

            assertThatIllegalStateException()
                    .isThrownBy(() -> launcher.run(new DefaultApplicationArguments()))
                    .withMessageContaining("no entry under carddemo.jobs declares it");
        }

        @Test
        @DisplayName("the launcher needs a job name, a Job provider, a launcher provider and the "
                + "configuration")
        void theLauncherNeedsAllFourCollaborators() {
            BatchConfig config = configWithContracts();
            ObjectProvider<Job> jobs = providerOf(Job.class, mock(Job.class));
            ObjectProvider<JobLauncher> launchers =
                    providerOf(JobLauncher.class, (job, parameters) -> new JobExecution(1L));

            assertThatNullPointerException().isThrownBy(() ->
                    new BatchConfig.JclJobLauncher(null, launchers, config, JOB_BEAN_NAME));
            assertThatNullPointerException().isThrownBy(() ->
                    new BatchConfig.JclJobLauncher(jobs, null, config, JOB_BEAN_NAME));
            assertThatNullPointerException().isThrownBy(() ->
                    new BatchConfig.JclJobLauncher(jobs, launchers, null, JOB_BEAN_NAME));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new BatchConfig.JclJobLauncher(jobs, launchers, config, " "));
        }

        @Test
        @DisplayName("the exit code is zero until a job has actually run")
        void theExitCodeIsZeroBeforeAnyRun() {
            assertThat(launcherReporting(ExitStatus.COMPLETED).getExitCode()).isZero();
        }

        @ParameterizedTest(name = "{0} publishes the job {1}")
        @CsvSource({
            "account-balance-job,accountBalanceJob",
            "transaction-report-job,transactionReportJob",
            "statement-generation-job-a,statementGenerationJobA",
            "account-interest-calc-job,accountInterestCalcJob",
            "customerfilereaderjob,customerfilereaderjob"
        })
        @DisplayName("a configuration key and its job bean name are two spellings of one thing")
        void aConfigurationKeyDerivesItsJobBeanName(final String jobKey, final String beanName) {
            assertThat(BatchConfig.jobBeanNameOf(jobKey)).isEqualTo(beanName);
        }

        @Test
        @DisplayName("deriving a job bean name needs a key")
        void derivingAJobBeanNameNeedsAKey() {
            assertThatNullPointerException()
                    .isThrownBy(() -> BatchConfig.jobBeanNameOf(null))
                    .withMessageContaining("job key is required");
        }
    }

    @Nested
    @DisplayName("Job-parameter contracts - one PARM in the estate, and it is not a date")
    class JobParameterContracts {

        @Test
        @DisplayName("parmDate is a String parameter whose value round-trips unaltered")
        void parmDateRoundTripsUnaltered() {
            documentBackedRunner().run(context -> {
                JobContract interest = context.getBean(JobContracts.class).contract(INTEREST_JOB);

                // app/jcl/INTCALC.jcl:L22 - //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'. The value
                // is character data: CBACT04C concatenates it verbatim into the transaction
                // identifiers it generates (STRING PARM-DATE, WS-TRANID-SUFFIX at
                // app/cbl/CBACT04C.cbl:L476-L477), so parsing it as a date, reformatting it or
                // revalidating it would corrupt every identifier the job writes.
                assertThat(interest.parameters()).hasSize(1);
                JobParameterContract declared = interest.parameters().get(0);
                assertThat(declared.name()).isEqualTo(BatchConfig.PARM_DATE_PARAMETER)
                        .isEqualTo("parmDate");
                assertThat(declared.type()).isEqualTo("string");
                assertThat(declared.value()).isEqualTo(JCL_PARM_DATE);

                JobParameters launched = interest.jobParameters();
                assertThat(launched.getString(BatchConfig.PARM_DATE_PARAMETER))
                        .isEqualTo(JCL_PARM_DATE)
                        .hasSize(BatchConfig.PARM_DATE_WIDTH);
                assertThat(launched.getParameters()).containsOnlyKeys(
                        BatchConfig.PARM_DATE_PARAMETER);
                assertThat(launched.getParameters().get(BatchConfig.PARM_DATE_PARAMETER).getType())
                        .isEqualTo(String.class);
                assertThat(launched.getParameters().get(BatchConfig.PARM_DATE_PARAMETER).getValue())
                        .isInstanceOf(String.class)
                        .isEqualTo(JCL_PARM_DATE);
            });
        }

        @Test
        @DisplayName("the shipped value satisfies the width the COBOL field declares")
        void theShippedValueSatisfiesItsOwnValidator() {
            documentBackedRunner().run(context -> {
                BatchConfig config = context.getBean(BatchConfig.class);
                JobParameters shipped = config.contract(INTEREST_JOB).jobParameters();

                // app/cbl/CBACT04C.cbl:178 declares PARM-DATE PIC X(10) and L476-L480 contributes
                // the whole declared width to a fixed PIC X(16) identifier, so a nine- or
                // eleven-character value would silently misplace the generated suffix.
                assertThatNoException()
                        .isThrownBy(() -> config.parmDateValidator().validate(shipped));
                assertThat(config.parmDateValidator())
                        .isNotSameAs(config.parmDateValidator());
            });
        }

        @Test
        @DisplayName("the other eight jobs declare no parameter at all, and none may be invented")
        void theOtherEightJobsDeclareNoParameter() {
            documentBackedRunner().run(context -> {
                JobContracts contracts = context.getBean(JobContracts.class);

                // app/jcl/POSTTRAN.jcl:L23 is a bare //STEP15 EXEC PGM=CBTRN02C, and the four
                // single-DD readers are the same. An empty list is the contract, not an omission.
                assertThat(contracts.entrySet().stream()
                        .filter(job -> !job.getValue().parameters().isEmpty())
                        .map(Map.Entry::getKey)
                        .toList())
                        .containsExactly(INTEREST_JOB);
                assertThat(contracts.contract(POSTTRAN_JOB).parameters()).isEmpty();
                assertThat(contracts.contract(POSTTRAN_JOB).jobParameters().isEmpty()).isTrue();
                for (String jobKey : READER_JOBS) {
                    assertThat(contracts.contract(jobKey).parameters())
                            .as("%s takes no PARM", jobKey).isEmpty();
                    assertThat(contracts.contract(jobKey).jobParameters().isEmpty())
                            .as("%s launches with no parameters", jobKey).isTrue();
                }
            });
        }

        @Test
        @DisplayName("DATEPARM is a dataset, so it is absent from every parameter contract")
        void dateParmIsADatasetAndNeverAParameter() {
            documentBackedRunner().run(context -> {
                JobContracts contracts = context.getBean(JobContracts.class);

                // app/jcl/TRANREPT.jcl:L73-L74 binds DATEPARM as a DD, so CBTRN03C's reporting date
                // range is read by the report-date reader bean. Turning it into a job parameter
                // would move a value out of the file the COBOL reads it from.
                assertThat(contracts.values().stream()
                        .flatMap(job -> job.parameters().stream())
                        .map(JobParameterContract::name)
                        .toList())
                        .containsExactly(BatchConfig.PARM_DATE_PARAMETER)
                        .noneSatisfy(name -> assertThat(name)
                                .containsIgnoringCase(DATE_RANGE_DD));
                assertThat(contracts.contract(REPORT_JOB).dateRangeSource())
                        .isEqualTo(DATE_RANGE_DD);
                assertThat(contracts.contract(REPORT_JOB).parameters()).isEmpty();
                assertThat(contracts.contract(INTEREST_JOB).dateRangeSource()).isNull();
            });
        }

        @Test
        @DisplayName("a job's view of a DD name resolves job-scoped first, global second")
        void aJobsViewOfADdNameResolvesJobScopedFirst() {
            documentBackedRunner().run(context -> {
                BatchConfig config = context.getBean(BatchConfig.class);

                // In the interest calculator the DD name TRANSACT is the GENERATED-TRANSACTION
                // OUTPUT (app/jcl/INTCALC.jcl:L37-L41), not the transaction master: the job-scoped
                // entry aliases it to the generation the JCL calls SYSTRAN, at RECFM=F LRECL=350.
                // Only widths and copybooks are asserted - no dataset name appears in Java (G46).
                assertThat(config.datasetBinding(INTEREST_JOB, "TRANSACT").recordLength())
                        .isEqualTo(350);
                assertThat(config.datasetBinding(INTEREST_JOB, "TRANSACT").copybook())
                        .isEqualTo("CVTRA05Y");
                // A DD name the job does not override falls through to the global catalogue.
                assertThat(config.datasetBinding(POSTTRAN_JOB, "ACCTFILE").recordLength())
                        .isEqualTo(300);
                assertThat(config.datasetBinding(POSTTRAN_JOB, "ACCTFILE").copybook())
                        .isEqualTo("CVACT01Y");
                assertThat(config.jobContracts())
                        .hasSize(JobContracts.REQUIRED_JOBS.size())
                        .isSameAs(context.getBean(JobContracts.class));
            });
        }

        @Test
        @DisplayName("the shipped catalogue satisfies every batch DD / repository DD equivalence")
        void theShippedCatalogueSatisfiesEveryDdEquivalence() {
            // The batch programs address datasets under their own JCL DD names while the repositories are
            // bound to the CICS file names, and every one of those keys carries an independent
            // environment override. requireSameDataset is the gate each job runs at construction; this
            // asserts the shipped defaults pass it, so the context starts on the configuration as
            // delivered. It also fixes the mapping in a test, which is the only place it is stated
            // outside the jobs themselves.
            documentBackedRunner().run(context -> {
                BatchConfig config = context.getBean(BatchConfig.class);

                assertThat(config.requireSameDataset(READCARD_JOB, "CARDFILE", "CARDDAT")).isNotBlank();
                assertThat(config.requireSameDataset(READXREF_JOB, "XREFFILE", "CCXREF")).isNotBlank();
                assertThat(config.requireSameDataset(INTEREST_JOB, "TCATBALF", "TCATBALF")).isNotBlank();
                assertThat(config.requireSameDataset(INTEREST_JOB, "ACCTFILE", "ACCTDAT")).isNotBlank();
                assertThat(config.requireSameDataset(INTEREST_JOB, "XREFFILE", "CCXREF")).isNotBlank();
                assertThat(config.requireSameDataset(INTEREST_JOB, "XREFFIL1", "CXACAIX")).isNotBlank();
                // The job-scoped alias: TRANSACT is the generated-transaction output here, not the master.
                assertThat(config.requireSameDataset(INTEREST_JOB, "TRANSACT", "SYSTRAN")).isNotBlank();
                assertThat(config.requireSameDataset(REPORT_JOB, "CARDXREF", "CCXREF")).isNotBlank();
                assertThat(config.requireSameDataset(REPORT_JOB, "TRANTYPE", "TRANTYPE")).isNotBlank();
                assertThat(config.requireSameDataset(REPORT_JOB, "TRANCATG", "TRANCATG")).isNotBlank();
            });
        }

        @Test
        @DisplayName("two keys naming different datasets are refused, and the message names both")
        void divergingKeysAreRefused() {
            documentBackedRunner().run(context -> {
                BatchConfig config = context.getBean(BatchConfig.class);

                // ACCTDAT and CARDDAT are two genuinely different datasets in the shipped catalogue, so
                // pairing them is the divergence this gate exists to catch.
                assertThatIllegalStateException()
                        .isThrownBy(() -> config.requireSameDataset(INTEREST_JOB, "ACCTFILE", "CARDDAT"))
                        .withMessageContaining("ACCTFILE")
                        .withMessageContaining("CARDDAT")
                        .withMessageContaining(INTEREST_JOB)
                        .withMessageContaining("Refusing to start");
            });
        }

        @Test
        @DisplayName("an undeclared key is refused by the same gate, naming the key to add")
        void anUndeclaredKeyIsRefused() {
            documentBackedRunner().run(context -> {
                BatchConfig config = context.getBean(BatchConfig.class);

                assertThatIllegalStateException()
                        .isThrownBy(() -> config.requireSameDataset(INTEREST_JOB, "ACCTFILE",
                                "NOSUCHDD"))
                        .withMessageContaining("NOSUCHDD");
            });
        }
    }
}
