package com.vsergeychik.carddemo.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.DefaultJobParametersValidator;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepInterruptionPolicy;
import org.springframework.batch.core.step.ThreadStepInterruptionPolicy;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.builder.TaskletStepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeExceptionMapper;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * The module's batch scaffolding: the one transaction manager, the builder seams the batch job classes are
 * assembled from, the JCL {@code COND=(0,NE)} step-gating policy, the shared listeners that carry a COBOL
 * {@code RETURN-CODE} out to the process exit code, and the per-job contracts read from configuration.
 *
 * <p>A job runs only when something deliberately launches it - which is exactly how a job ran on the
 * mainframe, when JCL submitted an {@code EXEC PGM=} step and not before.
 */
@Configuration
@EnableConfigurationProperties(BatchConfig.JobContracts.class)
public class BatchConfig {
    /**
     * The single job parameter this migration declares: {@code parmDate}, for the interest calculator
     * alone.
     */
    public static final String PARM_DATE_PARAMETER = "parmDate";

    /**
     * The identifying job parameter {@link #jclRunIdentityIncrementer()} adds so that each launch is a new
     * job instance: {@code run.id}, the name {@link RunIdIncrementer} uses.
     */
    public static final String RUN_IDENTITY_PARAMETER = "run.id";

    /**
     * The fixed width of {@link #PARM_DATE_PARAMETER}, in characters: 10.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:178} declares {@code PARM-DATE PIC X(10)} inside the
     * {@code EXTERNAL-PARMS} linkage item, and {@code app/jcl/INTCALC.jcl:22} supplies exactly ten
     * characters for it: {@code PARM='2022071800'}.
     */
    public static final int PARM_DATE_WIDTH = 10;

    private static final String PARM_DATE_WIDTH_RATIONALE =
            "app/cbl/CBACT04C.cbl:178 declares PARM-DATE PIC X(10), and L476-L480 does STRING "
                    + "PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID - so the whole "
                    + "declared width is contributed to a fixed PIC X(16) identifier, trailing "
                    + "spaces included. A shorter value shifts the generated suffix left in every "
                    + "identifier the job writes and a longer one pushes it off the end, and neither "
                    + "would fail at run time. app/jcl/INTCALC.jcl:22 supplies exactly 10 characters "
                    + "(PARM='2022071800'). The value is character data used verbatim and is never "
                    + "parsed as a date, so its width is the only thing there is to check.";

    public static final FlowExecutionStatus PROCEED = new FlowExecutionStatus("PROCEED");

    public static final FlowExecutionStatus SKIP = new FlowExecutionStatus("SKIP");

    public static final String COND_BYPASSED_EXIT_CODE = "COND BYPASSED";

    static final int JCL_RETURN_CODE_ZERO = 0;

    static final int NO_JCL_RETURN_CODE = -1;

    static final int NO_MAPPED_EXIT_CODE = 0;

    /**
     * The JCL return code reported for a step or a job that failed carrying no COBOL
     * {@code RETURN-CODE} of its own: {@code 12}, {@link AbendException#RETURN_CODE_IO_ERROR}.
     *
     * <p>Every executed JCL step ends with a numeric condition code - {@code IEF142I ... COND CODE 0012}
     * - and there is no such thing as a step that ended "FAILED". So a failure this module raises where the
     * COBOL has no counterpart, because the condition cannot arise in the legacy program at all, still has
     * to report a number: a dataset a utility step cannot address, or a subscript that does not address its
     * table. {@code 12} is the value this estate itself moves for a dataset it cannot use - the eight
     * standard abend paragraphs do {@code MOVE 12 TO APPL-RESULT} before {@code CALL 'CEE3ABD'} - so a
     * fatal condition reports the same code whether a COBOL program detected it or this translation did,
     * and the next step's {@code COND} test reads one vocabulary rather than two.
     *
     * <p>It is a fallback and never an override: a real abend's own return code always wins, which is what
     * keeps {@code CBSTM03A}'s {@code 8} distinct from the {@code 12} of the seven programs around it.
     *
     * <p>It is applied at both ends of a submission, because a code that is never read is no contract: the
     * step and job listeners put it on the exit status through
     * {@link #withJclReturnCode(ExitStatus, BatchStatus, List)}, so the {@code COND} gates that follow read
     * a number; and {@link #deliverableReturnCode(int)} applies it again where the launcher hands the code
     * to the operating system, so nothing that slipped past a listener can reach the shell as a value JCL
     * never produces.
     *
     * <p>The framework's own failure exit codes are words, not numbers: a step that failed on an exception
     * the module did not translate leaves {@code FAILED}, and a job asked to stop leaves {@code STOPPED}.
     * {@link #returnCodeOf(ExitStatus)} reports {@link #NO_JCL_RETURN_CODE} for those, which is the honest
     * answer to "what return code did this carry" - but it is not deliverable. Handed to the operating
     * system it becomes exit status 255, a value no JCL step ever produces and no {@code COND} test can
     * interpret; and delivering {@code 0} instead would report a failed job as a successful one. So the
     * launcher substitutes the severe-error code, which is both non-zero and one of the four this
     * migration's programs actually set.
     */
    static final int UNMAPPED_FAILURE_RETURN_CODE = AbendException.RETURN_CODE_IO_ERROR;

    private static final int MAX_CAUSE_CHAIN_DEPTH = 16;

    private static final Set<String> RETURN_CODE_ZERO_EXIT_CODES =
            Set.of(ExitStatus.COMPLETED.getExitCode(), ExitStatus.NOOP.getExitCode());

    private static final String PARAMETER_TYPE_STRING = "string";

    private final ObjectProvider<JobRepository> jobRepositoryProvider;

    private final ObjectProvider<PlatformTransactionManager> transactionManagerProvider;

    private final JobContracts jobContracts;

    private final JobParametersIncrementer runIdentityIncrementer = new RunIdIncrementer();

    private final DatasetBindings datasetBindings;

    /**
     * Constructor injection throughout: every collaborator is final, nothing is set after construction, and
     * no state is shared statically.
     *
     * @param jobRepositoryProvider provider for Spring Boot's auto-configured job repository; never
     *     {@code null}
     * @param transactionManagerProvider provider for the transaction manager declared by
     *     {@link #transactionManager(DataSource)}; never {@code null}
     * @param jobContracts the {@code carddemo.jobs} catalogue; never {@code null}
     * @param datasetBindings the {@code carddemo.datasets} catalogue; never {@code null}
     */
    public BatchConfig(ObjectProvider<JobRepository> jobRepositoryProvider,
                       ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
                       JobContracts jobContracts,
                       DatasetBindings datasetBindings) {
        this.jobRepositoryProvider = jobRepositoryProvider;
        this.transactionManagerProvider = transactionManagerProvider;
        this.jobContracts = jobContracts;
        this.datasetBindings = datasetBindings;
    }

    /**
     * The one and only transaction manager in this module, over the one {@link DataSource} that
     * {@code DataSourceConfig} publishes.
     *
     * @param dataSource the module's pooled {@code DataSource}, published by {@code DataSourceConfig};
     *     injected rather than constructed, so the driver stays a deployment-time input
     * @return the module-wide transaction manager
     */
    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    /**
     * A job builder already bound to the auto-configured job repository, carrying the two shared
     * return-code listeners, and carrying this module's run-identity and restart policy.
     *
     * @param jobName the job name, which is also its identity in the batch metadata; must be non-null and
     *     non-blank
     * @return a new job builder bound to the shared repository, both shared return-code listeners, the
     *     run-identity incrementer and the non-restartable policy
     * @throws IllegalArgumentException if {@code jobName} is {@code null}, empty or blank
     */
    public JobBuilder job(String jobName) {
        Assert.hasText(jobName, "A job name is required to build a job");
        return new JobBuilder(jobName, jobRepositoryProvider.getObject())
                .listener(abendExitStatusJobListener())
                .listener(condBypassExitStatusJobListener())
                .incrementer(jclRunIdentityIncrementer())
                .validator(jclParametersValidator(jobName))
                .preventRestart();
    }

    /**
     * The parameter allow-list every job carries: exactly the business parameters its {@code carddemo.jobs}
     * contract declares, plus the internal execution identity, and nothing else.
     *
     * <p>The ninth, {@code app/jcl/INTCALC.jcl:22}, carries exactly one, and a second value beside it would
     * be equally invisible.
     *
     * @param jobName the job's bean name, which is also the name its contract key derives to
     * @return a new validator; never shared, so it can be attached to a job builder freely
     */
    public JobParametersValidator jclParametersValidator(String jobName) {
        return new JclJobParametersValidator(this, jobName);
    }

    String contractKeyOf(String jobName) {
        Objects.requireNonNull(jobName, "A job name is required to find its carddemo.jobs contract");
        return jobContracts.keySet().stream()
                .filter(key -> jobName.equals(jobBeanNameOf(key)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The job '" + jobName + "' is not "
                        + "declared: no entry under carddemo.jobs declares it. A job's parameters, its "
                        + "step sequence and its COND gating are its contract, so a job with no "
                        + "contract has none to submit - correct " + JclJobLauncher.JOB_NAME_PROPERTY
                        + ", or declare the job. Declared jobs: "
                        + jobContracts.keySet().stream().map(BatchConfig::jobBeanNameOf).sorted()
                                .toList() + "."));
    }

    /**
     * The module's run-identity strategy: one new job instance per launch, and no invented business value.
     *
     * @return the shared incrementer
     */
    @Bean
    public JobParametersIncrementer jclRunIdentityIncrementer() {
        return runIdentityIncrementer;
    }

    /**
     * A step builder already bound to the auto-configured job repository and carrying the shared abend
     * listener.
     *
     * @param stepName the step name, transcribed from the JCL step it replaces wherever a JCL step exists;
     *     must be non-null and non-blank
     * @return a new step builder bound to the shared repository and listener
     * @throws IllegalArgumentException if {@code stepName} is {@code null}, empty or blank
     */
    public StepBuilder step(String stepName) {
        Assert.hasText(stepName, "A step name is required to build a step");
        return new StepBuilder(stepName, jobRepositoryProvider.getObject())
                .listener(abendExitStatusStepListener());
    }

    /**
     * A tasklet step builder, bound to the shared repository, the shared listener and the module's
     * transaction manager.
     *
     * <p>A tasklet runs the whole step body inside one transaction, which is what preserves the ordering
     * the COBOL relies on: a single pass over the input, accumulators carried across records, and writes
     * emitted in the order the program emits them.
     *
     * @param stepName the step name; must be non-null and non-blank
     * @param tasklet the step body; must be non-null
     * @return a new tasklet step builder ready for {@code build()}
     * @throws IllegalArgumentException if {@code stepName} is blank or {@code tasklet} is {@code null}
     */
    public TaskletStepBuilder taskletStep(String stepName, Tasklet tasklet) {
        Assert.notNull(tasklet, "A tasklet is required to build a tasklet step");
        return step(stepName).tasklet(tasklet, transactionManagerProvider.getObject());
    }

    /**
     * A chunk-oriented step builder, bound to the shared repository, the shared listener and the module's
     * transaction manager.
     *
     * <p>The chunk size is a parameter, never a module-wide default: it is a property of the COBOL loop
     * being translated, and this class holds no opinion about it.
     *
     * @param <I> the item type the reader produces
     * @param <O> the item type the writer consumes
     * @param stepName the step name; must be non-null and non-blank
     * @param chunkSize the commit interval in items, chosen by the job class from its own COBOL loop; must
     *     be positive
     * @return a new chunk-oriented step builder
     * @throws IllegalArgumentException if {@code stepName} is blank or {@code chunkSize} is not positive
     */
    public <I, O> SimpleStepBuilder<I, O> chunkStep(String stepName, int chunkSize) {
        Assert.isTrue(chunkSize > 0, "A chunk size must be positive");
        return step(stepName).<I, O>chunk(chunkSize, transactionManagerProvider.getObject());
    }

    /**
     * The {@code COND=(0,NE)} gate, published as a decider so a job flow can branch on it.
     *
     * @return a decider yielding {@link #PROCEED} or {@link #SKIP}
     */
    @Bean
    public JobExecutionDecider precedingExitCodeZeroDecider() {
        return BatchConfig::decidePrecedingExitCodeZero;
    }

    static FlowExecutionStatus decidePrecedingExitCodeZero(JobExecution jobExecution,
                                                          StepExecution stepExecution) {
        return allPrecedingStepsReturnedZero(jobExecution) ? PROCEED : SKIP;
    }

    static boolean allPrecedingStepsReturnedZero(JobExecution jobExecution) {
        Assert.notNull(jobExecution, "A job execution is required to evaluate the COND gate");
        return jobExecution.getStepExecutions().stream()
                .allMatch(step -> precedingStepReturnedZero(step.getExitStatus()));
    }

    static boolean precedingStepReturnedZero(ExitStatus exitStatus) {
        return returnCodeOf(exitStatus) == JCL_RETURN_CODE_ZERO;
    }

    /**
     * The return code a submission hands to the operating system: the one the execution reported, unless
     * that is not a code a JCL step could have produced.
     *
     * <p>Only negative values are substituted, and {@link #UNMAPPED_FAILURE_RETURN_CODE} explains why.
     * {@code 0}, {@code 4}, {@code 8}, {@code 12} and any other non-negative code a step set are delivered
     * exactly as they are - the point of the exercise is that the shell sees the number the COBOL set, and
     * this method is deliberately incapable of changing one of those.
     *
     * @param derived the code {@link #returnCodeOf(ExitStatus)} read from the finished execution
     * @return {@code derived} when it is a deliverable JCL return code, and
     *     {@link #UNMAPPED_FAILURE_RETURN_CODE} when it is not
     */
    static int deliverableReturnCode(int derived) {
        return derived < JCL_RETURN_CODE_ZERO ? UNMAPPED_FAILURE_RETURN_CODE : derived;
    }

    static int returnCodeOf(ExitStatus exitStatus) {
        Assert.notNull(exitStatus, "An exit status is required to derive a JCL return code");
        String exitCode = Objects.requireNonNullElse(exitStatus.getExitCode(), "");
        return RETURN_CODE_ZERO_EXIT_CODES.contains(exitCode)
                ? JCL_RETURN_CODE_ZERO
                : numericReturnCode(exitCode);
    }

    private static int numericReturnCode(String exitCode) {
        try {
            return Integer.parseInt(exitCode.trim());
        } catch (NumberFormatException notANumber) {
            return NO_JCL_RETURN_CODE;
        }
    }

    /**
     * The shared step listener that puts a COBOL {@code RETURN-CODE} onto a failed step's exit status.
     *
     * @return the shared step listener
     */
    @Bean
    public StepExecutionListener abendExitStatusStepListener() {
        return new AbendExitStatusStepListener();
    }

    /**
     * The shared job listener that puts a COBOL {@code RETURN-CODE} onto a failed job's exit status.
     *
     * @return the shared job listener
     */
    @Bean
    public JobExecutionListener abendExitStatusJobListener() {
        return new AbendExitStatusJobListener();
    }

    /**
     * The shared job listener that turns a {@link #COND_BYPASSED_EXIT_CODE} terminal into the highest JCL
     * return code the job's executed steps produced.
     *
     * @return the shared job listener
     */
    @Bean
    public JobExecutionListener condBypassExitStatusJobListener() {
        return new CondBypassExitStatusJobListener();
    }

    static int highestStepReturnCode(JobExecution jobExecution) {
        Assert.notNull(jobExecution, "A job execution is required to derive its highest return code");
        return jobExecution.getStepExecutions().stream()
                .map(StepExecution::getExitStatus)
                .mapToInt(BatchConfig::returnCodeOf)
                .filter(returnCode -> returnCode > JCL_RETURN_CODE_ZERO)
                .max()
                .orElse(NO_JCL_RETURN_CODE);
    }

    /**
     * Maps an {@link AbendException} escaping the application onto the process exit code, so that
     * {@code 0}, {@code 4}, {@code 8} and {@code 12} reach the operating system exactly as the COBOL set
     * them.
     *
     * <p>The exception is never swallowed and its message is never rewritten - the text the COBOL displays
     * before abending is observable behaviour, and the abend's own message reproduces it.
     *
     * <p>It claims an abend and nothing else, deliberately: this mapper sees every exception that escapes
     * the application, including the ones a context that never finished starting throws, and a
     * configuration this module refused at startup is not a job that ran and returned {@code 12}. A job
     * that failed without an abend gets its return code where it belongs instead - on its own exit status,
     * from {@link #withJclReturnCode(ExitStatus, BatchStatus, List)} - and {@link JclJobLauncher} carries
     * that out to the process.
     *
     * @return the mapper
     */
    @Bean
    public ExitCodeExceptionMapper abendExitCodeMapper() {
        return BatchConfig::exitCodeFor;
    }

    /**
     * The launcher that turns one process invocation into one JCL job submission, and its
     * {@code RETURN-CODE} into the process exit code.
     *
     * @param beanFactory the factory the configured job name is looked up in, by name
     * @param jobLauncherProvider the auto-configured launcher, resolved lazily so a broken launcher fails
     *     when a submission needs it rather than making this bean impossible to create
     * @param jobExplorerProvider the auto-configured explorer, resolved lazily for the same reason; it
     *     supplies the previous submission's execution identity
     * @param applicationContext the running context, closed on a successful submission so a one-shot
     *     process ends instead of idling
     * @param jobName the requested job's bean name
     * @return the launcher
     */
    @Bean
    @ConditionalOnProperty(name = JclJobLauncher.JOB_NAME_PROPERTY)
    public JclJobLauncher jclJobLauncher(ListableBeanFactory beanFactory,
            ObjectProvider<JobLauncher> jobLauncherProvider,
            ObjectProvider<JobExplorer> jobExplorerProvider,
            ConfigurableApplicationContext applicationContext,
            @Value("${" + JclJobLauncher.JOB_NAME_PROPERTY + "}") String jobName) {
        return new JclJobLauncher(beanFactory, jobLauncherProvider, jobExplorerProvider, this, jobName,
                new SpringApplicationExitTerminator(applicationContext));
    }

    /**
     * How a one-shot JCL submission ends the operating-system process it was invoked as.
     */
    public interface ProcessTerminator {
        void terminate(int returnCode);
    }

    /**
     * The production terminator: close the context through Spring Boot's own exit path, then end the JVM
     * with the code it computed.
     */
    static final class SpringApplicationExitTerminator implements ProcessTerminator {
        private final ConfigurableApplicationContext applicationContext;

        SpringApplicationExitTerminator(ConfigurableApplicationContext applicationContext) {
            this.applicationContext = Objects.requireNonNull(applicationContext, "The application "
                    + "context is required: a one-shot submission closes it before the process ends, so "
                    + "that the pool is drained and every shutdown callback has run");
        }

        @Override
        public void terminate(int returnCode) {
            System.exit(SpringApplication.exit(applicationContext, () -> returnCode));
        }
    }

    /**
     * Launches exactly one job and carries its {@code RETURN-CODE} out to the operating system.
     */
    public static final class JclJobLauncher implements ApplicationRunner, ExitCodeGenerator {
        public static final String JOB_NAME_PROPERTY = "carddemo.batch.job-name";

        private final ListableBeanFactory beanFactory;

        private final ObjectProvider<JobLauncher> jobLauncherProvider;

        private final ObjectProvider<JobExplorer> jobExplorerProvider;

        private final BatchConfig batchConfig;

        private final String jobName;

        private final ProcessTerminator processTerminator;

        private int returnCode = NO_MAPPED_EXIT_CODE;

        JclJobLauncher(ListableBeanFactory beanFactory,
                ObjectProvider<JobLauncher> jobLauncherProvider,
                ObjectProvider<JobExplorer> jobExplorerProvider,
                BatchConfig batchConfig, String jobName, ProcessTerminator processTerminator) {
            this.beanFactory = Objects.requireNonNull(beanFactory, "A bean factory is required to "
                    + "resolve the job named by " + JOB_NAME_PROPERTY + ": this module publishes one "
                    + "Job per translated program, so the name is the only unambiguous selector");
            this.jobLauncherProvider = Objects.requireNonNull(jobLauncherProvider, "A JobLauncher "
                    + "provider is required; Spring Boot's batch auto-configuration declares the "
                    + "single instance this module uses");
            this.jobExplorerProvider = Objects.requireNonNull(jobExplorerProvider, "A JobExplorer "
                    + "provider is required: the execution identity of a resubmission is read from the "
                    + "job's persisted history, never invented");
            this.batchConfig = Objects.requireNonNull(batchConfig, "The batch configuration is "
                    + "required to read the requested job's declared parameter contract");
            Assert.hasText(jobName, JOB_NAME_PROPERTY + " must name the job to submit");
            this.jobName = jobName;
            this.processTerminator = Objects.requireNonNull(processTerminator, "A process terminator "
                    + "is required: a submission that returned zero has to end the process, or a "
                    + "finished batch run idles with its return code undelivered");
        }

        /**
         * Submits the job, once, and delivers its return code to the operating system.
         *
         * <p>The code delivered is the execution's own, put through
         * {@link BatchConfig#deliverableReturnCode(int)} so a failure the module did not translate cannot
         * reach the shell as a number JCL never produces - see {@link #UNMAPPED_FAILURE_RETURN_CODE}.
         *
         * @param arguments the process arguments, which are deliberately not read: a job's parameters are
         *     its contract in {@code carddemo.jobs}, not free text from a command line
         * @throws JclReturnCodeException if the job's return code is not zero
         * @throws JobExecutionException if the launcher itself refuses the submission - an instance that
         *     already ran to completion, or parameters its validator rejected
         */
        @Override
        public void run(ApplicationArguments arguments) throws JobExecutionException {
            Job job = resolveJob();
            JobExecution execution =
                    jobLauncherProvider.getObject().run(job, submissionParameters(job.getName()));
            this.returnCode = deliverableReturnCode(returnCodeOf(execution.getExitStatus()));
            if (returnCode != JCL_RETURN_CODE_ZERO) {
                throw new JclReturnCodeException(job.getName(), returnCode,
                        execution.getExitStatus().getExitCode());
            }
            processTerminator.terminate(returnCode);
        }

        private Job resolveJob() {
            List<String> published =
                    Stream.of(beanFactory.getBeanNamesForType(Job.class)).sorted().toList();
            if (!published.contains(jobName)) {
                throw new IllegalStateException(JOB_NAME_PROPERTY + " names '" + jobName + "', but no "
                        + "Job bean is published under that name, so there is nothing to submit. A "
                        + "submission is resolved by bean name because this module publishes one job "
                        + "per translated program and a by-type lookup would be ambiguous. Published "
                        + "jobs: " + published + ".");
            }
            return beanFactory.getBean(jobName, Job.class);
        }

        private JobParameters submissionParameters(String resolvedJobName) {
            JobParameters declared =
                    batchConfig.contract(batchConfig.contractKeyOf(resolvedJobName)).jobParameters();
            return batchConfig.jclRunIdentityIncrementer()
                    .getNext(withPreviousRunIdentity(declared, resolvedJobName));
        }

        private JobParameters withPreviousRunIdentity(JobParameters declared, String resolvedJobName) {
            JobExplorer history = jobExplorerProvider.getObject();
            JobInstance lastInstance = history.getLastJobInstance(resolvedJobName);
            if (lastInstance == null) {
                return declared;
            }
            JobExecution lastExecution = history.getLastJobExecution(lastInstance);
            if (lastExecution == null) {
                return declared;
            }
            Long previousIdentity = lastExecution.getJobParameters().getLong(RUN_IDENTITY_PARAMETER);
            if (previousIdentity == null) {
                return declared;
            }
            return new JobParametersBuilder(declared)
                    .addLong(RUN_IDENTITY_PARAMETER, previousIdentity)
                    .toJobParameters();
        }

        /**
         * The return code the finished execution reported.
         *
         * @return {@code 0}, {@code 4}, {@code 8}, {@code 12} or whatever non-negative code a step set;
         *     {@link #UNMAPPED_FAILURE_RETURN_CODE} when the execution failed carrying no code a
         *     {@code COND} test could read; {@link #NO_MAPPED_EXIT_CODE} before the job has run
         */
        @Override
        public int getExitCode() {
            return returnCode;
        }

        public String jobName() {
            return jobName;
        }
    }

    /**
     * The per-job parameter allow-list: exactly the business parameters {@code carddemo.jobs} declares,
     * plus {@link #RUN_IDENTITY_PARAMETER}, and nothing else.
     */
    static final class JclJobParametersValidator implements JobParametersValidator {
        private final BatchConfig batchConfig;

        private final String jobName;

        private final JobParametersValidator parmDateRules = new ParmDateJobParametersValidator();

        JclJobParametersValidator(BatchConfig batchConfig, String jobName) {
            this.batchConfig = Objects.requireNonNull(batchConfig, "The batch configuration is "
                    + "required: a job's allowed parameters are the ones its carddemo.jobs contract "
                    + "declares");
            Assert.hasText(jobName, "A job name is required to find the contract whose parameters are "
                    + "allowed");
            this.jobName = jobName;
        }

        /**
         * Requires the submitted parameters to be exactly the declared business contract, plus at most the
         * internal execution identity.
         *
         * @param parameters the submitted parameters; {@code null} is read as none
         * @throws JobParametersInvalidException if an undeclared parameter is present, a declared one is
         *     absent, or the parameterised job's {@code parmDate} breaks its width contract
         * @throws IllegalStateException if no {@code carddemo.jobs} entry declares this job
         */
        @Override
        public void validate(JobParameters parameters) throws JobParametersInvalidException {
            JobParameters submitted = Objects.requireNonNullElseGet(parameters, JobParameters::new);
            String jobKey = batchConfig.contractKeyOf(jobName);
            Set<String> allowed = new LinkedHashSet<>(batchConfig.contract(jobKey).parameters().stream()
                    .map(JobParameterContract::name).toList());

            Set<String> business = new LinkedHashSet<>(submitted.getParameters().keySet());
            business.remove(RUN_IDENTITY_PARAMETER);

            Set<String> undeclared = new LinkedHashSet<>(business);
            undeclared.removeAll(allowed);
            Set<String> absent = new LinkedHashSet<>(allowed);
            absent.removeAll(business);
            if (!undeclared.isEmpty() || !absent.isEmpty()) {
                throw new JobParametersInvalidException("The submission of '" + jobName + "' does not "
                        + "match the parameter contract carddemo.jobs." + jobKey + " declares. "
                        + "Undeclared: " + undeclared + ". Missing: " + absent + ". Allowed: " + allowed
                        + " plus the internal execution identity " + RUN_IDENTITY_PARAMETER + ". A "
                        + "parameter the COBOL program never receives cannot change what it does, but "
                        + "it does change which job instance the submission resolves to, so a value "
                        + "that is not in the contract is refused rather than silently producing a "
                        + "different run.");
            }
            if (JobContracts.PARAMETERISED_JOB.equals(jobKey)) {
                parmDateRules.validate(submitted);
            }
        }
    }

    static String jobBeanNameOf(String jobKey) {
        Objects.requireNonNull(jobKey, "A job key is required to derive a job bean name");
        StringBuilder beanName = new StringBuilder(jobKey.length());
        boolean capitaliseNext = false;
        for (int index = 0; index < jobKey.length(); index++) {
            char character = jobKey.charAt(index);
            if (character == '-') {
                capitaliseNext = true;
            } else if (capitaliseNext) {
                beanName.append(Character.toUpperCase(character));
                capitaliseNext = false;
            } else {
                beanName.append(character);
            }
        }
        return beanName.toString();
    }

    /**
     * A job that finished with a non-zero {@code RETURN-CODE}, raised so that the code reaches the
     * operating system.
     */
    public static final class JclReturnCodeException extends RuntimeException
            implements ExitCodeGenerator {
        private static final long serialVersionUID = 1L;

        private final int returnCode;

        JclReturnCodeException(String jobName, int returnCode, String exitCode) {
            super("Job '" + jobName + "' ended with RETURN-CODE " + returnCode + " (exit status '"
                    + exitCode + "'). On the mainframe this is the value the next job step's COND "
                    + "test reads, so it is carried out as the process exit code unchanged.");
            this.returnCode = returnCode;
        }

        /**
         * The return code, which is also the process exit code.
         *
         * @return the code the job reported
         */
        @Override
        public int getExitCode() {
            return returnCode;
        }
    }

    /**
     * The between-record cancellation probe a long single-pass tasklet consults, so that a stop requested
     * while it is running is honoured before the next record rather than after the last one.
     *
     * <p>Placed mid-record it could abandon a COBOL paragraph half-performed, which is a state the legacy
     * program cannot be in.
     */
    @FunctionalInterface
    public interface StopSignal {
        StopSignal RUNNING = () -> {
        };

        void checkStopRequested();

        static StopSignal of(ChunkContext chunkContext) {
            Objects.requireNonNull(chunkContext, "A chunk context is required to observe a stop "
                    + "request; the framework supplies one to every tasklet");
            return of(chunkContext.getStepContext().getStepExecution());
        }

        static StopSignal of(StepExecution stepExecution) {
            Objects.requireNonNull(stepExecution, "A step execution is required to observe a stop "
                    + "request");
            StepInterruptionPolicy policy = new ThreadStepInterruptionPolicy();
            return () -> {
                try {
                    policy.checkInterrupted(stepExecution);
                } catch (JobInterruptedException stopRequested) {
                    throw new StopRequestedException(stepExecution.getStepName(), stopRequested);
                }
            };
        }
    }

    /**
     * A pass abandoned between records because its step was asked to stop.
     */
    public static final class StopRequestedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StopRequestedException(String stepName, JobInterruptedException interrupted) {
            super("Step '" + stepName + "' was asked to stop, so its pass was abandoned between "
                    + "records rather than part way through one. The record in flight was complete; "
                    + "records already written stay written, and NO write is retried - a retried "
                    + "COBOL WRITE or REWRITE would be a duplicate record, because these datasets "
                    + "carry no idempotency key. This job is not restartable: rerun it, which is a "
                    + "fresh pass over the whole input, exactly as resubmitting a cancelled JCL job "
                    + "is.", Objects.requireNonNull(interrupted, "The framework's interruption is "
                            + "required as the cause: it is what makes the step report as STOPPED "
                            + "rather than FAILED"));
        }
    }

    /**
     * Validates the whole job-contract graph against the dataset catalogue, once, at startup.
     *
     * @param jobContracts the job catalogue bound from {@code carddemo.jobs}
     * @param datasetBindings the DD-name catalogue bound from {@code carddemo.datasets}
     * @return the startup validator
     */
    @Bean
    public JobContractValidator jobContractValidator(JobContracts jobContracts,
            DatasetBindings datasetBindings) {
        return new JobContractValidator(jobContracts, datasetBindings);
    }

    /**
     * Runs {@link JobContracts#validate(DatasetBindings)} at context refresh.
     */
    static final class JobContractValidator implements InitializingBean {
        private final JobContracts jobContracts;

        private final DatasetBindings datasetBindings;

        JobContractValidator(JobContracts jobContracts, DatasetBindings datasetBindings) {
            this.jobContracts = jobContracts;
            this.datasetBindings = datasetBindings;
        }

        /**
         * Validates the job catalogue, failing the context on the first incoherence found.
         *
         * @throws IllegalStateException if any job contract is invalid
         */
        @Override
        public void afterPropertiesSet() {
            jobContracts.validate(datasetBindings);
        }
    }

    static int exitCodeFor(Throwable failure) {
        return findAbend(failure).map(AbendException::getReturnCode).orElse(NO_MAPPED_EXIT_CODE);
    }

    static ExitStatus withAbendExitCode(ExitStatus current, List<Throwable> failures) {
        ExitStatus reported = Objects.requireNonNullElse(current, ExitStatus.UNKNOWN);
        return findAbend(failures)
                .map(abend -> reported.replaceExitCode(Integer.toString(abend.getReturnCode())))
                .orElse(reported);
    }

    /**
     * The exit status a finished step or job reports, with a JCL return code on it in every case where the
     * execution ended abnormally.
     *
     * <p>Two sources, in this order of authority:
     *
     * <ol>
     *   <li>an {@link AbendException} anywhere in the failures - its {@code RETURN-CODE} is what the COBOL
     *       itself moved into {@code APPL-RESULT}, so it is transcribed unchanged;</li>
     *   <li>otherwise, for a {@link BatchStatus#FAILED} execution whose reported code is not a number,
     *       {@link #UNMAPPED_FAILURE_RETURN_CODE}. The framework leaves the literal {@code "FAILED"} there,
     *       which {@link #returnCodeOf(ExitStatus)} can only read as {@link #NO_JCL_RETURN_CODE} - and that
     *       reached the operating system as exit {@code 255}, a value no {@code COND} test in this estate
     *       has a meaning for.</li>
     * </ol>
     *
     * <p>Everything else is returned exactly as the framework left it, and the two exclusions are
     * deliberate. A code that already parses as a number is a code something chose - {@code 0}, {@code 4},
     * {@code 8}, {@code 12}, or the {@code COND BYPASSED} terminal's own rewrite - and must not be
     * overwritten. And a status other than {@code FAILED} is not a failure: a {@link BatchStatus#STOPPED}
     * step was cancelled between records rather than failing, which on the mainframe ends the job with an
     * abend code rather than a condition code, so this module deliberately reports no return code for it.
     *
     * @param current the status as the framework left it, or {@code null} which reads as
     *     {@link ExitStatus#UNKNOWN}
     * @param batchStatus the execution's own status, which is what says whether it failed; {@code null}
     *     reads as "not failed"
     * @param failures the execution's failure exceptions, searched for an abend; {@code null} reads as none
     * @return the status to report; never {@code null}
     */
    static ExitStatus withJclReturnCode(ExitStatus current, BatchStatus batchStatus,
            List<Throwable> failures) {
        ExitStatus reported = withAbendExitCode(current, failures);
        if (BatchStatus.FAILED != batchStatus || returnCodeOf(reported) != NO_JCL_RETURN_CODE) {
            return reported;
        }
        return reported.replaceExitCode(Integer.toString(UNMAPPED_FAILURE_RETURN_CODE));
    }

    static Optional<AbendException> findAbend(List<Throwable> failures) {
        return Objects.requireNonNullElse(failures, List.<Throwable>of()).stream()
                .map(BatchConfig::findAbend)
                .flatMap(Optional::stream)
                .findFirst();
    }

    static Optional<AbendException> findAbend(Throwable failure) {
        return Stream.iterate(failure, Objects::nonNull, Throwable::getCause)
                .limit(MAX_CAUSE_CHAIN_DEPTH)
                .filter(AbendException.class::isInstance)
                .map(AbendException.class::cast)
                .findFirst();
    }

    /**
     * The parameter validator for the interest calculator, and for no other job.
     *
     * <p>{@code app/jcl/INTCALC.jcl:L22} always supplies its {@code PARM}, so the translated job always
     * requires {@link #PARM_DATE_PARAMETER}.
     *
     * @return a new validator; never shared, so it can be attached to a job builder freely
     */
    public JobParametersValidator parmDateValidator() {
        return new ParmDateJobParametersValidator();
    }

    /**
     * The per-job contracts bound from {@code carddemo.jobs}.
     *
     * @return the catalogue; never {@code null}
     */
    public JobContracts jobContracts() {
        return jobContracts;
    }

    /**
     * The contract for one job, by its configuration key.
     *
     * @param jobKey the kebab-case job key, spelled exactly as {@code application.yml} declares it
     * @return the contract; never {@code null}
     * @throws IllegalStateException if no contract is configured under that key
     */
    public JobContract contract(String jobKey) {
        return jobContracts.contract(jobKey);
    }

    /**
     * Resolves one job's view of a DD name: its own override first, the global catalogue second.
     *
     * @param jobKey the kebab-case job key
     * @param ddName the DD or CICS file name, in the upper case the configuration declares
     * @return the binding this job addresses under that DD name; never {@code null}
     * @throws IllegalStateException if neither the job nor the global catalogue declares it
     */
    public DatasetBinding datasetBinding(String jobKey, String ddName) {
        return contract(jobKey).datasetBinding(ddName, datasetBindings);
    }

    /**
     * Requires that a job's JCL DD name and the DD name of the repository it reads through resolve to the
     * same dataset, and returns that dataset's name.
     *
     * <p>The shipped defaults agree, which is precisely what makes the divergence dangerous: it would not
     * appear in any test, and a job would read a dataset its own JCL never named - silently, and with a
     * correct-looking result.
     *
     * @param jobKey the kebab-case job key
     * @param jclDdName the DD name the job's own JCL step declares
     * @param repositoryDdName the DD name the repository this job reads through is bound to
     * @return the dataset name both keys resolve to; never blank
     * @throws IllegalStateException if either DD name is undeclared, or the two resolve to different
     *     datasets
     */
    public String requireSameDataset(String jobKey, String jclDdName, String repositoryDdName) {
        String jclDsname = datasetBinding(jobKey, jclDdName).dsname();
        String repositoryDsname = datasetBindings.binding(repositoryDdName).dsname();
        if (!jclDsname.equals(repositoryDsname)) {
            throw new IllegalStateException("Job " + jobKey + " declares DD " + jclDdName
                    + ", which resolves to dataset " + jclDsname + ", but it reads through the "
                    + repositoryDdName + " repository binding, which resolves to " + repositoryDsname
                    + ". A batch step reads what its DD statement names, so these must be the same "
                    + "dataset. Point carddemo.datasets." + jclDdName + ".dsname and carddemo.datasets."
                    + repositoryDdName + ".dsname at one dataset, or give job " + jobKey + " a "
                    + "job-scoped binding for " + jclDdName + " that matches. Refusing to start rather "
                    + "than read a dataset this job's JCL never named.");
        }
        return jclDsname;
    }

    /**
     * Requires that a job's configured step sequence is exactly the one the calling job class needs - same
     * steps, same programs, same {@code COND=(0,NE)} gates, same order, no extras and none missing - and
     * returns it.
     *
     * @param jobKey the kebab-case job key whose contract is being checked
     * @param required the sequence this job class requires, in execution order
     * @param jclReference the JCL or cataloged procedure the required sequence is transcribed from, quoted
     *     in the diagnostic so a reader can go and look at it
     * @return the configured sequence, which equals {@code required}; never {@code null}
     * @throws IllegalStateException if the configured sequence differs in any respect
     */
    public List<StepContract> requireSteps(String jobKey, List<StepContract> required,
            String jclReference) {
        List<StepContract> declared = contract(jobKey).steps();
        if (!required.equals(declared)) {
            throw new IllegalStateException("The carddemo.jobs contract for '" + jobKey + "' does not "
                    + "declare the step sequence of " + jclReference + ".\n  configured: "
                    + JobContracts.describe(declared) + "\n  required:   "
                    + JobContracts.describe(required)
                    + "\nStep names, their EXEC PGM= programs, their COND=(0,NE) gates and their order "
                    + "are all transcribed from " + jclReference + ". Any of those five differing gives "
                    + "a job that starts cleanly and runs different work against the same datasets, so "
                    + "this refuses to build the job rather than run it.");
        }
        return declared;
    }

    /**
     * Carries a {@code RETURN-CODE} onto a step's exit status: the abend's own, or
     * {@link #UNMAPPED_FAILURE_RETURN_CODE} for a step that failed carrying none.
     */
    static final class AbendExitStatusStepListener implements StepExecutionListener {
        /**
         * Reports the step's exit status, with the code replaced by a JCL return code whenever the step
         * failed - the abend's code when it carried one, {@link #UNMAPPED_FAILURE_RETURN_CODE} when it did
         * not.
         *
         * @param stepExecution the finished step; never {@code null} when called by the framework
         * @return the exit status to report
         */
        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            return withJclReturnCode(stepExecution.getExitStatus(), stepExecution.getStatus(),
                    stepExecution.getFailureExceptions());
        }
    }

    /**
     * Carries a {@code RETURN-CODE} onto a job's exit status: the abend's own, or
     * {@link #UNMAPPED_FAILURE_RETURN_CODE} for a job that failed carrying none.
     */
    static final class AbendExitStatusJobListener implements JobExecutionListener {
        /**
         * Sets the job's exit status from the abend when one is present anywhere in the job's failures, and
         * from {@link #UNMAPPED_FAILURE_RETURN_CODE} when the job failed with none - so that a
         * {@code RETURN-CODE} always reaches {@link JclJobLauncher} and, through it, the process.
         *
         * @param jobExecution the finished job; never {@code null} when called by the framework
         */
        @Override
        public void afterJob(JobExecution jobExecution) {
            jobExecution.setExitStatus(withJclReturnCode(jobExecution.getExitStatus(),
                    jobExecution.getStatus(), jobExecution.getAllFailureExceptions()));
        }
    }

    /**
     * Replaces a {@link #COND_BYPASSED_EXIT_CODE} job exit code with the highest JCL return code the job's
     * executed steps produced.
     */
    static final class CondBypassExitStatusJobListener implements JobExecutionListener {
        /**
         * Rewrites the exit code when, and only when, it is exactly {@link #COND_BYPASSED_EXIT_CODE}.
         *
         * @param jobExecution the finished job; never {@code null} when called by the framework
         */
        @Override
        public void afterJob(JobExecution jobExecution) {
            ExitStatus reported = Objects.requireNonNullElse(jobExecution.getExitStatus(),
                    ExitStatus.UNKNOWN);
            if (COND_BYPASSED_EXIT_CODE.equals(reported.getExitCode())) {
                jobExecution.setExitStatus(reported.replaceExitCode(
                        Integer.toString(highestStepReturnCode(jobExecution))));
            }
        }
    }

    /**
     * Requires the interest calculator's {@code parmDate} parameter to be present, non-blank and exactly
     * {@value #PARM_DATE_WIDTH} characters wide.
     *
     * <p>{@code app/jcl/INTCALC.jcl:22} supplies exactly ten characters - {@code PARM='2022071800'} - and
     * any other length is a misconfiguration rather than an alternative.
     */
    static final class ParmDateJobParametersValidator implements JobParametersValidator {
        private final JobParametersValidator requiredKeys = new DefaultJobParametersValidator(
                new String[] { PARM_DATE_PARAMETER }, new String[0]);

        /**
         * Validates the parameters of an interest-calculator launch.
         *
         * @param parameters the parameters to validate
         * @throws JobParametersInvalidException if {@code parmDate} is absent, blank, or not exactly
         *     {@value BatchConfig#PARM_DATE_WIDTH} characters wide
         */
        @Override
        public void validate(JobParameters parameters) throws JobParametersInvalidException {
            requiredKeys.validate(parameters);
            String parmDate = parameters.getString(PARM_DATE_PARAMETER);
            if (!StringUtils.hasText(parmDate)) {
                throw new JobParametersInvalidException("The job parameter '" + PARM_DATE_PARAMETER
                        + "' must not be blank. It is the PARM of app/jcl/INTCALC.jcl and is "
                        + "character data that CBACT04C concatenates verbatim into the transaction "
                        + "identifiers it generates, so a blank value would silently write short "
                        + "identifiers instead of failing.");
            }
            if (parmDate.length() != PARM_DATE_WIDTH) {
                throw new JobParametersInvalidException("The job parameter '" + PARM_DATE_PARAMETER
                        + "' is " + parmDate.length() + " characters ('" + parmDate + "'), but it "
                        + "must be exactly " + PARM_DATE_WIDTH + ". " + PARM_DATE_WIDTH_RATIONALE);
            }
        }
    }

    /**
     * The job-keyed catalogue of per-job contracts, bound from the {@code carddemo.jobs} configuration
     * prefix.
     */
    @ConfigurationProperties(prefix = "carddemo.jobs", ignoreUnknownFields = false)
    public static class JobContracts extends LinkedHashMap<String, JobContract> {
        static final Map<String, String> REQUIRED_JOBS = Map.of(
                "account-balance-job", "CBACT01C",
                "account-balance-reader-job", "CBACT02C",
                "account-balance-update-job", "CBACT03C",
                "customer-file-reader-job", "CBCUS01C",
                "account-interest-calc-job", "CBACT04C",
                "transaction-validation-job", "CBTRN02C",
                "transaction-report-job", "CBTRN03C",
                "statement-generation-job-a", "CBSTM03A",
                "transaction-posting-job", "CBTRN01C");

        static final Map<String, List<StepContract>> REQUIRED_STEPS = Map.of(
                "account-balance-job",
                List.of(new StepContract("STEP05", "CBACT01C", false)),
                "account-balance-reader-job",
                List.of(new StepContract("STEP05", "CBACT02C", false)),
                "account-balance-update-job",
                List.of(new StepContract("STEP05", "CBACT03C", false)),
                "customer-file-reader-job",
                List.of(new StepContract("STEP05", "CBCUS01C", false)),
                "account-interest-calc-job",
                List.of(new StepContract("STEP15", "CBACT04C", false)),
                "transaction-validation-job",
                List.of(new StepContract("STEP15", "CBTRN02C", false)),
                "transaction-report-job",
                List.of(new StepContract("STEP01R", "IDCAMS", false),
                        new StepContract("STEP05R", "SORT", false),
                        new StepContract("STEP10R", "CBTRN03C", false)),
                "statement-generation-job-a",
                List.of(new StepContract("DELDEF01", "IDCAMS", false),
                        new StepContract("STEP010", "SORT", false),
                        new StepContract("STEP020", "IDCAMS", true),
                        new StepContract("STEP030", "IEFBR14", true),
                        new StepContract("STEP040", "CBSTM03A", true)),
                "transaction-posting-job",
                List.of(new StepContract("STEP01", "CBTRN01C", false)));

        static final String PARAMETERISED_JOB = "account-interest-calc-job";

        private static final long serialVersionUID = 1L;

        /**
         * Resolves a job contract by its configuration key.
         *
         * @param jobKey the kebab-case job key
         * @return the configured contract; never {@code null}
         * @throws IllegalStateException if no contract is configured under {@code jobKey}
         */
        public JobContract contract(String jobKey) {
            JobContract contract = get(jobKey);
            if (contract == null) {
                throw new IllegalStateException("No job contract is configured for '" + jobKey
                        + "'. Every migrated batch job declares its program, its step sequence, its "
                        + "COND gating, its parameters and any job-scoped dataset override under "
                        + "the carddemo.jobs configuration prefix. Add carddemo.jobs." + jobKey
                        + " to application.yml, or correct the key at the call site: keys are "
                        + "matched exactly, with no case-insensitive or fuzzy fallback. Configured "
                        + "keys: " + keySet() + ".");
            }
            requireCompleteSteps(jobKey, contract.steps());
            return contract;
        }

        /**
         * The catalogue's whole validity contract, checked against the global dataset catalogue.
         *
         * @param global the DD-name catalogue owned by {@code DataSourceConfig}, already validated
         * @throws IllegalStateException if the key set is wrong, a program is mispaired, or any nested
         *     contract is incoherent
         * @throws IllegalArgumentException if a declared parameter is unusable, which
         *     {@link JobParameterContract#requireStringValue()} reports
         */
        public void validate(DatasetBindings global) {
            Assert.notNull(global, "The global dataset catalogue is required to validate job "
                    + "contracts, because a job-scoped alias is only meaningful relative to it");
            validateKeySet();
            forEach((jobKey, contract) -> validateContract(jobKey, contract, global));
        }

        private void validateKeySet() {
            Set<String> missing = new LinkedHashSet<>(REQUIRED_JOBS.keySet());
            missing.removeAll(keySet());
            Set<String> unexpected = new LinkedHashSet<>(keySet());
            unexpected.removeAll(REQUIRED_JOBS.keySet());
            if (!missing.isEmpty() || !unexpected.isEmpty()) {
                throw new IllegalStateException("carddemo.jobs must declare exactly the "
                        + REQUIRED_JOBS.size() + " runnable batch jobs this migration has - the 8 "
                        + "programs invoked by an EXEC PGM= step in app/jcl or app/proc plus the "
                        + "untriggered CBTRN01C. Missing: " + sortedKeys(missing) + ". Unexpected: "
                        + sortedKeys(unexpected) + ". Do not close a gap by inventing a tenth job: "
                        + "the migration plan's summary total of ten counts prompt-mandated class "
                        + "names ending in Job, two of which - StatementGenerationJobB for CBSTM03B "
                        + "and DateUtilityJob for CSUTLDTC - the same plan makes a @Component and a "
                        + "@Service (gate G12), while its own job table, its program classification "
                        + "and its dependency note all state nine. No further EXEC PGM= exists to "
                        + "translate, so a tenth job would be batch behaviour the COBOL does not "
                        + "have.");
            }
        }

        private void validateContract(String jobKey, JobContract contract, DatasetBindings global) {
            if (contract == null) {
                throw new IllegalStateException(invalidJob(jobKey)
                        + " it declares no properties at all. Every job declares at least a program "
                        + "and a step sequence.");
            }
            String expectedProgram = REQUIRED_JOBS.get(jobKey);
            if (!expectedProgram.equals(contract.program())) {
                throw new IllegalStateException(invalidJob(jobKey) + " it declares program '"
                        + contract.program() + "', but this job was translated from "
                        + expectedProgram + ". The pairing is fixed by the source: re-pointing a job "
                        + "key at another program would run the wrong step sequence against the "
                        + "wrong datasets, and nothing downstream would notice.");
            }
            validateSteps(jobKey, contract.steps());
            validateParameters(jobKey, contract.parameters());
            contract.datasets().forEach((ddName, override) ->
                    validateDatasetOverride(jobKey, ddName, override, global));
        }

        private void validateSteps(String jobKey, List<StepContract> steps) {
            if (steps.isEmpty()) {
                throw new IllegalStateException(invalidJob(jobKey) + " it declares no steps. A job "
                        + "runs a step sequence transcribed from its JCL, and the untriggered "
                        + "CBTRN01C still declares the single step it would run.");
            }
            requireCompleteSteps(jobKey, steps);
            Set<String> names = new LinkedHashSet<>();
            for (int index = 0; index < steps.size(); index++) {
                StepContract step = steps.get(index);
                if (!names.add(step.name())) {
                    throw new IllegalStateException(invalidJob(jobKey) + " it declares two steps "
                            + "named '" + step.name() + "'. Steps are addressed by name, so a "
                            + "duplicate makes one of them unreachable. Where the JCL itself names "
                            + "two steps identically - app/jcl/TRANREPT.jcl does, at L23 and L37 - "
                            + "the cataloged procedure form app/proc/TRANREPT.prc supplies the "
                            + "unambiguous names and configuration uses those.");
                }
                if (index == 0 && step.requirePrecedingExitCodeZero()) {
                    throw new IllegalStateException(invalidJob(jobKey) + " its first step '"
                            + step.name() + "' is gated on every preceding step having returned "
                            + "zero, but it has no preceding step. COND=(0,NE) gating is "
                            + "transcribed from the JCL and appears only on the three gated steps of "
                            + "app/jcl/CREASTMT.JCL, never on a job's first step.");
                }
            }
            requireExactSequence(jobKey, steps);
        }

        /**
         * Requires the declared sequence to be exactly the one {@link #REQUIRED_STEPS} transcribes from the
         * JCL - same steps, same programs, same gates, same order, no extras and none missing.
         *
         * @param jobKey the configuration key, quoted in the diagnostic
         * @param steps the declared steps, in declaration order
         * @throws IllegalStateException if the declared sequence differs from the transcribed one in any
         *     respect
         */
        private void requireExactSequence(String jobKey, List<StepContract> steps) {
            List<StepContract> transcribed = REQUIRED_STEPS.get(jobKey);
            if (!transcribed.equals(steps)) {
                throw new IllegalStateException(invalidJob(jobKey) + " its step sequence is not the one "
                        + "its JCL declares.\n  configured: " + describe(steps)
                        + "\n  required:   " + describe(transcribed)
                        + "\nEvery step name, its EXEC PGM= program and its COND=(0,NE) gate are "
                        + "transcribed from app/jcl and app/proc, and so is their order. A step added, "
                        + "removed, reordered, re-pointed at another program or gated differently "
                        + "produces a job that starts cleanly and does different work: reordering "
                        + "CREASTMT's sort and its REPRO loads the previous run's data, dropping "
                        + "TRANREPT's unload reports over a stale extract, and ungating STEP040 "
                        + "generates statements from a work file the load never populated.");
            }
        }

        static String describe(List<StepContract> steps) {
            return steps.stream()
                    .map(step -> step.name() + "/" + step.program()
                            + (step.requirePrecedingExitCodeZero() ? " [COND=(0,NE)]" : ""))
                    .toList()
                    .toString();
        }

        private void validateParameters(String jobKey, List<JobParameterContract> parameters) {
            if (!PARAMETERISED_JOB.equals(jobKey)) {
                if (!parameters.isEmpty()) {
                    throw new IllegalStateException(invalidJob(jobKey) + " it declares parameters "
                            + parameters.stream().map(JobParameterContract::name).toList()
                            + ", but its JCL step carries no PARM, so the COBOL program receives "
                            + "nothing. Only " + PARAMETERISED_JOB + " takes a parameter "
                            + "(app/jcl/INTCALC.jcl:22); CBTRN03C reads its reporting date range "
                            + "from the DATEPARM dataset and declares date-range-source instead.");
                }
                return;
            }
            List<String> names = parameters.stream().map(JobParameterContract::name).toList();
            if (!names.equals(List.of(PARM_DATE_PARAMETER))) {
                throw new IllegalStateException(invalidJob(jobKey) + " it declares parameters "
                        + names + ", but it takes exactly one: '" + PARM_DATE_PARAMETER + "', whose "
                        + "value is the PARM of app/jcl/INTCALC.jcl:22.");
            }
            parameters.forEach(JobParameterContract::requireStringValue);
        }

        private void validateDatasetOverride(String jobKey, String ddName,
                JobDatasetBinding override, DatasetBindings global) {
            boolean isAlias = StringUtils.hasText(override.alias());
            boolean isInline = StringUtils.hasText(override.dsname())
                    || override.recordLength() != null
                    || StringUtils.hasText(override.organization());
            if (isAlias && isInline) {
                throw new IllegalStateException(invalidJob(jobKey) + " its dataset override for DD "
                        + "name '" + ddName + "' names alias '" + override.alias() + "' and also "
                        + "declares its own dsname, organization or record-length. That is "
                        + "ambiguous: an alias resolves entirely through the global entry it names, "
                        + "so any inline geometry beside it is silently ignored. Declare one or the "
                        + "other.");
            }
            if (!isAlias && !isInline) {
                throw new IllegalStateException(invalidJob(jobKey) + " its dataset override for DD "
                        + "name '" + ddName + "' declares neither an alias nor a dataset of its own.");
            }
            if (isAlias) {
                global.binding(override.alias());
                return;
            }
            override.resolve(ddName, global);
            if (!StringUtils.hasText(override.dsname())) {
                throw new IllegalStateException(invalidJob(jobKey) + " its inline dataset for DD "
                        + "name '" + ddName + "' declares no dsname. An entry that is not an alias is "
                        + "a dataset of its own, so it has to say where it lives - there is no global "
                        + "entry for it to inherit a location from.");
            }
            requireDatasetNameGrammar(jobKey, ddName, override.dsname());
            if (override.recordLength() <= 0) {
                throw new IllegalStateException(invalidJob(jobKey) + " its inline dataset for DD "
                        + "name '" + ddName + "' declares record-length " + override.recordLength()
                        + ". The fixed record width is what the codec and the output writers are "
                        + "built from, so it must be a positive number of bytes - a wrong width "
                        + "silently corrupts every record the job writes under this DD name.");
            }
            if (override.blockSize() != null && override.blockSize() < 0) {
                throw new IllegalStateException(invalidJob(jobKey) + " its inline dataset for DD "
                        + "name '" + ddName + "' declares block-size " + override.blockSize()
                        + ". Transcribe the JCL DCB verbatim: 0 means system-determined, exactly as "
                        + "BLKSIZE=0 asks, but no DCB declares a negative block size.");
            }
        }

        private void requireDatasetNameGrammar(String jobKey, String ddName, String dsname) {
            try {
                DatasetRelation.requireDatasetName(dsname);
            } catch (IllegalArgumentException notADatasetName) {
                throw new IllegalStateException(invalidJob(jobKey) + " its inline dataset for DD "
                        + "name '" + ddName + "' declares dsname '" + dsname + "', which is not a "
                        + "z/OS dataset name. A dsname becomes a delimited SQL identifier, so a "
                        + "filesystem path or a classpath URL can never be read - state the dataset "
                        + "name here and bind the storage behind it in the profile's own way.",
                        notADatasetName);
            }
        }

        static void requireCompleteSteps(String jobKey, List<StepContract> steps) {
            for (int index = 0; index < steps.size(); index++) {
                StepContract step = steps.get(index);
                List<String> missing = new ArrayList<>(2);
                if (!StringUtils.hasText(step.name())) {
                    missing.add("carddemo.jobs." + jobKey + ".steps[" + index + "].name");
                }
                if (!StringUtils.hasText(step.program())) {
                    missing.add("carddemo.jobs." + jobKey + ".steps[" + index + "].program");
                }
                if (!missing.isEmpty()) {
                    throw new IllegalStateException(invalidJob(jobKey) + " its step at position "
                            + index + " declares no value for " + String.join(" and ", missing)
                            + ". Both are transcribed from the JCL step - the name from the step "
                            + "label and the program from its EXEC PGM= - and a step missing either "
                            + "cannot be addressed or run. Restore the omitted key: note that a list "
                            + "in a higher-precedence property source replaces the whole list rather "
                            + "than merging into it, so restating one element of steps discards every "
                            + "other element and every other key of the element restated.");
                }
            }
        }

        private static String invalidJob(String jobKey) {
            return "The carddemo.jobs contract for '" + jobKey + "' is invalid:";
        }

        private static List<String> sortedKeys(Set<String> keys) {
            return keys.stream().sorted().toList();
        }
    }

    /**
     * One job's contract: the program it was translated from, the parameters it declares, its step sequence
     * with the gating flag per step, where its reporting date range comes from, and any DD name it resolves
     * differently from the global catalogue.
     *
     * @param program the COBOL {@code PROGRAM-ID} this job was translated from, so a run can be read
     *     against {@code app/cbl}
     * @param parameters the declared job parameters, in declaration order; empty for every job whose JCL
     *     step carries no {@code PARM}
     * @param steps the step sequence, transcribed from the JCL wherever a JCL step exists, in execution
     *     order
     * @param dateRangeSource the DD name a job reads its reporting date range from, or {@code null} when it
     *     reads none
     * @param datasets the job-scoped DD-name overrides, keyed by DD name; empty when the job resolves every
     *     DD name from the global catalogue
     */
    public record JobContract(
            String program,
            List<JobParameterContract> parameters,
            List<StepContract> steps,
            String dateRangeSource,
            Map<String, JobDatasetBinding> datasets) {
        public JobContract {
            parameters = List.copyOf(Objects.requireNonNullElse(parameters, List.of()));
            steps = List.copyOf(Objects.requireNonNullElse(steps, List.of()));
            datasets = Map.copyOf(Objects.requireNonNullElse(datasets, Map.of()));
        }

        public StepContract step(String stepName) {
            return steps.stream()
                    .filter(step -> Objects.equals(step.name(), stepName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Job '" + program
                            + "' declares no step named '" + stepName + "'. Its steps are "
                            + steps.stream().map(StepContract::name).toList()
                            + ", transcribed from the JCL in declaration order."));
        }

        /**
         * The job parameters to launch this job with, built from what configuration declares.
         *
         * @return the declared job parameters
         * @throws IllegalArgumentException if a declared parameter names a type other than {@code string},
         *     or carries a blank value
         */
        public JobParameters jobParameters() {
            JobParametersBuilder builder = new JobParametersBuilder();
            parameters.forEach(parameter ->
                    builder.addString(parameter.name(), parameter.requireStringValue()));
            return builder.toJobParameters();
        }

        /**
         * Resolves this job's view of a DD name: its own override first, the global catalogue second.
         *
         * @param ddName the DD or CICS file name, in the upper case configuration declares
         * @param global the global DD-name catalogue owned by {@code DataSourceConfig}
         * @return the binding this job addresses under that DD name; never {@code null}
         * @throws IllegalStateException if neither this job nor the global catalogue declares it
         */
        public DatasetBinding datasetBinding(String ddName, DatasetBindings global) {
            Assert.notNull(global, "The global dataset catalogue is required to resolve a DD name");
            JobDatasetBinding override = datasets.get(ddName);
            return override == null ? global.binding(ddName) : override.resolve(ddName, global);
        }
    }

    /**
     * One step of a job: its name, the program or utility the JCL step ran, and whether it is gated behind
     * every preceding step having returned zero.
     *
     * <p>Steps whose {@code program} is a utility rather than a migrated COBOL program are recorded because
     * the gating and the ordering depend on them: the statement job's first three steps delete and define a
     * dataset, sort it and load it, and the migrated program runs only fourth.
     *
     * @param name the step name, transcribed from the JCL
     * @param program the program or utility named by the JCL step's {@code EXEC PGM=}
     * @param requirePrecedingExitCodeZero {@code true} only where the JCL step carries {@code COND=(0,NE)}:
     *     the three gated steps of {@code app/jcl/CREASTMT.JCL}, and nowhere else
     */
    public record StepContract(String name, String program, boolean requirePrecedingExitCodeZero) {
    }

    /**
     * One declared job parameter: its name, its type and its value.
     *
     * <p>Exactly one exists in this migration - the interest calculator's
     * {@value BatchConfig#PARM_DATE_PARAMETER}, whose value is the {@code PARM} of
     * {@code app/jcl/INTCALC.jcl:L22}.
     *
     * @param name the parameter key, spelled exactly as the job expects it
     * @param type the declared type; {@code string} is the only type this migration declares, because the
     *     only {@code PARM} in the estate is character data
     * @param value the parameter value, taken verbatim
     */
    public record JobParameterContract(String name, String type, String value) {
        /**
         * The value, once it is confirmed to be a usable string.
         *
         * <p>The width check applies to {@value BatchConfig#PARM_DATE_PARAMETER} specifically rather than
         * to every parameter, because a width is a property of the COBOL field a value lands in and this
         * migration declares exactly one such parameter.
         *
         * @return the value
         * @throws IllegalArgumentException if the declared type is not {@code string}, the value is
         *     {@code null}, empty or blank
         */
        public String requireStringValue() {
            Assert.isTrue(PARAMETER_TYPE_STRING.equalsIgnoreCase(type),
                    () -> "Job parameter '" + name + "' declares type '" + type + "', but "
                            + PARAMETER_TYPE_STRING + " is the only type this migration declares: "
                            + "the sole PARM in the estate is character data and is concatenated "
                            + "verbatim into generated identifiers, never parsed.");
            Assert.hasText(value,
                    () -> "Job parameter '" + name + "' declares no value. It is character data "
                            + "used verbatim, so a blank value would be written through rather than "
                            + "rejected.");
            Assert.isTrue(!PARM_DATE_PARAMETER.equals(name) || value.length() == PARM_DATE_WIDTH,
                    () -> "Job parameter '" + name + "' declares a value of " + value.length()
                            + " characters ('" + value + "'), but it must be exactly "
                            + PARM_DATE_WIDTH + ". " + PARM_DATE_WIDTH_RATIONALE);
            return value;
        }
    }

    /**
     * A job-scoped DD-name binding: either an alias of a global entry, or a dataset declared inline for one
     * job alone.
     *
     * @param alias the key of the global entry this DD name resolves to, or {@code null} for an inline
     *     declaration
     * @param dsname the dataset name for an inline declaration, or - under a profile that rebinds it, such
     *     as the fixture-backed test profile - a resolvable resource location
     * @param organization the access organization as configured: an indexed cluster, an alternate-index
     *     path over a base cluster, or a sequential dataset
     * @param gdg {@code true} when the JCL names a relative generation, so a write creates a new generation
     *     rather than replacing one
     * @param recordFormat the record format transcribed from the JCL {@code DCB}
     * @param blockSize the block size transcribed from the JCL {@code DCB}, where {@code 0} is meaningful
     *     and means system-determined, exactly as the JCL asks
     * @param recordLength the fixed record width in bytes, required for an inline declaration and absent
     *     for an alias
     * @param copybook the {@code app/cpy} member defining the layout, so a width can be diffed against its
     *     {@code PICTURE} clauses without leaving the configuration
     * @param keyLength the key width in bytes, required for a keyed inline declaration and absent for a
     *     sequential one or an alias
     * @param keyOffset the key's zero-based offset within the record, for a key that does not begin at the
     *     start of it; {@code null} means offset zero
     * @param base for an alternate-index path, the key of the base entry it indexes
     * @param alternateKey for an alternate-index path, the copybook field forming the alternate key
     */
    public record JobDatasetBinding(
            String alias,
            String dsname,
            String organization,
            boolean gdg,
            String recordFormat,
            Integer blockSize,
            Integer recordLength,
            String copybook,
            Integer keyLength,
            Integer keyOffset,
            String base,
            String alternateKey) {
        public DatasetBinding resolve(String ddName, DatasetBindings global) {
            return StringUtils.hasText(alias) ? global.binding(alias) : inline(ddName);
        }

        private DatasetBinding inline(String ddName) {
            Assert.notNull(recordLength,
                    () -> "The job-scoped dataset binding for DD name '" + ddName + "' declares "
                            + "neither an alias nor a record length. An entry that is not an alias "
                            + "is a dataset of its own, and its fixed record width is what the "
                            + "codec and the output writers are built from, so it cannot be "
                            + "inferred.");
            return new DatasetBinding(dsname, organization, gdg, recordFormat, blockSize,
                    recordLength, copybook, keyLength, keyOffset, base, alternateKey);
        }
    }
}
