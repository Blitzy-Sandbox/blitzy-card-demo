/*
 * ******************************************************************
 * Program     : BatchConfigTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test (batch wiring)
 * Function    : Proves that the batch wiring closes the runtime gap it
 *               exists to close - exactly one repository backed dataset
 *               binding per CBSTM03B DD whose rendered record is the
 *               declared width and round-trips through the statement
 *               processor's own parser - and that it does NOT re-register
 *               the report processor, which carries its own @Component
 *               @StepScope and would collide on the derived bean name.
 * Source      : app/cbl/CBSTM03B.CBL:L58-L97 (four DDs, access modes,
 *               key widths) @ 7756d89
 *             : app/cpy/CVACT01Y.cpy, CVCUS01Y.cpy, CVACT03Y.cpy,
 *               COSTM01.CPY (the four record layouts) @ 7756d89
 *             : app/cbl/CBTRN03C.cbl:L127-L137 (WS-REPORT-VARS)
 *               @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.batch.jobs.StatementGenerationJob;
import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.batch.readers.AccountReader;
import com.cardemo.batch.readers.CardCrossReferenceReader;
import com.cardemo.batch.readers.CardReader;
import com.cardemo.batch.readers.CustomerReader;
import com.cardemo.config.BatchConfig;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.entity.Transaction;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRepository;
import com.cardemo.service.report.ReportSubmissionService.JobSubmissionMessage;
import com.cardemo.service.report.ReportSubmissionService;
import com.cardemo.service.shared.FileService;
import com.cardemo.service.shared.FileStatusMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import io.awspring.cloud.sqs.listener.Visibility;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepLocator;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.batch.BatchDataSourceScriptDatabaseInitializer;
import org.springframework.boot.autoconfigure.batch.BatchProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Unit tests for {@link BatchConfig}.
 *
 * <p>The class under test is a configuration class, so it is exercised the way a configuration class should
 * be: by calling its bean methods directly with mocked collaborators. That keeps the assertions about the
 * contract - one binding per DD, exact record widths, keyset positioning - rather than about Spring.
 *
 * <p>The report processor group is the one exception, and deliberately so. The {@code @Bean @StepScope}
 * factory that once stood in {@code BatchConfig} was removed because
 * {@link com.cardemo.batch.processors.TransactionReportProcessor} is annotated
 * {@code @Component @StepScope} and derives the same bean name, which
 * {@code spring.main.allow-bean-definition-overriding: false} turns into a startup failure. Those tests
 * therefore assert the absence of the factory and construct the processor directly, which is exactly how a
 * step-scoped component is unit tested.
 *
 * <p>The bean inventory this class pins is the four dataset bindings, the framework metadata initialiser and
 * the report queue listener. It also pins the <em>absence</em> of any {@code Job}, {@code Step}, {@code Flow}
 * or {@code JobExecutionDecider} bean, because the six configuration classes in
 * {@code com.cardemo.batch.jobs} are the designated definition sites for those and a second definition would
 * abort startup - and, since finding CFG-003, the absence of any {@code JobExecutionListener} bean, which
 * nothing in the framework would ever collect onto a job.
 */
@DisplayName("BatchConfig - four CBSTM03B dataset bindings, batch metadata readiness, and no job bean")
class BatchConfigTest {

    /** A window size small enough that the tests can observe more than one window. */
    private static final int WINDOW_SIZE = 2;

    /** The sixteen-character card number of the fixture rows. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The bucket the projected work object lives in; any non-blank value serves, since the store is mocked. */
    private static final String WORK_BUCKET = "carddemo-batch-output";

    /**
     * The concrete key {@code STEP010} publishes, as a test double for a real generation key.
     *
     * <p>The shape matters only in that it is the key the binding must take from the job execution context
     * rather than resolve for itself: "the latest object" would hand a concurrent run the wrong generation.
     */
    private static final String WORK_OBJECT_KEY = "gdg/creastmt-work/G0001V00.ps";

    /** A second, strictly greater card number, so ordering is observable. */
    private static final String HIGHER_CARD_NUMBER = "4111111111111112";

    /** {@code XREF-CUST-ID PIC 9(09)}. */
    private static final Long CUSTOMER_ID = Long.valueOf(11L);

    /** {@code XREF-ACCT-ID PIC 9(11)}. */
    private static final Long ACCOUNT_ID = Long.valueOf(1L);

    /** The ten-character reporting period bounds the report step is started with. */
    private static final String START_DATE = "2022-01-01";

    /** The inclusive upper bound of the reporting period. */
    private static final String END_DATE = "2022-12-31";

    /**
     * Switches the stubbed metadata probe from answering to refusing.
     *
     * <p>It stands in for the two states a deployment can be in: the six framework tables present and
     * readable by the runtime role, or one of them missing because the DDL role never created it. The second
     * state is the one that used to be silent.
     */
    private static final String UNREADABLE_METADATA_PROPERTY = "carddemo.test.metadata-probe-refuses";

    /**
     * Names the product the stubbed driver reports, deciding whether the privilege probes run.
     *
     * <p>Left unset, the stubbed connection supplies no metadata at all, which is the shape every other test
     * in this class exercises and which must skip the privilege probes rather than fail startup on a driver
     * that declined to describe itself.
     */
    private static final String METADATA_PRODUCT_PROPERTY = "carddemo.test.metadata-product";

    /**
     * Names one privilege, or one object, the stubbed runtime role does not hold.
     *
     * <p>This is the state finding BAT-001 is about: a schema whose six tables exist and read cleanly under
     * the runtime role, and which that role cannot write. The old check passed there and the first launch of
     * the batch window failed.
     */
    private static final String WITHHELD_PRIVILEGE_PROPERTY = "carddemo.test.privilege-withheld";

    /**
     * Every privilege statement the readiness check issues: six tables and three sequences, three privileges
     * apiece.
     */
    private static final int PRIVILEGE_PROBE_COUNT = (6 * 3) + (3 * 3);

    /** The product name that unlocks the privilege probes. */
    private static final String POSTGRESQL = "PostgreSQL";

    /** The class under test, constructed with the small window size. */
    private final BatchConfig batchConfig = new BatchConfig(WINDOW_SIZE);

    @Nested
    @DisplayName("Construction validates its one configuration value")
    class Construction {

        @Test
        @DisplayName("a non-positive window size is rejected, because it would spin or end prematurely")
        void nonPositiveWindowSizeIsRejected() {
            assertThatIllegalArgumentException().isThrownBy(() -> new BatchConfig(0))
                    .withMessageContaining("carddemo.batch.dataset-window-size");
            assertThatIllegalArgumentException().isThrownBy(() -> new BatchConfig(-1));
        }

        @Test
        @DisplayName("a positive window size is accepted")
        void positiveWindowSizeIsAccepted() {
            assertThat(new BatchConfig(1)).isNotNull();
        }
    }

    @Nested
    @DisplayName("The report processor is owned by its job, and this class must not register it")
    class ReportProcessorRegistration {

        /**
         * Builds the report processor the way a step-scoped component is built: directly, with plain
         * strings where the framework binds job parameters.
         *
         * @return a fresh processor with its per-run state at the source's initial values
         */
        private TransactionReportProcessor newProcessor() {
            return new TransactionReportProcessor(
                    mock(TransactionRepository.class),
                    mock(CardCrossReferenceRepository.class),
                    mock(TransactionTypeRepository.class),
                    mock(TransactionCategoryRepository.class),
                    new FileStatusMapper(),
                    START_DATE,
                    END_DATE);
        }

        @Test
        @DisplayName("BatchConfig declares no processor factory, because the name would collide")
        void batchConfigDeclaresNoProcessorFactory() {
            // A @Bean @StepScope transactionReportProcessor factory stood in BatchConfig and was removed,
            // originally because TransactionReportProcessor was annotated @Component @StepScope and the two
            // definitions collided on the name "transactionReportProcessor" under
            // spring.main.allow-bean-definition-overriding: false. Under finding F-008 that annotation is
            // gone - TransactionReportJob constructs the processor itself and is its sole owner - so the
            // collision no longer exists, and this assertion now guards the ownership model instead: no
            // factory here may reintroduce a second provenance for a type the job owns, because that would
            // move the constructor's DATEPARM validation behind a scoped proxy where it surfaces as a
            // BeanCreationException rather than as the step's own FatalProcessingException.
            assertThat(Arrays.stream(BatchConfig.class.getDeclaredMethods()).map(Method::getName))
                    .as("BatchConfig owns the four FileService.Dataset bindings, the framework metadata "
                            + "initialiser and the report queue listener; the container-owned readers, "
                            + "processors and writers under batch/** register by @Component, and the report "
                            + "processor and backup reader are owned by TransactionReportJob")
                    .doesNotContain("transactionReportProcessor", "transactionBackupReader");
            assertThat(Arrays.stream(BatchConfig.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Bean.class))
                    .map(Method::getReturnType))
                    .as("the bean types this class contributes are the DD binding, the framework metadata "
                            + "initialiser, the report queue listener, the F-020 verification topology and the "
                            + "batch span-naming post-processor, and nothing that any batch/** component "
                            + "already registers for itself. Findings M-01 and CFG-003: JobExecutionListener "
                            + "is absent by design - every job registers its own on its own JobBuilder, "
                            + "because a listener bean declared here is applied to no job at all. The "
                            + "BeanPostProcessor is the one bean type here that is NOT a batch artefact, and "
                            + "it is here rather than in observability/** for two reasons: the package "
                            + "inventory that AAP 0.5.1.9 fixes at three classes plus its documentation, and "
                            + "the fact that what it configures is a job property. See BatchJobSpanNamingTest")
                    .containsOnly(FileService.Dataset.class,
                            BatchDataSourceScriptDatabaseInitializer.class,
                            BatchConfig.ReportJobQueueListener.class,
                            Step.class, Job.class, BeanPostProcessor.class);
            // Findings M-01 and CFG-003: a shared JobExecutionListener bean stood among those types and has
            // been removed. It registered nowhere - neither Spring Batch nor Boot collects listener beans onto
            // jobs, and every job attaches its own job-level listener with .listener(...) - so it was dead
            // weight that read as live behaviour. This assertion is what stops it coming back: a reinstated
            // listener bean fails here, and the remedy is to attach the behaviour to a job rather than to
            // publish an uncollected bean.
            assertThat(Arrays.stream(BatchConfig.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Bean.class))
                    .map(Method::getReturnType))
                    .as("a JobExecutionListener bean here would be collected by nothing and run on no job")
                    .doesNotContain(JobExecutionListener.class);
        }

        @Test
        @DisplayName("the only Job and Step beans declared here are the five F-020 verification factories, "
                + "and no Flow or decider is declared here at all")
        void theOnlyJobAndStepBeansHereAreTheVerificationTopology() {
            // This assertion may not read "no Job, Step or Flow bean is declared here", because that premise
            // does not hold: app/jcl/READACCT.jcl, READCARD.jcl, READXREF.jcl and READCUST.jcl each EXEC one
            // read-only program, the specification maps those four members onto verification *steps* rather
            // than onto four more jobs, and InventoryCountGateTest pins com.cardemo.batch.jobs at exactly six
            // classes. So the four steps - and the one job that gives them something launchable, because a
            // Step cannot be launched on its own - are declared here by design.
            //
            // The assertion below is stricter than a return-type prohibition rather than looser: instead of
            // forbidding four return types it pins the exact set of Job and Step bean methods by name, so
            // a sixth one appearing here still fails, and it keeps the outright prohibition on Flow and
            // JobExecutionDecider, which remain the exclusive property of com.cardemo.batch.jobs.
            final List<Method> beanMethods = Arrays.stream(BatchConfig.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Bean.class))
                    .toList();

            assertThat(beanMethods.stream().map(Method::getReturnType))
                    .as("flow composition and step gating stay in com.cardemo.batch.jobs: none of the four "
                            + "verification members carries a COND parameter, so there is nothing to gate")
                    .doesNotContain(Flow.class, JobExecutionDecider.class);
            assertThat(beanMethods.stream()
                    .filter(method -> Step.class.equals(method.getReturnType()))
                    .map(Method::getName))
                    .as("one step per read-only JCL member of AAP F-020, and no other step declared here")
                    .containsExactlyInAnyOrder(
                            BatchConfig.READ_ACCOUNT_STEP_BEAN_NAME,
                            BatchConfig.READ_CARD_STEP_BEAN_NAME,
                            BatchConfig.READ_CROSS_REFERENCE_STEP_BEAN_NAME,
                            BatchConfig.READ_CUSTOMER_STEP_BEAN_NAME);
            assertThat(beanMethods.stream()
                    .filter(method -> Job.class.equals(method.getReturnType()))
                    .map(Method::getName))
                    .as("exactly one job is declared here, and only because a Step with no job cannot be "
                            + "launched; every other job identity belongs to com.cardemo.batch.jobs")
                    .containsExactly(BatchConfig.DATASET_VERIFICATION_JOB_BEAN_NAME);
        }

        @Test
        @DisplayName("the processor is a bean nowhere: TransactionReportJob owns it, not this class")
        void theProcessorIsOwnedByItsJob() {
            // Finding F-008. This assertion was the inverse until the annotations came off: the class was
            // @Component @StepScope while com.cardemo.batch.jobs.TransactionReportJob constructed it with new,
            // so the definition the container published was resolved by nothing. Neither this class nor the
            // processor may reintroduce a second provenance - the six WS-REPORT-VARS items of
            // app/cbl/CBTRN03C.cbl:L127-L137 are per-run state, and one instance per step execution is what
            // keeps one report's pagination out of the next. That comes from construction inside the STEP10R
            // tasklet now, and TransactionReportProcessorScopeIsolationTest asserts both halves of it: no
            // component or scope annotation, and no static mutable field that construction could not isolate.
            assertThat(TransactionReportProcessor.class.isAnnotationPresent(Component.class))
                    .as("a bean definition here would be a second, unresolved provenance for one type")
                    .isFalse();
            assertThat(TransactionReportProcessor.class.isAnnotationPresent(StepScope.class))
                    .as("the scope would also move the constructor's DATEPARM validation behind a proxy, "
                            + "where a rejected pair surfaces as BeanCreationException rather than as the "
                            + "step's own FatalProcessingException")
                    .isFalse();
        }

        @Test
        @DisplayName("a processor starts with its per-run state at the source's initial values")
        void processorIsProducedWithInitialState() {
            final TransactionReportProcessor processor = newProcessor();

            assertThat(processor).isNotNull();
            assertThat(processor.lineCounter())
                    .as("WS-LINE-COUNTER starts at zero, app/cbl/CBTRN03C.cbl:L127-L137")
                    .isZero();
            assertThat(processor.pageTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(processor.accountTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(processor.grandTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("the end of data branch runs once per processor and refuses a second call")
        void endOfDataBranchRunsOncePerProcessor() {
            final TransactionReportProcessor processor = newProcessor();

            assertThat(processor.finishReport().lines())
                    .as("app/cbl/CBTRN03C.cbl:L202-L203 emits the closing page total and the grand total")
                    .isNotEmpty();
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(processor::finishReport)
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .as("the reason carries the diagnosis; the message is the source's own "
                                    + "ERROR WRITING REPTFILE literal and is not paraphrased")
                            .contains("exactly once"));
        }

        @Test
        @DisplayName("two constructions yield two independent instances, which is why the bean is step scoped")
        void twoInvocationsYieldIndependentInstances() {
            final TransactionReportProcessor first = newProcessor();
            final TransactionReportProcessor second = newProcessor();

            assertThat(first).isNotSameAs(second);
        }
    }

    @Nested
    @DisplayName("The container accepts this class with overriding disabled, and gets the inventory it expects")
    class ContainerRegistration {

        /**
         * The class under test in a real container, with overriding disabled exactly as the base profile sets
         * it, so a colliding definition fails here rather than at deployment.
         *
         * <p>Collaborators are mocked because the assertion is about the bean inventory, not about data
         * access. The two properties are supplied because the bucket key deliberately carries no inline
         * default: an environment that has not named a bucket must fail rather than read from somewhere
         * nobody chose.
         */
        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withAllowBeanDefinitionOverriding(false)
                .withUserConfiguration(BatchConfig.class, StubCollaborators.class)
                .withPropertyValues(
                        "carddemo.batch.dataset-window-size=100",
                        "carddemo.aws.s3.batch-output-bucket=carddemo-batch-output",
                        // The platform is pinned so the framework's script location resolves without a live
                        // connection, and the mode stays at the base profile's value so no script runs here.
                        "spring.batch.jdbc.platform=postgresql",
                        "spring.batch.jdbc.initialize-schema=never");

        @Test
        @DisplayName("four dataset bindings and no listener of any kind are contributed")
        void fourBindingsAndNoListenerAreContributed() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(FileService.Dataset.class))
                        .as("one binding per DD of app/cbl/CBSTM03B.CBL:L58-L78")
                        .hasSize(FileService.Dd.values().length);
                assertThat(context.getBeansOfType(JobExecutionListener.class))
                        .as("findings M-01 and CFG-003: a listener bean is never applied to a job "
                                + "implicitly - neither Spring Batch nor Boot collects one - so a bean "
                                + "published here would be dead wiring that reads as assurance; every job "
                                + "class attaches its own with .listener(...) instead")
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("exactly the F-020 verification topology reaches the container from here, and no "
                + "decider or flow does")
        void onlyTheVerificationTopologyReachesTheContainer() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(Job.class))
                        .as("the six classes of com.cardemo.batch.jobs are the definition sites for every "
                                + "other job; this one exists because a Step alone cannot be launched")
                        .containsOnlyKeys(BatchConfig.DATASET_VERIFICATION_JOB_BEAN_NAME);
                assertThat(context.getBeansOfType(Step.class))
                        .as("one step per read-only member of AAP F-020: READACCT, READCARD, READXREF, "
                                + "READCUST")
                        .containsOnlyKeys(
                                BatchConfig.READ_ACCOUNT_STEP_BEAN_NAME,
                                BatchConfig.READ_CARD_STEP_BEAN_NAME,
                                BatchConfig.READ_CROSS_REFERENCE_STEP_BEAN_NAME,
                                BatchConfig.READ_CUSTOMER_STEP_BEAN_NAME);
                assertThat(context.getBeansOfType(JobExecutionDecider.class))
                        .as("none of the four members carries a COND parameter, so there is no gating to "
                                + "translate and a decider here would be invented control flow")
                        .isEmpty();
                assertThat(context.getBeansOfType(Flow.class))
                        .as("flow composition belongs to com.cardemo.batch.jobs")
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("the registered bindings satisfy the file service inventory in a live container")
        void theRegisteredBindingsSatisfyTheInventory() {
            runner.run(context -> {
                final FileService service = new FileService(new FileStatusMapper(),
                        List.copyOf(context.getBeansOfType(FileService.Dataset.class).values()));
                service.afterPropertiesSet();
                for (final FileService.Dd dd : FileService.Dd.values()) {
                    assertThat(service.isBound(dd)).as("%s", dd.ddName()).isTrue();
                }
            });
        }

        @Test
        @DisplayName("a non-positive window size fails the context rather than defaulting silently")
        void aNonPositiveWindowSizeFailsTheContext() {
            runner.withPropertyValues("carddemo.batch.dataset-window-size=0")
                    .run(context -> assertThat(context).hasFailed());
        }

        @Test
        @DisplayName("the framework metadata initialiser is contributed, and it replaces Boot's own")
        void theMetadataInitialiserIsContributed() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(BatchDataSourceScriptDatabaseInitializer.class))
                        .as("Boot declares its own @ConditionalOnMissingBean, so exactly one must exist and"
                                + " it must be the one bound to the DDL-owning role")
                        .containsOnlyKeys("batchMetadataInitializer");
            });
        }

        @Test
        @DisplayName("an operator submission declares no consumer, so it ends and cannot race the online tier")
        void anOperatorSubmissionDeclaresNoConsumer() {
            // Two things break if a submission also drains the queue. A polling container holds non-daemon
            // threads, so a submission that succeeds never ends - proven at runtime, where a completed
            // interest submission sat polling instead of exiting. And a submitted process draining the queue
            // would be a second consumer alongside the online tier, so one message could launch two runs.
            new ApplicationContextRunner()
                    .withUserConfiguration(BatchConfig.class, StubCollaborators.class)
                    .withPropertyValues(
                            "carddemo.batch.dataset-window-size=100",
                            "carddemo.aws.s3.batch-output-bucket=carddemo-batch-output",
                            "spring.batch.jdbc.platform=postgresql",
                            "spring.batch.jdbc.initialize-schema=never",
                            "carddemo.aws.sqs.report-queue=carddemo-report-jobs.fifo",
                            "spring.batch.job.name=CARDDEMO-PIPELINE")
                    .run(context -> assertThat(context.getBeansOfType(
                            BatchConfig.ReportJobQueueListener.class))
                            .as("a batch submission is not the online tier")
                            .isEmpty());
        }

        @Test
        @DisplayName("the online tier does declare the consumer when a queue is configured")
        void theOnlineTierDeclaresTheConsumer() {
            runner.withUserConfiguration(LaunchCollaborators.class)
                    .withPropertyValues("carddemo.aws.sqs.report-queue=carddemo-report-jobs.fifo")
                    .run(context -> assertThat(context.getBeansOfType(
                            BatchConfig.ReportJobQueueListener.class))
                            .as("with a queue configured and no submission in progress, the JES2 internal "
                                    + "reader replacement must exist")
                            .containsOnlyKeys("reportJobQueueListener"));
        }

        @Test
        @DisplayName("carddemo.batch.report-queue-listener.enabled=false withdraws the consumer so a "
                + "producer-side context can be the only reader of the FIFO queue")
        void theConsumerCanBeWithdrawnSoAProducerSideContextIsTheOnlyReader() {
            // A FIFO message is delivered to exactly one reader, so a context that asserts what the producer
            // published has to be that reader. application-test.yml sets this property for exactly that
            // reason: three integration classes publish a submission and then receive it - the three
            // reporting periods, the correlation identifier on the message, and the health probe that must
            // not consume - and with a listener in the same context every one of them reads an empty queue.
            //
            // The property is not a way of leaving the consumer unexercised.
            // com.cardemo.integration.batch.ReportQueueListenerLiveTest sets it back to true in a context of
            // its own, against a separate FIFO queue, and drives the consumer through a live delivery to a
            // real TRANREPT execution - which is the half of the contract this class cannot reach, because
            // this class calls the listener method directly.
            runner.withUserConfiguration(LaunchCollaborators.class)
                    .withPropertyValues(
                            "carddemo.aws.sqs.report-queue=carddemo-report-jobs.fifo",
                            "carddemo.batch.report-queue-listener.enabled=false")
                    .run(context -> assertThat(context.getBeansOfType(
                            BatchConfig.ReportJobQueueListener.class))
                            .as("the property exists to leave the queue to a single reader")
                            .isEmpty());
        }

        @Test
        @DisplayName("the consumer is present by default, so omitting the property never silently "
                + "disables the JES2 internal reader replacement")
        void theConsumerDefaultsToPresentWhenThePropertyIsAbsent() {
            // The default matters more than the override. A deployment that never mentions this property must
            // still consume the queue, or a report submission would be published and then simply sit there.
            runner.withUserConfiguration(LaunchCollaborators.class)
                    .withPropertyValues(
                            "carddemo.aws.sqs.report-queue=carddemo-report-jobs.fifo",
                            "carddemo.batch.report-queue-listener.enabled=true")
                    .run(context -> assertThat(context.getBeansOfType(
                            BatchConfig.ReportJobQueueListener.class))
                            .as("an explicit true behaves exactly as an absent property does")
                            .containsOnlyKeys("reportJobQueueListener"));
        }

        @Test
        @DisplayName("application-test.yml disables the consumer, and that is asserted against the file "
                + "rather than assumed")
        void theTestProfileWithdrawsTheConsumerOnDisk() throws Exception {
            // The comment in application-test.yml claims this class asserts the gate. This is that assertion,
            // and it is made against the file so that deleting the property from the profile fails a test
            // instead of quietly reintroducing a second reader into the whole integration tier.
            final String profile = Files.readString(
                    Path.of("src/main/resources/application-test.yml"), StandardCharsets.UTF_8);

            assertThat(profile.replaceAll("\\s+", " "))
                    .as("the shared integration harness must remain the only reader of the report queue")
                    .contains("carddemo: batch: report-queue-listener: enabled: false");
        }

        /**
         * The collaborators only the queue listener needs, kept out of the default slice on purpose.
         *
         * <p>A {@code transactionReportJob} bean supplied to every slice would contradict
         * {@link #onlyTheVerificationTopologyReachesTheContainer}, which pins the exact set of {@link Job}
         * beans this class contributes to the one F-020 verification job and leaves every other job identity
         * to the six classes of {@code com.cardemo.batch.jobs}. Supplying it only where the listener is under
         * test keeps both assertions meaningful.
         */
        @Configuration(proxyBeanMethods = false)
        static class LaunchCollaborators {

            /**
             * The launcher the queue listener submits the report job through.
             *
             * @return a mocked launcher; what the listener does with it is tested elsewhere
             */
            @Bean
            JobLauncher jobLauncher() {
                return mock(JobLauncher.class);
            }

            /**
             * The job identity the queue listener resolves before it submits.
             *
             * @return the report job under the bean name the listener qualifies on
             */
            @Bean("transactionReportJob")
            Job transactionReportJob() {
                return mock(Job.class);
            }

            /**
             * The message-body binder the listener uses on the way to a job launch.
             *
             * @return a real mapper, because the listener binds an untrusted body with it
             */
            @Bean
            ObjectMapper objectMapper() {
                return new ObjectMapper();
            }

            /**
             * The clock the listener measures a submission's validity window against.
             *
             * <p>Finding SEC-002 gave the envelope code a window, so the listener has a time source; in the
             * running application it is the single {@code Clock} bean
             * {@code com.cardemo.config.ObservabilityConfig} publishes, and this slice supplies a fixed one
             * because a slice loads only the class under test.
             *
             * @return a fixed clock, never {@code null}
             */
            @Bean
            Clock clock() {
                return Clock.fixed(Instant.parse("2022-08-05T09:15:30Z"), ZoneOffset.UTC);
            }
        }

        @Test
        @DisplayName("no queue configured means no consumer rather than one bound to a guessed name")
        void noQueueConfiguredMeansNoConsumer() {
            runner.run(context -> assertThat(context.getBeansOfType(
                    BatchConfig.ReportJobQueueListener.class)).isEmpty());
        }

        @Test
        @DisplayName("a metadata table the runtime role cannot read fails startup instead of being swallowed")
        void anUnreadableMetadataTableFailsStartup() {
            runner.withPropertyValues(UNREADABLE_METADATA_PROPERTY + "=true")
                    .run(context -> {
                        assertThat(context)
                                .as("Boot's own initialiser carries continueOnError=true, which is exactly"
                                        + " how a deployment came up healthy with no batch_* tables at all")
                                .hasFailed();
                        // The direct cause rather than the root cause: the root is the driver's own
                        // SQLException, whose message is whatever the database chose to say. The link that
                        // matters is the one this class contributes - it has to name the table an operator
                        // must provision and the property that decides which role provisions it.
                        assertThat(context.getStartupFailure())
                                .cause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("BATCH_JOB_INSTANCE")
                                .hasMessageContaining("spring.batch.jdbc.initialize-schema")
                                .hasMessageContaining("spring.flyway.user");
                    });
        }

        @Test
        @DisplayName("on PostgreSQL every metadata table and sequence is checked for mutation privilege")
        void everyMetadataObjectIsCheckedForMutationPrivilege() throws SQLException {
            // Finding BAT-001: the check used to end at 'select 1 ... where 1 = 0' on six tables, which
            // proves existence and SELECT and nothing else. It said nothing about INSERT or UPDATE, and it
            // never looked at the three sequences the framework's script creates - so a runtime role granted
            // SELECT alone, or granted everything on tables and nothing on sequences, started cleanly and
            // failed on its first launch. These twenty-seven statements are what closed it.
            final ArgumentCaptor<String> issued = ArgumentCaptor.forClass(String.class);
            runner.withPropertyValues(METADATA_PRODUCT_PROPERTY + "=" + POSTGRESQL)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        verify(context.getBean(DataSource.class).getConnection(),
                                times(PRIVILEGE_PROBE_COUNT)).prepareStatement(issued.capture());
                    });
            assertThat(issued.getAllValues())
                    .as("both privilege functions are used: tables and sequences carry different grants")
                    .anyMatch(sql -> sql.contains("has_table_privilege"))
                    .anyMatch(sql -> sql.contains("has_sequence_privilege"));
        }

        @Test
        @DisplayName("a metadata table the runtime role cannot INSERT into fails startup, not the first launch")
        void aWithheldTablePrivilegeFailsStartup() {
            runner.withPropertyValues(
                            METADATA_PRODUCT_PROPERTY + "=" + POSTGRESQL,
                            WITHHELD_PRIVILEGE_PROPERTY + "=INSERT")
                    .run(context -> {
                        assertThat(context)
                                .as("a launch inserts a job instance, an execution and a step execution")
                                .hasFailed();
                        assertThat(context.getStartupFailure())
                                .cause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("INSERT")
                                .hasMessageContaining("BATCH_JOB_INSTANCE")
                                .hasMessageContaining("GRANT SELECT, INSERT, UPDATE, DELETE");
                    });
        }

        @Test
        @DisplayName("a metadata sequence the runtime role cannot USE fails startup, though every table reads")
        void aWithheldSequencePrivilegeFailsStartup() {
            // The narrowest form of the finding, and the one no table-only check could ever see: all six
            // tables present, readable and writable, and no USAGE on the sequences every identity in the
            // schema is drawn from. Nothing before the first launch would have noticed.
            runner.withPropertyValues(
                            METADATA_PRODUCT_PROPERTY + "=" + POSTGRESQL,
                            WITHHELD_PRIVILEGE_PROPERTY + "=USAGE")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .cause()
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("USAGE")
                                .hasMessageContaining("sequence")
                                .hasMessageContaining("BATCH_JOB_SEQ")
                                .hasMessageContaining("GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES");
                    });
        }

        @Test
        @DisplayName("an engine that is not PostgreSQL keeps the existence probe and skips the privilege ones")
        void aForeignEngineSkipsThePrivilegeProbesRatherThanFailing() throws SQLException {
            // has_table_privilege and has_sequence_privilege are PostgreSQL functions. Sending them to
            // another engine would fail startup on a deployment that is otherwise fine, so the honest
            // behaviour is to keep the existence probe, skip the privilege ones and say so in the log.
            runner.withPropertyValues(METADATA_PRODUCT_PROPERTY + "=Oracle")
                    .run(context -> {
                        assertThat(context)
                                .as("refusing to start on an unreadable privilege model would turn an "
                                        + "unverifiable claim into an outage")
                                .hasNotFailed();
                        verify(context.getBean(DataSource.class).getConnection(), never())
                                .prepareStatement(anyString());
                    });
        }

        /**
         * The collaborators the bean methods take, as mocks: the inventory is what is under test.
         *
         * <p>{@link BatchProperties} is bound from {@code spring.batch.*} rather than instantiated, because
         * the class under test reads three values off it - the table prefix, the initialisation mode and the
         * platform - and a bare instance would silently supply the framework defaults instead of the values
         * this test sets. That distinction is not cosmetic: the default mode is {@code EMBEDDED}, which
         * makes the framework interrogate the connection to decide whether the database is embedded, and the
         * default platform is the {@code @@platform@@} placeholder, which makes it interrogate the
         * connection again to resolve the script name. Both reach past the mock.
         */
        @Configuration(proxyBeanMethods = false)
        @EnableConfigurationProperties(BatchProperties.class)
        static class StubCollaborators {

            /**
             * A DataSource whose probe either answers or refuses, so both metadata outcomes are reachable.
             *
             * <p>The connection is stubbed rather than real because the assertion is about what the
             * initialiser does with the answer, not about PostgreSQL. {@code spring.batch.jdbc.platform} is
             * pinned in the runner so no live connection is needed to resolve the script location, and
             * {@code initialize-schema} stays {@code never} so no script runs here - the framework's own
             * integration tier covers the script itself against a real container.
             *
             * <p>Three switches, so every branch of the BAT-001 readiness check is reachable from a unit
             * test. {@code probeRefused} makes the existence probe raise. {@code product} decides what the
             * driver calls itself, and therefore whether the PostgreSQL-only privilege probes run at all -
             * left blank the connection supplies no {@link DatabaseMetaData}, which is the shape a minimal
             * stub has and which must skip rather than raise. {@code withheldPrivilege} names one privilege
             * or one object the runtime role does not hold, which is the state that produced the finding: a
             * schema that exists, reads cleanly, and cannot be written.
             *
             * @param probeRefused whether the metadata probe should refuse, standing in for a table that the
             *     DDL role never created or that the runtime role cannot read
             * @param product the product name the driver reports, or blank for a connection that supplies no
             *     metadata at all
             * @param withheldPrivilege a privilege name ({@code INSERT}, {@code USAGE}) or an object name
             *     ({@code BATCH_JOB_SEQ}) the role does not hold, or blank to hold everything
             * @return the stubbed DataSource
             * @throws SQLException never; declared by the stubbed JDBC contract
             */
            @Bean
            DataSource dataSource(
                    @Value("${" + UNREADABLE_METADATA_PROPERTY + ":false}") final boolean probeRefused,
                    @Value("${" + METADATA_PRODUCT_PROPERTY + ":}") final String product,
                    @Value("${" + WITHHELD_PRIVILEGE_PROPERTY + ":}") final String withheldPrivilege)
                    throws SQLException {

                final DataSource dataSource = mock(DataSource.class);
                final Connection connection = mock(Connection.class);
                final Statement statement = mock(Statement.class);
                when(dataSource.getConnection()).thenReturn(connection);
                when(connection.createStatement()).thenReturn(statement);
                if (probeRefused) {
                    when(statement.execute(anyString()))
                            .thenThrow(new SQLException("relation \"batch_job_instance\" does not exist"));
                } else {
                    when(statement.execute(anyString())).thenReturn(Boolean.TRUE.booleanValue());
                }
                if (!product.isBlank()) {
                    final DatabaseMetaData metaData = mock(DatabaseMetaData.class);
                    when(metaData.getDatabaseProductName()).thenReturn(product);
                    when(connection.getMetaData()).thenReturn(metaData);
                    stubPrivilegeQuery(connection, withheldPrivilege);
                }
                return dataSource;
            }

            /**
             * Answers {@code has_table_privilege} and {@code has_sequence_privilege} the way the driver would.
             *
             * <p>A fresh result set per {@code executeQuery()} rather than one shared mock, because the check
             * issues twenty-seven of these calls and a shared {@code next()} stub would exhaust itself on the
             * second one - reporting an empty result, which the class under test correctly reads as "the
             * object could not be checked". That would pass the refusal tests for entirely the wrong reason.
             *
             * @param connection the stubbed connection
             * @param withheldPrivilege the privilege or object name to answer {@code false} for, or blank
             * @throws SQLException never; declared by the stubbed JDBC contract
             */
            private static void stubPrivilegeQuery(final Connection connection,
                    final String withheldPrivilege) throws SQLException {

                // Index 1 is the object name and index 2 the privilege, in the order
                // JdbcTemplate.queryForObject binds the two varargs.
                final String[] bound = new String[3];
                final PreparedStatement privilegeQuery = mock(PreparedStatement.class);
                doAnswer(binding -> {
                    bound[binding.getArgument(0, Integer.class).intValue()] =
                            binding.getArgument(1, String.class);
                    return null;
                }).when(privilegeQuery).setString(anyInt(), anyString());
                when(privilegeQuery.executeQuery()).thenAnswer(execution -> {
                    final boolean held = withheldPrivilege.isBlank()
                            || !(withheldPrivilege.equalsIgnoreCase(bound[1])
                                    || withheldPrivilege.equalsIgnoreCase(bound[2]));
                    final ResultSetMetaData oneColumn = mock(ResultSetMetaData.class);
                    when(oneColumn.getColumnCount()).thenReturn(Integer.valueOf(1));
                    final ResultSet answer = mock(ResultSet.class);
                    when(answer.getMetaData()).thenReturn(oneColumn);
                    when(answer.next()).thenReturn(Boolean.TRUE, Boolean.FALSE);
                    when(answer.getBoolean(1)).thenReturn(Boolean.valueOf(held));
                    return answer;
                });
                when(connection.prepareStatement(anyString())).thenReturn(privilegeQuery);
            }

            /**
             * The object-store collaborator the slice supplies so the configuration can be instantiated.
             *
             * @return a mocked object store
             */
            @Bean
            S3Operations objectStorage() {
                return mock(S3Operations.class);
            }

            /**
             * The {@code ACCTDAT} repository collaborator the slice supplies.
             *
             * @return a mocked account cluster
             */
            @Bean
            AccountRepository accountRepository() {
                return mock(AccountRepository.class);
            }

            /**
             * The {@code CUSTDAT} repository collaborator the slice supplies.
             *
             * @return a mocked customer cluster
             */
            @Bean
            CustomerRepository customerRepository() {
                return mock(CustomerRepository.class);
            }

            /**
             * The {@code CCXREF} repository collaborator the slice supplies.
             *
             * @return a mocked cross-reference cluster
             */
            @Bean
            CardCrossReferenceRepository cardCrossReferenceRepository() {
                return mock(CardCrossReferenceRepository.class);
            }

            /**
             * The metadata store the four verification steps and their job record against.
             *
             * <p>Mocked rather than real because the assertion is the topology - which steps exist, in which
             * order, wired to which reader - and not what the framework writes to {@code BATCH_*}. The
             * framework tier is covered against a real container by the batch integration suites.
             *
             * @return a mocked job repository
             */
            @Bean
            JobRepository jobRepository() {
                return mock(JobRepository.class);
            }

            /**
             * The manager each verification step's chunk boundary commits against.
             *
             * @return a mocked transaction manager
             */
            @Bean
            PlatformTransactionManager transactionManager() {
                return mock(PlatformTransactionManager.class);
            }

            /**
             * The {@code ACCTFILE} reader of {@code app/cbl/CBACT01C.cbl}.
             *
             * <p>The four readers carry {@code @Component @StepScope} and live in
             * {@code com.cardemo.batch.readers}, which this slice does not scan, so the slice supplies
             * stand-ins. That is deliberate: a slice that scanned the readers package would be asserting the
             * readers' own wiring a second time - {@code AccountReaderTest} and its three siblings already own
             * that - instead of asserting what this class contributes.
             *
             * @return a mocked account reader
             */
            @Bean
            AccountReader accountReader() {
                return mock(AccountReader.class);
            }

            /**
             * The {@code CARDFILE} reader stand-in, supplied for the reason {@link #accountReader()} records.
             *
             * @return a mocked {@code CARDFILE} reader, from {@code app/cbl/CBACT02C.cbl}
             */
            @Bean
            CardReader cardReader() {
                return mock(CardReader.class);
            }

            /**
             * The {@code XREFFILE} reader stand-in, supplied for the reason {@link #accountReader()} records.
             *
             * @return a mocked {@code XREFFILE} reader, from {@code app/cbl/CBACT03C.cbl}
             */
            @Bean
            CardCrossReferenceReader cardCrossReferenceReader() {
                return mock(CardCrossReferenceReader.class);
            }

            /**
             * The {@code CUSTFILE} reader stand-in, supplied for the reason {@link #accountReader()} records.
             *
             * @return a mocked {@code CUSTFILE} reader, from {@code app/cbl/CBCUS01C.cbl}
             */
            @Bean
            CustomerReader customerReader() {
                return mock(CustomerReader.class);
            }
        }
    }

    @Nested
    @DisplayName("The four AAP F-020 read-only verification steps, and the job that makes them launchable")
    class DatasetVerificationTopology {

        /**
         * The four step bean names, in the order the job must run them.
         *
         * <p>The order is the order of the JCL members themselves - {@code READACCT}, {@code READCARD},
         * {@code READXREF}, {@code READCUST} - which is also the order in which their programs appear in the
         * corpus. Nothing in the corpus declares the four concurrent, so a split here would be invented
         * concurrency and the order is asserted rather than left to whichever order the container happens to
         * hand back.
         */
        private final List<String> stepNamesInJclOrder = List.of(
                BatchConfig.READ_ACCOUNT_STEP_BEAN_NAME,
                BatchConfig.READ_CARD_STEP_BEAN_NAME,
                BatchConfig.READ_CROSS_REFERENCE_STEP_BEAN_NAME,
                BatchConfig.READ_CUSTOMER_STEP_BEAN_NAME);

        /** The metadata store the steps record against; mocked, because the topology is what is asserted. */
        private final JobRepository jobRepository = mock(JobRepository.class);

        /** The manager each step's chunk boundary commits against. */
        private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        /**
         * Builds the four steps through their own bean factories, in JCL member order.
         *
         * @return the four steps, in the order the job must run them
         */
        private List<Step> buildSteps() {
            return List.of(
                    batchConfig.datasetVerificationReadAccountStep(
                            mock(AccountReader.class), jobRepository, transactionManager),
                    batchConfig.datasetVerificationReadCardStep(
                            mock(CardReader.class), jobRepository, transactionManager),
                    batchConfig.datasetVerificationReadCrossReferenceStep(
                            mock(CardCrossReferenceReader.class), jobRepository, transactionManager),
                    batchConfig.datasetVerificationReadCustomerStep(
                            mock(CustomerReader.class), jobRepository, transactionManager));
        }

        /**
         * Builds the verification job over the four steps.
         *
         * @param steps the four steps, in JCL member order
         * @return the job
         */
        private Job buildJob(final List<Step> steps) {
            return batchConfig.datasetVerificationJob(steps.get(0), steps.get(1), steps.get(2), steps.get(3),
                    jobRepository);
        }

        @Test
        @DisplayName("each step is named for the JCL member it translates, so a metadata row identifies it")
        void eachStepIsNamedForItsJclMember() {
            assertThat(buildSteps().stream().map(Step::getName))
                    .as("app/jcl/READACCT.jcl, READCARD.jcl, READXREF.jcl and READCUST.jcl each EXEC one "
                            + "read-only program of AAP F-020")
                    .containsExactlyElementsOf(stepNamesInJclOrder);
        }

        @Test
        @DisplayName("the job runs all four steps, in the order of their JCL members")
        void theJobRunsAllFourStepsInJclOrder() {
            final Job job = buildJob(buildSteps());

            assertThat(job.getName()).isEqualTo(BatchConfig.DATASET_VERIFICATION_JOB_BEAN_NAME);
            assertThat(job).isInstanceOf(StepLocator.class);
            assertThat(((StepLocator) job).getStepNames())
                    .as("sequential and complete: four steps, none dropped, none reordered")
                    .containsExactlyElementsOf(stepNamesInJclOrder);
            for (final String stepName : stepNamesInJclOrder) {
                assertThat(((StepLocator) job).getStep(stepName))
                        .as("%s", stepName)
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("an empty submission is accepted, because none of the four members passes a parameter")
        void anEmptySubmissionIsAccepted() {
            // READACCT, READCARD, READXREF and READCUST carry no PARM, no SYMNAMES and no DATEPARM, so a
            // validator demanding input here would demand something the source never supplies. An operator
            // therefore needs only the job name, and this is the assertion that keeps it that way.
            final Job job = buildJob(buildSteps());

            assertThatCode(() -> job.getJobParametersValidator().validate(new JobParameters()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the job registers a diagnostic context listener of its own, on its own JobBuilder")
        void theJobRegistersItsOwnDiagnosticContextListener() {
            // Findings M-01 and CFG-003: the shared listener BEAN this job used to be handed was collected by
            // nothing and applied to no job, so it was removed. What replaces it is a nested, non-bean
            // listener registered through JobBuilder.listener(...) - the only registration that takes effect -
            // and without one this job's events would be the only ones with no job instance identifier.
            final List<Object> declaredHere = reachable(buildJob(buildSteps()), JobExecutionListener.class)
                    .stream()
                    .filter(listener -> listener.getClass().getName().startsWith("com.cardemo."))
                    .toList();

            // Filtered to this project's own types on purpose. A built SimpleJob always holds the framework's
            // CompositeJobExecutionListener, which is itself a JobExecutionListener, so a reachability walk
            // reports it alongside whatever it aggregates. That composite is the registration mechanism, not a
            // second listener, and counting it would make this assertion a statement about Spring Batch's
            // internals rather than about this class.
            assertThat(declaredHere)
                    .as("exactly one job-level listener, declared by this class and not injected into it")
                    .hasSize(1)
                    .allSatisfy(listener -> assertThat(listener.getClass().getSimpleName())
                            .isEqualTo("DatasetVerificationJobListener"));
        }

        @Test
        @DisplayName("a missing collaborator is refused by name rather than producing a half-wired step")
        void aMissingCollaboratorIsRefusedByName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> batchConfig.datasetVerificationReadAccountStep(
                            null, jobRepository, transactionManager))
                    .withMessageContaining("accountReader");
            assertThatNullPointerException()
                    .isThrownBy(() -> batchConfig.datasetVerificationReadCardStep(
                            null, jobRepository, transactionManager))
                    .withMessageContaining("cardReader");
            assertThatNullPointerException()
                    .isThrownBy(() -> batchConfig.datasetVerificationReadCrossReferenceStep(
                            null, jobRepository, transactionManager))
                    .withMessageContaining("cardCrossReferenceReader");
            assertThatNullPointerException()
                    .isThrownBy(() -> batchConfig.datasetVerificationReadCustomerStep(
                            null, jobRepository, transactionManager))
                    .withMessageContaining("customerReader");
            assertThatNullPointerException()
                    .isThrownBy(() -> batchConfig.datasetVerificationReadAccountStep(
                            mock(AccountReader.class), null, transactionManager))
                    .withMessageContaining("jobRepository");
            assertThatNullPointerException()
                    .isThrownBy(() -> batchConfig.datasetVerificationReadAccountStep(
                            mock(AccountReader.class), jobRepository, null))
                    .withMessageContaining("transactionManager");
        }

        @Test
        @DisplayName("a missing step or repository is refused by name rather than producing a shorter job")
        void aMissingStepOrRepositoryIsRefusedByName() {
            final List<Step> steps = buildSteps();

            assertThatNullPointerException()
                    .isThrownBy(() -> batchConfig.datasetVerificationJob(
                            null, steps.get(1), steps.get(2), steps.get(3), jobRepository))
                    .withMessageContaining(BatchConfig.READ_ACCOUNT_STEP_BEAN_NAME);
            assertThatNullPointerException()
                    .isThrownBy(() -> batchConfig.datasetVerificationJob(
                            steps.get(0), null, steps.get(2), steps.get(3), jobRepository))
                    .withMessageContaining(BatchConfig.READ_CARD_STEP_BEAN_NAME);
            assertThatNullPointerException()
                    .isThrownBy(() -> batchConfig.datasetVerificationJob(
                            steps.get(0), steps.get(1), null, steps.get(3), jobRepository))
                    .withMessageContaining(BatchConfig.READ_CROSS_REFERENCE_STEP_BEAN_NAME);
            assertThatNullPointerException()
                    .isThrownBy(() -> batchConfig.datasetVerificationJob(
                            steps.get(0), steps.get(1), steps.get(2), null, jobRepository))
                    .withMessageContaining(BatchConfig.READ_CUSTOMER_STEP_BEAN_NAME);
            assertThatNullPointerException()
                    .isThrownBy(() -> batchConfig.datasetVerificationJob(
                            steps.get(0), steps.get(1), steps.get(2), steps.get(3), null))
                    .withMessageContaining("jobRepository");
        }

        @Test
        @DisplayName("no step wires a processor, because the four programs compute nothing")
        void noStepWiresAProcessor() {
            // CBACT01C, CBACT02C, CBACT03C and CBCUS01C have a verb inventory of OPEN, READ, CLOSE and
            // DISPLAY. A processor here would be a transformation stage the source does not have.
            for (final Step step : buildSteps()) {
                assertThat(reachable(step, ItemProcessor.class))
                        .as("%s", step.getName())
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("the wired writer holds only its own diagnostics, so the step cannot store anything")
        void theWiredWriterHoldsNothingItCouldStoreThrough() {
            // This is the read-only guarantee made structural rather than asserted. The step's own added
            // component - the counter that reproduces the end-of-run DISPLAY - holds no repository, no
            // object-store client, no EntityManager, no DataSource and no JdbcOperations, so there is nothing
            // present through which a later edit could reach the substrate without also adding a field here.
            // The readers' own read-only property is a separate claim, owned by SequentialReaderContractTest.
            final List<Class<?>> writeCapable = List.of(Repository.class, S3Operations.class,
                    EntityManager.class, DataSource.class, JdbcOperations.class);

            for (final Step step : buildSteps()) {
                final Set<ItemWriter<?>> writers = wiredWriters(step);
                assertThat(writers).as("%s wires exactly one writer", step.getName()).hasSize(1);
                final Class<?> writerClass = writers.iterator().next().getClass();
                final List<Class<?>> fieldTypes = Arrays.stream(writerClass.getDeclaredFields())
                        .filter(field -> !field.isSynthetic())
                        .map(Field::getType)
                        .toList();

                assertThat(fieldTypes)
                        .as("%s: three diagnostic labels and one counter, and nothing else", step.getName())
                        .containsOnly(String.class, long.class);
                for (final Class<?> forbidden : writeCapable) {
                    assertThat(fieldTypes)
                            .as("%s must hold no %s", step.getName(), forbidden.getSimpleName())
                            .noneMatch(forbidden::isAssignableFrom);
                }
            }
        }

        @Test
        @DisplayName("the writer counts the rows it saw and publishes the total under its own step name")
        void theWriterCountsAndPublishesUnderItsOwnStepName() throws Exception {
            final Step step = buildSteps().get(0);
            final ItemWriter<Object> writer = onlyWriter(step);
            final StepExecution execution = stepExecution(step.getName());

            ((StepExecutionListener) writer).beforeStep(execution);
            writer.write(Chunk.of(new Object(), new Object(), new Object()));

            assertThat(((StepExecutionListener) writer).afterStep(execution))
                    .as("returning null leaves in place the exit status the framework derived from the step's "
                            + "own outcome, so a failure is not overwritten with a success")
                    .isNull();
            assertThat(execution.getExecutionContext()
                    .getLong(step.getName() + BatchConfig.VERIFIED_ROW_COUNT_SUFFIX))
                    .as("the end-of-run count of app/cbl/CBACT01C.cbl, published rather than only logged")
                    .isEqualTo(3L);
        }

        @Test
        @DisplayName("a second execution starts from zero rather than inheriting the previous total")
        void aSecondExecutionStartsFromZero() throws Exception {
            final Step step = buildSteps().get(0);
            final ItemWriter<Object> writer = onlyWriter(step);
            final StepExecutionListener listener = (StepExecutionListener) writer;

            listener.beforeStep(stepExecution(step.getName()));
            writer.write(Chunk.of(new Object(), new Object()));
            final StepExecution second = stepExecution(step.getName());
            listener.beforeStep(second);
            listener.afterStep(second);

            assertThat(second.getExecutionContext()
                    .getLong(step.getName() + BatchConfig.VERIFIED_ROW_COUNT_SUFFIX))
                    .as("a restart or a second run must report what it read, not what its predecessor read")
                    .isZero();
        }

        @Test
        @DisplayName("four steps in one job publish four distinct counts rather than overwriting one entry")
        void fourStepsPublishFourDistinctCounts() throws Exception {
            // The four run in one job, so a shared key would leave the last step's count standing for all
            // four and the other three counts simply unobservable. This writes all four into one context on
            // purpose, which is the only way to prove the keys do not collide.
            final List<Step> steps = buildSteps();
            final StepExecution shared = stepExecution("shared");

            int rows = 1;
            for (final Step step : steps) {
                final ItemWriter<Object> writer = onlyWriter(step);
                ((StepExecutionListener) writer).beforeStep(shared);
                writer.write(Chunk.of(new Object[rows]));
                ((StepExecutionListener) writer).afterStep(shared);
                rows++;
            }

            long expected = 1L;
            for (final Step step : steps) {
                assertThat(shared.getExecutionContext()
                        .getLong(step.getName() + BatchConfig.VERIFIED_ROW_COUNT_SUFFIX))
                        .as("%s", step.getName())
                        .isEqualTo(expected);
                expected++;
            }
        }

        /**
         * Builds a step execution detached from any repository, for exercising the writer's listener contract.
         *
         * @param stepName the step name the execution belongs to
         * @return a fresh step execution with an empty execution context
         */
        private StepExecution stepExecution(final String stepName) {
            return new StepExecution(stepName,
                    new JobExecution(new JobInstance(Long.valueOf(1L),
                            BatchConfig.DATASET_VERIFICATION_JOB_BEAN_NAME), null, null));
        }

        /**
         * Returns the one writer a verification step wires.
         *
         * @param <T> the item type, which the writer ignores because it only counts
         * @param step the step to inspect
         * @return the writer
         */
        @SuppressWarnings("unchecked")
        private <T> ItemWriter<T> onlyWriter(final Step step) {
            final Set<ItemWriter<?>> writers = wiredWriters(step);
            assertThat(writers).hasSize(1);
            return (ItemWriter<T>) writers.iterator().next();
        }

        /**
         * Returns every writer reachable from a built step.
         *
         * @param step the step to inspect
         * @return the writers, which for a verification step is exactly one
         */
        private Set<ItemWriter<?>> wiredWriters(final Step step) {
            final Set<ItemWriter<?>> writers = new LinkedHashSet<>();
            for (final Object candidate : reachable(step, ItemWriter.class)) {
                writers.add((ItemWriter<?>) candidate);
            }
            return writers;
        }
    }


    @Nested
    @DisplayName("The queue listener that replaces the JES2 internal reader drains and launches exactly once")
    class ReportQueueListener {

        /** The deduplication header the producer sets, and this consumer's idempotency key. */
        private static final String DEDUPLICATION_HEADER =
                SqsHeaders.MessageSystemAttributes.SQS_MESSAGE_DEDUPLICATION_ID_HEADER;

        /**
         * The receive count the transport maintains, which bounds how often one delivery may be returned.
         *
         * <p>Finding B-15. Named from the framework's own constant rather than written as a literal, so a
         * fixture cannot exercise a header spelling the listener does not read.
         */
        private static final String RECEIVE_COUNT_HEADER =
                SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT;

        /** A submission body in the shape the producer publishes. */
        private static final String PAYLOAD = """
                {"reportName":"Monthly","startDate":"2022-07-01","endDate":"2022-07-31"}""";

        /**
         * The key the envelope code is derived from. Long enough to be a plausible signing key and local to
         * this test, so nothing here is a committed credential of any deployment.
         */
        private static final String SIGNING_KEY = "batch-config-test-envelope-key-0123456789";

        /** The message {@link #PAYLOAD} binds to, used to compute the code the producer would have sent. */
        private static final JobSubmissionMessage MESSAGE =
                new JobSubmissionMessage("Monthly", "2022-07-01", "2022-07-31");

        /** The header name carrying the envelope code. */
        private static final String SIGNATURE_HEADER =
                ReportSubmissionService.JobSubmissionEnvelope.SIGNATURE_HEADER;

        /** The instant the listener's clock reports, and the instant every fixture code is issued at. */
        private static final Instant NOW = Instant.parse("2022-08-05T09:15:30Z");

        /**
         * The authenticator this fixture signs with, built from {@link #SIGNING_KEY}.
         *
         * <p>Finding SEC-001. Two instances derived from the same configured key agree by construction, so the
         * fixture can sign what the listener will verify without either of them holding the key as a string.
         */
        private static final ReportSubmissionService.JobSubmissionEnvelope ENVELOPE =
                new ReportSubmissionService.JobSubmissionEnvelope(SIGNING_KEY);

        /**
         * Builds the headers a genuine delivery carries: the deduplication identifier and a code issued for
         * exactly that identifier.
         *
         * <p>Finding SEC-002. The identifier is an argument to the signature rather than an unrelated header,
         * which is what makes a code bound to one submission useless for another.
         *
         * @param deduplicationId the producer's idempotency key
         * @return the header map, never {@code null}
         */
        private static Map<String, Object> signedHeaders(final String deduplicationId) {
            return Map.of(DEDUPLICATION_HEADER, deduplicationId,
                    SIGNATURE_HEADER, ENVELOPE.sign(MESSAGE, deduplicationId, NOW));
        }

        /**
         * Builds headers carrying whatever the caller needs plus a code issued for whichever identifier those
         * headers will resolve to.
         *
         * <p>The identifier is resolved the same way the listener resolves it - the deduplication header, then
         * the framework's message identifier, then the unidentified sentinel - so that a fixture exercising
         * the fallback still presents an authentic code for the identifier the fallback produces.
         *
         * @param extra the additional headers
         * @return the header map, never {@code null}
         */
        private static Map<String, Object> signedHeaders(final Map<String, Object> extra) {
            final java.util.Map<String, Object> headers = new java.util.LinkedHashMap<>(extra);
            headers.put(SIGNATURE_HEADER, ENVELOPE.sign(MESSAGE, resolvedIdentifier(extra), NOW));
            return headers;
        }

        /**
         * Resolves the submission identifier a header map will produce, mirroring the listener's own order.
         *
         * @param headers the headers the delivery will carry
         * @return the identifier, never {@code null}
         */
        private static String resolvedIdentifier(final Map<String, Object> headers) {
            final Object deduplication = headers.get(DEDUPLICATION_HEADER);
            if (deduplication != null && !deduplication.toString().isBlank()) {
                return deduplication.toString();
            }
            final Object messageId = headers.get(MessageHeaders.ID);
            if (messageId != null && !messageId.toString().isBlank()) {
                return messageId.toString();
            }
            return "unidentified-submission";
        }

        /**
         * A finished execution reporting the outcome a caller asks for.
         *
         * @param status the batch status the launcher reports
         * @param exitStatus the exit status the launcher reports
         * @return the stubbed execution
         */
        private static JobExecution execution(final BatchStatus status, final ExitStatus exitStatus) {
            final JobExecution execution = mock(JobExecution.class);
            when(execution.getStatus()).thenReturn(status);
            when(execution.getExitStatus()).thenReturn(exitStatus);
            when(execution.getAllFailureExceptions()).thenReturn(java.util.List.of());
            return execution;
        }

        /**
         * A finished execution reporting success, which is what an acknowledged delivery requires.
         *
         * @return the stubbed execution
         */
        private static JobExecution completedExecution() {
            return execution(BatchStatus.COMPLETED, ExitStatus.COMPLETED);
        }

        /** The launcher, stubbed so the parameters the listener builds can be captured. */
        private final JobLauncher jobLauncher = mock(JobLauncher.class);

        /** The report job, named so a failure message identifies it. */
        private final Job reportJob = mock(Job.class);

        /** The listener under test, built through the public factory method. */
        private BatchConfig.ReportJobQueueListener listener;

        /** Builds the listener with a real mapper, because binding the body is part of what is under test. */
        @BeforeEach
        void buildListener() {
            when(reportJob.getName()).thenReturn("TRANREPT");
            listener = new BatchConfig(100).reportJobQueueListener(jobLauncher, reportJob,
                    new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), SIGNING_KEY);
        }

        @Test
        @DisplayName("a submission launches the report job carrying the message's own period")
        void aSubmissionLaunchesTheReportJob() throws Exception {
            final ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
            final JobExecution completed = completedExecution();
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class))).thenReturn(completed);

            listener.drainReportJobQueue(PAYLOAD, signedHeaders("submission-1"), null);

            verify(jobLauncher).run(eq(reportJob), captor.capture());
            final JobParameters launched = captor.getValue();
            assertThat(launched.getString("startDate"))
                    .as("app/proc/TRANREPT.prc:L41 PARM-START-DATE, taken from the message and not defaulted")
                    .isEqualTo("2022-07-01");
            assertThat(launched.getString("endDate")).isEqualTo("2022-07-31");
            assertThat(launched.getString("reportName")).isEqualTo("Monthly");
            assertThat(launched.getString("submissionId"))
                    .as("the deduplication identifier the producer minted, carried as the idempotency key")
                    .isEqualTo("submission-1");
        }

        @Test
        @DisplayName("the deduplication identifier is identifying, so a redelivery is the same job instance")
        void theDeduplicationIdentifierIsIdentifying() throws Exception {
            final ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
            final JobExecution completed = completedExecution();
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class))).thenReturn(completed);

            listener.drainReportJobQueue(PAYLOAD, signedHeaders("submission-1"), null);

            verify(jobLauncher).run(eq(reportJob), captor.capture());
            // Identity is the whole mechanism: Spring Batch keys the job instance on the identifying
            // parameters, so this flag is what turns at-least-once delivery into exactly-once launching.
            assertThat(captor.getValue().getParameters().get("submissionId").isIdentifying())
                    .as("a non-identifying submission id would make every redelivery a new instance")
                    .isTrue();
        }

        @Test
        @DisplayName("a redelivery of a completed submission starts nothing and does not propagate")
        void aRedeliveryOfACompletedSubmissionStartsNothing() throws Exception {
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class)))
                    .thenThrow(new JobInstanceAlreadyCompleteException("already complete"));

            // Must not throw: the message has to be acknowledged, or the queue redelivers it forever.
            listener.drainReportJobQueue(PAYLOAD, signedHeaders("submission-1"), null);

            verify(jobLauncher).run(eq(reportJob), any(JobParameters.class));
        }

        @Test
        @DisplayName("a delivery that overtakes a running execution is returned to the queue, not acknowledged")
        void aRedeliveryWhileRunningIsReturnedToTheQueue() throws Exception {
            // FINDING C-02, severity Blocker. This delivery used to be consumed, which deleted the only
            // record of the submission while its outcome was still unknown: if that running execution then
            // failed, nothing on the queue said a report had ever been asked for. Returning it means the
            // queue redelivers it after the visibility window, by which time the execution has a terminal
            // status and this consumer either finds the instance complete or launches a genuine retry.
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class)))
                    .thenThrow(new JobExecutionAlreadyRunningException("still running"));

            assertThatIllegalStateException()
                    .isThrownBy(() -> listener.drainReportJobQueue(PAYLOAD, signedHeaders("submission-1"), null))
                    .withMessageContaining("returned to the queue");

            verify(jobLauncher).run(eq(reportJob), any(JobParameters.class));
        }

        @Test
        @DisplayName("an execution that ends FAILED is not acknowledged, so the submission survives")
        void aFailedExecutionIsNotAcknowledged() throws Exception {
            // FINDING C-02. JobLauncher.run RETURNS a failed execution rather than throwing, so a listener
            // that ignored the returned object reported success to the queue for a report that was never
            // produced. Both halves of the outcome are consulted, because a step that ends with the FAILED
            // exit code without an exception leaves the batch status successful.
            final JobExecution failed = execution(BatchStatus.FAILED, ExitStatus.FAILED);
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class))).thenReturn(failed);

            assertThatIllegalStateException()
                    .isThrownBy(() -> listener.drainReportJobQueue(PAYLOAD, signedHeaders("submission-4"), null))
                    .withMessageContaining("FAILED");
        }

        @Test
        @DisplayName("a successful batch status with a FAILED exit code is also not acknowledged")
        void aFailedExitCodeIsNotAcknowledged() throws Exception {
            final JobExecution failedExit = execution(BatchStatus.COMPLETED, ExitStatus.FAILED);
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class))).thenReturn(failedExit);

            assertThatIllegalStateException()
                    .isThrownBy(() -> listener.drainReportJobQueue(PAYLOAD, signedHeaders("submission-5"), null));
        }

        @Test
        @DisplayName("a validation failure from the job's own validator is consumed, not returned forever")
        void aValidationFailureIsConsumedRatherThanReturned() throws Exception {
            // FINDING B-15, severity Major. This is the arm that was missing, and its absence stopped the
            // whole report tier. The report job's JobParametersValidator raises this application's typed
            // ValidationException rather than the framework's checked JobParametersInvalidException, so it fell
            // through every catch arm and out of the listener - which left the delivery on a FIFO queue with a
            // single message group, blocking every later submission behind a message that could never succeed,
            // with no dead-letter target and a fifteen-minute visibility window.
            //
            // A validation failure is permanent by construction: the same body validated again fails again.
            // So it is consumed, exactly as an unbindable body and an unverified envelope code are.
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class)))
                    .thenThrow(ValidationException.invalidField("startDate", "refused by the job"));

            assertThatCode(() -> listener.drainReportJobQueue(
                    PAYLOAD, signedHeaders("submission-validation"), null))
                    .as("nothing may escape this method for a delivery that can never succeed, because the "
                            + "framework acknowledges the message only when it returns normally")
                    .doesNotThrowAnyException();

            verify(jobLauncher).run(eq(reportJob), any(JobParameters.class));
        }

        @Test
        @DisplayName("a failing execution is returned on an early attempt and discarded once the bound is hit")
        void redeliveryIsBoundedSoOneDeliveryCannotHoldTheGroupForever() throws Exception {
            // FINDING B-15. Classifying failures one at a time only protects against the failures already
            // named. A delivery whose failure LOOKS retryable but never resolves would still be returned on
            // every attempt, and on an ordered single-group FIFO queue that stops the report tier rather than
            // just this submission. The receive count the transport maintains bounds it: the same failing
            // execution is returned early and consumed once the attempt count reaches the bound.
            final JobExecution failed = execution(BatchStatus.FAILED, ExitStatus.FAILED);
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class))).thenReturn(failed);

            assertThatIllegalStateException()
                    .as("a first attempt is returned, so a transient cause gets another chance")
                    .isThrownBy(() -> listener.drainReportJobQueue(PAYLOAD, signedHeaders(Map.of(
                            DEDUPLICATION_HEADER, "submission-attempt-1",
                            RECEIVE_COUNT_HEADER, "1")), null))
                    .withMessageContaining("returned to the queue");

            assertThatCode(() -> listener.drainReportJobQueue(PAYLOAD, signedHeaders(Map.of(
                    DEDUPLICATION_HEADER, "submission-attempt-3",
                    RECEIVE_COUNT_HEADER, "3")), null))
                    .as("the third attempt is consumed instead, so the group moves on and the operator gets "
                            + "an ERROR naming the delivery rather than an indefinitely blocked queue")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an unusable receive count counts as a first attempt, which errs towards redelivery")
        void anUnusableReceiveCountIsTreatedAsAFirstAttempt() throws Exception {
            // The header is untrusted input arriving untyped. Every unusable form - absent, non-numeric, zero
            // - normalises to one, because this value only ever ENDS a retry sequence: reading it too low
            // costs an extra attempt, while reading it too high would discard a submission that deserved one.
            final JobExecution failed = execution(BatchStatus.FAILED, ExitStatus.FAILED);
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class))).thenReturn(failed);

            for (final String unusable : java.util.List.of("not-a-number", "0", "-4", "")) {
                assertThatIllegalStateException()
                        .as("a receive count of '%s' must not be read as an exhausted attempt", unusable)
                        .isThrownBy(() -> listener.drainReportJobQueue(PAYLOAD, signedHeaders(Map.of(
                                DEDUPLICATION_HEADER, "submission-unusable-" + unusable.length(),
                                RECEIVE_COUNT_HEADER, unusable)), null))
                        .withMessageContaining("returned to the queue");
            }
        }

        @Test
        @DisplayName("a message with no envelope code launches nothing")
        void anUnsignedMessageLaunchesNothing() throws Exception {
            // FINDING M-11, severity High. The emulator queue enforces no authorisation of its own, so
            // before this check any process able to reach its port could submit a report job and name the
            // job instance. It is consumed rather than returned: an unsigned message will never become
            // signed, and a message group is ordered, so returning it would stall every later submission.
            listener.drainReportJobQueue(PAYLOAD, Map.of(DEDUPLICATION_HEADER, "submission-6"), null);

            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("a message whose envelope code was computed for another period launches nothing")
        void aForgedEnvelopeCodeLaunchesNothing() throws Exception {
            final String codeForAnotherPeriod = ENVELOPE.sign(
                    new JobSubmissionMessage("Monthly", "2022-08-01", "2022-08-31"), "submission-7", NOW);

            listener.drainReportJobQueue(PAYLOAD, Map.of(DEDUPLICATION_HEADER, "submission-7",
                    SIGNATURE_HEADER, codeForAnotherPeriod), null);

            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("a message whose envelope code was computed under another key launches nothing")
        void aCodeUnderAnotherKeyLaunchesNothing() throws Exception {
            final String codeUnderAnotherKey = new ReportSubmissionService.JobSubmissionEnvelope(
                    SIGNING_KEY + "-different").sign(MESSAGE, "submission-8", NOW);

            listener.drainReportJobQueue(PAYLOAD, Map.of(DEDUPLICATION_HEADER, "submission-8",
                    SIGNATURE_HEADER, codeUnderAnotherKey), null);

            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("a captured message replayed under a fresh identifier launches nothing")
        void aReplayUnderAFreshIdentifierLaunchesNothing() throws Exception {
            // FINDING SEC-002, severity HIGH. This is the replay the previous check admitted. The code covered
            // the payload alone, so an observer who captured one (body, code) pair could re-publish it under
            // any deduplication identifier they liked; each fresh identifier is a DIFFERENT set of identifying
            // job parameters, so each one resolved to a new job instance and launched a real report run. The
            // identifier is now an argument to the signature, so a code issued for one submission does not
            // authenticate another.
            final String authenticCode = ENVELOPE.sign(MESSAGE, "submission-original", NOW);

            listener.drainReportJobQueue(PAYLOAD, Map.of(DEDUPLICATION_HEADER, "submission-replayed",
                    SIGNATURE_HEADER, authenticCode), null);

            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("an exact redelivery inside the window still verifies, because that is the queue working")
        void anExactRedeliveryStillVerifies() throws Exception {
            // The complement of the test above, and the reason the identifier check is an equality test rather
            // than a seen-before test. A FIFO queue redelivers whenever the visibility window lapses before
            // acknowledgement; that delivery carries the ORIGINAL identifier, so it must verify, reach the
            // launcher, and be refused there as the same job instance. Treating it as an attack would turn an
            // ordinary redelivery into an operator incident.
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class)))
                    .thenThrow(new JobInstanceAlreadyCompleteException("already complete"));

            listener.drainReportJobQueue(PAYLOAD, signedHeaders("submission-8b"), null);
            listener.drainReportJobQueue(PAYLOAD, signedHeaders("submission-8b"), null);

            verify(jobLauncher, times(2)).run(eq(reportJob), any(JobParameters.class));
        }

        @Test
        @DisplayName("a code whose validity window has closed launches nothing")
        void anExpiredCodeLaunchesNothing() throws Exception {
            // FINDING SEC-002. The second replay the previous check admitted: an authentic code, for the
            // authentic identifier, presented indefinitely. Nothing bounded it but whatever the job repository
            // still remembered, and a repository that had been pruned would launch it again. The code now
            // carries the window it was issued for.
            final Instant stale = NOW.minusSeconds(
                    ReportSubmissionService.JobSubmissionEnvelope.LIFETIME_SECONDS
                            + ReportSubmissionService.JobSubmissionEnvelope.CLOCK_SKEW_TOLERANCE_SECONDS + 1L);
            final String expiredCode = ENVELOPE.sign(MESSAGE, "submission-8c", stale);

            listener.drainReportJobQueue(PAYLOAD, Map.of(DEDUPLICATION_HEADER, "submission-8c",
                    SIGNATURE_HEADER, expiredCode), null);

            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("a code issued by a clock far ahead of this one launches nothing")
        void aCodeFromTheFutureLaunchesNothing() throws Exception {
            // The other end of the same window. Tolerated up to the declared skew allowance, because two hosts
            // never agree exactly; refused beyond it, because a code issued far in the future would otherwise
            // be a code that stays valid far longer than its declared lifetime.
            final Instant ahead = NOW.plusSeconds(
                    ReportSubmissionService.JobSubmissionEnvelope.CLOCK_SKEW_TOLERANCE_SECONDS + 60L);
            final String prematureCode = ENVELOPE.sign(MESSAGE, "submission-8d", ahead);

            listener.drainReportJobQueue(PAYLOAD, Map.of(DEDUPLICATION_HEADER, "submission-8d",
                    SIGNATURE_HEADER, prematureCode), null);

            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("a code rendered under the retired version is refused rather than accepted as legacy")
        void aRetiredVersionIsRefused() throws Exception {
            // The version prefix is what lets a signing contract be replaced without a window in which both
            // are honoured. A v1 code - the shape that covered the payload alone - is not downgraded to, it is
            // refused, so resolving finding SEC-002 cannot be undone by presenting the older form.
            listener.drainReportJobQueue(PAYLOAD, Map.of(DEDUPLICATION_HEADER, "submission-8e",
                    SIGNATURE_HEADER, "v1=0123456789abcdef"), null);

            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("a report name outside the three source literals launches nothing")
        void aReportNameOutsideTheClosedSetLaunchesNothing() throws Exception {
            // FINDING M-12, severity High. An arbitrary, unbounded report name used to reach an IDENTIFYING
            // job parameter, which the batch repository persists and keys a job instance on. The record's own
            // constructor now refuses anything but the three literals of app/cbl/CORPT00C.cbl:L214, :L240 and
            // :L433, and the listener treats that refusal exactly as it treats an unparseable body.
            final String hostile = """
                    {"reportName":"Monthly'; DROP TABLE transaction; --","startDate":"2022-07-01",\
                    "endDate":"2022-07-31"}""";

            listener.drainReportJobQueue(hostile, signedHeaders("submission-9"), null);

            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("the producer's correlation identifier is restored for the launch and removed after it")
        void theProducersCorrelationIdentifierIsRestoredForTheLaunch() throws Exception {
            // FINDING M-03, severity High. The producer publishes a correlation identifier and a W3C trace
            // context; before this they were never consumed, so a report run could not be tied back to the
            // request that submitted it. The value is observed from inside the launch, because that is the
            // only scope in which it is supposed to exist.
            final String correlationId = "11111111-2222-3333-4444-555555555555";
            final java.util.List<String> observed = new java.util.ArrayList<>();
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class))).thenAnswer(invocation -> {
                observed.add(org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID));
                return completedExecution();
            });

            listener.drainReportJobQueue(PAYLOAD, signedHeaders(
                    Map.of(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId)), null);

            assertThat(observed)
                    .as("the identifier the producer propagated must label every line the launch emits")
                    .containsExactly(correlationId);
            assertThat(org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID))
                    .as("and must be removed afterwards, or the container's pooled thread would label the "
                            + "next submission with this one's identity")
                    .isNull();
        }

        @Test
        @DisplayName("a hostile correlation header is dropped rather than placed in the logging context")
        void aHostileCorrelationHeaderIsDropped() throws Exception {
            final java.util.List<String> observed = new java.util.ArrayList<>();
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class))).thenAnswer(invocation -> {
                observed.add(org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID));
                return completedExecution();
            });

            listener.drainReportJobQueue(PAYLOAD, signedHeaders(Map.of(
                    DEDUPLICATION_HEADER, "submission-10",
                    CorrelationIdFilter.CORRELATION_ID_HEADER, "forged\nlevel=ERROR forged line")), null);

            assertThat(observed).as("the launch still happens; only the label changes").hasSize(1);
            assertThat(observed.get(0))
                    .as("a control character in a propagated header could forge a log record, so the value is "
                            + "dropped and a substitute stands in for it. The substitute is NOT the transport "
                            + "identifier: finding C-02 established that the deduplication identifier is "
                            + "publisher-controlled free text and may not reach a log stream, so what stands "
                            + "in is the one-way digest of it - sixteen lowercase hexadecimal characters, "
                            + "stable across redeliveries of one submission and different between submissions")
                    .doesNotContain("forged")
                    .doesNotContain("\n")
                    .isNotEqualTo("submission-10")
                    .matches("[0-9a-f]{16}");
        }

        @Test
        @DisplayName("the envelope key is required, because there is no unsigned mode")
        void theEnvelopeKeyIsRequired() {
            // Raised by the authenticator this factory constructs rather than by a guard of the factory's own:
            // finding SEC-001 moved the key out of the listener entirely, so the one place that can refuse a
            // blank key is the one place that derives from it.
            final Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
            for (final String absent : new String[] {null, "", "  "}) {
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> new BatchConfig(100)
                                .reportJobQueueListener(jobLauncher, reportJob, new ObjectMapper(), fixed,
                                        absent))
                        .withMessageContaining("carddemo.security.jwt.signing-key");
            }
        }

        @Test
        @DisplayName("the listener retains no field holding the configured signing key")
        void theListenerRetainsNoFieldHoldingTheSigningKey() throws Exception {
            // FINDING SEC-001, severity HIGH, CWE-316. The listener used to keep the application signing key
            // in a String field for the life of the bean, immutable, uncollectable and recoverable from any
            // heap dump. Asserted structurally, because the absence of a retained secret is a property of the
            // FIELD SET and not of any one call.
            for (final java.lang.reflect.Field field
                    : BatchConfig.ReportJobQueueListener.class.getDeclaredFields()) {
                if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                if (field.get(listener) instanceof String text) {
                    assertThat(text)
                            .as("field %s holds a String; none may hold the signing key", field.getName())
                            .isNotEqualTo(SIGNING_KEY);
                }
            }
            assertThat(BatchConfig.ReportJobQueueListener.class.getDeclaredField("envelope").getType())
                    .as("what is retained instead is the authenticator, which holds only a derived key")
                    .isEqualTo(ReportSubmissionService.JobSubmissionEnvelope.class);
        }

        @Test
        @DisplayName("a malformed payload is consumed rather than returned, because a FIFO group is ordered")
        void aMalformedPayloadIsConsumed() throws Exception {
            // A rethrow would redeliver a message that can never bind, and every later submission in the
            // same ordered message group would wait one visibility window per attempt. The RedrivePolicy
            // localstack-init/init-aws.sh provisions bounds the failures the listener cannot classify; this
            // is not one of them, so it is dropped on the first delivery rather than after four.
            listener.drainReportJobQueue("{not json", signedHeaders("submission-2"), null);

            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("a validation refusal escaping the launcher is acknowledged, not left to circulate")
        void aValidationRefusalIsConsumed() throws Exception {
            // FINDING, severity MAJOR. This is the arm the reported outage travelled through with no arm to
            // catch it. TransactionReportParametersValidator implements JobParametersValidator, whose validate
            // declares JobParametersInvalidException - but it let a runtime ValidationException out, so the
            // refusal escaped JobLauncher.run unchanged, passed straight through this listener, and the SQS
            // container acknowledged nothing. One FIFO group carries every submission, so the message sat at
            // the head of an ordered group and was redelivered every visibility window for the queue's whole
            // four-day retention while every valid submission behind it was starved.
            //
            // The validator now honours its declared type, so the arm above catches the known path. This case
            // pins the belt-and-braces arm for a refusal raised anywhere else on the launch path, and what it
            // asserts is the only thing that matters to the queue: drainReportJobQueue must RETURN, because
            // the framework acknowledges on a normal return and rethrows are what strand the group.
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class)))
                    .thenThrow(ValidationException.invalidField("startDate",
                            "startDate must not be after endDate"));

            listener.drainReportJobQueue(PAYLOAD, signedHeaders("submission-2v"), null);

            verify(jobLauncher).run(eq(reportJob), any(JobParameters.class));
        }

        @Test
        @DisplayName("the refusal arm is typed on the application's own validation failure, not on RuntimeException")
        void theRefusalArmIsNarrow() throws Exception {
            // The narrowness IS the contract. A validation refusal is known to be permanent, so consuming it
            // costs nothing; an arbitrary runtime fault is not - a store briefly unreachable succeeds on the
            // next attempt - so a catch widened to RuntimeException would silently convert every transient
            // fault into a discarded submission. Everything not named must still be returned to the queue.
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class)))
                    .thenThrow(new IllegalArgumentException("a transient fault that is not a refusal"));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            listener.drainReportJobQueue(PAYLOAD, signedHeaders("submission-2w"), null));
        }

        @Test
        @DisplayName("a body missing a required field is consumed, not launched with a null period")
        void anIncompleteBodyIsConsumed() throws Exception {
            listener.drainReportJobQueue("{\"reportName\":\"Monthly\"}",
                    signedHeaders("submission-3"), null);

            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("a message with no deduplication identifier falls back to the framework's message id")
        void theMessageIdIsTheFallback() throws Exception {
            final ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
            final JobExecution completed = completedExecution();
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class))).thenReturn(completed);
            final UUID messageId = UUID.randomUUID();

            listener.drainReportJobQueue(PAYLOAD, signedHeaders(Map.of(MessageHeaders.ID, messageId)), null);

            verify(jobLauncher).run(eq(reportJob), captor.capture());
            assertThat(captor.getValue().getString("submissionId")).isEqualTo(messageId.toString());
        }

        @Test
        @DisplayName("a message carrying neither identifier collapses onto one instance rather than repeating")
        void neitherIdentifierCollapsesOntoOneInstance() throws Exception {
            final ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
            final JobExecution completed = completedExecution();
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class))).thenReturn(completed);

            listener.drainReportJobQueue(PAYLOAD, signedHeaders(Map.of()), null);

            verify(jobLauncher).run(eq(reportJob), captor.capture());
            // A generated value here would make an unidentifiable message launch on every redelivery. A
            // fixed one makes those deliveries collapse onto a single instance, which is the safer failure.
            assertThat(captor.getValue().getString("submissionId")).isEqualTo("unidentified-submission");
        }

        @Test
        @DisplayName("null headers are tolerated, and carry no envelope code, so nothing is launched")
        void nullHeadersAreToleratedAndCarryNoCode() throws Exception {
            // Tolerated means "does not fail with a NullPointerException", which is what this always
            // asserted. It no longer means "launches": a delivery with no headers carries no envelope code,
            // and finding M-11 makes an unauthenticated submission one this consumer refuses to act on.
            listener.drainReportJobQueue(PAYLOAD, null, null);

            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
        }

        // =========================================================================================
        // FINDING C-02, severity BLOCKER. The listener used to log the publisher-set deduplication
        // identifier and the three payload fields. All four are chosen by whoever publishes, which is
        // not necessarily this application, so each was untrusted free text reaching a log stream past
        // a masking layer that redacts only LABELLED values - a bare card number or government
        // identifier carries no label. The tests below plant protected values in every one of those
        // four places and assert none reaches any part of any record the listener writes.
        // =========================================================================================

        /** A card-number-shaped value, sixteen digits as {@code CARD-NUM PIC X(16)} of CVACT02Y declares. */
        private static final String PAN_SHAPED = "4111111111111111";

        /** A government-identifier-shaped value, nine digits as {@code CUST-SSN PIC 9(09)} declares. */
        private static final String GOVERNMENT_ID_SHAPED = "123456789";

        /** A customer-surname-shaped value, of the kind {@code CUST-LAST-NAME} of CVCUS01Y holds. */
        private static final String SURNAME_SHAPED = "Whitmore";

        /**
         * Runs one delivery with an appender attached and returns every part of every record it wrote.
         *
         * <p>The formatted message, the raw pattern and each placeholder argument are all collected, because a
         * value passed as an argument is absent from the pattern and present in the rendered line.
         *
         * @param payload the body to deliver, verbatim
         * @param headers the headers to deliver, verbatim
         * @return one entry per inspected part; never null and never empty
         */
        private List<String> deliverAndCapture(final String payload, final Map<String, Object> headers) {
            final Logger logger = (Logger) LoggerFactory.getLogger("com.cardemo");
            final ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.setContext(logger.getLoggerContext());
            appender.start();
            logger.addAppender(appender);
            try {
                // The visibility handle is optional in the framework's argument resolution and a directly
                // invoked listener has no queue behind it, so null is what a real absence looks like.
                listener.drainReportJobQueue(payload, headers, null);
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }

            assertThat(appender.list)
                    .as("the delivery must have produced a record; nothing captured would make the assertion "
                            + "below vacuously true")
                    .isNotEmpty();

            final List<String> parts = new ArrayList<>();
            for (final ILoggingEvent event : appender.list) {
                parts.add(event.getFormattedMessage());
                parts.add(String.valueOf(event.getMessage()));
                if (event.getArgumentArray() != null) {
                    for (final Object argument : event.getArgumentArray()) {
                        parts.add(String.valueOf(argument));
                    }
                }
                // A throwable attached to the record is rendered by the encoder too, so its message and the
                // messages of its causes are as public as the pattern itself.
                for (IThrowableProxy cause = event.getThrowableProxy(); cause != null;
                        cause = cause.getCause()) {
                    parts.add(String.valueOf(cause.getMessage()));
                }
            }
            return parts;
        }

        @Test
        @DisplayName("a hostile deduplication identifier is never logged, and never in any form")
        void aHostileDeduplicationIdentifierIsNeverLogged() throws Exception {
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class))).thenReturn(mock(JobExecution.class));
            final String hostile = PAN_SHAPED + "-" + GOVERNMENT_ID_SHAPED;

            final List<String> logged = deliverAndCapture(PAYLOAD, Map.of(DEDUPLICATION_HEADER, hostile));

            for (final String part : logged) {
                assertThat(part)
                        .as("the transport identifier is publisher-chosen, so it may not reach a log stream in "
                                + "any form; what is logged is a one-way digest of it")
                        .doesNotContain(PAN_SHAPED)
                        .doesNotContain(GOVERNMENT_ID_SHAPED);
            }
            assertThat(String.join(" ", logged))
                    .as("the digest is sixteen lowercase hexadecimal characters, so a record is still joinable "
                            + "across the several lines one delivery produces")
                    .containsPattern("delivery [0-9a-f]{16} ");
        }

        @Test
        @DisplayName("the digest is stable across redeliveries and different between submissions")
        void theLoggedDeliveryIdentifierIsStableAndDistinct() throws Exception {
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class))).thenReturn(mock(JobExecution.class));

            final String first = String.join(" ", deliverAndCapture(PAYLOAD,
                    Map.of(DEDUPLICATION_HEADER, "submission-A")));
            final String redelivery = String.join(" ", deliverAndCapture(PAYLOAD,
                    Map.of(DEDUPLICATION_HEADER, "submission-A")));
            final String second = String.join(" ", deliverAndCapture(PAYLOAD,
                    Map.of(DEDUPLICATION_HEADER, "submission-B")));

            final String firstDigest = digestIn(first);
            assertThat(digestIn(redelivery))
                    .as("a redelivery of one submission must carry the same identifier, or the several records "
                            + "of one delivery cannot be joined")
                    .isEqualTo(firstDigest);
            assertThat(digestIn(second))
                    .as("two different submissions must carry different identifiers, or the identifier "
                            + "distinguishes nothing")
                    .isNotEqualTo(firstDigest);
        }

        /**
         * Extracts the sixteen-character delivery digest from a captured line.
         *
         * @param logged the joined log parts
         * @return the digest
         */
        private String digestIn(final String logged) {
            final Matcher matcher = Pattern.compile("delivery ([0-9a-f]{16}) ").matcher(logged);
            assertThat(matcher.find()).as("every record names the delivery by its digest").isTrue();
            return matcher.group(1);
        }

        @Test
        @DisplayName("a hostile payload field is refused by the contract and never logged")
        void aHostilePayloadFieldIsRefusedAndNeverLogged() throws Exception {
            // A publisher that is not this application's submission surface: a card number where the report
            // period belongs, and a date of birth where a parameter date belongs.
            final String hostile = "{\"reportName\":\"" + PAN_SHAPED + " " + SURNAME_SHAPED
                    + "\",\"startDate\":\"1974-03-19\",\"endDate\":\"" + GOVERNMENT_ID_SHAPED + "\"}";

            final List<String> logged = deliverAndCapture(hostile, Map.of(DEDUPLICATION_HEADER, "submission-H"));

            verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
            for (final String part : logged) {
                assertThat(part)
                        .as("the refusal record must name the reason, never the value: a constructor message "
                                + "that quoted the offending field would disclose exactly what was withheld")
                        .doesNotContain(PAN_SHAPED)
                        .doesNotContain(SURNAME_SHAPED)
                        .doesNotContain(GOVERNMENT_ID_SHAPED);
            }
            assertThat(String.join(" ", logged))
                    .as("what an operator gets instead is the failure class, which is what they act on. The "
                            + "mapper wraps a record constructor's own refusal in this type, which is why the "
                            + "listener catches it separately from a parse failure - the two mean different "
                            + "things: a publisher ignoring the contract, versus a corrupted body")
                    .contains("ValueInstantiationException");
        }

        @Test
        @DisplayName("a well-formed submission still logs neither the report name nor either date")
        void aWellFormedSubmissionLogsNoPayloadField() throws Exception {
            // Built before the stubbing rather than inside it: completedExecution() stubs a mock of its own,
            // and Mockito reads a nested when(...) as an unfinished stubbing of the outer one.
            final JobExecution completed = completedExecution();
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class))).thenReturn(completed);

            final List<String> logged = deliverAndCapture(PAYLOAD, signedHeaders("submission-W"));

            for (final String part : logged) {
                assertThat(part)
                        .as("screening the values is one control and withholding them is another; a "
                                + "confidentiality boundary needs both, because the screen bounds the shape "
                                + "of a value and not its content")
                        .doesNotContain("Monthly")
                        .doesNotContain("2022-07-01")
                        .doesNotContain("2022-07-31");
            }
            // The period is still recoverable where it belongs: the identifying job parameters of the launched
            // execution, under the job repository's own access control.
            final ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
            verify(jobLauncher).run(eq(reportJob), captor.capture());
            assertThat(captor.getValue().getString("reportName")).isEqualTo("Monthly");
        }

        @Test
        @DisplayName("a malformed body's parser context is not attached to the record either")
        void aMalformedBodyDoesNotPublishItsParserContext() {
            // Jackson quotes the offending token and its surrounding context in its message, so attaching the
            // exception as the record's cause published the very bytes the remediation withholds.
            final String hostile = "{\"reportName\":\"" + PAN_SHAPED + "\",oops";

            final List<String> logged = deliverAndCapture(hostile, Map.of(DEDUPLICATION_HEADER, "submission-M"));

            for (final String part : logged) {
                assertThat(part).doesNotContain(PAN_SHAPED);
            }
            assertThat(String.join(" ", logged)).contains("JsonParseException");
        }

        @Test
        @DisplayName("the listener binds the configured queue and carries a stable container id")
        void theListenerBindsTheConfiguredQueue() throws Exception {
            final SqsListener annotation = BatchConfig.ReportJobQueueListener.class
                    .getMethod("drainReportJobQueue", String.class, Map.class,
                            io.awspring.cloud.sqs.listener.Visibility.class)
                    .getAnnotation(SqsListener.class);

            assertThat(annotation).as("the consumer that replaces the JES2 internal reader").isNotNull();
            assertThat(annotation.queueNames())
                    .as("resolved from configuration, never a literal queue name")
                    .containsExactly("${carddemo.aws.sqs.report-queue}");
            assertThat(annotation.id())
                    .as("a stable id, so 'exactly one consumer of this queue' is assertable at runtime")
                    .isEqualTo("carddemoReportJobsListener");
        }

        @Test
        @DisplayName("the poll wait stays strictly below the queue client's per-attempt deadline")
        void thePollWaitFitsInsideTheClientsAttemptDeadline() throws Exception {
            // FINDING, severity Medium, REGRESSION GUARD. AwsConfig bounds every queue call at a ten-second
            // apiCallAttemptTimeout, and Spring Cloud AWS defaults a listener's poll to the same ten seconds,
            // so every long poll over an idle queue raced that deadline, was aborted, retried and finally
            // failed the whole thirty-second call: an idle deployment logged ApiCallTimeoutException at ERROR
            // every thirty seconds, measured at 371 occurrences in one afternoon. The relationship is the
            // contract, so it is asserted against AwsConfig's own constant rather than against a literal -
            // read reflectively, because widening that class's API to observe it would be the wrong trade.
            final SqsListener annotation = BatchConfig.ReportJobQueueListener.class
                    .getMethod("drainReportJobQueue", String.class, Map.class,
                            io.awspring.cloud.sqs.listener.Visibility.class)
                    .getAnnotation(SqsListener.class);

            assertThat(annotation.pollTimeoutSeconds())
                    .as("an explicit wait, because the library's default is exactly the value it must stay "
                            + "below")
                    .isNotBlank();
            final int pollWait = Integer.parseInt(annotation.pollTimeoutSeconds());

            final java.lang.reflect.Field attemptDeadline = com.cardemo.config.AwsConfig.class
                    .getDeclaredField("API_CALL_ATTEMPT_TIMEOUT_SECONDS");
            attemptDeadline.setAccessible(true);
            final int attemptTimeout = attemptDeadline.getInt(null);

            assertThat(pollWait)
                    .as("a receive must complete and return empty inside one attempt; equal is not enough, "
                            + "because equal is precisely the state that produced the error every thirty "
                            + "seconds")
                    .isPositive()
                    .isLessThan(attemptTimeout);
        }

        @Test
        @DisplayName("the invisibility window covers the whole processing interval, and matches the queue's")
        void theInvisibilityWindowCoversTheProcessingInterval() throws Exception {
            // FINDING M-02, severity High. Only the poll wait used to be declared, so the queue's own default
            // of 30 seconds governed how long a received submission stayed hidden - far shorter than a report
            // run, so the message reappeared while its own job was still going. The window is stated on both
            // sides of the boundary, and the two values must agree: the queue attribute governs the delivery
            // this listener did not make, the annotation governs the ones it does.
            final SqsListener annotation = BatchConfig.ReportJobQueueListener.class
                    .getMethod("drainReportJobQueue", String.class, Map.class,
                            io.awspring.cloud.sqs.listener.Visibility.class)
                    .getAnnotation(SqsListener.class);

            assertThat(annotation.messageVisibilitySeconds())
                    .as("an explicit window, because the service default is the value that produced the defect")
                    .isNotBlank();
            final int window = Integer.parseInt(annotation.messageVisibilitySeconds());
            assertThat(window)
                    .as("a report run backs up the cluster, sorts a generation and writes the report; thirty "
                            + "seconds is not the order of magnitude involved")
                    .isGreaterThan(60);

            final String provisioning = Files.readString(Path.of("localstack-init", "init-aws.sh"));
            assertThat(provisioning)
                    .as("the queue must be provisioned with the same window the listener declares, or a "
                            + "delivery made before this consumer received it would expire early")
                    .contains("readonly QUEUE_VISIBILITY_TIMEOUT_SECONDS='" + window + "'")
                    .contains("VisibilityTimeout=${QUEUE_VISIBILITY_TIMEOUT_SECONDS}");
        }

        @Test
        @DisplayName("the listener takes the visibility handle, so a long run can extend its own window")
        void theListenerTakesTheVisibilityHandle() throws Exception {
            final Visibility visibility = mock(Visibility.class);
            final JobExecution completed = completedExecution();
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class))).thenReturn(completed);

            listener.drainReportJobQueue(PAYLOAD, signedHeaders("submission-11"), visibility);

            verify(visibility).changeTo(Integer.parseInt(BatchConfig.ReportJobQueueListener.class
                    .getMethod("drainReportJobQueue", String.class, Map.class, Visibility.class)
                    .getAnnotation(SqsListener.class).messageVisibilitySeconds()));
        }

        @Test
        @DisplayName("a refused extension is logged and the validated submission still runs")
        void aRefusedExtensionStillLaunches() throws Exception {
            final Visibility visibility = mock(Visibility.class);
            final JobExecution completed = completedExecution();
            when(jobLauncher.run(eq(reportJob), any(JobParameters.class))).thenReturn(completed);
            org.mockito.Mockito.doThrow(new IllegalStateException("window already elapsed"))
                    .when(visibility).changeTo(anyInt());

            listener.drainReportJobQueue(PAYLOAD, signedHeaders("submission-12"), visibility);

            // The window applied at receive time still stands, so refusing to run a validated submission over
            // a failed refresh would be the worse outcome.
            verify(jobLauncher).run(eq(reportJob), any(JobParameters.class));
        }

        @Test
        @DisplayName("this tree declares exactly one queue consumer, so two executions cannot race a message")
        void exactlyOneQueueConsumerExistsInTheTree() {
            // A second consumer of one FIFO queue would let two executions contend for a single submission.
            // Asserted over the source tree because the second declaration would most likely be added to a
            // different class, where a container-scoped assertion in this file would never see it.
            // Matched as an annotation at the start of a line rather than as the substring anywhere,
            // because several classes discuss the listener in prose - AwsConfig names it to say it does not
            // declare one - and a substring search would count documentation as a declaration.
            final Path mainSources = Path.of("src", "main", "java");
            try (var paths = Files.walk(mainSources)) {
                final List<String> declaringFiles = paths
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> {
                            try {
                                return Files.readAllLines(path).stream()
                                        .map(String::trim)
                                        .anyMatch(line -> line.startsWith("@SqsListener"));
                            } catch (final IOException unreadable) {
                                throw new UncheckedIOException(unreadable);
                            }
                        })
                        .map(path -> path.getFileName().toString())
                        .toList();
                assertThat(declaringFiles)
                        .as("the one consumer lives in the batch layer's configuration; AwsConfig owns the "
                                + "clients and BatchPipelineOrchestrator's contract requires zero")
                        .containsExactly("BatchConfig.java");
            } catch (final IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
    }

    @Nested
    @DisplayName("The job-instance diagnostic context is owned by the jobs, not by a listener bean here")
    class JobInstanceDiagnosticContext {

        /** The six job classes, each of which attaches its own job-level listener. */
        private static final List<String> JOB_CLASSES = List.of(
                "BatchPipelineOrchestrator.java",
                "CombineTransactionsJob.java",
                "DailyTransactionPostingJob.java",
                "InterestCalculationJob.java",
                "StatementGenerationJob.java",
                "TransactionReportJob.java");

        @Test
        @DisplayName("no shared job listener bean is declared, because nothing would ever collect it")
        void noSharedJobListenerBeanIsDeclared() {
            // CFG-003: a jobInstanceMdcListener() @Bean returning JobExecutionListener stood here. Spring
            // Batch attaches listeners through JobBuilder.listener(...) and Boot's batch auto-configuration
            // collects none from the container, so that bean ran on no job at all - and its own tests passed
            // by calling it directly, which is what kept the deadness invisible. It is gone, and this is the
            // guard: reinstating it fails here, and the remedy is to attach the behaviour to a job.
            assertThat(Arrays.stream(BatchConfig.class.getDeclaredMethods()).map(Method::getName))
                    .as("a listener published from a @Bean method is collected by nothing, so it would run "
                            + "on no job; .listener(...) on a JobBuilder is the registration that works")
                    .doesNotContain("jobInstanceMdcListener", "jobInstanceIdOf");
            assertThat(Arrays.stream(BatchConfig.class.getDeclaredMethods()).map(Method::getReturnType))
                    .as("no method here yields a listener under any name")
                    .doesNotContain(JobExecutionListener.class);
        }

        @Test
        @DisplayName("every job class attaches its own listener and publishes the instance identifier itself")
        void everyJobClassAttachesItsOwnListener() {
            // This is what shows the removal cost no behaviour rather than merely deleting code: the key the
            // withdrawn bean would have published is published by each of the six jobs, on the execution the
            // framework actually runs. Asserted over the source because the registration sits inside a
            // JobBuilder chain, where no container-scoped assertion in this file could observe it.
            final Path jobs = Path.of("src", "main", "java", "com", "cardemo", "batch", "jobs");
            for (final String jobClass : JOB_CLASSES) {
                final String source = read(jobs.resolve(jobClass));
                assertThat(source)
                        .as("%s attaches its own job-level listener", jobClass)
                        .contains(".listener(");
                assertThat(source)
                        .as("%s publishes the job instance identifier into the diagnostic context", jobClass)
                        // enterBatchScope is the canonical entry point - finding M-02 - and it publishes the
                        // key itself, so a job that opens a batch scope satisfies this without naming the key.
                        .containsAnyOf("enterBatchScope", "propagateJobInstanceId",
                                "MDC_KEY_JOB_INSTANCE_ID", "MDC_JOB_INSTANCE_ID");
            }
        }

        /**
         * Reads a source file whose absence is a test failure rather than a condition to handle.
         *
         * @param path the file to read
         * @return its full text
         */
        private String read(final Path path) {
            try {
                return Files.readString(path);
            } catch (final IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
    }

    @Nested
    @DisplayName("Exactly one binding per DD, and every DD is covered")
    class BindingInventory {

        @Test
        @DisplayName("the four bindings claim the four DDs, each exactly once")
        void fourBindingsClaimTheFourDds() {
            final List<FileService.Dataset> bindings = allBindings();

            assertThat(bindings).hasSize(FileService.Dd.values().length);
            assertThat(bindings.stream().map(FileService.Dataset::dd))
                    .containsExactlyInAnyOrder(FileService.Dd.values());
        }

        @Test
        @DisplayName("the file service accepts the full set and refuses an incomplete one")
        void fileServiceAcceptsTheFullSetAndRefusesAnIncompleteOne() {
            final FileService complete = new FileService(new FileStatusMapper(), allBindings());
            complete.afterPropertiesSet();
            for (final FileService.Dd dd : FileService.Dd.values()) {
                assertThat(complete.isBound(dd)).isTrue();
            }

            final FileService incomplete = new FileService(new FileStatusMapper(),
                    List.of(batchConfig.acctFileDataset(mock(AccountRepository.class))));
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(incomplete::afterPropertiesSet)
                    .withMessageContaining("TRNXFILE")
                    .withMessageContaining("com.cardemo.config.BatchConfig");
        }

        @Test
        @DisplayName("every relational binding opens and closes with the success status")
        void everyBindingOpensAndClosesSuccessfully() {
            for (final FileService.Dataset binding : allBindings()) {
                if (binding.dd() == FileService.Dd.TRNXFILE) {
                    // TRNXFILE is not backed by a relation. It is the projected work cluster of
                    // app/jcl/CREASTMT.JCL:L83, so it can only open over the object STEP010 published, and
                    // outside a running step there is no published key to take. Its open contract is
                    // asserted by the TrnxFile group below, in both directions.
                    continue;
                }
                assertThat(binding.openInput()).as("OPEN INPUT %s", binding.dd().ddName()).isEqualTo("00");
                assertThat(binding.close()).as("CLOSE %s", binding.dd().ddName()).isEqualTo("00");
            }
        }

        @Test
        @DisplayName("TRNXFILE refuses to open when no step published a work object, rather than reading "
                + "the live transaction relation instead")
        void trnxFileRefusesToOpenWithoutAPublishedWorkObject() {
            final FileService.Dataset binding =
                    batchConfig.trnxFileDataset(mock(S3Operations.class), WORK_BUCKET);

            assertThat(binding.openInput())
                    .as("file status '35' is 'the file is not available', which is what an absent work "
                            + "cluster is. Falling back to the live relation is the Blocker this binding was "
                            + "changed to remove: STEP040 reads the FROZEN projection, never the sort's input")
                    .isEqualTo("35");
            assertThat(binding.readNext().status())
                    .as("a DD that never opened yields end of file rather than a record")
                    .isEqualTo("10");
        }

        @Test
        @DisplayName("a sequential DD refuses a keyed read and a random DD refuses a sequential read")
        void accessModesAreEnforced() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> batchConfig
                            .trnxFileDataset(mock(S3Operations.class), WORK_BUCKET).readByKey("k"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> batchConfig
                            .xrefFileDataset(mock(CardCrossReferenceRepository.class)).readByKey("k"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> batchConfig
                            .custFileDataset(mock(CustomerRepository.class)).readNext());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> batchConfig
                            .acctFileDataset(mock(AccountRepository.class)).readNext());
        }
    }

    @Nested
    @DisplayName("TRNXFILE - the PROJECTED work cluster, streamed from the object STEP010 published")
    class TrnxFile {

        /**
         * Runs a body inside a step scope whose job execution context carries a published work-object key.
         *
         * <p>The binding is a singleton and takes the key from the running step, because {@code FileService}
         * indexes every binding by DD in its constructor and a step-scoped proxy would be asked for its DD
         * outside any step. Establishing a real step scope here is therefore what exercises the production
         * path rather than a stand-in for it.
         *
         * @param publishedKey the key to publish, or {@code null} to publish none
         * @param body the assertions to run
         */
        private void withPublishedWorkObject(final String publishedKey, final Runnable body) {
            final JobExecution jobExecution = new JobExecution(1L);
            if (publishedKey != null) {
                jobExecution.getExecutionContext()
                        .putString(StatementGenerationJob.WORK_OBJECT_KEY_CONTEXT_ENTRY, publishedKey);
            }
            final StepExecution stepExecution = new StepExecution("statementGenerationEmitStep",
                    jobExecution, 1L);
            StepSynchronizationManager.register(stepExecution);
            try {
                body.run();
            } finally {
                StepSynchronizationManager.release();
            }
        }

        /**
         * Stubs the object store so that the work object streams the supplied records.
         *
         * @param objectStorage the mocked store
         * @param records the fixed-width records the object contains, already at the declared width
         */
        private void stubWorkObject(final S3Operations objectStorage, final String... records) {
            final byte[] image = String.join("", records).getBytes(StandardCharsets.ISO_8859_1);
            final S3Resource resource = mock(S3Resource.class);
            try {
                when(resource.getInputStream()).thenReturn(new ByteArrayInputStream(image));
            } catch (final IOException impossible) {
                throw new AssertionError("stubbing getInputStream cannot fail", impossible);
            }
            when(objectStorage.download(eq(WORK_BUCKET), eq(WORK_OBJECT_KEY))).thenReturn(resource);
        }

        /**
         * Builds one projected record: the card number, then the original head, padded to the declared width.
         *
         * @param transactionId the identifier that the projection places at bytes 17-32
         * @return a record of exactly the DD's declared width
         */
        private String projectedRecord(final String transactionId) {
            final int width = FileService.Dd.TRNXFILE.recordWidth();
            final String head = CARD_NUMBER + transactionId;
            return head + " ".repeat(width - head.length());
        }

        @Test
        @DisplayName("records arrive from the projected object in its own order, at the declared width, and "
                + "end of file is reported once the object is exhausted")
        void recordsArriveFromTheProjectedObject() {
            final S3Operations objectStorage = mock(S3Operations.class);
            stubWorkObject(objectStorage, projectedRecord("0000000000000001"),
                    projectedRecord("0000000000000002"));

            withPublishedWorkObject(WORK_OBJECT_KEY, () -> {
                final FileService.Dataset binding =
                        batchConfig.trnxFileDataset(objectStorage, WORK_BUCKET);
                assertThat(binding.openInput()).isEqualTo("00");

                final FileService.DatasetRead first = binding.readNext();
                assertThat(first.status()).isEqualTo("00");
                assertThat(first.record()).hasSize(FileService.Dd.TRNXFILE.recordWidth());
                assertThat(first.record())
                        .as("the OUTREC projection of app/jcl/CREASTMT.JCL:L54 puts the card number first, "
                                + "and it was applied by the PRODUCER - this binding does not re-project")
                        .startsWith(CARD_NUMBER);
                assertThat(first.record().substring(16, 32)).isEqualTo("0000000000000001");

                assertThat(binding.readNext().record().substring(16, 32)).isEqualTo("0000000000000002");

                final FileService.DatasetRead end = binding.readNext();
                assertThat(end.status()).isEqualTo("10");
                assertThat(end.hasRecord()).isFalse();
                assertThat(binding.close()).isEqualTo("00");
            });
        }

        @Test
        @DisplayName("the live transaction relation is never consulted, which is the Blocker this binding "
                + "was changed to close")
        void theLiveTransactionRelationIsNeverConsulted() {
            final S3Operations objectStorage = mock(S3Operations.class);
            stubWorkObject(objectStorage, projectedRecord("0000000000000001"));

            withPublishedWorkObject(WORK_OBJECT_KEY, () -> {
                final FileService.Dataset binding =
                        batchConfig.trnxFileDataset(objectStorage, WORK_BUCKET);
                binding.openInput();
                binding.readNext();
                binding.close();
            });

            // The binding has no repository collaborator at all any more, which is the structural proof: the
            // factory method's parameter list cannot express a live query. Asserted on the store instead,
            // because that is the collaborator it DOES have.
            verify(objectStorage).download(eq(WORK_BUCKET), eq(WORK_OBJECT_KEY));
            verifyNoMoreInteractions(objectStorage);
        }

        @Test
        @DisplayName("a truncated final record is a physical error, not end of file, because the DD is "
                + "fixed-length")
        void aTruncatedFinalRecordIsAPhysicalError() {
            final S3Operations objectStorage = mock(S3Operations.class);
            stubWorkObject(objectStorage, projectedRecord("0000000000000001"), "SHORT");

            withPublishedWorkObject(WORK_OBJECT_KEY, () -> {
                final FileService.Dataset binding =
                        batchConfig.trnxFileDataset(objectStorage, WORK_BUCKET);
                binding.openInput();
                assertThat(binding.readNext().status()).isEqualTo("00");
                assertThat(binding.readNext().status())
                        .as("reading a partial record as a whole one would slice every field from the wrong "
                                + "offset, so it is reported rather than absorbed")
                        .startsWith("9");
            });
        }

        @Test
        @DisplayName("re-opening restarts the stream, exactly as a second OPEN INPUT does")
        void reOpeningRestartsTheStream() {
            final S3Operations objectStorage = mock(S3Operations.class);
            final byte[] image = projectedRecord("0000000000000001")
                    .getBytes(StandardCharsets.ISO_8859_1);
            final S3Resource resource = mock(S3Resource.class);
            try {
                when(resource.getInputStream())
                        .thenReturn(new ByteArrayInputStream(image), new ByteArrayInputStream(image));
            } catch (final IOException impossible) {
                throw new AssertionError("stubbing getInputStream cannot fail", impossible);
            }
            when(objectStorage.download(eq(WORK_BUCKET), eq(WORK_OBJECT_KEY))).thenReturn(resource);

            withPublishedWorkObject(WORK_OBJECT_KEY, () -> {
                final FileService.Dataset binding =
                        batchConfig.trnxFileDataset(objectStorage, WORK_BUCKET);
                binding.openInput();
                assertThat(binding.readNext().status()).isEqualTo("00");
                assertThat(binding.readNext().status()).isEqualTo("10");

                assertThat(binding.openInput()).isEqualTo("00");
                assertThat(binding.readNext().status())
                        .as("a second OPEN INPUT re-downloads the object, so the first record is available "
                                + "again")
                        .isEqualTo("00");
            });
        }
    }

    @Nested
    @DisplayName("XREFFILE - the driving cross-reference stream")
    class XrefFile {

        @Test
        @DisplayName("the 36 populated bytes are followed by the copybook's 14-byte filler")
        void populatedBytesAreFollowedByTheFiller() {
            final CardCrossReferenceRepository repository = mock(CardCrossReferenceRepository.class);
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(eq(""), any(Pageable.class)))
                    .thenReturn(List.of(new CardCrossReference(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID)));
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(eq(CARD_NUMBER),
                    any(Pageable.class))).thenReturn(List.of());

            final FileService.Dataset binding = batchConfig.xrefFileDataset(repository);
            binding.openInput();
            final FileService.DatasetRead read = binding.readNext();

            assertThat(read.status()).isEqualTo("00");
            assertThat(read.record()).hasSize(FileService.Dd.XREFFILE.recordWidth());
            assertThat(read.record().substring(0, 16)).isEqualTo(CARD_NUMBER);
            assertThat(read.record().substring(16, 25))
                    .as("XREF-CUST-ID PIC 9(09) is zero padded")
                    .isEqualTo("000000011");
            assertThat(read.record().substring(25, 36))
                    .as("XREF-ACCT-ID PIC 9(11) is zero padded")
                    .isEqualTo("00000000001");
            assertThat(read.record().substring(36))
                    .as("FILLER PIC X(14), app/cpy/CVACT03Y.cpy:L8")
                    .isEqualTo(" ".repeat(14));
            assertThat(binding.readNext().status()).isEqualTo("10");
        }

        @Test
        @DisplayName("the keyset position advances to the last card number returned")
        void keysetPositionAdvances() {
            final CardCrossReferenceRepository repository = mock(CardCrossReferenceRepository.class);
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(eq(""), any(Pageable.class)))
                    .thenReturn(List.of(new CardCrossReference(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID),
                            new CardCrossReference(HIGHER_CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID)));
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(eq(HIGHER_CARD_NUMBER),
                    any(Pageable.class))).thenReturn(List.of());

            final FileService.Dataset binding = batchConfig.xrefFileDataset(repository);
            binding.openInput();
            assertThat(binding.readNext().record()).startsWith(CARD_NUMBER);
            assertThat(binding.readNext().record()).startsWith(HIGHER_CARD_NUMBER);
            assertThat(binding.readNext().status())
                    .as("the second window is requested from the greater card number, and is empty")
                    .isEqualTo("10");
        }
    }

    @Nested
    @DisplayName("CUSTFILE and ACCTFILE - the two keyed reads")
    class KeyedReads {

        @Test
        @DisplayName("a customer record is rendered in the CVCUS01Y layout at exactly 500 characters")
        void customerRecordIsRenderedInLayout() {
            final CustomerRepository repository = mock(CustomerRepository.class);
            when(repository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer()));

            final FileService.DatasetRead read = batchConfig.custFileDataset(repository)
                    .readByKey("000000011");

            assertThat(read.status()).isEqualTo("00");
            assertThat(read.record()).hasSize(FileService.Dd.CUSTFILE.recordWidth());
            assertThat(read.record().substring(0, 9)).isEqualTo("000000011");
            assertThat(read.record().substring(9, 34)).isEqualTo("SYNTHETICA" + " ".repeat(15));
            assertThat(read.record().substring(279, 288))
                    .as("CUST-SSN PIC 9(09) sits after the two 15-character telephone numbers, so at "
                            + "9+25+25+25+50+50+50+2+3+10+15+15 = 279")
                    .isEqualTo("999009999");
        }

        @Test
        @DisplayName("an absent customer key reports the record-not-found status rather than throwing")
        void absentCustomerKeyReportsNotFound() {
            final CustomerRepository repository = mock(CustomerRepository.class);
            when(repository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

            final FileService.DatasetRead read = batchConfig.custFileDataset(repository)
                    .readByKey("000000011");

            assertThat(read.status()).isEqualTo("23");
            assertThat(read.hasRecord()).isFalse();
        }

        @Test
        @DisplayName("an account record is rendered at 300 characters with zoned-decimal money fields")
        void accountRecordIsRenderedInLayout() {
            final AccountRepository repository = mock(AccountRepository.class);
            when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));

            final FileService.DatasetRead read = batchConfig.acctFileDataset(repository)
                    .readByKey("00000000001");

            assertThat(read.status()).isEqualTo("00");
            assertThat(read.record()).hasSize(FileService.Dd.ACCTFILE.recordWidth());
            assertThat(read.record().substring(0, 11)).isEqualTo("00000000001");
            assertThat(read.record().substring(11, 12)).isEqualTo("Y");
            assertThat(read.record().substring(12, 24))
                    .as("+194.00 as PIC S9(10)V99 is twelve characters: eleven digits then the overpunch "
                            + "that carries both the twelfth digit and the sign")
                    .isEqualTo("00000001940{");
            assertThat(read.record().charAt(23))
                    .as("'{' is the +0 overpunch of app/data/ASCII/acctdata.txt:L1")
                    .isEqualTo('{');
        }

        @Test
        @DisplayName("a negative balance carries the negative overpunch, never an absolute value")
        void negativeBalanceCarriesTheNegativeOverpunch() {
            final AccountRepository repository = mock(AccountRepository.class);
            when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.of(accountWithBalance(
                    new BigDecimal("-919.00"))));

            final FileService.DatasetRead read = batchConfig.acctFileDataset(repository)
                    .readByKey("00000000001");

            assertThat(read.record().substring(12, 24))
                    .as("-919.00 gives eleven digits 00000009190 then '}', the -0 overpunch")
                    .isEqualTo("00000009190}");
        }

        @Test
        @DisplayName("an absent account key reports the record-not-found status")
        void absentAccountKeyReportsNotFound() {
            final AccountRepository repository = mock(AccountRepository.class);
            when(repository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            assertThat(batchConfig.acctFileDataset(repository).readByKey("00000000001").status())
                    .isEqualTo("23");
        }
    }

    /**
     * Builds all four bindings over mocked repositories.
     *
     * @return the four bindings, one per DD
     */
    private List<FileService.Dataset> allBindings() {
        return List.of(
                batchConfig.trnxFileDataset(mock(S3Operations.class), WORK_BUCKET),
                batchConfig.xrefFileDataset(mock(CardCrossReferenceRepository.class)),
                batchConfig.custFileDataset(mock(CustomerRepository.class)),
                batchConfig.acctFileDataset(mock(AccountRepository.class)));
    }

    /**
     * Builds one {@code TRAN-RECORD} of {@code app/cpy/CVTRA05Y.cpy}.
     *
     * @param transactionId {@code TRAN-ID PIC X(16)}
     * @param cardNumber {@code TRAN-CARD-NUM PIC X(16)}
     * @return the transaction; never {@code null}
     */
    private static Transaction transaction(final String transactionId, final String cardNumber) {
        return new Transaction(transactionId, "01", Integer.valueOf(1), "POS       ",
                "SYNTHETIC PURCHASE", new BigDecimal("12.34"), Long.valueOf(123456789L),
                "SAMPLE MERCHANT", "SPRINGFIELD", "0000012345", cardNumber,
                "2022-01-01-00.00.00.000000", "2022-01-02-00.00.00.000000");
    }

    /**
     * Builds the {@code ACCOUNT-RECORD} of {@code app/cpy/CVACT01Y.cpy} with the first fixture row's values.
     *
     * @return the account; never {@code null}
     */
    private static Account account() {
        return accountWithBalance(new BigDecimal("194.00"));
    }

    /**
     * Builds the {@code ACCOUNT-RECORD} with a caller-chosen balance, so sign handling is observable.
     *
     * @param currentBalance {@code ACCT-CURR-BAL PIC S9(10)V99}
     * @return the account; never {@code null}
     */
    private static Account accountWithBalance(final BigDecimal currentBalance) {
        return new Account(ACCOUNT_ID, "Y", currentBalance, new BigDecimal("2020.00"),
                new BigDecimal("1020.00"), "2015-07-01", "2025-06-30", "2020-07-01",
                new BigDecimal("0.00"), new BigDecimal("0.00"), "0000012345", "DEFAULT   ");
    }

    /**
     * Builds the {@code CUSTOMER-RECORD} of {@code app/cpy/CVCUS01Y.cpy}. Every protected component is
     * synthetic and unusable: the social-security area {@code 999} is never issued and both telephone
     * numbers sit in the reserved fiction range.
     *
     * @return the customer; never {@code null}
     */
    private static Customer customer() {
        return new Customer(CUSTOMER_ID, "SYNTHETICA", "Q", "TESTCASE", "1 SAMPLE STREET", "SUITE 100",
                "SPRINGFIELD", "IL", "USA", "0000012345", "(555) 010-0001", "(555) 010-0002",
                "999009999", "SYNTHETIC-ID-0001", "1990-01-01", "0000000001", "Y", "750");
    }

    /**
     * Package prefixes the reachability walk descends into. Anything else is treated as a leaf.
     *
     * <p>Bounded on purpose. A built {@code Step} is a framework object graph, and the only parts of it this
     * suite has a claim about are the components this project contributed and the framework wiring that holds
     * them, so descending into the JDK or into a third-party library would add cost and no assertion.
     */
    private static final List<String> TRAVERSED_PACKAGES =
            List.of("com.cardemo.", "org.springframework.");

    /** Upper bound on visited objects, so an unexpectedly wide graph cannot stall the suite. */
    private static final int REACHABILITY_BUDGET = 20_000;

    /**
     * Every object of a given type reachable from a root by following fields, collection elements and array
     * elements.
     *
     * <p>Used instead of naming the framework's own internal field path - {@code TaskletStep.tasklet} to
     * {@code ChunkOrientedTasklet.chunkProcessor} to {@code SimpleChunkProcessor.itemWriter} - because that
     * path is a framework implementation detail that a patch release may rename, whereas "the step wires
     * exactly one writer, and it holds nothing it could store through" is a claim about this project.
     *
     * <p>Mocks are neither reported nor descended into: a mock is a stand-in this suite supplied, so counting
     * one would be counting the harness rather than the wiring.
     *
     * @param root the object to walk from
     * @param type the type to collect
     * @return the distinct instances found, in discovery order
     */
    private static Set<Object> reachable(final Object root, final Class<?> type) {
        final Set<Object> found = new LinkedHashSet<>();
        final Map<Object, Boolean> visited = new IdentityHashMap<>();
        final Deque<Object> pending = new ArrayDeque<>();
        offer(pending, root);
        while (!pending.isEmpty() && visited.size() < REACHABILITY_BUDGET) {
            final Object current = pending.poll();
            if (visited.put(current, Boolean.TRUE) != null) {
                continue;
            }
            final boolean stubbed = mockingDetails(current).isMock();
            if (type.isInstance(current) && !stubbed) {
                found.add(current);
            }
            if (!stubbed) {
                enqueueChildren(current, pending);
            }
        }
        return found;
    }

    /**
     * Enqueues everything directly reachable from one object.
     *
     * @param current the object being expanded
     * @param pending the walk's work queue
     */
    private static void enqueueChildren(final Object current, final Deque<Object> pending) {
        if (current instanceof final Collection<?> elements) {
            elements.forEach(element -> offer(pending, element));
            return;
        }
        if (current instanceof final Map<?, ?> entries) {
            entries.values().forEach(value -> offer(pending, value));
            return;
        }
        final Class<?> concrete = current.getClass();
        if (concrete.isArray()) {
            if (!concrete.getComponentType().isPrimitive()) {
                for (final Object element : (Object[]) current) {
                    offer(pending, element);
                }
            }
            return;
        }
        if (TRAVERSED_PACKAGES.stream().noneMatch(prefix -> concrete.getName().startsWith(prefix))) {
            return;
        }
        for (Class<?> declaring = concrete; declaring != null && !Object.class.equals(declaring);
                declaring = declaring.getSuperclass()) {
            for (final Field field : declaring.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    offer(pending, field.get(current));
                } catch (final ReflectiveOperationException | RuntimeException refused) {
                    // A field the runtime refuses to open is simply not descended into. That cannot hide a
                    // writer this project wired, because the wiring path runs through classes of the two
                    // traversed packages, both of which are on the class path and therefore openable.
                    continue;
                }
            }
        }
    }

    /**
     * Adds a value to the walk queue unless it is {@code null}, which the queue implementation forbids.
     *
     * @param pending the walk's work queue
     * @param value the value to add, which may be {@code null}
     */
    private static void offer(final Deque<Object> pending, final Object value) {
        if (value != null) {
            pending.add(value);
        }
    }
}
