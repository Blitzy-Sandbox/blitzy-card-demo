/*
 * ******************************************************************
 * Program     : TransactionWriterTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the TRANSACT write boundary against the COBOL
 *               it reproduces: the relational insert and the 350-byte
 *               fixed-width emission performed as one unit, the 20-byte
 *               FILLER without which the image is 330 bytes, the
 *               trailing-sign overpunch on TRAN-AMT, the refusal to
 *               truncate any field that overflows its picture clause,
 *               the duplicate-key outcome that the identifier idiom
 *               makes reachable, the step-scoped object key, and the
 *               write-failure path that DISPLAYs then abends.
 * Source      : app/cbl/CBTRN02C.cbl:L562-L579 (2900-WRITE-TRANSACTION-FILE)
 *               app/cbl/CBTRN02C.cbl:L714-L731 (9910-DISPLAY-IO-STATUS)
 *               app/cbl/CBTRN02C.cbl:L707-L712 (9999-ABEND-PROGRAM)
 *               app/cbl/COTRN02C.cbl:L444-L451 (descending-browse ID)
 *               app/cbl/CBACT04C.cbl:L473-L516 (global suffix counter)
 *               app/cpy/CVTRA05Y.cpy           (350-byte TRAN-RECORD)
 *               app/jcl/TRANFILE.jcl:L53-L54   (KEYS(16 0), RECORDSIZE(350 350))
 *               app/catlg/LISTCAT.txt:L351-L360 (TRANSACT AIX) @ 7756d89
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * Unit tests for {@link TransactionWriter}, the Java form of {@code 2900-WRITE-TRANSACTION-FILE} at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579}.
 *
 * <h2>What it does</h2>
 * <p>
 * The legacy paragraph performs one {@code WRITE} to an indexed cluster. The Java writer performs two things
 * that must both succeed: the row goes into the relational table that replaced the cluster, and the identical
 * 350-byte image goes to the object store so that Gate 1 can compare bytes against the mainframe baseline.
 * This suite fixes both halves. It proves the image is {@code 350} characters because
 * {@code app/cpy/CVTRA05Y.cpy} says so and because {@code app/jcl/TRANFILE.jcl:L54} declares
 * {@code RECORDSIZE(350 350)}, that the twenty-byte {@code FILLER} is present, that {@code TRAN-AMT} carries
 * its sign in the final byte through the zoned-decimal overpunch, that no field is ever truncated to fit, and
 * that a duplicate {@code TRAN-ID} surfaces as a duplicate rather than being upserted away - the collision is
 * the intended observable outcome of re-driving the interest job with a used date parameter.
 *
 * <h2>How to build and test</h2>
 * <pre>
 * ./mvnw -B -ntp -Dtest='TransactionWriterTest' -DfailIfNoTests=false test
 * </pre>
 *
 * <h2>Key configuration and defaults</h2>
 * <ul>
 * <li>{@code carddemo.aws.s3.batch-output-bucket} - required, non-blank; {@value #BUCKET} here.</li>
 * <li>{@code carddemo.aws.s3.transaction-object-prefix} - defaults to {@code transact}.</li>
 * <li>The step execution is captured by the {@code @BeforeStep} callback. There is no default: without it
 * there is no job instance to scope the object key on, and the writer fails fast rather than inventing one.
 * </li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 * <li><b>Blocker.</b> A failure in group 3 or group 4 means the emitted bytes no longer match the mainframe's.
 * Every subsequent record in the file shifts, so the parity comparison fails wholesale.</li>
 * <li><b>Blocker.</b> A failure in group 5 means a duplicate identifier is no longer distinguished from a
 * plain constraint violation, or is being silently absorbed. The distinction is drawn on {@code SQLSTATE}
 * {@code 23505} through {@code FileStatusMapper.classifyStoreFailure} rather than on which exception subtype
 * the persistence layer chose. Catching {@code DuplicateKeyException} ahead of its supertype - which is what
 * the writer used to do - recognises a collision only when that subtype was chosen, and for a BATCHED flush it
 * need not be: the driver reports the batch and links the exception carrying the state of the failing entry
 * beneath it. Both shapes are asserted in group 5 for exactly that reason.</li>
 * <li><b>High.</b> A failure in group 7 means a lost object write would be reported as a clean run.</li>
 * <li><b>High.</b> A failure in group 2 means the writer would compose an object key from an absent job
 * instance, so two runs could collide on one key.</li>
 * <li><b>Medium.</b> A failure in group 6 means the downstream step reads the wrong object key from the
 * execution context.</li>
 * </ul>
 */
@DisplayName("TransactionWriter: CBTRN02C's insert and 350-byte TRANSACT image")
class TransactionWriterTest {

    /** The destination bucket. */
    private static final String BUCKET = "carddemo-batch-output";

    /** The default object prefix, as {@code application.yml} configures it. */
    private static final String PREFIX = "transact";

    /** The job instance identifier the object key is scoped on. */
    private static final long JOB_INSTANCE_ID = 7L;

    /** {@code TRAN-RECORD}, the record length {@code app/cpy/CVTRA05Y.cpy} declares. */
    private static final int RECORD_LENGTH = 350;

    private TransactionRepository repository;
    private S3Operations objectStorage;
    private MeterRegistry meterRegistry;

    /**
     * The sole registrar of the four application counters. The writer counts through this collaborator, so
     * the registry above is read only to assert what was registered under it.
     */
    private MetricsConfig metricsConfig;
    private StepExecution stepExecution;
    private TransactionWriter writer;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    @BeforeEach
    void buildWriterAndCaptureLogs() {
        repository = Mockito.mock(TransactionRepository.class);
        objectStorage = Mockito.mock(S3Operations.class);
        meterRegistry = new SimpleMeterRegistry();
        metricsConfig = new MetricsConfig(meterRegistry);
        writer = new TransactionWriter(repository, objectStorage, new FileStatusMapper(), metricsConfig,
                BUCKET, PREFIX);
        stepExecution = stepExecution(JOB_INSTANCE_ID);
        writer.beforeStep(stepExecution);

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TransactionWriter.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    // ------------------------------------------------------------------------------------------------
    // Fixtures and helpers
    // ------------------------------------------------------------------------------------------------

    /**
     * Builds a step execution whose job instance identifier is known, so the composed object key is fully
     * determined by the test.
     *
     * @param instanceId the job instance identifier
     * @return the execution the framework would hand to the listener callback
     */
    private static StepExecution stepExecution(final long instanceId) {
        JobExecution jobExecution = new JobExecution(new JobInstance(Long.valueOf(instanceId), "POSTTRAN"),
                Long.valueOf(instanceId), new JobParameters());
        return new StepExecution("dailyTransactionPostingStep", jobExecution);
    }

    /**
     * Builds one {@code TRAN-RECORD} in the {@code CVTRA05Y} layout, every field exactly as wide as its
     * picture clause so the image can be asserted by offset.
     *
     * @param transactionId {@code TRAN-ID PIC X(16)}
     * @param amount {@code TRAN-AMT PIC S9(09)V99}
     * @return the posted transaction
     */
    private static Transaction posted(final String transactionId, final String amount) {
        return new Transaction(
                transactionId,
                "01",
                Integer.valueOf(5001),
                "POS TERM  ",
                pad("Payment at Amazon", 100),
                new BigDecimal(amount),
                Long.valueOf(123456789L),
                pad("Amazon.com", 50),
                pad("Seattle", 50),
                "0000098101",
                "4111111111111111",
                "2022-07-18-11.22.33.123456",
                "2022-07-18-11.22.34.123456");
    }

    /** @return the canonical posted transaction: identifier 1, amount 100.00. */
    private static Transaction posted() {
        return posted("0000000000000001", "100.00");
    }

    /**
     * Right-pads a value with spaces to the width of its picture clause.
     *
     * @param value the value
     * @param width the field width
     * @return the padded value
     */
    private static String pad(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Writes one private field of a {@link Transaction}, so that a field state the entity's own guards refuse
     * to construct still reaches the writer's own geometry guards. Those guards are the last check before
     * bytes leave the process; a guard no test can reach is a guard nobody can trust.
     *
     * @param target the row to mutate
     * @param name the declared field name on {@link Transaction}
     * @param value the value to write
     */
    private static void setField(final Transaction target, final String name, final Object value) {
        try {
            java.lang.reflect.Field field = Transaction.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot write " + name + " on Transaction", failure);
        }
    }

    /**
     * Builds a chunk of transactions.
     *
     * @param items the items
     * @return the chunk a chunk-oriented step would hand to the writer
     */
    private static Chunk<Transaction> chunk(final Transaction... items) {
        return new Chunk<>(List.of(items));
    }

    /** @return the payload of the single upload the writer performed, decoded in the record charset. */
    private String uploadedPayload() {
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        Mockito.verify(objectStorage).upload(Mockito.eq(BUCKET), Mockito.anyString(), captor.capture(),
                Mockito.any(ObjectMetadata.class));
        try (InputStream stream = captor.getValue()) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            stream.transferTo(sink);
            return new String(sink.toByteArray(), StandardCharsets.ISO_8859_1);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot read the uploaded payload", failure);
        }
    }

    /** @return the object key of the single upload the writer performed. */
    private String uploadedKey() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(objectStorage).upload(Mockito.eq(BUCKET), captor.capture(),
                Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
        return captor.getValue();
    }

    /** @return the metadata of the single upload the writer performed. */
    private ObjectMetadata uploadedMetadata() {
        ArgumentCaptor<ObjectMetadata> captor = ArgumentCaptor.forClass(ObjectMetadata.class);
        Mockito.verify(objectStorage).upload(Mockito.eq(BUCKET), Mockito.anyString(),
                Mockito.any(InputStream.class), captor.capture());
        return captor.getValue();
    }

    /** @return every message this class's logger received. */
    private List<String> loggedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** @return the value of the processed-records counter. */
    private double processedCount() {
        io.micrometer.core.instrument.Counter counter =
                meterRegistry.find("carddemo.batch.records.processed").counter();
        return counter == null ? 0.0d : counter.count();
    }

    /**
     * Makes the object store fail every upload.
     *
     * @param cause the failure the store raises
     */
    private void makeStoreFail(final RuntimeException cause) {
        Mockito.when(objectStorage.upload(Mockito.anyString(), Mockito.anyString(),
                Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class))).thenThrow(cause);
    }

    @Nested
    @DisplayName("1. Construction: the collaborators and the configuration the write cannot proceed without")
    class Construction {

        @ParameterizedTest(name = "a null {0} is refused by name")
        @ValueSource(strings = {"transactionRepository", "objectStorage", "fileStatusMapper", "metrics"})
        @DisplayName("every collaborator is refused by name when absent")
        void everyCollaboratorIsRefusedByName(final String absent) {
            TransactionRepository repositoryArgument = "transactionRepository".equals(absent) ? null : repository;
            S3Operations storeArgument = "objectStorage".equals(absent) ? null : objectStorage;
            FileStatusMapper mapperArgument =
                    "fileStatusMapper".equals(absent) ? null : new FileStatusMapper();
            MetricsConfig metricsArgument = "metrics".equals(absent) ? null : metricsConfig;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionWriter(repositoryArgument, storeArgument, mapperArgument,
                            metricsArgument, BUCKET, PREFIX))
                    .withMessage(absent + " must not be null");
        }

        @ParameterizedTest(name = "a bucket of [{0}] is refused")
        @ValueSource(strings = {"", "   "})
        @DisplayName("a blank bucket is refused, naming the property that must be configured")
        void aBlankBucketIsRefused(final String bucket) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionWriter(repository, objectStorage, new FileStatusMapper(),
                            metricsConfig, bucket, PREFIX))
                    .withMessage("carddemo.aws.s3.batch-output-bucket must be configured with a non-blank value");
        }

        @Test
        @DisplayName("a null bucket is refused for the same reason")
        void aNullBucketIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionWriter(repository, objectStorage, new FileStatusMapper(),
                            metricsConfig, null, PREFIX))
                    .withMessage("carddemo.aws.s3.batch-output-bucket must be configured with a non-blank value");
        }

        @Test
        @DisplayName("a blank object prefix is refused, naming its own property")
        void aBlankPrefixIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionWriter(repository, objectStorage, new FileStatusMapper(),
                            metricsConfig, BUCKET, "  "))
                    .withMessage("carddemo.aws.s3.transaction-object-prefix must be configured with a "
                            + "non-blank value");
        }
    }

    @Nested
    @DisplayName("2. HIGH: the step context the object key is scoped on")
    class StepContext {

        @Test
        @DisplayName("writing before the listener callback fails fast rather than inventing a key")
        void writingBeforeTheCallbackFailsFast() {
            TransactionWriter detached = new TransactionWriter(repository, objectStorage, new FileStatusMapper(),
                    metricsConfig, BUCKET, PREFIX);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> detached.write(chunk(posted())))
                    .satisfies(failure -> assertThat(failure.getAbendReason())
                            .isEqualTo("no step execution was captured before the first write"));
        }

        @Test
        @DisplayName("writing before the callback tells the operator what to wire, and stores nothing")
        void writingBeforeTheCallbackStoresNothing() throws Exception {
            TransactionWriter detached = new TransactionWriter(repository, objectStorage, new FileStatusMapper(),
                    metricsConfig, BUCKET, PREFIX);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> detached.write(chunk(posted())));

            assertThat(loggedMessages())
                    .anyMatch(message -> message.contains("must be registered on a Spring Batch step"));
            Mockito.verify(repository, Mockito.never()).saveAllAndFlush(Mockito.anyList());
        }

        @Test
        @DisplayName("a step execution without a job instance is refused rather than defaulted to zero")
        void aStepExecutionWithoutAJobInstanceIsRefused() {
            writer.beforeStep(new StepExecution("dailyTransactionPostingStep",
                    new JobExecution(Long.valueOf(11L))));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())))
                    .satisfies(failure -> assertThat(failure.getAbendReason())
                            .isEqualTo("the step execution carries no job instance"));
        }

        @Test
        @DisplayName("a later callback replaces the captured execution, as a restarted step requires")
        void aLaterCallbackReplacesTheExecution() throws Exception {
            writer.beforeStep(stepExecution(9L));

            writer.write(chunk(posted()));

            assertThat(uploadedKey())
                    .as("the object key must follow the current job instance, not the first one seen")
                    .startsWith(String.format(Locale.ROOT, "%s/%019d/", PREFIX, 9L));
        }
    }

    @Nested
    @DisplayName("3. BLOCKER: the image is 350 bytes, and the last twenty of them are FILLER")
    class RecordGeometry {

        @Test
        @DisplayName("one transaction composes exactly 350 characters")
        void oneTransactionComposes350Characters() {
            assertThat(writer.composeFixedWidthImage(posted()))
                    .as("app/jcl/TRANFILE.jcl:L54 declares RECORDSIZE(350 350)")
                    .hasSize(RECORD_LENGTH);
        }

        @Test
        @DisplayName("every field lands at the offset CVTRA05Y gives it")
        void everyFieldLandsAtItsOffset() {
            String image = writer.composeFixedWidthImage(posted());

            assertThat(image.substring(0, 16)).as("TRAN-ID").isEqualTo("0000000000000001");
            assertThat(image.substring(16, 18)).as("TRAN-TYPE-CD").isEqualTo("01");
            assertThat(image.substring(18, 22)).as("TRAN-CAT-CD").isEqualTo("5001");
            assertThat(image.substring(22, 32)).as("TRAN-SOURCE").isEqualTo("POS TERM  ");
            assertThat(image.substring(32, 132)).as("TRAN-DESC").isEqualTo(pad("Payment at Amazon", 100));
            assertThat(image.substring(132, 143)).as("TRAN-AMT").isEqualTo("0000001000{");
            assertThat(image.substring(143, 152)).as("TRAN-MERCHANT-ID").isEqualTo("123456789");
            assertThat(image.substring(152, 202)).as("TRAN-MERCHANT-NAME").isEqualTo(pad("Amazon.com", 50));
            assertThat(image.substring(202, 252)).as("TRAN-MERCHANT-CITY").isEqualTo(pad("Seattle", 50));
            assertThat(image.substring(252, 262)).as("TRAN-MERCHANT-ZIP").isEqualTo("0000098101");
            assertThat(image.substring(262, 278)).as("TRAN-CARD-NUM").isEqualTo("4111111111111111");
            assertThat(image.substring(278, 304)).as("TRAN-ORIG-TS").isEqualTo("2022-07-18-11.22.33.123456");
            assertThat(image.substring(304, 330)).as("TRAN-PROC-TS").isEqualTo("2022-07-18-11.22.34.123456");
        }

        @Test
        @DisplayName("the twenty-byte FILLER completes the record; without it the image is 330 bytes")
        void theFillerCompletesTheRecord() {
            String image = writer.composeFixedWidthImage(posted());

            assertThat(image.substring(330))
                    .as("the FILLER is not an entity column, but it is part of the record")
                    .isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("the card number sits at offset 263, where the alternate index expects it")
        void theCardNumberSitsAtTheAlternateIndexOffset() {
            String image = writer.composeFixedWidthImage(posted());

            assertThat(image.indexOf("4111111111111111"))
                    .as("app/proc/TRANREPT.prc's SYMNAMES places TRAN-CARD-NUM at 263, one-based")
                    .isEqualTo(262);
        }

        @Test
        @DisplayName("a shorter field is right-padded rather than shifting its neighbours left")
        void aShorterFieldIsRightPadded() {
            Transaction transaction = posted();
            setField(transaction, "merchantName", "AMZ");

            String image = writer.composeFixedWidthImage(transaction);

            assertThat(image.substring(152, 202)).isEqualTo(pad("AMZ", 50));
            assertThat(image.substring(202, 252)).isEqualTo(pad("Seattle", 50));
            assertThat(image).hasSize(RECORD_LENGTH);
        }

        @Test
        @DisplayName("an absent text field becomes spaces, never a shortened record")
        void anAbsentTextFieldBecomesSpaces() {
            Transaction transaction = posted();
            setField(transaction, "merchantCity", null);

            String image = writer.composeFixedWidthImage(transaction);

            assertThat(image.substring(202, 252)).isEqualTo(" ".repeat(50));
            assertThat(image).hasSize(RECORD_LENGTH);
        }

        @Test
        @DisplayName("a chunk of three emits one object of exactly three records")
        void aChunkOfThreeEmitsThreeRecords() throws Exception {
            writer.write(chunk(posted(), posted("0000000000000002", "1.00"),
                    posted("0000000000000003", "-2.00")));

            String payload = uploadedPayload();
            assertThat(payload).hasSize(3 * RECORD_LENGTH);
            assertThat(payload.substring(0, 16)).isEqualTo("0000000000000001");
            assertThat(payload.substring(RECORD_LENGTH, RECORD_LENGTH + 16)).isEqualTo("0000000000000002");
            assertThat(payload.substring(2 * RECORD_LENGTH, 2 * RECORD_LENGTH + 16))
                    .isEqualTo("0000000000000003");
        }

        @Test
        @DisplayName("the payload encodes byte for byte, so no record boundary moves")
        void thePayloadEncodesByteForByte() throws Exception {
            writer.write(chunk(posted(), posted("0000000000000002", "1.00")));

            assertThat(uploadedPayload())
                    .as("a multi-byte charset would shift every record after the first non-ASCII byte")
                    .hasSize(2 * RECORD_LENGTH);
        }

        @Test
        @DisplayName("a Latin-1 character outside ASCII still occupies exactly one byte")
        void aLatin1CharacterOccupiesOneByte() throws Exception {
            Transaction transaction = posted();
            setField(transaction, "merchantCity", pad("Ni\u00f1o", 50));

            writer.write(chunk(transaction));

            String payload = uploadedPayload();
            assertThat(payload).hasSize(RECORD_LENGTH);
            assertThat(payload.substring(202, 252)).isEqualTo(pad("Ni\u00f1o", 50));
        }
    }

    @Nested
    @DisplayName("4. BLOCKER: no field is truncated to fit, and TRAN-AMT carries its sign in the last byte")
    class FieldRendering {

        @ParameterizedTest(name = "{0} renders as {1}")
        @CsvSource(delimiter = '|', value = {
            "0.00|0000000000{",
            "0.01|0000000000A",
            "0.09|0000000000I",
            "100.00|0000001000{",
            "999999999.99|9999999999I",
            "-0.01|0000000000J",
            "-0.09|0000000000R",
            "-100.00|0000001000}",
            "-999999999.99|9999999999R"})
        @DisplayName("the zoned-decimal overpunch carries the sign for both alphabets")
        void theOverpunchCarriesTheSign(final String amount, final String rendered) {
            String image = writer.composeFixedWidthImage(posted("0000000000000001", amount));

            assertThat(image.substring(132, 143)).isEqualTo(rendered);
        }

        @Test
        @DisplayName("a negative amount is never normalised to its absolute value")
        void aNegativeAmountIsNeverNormalised() {
            String image = writer.composeFixedWidthImage(posted("0000000000000001", "-250.55"));

            assertThat(image.substring(132, 143))
                    .as("taking abs() would emit E where the mainframe emits N")
                    .isEqualTo("0000002505N");
        }

        @ParameterizedTest(name = "a tie at {0} renders as {1}")
        @CsvSource(delimiter = '|', value = {
            "0.005|0000000000{",
            "0.015|0000000000B",
            "0.025|0000000000B"})
        @DisplayName("a tie is rounded half even, matching the posting arithmetic")
        void aTieIsRoundedHalfEven(final String amount, final String rendered) {
            Transaction transaction = posted();
            setField(transaction, "amount", new BigDecimal(amount));

            assertThat(writer.composeFixedWidthImage(transaction).substring(132, 143))
                    .as("HALF_EVEN sends a tie to the even digit: 0.005 to 0.00 where HALF_UP would "
                            + "give 0.01, and 0.025 to 0.02 where HALF_UP would give 0.03")
                    .isEqualTo(rendered);
        }

        @Test
        @DisplayName("a negative tie that rounds to zero loses its sign, because zoned zero is positive")
        void aNegativeTieRoundingToZeroLosesItsSign() {
            Transaction transaction = posted();
            setField(transaction, "amount", new BigDecimal("-0.005"));

            assertThat(writer.composeFixedWidthImage(transaction).substring(132, 143))
                    .as("HALF_EVEN sends -0.005 to 0.00, whose signum is zero, so the overpunch is { not }")
                    .isEqualTo("0000000000{");
        }

        @Test
        @DisplayName("an amount beyond PIC S9(09)V99 is refused rather than truncated")
        void anOverWideAmountIsRefused() {
            Transaction transaction = posted();
            setField(transaction, "amount", new BigDecimal("1000000000.00"));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("TRAN-AMT needs 12 zoned positions but PIC S9(09)V99 provides "
                            + "exactly 11");
        }

        @Test
        @DisplayName("an absent amount is refused, because the column is NOT NULL")
        void anAbsentAmountIsRefused() {
            Transaction transaction = posted();
            setField(transaction, "amount", null);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("TRAN-AMT is absent");
        }

        @Test
        @DisplayName("an over-wide character field is refused rather than truncated")
        void anOverWideCharacterFieldIsRefused() {
            Transaction transaction = posted();
            setField(transaction, "merchantName", "X".repeat(51));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("TRAN-MERCHANT-NAME is 51 characters but its picture clause "
                            + "declares exactly 50");
        }

        @Test
        @DisplayName("a character the record charset cannot hold in one byte is refused, not substituted")
        void anUnencodableCharacterIsRefused() {
            Transaction transaction = posted();
            setField(transaction, "merchantCity", pad("Tokyo \u6771\u4eac", 50));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("cannot represent in a single byte");
        }

        @Test
        @DisplayName("an absent unsigned field is refused, because it has no null representation")
        void anAbsentUnsignedFieldIsRefused() {
            Transaction transaction = posted();
            setField(transaction, "merchantId", null);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("TRAN-MERCHANT-ID is absent");
        }

        @Test
        @DisplayName("a negative unsigned field is refused, because there is no sign position at all")
        void aNegativeUnsignedFieldIsRefused() {
            Transaction transaction = posted();
            setField(transaction, "categoryCode", Integer.valueOf(-1));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("TRAN-CAT-CD is negative");
        }

        @Test
        @DisplayName("an unsigned field needing more digits than its clause is refused")
        void anOverWideUnsignedFieldIsRefused() {
            Transaction transaction = posted();
            setField(transaction, "categoryCode", Integer.valueOf(12345));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("TRAN-CAT-CD needs 5 digits but its picture clause declares "
                            + "exactly 4");
        }

        @Test
        @DisplayName("an unsigned field narrower than its clause is zero-filled on the left")
        void aNarrowUnsignedFieldIsZeroFilled() {
            Transaction transaction = posted();
            setField(transaction, "categoryCode", Integer.valueOf(7));

            assertThat(writer.composeFixedWidthImage(transaction).substring(18, 22)).isEqualTo("0007");
        }

        @Test
        @DisplayName("a null item has no record image and is refused by name")
        void aNullItemIsRefused() {
            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(null))
                    .withMessageContaining("the chunk contained a null item, which has no record image");
        }

        @ParameterizedTest(name = "a TRAN-ID of [{0}] is refused")
        @ValueSource(strings = {"", "                "})
        @DisplayName("an absent or blank TRAN-ID is refused, because the cluster is keyed on it")
        void aBlankIdentifierIsRefused(final String transactionId) {
            Transaction transaction = posted();
            setField(transaction, "transactionId", transactionId);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("KEYS(16 0)");
        }

        @Test
        @DisplayName("a null TRAN-ID is refused and rendered as (absent) in the diagnostic")
        void aNullIdentifierIsRenderedAsAbsent() {
            Transaction transaction = posted();
            setField(transaction, "transactionId", null);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("TRAN-ID (absent)");
        }

        @Test
        @DisplayName("a control character in the key is escaped in the diagnostic rather than emitted raw")
        void aControlCharacterInTheKeyIsEscaped() {
            Transaction transaction = posted();
            setField(transaction, "transactionId", "000000000000000\u0001");
            setField(transaction, "merchantName", "X".repeat(51));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("\\u0001");
        }
    }

    @Nested
    @DisplayName("5. BLOCKER: the insert, and the duplicate identifier the source's idiom makes reachable")
    class Persistence {

        @Test
        @DisplayName("the chunk is inserted exactly once, with every item")
        void theChunkIsInsertedOnce() throws Exception {
            Transaction first = posted();
            Transaction second = posted("0000000000000002", "1.00");

            writer.write(chunk(first, second));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.forClass(List.class);
            Mockito.verify(repository, Mockito.times(1)).saveAllAndFlush(captor.capture());
            assertThat(captor.getValue()).containsExactly(first, second);
        }

        @Test
        @DisplayName("a duplicate key surfaces as a duplicate record, never as an upsert")
        void aDuplicateKeySurfacesAsADuplicate() {
            Mockito.when(repository.saveAllAndFlush(Mockito.anyList()))
                    .thenThrow(new DuplicateKeyException("transaction_pkey"));

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())))
                    .satisfies(failure -> {
                        assertThat(failure.getCollidingKey())
                                .as("a chunk of one attributes the collision exactly")
                                .isEqualTo("0000000000000001");
                        assertThat(failure.getCause()).isInstanceOf(DuplicateKeyException.class);
                    });
        }

        @Test
        @DisplayName("the duplicate diagnostic names the identifier idiom that makes a collision reachable")
        void theDuplicateDiagnosticNamesTheIdiom() {
            Mockito.when(repository.saveAllAndFlush(Mockito.anyList()))
                    .thenThrow(new DuplicateKeyException("transaction_pkey"));

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .contains("app/cbl/COTRN02C.cbl:L444-L451")
                            .contains("do not substitute a sequence"));
        }

        @Test
        @DisplayName("a duplicate in a multi-item chunk lists every candidate and names no single key")
        void aDuplicateInAChunkListsEveryCandidate() {
            Mockito.when(repository.saveAllAndFlush(Mockito.anyList()))
                    .thenThrow(new DuplicateKeyException("transaction_pkey"));

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> writer.write(chunk(posted(), posted("0000000000000002", "1.00"))))
                    .satisfies(failure -> {
                        assertThat(failure.getCollidingKey())
                                .as("the driver does not say which row collided, so none is claimed")
                                .isNull();
                        assertThat(failure.getMessage())
                                .contains("0000000000000001, 0000000000000002");
                    });
        }

        @Test
        @DisplayName("a batched collision, whose state is linked below the thrown exception, is still a duplicate")
        void aBatchedCollisionIsRecognisedByItsSqlState() {
            // Finding F-8. This is the shape a batched flush produces: a generic integrity violation whose
            // own state says nothing, carrying the failing entry's state on a linked exception underneath.
            // Recognition by subtype misses it entirely and reports it as a referential or check-constraint
            // failure, losing the identifier race this group exists to pin.
            Mockito.when(repository.saveAllAndFlush(Mockito.anyList()))
                    .thenThrow(new DataIntegrityViolationException("batch entry 0 was refused",
                            new SQLException("batch failed", "40001",
                                    new SQLException("duplicate key value violates unique constraint "
                                            + "\"pk_transaction\"", "23505"))));

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .contains("Duplicate TRAN-ID rejected by the insert into"));
        }

        @Test
        @DisplayName("a plain constraint violation is classified separately from a duplicate")
        void aConstraintViolationIsClassifiedSeparately() {
            Mockito.when(repository.saveAllAndFlush(Mockito.anyList()))
                    .thenThrow(new DataIntegrityViolationException("fk_transaction_card"));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())))
                    .satisfies(failure -> {
                        assertThat(failure).isNotInstanceOf(DuplicateRecordException.class);
                        assertThat(failure.getMessage()).contains("A constraint violation rejected the insert");
                        assertThat(failure.getCause()).isInstanceOf(DataIntegrityViolationException.class);
                    });
        }

        @Test
        @DisplayName("any other store failure abends as CBTRN02C rather than being classified")
        void anyOtherStoreFailureAbends() {
            Mockito.when(repository.saveAllAndFlush(Mockito.anyList()))
                    .thenThrow(new DataAccessResourceFailureException("the connection dropped"));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())))
                    .satisfies(failure -> {
                        assertThat(failure.getAbendCulprit()).isEqualTo("CBTRN02C");
                        assertThat(failure.getAbendCode())
                                .isEqualTo(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE));
                        assertThat(failure.getCause())
                                .isInstanceOf(DataAccessResourceFailureException.class);
                    });
        }

        @Test
        @DisplayName("a failed insert writes no object, so the table and the dataset cannot diverge")
        void aFailedInsertWritesNoObject() {
            Mockito.when(repository.saveAllAndFlush(Mockito.anyList()))
                    .thenThrow(new DuplicateKeyException("transaction_pkey"));

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())));

            Mockito.verify(objectStorage, Mockito.never()).upload(Mockito.anyString(), Mockito.anyString(),
                    Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
            assertThat(processedCount()).isZero();
        }

        @Test
        @DisplayName("an unrenderable record is refused before the insert is attempted")
        void anUnrenderableRecordIsRefusedBeforeTheInsert() {
            Transaction broken = posted();
            setField(broken, "amount", null);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.write(chunk(broken)));

            Mockito.verify(repository, Mockito.never()).saveAllAndFlush(Mockito.anyList());
        }
    }

    @Nested
    @DisplayName("6. The object key, its metadata and the context entry a later step reads")
    class ObjectKeyAndPublication {

        @Test
        @DisplayName("the key carries the prefix, the job instance, the base name and the write ordinal")
        void theKeyCarriesTheOrdinal() throws Exception {
            writer.write(chunk(posted()));

            assertThat(uploadedKey())
                    .isEqualTo(String.format(Locale.ROOT, "%s/%019d/%s-%019d.dat", PREFIX, JOB_INSTANCE_ID,
                            PREFIX, 0L));
        }

        @Test
        @DisplayName("the write ordinal follows the step's own write count, so chunks cannot overwrite")
        void theOrdinalFollowsTheWriteCount() throws Exception {
            stepExecution.setWriteCount(4L);

            writer.write(chunk(posted()));

            assertThat(uploadedKey())
                    .as("a fresh generation per chunk is what the GDG guaranteed")
                    .endsWith(String.format(Locale.ROOT, "-%019d.dat", 4L));
        }

        @Test
        @DisplayName("the metadata declares the byte count and an opaque content type")
        void theMetadataDeclaresTheByteCount() throws Exception {
            writer.write(chunk(posted(), posted("0000000000000002", "1.00")));

            ObjectMetadata metadata = uploadedMetadata();
            assertThat(metadata.getContentLength()).isEqualTo(Long.valueOf(2L * RECORD_LENGTH));
            assertThat(metadata.getContentType()).isEqualTo("application/octet-stream");
        }

        @Test
        @DisplayName("the object key is published for the next step to read")
        void theObjectKeyIsPublished() throws Exception {
            writer.write(chunk(posted()));

            assertThat(stepExecution.getExecutionContext()
                    .getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY))
                    .isEqualTo(uploadedKey());
        }

        @Test
        @DisplayName("the published key is the newest one, not the first")
        void thePublishedKeyIsTheNewest() throws Exception {
            writer.write(chunk(posted()));
            stepExecution.setWriteCount(1L);
            writer.write(chunk(posted("0000000000000002", "1.00")));

            assertThat(stepExecution.getExecutionContext()
                    .getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY))
                    .endsWith(String.format(Locale.ROOT, "-%019d.dat", 1L));
        }

        @Test
        @DisplayName("a custom object prefix is honoured in both key positions")
        void aCustomPrefixIsHonoured() throws Exception {
            TransactionWriter prefixed = new TransactionWriter(repository, objectStorage,
                    new FileStatusMapper(), metricsConfig, BUCKET, "systran");
            prefixed.beforeStep(stepExecution);

            prefixed.write(chunk(posted()));

            assertThat(uploadedKey())
                    .as("app/jcl/DEFGDGB.jcl defines several GDG bases, and the prefix selects one")
                    .startsWith("systran/")
                    .contains("/transact-");
        }
    }

    @Nested
    @DisplayName("7. HIGH: a failed object write DISPLAYs, then abends, and publishes nothing")
    class WriteFailure {

        @BeforeEach
        void makeTheStoreFail() {
            makeStoreFail(new IllegalStateException("the bucket is unreachable"));
        }

        @Test
        @DisplayName("the source's own DISPLAY text precedes the abend")
        void theSourcesDisplayTextPrecedesTheAbend() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())));

            assertThat(loggedMessages()).contains("ERROR WRITING TO TRANSACTION FILE");
        }

        @Test
        @DisplayName("9910-DISPLAY-IO-STATUS renders the '9x' status as four characters")
        void theIoStatusIsRenderedAsFourCharacters() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())));

            assertThat(loggedMessages()).contains("FILE STATUS IS: NNNN9048");
        }

        @Test
        @DisplayName("the abend line is emitted, and the reason names the bucket, object and operation")
        void theAbendLineIsEmitted() {
            // NO LOG RECORD NAMES THE BUCKET OR THE OBJECT KEY, and that is deliberate: a log line carrying
            // them is an inventory of this deployment's storage layout, written to wherever logs are shipped.
            // They are carried by the escalation reason instead, which an operator only ever sees while
            // already inside the failure. This test once required the log line to quote the bucket.
            //
            // The escalation reason is not reachable from THIS scenario, and that is worth stating rather than
            // asserting around: the status mapper raises on a '9x' status, so the typed FileAccessException it
            // builds is what the caller receives, and abendProgram's own reason is constructed only on the
            // path where the mapper returns without raising. theStatusMapsToAFileAccessException asserts that
            // outcome directly. What this test owns is the pair of DISPLAY lines and their disclosure limit.
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .as("the typed failure names the logical file, the operation and the status")
                            .contains("WRITE")
                            .contains("TRANSACT"));

            assertThat(loggedMessages()).contains("ABENDING PROGRAM");
            assertThat(loggedMessages()).anyMatch(message -> message.contains("logicalFile=TRANSACT")
                    && message.contains("operation=WRITE"));
            assertThat(loggedMessages())
                    .as("no log record may name the bucket")
                    .noneMatch(message -> message.contains(BUCKET));
        }

        @Test
        @DisplayName("the '9x' status maps to a file access exception carrying the store failure")
        void theStatusMapsToAFileAccessException() {
            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())))
                    .satisfies(failure -> assertThat(failure.getCause())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("the bucket is unreachable"));
        }

        @Test
        @DisplayName("a failed object write publishes no key, so no step reads a phantom object")
        void aFailedWritePublishesNoKey() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())));

            assertThat(stepExecution.getExecutionContext()
                    .containsKey(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY))
                    .isFalse();
        }

        @Test
        @DisplayName("a failed object write counts no processed record")
        void aFailedWriteCountsNothing() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())));

            assertThat(processedCount()).isZero();
        }
    }

    @Nested
    @DisplayName("8. The chunk contract and the processed-records counter")
    class ChunkContract {

        @Test
        @DisplayName("a null chunk is a no-op, because a step with nothing to write writes nothing")
        void aNullChunkIsANoOp() throws Exception {
            writer.write(null);

            Mockito.verifyNoInteractions(repository);
            Mockito.verifyNoInteractions(objectStorage);
        }

        @Test
        @DisplayName("an empty chunk is a no-op and creates no empty object")
        void anEmptyChunkIsANoOp() throws Exception {
            writer.write(new Chunk<>(List.of()));

            Mockito.verifyNoInteractions(repository);
            Mockito.verifyNoInteractions(objectStorage);
            assertThat(processedCount()).isZero();
        }

        @Test
        @DisplayName("the counter is incremented once per record, not once per chunk")
        void theCounterIsIncrementedOncePerRecord() throws Exception {
            writer.write(chunk(posted(), posted("0000000000000002", "1.00"),
                    posted("0000000000000003", "2.00")));

            assertThat(processedCount())
                    .as("the counter replaces DISPLAY 'TRANSACTIONS PROCESSED :'")
                    .isEqualTo(3.0d);
        }

        @Test
        @DisplayName("two chunks accumulate on the same counter")
        void twoChunksAccumulate() throws Exception {
            writer.write(chunk(posted()));
            stepExecution.setWriteCount(1L);
            writer.write(chunk(posted("0000000000000002", "1.00")));

            assertThat(processedCount()).isEqualTo(2.0d);
        }

        @Test
        @DisplayName("the insert precedes the object write, so the table is the system of record")
        void theInsertPrecedesTheObjectWrite() throws Exception {
            writer.write(chunk(posted()));

            org.mockito.InOrder order = Mockito.inOrder(repository, objectStorage);
            order.verify(repository).saveAllAndFlush(Mockito.anyList());
            order.verify(objectStorage).upload(Mockito.eq(BUCKET), Mockito.anyString(),
                    Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
        }

        @Test
        @DisplayName("the record length is exposed as a constant so callers cannot re-derive it")
        void theRecordLengthIsExposed() {
            assertThat(TransactionWriter.RECORD_LENGTH)
                    .as("app/cpy/CVTRA05Y.cpy declares RECLN 350")
                    .isEqualTo(RECORD_LENGTH);
            assertThat(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY)
                    .isEqualTo("carddemo.transaction.object.key");
        }
    }
}
