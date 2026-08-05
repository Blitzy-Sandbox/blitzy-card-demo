/*
 * ******************************************************************
 * Program     : CombineTransactionsJobTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves the two steps of the transaction-combine job behave as
 *               the JCL member specifies: the concatenated SORTIN is consumed
 *               in ascending character order by TRAN-ID, SORTOUT is ONE
 *               350-byte generation whose concrete key is published and read
 *               back verbatim, STEP10 is NOT gated on STEP05R, a colliding
 *               TRAN-ID fails the load rather than being absorbed, and both
 *               26-character timestamps survive byte-identical end to end.
 * Source      : app/jcl/COMBTRAN.jcl:L22      (STEP05R EXEC PGM=SORT, no COND)
 *               app/jcl/COMBTRAN.jcl:L23-L26  (concatenated SORTIN, both (0))
 *               app/jcl/COMBTRAN.jcl:L28      (SYMNAMES TRAN-ID,1,16,CH)
 *               app/jcl/COMBTRAN.jcl:L30      (SORT FIELDS=(TRAN-ID,A))
 *               app/jcl/COMBTRAN.jcl:L33-L37  (SORTOUT, DCB=(*.SORTIN), (+1))
 *               app/jcl/COMBTRAN.jcl:L41      (STEP10 EXEC PGM=IDCAMS, NO COND)
 *               app/jcl/COMBTRAN.jcl:L43-L46  (TRANSACT (+1), TRANVSAM KSDS)
 *               app/jcl/COMBTRAN.jcl:L48      (REPRO INFILE/OUTFILE)
 *               app/ctl/REPROCT.ctl:L15       (the parameterised REPRO card)
 *               There is NO COBOL program for this job @ 7756d89
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
package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.BatchUpdateException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.cardemo.batch.jobs.CombineTransactionsJob;
import com.cardemo.batch.processors.TransactionCombineProcessor;
import com.cardemo.batch.readers.CombinedTransactionReader;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.service.shared.FileStatusMapper;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Unit coverage for {@link CombineTransactionsJob}, the one job in the corpus with no COBOL program.
 *
 * <p>Every assertion here cites {@code app/jcl/COMBTRAN.jcl} or {@code app/ctl/REPROCT.ctl}, because those two
 * members are the only source of truth for this job's behaviour. The load-bearing cases are the four that a
 * plausible-looking implementation gets wrong: character rather than numeric sort order, the {@code (+1)} key
 * handoff between the two steps, the deliberate absence of {@code COND} gating, and a duplicate identifier
 * failing the load instead of being absorbed by an upsert.
 */
class CombineTransactionsJobTest {

    private static final int RECORD_LENGTH = 350;
    private static final String BUCKET = "carddemo-batch-output";
    private static final String PREFIX = "gdg/transact-combined";

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final S3Operations objectStorage = mock(S3Operations.class);
    private final FileStatusMapper fileStatusMapper = new FileStatusMapper();
    private final MetricsConfig metricsConfig = new MetricsConfig(new SimpleMeterRegistry());
    private final TransactionWriter transactionWriter = new TransactionWriter(
            mock(com.cardemo.repository.TransactionRepository.class), objectStorage, fileStatusMapper,
            metricsConfig, BUCKET, "transact");

    private CombineTransactionsJob newJob() {
        return new CombineTransactionsJob(jobRepository, transactionManager, transactionWriter,
                jdbcTemplate, objectStorage, fileStatusMapper, metricsConfig,
                "COMBTRAN", 100, 1_000_000, BUCKET, PREFIX);
    }

    private static Transaction transaction(final String id, final String amount) {
        return new Transaction(id, "01", Integer.valueOf(1), "POS       ",
                "A".repeat(100), new BigDecimal(amount), Long.valueOf(7L),
                "M".repeat(50), "C".repeat(50), "12345     ",
                "4111111111111111", "2024-01-02-03.04.05.120000", "2024-01-02-03.04.06.780000");
    }

    private static StepExecution stepExecution() {
        final JobInstance instance = new JobInstance(11L, "COMBTRAN");
        final JobExecution jobExecution = new JobExecution(instance, 22L, new JobParameters());
        final StepExecution stepExecution = jobExecution.createStepExecution("combineTransactionsSortStep");
        stepExecution.setId(33L);
        return stepExecution;
    }

    // ---------------------------------------------------------------- structure

    @Test
    @DisplayName("Declares exactly four beans - two Steps, one Flow, one Job - and no infrastructure bean")
    void declaresOnlyJobStepFlowBeans() {
        final List<Method> beans = new ArrayList<>();
        for (final Method method : CombineTransactionsJob.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(org.springframework.context.annotation.Bean.class)) {
                beans.add(method);
            }
        }
        assertThat(beans).hasSize(4);
        for (final Method bean : beans) {
            assertThat(bean.getReturnType()).isIn(Step.class, org.springframework.batch.core.job.flow.Flow.class,
                    Job.class);
            assertThat(bean.getAnnotation(org.springframework.context.annotation.Bean.class).value()[0])
                    .startsWith("combineTransactions");
        }
    }

    @Test
    @DisplayName("The @Configuration bean name differs from the job bean name, so context refresh cannot "
            + "abort with a BeanDefinitionOverrideException")
    void configurationBeanNameDiffersFromTheJobBeanName() {
        final org.springframework.context.annotation.Configuration annotation =
                CombineTransactionsJob.class.getAnnotation(
                        org.springframework.context.annotation.Configuration.class);
        assertThat(annotation).isNotNull();

        // The component scan would otherwise register the configuration class under the decapitalised class
        // name - which is exactly the name the Job factory method uses - and the two definitions would clash.
        final String decapitalisedClassName = "combineTransactionsJob";
        assertThat(annotation.value()).isNotBlank().isNotEqualTo(decapitalisedClassName);

        final Method jobFactory = java.util.Arrays.stream(
                        CombineTransactionsJob.class.getDeclaredMethods())
                .filter(m -> m.getReturnType() == Job.class)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Job factory method"));
        assertThat(jobFactory.getAnnotation(org.springframework.context.annotation.Bean.class).value())
                .containsExactly(decapitalisedClassName);
    }

    @Test
    @DisplayName("No @EnableBatchProcessing, and every static field is final")
    void noEnableBatchProcessingAndNoStaticMutableState() {
        assertThat(CombineTransactionsJob.class.getAnnotations())
                .noneMatch(a -> a.annotationType().getSimpleName().equals("EnableBatchProcessing"));
        for (final Field field : CombineTransactionsJob.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("static field %s must be final", field.getName()).isTrue();
            } else {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("instance field %s must be final", field.getName()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("Rejects a null collaborator and a non-positive size")
    void rejectsInvalidConstruction() {
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                new CombineTransactionsJob(null, transactionManager, transactionWriter, jdbcTemplate,
                        objectStorage, fileStatusMapper, metricsConfig, "COMBTRAN", 100, 10, BUCKET, PREFIX));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new CombineTransactionsJob(jobRepository, transactionManager, transactionWriter, jdbcTemplate,
                        objectStorage, fileStatusMapper, metricsConfig, "COMBTRAN", 0, 10, BUCKET, PREFIX));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new CombineTransactionsJob(jobRepository, transactionManager, transactionWriter, jdbcTemplate,
                        objectStorage, fileStatusMapper, metricsConfig, "COMBTRAN", 100, 10, "  ", PREFIX));
    }

    // ---------------------------------------------------------------- SORT semantics

    @Test
    @DisplayName("SORT FIELDS=(TRAN-ID,A) with CH format: character order, not numeric")
    void sortKeyIsCharacterOrderedNotNumeric() {
        final Comparator<Transaction> comparator = TransactionCombineProcessor.TRAN_ID_ASCENDING;
        // A leading space sorts BELOW a digit under CH; a numeric read would make these equal/inverted.
        final Transaction leadingSpace = transaction(" 000000000000002", "1.00");
        final Transaction plainDigits = transaction("0000000000000001", "1.00");
        assertThat(comparator.compare(leadingSpace, plainDigits)).isNegative();
        // A non-digit sorts by code point, above the digits.
        final Transaction nonDigit = transaction("Z000000000000000", "1.00");
        assertThat(comparator.compare(nonDigit, plainDigits)).isPositive();
    }

    @Test
    @DisplayName("STEP05R consumes the concatenated stream in reader order, writes ONE 350-byte object "
            + "and publishes its concrete key")
    void sortStepEmitsOneGenerationAndPublishesKey() throws Exception {
        final CombinedTransactionReader reader = mock(CombinedTransactionReader.class);
        when(reader.read()).thenReturn(
                transaction("0000000000000001", "1.00"),
                transaction("0000000000000002", "2.00"),
                null);
        when(reader.getBackupRecordsRead()).thenReturn(1L);
        when(reader.getSystranRecordsRead()).thenReturn(1L);
        when(reader.getResolvedBackupObjectKey()).thenReturn("gdg/transact-bkup/x.dat");
        when(reader.getResolvedSystranObjectKey()).thenReturn("gdg/systran/y.dat");

        final StepExecution stepExecution = stepExecution();
        final Step step = newJob().combineTransactionsSortStep(reader, new TransactionCombineProcessor());
        assertThat(step.getName()).isEqualTo("combineTransactionsSortStep");

        invokeTasklet(step, stepExecution);

        final ArgumentCapture capture = uploadCapture();
        assertThat(capture.payload().length).isEqualTo(2 * RECORD_LENGTH);
        assertThat(capture.payload().length % RECORD_LENGTH).isZero();

        final ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();
        final String publishedKey = jobContext.getString("carddemo.transact.combined.object.key");
        assertThat(publishedKey).isEqualTo(capture.key()).startsWith(PREFIX + "/");
        assertThat(jobContext.getInt("carddemo.transact.combined.record.count")).isEqualTo(2);
        verify(reader).close();
    }

    @Test
    @DisplayName("A record whose image is not 350 bytes fails rather than being written")
    void refusesWrongGeometry() {
        assertThat(TransactionCombineProcessor.COMBINED_RECORD_LENGTH).isEqualTo(RECORD_LENGTH);
        assertThat(transactionWriter.composeFixedWidthImage(transaction("0000000000000001", "1.00")))
                .hasSize(RECORD_LENGTH);
    }

    // ---------------------------------------------------------------- (+1) handoff

    @Test
    @DisplayName("STEP10 reads the EXACT key STEP05R published, never a re-resolved latest")
    void loadStepReadsThePublishedKeyNotTheLatest() throws Exception {
        final StepExecution loadExecution = stepExecution();
        final String publishedKey = PREFIX + "/0000000000000000011/transact-combined-0000000000000000033.dat";
        loadExecution.getJobExecution().getExecutionContext()
                .putString("carddemo.transact.combined.object.key", publishedKey);

        final String image =
                transactionWriter.composeFixedWidthImage(transaction("0000000000000001", "1.00"));
        stubDownload(publishedKey, image);

        final Step step = newJob().combineTransactionsLoadStep(new TransactionCombineProcessor());
        invokeTasklet(step, loadExecution);

        // The decoy newer object must never be requested.
        verify(objectStorage).download(BUCKET, publishedKey);
        verify(objectStorage, never()).download(eq(BUCKET),
                eq(PREFIX + "/0000000000000000099/transact-combined-0000000000000000099.dat"));
        verify(objectStorage, never()).listObjects(anyString(), anyString());
    }

    @Test
    @DisplayName("An absent published key fails; the load never falls back to resolving the latest")
    void absentPublishedKeyFails() {
        final Step step = newJob().combineTransactionsLoadStep(new TransactionCombineProcessor());
        assertThatExceptionOfType(FatalProcessingException.class)
                .isThrownBy(() -> invokeTasklet(step, stepExecution()));
        verify(objectStorage, never()).listObjects(anyString(), anyString());
    }

    @Test
    @DisplayName("Timestamps survive byte-identical through encode, upload, download and decode")
    void timestampsRoundTripByteIdentical() throws Exception {
        final Transaction original = transaction("0000000000000009", "123.45");
        final String image = transactionWriter.composeFixedWidthImage(original);
        assertThat(image.substring(278, 304)).isEqualTo("2024-01-02-03.04.05.120000");
        assertThat(image.substring(304, 330)).isEqualTo("2024-01-02-03.04.06.780000");

        final StepExecution loadExecution = stepExecution();
        final String key = PREFIX + "/k.dat";
        loadExecution.getJobExecution().getExecutionContext()
                .putString("carddemo.transact.combined.object.key", key);
        stubDownload(key, image);

        final List<Object[]> captured = captureBatchArgs();
        invokeTasklet(newJob().combineTransactionsLoadStep(new TransactionCombineProcessor()), loadExecution);

        assertThat(captured).hasSize(1);
        final Object[] row = captured.get(0);
        assertThat(row).hasSize(14);
        assertThat(row[11]).isEqualTo("2024-01-02-03.04.05.120000");
        assertThat(row[12]).isEqualTo("2024-01-02-03.04.06.780000");
        assertThat(row[0]).isEqualTo("0000000000000009");
        assertThat((BigDecimal) row[5]).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("123.45"));
        assertThat(row[13]).isEqualTo(Long.valueOf(0L));
    }

    @Test
    @DisplayName("A negative amount decodes with its sign preserved - no absolute-value normalisation")
    void negativeAmountKeepsItsSign() throws Exception {
        final Transaction negative = transaction("0000000000000010", "-123.45");
        final String image = transactionWriter.composeFixedWidthImage(negative);
        assertThat(image.charAt(142)).isIn('}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R');

        final StepExecution loadExecution = stepExecution();
        final String key = PREFIX + "/neg.dat";
        loadExecution.getJobExecution().getExecutionContext()
                .putString("carddemo.transact.combined.object.key", key);
        stubDownload(key, image);

        final List<Object[]> captured = captureBatchArgs();
        invokeTasklet(newJob().combineTransactionsLoadStep(new TransactionCombineProcessor()), loadExecution);
        assertThat((BigDecimal) captured.get(0)[5]).usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal("-123.45"));
    }

    @Test
    @DisplayName("An object whose size is not a multiple of 350 is refused, not parsed")
    void refusesMisalignedObject() throws Exception {
        final StepExecution loadExecution = stepExecution();
        final String key = PREFIX + "/bad.dat";
        loadExecution.getJobExecution().getExecutionContext()
                .putString("carddemo.transact.combined.object.key", key);
        stubDownload(key, "x".repeat(RECORD_LENGTH + 7));

        assertThatExceptionOfType(FatalProcessingException.class).isThrownBy(() ->
                invokeTasklet(newJob().combineTransactionsLoadStep(new TransactionCombineProcessor()),
                        loadExecution));
    }

    // ---------------------------------------------------------------- duplicates

    @Test
    @DisplayName("A colliding TRAN-ID surfaces DuplicateRecordException - never an upsert or a swallow")
    void duplicateIdentifierFailsTheLoad() throws Exception {
        final StepExecution loadExecution = stepExecution();
        final String key = PREFIX + "/dup.dat";
        loadExecution.getJobExecution().getExecutionContext()
                .putString("carddemo.transact.combined.object.key", key);

        final String duplicated = transactionWriter.composeFixedWidthImage(
                transaction("0000000000000001", "1.00"));
        stubDownload(key, duplicated + duplicated);

        final BatchUpdateException driverFailure = new BatchUpdateException("duplicate key", new int[] {1});
        when(jdbcTemplate.batchUpdate(anyString(), anyBatchArgs()))
                .thenThrow(new DuplicateKeyException("pk_transaction", driverFailure));

        assertThatExceptionOfType(DuplicateRecordException.class).isThrownBy(() ->
                        invokeTasklet(newJob().combineTransactionsLoadStep(new TransactionCombineProcessor()),
                                loadExecution))
                .satisfies(failure -> assertThat(failure.getCause()).isNotNull());
    }

    @Test
    @DisplayName("The load SQL is a static parameterised constant: 14 placeholders, no ON CONFLICT")
    void loadSqlIsParameterisedAndHasNoConflictClause() throws Exception {
        final Field field = CombineTransactionsJob.class.getDeclaredField("LOAD_INSERT_SQL");
        field.setAccessible(true);
        final String sql = (String) field.get(null);
        assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        assertThat(sql.chars().filter(c -> c == '?').count()).isEqualTo(14L);
        assertThat(sql.toUpperCase(java.util.Locale.ROOT))
                .doesNotContain("ON CONFLICT").doesNotContain("MERGE").contains("INSERT INTO");
    }

    // ---------------------------------------------------------------- flow / decider / MDC

    @Test
    @DisplayName("STEP10 is NOT gated on STEP05R: the flow carries a wildcard transition to the load")
    void loadIsNotGatedOnTheSort() throws Exception {
        final Step sort = mock(Step.class);
        final Step load = mock(Step.class);
        when(sort.getName()).thenReturn("combineTransactionsSortStep");
        when(load.getName()).thenReturn("combineTransactionsLoadStep");

        final org.springframework.batch.core.job.flow.Flow flow =
                newJob().combineTransactionsFlow(sort, load);
        assertThat(flow.getName()).isEqualTo("combineTransactionsFlow");

        // FlowBuilder names states step0/step1/decision0 in declaration order: step0 is the sort
        // (STEP05R), step1 is the load (STEP10) and decision0 is the return-code decider.
        final List<String> transitions = describeTransitions(flow);
        final List<String> fromSort = transitions.stream().filter(t -> t.startsWith("step0|")).toList();

        // THE no-gating assertion: the wildcard arm out of STEP05R targets the LOAD STEP itself, never a
        // decision state. Only FAILED and ABEND divert - the abend suppression the platform applies. That
        // is exactly the absence of COND at app/jcl/COMBTRAN.jcl:L41.
        assertThat(fromSort).containsExactlyInAnyOrder(
                "step0|*->step1", "step0|FAILED->decision0", "step0|ABEND->decision0");
        assertThat(fromSort).noneMatch(t -> t.equals("step0|COMPLETED->decision0"));

        // The decider is reached only AFTER the load, and all four return codes plus the catch-all are wired:
        // 0 and 4 end the flow carrying their own code, 8 and 12 fail it, and an unrecognised code fails too.
        assertThat(transitions).contains(
                "step1|*->decision0",
                "decision0|COMPLETED->end0",
                "decision0|COMPLETED WITH REJECTS->end1",
                "decision0|FAILED->FAILED",
                "decision0|ABEND->FAILED",
                "decision0|*->FAILED");
    }

    /** Renders each flow transition as {@code fromState|pattern->toState} for exact assertions. */
    private static List<String> describeTransitions(
            final org.springframework.batch.core.job.flow.Flow flow) throws Exception {

        final Field field = flow.getClass().getDeclaredField("stateTransitions");
        field.setAccessible(true);
        final List<?> raw = (List<?>) field.get(flow);
        final List<String> described = new ArrayList<>();
        for (final Object transition : raw) {
            final Object state = transition.getClass().getMethod("getState").invoke(transition);
            final String from = (String) state.getClass().getMethod("getName").invoke(state);
            final String pattern =
                    (String) transition.getClass().getMethod("getPattern").invoke(transition);
            final String next = (String) transition.getClass().getMethod("getNext").invoke(transition);
            described.add(stripFlowPrefix(from) + "|" + pattern + "->" + stripFlowPrefix(next));
        }
        return described;
    }

    private static String stripFlowPrefix(final String stateName) {
        if (stateName == null) {
            return "";
        }
        final int separator = stateName.lastIndexOf('.');
        return separator < 0 ? stateName : stateName.substring(separator + 1);
    }

    @Test
    @DisplayName("The decider covers return codes 0, 4, 8 and 12")
    void deciderCoversAllFourReturnCodes() throws Exception {
        final JobExecutionDecider decider = newDecider();
        assertThat(decide(decider, ExitStatus.COMPLETED).getName()).isEqualTo("COMPLETED");
        assertThat(decide(decider, new ExitStatus("COMPLETED WITH REJECTS")).getName())
                .isEqualTo("COMPLETED WITH REJECTS");
        assertThat(decide(decider, ExitStatus.FAILED).getName()).isEqualTo("FAILED");
        assertThat(decide(decider, new ExitStatus("SOMETHING UNRECOGNISED")).getName()).isEqualTo("ABEND");
    }

    @Test
    @DisplayName("The listener publishes jobInstanceId and correlationId, then clears both")
    void listenerEstablishesAndClearsDiagnosticContext() throws Exception {
        final JobExecutionListener listener = newListener();
        final JobExecution execution =
                new JobExecution(new JobInstance(77L, "COMBTRAN"), 88L, new JobParameters());
        execution.setExitStatus(ExitStatus.COMPLETED);
        execution.setStatus(BatchStatus.COMPLETED);

        listener.beforeJob(execution);
        assertThat(org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID)).isEqualTo("77");
        assertThat(CorrelationIdFilter.currentCorrelationId()).isEqualTo("combtran-88");

        listener.afterJob(execution);
        assertThat(org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID)).isNull();
        assertThat(CorrelationIdFilter.currentCorrelationId()).isNull();
    }

    @Test
    @DisplayName("An outer scope's correlation identifier is never stolen")
    void doesNotClearAForeignCorrelationId() throws Exception {
        CorrelationIdFilter.propagate("outer-owned-id");
        try {
            final JobExecutionListener listener = newListener();
            final JobExecution execution =
                    new JobExecution(new JobInstance(1L, "COMBTRAN"), 2L, new JobParameters());
            execution.setExitStatus(ExitStatus.COMPLETED);
            listener.beforeJob(execution);
            listener.afterJob(execution);
            assertThat(CorrelationIdFilter.currentCorrelationId()).isEqualTo("outer-owned-id");
        } finally {
            CorrelationIdFilter.propagate(null);
        }
    }

    // ---------------------------------------------------------------- boundary conditions

    @Test
    @DisplayName("Both SORTIN sources empty: one empty generation is published and the load says so")
    void emptyConcatenatedInputIsExplicitNotSilent() throws Exception {
        final CombinedTransactionReader reader = mock(CombinedTransactionReader.class);
        when(reader.read()).thenReturn(null);
        when(reader.getResolvedBackupObjectKey()).thenReturn("gdg/transact-bkup/x.dat");
        when(reader.getResolvedSystranObjectKey()).thenReturn(null);

        final StepExecution sortExecution = stepExecution();
        invokeTasklet(newJob().combineTransactionsSortStep(reader, new TransactionCombineProcessor()),
                sortExecution);

        final ArgumentCapture capture = uploadCapture();
        assertThat(capture.payload()).isEmpty();
        assertThat(sortExecution.getJobExecution().getExecutionContext()
                .getInt("carddemo.transact.combined.record.count")).isZero();

        // The load must report the empty generation rather than pass silently, and must insert nothing.
        final StepExecution loadExecution = stepExecution();
        loadExecution.getJobExecution().getExecutionContext()
                .putString("carddemo.transact.combined.object.key", capture.key());
        loadExecution.getJobExecution().getExecutionContext()
                .putInt("carddemo.transact.combined.record.count", 0);
        stubDownload(capture.key(), "");
        invokeTasklet(newJob().combineTransactionsLoadStep(new TransactionCombineProcessor()), loadExecution);
        verify(jdbcTemplate, never()).batchUpdate(anyString(), anyBatchArgs());
    }

    @Test
    @DisplayName("Exceeding the per-run record bound fails - it never truncates like CBSTM03A did")
    void recordBoundFailsRatherThanTruncating() {
        final CombinedTransactionReader reader = mock(CombinedTransactionReader.class);
        when(reader.read()).thenReturn(
                transaction("0000000000000001", "1.00"),
                transaction("0000000000000002", "2.00"),
                transaction("0000000000000003", "3.00"),
                null);

        final CombineTransactionsJob bounded = new CombineTransactionsJob(jobRepository, transactionManager,
                transactionWriter, jdbcTemplate, objectStorage, fileStatusMapper, metricsConfig,
                "COMBTRAN", 100, 2, BUCKET, PREFIX);

        assertThatExceptionOfType(FatalProcessingException.class).isThrownBy(() ->
                invokeTasklet(bounded.combineTransactionsSortStep(reader, new TransactionCombineProcessor()),
                        stepExecution()));
        verify(objectStorage, never()).upload(anyString(), anyString(), any(), any(ObjectMetadata.class));
    }

    @Test
    @DisplayName("An upload failure becomes a typed FileAccessException carrying the rendered 9x status")
    void uploadFailureIsTypedAndCarriesTheRenderedStatus() {
        final CombinedTransactionReader reader = mock(CombinedTransactionReader.class);
        when(reader.read()).thenReturn(transaction("0000000000000001", "1.00"), (Transaction) null);
        when(objectStorage.upload(anyString(), anyString(), any(), any(ObjectMetadata.class)))
                .thenThrow(new IllegalStateException("object storage unreachable"));

        // The '9x' arm of the status map, applied on every I/O path and never swallowed. The root cause is
        // preserved, and the DD name of app/jcl/COMBTRAN.jcl:L33 identifies which file failed.
        assertThatExceptionOfType(FileAccessException.class)
                .isThrownBy(() -> invokeTasklet(
                        newJob().combineTransactionsSortStep(reader, new TransactionCombineProcessor()),
                        stepExecution()))
                .satisfies(failure -> {
                    assertThat(failure.getCause()).isNotNull();
                    assertThat(failure.getMessage()).contains("SORTOUT").contains("WRITE");
                })
                .isInstanceOf(CardDemoException.class);
        // The abend contract this job uses when a condition is NOT an I/O status: 999 and RC 12, never 9999.
        assertThat(FatalProcessingException.BATCH_ABEND_CODE).isEqualTo(999);
        assertThat(FatalProcessingException.BATCH_RETURN_CODE).isEqualTo(12);
    }

    @Test
    @DisplayName("A download failure abends, and a record-count mismatch is refused")
    void downloadFailureAndCountMismatchAreRefused() throws Exception {
        final StepExecution failing = stepExecution();
        failing.getJobExecution().getExecutionContext()
                .putString("carddemo.transact.combined.object.key", PREFIX + "/gone.dat");
        when(objectStorage.download(BUCKET, PREFIX + "/gone.dat"))
                .thenThrow(new IllegalStateException("no such key"));
        assertThatExceptionOfType(FileAccessException.class).isThrownBy(() ->
                        invokeTasklet(newJob().combineTransactionsLoadStep(new TransactionCombineProcessor()),
                                failing))
                .satisfies(failure -> assertThat(failure.getMessage())
                        .contains("TRANSACT").contains("READ"));

        final StepExecution mismatched = stepExecution();
        final String key = PREFIX + "/mismatch.dat";
        mismatched.getJobExecution().getExecutionContext()
                .putString("carddemo.transact.combined.object.key", key);
        mismatched.getJobExecution().getExecutionContext()
                .putInt("carddemo.transact.combined.record.count", 5);
        stubDownload(key, transactionWriter.composeFixedWidthImage(transaction("0000000000000001", "1.00")));
        assertThatExceptionOfType(FatalProcessingException.class).isThrownBy(() ->
                invokeTasklet(newJob().combineTransactionsLoadStep(new TransactionCombineProcessor()),
                        mismatched));
    }

    @Test
    @DisplayName("A non-digit numeric field and an unrecognised sign overpunch are both refused")
    void malformedNumericFieldsAreRefused() {
        final String valid =
                transactionWriter.composeFixedWidthImage(transaction("0000000000000001", "1.00"));

        // TRAN-CAT-CD at bytes 19-22 corrupted to a non-digit.
        final String badCategory = valid.substring(0, 18) + "X" + valid.substring(19);
        assertThatExceptionOfType(FatalProcessingException.class)
                .isThrownBy(() -> loadImage(badCategory, PREFIX + "/badcat.dat"));

        // TRAN-AMT sign overpunch at byte 143 replaced by a character in neither alphabet.
        final String badSign = valid.substring(0, 142) + "*" + valid.substring(143);
        assertThatExceptionOfType(FatalProcessingException.class)
                .isThrownBy(() -> loadImage(badSign, PREFIX + "/badsign.dat"));
    }

    @Test
    @DisplayName("A generation prefix given with a trailing separator is normalised, not doubled")
    void generationPrefixIsNormalised() throws Exception {
        final CombinedTransactionReader reader = mock(CombinedTransactionReader.class);
        when(reader.read()).thenReturn(transaction("0000000000000001", "1.00"), (Transaction) null);

        final CombineTransactionsJob trailing = new CombineTransactionsJob(jobRepository, transactionManager,
                transactionWriter, jdbcTemplate, objectStorage, fileStatusMapper, metricsConfig,
                "COMBTRAN", 100, 10, BUCKET, PREFIX + "///");
        invokeTasklet(trailing.combineTransactionsSortStep(reader, new TransactionCombineProcessor()),
                stepExecution());

        assertThat(uploadCapture().key()).startsWith(PREFIX + "/").doesNotContain("//");
    }

    @Test
    @DisplayName("A prefix of separators only, a non-positive bound and a blank name are all rejected")
    void invalidPrefixAndBoundAreRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new CombineTransactionsJob(jobRepository, transactionManager, transactionWriter, jdbcTemplate,
                        objectStorage, fileStatusMapper, metricsConfig, "COMBTRAN", 100, 10, BUCKET, "///"));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new CombineTransactionsJob(jobRepository, transactionManager, transactionWriter, jdbcTemplate,
                        objectStorage, fileStatusMapper, metricsConfig, "COMBTRAN", 100, 0, BUCKET, PREFIX));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new CombineTransactionsJob(jobRepository, transactionManager, transactionWriter, jdbcTemplate,
                        objectStorage, fileStatusMapper, metricsConfig, " ", 100, 10, BUCKET, PREFIX));
    }

    @Test
    @DisplayName("The job bean carries the configured name")
    void jobBeanIsNamed() {
        final Step sort = mock(Step.class);
        final Step load = mock(Step.class);
        when(sort.getName()).thenReturn("combineTransactionsSortStep");
        when(load.getName()).thenReturn("combineTransactionsLoadStep");
        final CombineTransactionsJob configuration = newJob();
        final Job job = configuration.combineTransactionsJob(
                configuration.combineTransactionsFlow(sort, load));
        assertThat(job.getName()).isEqualTo("COMBTRAN");
    }

    // ---------------------------------------------------------------- helpers

    /** Drives STEP10 over one supplied generation image. */
    private void loadImage(final String image, final String key) throws Exception {
        final StepExecution execution = stepExecution();
        execution.getJobExecution().getExecutionContext()
                .putString("carddemo.transact.combined.object.key", key);
        stubDownload(key, image);
        invokeTasklet(newJob().combineTransactionsLoadStep(new TransactionCombineProcessor()), execution);
    }

    private static List<Object[]> anyBatchArgs() {
        return org.mockito.ArgumentMatchers.anyList();
    }

    private record ArgumentCapture(String key, byte[] payload) { }

    private ArgumentCapture uploadCapture() {
        final org.mockito.ArgumentCaptor<String> keyCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        final org.mockito.ArgumentCaptor<ByteArrayInputStream> bodyCaptor =
                org.mockito.ArgumentCaptor.forClass(ByteArrayInputStream.class);
        verify(objectStorage).upload(eq(BUCKET), keyCaptor.capture(), bodyCaptor.capture(),
                any(ObjectMetadata.class));
        return new ArgumentCapture(keyCaptor.getValue(), bodyCaptor.getValue().readAllBytes());
    }

    private void stubDownload(final String key, final String image) {
        final S3Resource resource = mock(S3Resource.class);
        try {
            when(resource.getInputStream())
                    .thenReturn(new ByteArrayInputStream(image.getBytes(StandardCharsets.ISO_8859_1)));
        } catch (final Exception unreachable) {
            throw new IllegalStateException(unreachable);
        }
        when(objectStorage.download(BUCKET, key)).thenReturn(resource);
    }

    private List<Object[]> captureBatchArgs() {
        final List<Object[]> captured = new ArrayList<>();
        when(jdbcTemplate.batchUpdate(anyString(), anyBatchArgs())).thenAnswer(invocation -> {
            captured.addAll(invocation.<List<Object[]>>getArgument(1));
            return new int[captured.size()];
        });
        return captured;
    }

    private static void invokeTasklet(final Step step, final StepExecution stepExecution) throws Exception {
        final Object tasklet = readField(step, "tasklet");
        final org.springframework.batch.core.scope.context.StepContext stepContext =
                new org.springframework.batch.core.scope.context.StepContext(stepExecution);
        final org.springframework.batch.core.scope.context.ChunkContext chunkContext =
                new org.springframework.batch.core.scope.context.ChunkContext(stepContext);
        final Method execute = tasklet.getClass().getMethod("execute",
                org.springframework.batch.core.StepContribution.class,
                org.springframework.batch.core.scope.context.ChunkContext.class);
        execute.setAccessible(true);
        try {
            execute.invoke(tasklet, stepExecution.createStepContribution(), chunkContext);
        } catch (final java.lang.reflect.InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw wrapped;
        }
    }

    private static Object readField(final Object target, final String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (final NoSuchFieldException continueUp) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static JobExecutionDecider newDecider() throws Exception {
        for (final Class<?> nested : CombineTransactionsJob.class.getDeclaredClasses()) {
            if (JobExecutionDecider.class.isAssignableFrom(nested)) {
                final var constructor = nested.getDeclaredConstructor();
                constructor.setAccessible(true);
                return (JobExecutionDecider) constructor.newInstance();
            }
        }
        throw new AssertionError("no nested JobExecutionDecider found");
    }

    private static JobExecutionListener newListener() throws Exception {
        for (final Class<?> nested : CombineTransactionsJob.class.getDeclaredClasses()) {
            if (JobExecutionListener.class.isAssignableFrom(nested)) {
                final var constructor = nested.getDeclaredConstructor();
                constructor.setAccessible(true);
                return (JobExecutionListener) constructor.newInstance();
            }
        }
        throw new AssertionError("no nested JobExecutionListener found");
    }

    private static FlowExecutionStatus decide(final JobExecutionDecider decider, final ExitStatus exitStatus) {
        final JobExecution execution =
                new JobExecution(new JobInstance(5L, "COMBTRAN"), 6L, new JobParameters());
        final StepExecution step = execution.createStepExecution("combineTransactionsLoadStep");
        step.setExitStatus(exitStatus);
        return decider.decide(execution, step);
    }
}
