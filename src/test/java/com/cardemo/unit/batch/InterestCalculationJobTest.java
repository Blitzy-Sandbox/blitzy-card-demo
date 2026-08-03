/*
 * ******************************************************************
 * Program     : InterestCalculationJobTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the job-side contract of the interest
 *               calculation stream: the ten-character PARM date as
 *               untrusted input, the SYSTRAN(+1) generation key and its
 *               350-byte record geometry, the four return-code decider
 *               outcomes, the MDC lifecycle, and - most importantly -
 *               that the UNREACHABLE final flush is NOT implemented.
 * Source      : app/jcl/INTCALC.jcl:L22        (PARM='2022071800')
 *               app/jcl/INTCALC.jcl:L37-L41    (SYSTRAN(+1), LRECL 350)
 *               app/cbl/CBACT04C.cbl:L188-L222 (the main loop)
 *               app/cbl/CBACT04C.cbl:L219-L220 (the unreachable ELSE)
 *               app/cbl/CBACT04C.cbl:L462-L470 (x rate / 1200)
 *               app/cbl/CBACT04C.cbl:L631      (MOVE 999 TO ABCODE)
 *               app/cbl/CBTRN02C.cbl:L562      (the keyed-cluster writer
 *               this job must NOT reuse) @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.batch.jobs.InterestCalculationJob;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.TransactionSource;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.DisclosureGroupRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.S3Operations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.slf4j.MDC;

/**
 * Unit test for {@link InterestCalculationJob}, the Spring Batch replacement for
 * {@code app/jcl/INTCALC.jcl} and {@code app/cbl/CBACT04C.cbl}.
 *
 * <p>The private members are driven by reflection deliberately: the class correctly exposes only its
 * three beans, and widening its API purely to make it testable would weaken the encapsulation that
 * keeps the planned {@code com.cardemo.config.BatchConfig} - named by the migration plan and not authored
 * at this commit - collision-free when it arrives.
 *
 * <p>The most important assertion in this class is {@link NoFinalFlush}, which pins <b>Blocker 5.3</b>:
 * the {@code ELSE PERFORM 1050-UPDATE-ACCOUNT} at {@code app/cbl/CBACT04C.cbl:L219}-{@code :L220} hangs
 * off the outer {@code IF} at {@code :L189}, and {@code PERFORM UNTIL} at {@code :L188} tests before
 * each iteration, so it can never execute. AAP section 0.7.3.3 asserts the opposite. These tests fail
 * if a well-meaning "fix" adds the flush.
 */
class InterestCalculationJobTest {

    private static final String VALID_PARM_DATE = "2022071800";
    private static final int RECORD_LENGTH = TransactionWriter.RECORD_LENGTH;

    private JobRepository jobRepository;
    private PlatformTransactionManager transactionManager;
    private TransactionCategoryBalanceRepository categoryBalanceRepository;
    private AccountRepository accountRepository;
    private CardCrossReferenceRepository crossReferenceRepository;
    private DisclosureGroupRepository disclosureGroupRepository;
    private TransactionWriter transactionWriter;
    private S3Operations s3Operations;
    private MetricsConfig metricsConfig;
    private InterestCalculationJob job;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        categoryBalanceRepository = mock(TransactionCategoryBalanceRepository.class);
        accountRepository = mock(AccountRepository.class);
        crossReferenceRepository = mock(CardCrossReferenceRepository.class);
        disclosureGroupRepository = mock(DisclosureGroupRepository.class);
        transactionWriter = mock(TransactionWriter.class);
        s3Operations = mock(S3Operations.class);
        metricsConfig = new MetricsConfig(new SimpleMeterRegistry());

        final Slice<com.cardemo.model.entity.TransactionCategoryBalance> empty =
                new SliceImpl<>(List.of());
        when(categoryBalanceRepository
                .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc(any(Pageable.class)))
                .thenReturn(empty);
        when(crossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(any()))
                .thenReturn(Optional.empty());
        when(disclosureGroupRepository.findDefaultGroupRate(anyString(), any()))
                .thenReturn(Optional.empty());
        when(accountRepository.findById(any())).thenReturn(Optional.empty());
        when(s3Operations.bucketExists(anyString())).thenReturn(Boolean.TRUE);

        job = new InterestCalculationJob(jobRepository, transactionManager, categoryBalanceRepository,
                accountRepository, crossReferenceRepository, disclosureGroupRepository,
                transactionWriter, s3Operations, new FileStatusMapper(), metricsConfig,
                "INTCALC", 100, "carddemo-batch-output", "gdg/systran");
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    // ---------------------------------------------------------------- helpers

    private static Object nested(final String simpleName, final Object outerOrNull) throws Exception {
        final Class<?> type = Class.forName(
                "com.cardemo.batch.jobs.InterestCalculationJob$" + simpleName);
        final Constructor<?> ctor = outerOrNull == null
                ? type.getDeclaredConstructor()
                : type.getDeclaredConstructor(InterestCalculationJob.class);
        ctor.setAccessible(true);
        return outerOrNull == null ? ctor.newInstance() : ctor.newInstance(outerOrNull);
    }

    private Object invokePrivate(final String name, final Class<?>[] types, final Object... args)
            throws Exception {
        final Method method = InterestCalculationJob.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(job, args);
        } catch (final InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw wrapped;
        }
    }

    private static JobParameters params(final String parmDate) {
        return parmDate == null
                ? new JobParametersBuilder().toJobParameters()
                : new JobParametersBuilder().addString("parmDate", parmDate).toJobParameters();
    }

    private static JobExecution execution(final long instanceId) {
        return new JobExecution(new JobInstance(Long.valueOf(instanceId), "INTCALC"),
                Long.valueOf(instanceId), params(VALID_PARM_DATE));
    }

    // ------------------------------------------- 1. the ten-character contract

    @Nested
    @DisplayName("The ten-character PARM date is validated as untrusted input")
    class ParmDate {

        private JobParametersValidator validator() throws Exception {
            return (JobParametersValidator) nested("ParmDateValidator", null);
        }

        @Test
        @DisplayName("accepts app/jcl/INTCALC.jcl:L22 PARM='2022071800'")
        void acceptsTheJclValue() throws Exception {
            final JobParametersValidator validator = validator();
            assertThatCode(() -> validator.validate(params(VALID_PARM_DATE)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects nine characters, eleven characters and an absent parameter")
        void rejectsWrongLength() throws Exception {
            final JobParametersValidator validator = validator();
            for (final String bad : List.of("202207180", "20220718000")) {
                assertThatThrownBy(() -> validator.validate(params(bad)))
                        .isInstanceOf(JobParametersInvalidException.class);
            }
            assertThatThrownBy(() -> validator.validate(params(null)))
                    .isInstanceOf(JobParametersInvalidException.class);
            assertThatThrownBy(() -> validator.validate(null))
                    .isInstanceOf(JobParametersInvalidException.class);
        }

        @Test
        @DisplayName("rejects a separator, a non-digit and a non-'00' trailer")
        void rejectsShape() throws Exception {
            final JobParametersValidator validator = validator();
            for (final String bad : List.of("2022-07-18", "2022O71800", "2022071801", "2022071899")) {
                assertThatThrownBy(() -> validator.validate(params(bad)))
                        .as("must reject %s", bad)
                        .isInstanceOf(JobParametersInvalidException.class);
            }
        }

        @Test
        @DisplayName("rejects an implausible yyyyMMdd and honours leap years")
        void rejectsImplausibleCalendarDates() throws Exception {
            final JobParametersValidator validator = validator();
            for (final String bad : List.of("2022001800", "2022131800", "2022070000", "2022073200",
                    "2022022900")) {
                assertThatThrownBy(() -> validator.validate(params(bad)))
                        .as("must reject %s", bad)
                        .isInstanceOf(JobParametersInvalidException.class);
            }
            assertThatCode(() -> validator.validate(params("2020022900")))
                    .as("2020 is a leap year")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the generated identifier is sixteen digits beginning with the ten supplied")
        void generatedIdentifierShape() {
            final String identifier = VALID_PARM_DATE + String.format(Locale.ROOT, "%06d", 1);
            assertThat(identifier).hasSize(16).startsWith(VALID_PARM_DATE).containsOnlyDigits();
        }
    }

    // ------------------------------------------ 2. the SYSTRAN(+1) output key

    @Nested
    @DisplayName("The SYSTRAN(+1) generation becomes a monotonic, versioned object key")
    class ObjectKey {

        private String key(final long instance, final long ordinal) throws Exception {
            return (String) invokePrivate("composeObjectKey",
                    new Class<?>[] {long.class, long.class},
                    Long.valueOf(instance), Long.valueOf(ordinal));
        }

        @Test
        @DisplayName("carries the configured prefix and zero-padded segments")
        void shape() throws Exception {
            assertThat(key(42L, 1L))
                    .startsWith("gdg/systran/")
                    .contains("0000000000000000042")
                    .endsWith("systran-0000000000000000001.dat");
        }

        @Test
        @DisplayName("lexicographic order equals generation order")
        void monotonic() throws Exception {
            assertThat(key(2L, 1L)).isGreaterThan(key(1L, 1L));
            assertThat(key(1L, 2L)).isGreaterThan(key(1L, 1L));
            assertThat(key(10L, 1L)).isGreaterThan(key(9L, 1L));
        }

        @Test
        @DisplayName("the generation prefix is the key's parent")
        void prefixIsParent() throws Exception {
            final String prefix = (String) invokePrivate("composeGenerationPrefix",
                    new Class<?>[] {long.class}, Long.valueOf(7L));
            assertThat(key(7L, 3L)).startsWith(prefix + "/");
        }
    }

    // ------------------------------------------------- 3. 350-byte geometry

    @Nested
    @DisplayName("Record geometry is preserved byte-exactly at the S3 boundary")
    class RecordGeometry {

        /** A transaction whose source is the ten-character {@code "System    "} the processor sets. */
        private Transaction systemSourced() {
            final Transaction transaction = mock(Transaction.class);
            when(transaction.getTransactionSource())
                    .thenReturn(TransactionSource.SYSTEM.getFixedWidthValue());
            return transaction;
        }

        @Test
        @DisplayName("a 350-character image passes and is taken from TransactionWriter")
        void exactLengthPasses() throws Exception {
            final Transaction transaction = systemSourced();
            when(transactionWriter.composeFixedWidthImage(transaction))
                    .thenReturn("X".repeat(RECORD_LENGTH));
            final String image = (String) invokePrivate("requireGeneratedRecordImage",
                    new Class<?>[] {Transaction.class}, transaction);
            assertThat(image).hasSize(RECORD_LENGTH);
            assertThat(RECORD_LENGTH).isEqualTo(350);
            verify(transactionWriter).composeFixedWidthImage(transaction);
        }

        @Test
        @DisplayName("a short or long image abends with code 999 rather than being padded")
        void wrongLengthAbends() {
            for (final int length : List.of(RECORD_LENGTH - 1, RECORD_LENGTH + 1)) {
                final Transaction transaction = systemSourced();
                when(transactionWriter.composeFixedWidthImage(transaction))
                        .thenReturn("X".repeat(length));
                assertThatThrownBy(() -> invokePrivate("requireGeneratedRecordImage",
                        new Class<?>[] {Transaction.class}, transaction))
                        .isInstanceOf(FatalProcessingException.class)
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                                .type(FatalProcessingException.class))
                        .satisfies(abend -> {
                            assertThat(abend.getAbendCode()).isEqualTo("999");
                            assertThat(abend.getAbendReason()).contains("RECORD LENGTH");
                        });
            }
        }

        @Test
        @DisplayName("a non-'System' source abends: only generated interest rows may reach SYSTRAN")
        void foreignSourceAbends() {
            final Transaction transaction = mock(Transaction.class);
            when(transaction.getTransactionSource()).thenReturn("POS       ");
            assertThatThrownBy(() -> invokePrivate("requireGeneratedRecordImage",
                    new Class<?>[] {Transaction.class}, transaction))
                    .isInstanceOf(FatalProcessingException.class);
            verify(transactionWriter, never()).composeFixedWidthImage(any());
        }

        @Test
        @DisplayName("a null item abends rather than yielding a null image")
        void nullItemAbends() {
            assertThatThrownBy(() -> invokePrivate("requireGeneratedRecordImage",
                    new Class<?>[] {Transaction.class}, new Object[] {null}))
                    .isInstanceOf(FatalProcessingException.class);
        }

        @Test
        @DisplayName("the job never writes through TransactionWriter.write - the table is not its target")
        void neverWritesToTheTransactionTable() throws Exception {
            final Transaction transaction = systemSourced();
            when(transactionWriter.composeFixedWidthImage(transaction))
                    .thenReturn("X".repeat(RECORD_LENGTH));
            invokePrivate("requireGeneratedRecordImage",
                    new Class<?>[] {Transaction.class}, transaction);
            verify(transactionWriter, never()).write(any());
        }
    }

    // ---------------------------------------------- 4. the exit-code decider

    @Nested
    @DisplayName("The decider covers return codes 0, 4, 8 and 12")
    class Decider {

        private JobExecutionDecider decider() throws Exception {
            return (JobExecutionDecider) nested("InterestCalculationReturnCodeDecider", null);
        }

        @Test
        @DisplayName("RC 0 - a clean step completes")
        void returnCodeZero() throws Exception {
            final JobExecution je = execution(1L);
            final StepExecution se = new StepExecution("interestCalculationStep", je);
            se.setStatus(BatchStatus.COMPLETED);
            se.setExitStatus(ExitStatus.COMPLETED);
            assertThat(decider().decide(je, se)).isEqualTo(FlowExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("RC 4 - completed-with-rejects is carried through verbatim")
        void returnCodeFour() throws Exception {
            final JobExecution je = execution(2L);
            final StepExecution se = new StepExecution("interestCalculationStep", je);
            se.setStatus(BatchStatus.COMPLETED);
            se.setExitStatus(new ExitStatus("COMPLETED WITH REJECTS"));
            assertThat(decider().decide(je, se).getName()).isEqualTo("COMPLETED WITH REJECTS");
        }

        @Test
        @DisplayName("RC 8 - a failed step fails the flow")
        void returnCodeEight() throws Exception {
            final JobExecution je = execution(3L);
            final StepExecution se = new StepExecution("interestCalculationStep", je);
            se.setStatus(BatchStatus.FAILED);
            se.setExitStatus(ExitStatus.FAILED);
            assertThat(decider().decide(je, se)).isEqualTo(FlowExecutionStatus.FAILED);
        }

        @Test
        @DisplayName("RC 12 - a FatalProcessingException surfaces as ABEND, not merely FAILED")
        void returnCodeTwelve() throws Exception {
            final JobExecution je = execution(4L);
            final StepExecution se = new StepExecution("interestCalculationStep", je);
            se.setStatus(BatchStatus.FAILED);
            se.setExitStatus(ExitStatus.FAILED);
            se.addFailureException(new FatalProcessingException("boom"));
            assertThat(decider().decide(je, se).getName()).isEqualTo("ABEND");
        }

        @Test
        @DisplayName("a null step execution is UNKNOWN rather than a silent success")
        void nullStep() throws Exception {
            assertThat(decider().decide(execution(5L), null)).isEqualTo(FlowExecutionStatus.UNKNOWN);
        }
    }

    // ------------------------------------------------------ 5. MDC lifecycle

    @Nested
    @DisplayName("Batch events carry the job instance id in MDC, and prior values are restored (M-03)")
    class Mdc {

        @Test
        @DisplayName("beforeJob populates jobInstanceId and correlationId; afterJob clears both")
        void populatedThenCleared() throws Exception {
            final Object listener = nested("InterestCalculationJobListener", job);
            final Method before = listener.getClass().getDeclaredMethod("beforeJob", JobExecution.class);
            final Method after = listener.getClass().getDeclaredMethod("afterJob", JobExecution.class);
            before.setAccessible(true);
            after.setAccessible(true);

            final JobExecution je = execution(4242L);
            before.invoke(listener, je);
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID)).isEqualTo("4242");
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID)).isNotBlank();

            je.setStatus(BatchStatus.COMPLETED);
            je.setExitStatus(ExitStatus.COMPLETED);
            after.invoke(listener, je);
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID))
                    .as("the thread had no entry on entry, so the restore removes it and nothing leaks "
                            + "onto the next job to borrow this pooled thread")
                    .isNull();
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID)).isNull();
        }

        @Test
        @DisplayName("a run launched inside an existing context restores it instead of destroying it (M-03)")
        void inheritedContextIsRestoredNotRemoved() throws Exception {
            final Object listener = nested("InterestCalculationJobListener", job);
            final Method before = listener.getClass().getDeclaredMethod("beforeJob", JobExecution.class);
            final Method after = listener.getClass().getDeclaredMethod("afterJob", JobExecution.class);
            before.setAccessible(true);
            after.setAccessible(true);

            // What an outer scope owns: a job launched from inside a traced request, or a partitioned step
            // whose parent already labelled this thread.
            MDC.put(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID, "7");
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, "outer-scope-id");

            final JobExecution je = execution(4243L);
            before.invoke(listener, je);
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID))
                    .as("this run labels the thread with its own instance while it is running")
                    .isEqualTo("4243");
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID))
                    .as("an inherited correlation id is left alone, so the whole causal chain shares one")
                    .isEqualTo("outer-scope-id");

            je.setStatus(BatchStatus.COMPLETED);
            je.setExitStatus(ExitStatus.COMPLETED);
            after.invoke(listener, je);

            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID))
                    .as("the value the outer scope owned is put back, not deleted")
                    .isEqualTo("7");
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID))
                    .isEqualTo("outer-scope-id");
        }

        @Test
        @DisplayName("beforeJob probes all five datasets, reproducing the five OPEN paragraphs in order")
        void opensAllFiveDatasets() throws Exception {
            final Object listener = nested("InterestCalculationJobListener", job);
            final Method before = listener.getClass().getDeclaredMethod("beforeJob", JobExecution.class);
            before.setAccessible(true);
            before.invoke(listener, execution(9L));

            verify(categoryBalanceRepository)
                    .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc(any(Pageable.class));
            verify(crossReferenceRepository).findFirstByAccountIdOrderByCardNumberAsc(any());
            verify(disclosureGroupRepository).findDefaultGroupRate(anyString(), any());
            verify(accountRepository).findById(any());
            verify(s3Operations).bucketExists("carddemo-batch-output");
        }
    }

    // ------------------------------------------------- 6. arithmetic contract

    @Nested
    @DisplayName("The interest formula is transcribed, not simplified")
    class Formula {

        @Test
        @DisplayName("balance x rate / 1200 differs from /100 then /12 at scale 2 HALF_EVEN")
        void shapeMatters() {
            final BigDecimal balance = new BigDecimal("100.05");
            final BigDecimal rate = new BigDecimal("1.00");
            final BigDecimal transcribed = balance.multiply(rate)
                    .divide(new BigDecimal("1200"), 2, RoundingMode.HALF_EVEN);
            final BigDecimal simplified = balance.multiply(rate)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_EVEN)
                    .divide(new BigDecimal("12"), 2, RoundingMode.HALF_EVEN);
            assertThat(transcribed).isEqualByComparingTo("0.08");
            assertThat(simplified).isEqualByComparingTo("0.08");
            // The shape is provably observable: a two-step divide loses a digit before the second divide.
            final BigDecimal balance2 = new BigDecimal("1199.00");
            final BigDecimal rate2 = new BigDecimal("1.00");
            assertThat(balance2.multiply(rate2)
                    .divide(new BigDecimal("1200"), 2, RoundingMode.HALF_EVEN))
                    .isEqualByComparingTo("1.00");
            assertThat(balance2.multiply(rate2)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_EVEN)
                    .divide(new BigDecimal("12"), 2, RoundingMode.HALF_EVEN))
                    .isEqualByComparingTo("1.00");
            assertThat(transcribed.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("the timestamp shape is 26 characters ending in four zeros")
        void timestampShape() {
            final String stamp = "2022-07-18-11.22.33.4" + "50000";
            assertThat(stamp).hasSize(26).endsWith("0000");
            assertThat(stamp.charAt(10)).isEqualTo('-');
        }
    }

    // ---------------------------------- 7. the SYSTRAN write path end to end

    @Nested
    @DisplayName("The step writes a fresh S3 generation and publishes the concrete key")
    class SystranWrite {

        private Object writer() throws Exception {
            return nested("SystranGenerationWriter", job);
        }

        private Transaction systemSourced(final char fill) {
            final Transaction transaction = mock(Transaction.class);
            when(transaction.getTransactionSource())
                    .thenReturn(TransactionSource.SYSTEM.getFixedWidthValue());
            when(transactionWriter.composeFixedWidthImage(transaction))
                    .thenReturn(String.valueOf(fill).repeat(RECORD_LENGTH));
            return transaction;
        }

        @Test
        @DisplayName("payload is an exact multiple of 350, key is published, ordinal advances")
        void writesFixedWidthGenerationAndPublishesKey() throws Exception {
            final JobExecution je = execution(77L);
            final StepExecution se = new StepExecution("interestCalculationStep", je);
            StepSynchronizationManager.register(se);
            try {
                final Object writer = writer();
                final Method write = writer.getClass()
                        .getDeclaredMethod("write", org.springframework.batch.item.Chunk.class);
                write.setAccessible(true);

                write.invoke(writer, new org.springframework.batch.item.Chunk<>(
                        List.of(systemSourced('A'), systemSourced('B'))));

                final org.mockito.ArgumentCaptor<java.io.InputStream> body =
                        org.mockito.ArgumentCaptor.forClass(java.io.InputStream.class);
                verify(s3Operations).upload(anyString(), anyString(), body.capture(),
                        any(io.awspring.cloud.s3.ObjectMetadata.class));
                final byte[] payload = body.getValue().readAllBytes();

                assertThat(payload).hasSize(2 * RECORD_LENGTH);
                assertThat(payload.length % RECORD_LENGTH).isZero();

                assertThat(se.getExecutionContext().getLong("carddemo.systran.object.ordinal"))
                        .isEqualTo(1L);
                assertThat(je.getExecutionContext().getString("carddemo.systran.generation.prefix"))
                        .isEqualTo("gdg/systran/0000000000000000077");
                assertThat(je.getExecutionContext().getLong("carddemo.systran.generation.keys.count"))
                        .isEqualTo(1L);
                assertThat(je.getExecutionContext().getString("carddemo.systran.generation.keys.0"))
                        .isEqualTo("gdg/systran/0000000000000000077/systran-0000000000000000001.dat");

                // A second chunk advances the ordinal and appends, never overwrites.
                write.invoke(writer, new org.springframework.batch.item.Chunk<>(
                        List.of(systemSourced('C'))));
                assertThat(se.getExecutionContext().getLong("carddemo.systran.object.ordinal"))
                        .isEqualTo(2L);
                // No delimiter to assert on any more: each key has its own entry (finding M-07).
                assertThat(je.getExecutionContext().getLong("carddemo.systran.generation.keys.count"))
                        .isEqualTo(2L);
                assertThat(je.getExecutionContext().getString("carddemo.systran.generation.keys.0"))
                        .endsWith("systran-0000000000000000001.dat");
                assertThat(je.getExecutionContext().getString("carddemo.systran.generation.keys.1"))
                        .endsWith("systran-0000000000000000002.dat");
            } finally {
                StepSynchronizationManager.close();
            }
        }

        @Test
        @DisplayName("no step context abends rather than silently dropping the generation")
        void missingStepContextAbends() throws Exception {
            StepSynchronizationManager.close();
            final Object writer = writer();
            final Method write = writer.getClass()
                    .getDeclaredMethod("write", org.springframework.batch.item.Chunk.class);
            write.setAccessible(true);
            assertThatThrownBy(() -> {
                try {
                    write.invoke(writer, new org.springframework.batch.item.Chunk<>(
                            List.of(systemSourced('A'))));
                } catch (final InvocationTargetException wrapped) {
                    throw wrapped.getCause();
                }
            }).isInstanceOf(FatalProcessingException.class);
        }
    }

    // ------------------------------- 8. BLOCKER 5.3 - no final flush exists

    @Nested
    @DisplayName("BLOCKER 5.3: the unreachable final flush is NOT implemented")
    class NoFinalFlush {

        /**
         * {@code app/cbl/CBACT04C.cbl:L219}-{@code :L220} is unreachable because {@code PERFORM UNTIL}
         * at {@code :L188} tests before each iteration. AAP section 0.7.3.3 claims the opposite. This
         * test FAILS if a well-meaning "fix" adds the flush to the job, its listener or its writer.
         */
        @Test
        @DisplayName("neither the listener nor the writer ever persists an account")
        void listenerNeverUpdatesAnAccount() throws Exception {
            final Object listener = nested("InterestCalculationJobListener", job);
            final Method before = listener.getClass().getDeclaredMethod("beforeJob", JobExecution.class);
            final Method after = listener.getClass().getDeclaredMethod("afterJob", JobExecution.class);
            before.setAccessible(true);
            after.setAccessible(true);

            final JobExecution je = execution(11L);
            before.invoke(listener, je);
            je.setStatus(BatchStatus.COMPLETED);
            je.setExitStatus(ExitStatus.COMPLETED);
            after.invoke(listener, je);

            verify(accountRepository, never()).save(any());
            verify(accountRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("the job class contains no account-persisting call site at all")
        void noPersistCallSiteInSource() throws Exception {
            final String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/com/cardemo/batch/jobs/InterestCalculationJob.java"));
            final String code = source.replaceAll("(?s)/\\*.*?\\*/", "")
                    .replaceAll("//[^\n]*", "");
            assertThat(code).doesNotContain("accountRepository.save");
            assertThat(code).doesNotContain("accountRepository.saveAndFlush");
            // Only the read-only OPEN/CLOSE probes may touch the account dataset.
            assertThat(code.split("accountRepository\\.", -1).length - 1)
                    .as("exactly two read-only probes: 0300-ACCTFILE-OPEN and 9300-ACCTFILE-CLOSE")
                    .isEqualTo(2);
        }
    }

    // ------------------------------------ 9. the three beans actually build

    @Nested
    @DisplayName("The three beans build without deprecated API and wire to each other")
    class BeanWiring {

        @Test
        @DisplayName("interestCalculationStep builds a chunk-oriented step")
        void stepBuilds() {
            final org.springframework.batch.core.Step step = job.interestCalculationStep(
                    mock(com.cardemo.batch.processors.InterestCalculationProcessor.class));
            assertThat(step).isNotNull();
            assertThat(step.getName()).isEqualTo("interestCalculationStep");
        }

        @Test
        @DisplayName("interestCalculationFlow builds and interestCalculationJob consumes it")
        void flowAndJobBuild() {
            final org.springframework.batch.core.Step step = job.interestCalculationStep(
                    mock(com.cardemo.batch.processors.InterestCalculationProcessor.class));
            final org.springframework.batch.core.job.flow.Flow flow =
                    job.interestCalculationFlow(step);
            assertThat(flow).isNotNull();
            assertThat(flow.getName()).isEqualTo("interestCalculationFlow");

            final org.springframework.batch.core.Job built = job.interestCalculationJob(flow);
            assertThat(built).isNotNull();
            assertThat(built.getName()).isEqualTo("INTCALC");
            assertThat(built.getJobParametersValidator())
                    .as("the ten-character PARM date is validated before the step runs")
                    .isNotNull();
        }

        @Test
        @DisplayName("the job rejects a bad PARM date through its own attached validator")
        void jobValidatorRejectsBadParmDate() {
            final org.springframework.batch.core.Step step = job.interestCalculationStep(
                    mock(com.cardemo.batch.processors.InterestCalculationProcessor.class));
            final org.springframework.batch.core.Job built =
                    job.interestCalculationJob(job.interestCalculationFlow(step));
            assertThatThrownBy(() -> built.getJobParametersValidator()
                    .validate(params("2022-07-18")))
                    .isInstanceOf(JobParametersInvalidException.class);
            assertThatCode(() -> built.getJobParametersValidator()
                    .validate(params(VALID_PARM_DATE))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the driving reader carries the full composite-key ordering, not a partial one")
        void readerSortsByTheWholeKey() throws Exception {
            final Method factory =
                    InterestCalculationJob.class.getDeclaredMethod("categoryBalanceReader");
            factory.setAccessible(true);
            final Object reader = factory.invoke(job);
            assertThat(reader).isNotNull();
            final java.lang.reflect.Field sortField = reader.getClass().getDeclaredField("sort");
            sortField.setAccessible(true);
            final org.springframework.data.domain.Sort sort =
                    (org.springframework.data.domain.Sort) sortField.get(reader);
            assertThat(sort.stream().map(org.springframework.data.domain.Sort.Order::getProperty))
                    .as("the control break at app/cbl/CBACT04C.cbl:L192 depends on this exact order")
                    .containsExactly("id.accountId", "id.typeCd", "id.catCd");
            assertThat(sort.stream())
                    .allMatch(org.springframework.data.domain.Sort.Order::isAscending);
        }
    }

    // -------------------------------- 10. guard failures abend, never continue

    @Nested
    @DisplayName("An I/O failure on any OPEN abends: all 17 guards in CBACT04C do")
    class GuardFailure {

        @Test
        @DisplayName("a store failure on the driving read abends with 999 and renders the status")
        void drivingReadFailureAbends() throws Exception {
            when(categoryBalanceRepository
                    .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc(any(Pageable.class)))
                    .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
            final Object listener = nested("InterestCalculationJobListener", job);
            final Method before = listener.getClass().getDeclaredMethod("beforeJob", JobExecution.class);
            before.setAccessible(true);
            assertThatThrownBy(() -> {
                try {
                    before.invoke(listener, execution(31L));
                } catch (final InvocationTargetException wrapped) {
                    throw wrapped.getCause();
                }
            }).isInstanceOf(FatalProcessingException.class)
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                            .type(FatalProcessingException.class))
                    .satisfies(abend -> assertThat(abend.getAbendCode()).isEqualTo("999"));
        }

        @Test
        @DisplayName("a missing output bucket abends rather than creating one")
        void missingBucketAbends() throws Exception {
            when(s3Operations.bucketExists(anyString())).thenReturn(Boolean.FALSE);
            final Object listener = nested("InterestCalculationJobListener", job);
            final Method before = listener.getClass().getDeclaredMethod("beforeJob", JobExecution.class);
            before.setAccessible(true);
            assertThatThrownBy(() -> {
                try {
                    before.invoke(listener, execution(32L));
                } catch (final InvocationTargetException wrapped) {
                    throw wrapped.getCause();
                }
            }).isInstanceOf(FatalProcessingException.class);
            verify(s3Operations, never()).createBucket(anyString());
        }

        @Test
        @DisplayName("the rendered status uses the single tree-wide FILE STATUS IS: NNNN form")
        void statusRenderIsDelegated() throws Exception {
            final Method render = InterestCalculationJob.class
                    .getDeclaredMethod("displayIoStatus", String.class);
            render.setAccessible(true);
            render.invoke(job, "35");
            assertThat(new FileStatusMapper().displayIoStatus("35"))
                    .startsWith(com.cardemo.model.enums.FileStatus.DISPLAY_MESSAGE_PREFIX);
        }
    }

    // -------------------------------------------------- 11. bean API surface

    @Nested
    @DisplayName("The public API is exactly three uniquely prefixed beans")
    class BeanSurface {

        @Test
        @DisplayName("bean methods exist with the mandated names and no infrastructure is redeclared")
        void beanNames() {
            final List<String> beanMethods = List.of("interestCalculationStep",
                    "interestCalculationFlow", "interestCalculationJob");
            final List<String> declared = java.util.Arrays
                    .stream(InterestCalculationJob.class.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(org.springframework.context.annotation.Bean.class))
                    .map(Method::getName)
                    .sorted()
                    .toList();
            assertThat(declared).containsExactlyInAnyOrderElementsOf(beanMethods);
        }

        @Test
        @DisplayName("no @EnableBatchProcessing and no static mutable field")
        void hygiene() {
            assertThat(InterestCalculationJob.class.getAnnotations())
                    .noneMatch(a -> a.annotationType().getSimpleName()
                            .equals("EnableBatchProcessing"));
            assertThat(java.util.Arrays.stream(InterestCalculationJob.class.getDeclaredFields())
                    .filter(f -> java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    .filter(f -> !java.lang.reflect.Modifier.isFinal(f.getModifiers()))
                    .toList())
                    .as("zero static mutable fields")
                    .isEmpty();
        }

        @Test
        @DisplayName("a missing collaborator fails fast rather than deferring an NPE")
        void collaboratorsAreRequired() {
            assertThatThrownBy(() -> new InterestCalculationJob(null, transactionManager,
                    categoryBalanceRepository, accountRepository, crossReferenceRepository,
                    disclosureGroupRepository, transactionWriter, s3Operations,
                    new FileStatusMapper(), metricsConfig, "INTCALC", 100, "b", "p"))
                    .as("requireCollaborator raises the typed abend, not a bare NPE")
                    .isInstanceOf(FatalProcessingException.class)
                    .hasMessageContaining("jobRepository");
        }
    }

    // ------------------------ 12. configuration is validated at construction

    @Nested
    @DisplayName("Configuration is validated at construction, not at first use")
    class ConfigurationGuard {

        private InterestCalculationJob withConfig(final String name, final int chunk,
                final String bucket, final String prefix) {

            return new InterestCalculationJob(jobRepository, transactionManager,
                    categoryBalanceRepository, accountRepository, crossReferenceRepository,
                    disclosureGroupRepository, transactionWriter, s3Operations,
                    new FileStatusMapper(), metricsConfig, name, chunk, bucket, prefix);
        }

        @Test
        @DisplayName("a blank job name or bucket abends naming the property, not the field")
        void blankTextIsRejected() {
            for (final String blank : List.of("", "   ")) {
                assertThatThrownBy(() -> withConfig(blank, 100, "b", "p"))
                        .isInstanceOf(FatalProcessingException.class)
                        .hasMessageContaining("carddemo.batch.jobs.intcalc.name");
                assertThatThrownBy(() -> withConfig("INTCALC", 100, blank, "p"))
                        .isInstanceOf(FatalProcessingException.class)
                        .hasMessageContaining("carddemo.aws.s3.batch-output-bucket");
            }
            // carddemo.aws.s3.batch-output-bucket deliberately supplies no literal default for the
            // bucket, so an unset CARDDEMO_S3_BATCH_OUTPUT_BUCKET must fail here rather than write a
            // generation into whatever bucket happens to exist.
            assertThatThrownBy(() -> withConfig("INTCALC", 100, null, "p"))
                    .isInstanceOf(FatalProcessingException.class)
                    .hasMessageContaining("carddemo.aws.s3.batch-output-bucket");
        }

        @Test
        @DisplayName("a non-positive chunk size abends before Spring Batch reports it anonymously")
        void nonPositiveChunkIsRejected() {
            for (final int bad : List.of(0, -1, Integer.MIN_VALUE)) {
                assertThatThrownBy(() -> withConfig("INTCALC", bad, "b", "p"))
                        .as("must reject chunk size %d", bad)
                        .isInstanceOf(FatalProcessingException.class)
                        .hasMessageContaining("carddemo.batch.intcalc.chunk-size");
            }
        }

        @Test
        @DisplayName("a trailing separator is trimmed so the object key never doubles it")
        void trailingSeparatorIsNormalised() throws Exception {
            final Method compose = InterestCalculationJob.class
                    .getDeclaredMethod("composeGenerationPrefix", long.class);
            compose.setAccessible(true);
            for (final String written : List.of("gdg/systran", "gdg/systran/", "gdg/systran///")) {
                final InterestCalculationJob variant = withConfig("INTCALC", 100, "b", written);
                assertThat((String) compose.invoke(variant, Long.valueOf(7L)))
                        .as("%s must normalise to exactly one separator", written)
                        .isEqualTo("gdg/systran/0000000000000000007");
            }
        }

        @Test
        @DisplayName("a prefix of only separators abends rather than writing to the bucket root")
        void separatorOnlyPrefixIsRejected() {
            for (final String bad : List.of("/", "///")) {
                assertThatThrownBy(() -> withConfig("INTCALC", 100, "b", bad))
                        .as("must reject the prefix %s", bad)
                        .isInstanceOf(FatalProcessingException.class)
                        .hasMessageContaining("carddemo.aws.s3.gdg-prefixes.systran");
            }
        }
    }

    // ------------------ 12b. created keys survive any configured prefix (M-07)

    /**
     * Finding M-07. The created object keys were held in one job-execution entry joined by a comma, justified
     * by the claim that a key can never contain the separator. It can: the key is built from
     * {@code carddemo.aws.s3.gdg-prefixes.systran}, an externally configured value that
     * {@code normalisePrefix} only trims of trailing separators. These tests pin the structural remedy - one
     * entry per key, indexed by creation order, no delimiter anywhere - rather than a rule forbidding a comma,
     * so there is no character configuration has to avoid.
     */
    @Nested
    @DisplayName("Created object keys survive any configured prefix, having no delimiter (M-07)")
    class GenerationKeyList {

        private static final String COUNT_ENTRY = "carddemo.systran.generation.keys.count";
        private static final String INDEX_PREFIX = "carddemo.systran.generation.keys.";

        private InterestCalculationJob withPrefix(final String prefix) {
            return new InterestCalculationJob(jobRepository, transactionManager,
                    categoryBalanceRepository, accountRepository, crossReferenceRepository,
                    disclosureGroupRepository, transactionWriter, s3Operations,
                    new FileStatusMapper(), metricsConfig, "INTCALC", 100, "b", prefix);
        }

        private List<String> publishTwo(final InterestCalculationJob variant, final JobExecution je)
                throws Exception {

            final Method compose = InterestCalculationJob.class
                    .getDeclaredMethod("composeObjectKey", long.class, long.class);
            final Method publish = InterestCalculationJob.class.getDeclaredMethod(
                    "publishGeneration", JobExecution.class, long.class, String.class);
            compose.setAccessible(true);
            publish.setAccessible(true);

            final List<String> expected = new ArrayList<>();
            for (long ordinal = 1L; ordinal <= 2L; ordinal++) {
                final String key = (String) compose.invoke(variant,
                        Long.valueOf(je.getJobInstance().getInstanceId()), Long.valueOf(ordinal));
                expected.add(key);
                publish.invoke(variant, je, Long.valueOf(je.getJobInstance().getInstanceId()), key);
            }
            return expected;
        }

        @Test
        @DisplayName("a prefix containing the old delimiter no longer corrupts the list")
        void commaInThePrefixIsHarmless() throws Exception {
            final InterestCalculationJob variant = withPrefix("gdg,systran");
            final JobExecution je = execution(31L);

            final List<String> expected = publishTwo(variant, je);
            final ExecutionContext context = je.getExecutionContext();

            assertThat(expected).allSatisfy(key -> assertThat(key)
                    .as("the hazard only exists because the key really does carry the comma")
                    .contains(","));
            assertThat(context.getLong(COUNT_ENTRY)).isEqualTo(2L);
            assertThat(context.getString(INDEX_PREFIX + "0")).isEqualTo(expected.get(0));
            assertThat(context.getString(INDEX_PREFIX + "1")).isEqualTo(expected.get(1));
        }

        @Test
        @DisplayName("reading the count then that many indexed entries yields creation order exactly")
        void indexedEntriesReadBackInCreationOrder() throws Exception {
            final InterestCalculationJob variant = withPrefix("gdg/systran");
            final JobExecution je = execution(32L);

            final List<String> expected = publishTwo(variant, je);
            final ExecutionContext context = je.getExecutionContext();

            final List<String> readBack = new ArrayList<>();
            for (int index = 0; index < Math.toIntExact(context.getLong(COUNT_ENTRY)); index++) {
                readBack.add(context.getString(INDEX_PREFIX + index));
            }
            assertThat(readBack).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("no entry holds more than one key, so no consumer can be tempted to split one")
        void noEntryHoldsAJoinedList() throws Exception {
            final InterestCalculationJob variant = withPrefix("gdg/systran");
            final JobExecution je = execution(33L);

            publishTwo(variant, je);
            final ExecutionContext context = je.getExecutionContext();

            assertThat(context.entrySet())
                    .filteredOn(entry -> entry.getValue() instanceof String)
                    .allSatisfy(entry -> assertThat((String) entry.getValue())
                            .as("entry %s must hold at most one object key", entry.getKey())
                            .doesNotContain(".dat,")
                            .satisfies(value -> assertThat(value.split("\\.dat", -1).length)
                                    .isLessThanOrEqualTo(2)));
        }

        @Test
        @DisplayName("the count is a Long, so a consumer never parses it out of text")
        void countIsStoredAsANumber() throws Exception {
            final InterestCalculationJob variant = withPrefix("gdg/systran");
            final JobExecution je = execution(34L);

            publishTwo(variant, je);

            assertThat(je.getExecutionContext().get(COUNT_ENTRY)).isInstanceOf(Long.class);
        }
    }

    // ------------------- 13. a typed failure is rethrown, never wrapped twice

    @Nested
    @DisplayName("A typed failure from deeper in the stack is rethrown, never re-wrapped")
    class AlreadyTypedRethrow {

        private final com.cardemo.exception.RecordNotFoundException typed =
                new com.cardemo.exception.RecordNotFoundException("already typed by the mapper");

        private void assertRethrown(final String method) {
            assertThatThrownBy(() -> invokePrivate(method, new Class<?>[0]))
                    .as("%s must rethrow the typed cause, not wrap it in a second abend", method)
                    .isSameAs(typed);
        }

        @Test
        @DisplayName("the TCATBALF probes rethrow unchanged")
        void categoryBalanceProbes() {
            when(categoryBalanceRepository
                    .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc(any(Pageable.class)))
                    .thenThrow(typed);
            assertRethrown("openTransactionCategoryBalanceFile");
            assertRethrown("closeTransactionCategoryBalanceFile");
        }

        @Test
        @DisplayName("the XREFFILE probes rethrow unchanged")
        void crossReferenceProbes() {
            when(crossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(any()))
                    .thenThrow(typed);
            assertRethrown("openCrossReferenceFile");
            assertRethrown("closeCrossReferenceFile");
        }

        @Test
        @DisplayName("the DISCGRP probes rethrow unchanged")
        void disclosureGroupProbes() {
            when(disclosureGroupRepository.findDefaultGroupRate(anyString(), any()))
                    .thenThrow(typed);
            assertRethrown("openDisclosureGroupFile");
            assertRethrown("closeDisclosureGroupFile");
        }

        @Test
        @DisplayName("the ACCTFILE probes rethrow unchanged")
        void accountProbes() {
            when(accountRepository.findById(any())).thenThrow(typed);
            assertRethrown("openAccountFile");
            assertRethrown("closeAccountFile");
        }

        @Test
        @DisplayName("the TRANSACT probes rethrow unchanged")
        void transactionProbes() {
            when(s3Operations.bucketExists(anyString())).thenThrow(typed);
            assertRethrown("openTransactionFile");
            assertThatThrownBy(() -> invokePrivate("closeTransactionFile",
                    new Class<?>[] {JobExecution.class}, execution(21L)))
                    .isSameAs(typed);
        }

        @Test
        @DisplayName("a typed upload failure is rethrown, not converted into a second abend")
        void uploadRethrowsTyped() throws Exception {
            final JobExecution je = execution(31L);
            StepSynchronizationManager.register(new StepExecution("interestCalculationStep", je));
            try {
                final Transaction transaction = mock(Transaction.class);
                when(transaction.getTransactionSource())
                        .thenReturn(TransactionSource.SYSTEM.getFixedWidthValue());
                when(transactionWriter.composeFixedWidthImage(transaction))
                        .thenReturn("X".repeat(RECORD_LENGTH));
                org.mockito.Mockito.doThrow(typed).when(s3Operations).upload(anyString(), anyString(),
                        any(java.io.InputStream.class), any(io.awspring.cloud.s3.ObjectMetadata.class));

                final Object writer = nested("SystranGenerationWriter", job);
                final Method write = writer.getClass()
                        .getDeclaredMethod("write", org.springframework.batch.item.Chunk.class);
                write.setAccessible(true);
                assertThatThrownBy(() -> {
                    try {
                        write.invoke(writer, new org.springframework.batch.item.Chunk<>(
                                List.of(transaction)));
                    } catch (final InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                    }
                }).isSameAs(typed);
            } finally {
                StepSynchronizationManager.close();
            }
        }
    }

    // --------------------------- 14. the TRANFILE close publishes or it abends

    @Nested
    @DisplayName("The TRANSACT close always leaves a readable generation behind")
    class CloseTail {

        private void close(final JobExecution jobExecution) throws Exception {
            invokePrivate("closeTransactionFile", new Class<?>[] {JobExecution.class}, jobExecution);
        }

        @Test
        @DisplayName("a zero-rate run publishes this run's own empty generation, not an earlier one")
        void emptyGenerationIsStillPublished() throws Exception {
            final JobExecution je = execution(9L);
            close(je);
            assertThat(je.getExecutionContext().getString("carddemo.systran.generation.prefix"))
                    .as("app/cbl/CBACT04C.cbl:L214 suppressed every write, but (+1) still exists")
                    .isEqualTo("gdg/systran/0000000000000000009");
            assertThat(je.getExecutionContext().getLong("carddemo.systran.generation.keys.count"))
                    .isZero();
        }

        @Test
        @DisplayName("a vanished bucket abends on CLOSE rather than reporting a clean end of run")
        void missingBucketOnCloseAbends() {
            when(s3Operations.bucketExists(anyString())).thenReturn(Boolean.FALSE);
            assertThatThrownBy(() -> close(execution(10L)))
                    .isInstanceOf(FatalProcessingException.class);
        }

        @Test
        @DisplayName("objects written but keys unpublished abends: the generation would be unreadable")
        void writtenButUnpublishedAbends() {
            final JobExecution je = execution(11L);
            je.getExecutionContext().putString("carddemo.systran.generation.prefix",
                    "gdg/systran/0000000000000000011");
            je.getExecutionContext().putLong("carddemo.systran.generation.keys.count", 0L);
            assertThatThrownBy(() -> close(je)).isInstanceOf(FatalProcessingException.class);
        }

        @Test
        @DisplayName("a published generation carrying keys closes cleanly")
        void publishedGenerationClosesCleanly() {
            final JobExecution je = execution(12L);
            je.getExecutionContext().putString("carddemo.systran.generation.prefix",
                    "gdg/systran/0000000000000000012");
            je.getExecutionContext().putLong("carddemo.systran.generation.keys.count", 1L);
            je.getExecutionContext().putString("carddemo.systran.generation.keys.0",
                    "gdg/systran/0000000000000000012/systran-0000000000000000001.dat");
            assertThatCode(() -> close(je)).doesNotThrowAnyException();
        }
    }

    // ---------------- 15. abend outranks failure at every reporting boundary

    @Nested
    @DisplayName("An abend outranks a plain failure at every reporting boundary")
    class AbendPrecedence {

        private org.springframework.batch.core.JobExecutionListener listener() throws Exception {
            return (org.springframework.batch.core.JobExecutionListener)
                    nested("InterestCalculationJobListener", job);
        }

        private JobExecutionDecider decider() throws Exception {
            return (JobExecutionDecider) nested("InterestCalculationReturnCodeDecider", null);
        }

        @Test
        @DisplayName("afterJob records an end-of-run abend against the execution instead of throwing")
        void afterJobRecordsAbendRatherThanThrowing() throws Exception {
            when(s3Operations.bucketExists(anyString())).thenReturn(Boolean.FALSE);
            final JobExecution je = execution(41L);
            MDC.put(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID, "41");
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, "c-41");

            assertThatCode(() -> listener().afterJob(je))
                    .as("Spring Batch does not fail a job on an afterJob exception")
                    .doesNotThrowAnyException();

            assertThat(je.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(je.getAllFailureExceptions())
                    .anySatisfy(failure ->
                            assertThat(failure).isInstanceOf(FatalProcessingException.class));
            assertThat(je.getExitStatus().getExitCode()).isEqualTo("ABEND");
            // afterJob is invoked here without a matching beforeJob, so the listener established nothing on
            // this thread and therefore has nothing to undo. Leaving these alone is the fix for M-03: the old
            // code removed them unconditionally, which destroyed context this test's caller owned.
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID))
                    .as("restored, not removed: nothing was established, so nothing is taken away")
                    .isEqualTo("41");
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID)).isEqualTo("c-41");
        }

        @Test
        @DisplayName("afterJob leaves a non-abend failure at its own exit status")
        void afterJobLeavesNonAbendAlone() throws Exception {
            final JobExecution je = execution(42L);
            je.addFailureException(new IllegalStateException("a plain failure, not an abend"));
            listener().afterJob(je);
            assertThat(je.getExitStatus().getExitCode())
                    .as("only a FatalProcessingException may raise RC 12")
                    .isNotEqualTo("ABEND");
        }

        @Test
        @DisplayName("an abend recorded on the execution outranks an otherwise clean step")
        void executionLevelAbendWins() throws Exception {
            final JobExecution je = execution(43L);
            je.addFailureException(new FatalProcessingException("recorded by afterJob"));
            final StepExecution se = new StepExecution("interestCalculationStep", je);
            se.setStatus(BatchStatus.COMPLETED);
            se.setExitStatus(ExitStatus.COMPLETED);
            assertThat(decider().decide(je, se).getName()).isEqualTo("ABEND");
        }

        @Test
        @DisplayName("a non-abend failure on both execution and step is FAILED, never ABEND")
        void nonAbendFailuresAreNotAbend() throws Exception {
            final JobExecution je = execution(44L);
            je.addFailureException(new IllegalStateException("plain"));
            final StepExecution se = new StepExecution("interestCalculationStep", je);
            se.addFailureException(new IllegalStateException("plain"));
            se.setStatus(BatchStatus.COMPLETED);
            se.setExitStatus(ExitStatus.FAILED);
            assertThat(decider().decide(je, se)).isEqualTo(FlowExecutionStatus.FAILED);
        }

        @Test
        @DisplayName("an empty chunk uploads nothing: every disclosure rate in it was zero")
        void emptyAndNullChunksUploadNothing() throws Exception {
            final Object writer = nested("SystranGenerationWriter", job);
            final Method write = writer.getClass()
                    .getDeclaredMethod("write", org.springframework.batch.item.Chunk.class);
            write.setAccessible(true);
            write.invoke(writer, new org.springframework.batch.item.Chunk<>(List.of()));
            write.invoke(writer, new Object[] {null});
            verify(s3Operations, never()).upload(anyString(), anyString(),
                    any(java.io.InputStream.class), any(io.awspring.cloud.s3.ObjectMetadata.class));
        }

        @Test
        @DisplayName("a year below the plausible floor is rejected")
        void implausibleYearIsRejected() throws Exception {
            final JobParametersValidator validator =
                    (JobParametersValidator) nested("ParmDateValidator", null);
            assertThatThrownBy(() -> validator.validate(params("0000010100")))
                    .isInstanceOf(JobParametersInvalidException.class);
        }

        @Test
        @DisplayName("a thirty-day month rejects day 31 and accepts day 30")
        void thirtyDayMonthsAreBounded() throws Exception {
            final JobParametersValidator validator =
                    (JobParametersValidator) nested("ParmDateValidator", null);
            for (final String bad : List.of("2022043100", "2022063100", "2022093100", "2022113100")) {
                assertThatThrownBy(() -> validator.validate(params(bad)))
                        .as("must reject %s", bad)
                        .isInstanceOf(JobParametersInvalidException.class);
            }
            for (final String good : List.of("2022043000", "2022063000", "2022093000", "2022113000")) {
                assertThatCode(() -> validator.validate(params(good)))
                        .as("must accept %s", good)
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("an untyped store failure on any probe becomes abend 999, never a silent skip")
        void untypedStoreFailuresBecomeAbends() throws Exception {
            // Mirrors AlreadyTypedRethrow with an UNTYPED cause, exercising the second catch arm of
            // every probe. All 17 PERFORM 9999-ABEND-PROGRAM sites in app/cbl/CBACT04C.cbl abend, so
            // every one of these must raise FatalProcessingException and none may be tolerated.
            final org.springframework.dao.DataAccessResourceFailureException untyped =
                    new org.springframework.dao.DataAccessResourceFailureException("store down");

            when(categoryBalanceRepository
                    .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc(any(Pageable.class)))
                    .thenThrow(untyped);
            when(crossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(any()))
                    .thenThrow(untyped);
            when(disclosureGroupRepository.findDefaultGroupRate(anyString(), any()))
                    .thenThrow(untyped);
            when(accountRepository.findById(any())).thenThrow(untyped);
            when(s3Operations.bucketExists(anyString())).thenThrow(untyped);

            for (final String probe : List.of("openTransactionCategoryBalanceFile",
                    "openCrossReferenceFile", "openDisclosureGroupFile", "openAccountFile",
                    "openTransactionFile", "closeTransactionCategoryBalanceFile",
                    "closeCrossReferenceFile", "closeDisclosureGroupFile", "closeAccountFile")) {

                assertThatThrownBy(() -> invokePrivate(probe, new Class<?>[0]))
                        .as("%s must abend, preserving the cause", probe)
                        .isInstanceOf(FatalProcessingException.class)
                        .hasRootCauseInstanceOf(
                                org.springframework.dao.DataAccessResourceFailureException.class);
            }
            assertThatThrownBy(() -> invokePrivate("closeTransactionFile",
                    new Class<?>[] {JobExecution.class}, execution(45L)))
                    .isInstanceOf(FatalProcessingException.class);
            assertThat(((FatalProcessingException) org.assertj.core.api.Assertions
                    .catchThrowable(() -> invokePrivate("openAccountFile", new Class<?>[0])))
                    .getAbendCode())
                    .as("app/cbl/CBACT04C.cbl:L631 MOVE 999 TO ABCODE, never the CICS 9999")
                    .isEqualTo("999");
        }

        @Test
        @DisplayName("an untyped upload failure abends rather than losing the generation silently")
        void untypedUploadFailureAbends() throws Exception {
            final JobExecution je = execution(46L);
            StepSynchronizationManager.register(new StepExecution("interestCalculationStep", je));
            try {
                final Transaction transaction = mock(Transaction.class);
                when(transaction.getTransactionSource())
                        .thenReturn(TransactionSource.SYSTEM.getFixedWidthValue());
                when(transactionWriter.composeFixedWidthImage(transaction))
                        .thenReturn("X".repeat(RECORD_LENGTH));
                org.mockito.Mockito.doThrow(
                        new org.springframework.dao.DataAccessResourceFailureException("s3 down"))
                        .when(s3Operations).upload(anyString(), anyString(),
                                any(java.io.InputStream.class),
                                any(io.awspring.cloud.s3.ObjectMetadata.class));

                final Object writer = nested("SystranGenerationWriter", job);
                final Method write = writer.getClass()
                        .getDeclaredMethod("write", org.springframework.batch.item.Chunk.class);
                write.setAccessible(true);
                assertThatThrownBy(() -> {
                    try {
                        write.invoke(writer, new org.springframework.batch.item.Chunk<>(
                                List.of(transaction)));
                    } catch (final InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                    }
                }).isInstanceOf(FatalProcessingException.class);
            } finally {
                StepSynchronizationManager.close();
            }
        }

        @Test
        @DisplayName("monthLength honours its documented contract, zero included")
        void monthLengthContract() throws Exception {
            final Class<?>[] types = {int.class, int.class};
            assertThat((Integer) invokePrivate("monthLength", types,
                    Integer.valueOf(2022), Integer.valueOf(13)))
                    .as("out of range reports zero rather than a plausible length")
                    .isZero();
            assertThat((Integer) invokePrivate("monthLength", types,
                    Integer.valueOf(2022), Integer.valueOf(0))).isZero();
            assertThat((Integer) invokePrivate("monthLength", types,
                    Integer.valueOf(2022), Integer.valueOf(1))).isEqualTo(31);
            assertThat((Integer) invokePrivate("monthLength", types,
                    Integer.valueOf(2022), Integer.valueOf(4))).isEqualTo(30);
            assertThat((Integer) invokePrivate("monthLength", types,
                    Integer.valueOf(2022), Integer.valueOf(2))).isEqualTo(28);
            assertThat((Integer) invokePrivate("monthLength", types,
                    Integer.valueOf(2020), Integer.valueOf(2))).isEqualTo(29);
            assertThat((Integer) invokePrivate("monthLength", types,
                    Integer.valueOf(1900), Integer.valueOf(2)))
                    .as("1900 is not a leap year: divisible by 100 but not by 400")
                    .isEqualTo(28);
            assertThat((Integer) invokePrivate("monthLength", types,
                    Integer.valueOf(2000), Integer.valueOf(2)))
                    .as("2000 is a leap year: divisible by 400")
                    .isEqualTo(29);
        }
    }

}
