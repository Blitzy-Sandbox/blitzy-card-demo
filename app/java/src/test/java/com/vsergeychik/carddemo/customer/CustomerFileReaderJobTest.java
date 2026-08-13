package com.vsergeychik.carddemo.customer;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.customer.CustomerService.Execution;
import com.vsergeychik.carddemo.customer.CustomerService.Sysout;
import com.vsergeychik.carddemo.customer.CustomerService.SysoutSink;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.item.ChunkOrientedTasklet;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit contract for {@link CustomerFileReaderJob}, the batch shell around {@code CBCUS01C}.
 */
@DisplayName("CustomerFileReaderJob - READCUST STEP05 / CBCUS01C")
class CustomerFileReaderJobTest {
    private static final int FIXTURE_RECORD_COUNT = 50;
    private static final int FIFTY_RECORD_LINE_COUNT = 102;
    private static final String FIXTURE_RESOURCE = "/fixtures/custdata.txt";

    private static final Path MODULE_DIRECTORY = Path.of("app", "java");
    private static final Path JOB_SOURCE = MODULE_DIRECTORY.resolve(Path.of("src", "main", "java",
            "com", "vsergeychik", "carddemo", "customer", "CustomerFileReaderJob.java"));
    private static final Path CUSTOMER_SOURCE_DIRECTORY = MODULE_DIRECTORY.resolve(Path.of("src", "main",
            "java", "com", "vsergeychik", "carddemo", "customer"));
    private static final Path TEST_SOURCE = MODULE_DIRECTORY.resolve(Path.of("src", "test", "java",
            "com", "vsergeychik", "carddemo", "customer", "CustomerFileReaderJobTest.java"));
    private static final Path POM = MODULE_DIRECTORY.resolve("pom.xml");

    private static final class CapturedSysout implements SysoutSink {
        private final List<String> lines = new ArrayList<>();

        @Override
        public void write(String line) {
            lines.add(line);
        }

        private List<String> lines() {
            return List.copyOf(lines);
        }
    }

    private record PresentBean<T>(T bean) implements ObjectProvider<T> {
        @Override
        public T getObject() {
            return bean;
        }
    }

    private static final class AbsentBean<T> implements ObjectProvider<T> {
        @Override
        public T getObject() {
            throw new NoSuchBeanDefinitionException("no bean is declared in this plain unit test");
        }
    }

    private record TaskletCall(StepContribution contribution, ChunkContext chunkContext) {
    }

    private record ExecutedJob(BatchConfig batchConfig,
                               JobExecution jobExecution,
                               StepExecution stepExecution,
                               CapturedSysout sysout) {
    }

    private static JobContracts jobContracts() {
        return jobContracts(List.of(new StepContract(CustomerService.STEP_NAME,
                CustomerService.PROGRAM_ID, false)));
    }

    private static JobContracts jobContracts(StepContract step) {
        return jobContracts(List.of(step));
    }

    private static JobContracts jobContracts(List<StepContract> steps) {
        JobContracts contracts = new JobContracts();
        contracts.put(CustomerFileReaderJob.JOB_KEY,
                new JobContract(CustomerService.PROGRAM_ID, List.of(), steps, null, Map.of()));
        return contracts;
    }

    private static BatchConfig batchConfig(JobRepository repository,
                                           PlatformTransactionManager transactionManager,
                                           JobContracts contracts) {
        try {
            Constructor<?> constructor = Arrays.stream(BatchConfig.class.getConstructors())
                    .filter(candidate -> candidate.getParameterCount() == 4)
                    .filter(candidate -> candidate.getParameterTypes()[0] == ObjectProvider.class)
                    .filter(candidate -> candidate.getParameterTypes()[1] == ObjectProvider.class)
                    .filter(candidate -> candidate.getParameterTypes()[2] == JobContracts.class)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "BatchConfig no longer exposes its documented four-argument constructor"));
            Object datasetBindings = constructor.getParameterTypes()[3].getConstructor().newInstance();
            return (BatchConfig) constructor.newInstance(new PresentBean<>(repository),
                    new PresentBean<>(transactionManager), contracts, datasetBindings);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot instantiate the documented BatchConfig surface",
                    failure);
        }
    }

    private static BatchConfig mockedScaffolding(JobContracts contracts) {
        return batchConfig(mock(JobRepository.class), mock(PlatformTransactionManager.class), contracts);
    }

    private static CustomerFileReaderJob subject(CustomerService service, SysoutSink sink) {
        return subject(mockedScaffolding(jobContracts()), service, new PresentBean<>(sink));
    }

    private static CustomerFileReaderJob subject(BatchConfig batchConfig,
                                                 CustomerService service,
                                                 ObjectProvider<SysoutSink> sinkProvider) {
        return new CustomerFileReaderJob(batchConfig, service, sinkProvider);
    }

    private static List<String> fixtureRows() {
        try (InputStream stream =
                     CustomerFileReaderJobTest.class.getResourceAsStream(FIXTURE_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing classpath fixture " + FIXTURE_RESOURCE);
            }
            List<String> rows = new String(stream.readAllBytes(), StandardCharsets.US_ASCII)
                    .lines()
                    .toList();
            if (rows.size() != FIXTURE_RECORD_COUNT) {
                throw new IllegalStateException("The customer fixture must contain exactly "
                        + FIXTURE_RECORD_COUNT + " records, but contains " + rows.size());
            }
            return rows;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static List<String> successfulLines(List<String> records) {
        List<String> lines = new ArrayList<>(
                CustomerService.expectedSysoutLineCount(records.size()));
        lines.add(CustomerService.START_OF_EXECUTION);
        for (String record : records) {
            lines.add(record);
            lines.add(record);
        }
        lines.add(CustomerService.END_OF_EXECUTION);
        return List.copyOf(lines);
    }

    private static void emitSuccessfulRun(Sysout sysout, List<String> records) {
        sysout.display(CustomerService.START_OF_EXECUTION);
        for (String record : records) {
            sysout.displayCustomerRecord(record);
            sysout.displayCustomerRecord(record);
        }
        sysout.display(CustomerService.END_OF_EXECUTION);
    }

    private static CustomerService serviceReturning(List<String> records) {
        CustomerService service = mock(CustomerService.class);
        when(service.readAndPrintCustomerFileTo(any(SysoutSink.class))).thenAnswer(invocation -> {
            SysoutSink sink = invocation.getArgument(0, SysoutSink.class);
            emitSuccessfulRun(new Sysout(sink), records);
            return records.size();
        });
        return service;
    }

    private static List<String> abendLines() {
        return List.of(CustomerService.START_OF_EXECUTION,
                CustomerService.ERROR_READING_CUSTOMER_FILE,
                "35",
                CustomerService.ABENDING_PROGRAM);
    }

    private static CustomerService serviceThrowing(AbendException abend) {
        CustomerService service = mock(CustomerService.class);
        when(service.readAndPrintCustomerFileTo(any(SysoutSink.class))).thenAnswer(invocation -> {
            SysoutSink sink = invocation.getArgument(0, SysoutSink.class);
            abendLines().forEach(sink::write);
            throw abend;
        });
        return service;
    }

    private static TaskletCall taskletCall() {
        JobExecution jobExecution = new JobExecution(71L);
        StepExecution stepExecution =
                new StepExecution(CustomerService.STEP_NAME, jobExecution);
        return new TaskletCall(new StepContribution(stepExecution),
                new ChunkContext(new StepContext(stepExecution)));
    }

    private static ExecutedJob executeJob(CustomerService service) throws Exception {
        ResourcelessJobRepository repository = new ResourcelessJobRepository();
        BatchConfig batch = batchConfig(repository, new ResourcelessTransactionManager(),
                jobContracts());
        CapturedSysout sink = new CapturedSysout();
        CustomerFileReaderJob subject =
                subject(batch, service, new PresentBean<>(sink));
        JobExecution execution = repository.createJobExecution(
                CustomerFileReaderJob.JOB_NAME, new JobParameters());

        subject.customerFileReaderJob().execute(execution);

        StepExecution stepExecution = execution.getStepExecutions().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "READCUST must record its one STEP05 execution"));
        return new ExecutedJob(batch, execution, stepExecution, sink);
    }

    private static Path modulePath(Path relativePath) {
        Path normalized = relativePath.normalize();
        if (normalized.isAbsolute() || !normalized.startsWith(MODULE_DIRECTORY)) {
            throw new IllegalArgumentException(
                    "Source inspection is confined to app/java: " + relativePath);
        }

        for (Path cursor = Path.of("").toAbsolutePath().normalize();
             cursor != null;
             cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(normalized);
            if (Files.exists(candidate)) {
                try {
                    Path moduleRoot = cursor.resolve(MODULE_DIRECTORY).toRealPath();
                    Path resolved = candidate.toRealPath();
                    if (!resolved.startsWith(moduleRoot)) {
                        throw new IllegalArgumentException(
                                "Resolved path escaped app/java: " + relativePath);
                    }
                    return resolved;
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
        }
        throw new IllegalStateException("Cannot locate module path " + relativePath);
    }

    private static String moduleSource(Path relativePath) {
        Path source = modulePath(relativePath);
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Expected a regular source file: " + relativePath);
        }
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String customerProductionSources() {
        try (Stream<Path> files = Files.walk(modulePath(CUSTOMER_SOURCE_DIRECTORY))) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .map(CustomerFileReaderJobTest::readUtf8)
                    .collect(Collectors.joining(System.lineSeparator()));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String readUtf8(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String executableJava(String source) {
        StringBuilder code = new StringBuilder(source.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean stringLiteral = false;
        boolean characterLiteral = false;
        boolean escaped = false;

        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';

            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                    code.append(current);
                }
            } else if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
            } else if (stringLiteral) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    stringLiteral = false;
                }
            } else if (characterLiteral) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '\'') {
                    characterLiteral = false;
                }
            } else if (current == '/' && next == '/') {
                lineComment = true;
                index++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                index++;
            } else if (current == '"') {
                stringLiteral = true;
                code.append(' ');
            } else if (current == '\'') {
                characterLiteral = true;
                code.append(' ');
            } else {
                code.append(current);
            }
        }
        return code.toString();
    }

    private static String compiledForm(Class<?> type) {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream stream = type.getResourceAsStream(resourceName)) {
            if (stream == null) {
                throw new IllegalStateException("Missing compiled class resource " + resourceName);
            }
            return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Object shippedProperty(String resourceName, String propertyName) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(resourceName,
                    new ClassPathResource(resourceName));
            return sources.stream()
                    .map(source -> source.getProperty(propertyName))
                    .filter(value -> value != null)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            propertyName + " is absent from " + resourceName));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static List<String> declaredTypeNames() {
        List<String> names = new ArrayList<>();
        for (Field field : CustomerFileReaderJob.class.getDeclaredFields()) {
            names.add(field.getType().getName());
            names.add(field.getGenericType().getTypeName());
        }
        for (Constructor<?> constructor : CustomerFileReaderJob.class.getDeclaredConstructors()) {
            Arrays.stream(constructor.getParameterTypes())
                    .map(Class::getName)
                    .forEach(names::add);
            Arrays.stream(constructor.getGenericParameterTypes())
                    .map(java.lang.reflect.Type::getTypeName)
                    .forEach(names::add);
        }
        return List.copyOf(names);
    }

    @Nested
    @DisplayName("JobAndStepShape")
    class JobAndStepShape {
        @Test
        @DisplayName("the published Job and ordinary Step factory use the READCUST identities")
        void jobBeanAndStepCarryTheJclNames() throws ReflectiveOperationException {
            CustomerService service = mock(CustomerService.class);
            CustomerFileReaderJob subject =
                    subject(service, new CapturedSysout());
            Method jobFactory =
                    CustomerFileReaderJob.class.getDeclaredMethod("customerFileReaderJob");
            Method stepFactory =
                    CustomerFileReaderJob.class.getDeclaredMethod("customerFileReaderStep");

            assertThat(CustomerFileReaderJob.class.getAnnotation(Configuration.class).value())
                    .isEqualTo(CustomerFileReaderJob.CONFIGURATION_BEAN_NAME);
            assertThat(jobFactory.getAnnotation(Bean.class)).isNotNull();
            assertThat(jobFactory.getName()).isEqualTo(CustomerFileReaderJob.JOB_NAME);
            assertThat(stepFactory.getAnnotation(Bean.class))
                    .as("the actual API keeps Step ordinary so sibling jobs do not create ambiguity")
                    .isNull();

            Job job = subject.customerFileReaderJob();
            Step step = subject.customerFileReaderStep();
            assertThat(job.getName()).isEqualTo(CustomerFileReaderJob.JOB_NAME);
            assertThat(job).isInstanceOf(SimpleJob.class);
            assertThat(((SimpleJob) job).getStepNames())
                    .as("READCUST.jcl contains exactly one EXEC statement")
                    .hasSize(1)
                    .containsExactly(CustomerService.STEP_NAME);
            assertThat(step.getName()).isEqualTo(CustomerService.STEP_NAME);
            assertThat(CustomerFileReaderJob.CONFIGURATION_BEAN_NAME)
                    .isNotEqualTo(CustomerFileReaderJob.JOB_NAME);
        }

        @Test
        @DisplayName("STEP05 is one single-pass tasklet and has no item pipeline or commit interval")
        void stepIsATaskletAndNotAChunkPipeline() {
            Step step = subject(mock(CustomerService.class), new CapturedSysout())
                    .customerFileReaderStep();
            String bytecode = compiledForm(CustomerFileReaderJob.class);
            String source = moduleSource(JOB_SOURCE);

            assertThat(step).isInstanceOf(TaskletStep.class);
            assertThat(((TaskletStep) step).getTasklet())
                    .isNotInstanceOf(ChunkOrientedTasklet.class);
            assertThat(bytecode).doesNotContain(
                    "org/springframework/batch/item/ItemReader",
                    "org/springframework/batch/item/ItemProcessor",
                    "org/springframework/batch/item/ItemWriter",
                    "org/springframework/batch/core/step/builder/SimpleStepBuilder");
            assertThat(source)
                    .contains("batchConfig.taskletStep(stepContract.name(), "
                            + "customerFileDisplayTasklet())")
                    .doesNotContain("batchConfig.chunkStep(");
        }

        @Test
        @DisplayName("the job declares no scheduling, trigger, retry, skip or transition policy")
        void jobHasNoExecutionPolicyBeyondItsSingleStep() {
            String bytecode = compiledForm(CustomerFileReaderJob.class);
            String source = moduleSource(JOB_SOURCE);

            assertThat(bytecode).doesNotContain(
                    "org/springframework/scheduling/annotation/Scheduled",
                    "org/springframework/scheduling/Trigger",
                    "org/springframework/retry/RetryPolicy",
                    "org/springframework/batch/core/step/skip/SkipPolicy");
            assertThat(source).doesNotContain(
                    "@Scheduled",
                    ".retry(",
                    ".skip(",
                    ".faultTolerant(",
                    ".next(",
                    ".on(",
                    ".from(");
        }

        @Test
        @DisplayName("both shipped profiles disable startup execution while the Job remains buildable")
        void automaticStartupIsDisabled() {
            CustomerService service = mock(CustomerService.class);
            CustomerFileReaderJob subject =
                    subject(service, new CapturedSysout());

            assertThat(shippedProperty("application.yml", "spring.batch.job.enabled"))
                    .isEqualTo(false);
            assertThat(shippedProperty("application-test.yml", "spring.batch.job.enabled"))
                    .isEqualTo(false);
            assertThat(subject.customerFileReaderJob()).isNotNull();
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("READCUST has empty parameters, one ungated step and configuration-bound CUSTFILE")
        void parametersFlowAndDatasetBindingMatchTheJcl() {
            CustomerFileReaderJob subject =
                    subject(mock(CustomerService.class), new CapturedSysout());
            JobParameters parameters = subject.jobParameters();

            assertThat(parameters.isEmpty()).isTrue();
            assertThat(parameters.getParameters()).isEmpty();
            assertThat(parameters.getParameters()).doesNotContainKey("parmDate");
            assertThat(subject.stepContract())
                    .isEqualTo(new StepContract(CustomerService.STEP_NAME,
                            CustomerService.PROGRAM_ID, false));
            assertThat(((SimpleJob) subject.customerFileReaderJob()).getStepNames())
                    .containsExactly(CustomerService.STEP_NAME);

            assertThat(shippedProperty("application.yml",
                    "carddemo.datasets.CUSTFILE.dsname").toString())
                    .startsWith("${CARDDEMO_DATASET_CUSTFILE:")
                    .endsWith("}");
            assertThat(shippedProperty("application-test.yml",
                    "carddemo.datasets.CUSTFILE.dsname"))
                    .isEqualTo("CARDDEMO.TEST.CUSTDATA.VSAM.KSDS");
        }

        @Test
        @DisplayName("absence of a sink bean resolves the service-owned standard-output sink, in the "
                + "code page the service reads the customer master in")
        void standardOutputIsTheDeterministicFallback() {
            CustomerService service = mock(CustomerService.class);
            when(service.datasetCharset()).thenReturn(StandardCharsets.US_ASCII);
            when(service.standardOutputSysoutSink()).thenCallRealMethod();
            CustomerFileReaderJob subject = subject(mockedScaffolding(jobContracts()),
                    service, new AbsentBean<>());

            assertThat(subject.sysoutSink())
                    .isInstanceOf(CustomerService.PrintStreamSysoutSink.class);
            PrintStream stream =
                    ((CustomerService.PrintStreamSysoutSink) subject.sysoutSink()).stream();
            assertThat(stream.charset()).isEqualTo(StandardCharsets.US_ASCII);
            assertThat(stream).isNotSameAs(System.out);

            verify(service, times(1)).datasetCharset();
        }

        @Test
        @DisplayName("the fallback sink is resolved once, in the constructor, not per record")
        void theFallbackSinkIsResolvedOnce() {
            CustomerService service = mock(CustomerService.class);
            when(service.datasetCharset()).thenReturn(StandardCharsets.US_ASCII);
            when(service.standardOutputSysoutSink()).thenCallRealMethod();
            CustomerFileReaderJob subject = subject(mockedScaffolding(jobContracts()),
                    service, new AbsentBean<>());

            assertThat(subject.sysoutSink()).isSameAs(subject.sysoutSink());
            verify(service, times(1)).datasetCharset();
        }
    }

    @Nested
    @DisplayName("Delegation")
    class Delegation {
        @Test
        @DisplayName("CustomerService is the only decision-making collaborator")
        void onlyTheServiceOwnsProgramDecisions() {
            Constructor<?> constructor =
                    CustomerFileReaderJob.class.getDeclaredConstructors()[0];
            List<Field> instanceFields = Arrays.stream(
                            CustomerFileReaderJob.class.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .toList();
            List<Class<?>> decisionCollaborators = Stream.concat(
                            instanceFields.stream().map(Field::getType),
                            Arrays.stream(constructor.getParameterTypes()))
                    .filter(type -> type.getSimpleName().endsWith("Service")
                            || type.getSimpleName().endsWith("Repository"))
                    .distinct()
                    .toList();

            assertThat(decisionCollaborators).containsExactly(CustomerService.class);
            assertThat(constructor.getParameterTypes()).containsExactly(
                    BatchConfig.class, CustomerService.class, ObjectProvider.class);

            List<String> forbiddenDirectTypes = List.of(
                    "com.vsergeychik.carddemo.customer.Customer" + "Repository",
                    "org.springframework.jdbc.core.Jdbc" + "Template",
                    "javax.sql.Data" + "Source",
                    "java.nio.charset.Char" + "set");
            assertThat(declaredTypeNames()).noneMatch(typeName ->
                    forbiddenDirectTypes.stream().anyMatch(typeName::contains));
        }

        @Test
        @DisplayName("one tasklet invocation delegates once, finishes, and publishes all 102 lines")
        void oneInvocationSurfacesTheFiftyRecordRun() throws Exception {
            List<String> records = fixtureRows();
            List<String> expected = successfulLines(records);
            CustomerService service = serviceReturning(records);
            CapturedSysout sink = new CapturedSysout();
            Tasklet tasklet = subject(service, sink).customerFileDisplayTasklet();
            TaskletCall call = taskletCall();

            RepeatStatus status =
                    tasklet.execute(call.contribution(), call.chunkContext());

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            assertThat(call.contribution().getReadCount()).isEqualTo(FIXTURE_RECORD_COUNT);
            assertThat(sink.lines())
                    .hasSize(FIFTY_RECORD_LINE_COUNT)
                    .containsExactlyElementsOf(expected);
            assertThat(sink.lines()).hasSize(
                    1 + FIXTURE_RECORD_COUNT * CustomerService.DISPLAYS_PER_RECORD + 1);
            for (int record = 0; record < FIXTURE_RECORD_COUNT; record++) {
                int firstImage = 1 + record * CustomerService.DISPLAYS_PER_RECORD;
                assertThat(sink.lines().get(firstImage))
                        .isEqualTo(records.get(record))
                        .isEqualTo(sink.lines().get(firstImage + 1));
            }
            verify(service, times(1)).readAndPrintCustomerFileTo(any(SysoutSink.class));
            verifyNoMoreInteractions(service);
        }

        @Test
        @DisplayName("the published capturing surface still runs the program without a JobLauncher")
        void theCapturingSurfaceStillDelegates() {
            List<String> records = fixtureRows();
            Execution expected = new Execution(successfulLines(records),
                    AbendException.RETURN_CODE_OK, records.size());
            CustomerService service = mock(CustomerService.class);
            when(service.readAndPrintCustomerFile(any(Sysout.class))).thenReturn(expected);
            Sysout sysout = new Sysout();

            Execution actual = subject(service, new CapturedSysout())
                    .readAndPrintCustomerFile(sysout);

            assertThat(actual).isSameAs(expected);
            verify(service, times(1)).readAndPrintCustomerFile(sysout);
            verifyNoMoreInteractions(service);
        }

        @Test
        @DisplayName("the tasklet streams to SYSOUT and retains no line sequence")
        void theTaskletRetainsNothing() {
            String source = moduleSource(JOB_SOURCE);

            assertThat(source)
                    .contains("customerService.readAndPrintCustomerFileTo(sysoutSink)")
                    .doesNotContain("new Sysout()")
                    .doesNotContain(".lines()")
                    .doesNotContain("private void spool(");

            assertThat(Arrays.stream(CustomerFileReaderJob.class.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .map(field -> field.getGenericType().getTypeName()))
                    .noneMatch(typeName -> typeName.contains("List")
                            || typeName.contains("Collection")
                            || typeName.contains("Sysout>"));
        }

        @Test
        @DisplayName("a failing run's lines are already at the destination when the abend arrives")
        void aFailingRunsLinesAreAlreadySpooled() {
            AbendException abend = AbendException.standard(CustomerService.PROGRAM_ID,
                    AbendException.RETURN_CODE_IO_ERROR,
                    CustomerService.ERROR_READING_CUSTOMER_FILE);
            CustomerService service = serviceThrowing(abend);
            CapturedSysout sink = new CapturedSysout();
            Tasklet tasklet = subject(service, sink).customerFileDisplayTasklet();
            TaskletCall call = taskletCall();

            AbendException thrown = catchThrowableOfType(AbendException.class,
                    () -> tasklet.execute(call.contribution(), call.chunkContext()));

            assertThat(thrown).isSameAs(abend);
            assertThat(sink.lines()).containsExactlyElementsOf(abendLines());
            assertThat(call.contribution().getReadCount())
                    .as("an abending run publishes no read count, as it did not reach the return")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("ExitStatusContract")
    class ExitStatusContract {
        @Test
        @DisplayName("a successful real job completes and contributes process code zero")
        void successCompletesWithZero() throws Exception {
            CustomerService service = serviceReturning(List.of());

            ExecutedJob executed = executeJob(service);

            assertThat(executed.stepExecution().getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(executed.stepExecution().getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
            assertThat(executed.jobExecution().getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(executed.jobExecution().getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
            assertThat(executed.batchConfig().abendExitCodeMapper().getExitCode(null)).isZero();
            assertThat(executed.batchConfig().precedingExitCodeZeroDecider()
                    .decide(executed.jobExecution(), executed.stepExecution()))
                    .isEqualTo(BatchConfig.PROCEED);
            verify(service, times(1)).readAndPrintCustomerFileTo(any(SysoutSink.class));
        }

        @Test
        @DisplayName("an abend escapes the tasklet with its standard parameters and keeps prior output")
        void abendIsNotSwallowedByTheTasklet() {
            AbendException abend = AbendException.standard(CustomerService.PROGRAM_ID,
                    AbendException.RETURN_CODE_IO_ERROR,
                    CustomerService.ERROR_READING_CUSTOMER_FILE);
            CustomerService service = serviceThrowing(abend);
            CapturedSysout sink = new CapturedSysout();
            Tasklet tasklet = subject(service, sink).customerFileDisplayTasklet();
            TaskletCall call = taskletCall();

            AbendException thrown = catchThrowableOfType(AbendException.class,
                    () -> tasklet.execute(call.contribution(), call.chunkContext()));

            assertThat(thrown).isSameAs(abend);
            assertThat(thrown.getProgram()).isEqualTo(CustomerService.PROGRAM_ID);
            assertThat(thrown.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
            assertThat(thrown.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(thrown.getTiming()).hasValue(AbendException.STANDARD_TIMING);
            assertThat(sink.lines())
                    .containsExactlyElementsOf(abendLines())
                    .doesNotContain(CustomerService.END_OF_EXECUTION);
            verify(service, times(1)).readAndPrintCustomerFileTo(any(SysoutSink.class));
        }

        @Test
        @DisplayName("the failed step, failed job, gate and process mapper all carry return code 12")
        void abendReturnCodeCrossesEveryBatchBoundary() throws Exception {
            AbendException abend = AbendException.standard(CustomerService.PROGRAM_ID,
                    AbendException.RETURN_CODE_IO_ERROR,
                    CustomerService.ERROR_READING_CUSTOMER_FILE);

            ExecutedJob executed = executeJob(serviceThrowing(abend));

            assertThat(executed.stepExecution().getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(executed.jobExecution().getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(executed.stepExecution().getExitStatus().getExitCode()).isEqualTo("12");
            assertThat(executed.jobExecution().getExitStatus().getExitCode()).isEqualTo("12");
            assertThat(executed.stepExecution().getFailureExceptions()).contains(abend);
            assertThat(executed.jobExecution().getAllFailureExceptions()).contains(abend);
            assertThat(executed.sysout().lines()).containsExactlyElementsOf(abendLines());

            assertThat(executed.batchConfig().precedingExitCodeZeroDecider()
                    .decide(executed.jobExecution(), executed.stepExecution()))
                    .isEqualTo(BatchConfig.SKIP);
            assertThat(executed.batchConfig().abendExitCodeMapper().getExitCode(abend))
                    .isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
            assertThat(executed.batchConfig().abendExitCodeMapper()
                    .getExitCode(new IllegalStateException("framework wrapper", abend)))
                    .isEqualTo(AbendException.RETURN_CODE_IO_ERROR);

            assertThat(List.of(AbendException.RETURN_CODE_OK,
                    AbendException.RETURN_CODE_WARNING,
                    AbendException.RETURN_CODE_ASSUMED_FAILURE,
                    AbendException.RETURN_CODE_IO_ERROR))
                    .containsExactly(0, 4, 8, 12);
            assertThat(AbendException.RETURN_CODE_END_OF_FILE).isEqualTo(16);
            assertThat(abend.getReturnCode()).isEqualTo(12);
        }
    }

    @Nested
    @DisplayName("AbsenceAssertions")
    class AbsenceAssertions {
        @Test
        @DisplayName("production dataset identity is absent and all locations come from configuration")
        void noProductionDatasetLiteralIsCompiledIntoTheJob() {
            String productionPrefix = "AWS.M2." + "CARDDEMO.";

            assertThat(moduleSource(JOB_SOURCE)).doesNotContain(productionPrefix);
            assertThat(compiledForm(CustomerFileReaderJob.class)).doesNotContain(productionPrefix);
        }

        @Test
        @DisplayName("all state is final or constant and every dependency arrives through one constructor")
        void noMutableStaticOrSetterInjectedStateExists() {
            List<Field> fields = Arrays.stream(
                            CustomerFileReaderJob.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .toList();

            assertThat(fields).allSatisfy(field -> {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be a constant", field.getName())
                            .isTrue();
                } else {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be constructor-set", field.getName())
                            .isTrue();
                }
            });
            assertThat(CustomerFileReaderJob.class.getDeclaredConstructors()).hasSize(1);
            assertThat(Arrays.stream(CustomerFileReaderJob.class.getDeclaredMethods())
                    .map(Method::getName)
                    .filter(name -> name.startsWith("set")))
                    .isEmpty();
        }

        @Test
        @DisplayName("imports are explicit and legacy Batch support is absent")
        void noWildcardOrForbiddenBatchTestSupportAppears() {
            String testSource = moduleSource(TEST_SOURCE);
            String jobSource = moduleSource(JOB_SOURCE);
            String pomSource = moduleSource(POM);
            List<String> unavailableSupport = List.of(
                    "spring-batch" + "-test",
                    "JobLauncher" + "TestUtils",
                    "JobRepository" + "TestUtils",
                    "@Spring" + "BatchTest",
                    "MetaDataInstance" + "Factory");
            List<String> removedConfiguration = List.of(
                    "JobBuilder" + "Factory",
                    "StepBuilder" + "Factory",
                    "@Enable" + "BatchProcessing");

            assertThat(testSource)
                    .doesNotContainPattern("(?m)^\\s*import\\s+(?:static\\s+)?[^;]*\\.\\*;\\s*$")
                    .doesNotContain(unavailableSupport.toArray(String[]::new))
                    .doesNotContain(removedConfiguration.toArray(String[]::new));
            assertThat(jobSource)
                    .doesNotContainPattern("(?m)^\\s*import\\s+(?:static\\s+)?[^;]*\\.\\*;\\s*$")
                    .doesNotContain(unavailableSupport.toArray(String[]::new))
                    .doesNotContain(removedConfiguration.toArray(String[]::new));
            assertThat(pomSource).doesNotContain(unavailableSupport.get(0));
        }

        @Test
        @DisplayName("the customer package declares no approximate or scaled numeric Java type")
        void copybookHasNoNumericTypeToTranslate() {
            String executableSources = executableJava(customerProductionSources());
            String wideBinaryKeyword = "dou" + "ble";
            String narrowBinaryKeyword = "flo" + "at";

            assertThat(executableSources)
                    .doesNotContainPattern("\\b" + wideBinaryKeyword + "\\b")
                    .doesNotContainPattern("\\b" + narrowBinaryKeyword + "\\b")
                    .doesNotContain("BigDecimal");
        }

        @Test
        @DisplayName("source helpers reject every read-only reference tree")
        void sourceInspectionCannotEscapeTheJavaModule() {
            List<Path> referenceFiles = List.of(
                    Path.of("app", "jcl", "READCUST.jcl"),
                    Path.of("app", "cbl", "CBCUS01C.cbl"),
                    Path.of("app", "csd", "CARDDEMO.CSD"),
                    Path.of("app", "cpy", "CVCUS01Y.cpy"),
                    Path.of("app", "data", "ASCII", "custdata.txt"));

            referenceFiles.forEach(reference ->
                    assertThatIllegalArgumentException()
                            .isThrownBy(() -> modulePath(reference))
                            .withMessageContaining("confined to app/java"));
            assertThat(FIXTURE_RESOURCE).startsWith("/fixtures/");
        }
    }

    @Nested
    @DisplayName("StartupGuards")
    class StartupGuards {
        @Test
        @DisplayName("a contract pointing STEP05 at another program is refused")
        void wrongProgramIsRefused() {
            BatchConfig wrong = mockedScaffolding(jobContracts(
                    new StepContract(CustomerService.STEP_NAME, "CBACT01C", false)));

            assertThatIllegalStateException()
                    .isThrownBy(() -> subject(wrong, mock(CustomerService.class),
                            new PresentBean<>(new CapturedSysout())))
                    .withMessageContaining(CustomerService.PROGRAM_ID)
                    .withMessageContaining("READCUST.jcl");
        }

        @Test
        @DisplayName("a gate on the only READCUST step is refused")
        void gatedStepIsRefused() {
            BatchConfig gated = mockedScaffolding(jobContracts(
                    new StepContract(CustomerService.STEP_NAME,
                            CustomerService.PROGRAM_ID, true)));

            assertThatIllegalStateException()
                    .isThrownBy(() -> subject(gated, mock(CustomerService.class),
                            new PresentBean<>(new CapturedSysout())))
                    .withMessageContaining("COND")
                    .withMessageContaining("bypass");
        }

        @Test
        @DisplayName("an additional step is refused")
        void additionalStepIsRefused() {
            BatchConfig extra = mockedScaffolding(jobContracts(List.of(
                    new StepContract(CustomerService.STEP_NAME,
                            CustomerService.PROGRAM_ID, false),
                    new StepContract("STEP06", CustomerService.PROGRAM_ID, false))));

            assertThatIllegalStateException()
                    .isThrownBy(() -> subject(extra, mock(CustomerService.class),
                            new PresentBean<>(new CapturedSysout())))
                    .withMessageContaining("configured: [STEP05/CBCUS01C, STEP06/CBCUS01C]")
                    .withMessageContaining("required:   [STEP05/CBCUS01C]");
        }

        @Test
        @DisplayName("an absent job contract is refused")
        void absentContractIsRefused() {
            BatchConfig absent = mockedScaffolding(new JobContracts());

            assertThatIllegalStateException()
                    .isThrownBy(() -> subject(absent, mock(CustomerService.class),
                            new PresentBean<>(new CapturedSysout())));
        }

        @Test
        @DisplayName("the required sequence is exactly the shipped one-step contract")
        void requiredSequenceIsExact() {
            assertThat(CustomerFileReaderJob.REQUIRED_STEPS)
                    .containsExactly(new StepContract(CustomerService.STEP_NAME,
                            CustomerService.PROGRAM_ID, false));
        }
    }
}
