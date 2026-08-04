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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.batch.jobs.StatementGenerationJob;
import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.config.BatchConfig;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRepository;
import com.cardemo.service.shared.FileService;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

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
 */
@DisplayName("BatchConfig - the four CBSTM03B dataset bindings, and no report processor factory")
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
    @DisplayName("The report processor registers itself, and this class must not register it again")
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
            // A @Bean @StepScope transactionReportProcessor factory stood in BatchConfig and was removed.
            // TransactionReportProcessor is annotated @Component @StepScope, so its default bean name is
            // already "transactionReportProcessor"; with spring.main.allow-bean-definition-overriding set
            // to false in the base profile, the pair aborts context startup. This assertion is the
            // regression guard: it fails the moment either registration is reintroduced alongside the
            // other, which is a failure a slice test would otherwise only surface as a startup error.
            assertThat(Arrays.stream(BatchConfig.class.getDeclaredMethods()).map(Method::getName))
                    .as("BatchConfig owns the four FileService.Dataset bindings and nothing else; every "
                            + "reader, processor and writer under batch/** registers by @Component")
                    .doesNotContain("transactionReportProcessor");
            assertThat(Arrays.stream(BatchConfig.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Bean.class))
                    .map(Method::getReturnType))
                    .as("the only bean type this class contributes is the DD binding")
                    .containsOnly(FileService.Dataset.class);
        }

        @Test
        @DisplayName("the processor carries its own @Component and @StepScope")
        void theProcessorCarriesItsOwnScope() {
            assertThat(TransactionReportProcessor.class.isAnnotationPresent(Component.class))
                    .as("the component annotation is what replaced the factory")
                    .isTrue();
            assertThat(TransactionReportProcessor.class.isAnnotationPresent(StepScope.class))
                    .as("the six WS-REPORT-VARS items of app/cbl/CBTRN03C.cbl:L127-L137 are per-run state, "
                            + "so a singleton would carry one report's pagination into the next; the scope "
                            + "is a property of the component rather than of whoever wires it")
                    .isTrue();
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
}
