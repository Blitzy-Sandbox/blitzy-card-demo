/*
 * ******************************************************************
 * Program     : RejectWriterTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the DALYREJS emission boundary against the
 *               COBOL it reproduces: the 430-byte record that resolves
 *               as 350 bytes of transaction image plus an 80-byte
 *               validation trailer of a four-digit reason code and a
 *               76-character description, the zoned-decimal trailing
 *               sign overpunch on DALYTRAN-AMT, the byte-transparent
 *               ISO-8859-1 framing that keeps every record boundary
 *               where the mainframe put it, the object key derived from
 *               the job instance and job execution identifiers, the
 *               chunk and single-record entry points, the reject
 *               counter tagged by reject code, and the write-failure
 *               path that DISPLAYs then abends with code 999.
 * Source      : app/cbl/CBTRN02C.cbl:L176-L182 (REJECT-RECORD layout)
 *               app/cbl/CBTRN02C.cbl:L442-L465 (2500-WRITE-REJECT-REC)
 *               app/cbl/CBTRN02C.cbl:L714-L731 (9910-DISPLAY-IO-STATUS)
 *               app/cbl/CBTRN02C.cbl:L707-L712 (9999-ABEND-PROGRAM)
 *               app/jcl/POSTTRAN.jcl:L34-L38   (DALYREJS DD, LRECL=430)
 *               app/jcl/DALYREJS.jcl:L24-L28   (GDG base)
 *               app/cpy/CVTRA06Y.cpy           (350-byte DALYTRAN layout)
 *               app/data/ASCII/dailytran.txt   (overpunch signs) @ 7756d89
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
import com.cardemo.batch.writers.RejectWriter;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

/**
 * Unit tests for {@link RejectWriter}, the Java form of {@code 2500-WRITE-REJECT-REC} at
 * {@code app/cbl/CBTRN02C.cbl:L442-L465}.
 *
 * <h2>What it does</h2>
 * <p>
 * Every assertion here is a statement about bytes. The legacy step writes a fixed-length record to a dataset
 * declared {@code RECFM=F,LRECL=430} at {@code app/jcl/POSTTRAN.jcl:L34-L38}, and that 430 is not a round
 * number chosen for convenience: it is {@code REJECT-TRAN-DATA PIC X(350)} followed by
 * {@code VALIDATION-TRAILER}, itself {@code WS-VALIDATION-FAIL-REASON PIC 9(4)} plus
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} ({@code app/cbl/CBTRN02C.cbl:L176-L182}). The suite proves
 * the arithmetic holds field by field, that the trailing-sign overpunch on {@code DALYTRAN-AMT} is produced
 * for both signs across the whole alphabet, that the payload encodes byte for byte so no record boundary
 * moves, and that a failed write reproduces the source's {@code DISPLAY} then abend rather than being
 * swallowed.
 *
 * <h2>How to build and test</h2>
 * <pre>
 * ./mvnw -B -ntp -Dtest='RejectWriterTest' -DfailIfNoTests=false test
 * </pre>
 *
 * <h2>Key configuration and defaults</h2>
 * <ul>
 * <li>{@code carddemo.aws.s3.batch-output-bucket} - the destination bucket; {@value #BUCKET} here.</li>
 * <li>{@code #stepExecution} - the step-scoped execution the object key is derived from. A {@code null}
 * execution is tolerated and yields the unassigned identifier {@code 0}, because the writer is constructible
 * outside a step for diagnostics.</li>
 * <li>{@code ISO-8859-1} - the record charset. It is the only single-byte transparent charset in the JDK's
 * required set, and the framing guard fails the write if characters and bytes ever disagree.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 * <li><b>Blocker.</b> A failure in group 2 or group 3 means the emitted record no longer matches the
 * mainframe's bytes. Gate 1 compares this output against the legacy baseline, so a one-byte shift fails the
 * parity comparison for every record in the file, not just the record that moved.</li>
 * <li><b>Blocker.</b> A failure in group 4 means a value that cannot fit its picture clause is being written
 * anyway - silent truncation, which no downstream test can detect because the record still has the right
 * length.</li>
 * <li><b>High.</b> A failure in group 6 means the write-failure path no longer abends, so a lost reject file
 * would be reported as a clean run.</li>
 * <li><b>High.</b> A failure in group 5 means two runs can collide on one object key, overwriting a
 * generation that the legacy GDG would have preserved.</li>
 * <li><b>Medium.</b> A failure in group 7 means the {@code reject.code} tag is wrong, so the dashboard panel
 * that replaces {@code DISPLAY 'TRANSACTIONS REJECTED  :'} attributes rejects to the wrong reason.</li>
 * </ul>
 */
@DisplayName("RejectWriter: CBTRN02C's 430-byte DALYREJS record")
class RejectWriterTest {

    /** The destination bucket, standing in for the {@code DALYREJS} GDG base. */
    private static final String BUCKET = "carddemo-batch-output";

    /**
     * The configured generation prefix, {@value}, mirroring {@code carddemo.aws.s3.gdg-prefixes.daly-rejs}
     * without the {@code gdg/} parent so that the key assertions below read against one path segment. The
     * writer takes the prefix as configuration rather than embedding it, so the tests supply it too.
     */
    private static final String REJECT_PREFIX = "dalyrejs";

    /** The job instance identifier the generation prefix is derived from. */
    private static final long JOB_INSTANCE_ID = 7L;

    /** The job execution identifier the generation key is derived from. */
    private static final long JOB_EXECUTION_ID = 42L;

    /** {@code REJECT-TRAN-DATA PIC X(350)}, the transaction image half of the record. */
    private static final int TRAN_DATA_LENGTH = 350;

    /** {@code VALIDATION-TRAILER}, the reason half of the record. */
    private static final int TRAILER_LENGTH = 80;

    /** The whole record: {@value #TRAN_DATA_LENGTH} plus {@value #TRAILER_LENGTH}. */
    private static final int RECORD_LENGTH = TRAN_DATA_LENGTH + TRAILER_LENGTH;

    private S3Operations s3Operations;
    private MeterRegistry meterRegistry;

    /**
     * The sole registrar of the four application counters. The writer counts through this collaborator rather
     * than resolving a meter itself, so the registry above is read only to assert what was registered.
     */
    private MetricsConfig metricsConfig;
    private StepExecution stepExecution;
    private RejectWriter writer;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    @BeforeEach
    void buildWriterAndCaptureLogs() {
        s3Operations = Mockito.mock(S3Operations.class);
        meterRegistry = new SimpleMeterRegistry();
        metricsConfig = new MetricsConfig(meterRegistry);
        stepExecution = stepExecution(JOB_INSTANCE_ID, JOB_EXECUTION_ID);
        writer = new RejectWriter(s3Operations, metricsConfig, new FileStatusMapper(), BUCKET, REJECT_PREFIX,
                stepExecution);

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RejectWriter.class);
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
     * Builds a step execution whose job instance and job execution identifiers are known, so that the object
     * key the writer composes is fully determined by the test.
     *
     * @param instanceId the job instance identifier, which drives the generation prefix
     * @param executionId the job execution identifier, which drives the generation key
     * @return a step execution carrying both
     */
    private static StepExecution stepExecution(final long instanceId, final long executionId) {
        JobExecution jobExecution = new JobExecution(new JobInstance(Long.valueOf(instanceId), "POSTTRAN"),
                Long.valueOf(executionId), new JobParameters());
        return new StepExecution("dailyTransactionPostingStep", jobExecution);
    }

    /**
     * Builds one {@code DALYTRAN-RECORD} in the {@code CVTRA06Y} layout, with every field exactly as wide as
     * its picture clause so that the emitted image can be asserted by offset.
     *
     * @param transactionId {@code DALYTRAN-ID PIC X(16)}
     * @param amount {@code DALYTRAN-AMT PIC S9(09)V99}
     * @return the staging row that failed validation
     */
    private static DailyTransaction rejected(final String transactionId, final String amount) {
        return new DailyTransaction(
                Long.valueOf(1L),
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

    /** @return the canonical rejected row: a positive amount of 100.00. */
    private static DailyTransaction rejected() {
        return rejected("0000000000000001", "100.00");
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
     * Writes one private field of a {@link DailyTransaction}, so that a field state the entity's own guards
     * refuse to construct - a {@code null} in a {@code NOT NULL} column, a value wider than its picture
     * clause, a magnitude outside the zoned-decimal domain - still reaches the writer's geometry guards. Those
     * guards are the last line before bytes leave the process, and a guard that no test can reach is a guard
     * nobody can trust.
     *
     * @param target the row to mutate
     * @param name the declared field name on {@link DailyTransaction}
     * @param value the value to write
     */
    private static void setField(final DailyTransaction target, final String name, final Object value) {
        try {
            java.lang.reflect.Field field = DailyTransaction.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot write " + name + " on DailyTransaction", failure);
        }
    }

    /**
     * Captures the single payload the writer uploaded and returns it as a string in the record charset.
     *
     * @return the uploaded payload
     */
    private String uploadedPayload() {
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        Mockito.verify(s3Operations).upload(Mockito.eq(BUCKET), Mockito.anyString(), captor.capture(),
                Mockito.any(ObjectMetadata.class));
        return new String(drain(captor.getValue()), StandardCharsets.ISO_8859_1);
    }

    /**
     * Captures the single payload the writer uploaded and returns its raw bytes, so that byte length can be
     * compared against character length independently of any decoding.
     *
     * @return the uploaded bytes
     */
    private byte[] uploadedBytes() {
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        Mockito.verify(s3Operations).upload(Mockito.eq(BUCKET), Mockito.anyString(), captor.capture(),
                Mockito.any(ObjectMetadata.class));
        return drain(captor.getValue());
    }

    /** @return the object key of the single upload the writer performed. */
    private String uploadedKey() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(s3Operations).upload(Mockito.eq(BUCKET), captor.capture(), Mockito.any(InputStream.class),
                Mockito.any(ObjectMetadata.class));
        return captor.getValue();
    }

    /** @return every object key the writer uploaded, in order. */
    private List<String> uploadedKeys() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(s3Operations, Mockito.atLeastOnce()).upload(Mockito.eq(BUCKET), captor.capture(),
                Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
        return captor.getAllValues();
    }

    /** @return the metadata of the single upload the writer performed. */
    private ObjectMetadata uploadedMetadata() {
        ArgumentCaptor<ObjectMetadata> captor = ArgumentCaptor.forClass(ObjectMetadata.class);
        Mockito.verify(s3Operations).upload(Mockito.eq(BUCKET), Mockito.anyString(),
                Mockito.any(InputStream.class), captor.capture());
        return captor.getValue();
    }

    /**
     * Reads a stream to exhaustion.
     *
     * @param source the stream the writer handed to the store
     * @return every byte it carried
     */
    private static byte[] drain(final InputStream source) {
        try (InputStream stream = source) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            stream.transferTo(sink);
            return sink.toByteArray();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot read the uploaded payload", failure);
        }
    }

    /** @return every message this class's logger received. */
    private List<String> loggedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Builds a chunk of rejected items.
     *
     * @param items the items
     * @return the chunk a chunk-oriented step would hand to the writer
     */
    private static Chunk<RejectWriter.RejectedTransaction> chunk(final RejectWriter.RejectedTransaction... items) {
        List<RejectWriter.RejectedTransaction> list = new ArrayList<>(List.of(items));
        return new Chunk<>(list);
    }

    /**
     * Reads one counter's value.
     *
     * @param code the reject code the counter is tagged with
     * @return the count, or zero when the counter was never registered
     */
    private double rejectCount(final int code) {
        io.micrometer.core.instrument.Counter counter = meterRegistry
                .find(MetricsConfig.METRIC_RECORDS_REJECTED)
                .tag(MetricsConfig.TAG_REJECT_CODE, Integer.toString(code))
                .counter();
        return counter == null ? 0.0d : counter.count();
    }

    @Nested
    @DisplayName("1. Construction: the collaborators the emission cannot proceed without")
    class Construction {

        @Test
        @DisplayName("a null store is refused by name")
        void aNullStoreIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RejectWriter(null, metricsConfig, new FileStatusMapper(), BUCKET,
                            REJECT_PREFIX, stepExecution))
                    .withMessage("s3Operations must not be null");
        }

        @Test
        @DisplayName("a null metrics collaborator is refused by name")
        void aNullMetricsConfigIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RejectWriter(s3Operations, null, new FileStatusMapper(), BUCKET,
                            REJECT_PREFIX, stepExecution))
                    .withMessage("metricsConfig must not be null");
        }

        @Test
        @DisplayName("a null status mapper is refused by name")
        void aNullStatusMapperIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RejectWriter(s3Operations, metricsConfig, null, BUCKET,
                            REJECT_PREFIX, stepExecution))
                    .withMessage("fileStatusMapper must not be null");
        }

        @Test
        @DisplayName("a null bucket is refused by name")
        void aNullBucketIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new RejectWriter(s3Operations, metricsConfig, new FileStatusMapper(),
                            null, REJECT_PREFIX, stepExecution))
                    .withMessageContaining(
                            "carddemo.aws.s3.batch-output-bucket must be configured with a non-blank value");
        }

        @Test
        @DisplayName("a null step execution is tolerated and yields the unassigned identifier")
        void aNullStepExecutionIsTolerated() {
            RejectWriter detached =
                    new RejectWriter(s3Operations, metricsConfig, new FileStatusMapper(), BUCKET,
                            REJECT_PREFIX, null);

            detached.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedKey())
                    .as("outside a step there is no job instance, so the prefix carries nineteen zeros")
                    .startsWith("dalyrejs/0000000000000000000/");
        }

        @Test
        @DisplayName("outside a step nothing is published to an execution context, and the writer says so")
        void outsideAStepNothingIsPublished() {
            RejectWriter detached =
                    new RejectWriter(s3Operations, metricsConfig, new FileStatusMapper(), BUCKET,
                            REJECT_PREFIX, null);

            detached.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            assertThat(loggedMessages())
                    .contains("No step context is available, so the DALYREJS generation key is not published");
        }
    }

    @Nested
    @DisplayName("2. BLOCKER: the record is 430 bytes, and 430 is 350 plus 80")
    class RecordGeometry {

        @Test
        @DisplayName("one reject emits exactly 430 characters")
        void oneRejectEmitsExactly430Characters() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedPayload())
                    .as("app/jcl/POSTTRAN.jcl declares DALYREJS as RECFM=F,LRECL=430")
                    .hasSize(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the trailer is a four-digit reason code followed by a 76-character description")
        void theTrailerIsAReasonCodeAndADescription() {
            writer.writeReject(rejected(), RejectCode.OVERLIMIT_TRANSACTION);

            String trailer = uploadedPayload().substring(TRAN_DATA_LENGTH);
            assertThat(trailer).hasSize(TRAILER_LENGTH);
            assertThat(trailer.substring(0, 4))
                    .as("WS-VALIDATION-FAIL-REASON PIC 9(4) is zero-filled, not space-filled")
                    .isEqualTo("0102");
            assertThat(trailer.substring(4))
                    .as("WS-VALIDATION-FAIL-REASON-DESC PIC X(76) carries the literal, space-padded")
                    .isEqualTo(pad("OVERLIMIT TRANSACTION", 76));
        }

        @ParameterizedTest(name = "reject {0} renders reason {1} and its own literal description")
        @CsvSource({
            "INVALID_CARD_NUMBER, 0100, INVALID CARD NUMBER FOUND",
            "ACCOUNT_RECORD_NOT_FOUND, 0101, ACCOUNT RECORD NOT FOUND",
            "OVERLIMIT_TRANSACTION, 0102, OVERLIMIT TRANSACTION",
            "TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION, 0103, TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
            "ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE, 0109, ACCOUNT RECORD NOT FOUND"})
        @DisplayName("every reject code renders its own reason and its own exact literal")
        void everyRejectCodeRendersItsOwnLiteral(final RejectCode rejectCode, final String reason,
                final String description) {
            writer.writeReject(rejected(), rejectCode);

            String trailer = uploadedPayload().substring(TRAN_DATA_LENGTH);
            assertThat(trailer.substring(0, 4)).isEqualTo(reason);
            assertThat(trailer.substring(4)).isEqualTo(pad(description, 76));
        }

        @Test
        @DisplayName("every DALYTRAN field lands at the offset CVTRA06Y gives it")
        void everyFieldLandsAtItsOffset() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            String image = uploadedPayload().substring(0, TRAN_DATA_LENGTH);
            assertThat(image.substring(0, 16)).as("DALYTRAN-ID").isEqualTo("0000000000000001");
            assertThat(image.substring(16, 18)).as("DALYTRAN-TYPE-CD").isEqualTo("01");
            assertThat(image.substring(18, 22)).as("DALYTRAN-CAT-CD").isEqualTo("5001");
            assertThat(image.substring(22, 32)).as("DALYTRAN-SOURCE").isEqualTo("POS TERM  ");
            assertThat(image.substring(32, 132)).as("DALYTRAN-DESC")
                    .isEqualTo(pad("Payment at Amazon", 100));
            assertThat(image.substring(132, 143)).as("DALYTRAN-AMT").isEqualTo("0000001000{");
            assertThat(image.substring(143, 152)).as("DALYTRAN-MERCHANT-ID").isEqualTo("123456789");
            assertThat(image.substring(152, 202)).as("DALYTRAN-MERCHANT-NAME")
                    .isEqualTo(pad("Amazon.com", 50));
            assertThat(image.substring(202, 252)).as("DALYTRAN-MERCHANT-CITY")
                    .isEqualTo(pad("Seattle", 50));
            assertThat(image.substring(252, 262)).as("DALYTRAN-MERCHANT-ZIP").isEqualTo("0000098101");
            assertThat(image.substring(262, 278)).as("DALYTRAN-CARD-NUM").isEqualTo("4111111111111111");
            assertThat(image.substring(278, 304)).as("DALYTRAN-ORIG-TS")
                    .isEqualTo("2022-07-18-11.22.33.123456");
            assertThat(image.substring(304, 330)).as("DALYTRAN-PROC-TS")
                    .isEqualTo("2022-07-18-11.22.34.123456");
            assertThat(image.substring(330))
                    .as("the 20-byte FILLER completes the 350-byte staging record")
                    .isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("the payload encodes byte for byte, so no record boundary moves")
        void thePayloadEncodesByteForByte() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            byte[] bytes = uploadedBytes();
            assertThat(bytes)
                    .as("a multi-byte charset would silently shift every subsequent record")
                    .hasSize(RECORD_LENGTH);
            assertThat(new String(bytes, StandardCharsets.ISO_8859_1))
                    .as("byte length and character length must agree, or a record boundary has moved")
                    .hasSize(bytes.length);
        }

        @Test
        @DisplayName("a shorter field is right-padded rather than shifting its neighbours left")
        void aShorterFieldIsRightPadded() {
            DailyTransaction transaction = rejected();
            setField(transaction, "merchantName", "AMZ");

            writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER);

            String image = uploadedPayload();
            assertThat(image.substring(152, 202)).isEqualTo(pad("AMZ", 50));
            assertThat(image.substring(202, 252))
                    .as("the city must still start at 203, not at 156")
                    .isEqualTo(pad("Seattle", 50));
            assertThat(image).hasSize(RECORD_LENGTH);
        }

        @Test
        @DisplayName("an absent optional text field becomes spaces, never a shortened record")
        void anAbsentTextFieldBecomesSpaces() {
            DailyTransaction transaction = rejected();
            setField(transaction, "merchantCity", null);

            writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER);

            String image = uploadedPayload();
            assertThat(image.substring(202, 252)).isEqualTo(" ".repeat(50));
            assertThat(image).hasSize(RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("3. BLOCKER: the trailing-sign overpunch of DALYTRAN-AMT PIC S9(09)V99")
    class SignOverpunch {

        @ParameterizedTest(name = "{0} renders as {1}")
        @CsvSource(delimiter = '|', value = {
            "0.00|0000000000{",
            "0.01|0000000000A",
            "0.02|0000000000B",
            "0.03|0000000000C",
            "0.04|0000000000D",
            "0.05|0000000000E",
            "0.06|0000000000F",
            "0.07|0000000000G",
            "0.08|0000000000H",
            "0.09|0000000000I",
            "100.00|0000001000{",
            "999999999.99|9999999999I"})
        @DisplayName("a non-negative amount carries the positive overpunch alphabet { through I")
        void aPositiveAmountCarriesThePositiveAlphabet(final String amount, final String rendered) {
            writer.writeReject(rejected("0000000000000001", amount), RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedPayload().substring(132, 143)).isEqualTo(rendered);
        }

        @ParameterizedTest(name = "{0} renders as {1}")
        @CsvSource(delimiter = '|', value = {
            "-0.01|0000000000J",
            "-0.02|0000000000K",
            "-0.03|0000000000L",
            "-0.04|0000000000M",
            "-0.05|0000000000N",
            "-0.06|0000000000O",
            "-0.07|0000000000P",
            "-0.08|0000000000Q",
            "-0.09|0000000000R",
            "-100.00|0000001000}",
            "-999999999.99|9999999999R"})
        @DisplayName("a negative amount carries the negative overpunch alphabet } through R")
        void aNegativeAmountCarriesTheNegativeAlphabet(final String amount, final String rendered) {
            writer.writeReject(rejected("0000000000000001", amount), RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedPayload().substring(132, 143))
                    .as("app/data/ASCII/dailytran.txt carries both signs, so the debit branch is real")
                    .isEqualTo(rendered);
        }

        @Test
        @DisplayName("a negative amount is never normalised to its absolute value")
        void aNegativeAmountIsNeverNormalised() {
            writer.writeReject(rejected("0000000000000001", "-250.55"), RejectCode.OVERLIMIT_TRANSACTION);

            assertThat(uploadedPayload().substring(132, 143))
                    .as("the sign lives in the final byte; taking abs() would emit E instead of N")
                    .isEqualTo("0000002505N");
        }

        @Test
        @DisplayName("an amount of scale one is rendered at scale two, not truncated")
        void anAmountOfScaleOneIsRenderedAtScaleTwo() {
            writer.writeReject(rejected("0000000000000001", "12.5"), RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedPayload().substring(132, 143)).isEqualTo("0000000125{");
        }

        @Test
        @DisplayName("an amount beyond PIC S9(09)V99 is refused rather than truncated")
        void anOverWideAmountIsRefused() {
            DailyTransaction transaction = rejected();
            setField(transaction, "amount", new BigDecimal("1000000000.00"));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("does not fit its picture clause");
            Mockito.verify(s3Operations, Mockito.never()).upload(Mockito.anyString(), Mockito.anyString(),
                    Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
        }

        @Test
        @DisplayName("an amount below the negative bound is refused rather than truncated")
        void anUnderWideAmountIsRefused() {
            DailyTransaction transaction = rejected();
            setField(transaction, "amount", new BigDecimal("-1000000000.00"));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("does not fit its picture clause");
        }

        @Test
        @DisplayName("an absent amount is refused, because PIC S9(09)V99 has no null representation")
        void anAbsentAmountIsRefused() {
            DailyTransaction transaction = rejected();
            setField(transaction, "amount", null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("has no null");
        }

        @Test
        @DisplayName("an amount of scale three is rounded half even, matching the posting arithmetic")
        void anAmountOfScaleThreeIsRoundedHalfEven() {
            DailyTransaction transaction = rejected();
            setField(transaction, "amount", new BigDecimal("0.005"));

            writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedPayload().substring(132, 143))
                    .as("HALF_EVEN sends a tie to the even digit, so 0.005 becomes 0.00")
                    .isEqualTo("0000000000{");
        }
    }

    @Nested
    @DisplayName("4. BLOCKER: a value that cannot fit its picture clause is refused, never truncated")
    class GeometryGuards {

        @Test
        @DisplayName("an over-wide text field abends rather than being silently truncated")
        void anOverWideTextFieldAbends() {
            DailyTransaction transaction = rejected();
            setField(transaction, "merchantName", "X".repeat(51));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("DALYTRAN-MERCHANT-NAME does not fit its picture clause");
        }

        @Test
        @DisplayName("the geometry failure withholds the offending value, which may identify a customer")
        void theGeometryFailureWithholdsTheValue() {
            DailyTransaction transaction = rejected();
            setField(transaction, "merchantName", "SECRETMERCHANT".repeat(4));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .as("Rule 1 clause D1 keeps field content out of diagnostics")
                            .doesNotContain("SECRETMERCHANT"));
        }

        @Test
        @DisplayName("the geometry failure abends as CBTRN02C with code 999 and return code 12")
        void theGeometryFailureAbendsAsCbtrn02c() {
            DailyTransaction transaction = rejected();
            setField(transaction, "amount", null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .satisfies(failure -> {
                        assertThat(failure.getAbendCode()).isEqualTo("999");
                        assertThat(failure.getAbendCulprit()).isEqualTo("CBTRN02C");
                        assertThat(failure.getAbendReason()).isEqualTo("REJECT RECORD GEOMETRY VIOLATION");
                    });
        }

        @Test
        @DisplayName("an absent category code is refused, because an unsigned field has no null form")
        void anAbsentCategoryCodeIsRefused() {
            DailyTransaction transaction = rejected();
            setField(transaction, "categoryCode", null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("DALYTRAN-CAT-CD is null and cannot be rendered");
        }

        @Test
        @DisplayName("an absent merchant identifier is refused for the same reason")
        void anAbsentMerchantIdentifierIsRefused() {
            DailyTransaction transaction = rejected();
            setField(transaction, "merchantId", null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("DALYTRAN-MERCHANT-ID is null and cannot be rendered");
        }

        @ParameterizedTest(name = "a category code of {0} is refused")
        @ValueSource(ints = {-1, 10000})
        @DisplayName("a category code outside PIC 9(04) is refused")
        void anOutOfRangeCategoryCodeIsRefused(final int categoryCode) {
            DailyTransaction transaction = rejected();
            setField(transaction, "categoryCode", Integer.valueOf(categoryCode));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("admits only 0 through 9999");
        }

        @ParameterizedTest(name = "a merchant identifier of {0} is refused")
        @ValueSource(longs = {-1L, 1_000_000_000L})
        @DisplayName("a merchant identifier outside PIC 9(09) is refused")
        void anOutOfRangeMerchantIdentifierIsRefused(final long merchantId) {
            DailyTransaction transaction = rejected();
            setField(transaction, "merchantId", Long.valueOf(merchantId));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("admits only 0 through 999999999");
        }

        @Test
        @DisplayName("a category code narrower than its field is zero-filled on the left")
        void aNarrowCategoryCodeIsZeroFilled() {
            DailyTransaction transaction = rejected();
            setField(transaction, "categoryCode", Integer.valueOf(7));

            writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedPayload().substring(18, 22))
                    .as("PIC 9(04) is zero-filled; space-filling would break the downstream sort")
                    .isEqualTo("0007");
        }
    }

    @Nested
    @DisplayName("5. The generation key that replaces the GDG relative generation")
    class GenerationKey {

        @Test
        @DisplayName("the key carries the job instance, the job execution and a sequence number")
        void theKeyCarriesBothIdentifiersAndASequence() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedKey())
                    .as("app/jcl/DALYREJS.jcl's GDG base becomes a deterministic object prefix")
                    .isEqualTo(String.format(Locale.ROOT, "dalyrejs/%019d/%019d-%019d.dat",
                            JOB_INSTANCE_ID, JOB_EXECUTION_ID, 1));
        }

        @Test
        @DisplayName("a second emission takes the next sequence number rather than overwriting the first")
        void aSecondEmissionTakesTheNextSequence() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);
            writer.writeReject(rejected("0000000000000002", "5.00"), RejectCode.OVERLIMIT_TRANSACTION);

            assertThat(uploadedKeys())
                    .as("a GDG never overwrites a generation, so neither may the object store")
                    .containsExactly(
                            String.format(Locale.ROOT, "dalyrejs/%019d/%019d-%019d.dat",
                                    JOB_INSTANCE_ID, JOB_EXECUTION_ID, 1),
                            String.format(Locale.ROOT, "dalyrejs/%019d/%019d-%019d.dat",
                                    JOB_INSTANCE_ID, JOB_EXECUTION_ID, 2));
        }

        @Test
        @DisplayName("two job instances write under different prefixes")
        void twoJobInstancesWriteUnderDifferentPrefixes() {
            RejectWriter other = new RejectWriter(s3Operations, metricsConfig, new FileStatusMapper(),
                    BUCKET, REJECT_PREFIX, stepExecution(8L, 43L));

            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);
            other.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedKeys())
                    .containsExactly(
                            String.format(Locale.ROOT, "dalyrejs/%019d/%019d-%019d.dat", 7L, 42L, 1),
                            String.format(Locale.ROOT, "dalyrejs/%019d/%019d-%019d.dat", 8L, 43L, 1));
        }

        @Test
        @DisplayName("the object metadata declares the byte count and an opaque content type")
        void theMetadataDeclaresTheByteCount() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            ObjectMetadata metadata = uploadedMetadata();
            assertThat(metadata.getContentLength())
                    .as("a fixed-length dataset is opaque bytes, and its length is part of the contract")
                    .isEqualTo(Long.valueOf(RECORD_LENGTH));
            assertThat(metadata.getContentType()).isEqualTo("application/octet-stream");
        }

        @Test
        @DisplayName("a successful emission publishes the key, the prefix and the cumulative count")
        void aSuccessfulEmissionPublishesItsKey() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            assertThat(stepExecution.getExecutionContext()
                    .getString(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY))
                    .isEqualTo(uploadedKey());
            assertThat(stepExecution.getExecutionContext()
                    .getString(RejectWriter.REJECT_GENERATION_PREFIX_CONTEXT_KEY))
                    .isEqualTo(String.format(Locale.ROOT, "dalyrejs/%019d/", JOB_INSTANCE_ID));
            assertThat(stepExecution.getExecutionContext()
                    .getLong(RejectWriter.REJECT_RECORD_COUNT_CONTEXT_KEY))
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the published count accumulates across emissions")
        void thePublishedCountAccumulates() throws Exception {
            writer.write(chunk(
                    new RejectWriter.RejectedTransaction(rejected(), RejectCode.INVALID_CARD_NUMBER),
                    new RejectWriter.RejectedTransaction(rejected("0000000000000002", "1.00"),
                            RejectCode.OVERLIMIT_TRANSACTION)));
            writer.writeReject(rejected("0000000000000003", "2.00"), RejectCode.ACCOUNT_RECORD_NOT_FOUND);

            assertThat(stepExecution.getExecutionContext()
                    .getLong(RejectWriter.REJECT_RECORD_COUNT_CONTEXT_KEY))
                    .as("the count is the run's reject total, which drives the return code 4 decision")
                    .isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("6. HIGH: a failed write DISPLAYs, then abends, and publishes nothing")
    class WriteFailure {

        @BeforeEach
        void makeTheStoreFail() {
            Mockito.when(s3Operations.upload(Mockito.anyString(), Mockito.anyString(),
                            Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class)))
                    .thenThrow(new IllegalStateException("the bucket is unreachable"));
        }

        @Test
        @DisplayName("the source's own DISPLAY text precedes the abend")
        void theSourcesDisplayTextPrecedesTheAbend() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER));

            assertThat(loggedMessages())
                    .as("app/cbl/CBTRN02C.cbl:L460 DISPLAYs before performing 9999-ABEND-PROGRAM")
                    .contains("ERROR WRITING TO REJECTS FILE");
        }

        @Test
        @DisplayName("9910-DISPLAY-IO-STATUS renders the '9x' status as four characters")
        void theIoStatusIsRenderedAsFourCharacters() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER));

            assertThat(loggedMessages())
                    .as("a '9' first byte expands the second byte into three digits")
                    .contains("FILE STATUS IS: NNNN9048");
        }

        @Test
        @DisplayName("the abend line names the file, the operation, the code and the return code")
        void theAbendLineNamesEverything() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER));

            assertThat(loggedMessages()).anyMatch(message -> message.startsWith("ABENDING PROGRAM: DALYREJS")
                    && message.contains("WRITE")
                    && message.contains("999")
                    && message.contains("12"));
        }

        @Test
        @DisplayName("the '9x' status maps to a file access exception carrying the store failure as its cause")
        void theStatusMapsToAFileAccessException() {
            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER))
                    .satisfies(failure -> assertThat(failure.getCause())
                            .as("Rule 1 clause B4 preserves the root cause rather than swallowing it")
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("the bucket is unreachable"));
        }

        @Test
        @DisplayName("a failed write publishes no key, so no downstream step reads a phantom generation")
        void aFailedWritePublishesNoKey() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER));

            assertThat(stepExecution.getExecutionContext()
                    .containsKey(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY))
                    .isFalse();
        }

        @Test
        @DisplayName("a failed write increments no reject counter")
        void aFailedWriteCountsNothing() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER));

            assertThat(rejectCount(RejectCode.INVALID_CARD_NUMBER.getCode()))
                    .as("a record that never reached the dataset was never rejected, only lost")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("7. The rejected-records counter that replaces DISPLAY 'TRANSACTIONS REJECTED  :'")
    class RejectCounter {

        @Test
        @DisplayName("one reject increments the counter tagged with its own code")
        void oneRejectIncrementsItsOwnCounter() {
            writer.writeReject(rejected(), RejectCode.OVERLIMIT_TRANSACTION);

            assertThat(rejectCount(102)).isEqualTo(1.0d);
            assertThat(rejectCount(100)).isZero();
        }

        @Test
        @DisplayName("two codes are counted separately, so the dashboard can attribute rejects")
        void twoCodesAreCountedSeparately() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);
            writer.writeReject(rejected("0000000000000002", "1.00"), RejectCode.INVALID_CARD_NUMBER);
            writer.writeReject(rejected("0000000000000003", "2.00"),
                    RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION);

            assertThat(rejectCount(100)).isEqualTo(2.0d);
            assertThat(rejectCount(103)).isEqualTo(1.0d);
        }

        @Test
        @DisplayName("the counter carries the name and tag key the dashboard queries")
        void theCounterCarriesTheDocumentedNameAndTag() {
            writer.writeReject(rejected(), RejectCode.ACCOUNT_RECORD_NOT_FOUND);

            assertThat(MetricsConfig.METRIC_RECORDS_REJECTED).isEqualTo("carddemo.batch.records.rejected");
            assertThat(MetricsConfig.TAG_REJECT_CODE).isEqualTo("reject.code");
            assertThat(meterRegistry.find(MetricsConfig.METRIC_RECORDS_REJECTED).counters())
                    .as("MetricsConfig pre-registers one series per reject code at startup, so a code that "
                            + "never occurs reports zero rather than being absent from the scrape. There are "
                            + "exactly five reject codes, so there are exactly five series")
                    .hasSize(RejectCode.values().length);
            assertThat(meterRegistry.find(MetricsConfig.METRIC_RECORDS_REJECTED)
                    .tag(MetricsConfig.TAG_REJECT_CODE,
                            Integer.toString(RejectCode.ACCOUNT_RECORD_NOT_FOUND.getCode()))
                    .counter())
                    .as("and the write above incremented the one series its reject code names")
                    .isNotNull()
                    .satisfies(counter -> assertThat(counter.count()).isEqualTo(1.0d));
        }
    }

    @Nested
    @DisplayName("8. The chunk entry point: one generation per chunk, one count per record")
    class ChunkWriting {

        @Test
        @DisplayName("an empty chunk creates no generation and says why")
        void anEmptyChunkCreatesNoGeneration() throws Exception {
            writer.write(chunk());

            Mockito.verify(s3Operations, Mockito.never()).upload(Mockito.anyString(), Mockito.anyString(),
                    Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
            assertThat(loggedMessages())
                    .contains("No rejected transactions in this chunk; no DALYREJS generation is created");
        }

        @Test
        @DisplayName("a chunk of three emits one object of exactly three records")
        void aChunkOfThreeEmitsOneObject() throws Exception {
            writer.write(chunk(
                    new RejectWriter.RejectedTransaction(rejected(), RejectCode.INVALID_CARD_NUMBER),
                    new RejectWriter.RejectedTransaction(rejected("0000000000000002", "1.00"),
                            RejectCode.OVERLIMIT_TRANSACTION),
                    new RejectWriter.RejectedTransaction(rejected("0000000000000003", "-2.00"),
                            RejectCode.ACCOUNT_RECORD_NOT_FOUND)));

            String payload = uploadedPayload();
            assertThat(payload)
                    .as("a fixed-length dataset is a whole number of records, never a delimited stream")
                    .hasSize(3 * RECORD_LENGTH);
            assertThat(payload.substring(0, 16)).isEqualTo("0000000000000001");
            assertThat(payload.substring(RECORD_LENGTH, RECORD_LENGTH + 16)).isEqualTo("0000000000000002");
            assertThat(payload.substring(2 * RECORD_LENGTH, 2 * RECORD_LENGTH + 16))
                    .isEqualTo("0000000000000003");
        }

        @Test
        @DisplayName("a chunk preserves the reject code of each record, not the first record's code")
        void aChunkPreservesEachRecordsCode() throws Exception {
            writer.write(chunk(
                    new RejectWriter.RejectedTransaction(rejected(), RejectCode.INVALID_CARD_NUMBER),
                    new RejectWriter.RejectedTransaction(rejected("0000000000000002", "1.00"),
                            RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION)));

            String payload = uploadedPayload();
            assertThat(payload.substring(TRAN_DATA_LENGTH, TRAN_DATA_LENGTH + 4)).isEqualTo("0100");
            assertThat(payload.substring(RECORD_LENGTH + TRAN_DATA_LENGTH,
                    RECORD_LENGTH + TRAN_DATA_LENGTH + 4)).isEqualTo("0103");
        }

        @Test
        @DisplayName("a chunk increments one counter per record, per code")
        void aChunkCountsEveryRecord() throws Exception {
            writer.write(chunk(
                    new RejectWriter.RejectedTransaction(rejected(), RejectCode.INVALID_CARD_NUMBER),
                    new RejectWriter.RejectedTransaction(rejected("0000000000000002", "1.00"),
                            RejectCode.INVALID_CARD_NUMBER),
                    new RejectWriter.RejectedTransaction(rejected("0000000000000003", "2.00"),
                            RejectCode.OVERLIMIT_TRANSACTION)));

            assertThat(rejectCount(100)).isEqualTo(2.0d);
            assertThat(rejectCount(102)).isEqualTo(1.0d);
        }

        @Test
        @DisplayName("a null chunk is refused by name")
        void aNullChunkIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> writer.write(null))
                    .withMessage("chunk must not be null");
        }

        @Test
        @DisplayName("an item pair refuses a null transaction and a null reject code by name")
        void anItemPairRefusesNulls() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RejectWriter.RejectedTransaction(null,
                            RejectCode.INVALID_CARD_NUMBER))
                    .withMessage("transaction must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RejectWriter.RejectedTransaction(rejected(), null))
                    .withMessage("rejectCode must not be null");
        }

        @Test
        @DisplayName("a chunk emits one object rather than one object per record")
        void aChunkEmitsOneObjectNotThree() throws Exception {
            writer.write(chunk(
                    new RejectWriter.RejectedTransaction(rejected(), RejectCode.INVALID_CARD_NUMBER),
                    new RejectWriter.RejectedTransaction(rejected("0000000000000002", "1.00"),
                            RejectCode.INVALID_CARD_NUMBER)));

            Mockito.verify(s3Operations, Mockito.times(1)).upload(Mockito.eq(BUCKET), Mockito.anyString(),
                    Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
        }

        @Test
        @DisplayName("a chunk whose first record is unrenderable emits nothing at all")
        void aBadRecordInAChunkEmitsNothing() {
            DailyTransaction broken = rejected("0000000000000002", "1.00");
            setField(broken, "amount", null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.write(chunk(
                            new RejectWriter.RejectedTransaction(rejected(), RejectCode.INVALID_CARD_NUMBER),
                            new RejectWriter.RejectedTransaction(broken, RejectCode.OVERLIMIT_TRANSACTION))));

            Mockito.verify(s3Operations, Mockito.never()).upload(Mockito.anyString(), Mockito.anyString(),
                    Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
            assertThat(rejectCount(100))
                    .as("the whole chunk is built before any byte is written, so nothing is half-counted")
                    .isZero();
        }
    }
}
