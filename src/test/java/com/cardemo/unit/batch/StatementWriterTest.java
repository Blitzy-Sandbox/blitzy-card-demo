/*
 * ******************************************************************
 * Program     : StatementWriterTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the dual-format statement emission boundary
 *               against the COBOL it reproduces: the 80-byte STMTFILE
 *               record and the 100-byte HTMLFILE record that are never
 *               harmonised, the header paragraph's STRING semantics that
 *               transfer only the characters before the first space of
 *               each name and address part, the two rule lines the source
 *               writes twice and which are deliberately not collapsed,
 *               the detail line assembled as 16 + 1 + 49 + 1 + 13, the
 *               zero-filled and zero-suppressed edited amount masks with
 *               their trailing sign position, the per-account object keys
 *               that replace the GDG generation, and the upload failure
 *               that surfaces as a typed I/O exception.
 * Source      : app/cbl/CBSTM03A.CBL:L433-L437 (4000-TRNXFILE-TOTALS)
 *               app/cbl/CBSTM03A.CBL:L460-L502 (5000-CREATE-STATEMENT)
 *               app/cbl/CBSTM03A.CBL:L508-L552 (5100-WRITE-HTML-HEADER)
 *               app/cbl/CBSTM03A.CBL:L560-L669 (5200-WRITE-HTML-NMADBS)
 *               app/cbl/CBSTM03A.CBL:L676-L721 (6000-WRITE-TRANS)
 *               app/cbl/CBSTM03A.CBL:L921      (9999-ABEND-PROGRAM)
 *               app/cbl/CBSTM03A.CBL:L149      (100-byte HTML record area)
 *               app/jcl/CREASTMT.JCL:STEP040   (STMTFILE LRECL=80, HTMLFILE LRECL=100)
 *               app/cpy/COSTM01.CPY            (350-byte statement record) @ 7756d89
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
import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;

/**
 * Unit tests for {@link StatementWriter}, the Java form of the statement emission paragraphs of
 * {@code app/cbl/CBSTM03A.CBL}.
 *
 * <h2>What it does</h2>
 * <p>
 * The legacy program writes two datasets at once and they have different record lengths:
 * {@code app/jcl/CREASTMT.JCL:STEP040} declares {@code STMTFILE} at {@code LRECL=80} and {@code HTMLFILE} at
 * {@code LRECL=100}, and {@code app/cbl/CBSTM03A.CBL:L149} independently confirms the hundred-character HTML
 * record area. The two widths are never harmonised, because harmonising them would change both outputs. This
 * suite fixes that geometry, then fixes the composition rules that are easy to translate wrongly: the
 * {@code STRING … DELIMITED BY ' '} semantics that transfer only the characters before the first space of each
 * name and address part, the two rule lines the source writes twice in a row and which are preserved as two
 * writes rather than collapsed into one, the detail line that is exactly {@code 16 + 1 + 49 + 1 + 13}, and the
 * two edited-amount masks - {@code 9(9).99-} zero filled for the balance and {@code Z(9).99-} zero suppressed
 * for amounts - whose sign occupies a trailing position that is a space when the value is not negative.
 *
 * <h2>How to build and test</h2>
 * <pre>
 * ./mvnw -B -ntp -Dtest='StatementWriterTest' -DfailIfNoTests=false test
 * </pre>
 *
 * <h2>Key configuration and defaults</h2>
 * <ul>
 * <li>{@code carddemo.aws.s3.statements-bucket} - <strong>required, with no inline default</strong>, on the
 * same terms as the batch output bucket the other two writers in the package take. Absent and blank are
 * refused identically, because an unset property resolves to an empty string rather than to null and either
 * one would send every statement to an unnamed destination.</li>
 * <li>The generation used in the object key comes from the job instance identifier captured by
 * {@code beforeStep}. Outside a step it stays {@code 0}, and an explicit generation may be supplied.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 * <li><b>Blocker.</b> A failure in group 3 means a record is no longer its declared width, so a fixed-blocked
 * dataset read of the object splits records at the wrong byte and the whole statement is unreadable.</li>
 * <li><b>Blocker.</b> A failure in group 4 or group 6 means the emitted statement no longer matches the
 * legacy baseline character for character, which is what Gate 1 compares.</li>
 * <li><b>High.</b> A failure in group 2 means a record can be emitted with no statement open, so it would be
 * attributed to the previous account.</li>
 * <li><b>High.</b> A failure in group 8 means two accounts, or two runs of one account, can collide on one
 * object key.</li>
 * <li><b>Medium.</b> A failure in group 9 means a rejected upload is not surfaced as an I/O failure, so a
 * statement that was never stored would be reported as delivered.</li>
 * </ul>
 */
@DisplayName("StatementWriter: CBSTM03A's 80-byte STMTFILE and 100-byte HTMLFILE")
class StatementWriterTest {

    /** The statements bucket. */
    private static final String BUCKET = "carddemo-statements";

    /**
     * A frozen time source. The writer reads the clock only to derive the statement month when no step
     * supplies one, so a fixed instant keeps every object key deterministic.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-06-10T19:27:53.470Z"), ZoneOffset.UTC);

    /** {@code STMTFILE} record length, {@code app/jcl/CREASTMT.JCL:STEP040} {@code LRECL=80}. */
    private static final int TEXT_LENGTH = 80;

    /** {@code HTMLFILE} record length, {@code app/jcl/CREASTMT.JCL:STEP040} {@code LRECL=100}. */
    private static final int HTML_LENGTH = 100;

    /** The account the statement is produced for. */
    private static final String ACCOUNT_ID = "00000000050";

    /** The statement month segment of the object key. */
    private static final String MONTH = "2022-07";

    private S3Template s3Template;

    /**
     * The registry the four application series are registered against.
     *
     * <p>The writer holds no meter owner and no registry of its own, so nothing it does can reach this. It is
     * here to prove exactly that: a test registers the series through {@link MetricsConfig}, drives the
     * writer, and reads the series back unchanged.
     */
    private MeterRegistry meterRegistry;

    /**
     * The writer under test. It registers and advances no instrument: {@code MetricsConfig} is the sole
     * registrar of the four application counters and is not a collaborator of this class, so there is no
     * registry to read here. {@code StatementWriterContractTest.InstrumentOwnership} asserts that absence.
     */
    private StatementWriter writer;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    @BeforeEach
    void buildWriterAndCaptureLogs() {
        s3Template = Mockito.mock(S3Template.class);
        meterRegistry = new SimpleMeterRegistry();
        writer = new StatementWriter(s3Template, new FileStatusMapper(), FIXED_CLOCK, BUCKET);

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(StatementWriter.class);
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
     * Builds one statement transaction in the {@code COSTM01} layout.
     *
     * @param transactionId {@code TRNX-ID}
     * @param amount {@code TRNX-AMT}
     * @param description {@code TRNX-DESC}
     * @return the transaction
     */
    private static StatementTransaction transaction(final String transactionId, final String amount,
            final String description) {
        return new StatementTransaction(
                "0500024453765740",
                transactionId,
                "01",
                "5001",
                "POS TERM  ",
                description,
                new BigDecimal(amount),
                "123456789",
                "Amazon.com",
                "Seattle",
                "0000098101",
                "2022-07-18-11.22.33.123456",
                "2022-07-18-11.22.34.123456",
                "");
    }

    /** @return the canonical transaction: identifier 1, 100.00, a short description. */
    private static StatementTransaction transaction() {
        return transaction("0000000000000001", "100.00", "COFFEE AND CAKE");
    }

    /**
     * Builds one composed statement, as the processor hands it to the {@code ItemWriter} contract.
     *
     * <p>Both line lists are already at their declared widths - {@value StatementTransaction#STATEMENT_TEXT_RECORD_LENGTH}
     * for {@code STMTFILE} and {@value StatementTransaction#STATEMENT_HTML_RECORD_LENGTH} for {@code HTMLFILE},
     * per {@code app/jcl/CREASTMT.JCL:STEP040} - so the writer pads nothing and rejects nothing.
     *
     * @return one statement for the canonical account
     */
    private static StatementProcessor.Statement statement() {
        return new StatementProcessor.Statement(ACCOUNT_ID, new BigDecimal("100.00"),
                List.of("A".repeat(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH)),
                List.of("B".repeat(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH)));
    }

    /**
     * Opens the outputs for the canonical account and month.
     */
    private void open() {
        writer.openStatementOutputs(ACCOUNT_ID, MONTH);
    }

    /**
     * Flushes and returns the payload of one of the two objects, decoded in the record charset.
     *
     * @param objectName the object name suffix, {@code STATEMNT.PS} or {@code STATEMNT.HTML}
     * @return the payload
     */
    private String payloadOf(final String objectName) {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InputStream> streams = ArgumentCaptor.forClass(InputStream.class);
        Mockito.verify(s3Template, Mockito.atLeastOnce()).upload(Mockito.eq(BUCKET), keys.capture(),
                streams.capture(), Mockito.any(ObjectMetadata.class));
        for (int index = 0; index < keys.getAllValues().size(); index++) {
            if (keys.getAllValues().get(index).endsWith(objectName)) {
                return drain(streams.getAllValues().get(index));
            }
        }
        throw new AssertionError("no object was uploaded whose key ends with " + objectName
                + "; keys=" + keys.getAllValues());
    }

    /** @return the 80-byte text records of the flushed statement, split at the record boundary. */
    private List<String> textRecords() {
        return split(payloadOf("STATEMNT.PS"), TEXT_LENGTH);
    }

    /** @return the 100-byte HTML records of the flushed statement, split at the record boundary. */
    private List<String> htmlRecords() {
        return split(payloadOf("STATEMNT.HTML"), HTML_LENGTH);
    }

    /**
     * Splits a fixed-blocked payload into its records. A fixed-blocked dataset stores no delimiters, so the
     * only way to recover the records is to cut at the declared width - which is exactly why the width must
     * be exact.
     *
     * @param payload the whole object
     * @param width the declared record length
     * @return every record, in order
     */
    private static List<String> split(final String payload, final int width) {
        assertThat(payload.length() % width)
                .as("a fixed-blocked object is a whole number of %d-byte records", width)
                .isZero();
        List<String> records = new ArrayList<>(payload.length() / width);
        for (int offset = 0; offset < payload.length(); offset += width) {
            records.add(payload.substring(offset, offset + width));
        }
        return records;
    }

    /**
     * Reads a stream to exhaustion and decodes it in the record charset.
     *
     * @param source the stream handed to the store
     * @return the decoded payload
     */
    private static String drain(final InputStream source) {
        try (InputStream stream = source) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            stream.transferTo(sink);
            return new String(sink.toByteArray(), StandardCharsets.ISO_8859_1);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot read the uploaded payload", failure);
        }
    }

    /**
     * Captures the metadata of the upload whose key ends with the supplied object name.
     *
     * @param objectName the object name suffix
     * @return its metadata
     */
    private ObjectMetadata metadataOf(final String objectName) {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ObjectMetadata> metadata = ArgumentCaptor.forClass(ObjectMetadata.class);
        Mockito.verify(s3Template, Mockito.atLeastOnce()).upload(Mockito.eq(BUCKET), keys.capture(),
                Mockito.any(InputStream.class), metadata.capture());
        for (int index = 0; index < keys.getAllValues().size(); index++) {
            if (keys.getAllValues().get(index).endsWith(objectName)) {
                return metadata.getAllValues().get(index);
            }
        }
        throw new AssertionError("no object was uploaded whose key ends with " + objectName);
    }

    /** @return every object key the writer uploaded, in order. */
    private List<String> uploadedKeys() {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        Mockito.verify(s3Template, Mockito.atLeastOnce()).upload(Mockito.eq(BUCKET), keys.capture(),
                Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
        return keys.getAllValues();
    }

    /** @return every message this class's logger received. */
    private List<String> loggedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Reads the untagged processed-records series, citing the owner's symbol rather than retyping the name.
     *
     * @return the counter's value, or {@code 0.0} when the series has never been registered
     */
    private double processedCount() {
        io.micrometer.core.instrument.Counter counter =
                meterRegistry.find(MetricsConfig.METRIC_RECORDS_PROCESSED).counter();
        return counter == null ? 0.0d : counter.count();
    }

    /**
     * Builds a chunk of statement transactions.
     *
     * @param items the items
     * @return the chunk
     */
    private static Chunk<StatementTransaction> chunk(final StatementTransaction... items) {
        return new Chunk<>(List.of(items));
    }

    /**
     * Builds a step execution carrying a known job instance identifier.
     *
     * @param instanceId the job instance identifier, which becomes the key generation
     * @return the execution
     */
    private static StepExecution stepExecution(final long instanceId) {
        JobExecution jobExecution = new JobExecution(new JobInstance(Long.valueOf(instanceId), "CREASTMT"),
                Long.valueOf(instanceId), new JobParameters());
        return new StepExecution("statementGenerationStep", jobExecution);
    }

    @Nested
    @DisplayName("1. Construction: the collaborators and the destination the emission cannot proceed without")
    class Construction {

        @Test
        @DisplayName("a null store is refused by name")
        void aNullStoreIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new StatementWriter(null, new FileStatusMapper(),
                            FIXED_CLOCK, BUCKET))
                    .withMessage("objectStorage must not be null");
        }

        @Test
        @DisplayName("a null status mapper is refused by name")
        void aNullStatusMapperIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new StatementWriter(s3Template, null, FIXED_CLOCK, BUCKET))
                    .withMessage("fileStatusMapper must not be null");
        }

        @Test
        @DisplayName("no meter owner and no registry is taken at all, so no series can be advanced here")
        void noMeterOwnerIsTakenAtAll() {
            // This writer used to take MetricsConfig and advance the untagged records-processed counter once per
            // statement. That counter's unit is one record a batch step handled - the WS-TRANSACTION-COUNT
            // analogue of app/cbl/CBTRN02C.cbl:L206 - and a statement is an aggregate over transactions that an
            // earlier posting run already counted, so each increment added the same underlying work to the
            // series a second time under a second meaning. Since the series carries no tag, no query can
            // separate the two afterwards.
            //
            // Withholding the collaborator, rather than merely not calling it, is what makes that permanent.
            assertThat(StatementWriter.class.getDeclaredConstructors())
                    .singleElement()
                    .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                            .doesNotContain(MetricsConfig.class)
                            .doesNotContain(MeterRegistry.class)
                            .doesNotContain(io.micrometer.core.instrument.Counter.class)
                            .hasSize(4));
        }

        @Test
        @DisplayName("a null clock is refused by name")
        void aNullClockIsRefused() {
            // There is no meter-owner argument to refuse: this writer reports no series, so it takes no
            // MetricsConfig. The clock is the collaborator that took its constructor position.
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new StatementWriter(s3Template, new FileStatusMapper(), null, BUCKET))
                    .withMessage("clock must not be null");
        }

        @Test
        @DisplayName("a null bucket is refused, naming the variable to set")
        void aNullBucketIsRefused() {
            // An absent bucket is refused on exactly the same terms as a blank one, which is what the
            // cross-writer policy in WriterObjectStoragePolicyTest asserts of all three writers: an unset
            // property resolves to an empty string rather than to null, so a guard that distinguished the
            // two would report the same misconfiguration two different ways. The message names the
            // environment variable rather than the parameter, because the reader of a startup failure is an
            // operator who has to set a value, not a caller who passed one.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementWriter(s3Template,
                            new FileStatusMapper(), FIXED_CLOCK, null))
                    .withMessageContaining("CARDDEMO_S3_STATEMENTS_BUCKET");
        }

        @ParameterizedTest(name = "a bucket of [{0}] is refused, naming the variable to set")
        @ValueSource(strings = {"", "   "})
        @DisplayName("a blank bucket is refused rather than defaulted")
        void aBlankBucketIsRefused(final String bucket) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementWriter(s3Template,
                            new FileStatusMapper(), FIXED_CLOCK, bucket))
                    .withMessageContaining("CARDDEMO_S3_STATEMENTS_BUCKET");
        }

        @Test
        @DisplayName("the two DD names and both context keys are exposed as constants")
        void theDdNamesAreExposed() {
            assertThat(StatementWriter.STMTFILE_DD_NAME).isEqualTo("STMTFILE");
            assertThat(StatementWriter.HTMLFILE_DD_NAME).isEqualTo("HTMLFILE");
            assertThat(StatementWriter.CONTEXT_KEY_TEXT_OBJECT).isEqualTo("carddemo.statement.text.objectKey");
            assertThat(StatementWriter.CONTEXT_KEY_HTML_OBJECT).isEqualTo("carddemo.statement.html.objectKey");
        }
    }

    @Nested
    @DisplayName("2. HIGH: the per-account output lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("a line written with no statement open abends as CBSTM03A")
        void aLineWithNoStatementOpenAbends() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeStatementLine("anything"))
                    .satisfies(failure -> {
                        assertThat(failure.getAbendCulprit()).isEqualTo("CBSTM03A");
                        assertThat(failure.getAbendCode())
                                .isEqualTo(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE));
                        assertThat(failure.getAbendReason())
                                .isEqualTo("no statement is open; call openStatementOutputs before "
                                        + "emitting a record");
                    });
        }

        @Test
        @DisplayName("a persisted statement leaves the processed-records counter exactly where it was")
        void aPersistedStatementMovesNoCounter() throws Exception {
            // The owner registers the four series eagerly against the shared registry, so the counter below
            // exists before anything is written. One increment stands for one DALYTRAN record that the daily
            // posting run genuinely handled - the only unit this untagged series carries.
            MetricsConfig owner = new MetricsConfig(meterRegistry);
            owner.countRecordProcessed();

            writer.write(new Chunk<>(List.of(statement())));

            // Finding H-09, severity High, RESOLVED: the two statement objects were emitted (asserted by the
            // key and geometry groups below) and the counter still reports the one real record. Before the
            // fix this read 2.0, conflating a rendering of already-counted transactions with the records
            // themselves and inflating sum(carddemo_batch_records_processed_total) on every statement run.
            assertThat(processedCount()).isEqualTo(1.0d);
            assertThat(uploadedKeys()).hasSize(2);
        }

        @Test
        @DisplayName("an HTML fragment with no statement open abends against the HTMLFILE DD")
        void anHtmlFragmentWithNoStatementOpenAbends() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeHtmlFragment("<p>x</p>"))
                    .satisfies(failure -> assertThat(failure.getMessage()).contains("HTMLFILE WRITE"));
        }

        @Test
        @DisplayName("a text record, a markup record, a flush and a close all require an open statement")
        void everyEmissionRequiresAnOpenStatement() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeStatementLine("a text record"));
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeHtmlFragment("<p>a markup record</p>"));
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.flushStatementOutputs());
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.closeStatementOutputs());
        }

        @Test
        @DisplayName("opening a second statement while one is open abends rather than interleaving records")
        void openingTwiceAbends() {
            open();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.openStatementOutputs("00000000051", MONTH))
                    .satisfies(failure -> assertThat(failure.getAbendReason())
                            .isEqualTo("a statement for another account is still open; close it before "
                                    + "opening the next"));
        }

        @Test
        @DisplayName("a null account identifier or statement month abends, naming which segment failed")
        void aNullKeySegmentIsRefused() {
            // Both segments come from a record, so an unusable value is a data defect in a batch step and
            // reaches 9999-ABEND-PROGRAM rather than surfacing as an unchecked argument error. The
            // distinction is kept: a negative generation, which can only be a caller defect, is still an
            // IllegalArgumentException - see aNegativeGenerationIsRefused below.
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.openStatementOutputs(null, MONTH))
                    .withMessageContaining("the account identifier must be exactly 11 ASCII digits");
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.openStatementOutputs(ACCOUNT_ID, null))
                    .withMessageContaining("the statement month must not be null");
        }

        @ParameterizedTest(name = "a blank segment of [{0}] is refused")
        @ValueSource(strings = {"", "  "})
        @DisplayName("a blank account identifier is refused, because it becomes part of the object key")
        void aBlankKeySegmentIsRefused(final String accountId) {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.openStatementOutputs(accountId, MONTH))
                    .withMessageContaining("the account identifier must be exactly 11 ASCII digits");
        }

        @Test
        @DisplayName("a blank statement month is refused for the same reason")
        void aBlankMonthIsRefused() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.openStatementOutputs(ACCOUNT_ID, " "))
                    .withMessageContaining("the statement month must be a canonical uuuu-MM");
        }

        @Test
        @DisplayName("a negative generation is refused, because a GDG generation is never negative")
        void aNegativeGenerationIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> writer.openStatementOutputs(ACCOUNT_ID, MONTH, -1L))
                    .withMessage("generation must not be negative but was -1");
        }

        @Test
        @DisplayName("closing flushes, then releases the writer for the next account")
        void closingReleasesTheWriter() {
            open();
            writer.writeStatementLine("first account");
            writer.closeStatementOutputs();

            writer.openStatementOutputs("00000000051", MONTH);
            writer.writeStatementLine("second account");
            Map<String, String> keys = writer.closeStatementOutputs();

            assertThat(keys).containsKeys("STMTFILE", "HTMLFILE");
            assertThat(uploadedKeys())
                    .as("each account gets its own pair of objects")
                    .hasSize(4);
        }

        @Test
        @DisplayName("a second account starts with an empty record area, never the previous account's records")
        void aSecondAccountStartsEmpty() {
            open();
            writer.writeStatementLine("first account");
            writer.closeStatementOutputs();
            Mockito.reset(s3Template);

            writer.openStatementOutputs("00000000051", MONTH);
            writer.writeStatementLine("second account");
            writer.flushStatementOutputs();

            List<String> records = textRecords();
            assertThat(records)
                    .as("carrying a record over would attribute it to the wrong account")
                    .hasSize(1);
            assertThat(records.getFirst()).startsWith("second account");
        }

        @Test
        @DisplayName("opening resets the previously created keys, so a partial run cannot be mistaken for done")
        void openingResetsTheCreatedKeys() {
            open();
            writer.writeStatementLine("first account");
            writer.closeStatementOutputs();
            assertThat(writer.createdObjectKeys()).isNotEmpty();

            writer.openStatementOutputs("00000000051", MONTH);

            assertThat(writer.createdObjectKeys()).isEmpty();
        }
    }

    @Nested
    @DisplayName("3. BLOCKER: 80 bytes for the text record, 100 for the HTML record, never harmonised")
    class RecordGeometry {

        @Test
        @DisplayName("a short text line is padded to exactly 80 characters")
        void aShortTextLineIsPaddedTo80() {
            open();
            writer.writeStatementLine("short");
            writer.flushStatementOutputs();

            assertThat(textRecords()).containsExactly("short" + " ".repeat(TEXT_LENGTH - 5));
        }

        @Test
        @DisplayName("a short HTML fragment is padded to exactly 100 characters")
        void aShortHtmlFragmentIsPaddedTo100() {
            open();
            writer.writeHtmlFragment("<p>x</p>");
            writer.flushStatementOutputs();

            assertThat(htmlRecords()).containsExactly("<p>x</p>" + " ".repeat(HTML_LENGTH - 8));
        }

        @Test
        @DisplayName("a text line of exactly 80 characters is emitted unchanged")
        void anExactWidthTextLineIsUnchanged() {
            open();
            writer.writeStatementLine("X".repeat(TEXT_LENGTH));
            writer.flushStatementOutputs();

            assertThat(textRecords()).containsExactly("X".repeat(TEXT_LENGTH));
        }

        @Test
        @DisplayName("a text line of 81 characters abends rather than being truncated")
        void anOverWideTextLineAbends() {
            open();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeStatementLine("X".repeat(TEXT_LENGTH + 1)))
                    .satisfies(failure -> assertThat(failure.getAbendReason())
                            .isEqualTo("a composed record is 81 characters but the record area is exactly 80"));
        }

        @Test
        @DisplayName("an HTML fragment of 101 characters abends rather than being truncated")
        void anOverWideHtmlFragmentAbends() {
            open();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeHtmlFragment("X".repeat(HTML_LENGTH + 1)))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .contains("a composed record is 101 characters but the record area is exactly 100")
                            .contains("HTMLFILE"));
        }

        @Test
        @DisplayName("the over-wide diagnostic reports the length but never the record content")
        void theOverWideDiagnosticWithholdsTheContent() {
            open();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeStatementLine("SECRETNAME".repeat(9)))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .as("Rule 1 clause D1 keeps statement content out of diagnostics")
                            .doesNotContain("SECRETNAME"));
        }

        @Test
        @DisplayName("a null line becomes a full record of spaces, never a zero-length record")
        void aNullLineBecomesSpaces() {
            open();
            writer.writeStatementLine(null);
            writer.flushStatementOutputs();

            assertThat(textRecords()).containsExactly(" ".repeat(TEXT_LENGTH));
        }

        @Test
        @DisplayName("the text object is a whole number of 80-byte records and the HTML object of 100")
        void bothObjectsAreWholeNumbersOfRecords() throws Exception {
            open();
            writer.writeStatementLine("a text record");
            writer.writeHtmlFragment("<p>a markup record</p>");
            writer.writeStatementLine("another text record");
            writer.flushStatementOutputs();

            assertThat(payloadOf("STATEMNT.PS").length() % TEXT_LENGTH).isZero();
            assertThat(payloadOf("STATEMNT.HTML").length() % HTML_LENGTH).isZero();
        }

        @Test
        @DisplayName("a Latin-1 character outside ASCII still occupies exactly one byte")
        void aLatin1CharacterOccupiesOneByte() {
            open();
            writer.writeStatementLine("Ni\u00f1o");
            writer.flushStatementOutputs();

            assertThat(metadataOf("STATEMNT.PS").getContentLength())
                    .as("a multi-byte charset would make the object longer than its record count")
                    .isEqualTo(Long.valueOf(TEXT_LENGTH));
        }
    }

    // ====================================================================================================
    // 4. 5000-CREATE-STATEMENT is no longer emitted here. This writer receives already-composed lines: the
    // sixteen header records, the STRING delimiter behaviour, the doubled rule lines, the basic-details
    // block and the eighty-character column headings are all built by
    // com.cardemo.batch.processors.StatementProcessor and are asserted in StatementProcessorTest section 5
    // ("process: the dual 80-byte text and 100-byte HTML emission"), against the same source paragraphs.
    // Restating them here would assert a method this class no longer declares.
    // ====================================================================================================

    // ====================================================================================================
    // 5. 6000-WRITE-TRANS is likewise composed rather than emitted here. The detail line and its eleven
    // HTML records are asserted in StatementProcessorTest section 5, which drives the composition end to
    // end; this class asserts only that whatever lines it is handed reach object storage at their exact
    // declared widths.
    // ====================================================================================================

    // ====================================================================================================
    // 6. The two edited-amount masks belong to the composition too. The zero-suppressed Z(9).99- of the
    // transaction and total amounts and the zero-filled 9(9).99- of the balance - including the over-wide,
    // absent and negative cases and the half-even tie - are asserted in StatementProcessorTest section 5.
    // ====================================================================================================

    @Nested
    @DisplayName("7. The HTML fragment table that replaces the 88-level constant set")
    class HtmlFragmentTable {

        @Test
        @DisplayName("every fragment fits the 100-byte record area")
        void everyFragmentFitsTheRecordArea() {
            assertThat(writer.htmlFragments().values())
                    .isNotEmpty()
                    .allSatisfy(fragment -> assertThat(fragment.length())
                            .as("a fragment longer than the record area could never be written")
                            .isLessThanOrEqualTo(HTML_LENGTH));
        }

        @Test
        @DisplayName("the table is unmodifiable, so no caller can rewrite the legacy markup")
        void theTableIsUnmodifiable() {
            Map<String, String> fragments = writer.htmlFragments();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> fragments.put("HTML_L01", "<p>tampered</p>"));
        }

        @Test
        @DisplayName("the table carries the document scaffolding the source declares as literals")
        void theTableCarriesTheScaffolding() {
            Map<String, String> fragments = writer.htmlFragments();

            assertThat(fragments).containsEntry("HTML_L01", "<!DOCTYPE html>");
            assertThat(fragments).containsEntry("HTML_L04", "<meta charset=\"utf-8\">");
            assertThat(fragments).containsKeys("HTML_LTRS", "HTML_LTRE", "HTML_LTDS", "HTML_LTDE");
        }

        @Test
        @DisplayName("the table is stable across calls, because the markup is constant")
        void theTableIsStableAcrossCalls() {
            assertThat(writer.htmlFragments()).isEqualTo(writer.htmlFragments());
        }
    }

    @Nested
    @DisplayName("8. HIGH: the object keys that replace the GDG generation, and the step context")
    class ObjectKeysAndContext {

        @Test
        @DisplayName("both keys carry the account, the month, the generation, the statement ordinal and the "
                + "object name")
        void bothKeysCarryTheAccountMonthAndGeneration() {
            writer.openStatementOutputs(ACCOUNT_ID, MONTH, 7L);
            writer.writeStatementLine("x");
            writer.flushStatementOutputs();

            assertThat(uploadedKeys()).containsExactly(
                    String.format(Locale.ROOT,
                            "statements/account=%s/month=%s/generation=%019d/statement=%019d/STATEMNT.PS",
                            ACCOUNT_ID, MONTH, 7L, 1L),
                    String.format(Locale.ROOT,
                            "statements/account=%s/month=%s/generation=%019d/statement=%019d/STATEMNT.HTML",
                            ACCOUNT_ID, MONTH, 7L, 1L));
        }

        @Test
        @DisplayName("F-01: two statements for the same account and month occupy two distinct key pairs")
        void twoStatementsForOneAccountOccupyDistinctKeys() {
            writer.openStatementOutputs(ACCOUNT_ID, MONTH, 7L);
            writer.writeStatementLine("first");
            writer.closeStatementOutputs();
            writer.openStatementOutputs(ACCOUNT_ID, MONTH, 7L);
            writer.writeStatementLine("second");
            writer.closeStatementOutputs();

            assertThat(uploadedKeys())
                    .as("an account holds as many statements as it holds cards, because CARDXREF.VSAM.AIX is "
                            + "a non-unique alternate index on the account identifier; four distinct keys "
                            + "means neither statement was overwritten")
                    .doesNotHaveDuplicates()
                    .hasSize(4);
            assertThat(uploadedKeys().get(0))
                    .contains(String.format(Locale.ROOT, "statement=%019d/", 1L));
            assertThat(uploadedKeys().get(2))
                    .as("the ordinal advances with the second open, and every other segment is unchanged")
                    .contains(String.format(Locale.ROOT, "statement=%019d/", 2L));
        }

        @Test
        @DisplayName("the content types distinguish the two outputs")
        void theContentTypesDistinguishTheOutputs() {
            open();
            writer.writeStatementLine("x");
            writer.writeHtmlFragment("<p>x</p>");
            writer.flushStatementOutputs();

            assertThat(metadataOf("STATEMNT.PS").getContentType())
                    .as("the charset parameter is not decoration: the records are ISO-8859-1 and a bare "
                            + "text media type is decoded with the recipient's default")
                    .isEqualTo("text/plain; charset=ISO-8859-1");
            assertThat(metadataOf("STATEMNT.HTML").getContentType())
                    .as("the markup object carries the same charset parameter and for the same reason")
                    .isEqualTo("text/html; charset=ISO-8859-1");
            assertThat(metadataOf("STATEMNT.PS").getContentLength()).isEqualTo(Long.valueOf(TEXT_LENGTH));
            assertThat(metadataOf("STATEMNT.HTML").getContentLength()).isEqualTo(Long.valueOf(HTML_LENGTH));
        }

        @Test
        @DisplayName("the generation defaults to zero outside a step")
        void theGenerationDefaultsToZeroOutsideAStep() {
            open();
            writer.writeStatementLine("x");
            writer.flushStatementOutputs();

            assertThat(uploadedKeys().getFirst())
                    .contains(String.format(Locale.ROOT, "generation=%019d/", 0L));
        }

        @Test
        @DisplayName("beforeStep adopts the job instance identifier as the generation")
        void beforeStepAdoptsTheJobInstance() {
            writer.beforeStep(stepExecution(42L));
            open();
            writer.writeStatementLine("x");
            writer.flushStatementOutputs();

            assertThat(uploadedKeys().getFirst())
                    .as("the GDG generation becomes the job instance, so a re-run cannot overwrite")
                    .contains(String.format(Locale.ROOT, "generation=%019d/", 42L));
        }

        @Test
        @DisplayName("beforeStep tolerates an execution with no job instance and keeps the default")
        void beforeStepToleratesAnEmptyExecution() {
            writer.beforeStep(null);
            writer.beforeStep(new StepExecution("statementGenerationStep",
                    new JobExecution(Long.valueOf(11L))));
            open();
            writer.writeStatementLine("x");
            writer.flushStatementOutputs();

            assertThat(uploadedKeys().getFirst())
                    .contains(String.format(Locale.ROOT, "generation=%019d/", 0L));
        }

        @Test
        @DisplayName("an explicit generation overrides the one captured from the step")
        void anExplicitGenerationOverridesTheStep() {
            writer.beforeStep(stepExecution(42L));
            writer.openStatementOutputs(ACCOUNT_ID, MONTH, 9L);
            writer.writeStatementLine("x");
            writer.flushStatementOutputs();

            assertThat(uploadedKeys().getFirst())
                    .contains(String.format(Locale.ROOT, "generation=%019d/", 9L));
        }

        @Test
        @DisplayName("no keys are reported before the flush, so a partial statement is never advertised")
        void noKeysAreReportedBeforeTheFlush() {
            open();
            writer.writeStatementLine("x");

            assertThat(writer.createdObjectKeys()).isEmpty();
        }

        @Test
        @DisplayName("both keys are reported after the flush, in DD-name order")
        void bothKeysAreReportedAfterTheFlush() {
            open();
            writer.writeStatementLine("x");

            assertThat(writer.flushStatementOutputs())
                    .containsOnlyKeys("STMTFILE", "HTMLFILE")
                    .containsEntry("STMTFILE", uploadedKeys().getFirst());
        }

        @Test
        @DisplayName("a second flush uploads nothing more, because the outputs are already emitted")
        void aSecondFlushUploadsNothingMore() {
            open();
            writer.writeStatementLine("x");
            Map<String, String> first = writer.flushStatementOutputs();
            Map<String, String> second = writer.flushStatementOutputs();

            assertThat(second).isEqualTo(first);
            Mockito.verify(s3Template, Mockito.times(2)).upload(Mockito.eq(BUCKET), Mockito.anyString(),
                    Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
        }

        @Test
        @DisplayName("close flushes when the flush has not happened yet")
        void closeFlushesWhenNeeded() {
            open();
            writer.writeStatementLine("x");

            assertThat(writer.closeStatementOutputs()).containsKeys("STMTFILE", "HTMLFILE");
            Mockito.verify(s3Template, Mockito.times(2)).upload(Mockito.eq(BUCKET), Mockito.anyString(),
                    Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
        }

        @Test
        @DisplayName("afterStep publishes both keys for the next step to read")
        void afterStepPublishesBothKeys() {
            StepExecution execution = stepExecution(42L);
            writer.beforeStep(execution);
            open();
            writer.writeStatementLine("x");
            Map<String, String> keys = writer.closeStatementOutputs();

            ExitStatus status = writer.afterStep(execution);

            assertThat(status)
                    .as("the listener publishes and never overrides the step's own exit status")
                    .isNull();
            assertThat(execution.getExecutionContext().getString(StatementWriter.CONTEXT_KEY_TEXT_OBJECT))
                    .isEqualTo(keys.get("STMTFILE"));
            assertThat(execution.getExecutionContext().getString(StatementWriter.CONTEXT_KEY_HTML_OBJECT))
                    .isEqualTo(keys.get("HTMLFILE"));
        }

        @Test
        @DisplayName("afterStep publishes nothing when no statement was emitted")
        void afterStepPublishesNothingWithoutAStatement() {
            StepExecution execution = stepExecution(42L);

            assertThat(writer.afterStep(execution)).isNull();
            assertThat(execution.getExecutionContext().containsKey(StatementWriter.CONTEXT_KEY_TEXT_OBJECT))
                    .isFalse();
        }

        @Test
        @DisplayName("afterStep tolerates a null execution")
        void afterStepToleratesANullExecution() {
            assertThat(writer.afterStep(null)).isNull();
        }

        @Test
        @DisplayName("the flush announces the record counts of both outputs")
        void theFlushAnnouncesTheRecordCounts() {
            open();
            writer.writeStatementLine("one text record");
            writer.writeStatementLine("another text record");
            writer.writeHtmlFragment("<p>a markup record</p>");
            writer.flushStatementOutputs();

            // The counts are derived from the accumulated buffer lengths divided by the record widths, so
            // they are a property of what this writer emitted rather than of what any composer produced.
            assertThat(loggedMessages())
                    .anyMatch(message -> message.startsWith("Emitted statement 1 objects for generation 0")
                            && message.contains("2 text records")
                            && message.contains("1 html records"));
        }
    }

    @Nested
    @DisplayName("9. MEDIUM: a rejected upload surfaces as a typed I/O failure, never as a delivered statement")
    class UploadFailure {

        @Test
        @DisplayName("a rejected text upload maps the '9x' status to a file access exception")
        void aRejectedUploadMapsToAFileAccessException() {
            Mockito.when(s3Template.upload(Mockito.anyString(), Mockito.anyString(),
                            Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class)))
                    .thenThrow(new IllegalStateException("the bucket is unreachable"));
            open();
            writer.writeStatementLine("x");

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> writer.flushStatementOutputs())
                    .satisfies(failure -> assertThat(failure.getCause())
                            .as("Rule 1 clause B4 preserves the root cause")
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("the bucket is unreachable"));
        }

        @Test
        @DisplayName("the failure diagnostic names the DD, the operation, the byte count and the status")
        void theFailureDiagnosticNamesEverything() {
            Mockito.when(s3Template.upload(Mockito.anyString(), Mockito.anyString(),
                            Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class)))
                    .thenThrow(new IllegalStateException("the bucket is unreachable"));
            open();
            writer.writeStatementLine("x");

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> writer.flushStatementOutputs());

            assertThat(loggedMessages()).anyMatch(message -> message.startsWith(
                    "Object storage rejected the STMTFILE WRITE of 80 bytes")
                    && message.contains("FILE STATUS IS: NNNN9048"));
        }

        @Test
        @DisplayName("a rejected text upload never attempts the HTML upload, so no half statement is stored")
        void aRejectedTextUploadStopsTheHtmlUpload() {
            Mockito.when(s3Template.upload(Mockito.anyString(), Mockito.anyString(),
                            Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class)))
                    .thenThrow(new IllegalStateException("the bucket is unreachable"));
            open();
            writer.writeStatementLine("x");

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> writer.flushStatementOutputs());

            Mockito.verify(s3Template, Mockito.times(1)).upload(Mockito.eq(BUCKET), Mockito.anyString(),
                    Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
        }

        @Test
        @DisplayName("a rejected upload reports no created keys, so nothing downstream reads a phantom object")
        void aRejectedUploadReportsNoKeys() {
            Mockito.when(s3Template.upload(Mockito.anyString(), Mockito.anyString(),
                            Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class)))
                    .thenThrow(new IllegalStateException("the bucket is unreachable"));
            open();
            writer.writeStatementLine("x");

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> writer.flushStatementOutputs());

            assertThat(writer.createdObjectKeys()).isEmpty();
        }

        @Test
        @DisplayName("a rejected HTML upload is reported against the HTMLFILE DD, not the text DD")
        void aRejectedHtmlUploadNamesItsOwnDd() {
            Mockito.when(s3Template.upload(Mockito.eq(BUCKET), Mockito.endsWith("STATEMNT.HTML"),
                            Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class)))
                    .thenThrow(new IllegalStateException("the bucket rejected the html object"));
            open();
            writer.writeStatementLine("x");

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> writer.flushStatementOutputs());

            assertThat(loggedMessages())
                    .anyMatch(message -> message.startsWith("Object storage rejected the HTMLFILE WRITE"));
        }
    }

    @Nested
    @DisplayName("10. m-03: a statement key cannot travel inside a preserved cause")
    class KeyBearingCauseSanitisation {

        /**
         * Creates the sanitisation group.
         *
         * <p>Declared explicitly because the enclosing class declares its own constructors.
         */
        KeyBearingCauseSanitisation() {
            // Intentionally empty; each test builds the throwable it needs.
        }

        @Test
        @DisplayName("a key-bearing upload failure is raised with a cause that names no account digits")
        void aKeyBearingUploadFailureIsSanitised() {
            // The shape an object store really produces: the key inside the SDK's own message. This is the
            // path the finding is about, because this throwable used to be preserved verbatim and both the
            // framework's step-failure logging and the EXIT_MESSAGE column render whatever cause it carries.
            Mockito.when(s3Template.upload(Mockito.anyString(), Mockito.anyString(),
                            Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class)))
                    .thenThrow(new IllegalStateException("Access Denied (Bucket: " + BUCKET + ", Key: "
                            + "statements/account=" + ACCOUNT_ID + "/month=" + MONTH
                            + "/generation=0000000000000000000/statement=0000000000000000001/STATEMNT.PS)"));
            open();
            writer.writeStatementLine("x");

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> writer.flushStatementOutputs())
                    .satisfies(failure -> {
                        assertThat(renderChainOf(failure))
                                .as("no rendering of the raised failure may carry the account digits")
                                .doesNotContain(ACCOUNT_ID);
                        assertThat(failure.getCause())
                                .as("Rule 1 clause B still holds: a cause is present, so nothing is swallowed")
                                .isInstanceOf(StatementWriter.SanitizedCause.class);
                        assertThat(failure.getCause().getMessage())
                                .as("the classification survives as the original type name, and the text "
                                        + "survives with only the account digits replaced")
                                .startsWith(IllegalStateException.class.getName() + ": ")
                                .contains("Access Denied")
                                .contains("Bucket: " + BUCKET)
                                .contains("statements/account="
                                        + StatementWriter.ACCOUNT_SEGMENT_REDACTION
                                        + "/month=" + MONTH)
                                .contains("/statement=0000000000000000001/STATEMNT.PS");
                    });
        }

        @Test
        @DisplayName("the sanitised cause keeps the original stack trace, so the fault location survives")
        void theSanitisedCauseKeepsTheStackTrace() {
            IllegalStateException original = new IllegalStateException(
                    "denied for statements/account=" + ACCOUNT_ID + "/month=" + MONTH + "/STATEMNT.PS");

            Throwable sanitised = StatementWriter.sanitizedCause(original);

            assertThat(sanitised).isNotSameAs(original);
            assertThat(sanitised.getStackTrace())
                    .as("a stack frame is a class, a method and a line, so it cannot hold a key - and it is "
                            + "the one part of a cause that says WHERE the failure happened")
                    .isEqualTo(original.getStackTrace());
        }

        @Test
        @DisplayName("a cause that names no key is returned unchanged, so its type is not given up needlessly")
        void aCauseWithoutAKeyIsReturnedUnchanged() {
            // Preserving the instance preserves the type, which is what lets a handler test instanceof or
            // read a status code from an SDK exception. The substitution happens only where keeping the
            // instance would mean keeping the disclosure.
            IllegalStateException original = new IllegalStateException("the bucket is unreachable");

            assertThat(StatementWriter.sanitizedCause(original)).isSameAs(original);
            assertThat(StatementWriter.sanitizedCause(null)).isNull();
        }

        @Test
        @DisplayName("a key three causes down is removed too, and every type name in the chain survives")
        void aKeyDeepInTheChainIsRemoved() {
            IllegalStateException root = new IllegalStateException(
                    "PUT statements/account=" + ACCOUNT_ID + "/month=" + MONTH + "/STATEMNT.PS refused");
            IllegalArgumentException middle = new IllegalArgumentException("request failed", root);
            RuntimeException top = new RuntimeException("unable to execute HTTP request", middle);

            Throwable sanitised = StatementWriter.sanitizedCause(top);

            assertThat(renderChainOf(sanitised)).doesNotContain(ACCOUNT_ID);
            assertThat(sanitised.getMessage())
                    .startsWith(RuntimeException.class.getName() + ": unable to execute HTTP request");
            assertThat(sanitised.getCause().getMessage())
                    .startsWith(IllegalArgumentException.class.getName() + ": request failed");
            assertThat(sanitised.getCause().getCause().getMessage())
                    .startsWith(IllegalStateException.class.getName() + ": PUT statements/account="
                            + StatementWriter.ACCOUNT_SEGMENT_REDACTION)
                    .endsWith("refused");
            assertThat(sanitised.getCause().getCause().getCause())
                    .as("the chain ends where the original ended; no element is invented")
                    .isNull();
        }

        @Test
        @DisplayName("the sanitiser is checked against a key this writer really emitted, not a retyped one")
        void theSanitiserMatchesAKeyTheWriterActuallyEmitted() {
            // The anchor of both the sanitiser and the appender rule is the literal 'statements/account='.
            // Asserting it against a key captured from the upload boundary is what keeps the pattern
            // non-vacuous: if the key shape is ever renamed, this fails and names the sanitiser, whereas a
            // retyped literal would keep passing while both defences quietly stopped matching anything.
            writer.openStatementOutputs(ACCOUNT_ID, MONTH, 7L);
            writer.writeStatementLine("x");
            writer.flushStatementOutputs();

            assertThat(uploadedKeys()).isNotEmpty();
            for (String key : uploadedKeys()) {
                assertThat(StatementWriter.withoutStatementKeyAccount(key))
                        .as("emitted key [%s]", key)
                        .doesNotContain(ACCOUNT_ID)
                        .contains("statements/account=" + StatementWriter.ACCOUNT_SEGMENT_REDACTION + "/")
                        .endsWith(key.substring(key.indexOf("/month=")));
            }
        }

        @Test
        @DisplayName("a labelled account identifier in a cause is left alone, because that is diagnosis")
        void aLabelledAccountIdentifierIsNotTouched() {
            // The mirror of the masking configuration's own decision: ACCT-ID is published as a labelled
            // field on purpose and Gate 1 compares those lines byte for byte. Only the KEY form is removed.
            String diagnostic = "posting failed for accountId=" + ACCOUNT_ID;

            assertThat(StatementWriter.withoutStatementKeyAccount(diagnostic)).isEqualTo(diagnostic);
            assertThat(StatementWriter.withoutStatementKeyAccount(null)).isNull();
        }

        /**
         * Renders a throwable and every cause beneath it into one string, the way a log appender or the batch
         * metadata would.
         *
         * <p>Messages and stack traces both, because an assertion that read only the top message would pass
         * while the key sat one cause down - which is exactly the defect this group exists to prevent.
         *
         * @param failure the throwable to render, never {@code null}
         * @return the concatenated rendering, never {@code null}
         */
        private String renderChainOf(final Throwable failure) {
            StringWriter rendered = new StringWriter();
            try (PrintWriter writer = new PrintWriter(rendered)) {
                failure.printStackTrace(writer);
            }
            return rendered.toString();
        }
    }
}
