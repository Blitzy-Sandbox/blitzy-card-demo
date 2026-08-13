package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Collections;
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
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
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
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
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
 * Tests for {@link BatchConfig}: the module's one transaction manager, the builder seams the batch job
 * classes are assembled from, the JCL {@code COND=(0,NE)} step-gating policy, the COBOL {@code RETURN-CODE}
 * contract, and - just as load-bearing - every bean this class deliberately refuses to declare.
 */
@DisplayName("BatchConfig - the COND=(0,NE) gate, the RETURN-CODE contract and the Batch 5 seams")
class BatchConfigTest {
    private static final String STATEMENT_JOB = "statement-generation-job-a";

    private static final String INTEREST_JOB = "account-interest-calc-job";

    private static final String POSTTRAN_JOB = "transaction-validation-job";

    private static final String REPORT_JOB = "transaction-report-job";

    private static final String READCARD_JOB = "account-balance-reader-job";

    private static final String READXREF_JOB = "account-balance-update-job";

    private static final List<String> READER_JOBS = List.of(
            "account-balance-job",
            "account-balance-reader-job",
            "account-balance-update-job",
            "customer-file-reader-job");

    private static final String JCL_PARM_DATE = "2022071800";

    private static final String DATE_RANGE_DD = "DATEPARM";

    private static final String ACTIVATE_TEST_PROFILE = "spring.profiles.active=test";

    private static final Tasklet NO_OP_TASKLET = (contribution, chunkContext) -> RepeatStatus.FINISHED;

    private static final ItemReader<String> EXHAUSTED_READER = () -> null;

    private static final ItemWriter<String> DISCARDING_WRITER = chunk -> { };

    private static final List<Integer> CARRIED_RETURN_CODES = List.of(
            AbendException.RETURN_CODE_OK,
            AbendException.RETURN_CODE_WARNING,
            AbendException.RETURN_CODE_ASSUMED_FAILURE,
            AbendException.RETURN_CODE_IO_ERROR,
            AbendException.RETURN_CODE_END_OF_FILE);

    private static BatchConfig resourcelessConfig() {
        return configOver(new ResourcelessJobRepository(), new ResourcelessTransactionManager());
    }

    private static BatchConfig configOver(JobRepository repository,
            PlatformTransactionManager transactionManager) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("jobRepository", repository);
        factory.registerSingleton("transactionManager", transactionManager);
        return configOver(factory);
    }

    private static BatchConfig configOver(DefaultListableBeanFactory factory) {
        ObjectProvider<JobRepository> repositories = factory.getBeanProvider(JobRepository.class);
        ObjectProvider<PlatformTransactionManager> managers =
                factory.getBeanProvider(PlatformTransactionManager.class);
        return new BatchConfig(repositories, managers, new JobContracts(), new DatasetBindings());
    }

    private static JobExecution jobExecutionWithStepExitCodes(String... exitCodes) {
        JobExecution jobExecution = new JobExecution(1L);
        for (int index = 0; index < exitCodes.length; index++) {
            StepExecution stepExecution = jobExecution.createStepExecution("STEP" + index);
            stepExecution.setExitStatus(new ExitStatus(exitCodes[index]));
        }
        return jobExecution;
    }

    private static Throwable wrapped(Throwable innermost, int depth) {
        Throwable outermost = innermost;
        for (int link = 0; link < depth; link++) {
            outermost = new IllegalStateException("wrapper " + link, outermost);
        }
        return outermost;
    }

    private static ApplicationContextRunner documentBackedRunner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> RandomValuePropertySource
                        .addToEnvironment(context.getEnvironment()))
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(DataSourceConfig.class, BatchConfig.class)
                .withPropertyValues(ACTIVATE_TEST_PROFILE);
    }

    private static ApplicationContextRunner runnerWithBatchAutoConfiguration() {
        return documentBackedRunner()
                .withConfiguration(AutoConfigurations.of(BatchAutoConfiguration.class));
    }

    private static List<Class<?>> declaredBeanTypes() {
        return Arrays.stream(BatchConfig.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .map(Method::getReturnType)
                .toList();
    }

    @Nested
    @DisplayName("The COND=(0,NE) gate - bypass unless every preceding step returned zero")
    class CondZeroNotEqualGate {
        @Test
        @DisplayName("COMPLETED and NOOP are both return code zero")
        void completedAndNoopAreZero() {
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
        @DisplayName("the bypass exit code is not zero and not numeric, so an unmapped bypass can never "
                + "read as success")
        void theBypassExitCodeIsNeverASuccess() {
            assertThat(BatchConfig.COND_BYPASSED_EXIT_CODE).isEqualTo("COND BYPASSED");
            assertThat(BatchConfig.COND_BYPASSED_EXIT_CODE)
                    .isNotEqualTo(ExitStatus.COMPLETED.getExitCode())
                    .isNotEqualTo(ExitStatus.NOOP.getExitCode());
            assertThat(BatchConfig.returnCodeOf(new ExitStatus(BatchConfig.COND_BYPASSED_EXIT_CODE)))
                    .isEqualTo(BatchConfig.NO_JCL_RETURN_CODE)
                    .isNotEqualTo(BatchConfig.JCL_RETURN_CODE_ZERO);
        }

        @Test
        @DisplayName("the highest executed step's return code is what a bypassed job reports, because "
                + "that is what z/OS reports")
        void theHighestStepReturnCodeIsReported() {
            assertThat(BatchConfig.highestStepReturnCode(
                    jobExecutionWithStepExitCodes("COMPLETED", "4")))
                    .isEqualTo(4);
            assertThat(BatchConfig.highestStepReturnCode(
                    jobExecutionWithStepExitCodes("4", "8", "COMPLETED")))
                    .as("highest, not last")
                    .isEqualTo(8);
            assertThat(BatchConfig.highestStepReturnCode(
                    jobExecutionWithStepExitCodes("8", "4", "COMPLETED")))
                    .as("and highest, not first - the order the steps ran in does not matter")
                    .isEqualTo(8);
            assertThat(BatchConfig.highestStepReturnCode(
                    jobExecutionWithStepExitCodes("COMPLETED", "12", "4", "8")))
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("a history with no positive code reports the sentinel, never zero")
        void anIndeterminateHistoryIsNotZero() {
            for (JobExecution indeterminate : List.of(
                    jobExecutionWithStepExitCodes(),
                    jobExecutionWithStepExitCodes("COMPLETED", "NOOP"),
                    jobExecutionWithStepExitCodes("FAILED", "STOPPED"))) {
                assertThat(BatchConfig.highestStepReturnCode(indeterminate))
                        .isEqualTo(BatchConfig.NO_JCL_RETURN_CODE);
            }
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> BatchConfig.highestStepReturnCode(null))
                    .withMessageContaining("highest return code");
        }

        @Test
        @DisplayName("the listener rewrites the bypass code and leaves every other exit code alone")
        void theListenerRewritesOnlyTheBypassCode() {
            JobExecutionListener listener = resourcelessConfig().condBypassExitStatusJobListener();

            JobExecution bypassed = jobExecutionWithStepExitCodes("COMPLETED", "4");
            bypassed.setExitStatus(new ExitStatus(BatchConfig.COND_BYPASSED_EXIT_CODE,
                    "STEP010 returned 4"));
            listener.afterJob(bypassed);
            assertThat(bypassed.getExitStatus().getExitCode()).isEqualTo("4");
            assertThat(bypassed.getExitStatus().getExitDescription())
                    .as("the description says which step caused it; the rewrite must not discard it")
                    .isEqualTo("STEP010 returned 4");
            assertThat(bypassed.getStatus())
                    .as("bypassing is not failing - only the code changes")
                    .isNotEqualTo(BatchStatus.FAILED);

            for (ExitStatus untouched : List.of(ExitStatus.COMPLETED, ExitStatus.FAILED,
                    ExitStatus.NOOP, new ExitStatus("8"), ExitStatus.UNKNOWN)) {
                JobExecution execution = jobExecutionWithStepExitCodes("COMPLETED", "4");
                execution.setExitStatus(untouched);
                listener.afterJob(execution);
                assertThat(execution.getExitStatus()).isEqualTo(untouched);
            }
        }

        @Test
        @DisplayName("a null exit status is read as UNKNOWN rather than dereferenced, and is left as it "
                + "was found")
        void aNullExitStatusIsTolerated() {
            JobExecutionListener listener = resourcelessConfig().condBypassExitStatusJobListener();
            JobExecution execution = jobExecutionWithStepExitCodes("4");
            execution.setExitStatus(null);

            assertThatNoException().isThrownBy(() -> listener.afterJob(execution));

            assertThat(execution.getExitStatus()).isNull();
        }

        @Test
        @DisplayName("the bypass listener and the abend listener compose in either order")
        void theTwoListenersCompose() {
            BatchConfig config = resourcelessConfig();
            List<List<JobExecutionListener>> orders = List.of(
                    List.of(config.abendExitStatusJobListener(),
                            config.condBypassExitStatusJobListener()),
                    List.of(config.condBypassExitStatusJobListener(),
                            config.abendExitStatusJobListener()));

            for (List<JobExecutionListener> order : orders) {
                JobExecution bypassed = jobExecutionWithStepExitCodes("COMPLETED", "4");
                bypassed.setExitStatus(new ExitStatus(BatchConfig.COND_BYPASSED_EXIT_CODE));
                order.forEach(listener -> listener.afterJob(bypassed));
                assertThat(bypassed.getExitStatus().getExitCode()).isEqualTo("4");

                JobExecution abended = jobExecutionWithStepExitCodes("COMPLETED", "FAILED");
                abended.setExitStatus(ExitStatus.FAILED);
                abended.addFailureException(AbendException.withoutAbendParameters("CBSTM03A",
                        AbendException.RETURN_CODE_ASSUMED_FAILURE, "ERROR READING TRNXFILE"));
                order.forEach(listener -> listener.afterJob(abended));
                assertThat(abended.getExitStatus().getExitCode())
                        .isEqualTo(Integer.toString(AbendException.RETURN_CODE_ASSUMED_FAILURE));
            }
        }

        @Test
        @DisplayName("the two outcomes are distinct, named as a flow wires them, and neither is a "
                + "failure")
        void theTwoOutcomesAreDistinctAndNeitherIsAFailure() {
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

    @Nested
    @DisplayName("The RETURN-CODE contract - 0, 4, 8, 12 reach the exit status and the process")
    class ReturnCodeContract {
        @ParameterizedTest(name = "an abend carrying RETURN-CODE {0} maps to exit code {0}")
        @ValueSource(ints = { 0, 4, 8, 12, 16 })
        @DisplayName("the carried code is transcribed unchanged, including zero")
        void theCarriedCodeIsTranscribedUnchanged(int returnCode) {
            AbendException abend = AbendException.standard("CBACT04C", returnCode);

            assertThat(BatchConfig.exitCodeFor(abend)).isEqualTo(returnCode);
            assertThat(BatchConfig.withAbendExitCode(ExitStatus.FAILED, List.<Throwable>of(abend))
                    .getExitCode()).isEqualTo(Integer.toString(returnCode));
        }

        @ParameterizedTest(name = "the CBSTM03A shape carrying RETURN-CODE {0} maps the same way")
        @ValueSource(ints = { 0, 4, 8, 12, 16 })
        @DisplayName("an abend that set neither ABCODE nor TIMING maps identically - the "
                + "CBSTM03A.CBL:923 shape")
        void theShapeWithoutAbendParametersMapsIdentically(int returnCode) {
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
            for (int returnCode : CARRIED_RETURN_CODES) {
                ExitStatus written = BatchConfig.withAbendExitCode(ExitStatus.FAILED,
                        List.<Throwable>of(AbendException.standard("CBTRN01C", returnCode)));

                assertThat(BatchConfig.returnCodeOf(written)).isEqualTo(returnCode);
                assertThat(BatchConfig.precedingStepReturnedZero(written))
                        .isEqualTo(returnCode == BatchConfig.JCL_RETURN_CODE_ZERO);
            }
        }
    }

    @Nested
    @DisplayName("The transaction manager and the Batch 5 builder seams")
    class TransactionManagerAndBuilderSeams {
        private static final String H2_URL = "jdbc:h2:mem:batchconfigtest";

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
            assertThat(BatchConfig.returnCodeOf(stepExecution.getExitStatus())).isEqualTo(12);
            assertThat(BatchConfig.allPrecedingStepsReturnedZero(jobExecution)).isFalse();
        }
    }

    @Nested
    @DisplayName("StopSignal - the between-record cancellation probe (N-02)")
    class TheStopSignal {
        private static final String STEP_NAME = "STEP040";

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

                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
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

            assertThat(BatchConfig.returnCodeOf(stepExecution.getExitStatus()))
                    .isEqualTo(BatchConfig.NO_JCL_RETURN_CODE);
            assertThat(BatchConfig.allPrecedingStepsReturnedZero(jobExecution)).isFalse();
        }

        @Test
        @DisplayName("a stop already pending when the step begins is caught by the framework itself, "
                + "before the tasklet is invoked - so the two mechanisms compose")
        void aStopPendingAtEntryIsCaughtByTheFramework() throws JobInterruptedException {
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

    @Nested
    @DisplayName("The contract it refuses to declare - the absences are load-bearing")
    class TheContractItRefusesToDeclare {
        @Test
        @DisplayName("nine bean methods, and these exactly - so nothing else can have crept in")
        void exactlyNineBeansAndTheseExactly() {
            assertThat(declaredBeanTypes()).containsExactlyInAnyOrder(
                    PlatformTransactionManager.class,
                    JobExecutionDecider.class,
                    StepExecutionListener.class,
                    JobExecutionListener.class,
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
            assertThat(BatchConfig.class.getAnnotation(EnableBatchProcessing.class)).isNull();
            assertThat(DefaultBatchConfiguration.class.isAssignableFrom(BatchConfig.class)).isFalse();
            assertThat(BatchConfig.class.getSuperclass()).isEqualTo(Object.class);
            assertThat(BatchConfig.class.getInterfaces()).isEmpty();
        }

        @Test
        @DisplayName("the removed Batch 4 builder factories really are absent from this classpath")
        void theRemovedBatch4BuilderFactoriesAreAbsent() {
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
                assertThat(declaredBy).isNotEqualTo(thisConfiguration[0]);
            });
        }
    }

    @Nested
    @DisplayName("The JCL job launcher - a submission in, a RETURN-CODE out")
    class TheJclJobLauncher {
        private static final String JOB_BEAN_NAME = "accountBalanceJob";

        private static final String SECOND_JOB_BEAN_NAME = "customerFileReaderJob";

        private final List<Integer> terminatedWith = new ArrayList<>();

        private final BatchConfig.ProcessTerminator recordingTerminator = terminatedWith::add;

        private BatchConfig.JclJobLauncher launcherReporting(ExitStatus exitStatus) {
            return launcherReporting(exitStatus, JOB_BEAN_NAME);
        }

        private BatchConfig.JclJobLauncher launcherReporting(ExitStatus exitStatus, String jobName) {
            JobLauncher launcher = (submitted, parameters) -> {
                JobExecution execution = new JobExecution(1L, parameters);
                execution.setExitStatus(exitStatus);
                return execution;
            };
            return launcherOver(configWithContracts(), factoryPublishing(jobName), launcher,
                    noHistory(), jobName);
        }

        private BatchConfig.JclJobLauncher launcherOver(BatchConfig config,
                ListableBeanFactory beanFactory, JobLauncher launcher, JobExplorer history,
                String jobName) {
            return new BatchConfig.JclJobLauncher(beanFactory,
                    providerOf(JobLauncher.class, launcher), providerOf(JobExplorer.class, history),
                    config, jobName, recordingTerminator);
        }

        private ListableBeanFactory factoryPublishing(String... jobNames) {
            DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
            for (String jobName : jobNames) {
                Job job = mock(Job.class);
                when(job.getName()).thenReturn(jobName);
                factory.registerSingleton(jobName, job);
            }
            return factory;
        }

        private JobExplorer noHistory() {
            JobExplorer explorer = mock(JobExplorer.class);
            when(explorer.getLastJobInstance(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
            return explorer;
        }

        private BatchConfig configWithContracts() {
            DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
            factory.registerSingleton("jobRepository", new ResourcelessJobRepository());
            factory.registerSingleton("transactionManager", new ResourcelessTransactionManager());
            JobContracts contracts = new JobContracts();
            contracts.put("account-balance-job", new JobContract("CBACT01C", List.of(),
                    List.of(new StepContract("STEP05", "CBACT01C", false)), null, Map.of()));
            contracts.put("customer-file-reader-job", new JobContract("CBCUS01C", List.of(),
                    List.of(new StepContract("STEP05", "CBCUS01C", false)), null, Map.of()));
            return new BatchConfig(factory.getBeanProvider(JobRepository.class),
                    factory.getBeanProvider(PlatformTransactionManager.class), contracts,
                    new DatasetBindings());
        }

        private <T> ObjectProvider<T> providerOf(Class<T> type, T singleton) {
            DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
            factory.registerSingleton(type.getSimpleName(), singleton);
            return factory.getBeanProvider(type);
        }

        @ParameterizedTest(name = "an exit status of {0} leaves the process exit code 0")
        @ValueSource(strings = { "COMPLETED", "NOOP", "0" })
        @DisplayName("a job that returned zero completes quietly - nothing is thrown, the exit code "
                + "stays zero, and the process is ended with it")
        void aZeroReturnCodeThrowsNothing(final String exitCode) throws Exception {
            BatchConfig.JclJobLauncher launcher = launcherReporting(new ExitStatus(exitCode));

            launcher.run(new DefaultApplicationArguments());

            assertThat(launcher.getExitCode()).isZero();
            assertThat(launcher.jobName()).isEqualTo(JOB_BEAN_NAME);
            assertThat(terminatedWith).containsExactly(0);
        }

        @ParameterizedTest(name = "RETURN-CODE {0} reaches the process as {0}")
        @ValueSource(strings = { "4", "8", "12" })
        @DisplayName("a non-zero RETURN-CODE is raised as an ExitCodeGenerator, so the shell sees the "
                + "number the COBOL set")
        void aNonZeroReturnCodeBecomesTheProcessExitCode(final String exitCode) {
            BatchConfig.JclJobLauncher launcher = launcherReporting(new ExitStatus(exitCode));

            assertThatExceptionOfType(BatchConfig.JclReturnCodeException.class)
                    .isThrownBy(() -> launcher.run(new DefaultApplicationArguments()))
                    .withMessageContaining("RETURN-CODE " + exitCode)
                    .satisfies(raised -> assertThat(raised.getExitCode())
                            .isEqualTo(Integer.parseInt(exitCode)));
            assertThat(launcher.getExitCode()).isEqualTo(Integer.parseInt(exitCode));
            assertThat(terminatedWith).isEmpty();
        }

        @Test
        @DisplayName("a framework exit status that carries no return code is still a failure, and is "
                + "reported as one")
        void aFrameworkFailureIsStillAFailure() {
            BatchConfig.JclJobLauncher launcher = launcherReporting(ExitStatus.FAILED);

            assertThatExceptionOfType(BatchConfig.JclReturnCodeException.class)
                    .isThrownBy(() -> launcher.run(new DefaultApplicationArguments()))
                    .withMessageContaining("exit status 'FAILED'");
            assertThat(terminatedWith).isEmpty();
        }

        @Test
        @DisplayName("the configured name selects one job out of several published under the same type")
        void theConfiguredNameSelectsOneJobOutOfSeveral() throws Exception {
            List<Job> submitted = new ArrayList<>();
            ListableBeanFactory published = factoryPublishing(JOB_BEAN_NAME, SECOND_JOB_BEAN_NAME);
            JobLauncher recording = (job, parameters) -> {
                submitted.add(job);
                JobExecution execution = new JobExecution(1L, parameters);
                execution.setExitStatus(ExitStatus.COMPLETED);
                return execution;
            };

            launcherOver(configWithContracts(), published, recording, noHistory(), SECOND_JOB_BEAN_NAME)
                    .run(new DefaultApplicationArguments());

            assertThat(submitted).hasSize(1);
            assertThat(submitted.get(0).getName()).isEqualTo(SECOND_JOB_BEAN_NAME);
        }

        @Test
        @DisplayName("resolving by type is what would fail, which is why the name is used - asserted "
                + "rather than assumed")
        void resolvingByTypeIsWhatWouldFail() {
            DefaultListableBeanFactory published =
                    (DefaultListableBeanFactory) factoryPublishing(JOB_BEAN_NAME, SECOND_JOB_BEAN_NAME);

            assertThatExceptionOfType(NoUniqueBeanDefinitionException.class)
                    .isThrownBy(() -> published.getBeanProvider(Job.class).getObject());
        }

        @Test
        @DisplayName("a name no job is published under is refused, and the diagnostic lists the jobs "
                + "that are")
        void anUnpublishedJobNameIsRefused() {
            BatchConfig.JclJobLauncher launcher = launcherOver(configWithContracts(),
                    factoryPublishing(JOB_BEAN_NAME, SECOND_JOB_BEAN_NAME),
                    (job, parameters) -> new JobExecution(1L), noHistory(), "accountBalanceJobs");

            assertThatIllegalStateException()
                    .isThrownBy(() -> launcher.run(new DefaultApplicationArguments()))
                    .withMessageContaining("no Job bean is published under that name")
                    .withMessageContaining(JOB_BEAN_NAME)
                    .withMessageContaining(SECOND_JOB_BEAN_NAME);
            assertThat(terminatedWith).isEmpty();
        }

        @Test
        @DisplayName("the submitted parameters are the job's declared contract plus the execution "
                + "identity, and nothing else")
        void theSubmittedParametersAreTheContractPlusTheRunIdentity() throws Exception {
            List<JobParameters> submitted = new ArrayList<>();
            JobLauncher recording = (requested, parameters) -> {
                submitted.add(parameters);
                JobExecution execution = new JobExecution(1L, parameters);
                execution.setExitStatus(ExitStatus.COMPLETED);
                return execution;
            };

            launcherOver(configWithContracts(), factoryPublishing(JOB_BEAN_NAME), recording,
                    noHistory(), JOB_BEAN_NAME)
                    .run(new DefaultApplicationArguments("--ignored=value"));

            assertThat(submitted).hasSize(1);
            assertThat(submitted.get(0).getParameters().keySet())
                    .containsExactly(BatchConfig.RUN_IDENTITY_PARAMETER);
            assertThat(submitted.get(0).getLong(BatchConfig.RUN_IDENTITY_PARAMETER)).isEqualTo(1L);
        }

        @Test
        @DisplayName("a job with no declared contract is refused, because a job's parameters ARE its "
                + "contract")
        void aJobWithNoContractIsRefused() {
            BatchConfig.JclJobLauncher launcher = launcherOver(configWithContracts(),
                    factoryPublishing("someUndeclaredJob"),
                    (job, parameters) -> new JobExecution(1L), noHistory(), "someUndeclaredJob");

            assertThatIllegalStateException()
                    .isThrownBy(() -> launcher.run(new DefaultApplicationArguments()))
                    .withMessageContaining("no entry under carddemo.jobs declares it");
        }

        @Test
        @DisplayName("the launcher needs every collaborator, because each one is load-bearing")
        void theLauncherNeedsAllItsCollaborators() {
            BatchConfig config = configWithContracts();
            ListableBeanFactory jobs = factoryPublishing(JOB_BEAN_NAME);
            ObjectProvider<JobLauncher> launchers =
                    providerOf(JobLauncher.class, (job, parameters) -> new JobExecution(1L));
            ObjectProvider<JobExplorer> explorers = providerOf(JobExplorer.class, noHistory());

            assertThatNullPointerException().isThrownBy(() -> new BatchConfig.JclJobLauncher(
                    null, launchers, explorers, config, JOB_BEAN_NAME, recordingTerminator));
            assertThatNullPointerException().isThrownBy(() -> new BatchConfig.JclJobLauncher(
                    jobs, null, explorers, config, JOB_BEAN_NAME, recordingTerminator));
            assertThatNullPointerException().isThrownBy(() -> new BatchConfig.JclJobLauncher(
                    jobs, launchers, null, config, JOB_BEAN_NAME, recordingTerminator));
            assertThatNullPointerException().isThrownBy(() -> new BatchConfig.JclJobLauncher(
                    jobs, launchers, explorers, null, JOB_BEAN_NAME, recordingTerminator));
            assertThatIllegalArgumentException().isThrownBy(() -> new BatchConfig.JclJobLauncher(
                    jobs, launchers, explorers, config, " ", recordingTerminator));
            assertThatNullPointerException().isThrownBy(() -> new BatchConfig.JclJobLauncher(
                    jobs, launchers, explorers, config, JOB_BEAN_NAME, null));
        }

        @Test
        @DisplayName("the production terminator needs a context to close before it ends the process")
        void theProductionTerminatorNeedsAContext() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new BatchConfig.SpringApplicationExitTerminator(null))
                    .withMessageContaining("application context is required");
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

        @Test
        @DisplayName("finding a job's contract needs a name")
        void findingAContractNeedsAJobName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> configWithContracts().contractKeyOf(null))
                    .withMessageContaining("job name is required");
        }
    }

    @Nested
    @DisplayName("Resubmission - the same deck submitted twice is two instances, not a refusal")
    class Resubmission {
        private final List<Integer> terminatedWith = new ArrayList<>();

        @Configuration(proxyBeanMethods = false)
        static class TwoPublishedJobs {
            @Bean
            Job accountBalanceJob(BatchConfig batchConfig) {
                return batchConfig.job("accountBalanceJob")
                        .start(batchConfig.taskletStep("STEP05", NO_OP_TASKLET).build())
                        .build();
            }

            @Bean
            Job customerFileReaderJob(BatchConfig batchConfig) {
                return batchConfig.job("customerFileReaderJob")
                        .start(batchConfig.taskletStep("STEP05", NO_OP_TASKLET).build())
                        .build();
            }
        }

        @Test
        @DisplayName("two sequential submissions of the same job are two instances, numbered 1 then 2")
        void twoSequentialSubmissionsAreTwoInstances() {
            runnerWithBatchAutoConfiguration()
                    .withUserConfiguration(TwoPublishedJobs.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBeanNamesForType(Job.class)).hasSize(2);

                        JobExplorer history = context.getBean(JobExplorer.class);
                        BatchConfig.JclJobLauncher launcher = new BatchConfig.JclJobLauncher(
                                context.getBeanFactory(),
                                context.getBeanProvider(JobLauncher.class),
                                context.getBeanProvider(JobExplorer.class),
                                context.getBean(BatchConfig.class),
                                "accountBalanceJob",
                                terminatedWith::add);

                        launcher.run(new DefaultApplicationArguments());
                        launcher.run(new DefaultApplicationArguments());

                        assertThat(terminatedWith).containsExactly(0, 0);
                        List<JobInstance> instances =
                                history.getJobInstances("accountBalanceJob", 0, 10);
                        assertThat(instances).hasSize(2);
                        assertThat(instances.stream()
                                .map(instance -> history.getLastJobExecution(instance))
                                .map(execution -> execution.getJobParameters()
                                        .getLong(BatchConfig.RUN_IDENTITY_PARAMETER))
                                .toList())
                                .containsExactlyInAnyOrder(1L, 2L);
                        assertThat(instances.stream().map(JobInstance::getId).distinct().toList())
                                .hasSize(2);
                    });
        }

        @Test
        @DisplayName("each job's identity is its own: submitting one does not number the other")
        void eachJobCarriesItsOwnIdentity() {
            runnerWithBatchAutoConfiguration()
                    .withUserConfiguration(TwoPublishedJobs.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed();

                        for (String jobName : List.of("accountBalanceJob", "accountBalanceJob",
                                "customerFileReaderJob")) {
                            new BatchConfig.JclJobLauncher(context.getBeanFactory(),
                                    context.getBeanProvider(JobLauncher.class),
                                    context.getBeanProvider(JobExplorer.class),
                                    context.getBean(BatchConfig.class), jobName, terminatedWith::add)
                                    .run(new DefaultApplicationArguments());
                        }

                        JobExplorer history = context.getBean(JobExplorer.class);
                        assertThat(history.getJobInstances("accountBalanceJob", 0, 10)).hasSize(2);
                        assertThat(history.getLastJobExecution(
                                history.getLastJobInstance("customerFileReaderJob"))
                                .getJobParameters().getLong(BatchConfig.RUN_IDENTITY_PARAMETER))
                                .isEqualTo(1L);
                        assertThat(terminatedWith).containsExactly(0, 0, 0);
                    });
        }
    }

    @Nested
    @DisplayName("The parameter allow-list - the contract, the execution identity, and nothing else")
    class TheParameterAllowList {
        private BatchConfig shippedContracts() {
            DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
            factory.registerSingleton("jobRepository", new ResourcelessJobRepository());
            factory.registerSingleton("transactionManager", new ResourcelessTransactionManager());
            JobContracts contracts = new JobContracts();
            contracts.put("account-balance-job", new JobContract("CBACT01C", List.of(),
                    List.of(new StepContract("STEP05", "CBACT01C", false)), null, Map.of()));
            contracts.put("account-interest-calc-job", new JobContract("CBACT04C",
                    List.of(new JobParameterContract(BatchConfig.PARM_DATE_PARAMETER, "string",
                            "2022071800")),
                    List.of(new StepContract("STEP15", "CBACT04C", false)), null, Map.of()));
            return new BatchConfig(factory.getBeanProvider(JobRepository.class),
                    factory.getBeanProvider(PlatformTransactionManager.class), contracts,
                    new DatasetBindings());
        }

        @Test
        @DisplayName("a parameterless job accepts the execution identity alone")
        void aParameterlessJobAcceptsTheIdentityAlone() {
            JobParametersValidator validator =
                    shippedContracts().jclParametersValidator("accountBalanceJob");

            assertThatNoException().isThrownBy(() -> validator.validate(new JobParametersBuilder()
                    .addLong(BatchConfig.RUN_IDENTITY_PARAMETER, 7L).toJobParameters()));
            assertThatNoException().isThrownBy(() -> validator.validate(new JobParameters()));
            assertThatNoException().isThrownBy(() -> validator.validate(null));
        }

        @Test
        @DisplayName("a parameterless job refuses an undeclared parameter, naming it")
        void aParameterlessJobRefusesAnUndeclaredParameter() {
            JobParametersValidator validator =
                    shippedContracts().jclParametersValidator("accountBalanceJob");

            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validator.validate(new JobParametersBuilder()
                            .addLong(BatchConfig.RUN_IDENTITY_PARAMETER, 1L)
                            .addString("parmDate", "2022071800")
                            .toJobParameters()))
                    .withMessageContaining("Undeclared: [parmDate]")
                    .withMessageContaining("account-balance-job");
        }

        @Test
        @DisplayName("the parameterised job accepts its declared parmDate beside the identity")
        void theParameterisedJobAcceptsItsDeclaredParameter() {
            JobParametersValidator validator =
                    shippedContracts().jclParametersValidator("accountInterestCalcJob");

            assertThatNoException().isThrownBy(() -> validator.validate(new JobParametersBuilder()
                    .addLong(BatchConfig.RUN_IDENTITY_PARAMETER, 3L)
                    .addString(BatchConfig.PARM_DATE_PARAMETER, "2022071800")
                    .toJobParameters()));
        }

        @Test
        @DisplayName("the parameterised job refuses a submission that omits its parmDate")
        void theParameterisedJobRefusesAnOmittedParameter() {
            JobParametersValidator validator =
                    shippedContracts().jclParametersValidator("accountInterestCalcJob");

            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validator.validate(new JobParametersBuilder()
                            .addLong(BatchConfig.RUN_IDENTITY_PARAMETER, 1L).toJobParameters()))
                    .withMessageContaining("Missing: [" + BatchConfig.PARM_DATE_PARAMETER + "]");
        }

        @Test
        @DisplayName("the parameterised job still applies the parmDate width rules on top of the "
                + "allow-list")
        void theParameterisedJobStillAppliesTheWidthRules() {
            JobParametersValidator validator =
                    shippedContracts().jclParametersValidator("accountInterestCalcJob");

            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validator.validate(new JobParametersBuilder()
                            .addLong(BatchConfig.RUN_IDENTITY_PARAMETER, 1L)
                            .addString(BatchConfig.PARM_DATE_PARAMETER, "202207180")
                            .toJobParameters()));
        }

        @Test
        @DisplayName("an undeclared job is refused rather than allowed everything")
        void anUndeclaredJobIsRefused() {
            JobParametersValidator validator =
                    shippedContracts().jclParametersValidator("someUndeclaredJob");

            assertThatIllegalStateException()
                    .isThrownBy(() -> validator.validate(new JobParameters()))
                    .withMessageContaining("no entry under carddemo.jobs declares it");
        }

        @Test
        @DisplayName("the validator needs a configuration and a job name")
        void theValidatorNeedsItsCollaborators() {
            assertThatNullPointerException().isThrownBy(() ->
                    new BatchConfig.JclJobParametersValidator(null, "accountBalanceJob"));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new BatchConfig.JclJobParametersValidator(shippedContracts(), " "));
        }

        @Test
        @DisplayName("every job the seam builds carries the allow-list, so no job author can forget it")
        void everyJobBuiltThroughTheSeamCarriesTheAllowList() {
            BatchConfig config = shippedContracts();

            Job job = config.job("accountBalanceJob")
                    .start(config.taskletStep("STEP05", NO_OP_TASKLET).build())
                    .build();

            assertThat(job.getJobParametersValidator())
                    .isInstanceOf(BatchConfig.JclJobParametersValidator.class);
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

                assertThat(config.datasetBinding(INTEREST_JOB, "TRANSACT").recordLength())
                        .isEqualTo(350);
                assertThat(config.datasetBinding(INTEREST_JOB, "TRANSACT").copybook())
                        .isEqualTo("CVTRA05Y");
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
            documentBackedRunner().run(context -> {
                BatchConfig config = context.getBean(BatchConfig.class);

                assertThat(config.requireSameDataset(READCARD_JOB, "CARDFILE", "CARDDAT")).isNotBlank();
                assertThat(config.requireSameDataset(READXREF_JOB, "XREFFILE", "CCXREF")).isNotBlank();
                assertThat(config.requireSameDataset(INTEREST_JOB, "TCATBALF", "TCATBALF")).isNotBlank();
                assertThat(config.requireSameDataset(INTEREST_JOB, "ACCTFILE", "ACCTDAT")).isNotBlank();
                assertThat(config.requireSameDataset(INTEREST_JOB, "XREFFILE", "CCXREF")).isNotBlank();
                assertThat(config.requireSameDataset(INTEREST_JOB, "XREFFIL1", "CXACAIX")).isNotBlank();
                assertThat(config.requireSameDataset(INTEREST_JOB, "TRANSACT", "SYSTRAN")).isNotBlank();
                assertThat(config.requireSameDataset(REPORT_JOB, "CARDXREF", "CCXREF")).isNotBlank();
                assertThat(config.requireSameDataset(REPORT_JOB, "TRANTYPE", "TRANTYPE")).isNotBlank();
                assertThat(config.requireSameDataset(REPORT_JOB, "TRANCATG", "TRANCATG")).isNotBlank();
            });
        }

        @Test
        @DisplayName("requireSteps accepts the shipped sequence of every job and refuses any deviation "
                + "from it")
        void requireStepsGuardsTheWholeTuple() {
            documentBackedRunner().run(context -> {
                BatchConfig config = context.getBean(BatchConfig.class);

                JobContracts.REQUIRED_STEPS.forEach((jobKey, steps) ->
                        assertThat(config.requireSteps(jobKey, steps, "the JCL"))
                                .as("%s ships the sequence it requires", jobKey)
                                .isEqualTo(steps));

                List<StepContract> shipped = JobContracts.REQUIRED_STEPS.get(STATEMENT_JOB);
                List<StepContract> dropped = shipped.subList(0, 4);
                List<StepContract> added = new ArrayList<>(shipped);
                added.add(new StepContract("STEP050", "CBSTM03A", true));
                List<StepContract> reordered = new ArrayList<>(shipped);
                Collections.swap(reordered, 0, 2);
                List<StepContract> rePointed = new ArrayList<>(shipped);
                rePointed.set(1, new StepContract("STEP010", "IEFBR14", false));
                List<StepContract> misgated = new ArrayList<>(shipped);
                misgated.set(4, new StepContract("STEP040", "CBSTM03A", false));

                for (List<StepContract> deviation
                        : List.of(dropped, added, reordered, rePointed, misgated)) {
                    assertThatIllegalStateException()
                            .isThrownBy(() -> config.requireSteps(STATEMENT_JOB, deviation,
                                    "app/jcl/CREASTMT.JCL"))
                            .withMessageContaining("does not declare the step sequence of "
                                    + "app/jcl/CREASTMT.JCL")
                            .withMessageContaining("configured: [DELDEF01/IDCAMS")
                            .withMessageContaining("refuses to build the job rather than run it");
                }
            });
        }

        @Test
        @DisplayName("two keys naming different datasets are refused, and the message names both")
        void divergingKeysAreRefused() {
            documentBackedRunner().run(context -> {
                BatchConfig config = context.getBean(BatchConfig.class);

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
