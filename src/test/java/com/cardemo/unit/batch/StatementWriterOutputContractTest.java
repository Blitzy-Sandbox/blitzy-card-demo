/*
 * ******************************************************************
 * Program     : StatementWriterOutputContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the single processor-to-writer contract for
 *               statement generation: the writer persists lines the
 *               processor rendered and composes none of its own, it
 *               emits one text object and one HTML object per
 *               statement at exactly 80 and 100 characters per
 *               record, it refuses any object-key segment that is not
 *               eleven ASCII digits or a canonical uuuu-MM, and it
 *               holds no per-execution state outside the step it was
 *               created for.
 * Source      : app/cbl/CBSTM03A.CBL:L45       (FD-STMTFILE-REC X(80))
 *               app/cbl/CBSTM03A.CBL:L47       (FD-HTMLFILE-REC X(100))
 *               app/cbl/CBSTM03A.CBL:L149      (HTML-FIXED-LN X(100))
 *               app/cbl/CBSTM03A.CBL:L293      (OPEN OUTPUT both)
 *               app/cbl/CBSTM03A.CBL:L339      (CLOSE both)
 *               app/cbl/CBSTM03A.CBL:L921      (9999-ABEND-PROGRAM)
 *               app/jcl/CREASTMT.JCL:L94       (LRECL=80 / LRECL=100)
 *               app/cpy/CVACT01Y.cpy:L5        (ACCT-ID PIC 9(11)) @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Resource;
import io.awspring.cloud.s3.S3Template;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.item.Chunk;

@DisplayName("StatementWriter: the persistence half of the statement contract")
class StatementWriterOutputContractTest {

    /** A valid eleven-digit account identifier. */
    private static final String ACCOUNT_ID = "00000000001";

    /** The instant the fixed clock reports, which fixes the statement month at 2024-03. */
    private static final Instant FIXED_INSTANT = Instant.parse("2024-03-15T10:30:00Z");

    /** The month segment the fixed clock yields. */
    private static final String FIXED_MONTH = "2024-03";

    /**
     * Records the uploads the writer performs, in order.
     *
     * <p>Synchronised rather than a bare {@code ArrayList} because
     * {@code StepScopedState#concurrentStepExecutionsAreIsolated()}
     * drives eight writers from eight threads at once, and every one of them appends here from inside the
     * recording answer stubbed on {@link #s3Template}. Concurrent {@code ArrayList.add} interleaves the size
     * update with the element store and silently drops elements: this list came back with 186 of 192 uploads on
     * one run while passing on others, which presented as a flaky test rather than as the harness defect it was.
     * The production writer was never implicated - the per-execution assertions in that test, that each writer
     * counted exactly its own statements and emitted only its own account prefix, passed on the failing run too.
     * A synchronised wrapper is sufficient and is preferred to a copy-on-write list because insertion order must
     * be preserved for the index-based assertions elsewhere in this class, and because every reader here runs
     * after {@code Future.get()} has established a happens-before edge, so no iteration ever overlaps a write.
     */
    private final List<Upload> uploads = java.util.Collections.synchronizedList(new ArrayList<>());

    /** The storage boundary, recording rather than calling out. */
    private S3Template s3Template;

    /** The metric registry the writer counts into. */
    private MeterRegistry meterRegistry;

    /** The writer under test. */
    private StatementWriter writer;

    /**
     * One recorded upload.
     *
     * @param bucket the target bucket
     * @param key the object key
     * @param content the object content as ISO-8859-1 text
     */
    private record Upload(String bucket, String key, String content) {
    }

    @BeforeEach
    void setUp() {
        this.uploads.clear();
        this.s3Template = Mockito.mock(S3Template.class);
        Mockito.when(this.s3Template.upload(Mockito.anyString(), Mockito.anyString(),
                        Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class)))
                .thenAnswer(invocation -> {
                    String bucket = invocation.getArgument(0);
                    String key = invocation.getArgument(1);
                    InputStream body = invocation.getArgument(2);
                    this.uploads.add(new Upload(bucket, key, readAll(body)));
                    return Mockito.mock(S3Resource.class);
                });
        this.meterRegistry = new SimpleMeterRegistry();
        this.writer = newWriter();
    }

    /**
     * Builds a writer over the recording storage boundary and a fixed clock.
     *
     * @return a writer whose statement month is already derived
     */
    private StatementWriter newWriter() {
        StatementWriter created = new StatementWriter(this.s3Template,
                new MetricsConfig(this.meterRegistry), new FileStatusMapper(),
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), "carddemo-statements");
        // beforeStep derives the month and resets the counter. A null execution is the documented
        // directly-driven form, which leaves the generation at its initial value.
        created.beforeStep(null);
        return created;
    }

    /**
     * Reads a stream fully as ISO-8859-1 text, the record charset.
     *
     * @param stream the stream to drain
     * @return the content
     * @throws IOException if the stream cannot be read
     */
    private static String readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        stream.transferTo(buffer);
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }

    /**
     * Builds a statement whose lines are already at their declared widths.
     *
     * @param accountId the account identifier
     * @param textLineCount how many 80-character text records to carry
     * @param htmlLineCount how many 100-character HTML records to carry
     * @return a valid statement
     */
    private static StatementProcessor.Statement statement(String accountId, int textLineCount,
            int htmlLineCount) {
        List<String> textLines = new ArrayList<>();
        for (int index = 0; index < textLineCount; index++) {
            textLines.add(StatementRecordFixtures.fit("TEXT LINE " + index,
                    StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH));
        }
        List<String> htmlLines = new ArrayList<>();
        for (int index = 0; index < htmlLineCount; index++) {
            htmlLines.add(StatementRecordFixtures.fit("<p>HTML LINE " + index + "</p>",
                    StatementTransaction.STATEMENT_HTML_RECORD_LENGTH));
        }
        return new StatementProcessor.Statement(accountId, new BigDecimal("12.34"), textLines, htmlLines);
    }

    @Nested
    @DisplayName("H3 - the writer persists what the processor rendered and composes nothing")
    class ProcessorToWriterContract {

        @Test
        @DisplayName("one statement produces exactly one text object and one HTML object")
        void oneStatementProducesOneObjectPair() throws Exception {
            writer.write(Chunk.of(statement(ACCOUNT_ID, 3, 5)));

            assertThat(uploads).hasSize(2);
            assertThat(uploads.get(0).key()).endsWith("STATEMNT.PS");
            assertThat(uploads.get(1).key()).endsWith("STATEMNT.HTML");
            assertThat(writer.statementsWritten()).isEqualTo(1L);
        }

        @Test
        @DisplayName("the persisted objects carry exactly the lines the statement supplied, unaltered")
        void persistedContentIsExactlyTheSuppliedLines() throws Exception {
            StatementProcessor.Statement supplied = statement(ACCOUNT_ID, 2, 3);

            writer.write(Chunk.of(supplied));

            assertThat(uploads.get(0).content()).isEqualTo(String.join("", supplied.textLines()));
            assertThat(uploads.get(1).content()).isEqualTo(String.join("", supplied.htmlLines()));
        }

        @Test
        @DisplayName("record geometry is exact: the text object is a multiple of 80, the HTML of 100")
        void recordGeometryIsExact() throws Exception {
            writer.write(Chunk.of(statement(ACCOUNT_ID, 7, 11)));

            assertThat(uploads.get(0).content().length())
                    .isEqualTo(7 * StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH);
            assertThat(uploads.get(1).content().length())
                    .isEqualTo(11 * StatementTransaction.STATEMENT_HTML_RECORD_LENGTH);
            assertThat(writer.recordWidths()).containsExactly(
                    StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH,
                    StatementTransaction.STATEMENT_HTML_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the HTML fragment table has exactly one definition, owned by the processor")
        void htmlFragmentTableHasOneDefinition() {
            // The writer no longer carries its own copy of the 34 fragments: it delegates, so the two
            // views are the same map and cannot drift.
            assertThat(writer.htmlFragments()).isEqualTo(StatementProcessor.htmlFragments());
            assertThat(writer.htmlFragments()).isNotEmpty();
        }

        @Test
        @DisplayName("a chunk of several statements produces one object pair per statement")
        void chunkOfSeveralStatementsProducesOnePairEach() throws Exception {
            writer.write(Chunk.of(statement("00000000001", 1, 1),
                    statement("00000000002", 1, 1),
                    statement("00000000003", 1, 1)));

            assertThat(uploads).hasSize(6);
            assertThat(writer.statementsWritten()).isEqualTo(3L);
            // Each pair is filed under its own account segment.
            assertThat(uploads.get(0).key()).contains("account=00000000001");
            assertThat(uploads.get(2).key()).contains("account=00000000002");
            assertThat(uploads.get(4).key()).contains("account=00000000003");
        }

        @Test
        @DisplayName("a null or empty chunk writes nothing")
        void emptyChunkWritesNothing() throws Exception {
            writer.write(null);
            writer.write(Chunk.of());

            assertThat(uploads).isEmpty();
            assertThat(writer.statementsWritten()).isZero();
        }

        @Test
        @DisplayName("a line longer than its record area is refused rather than silently truncated")
        void overlongRecordIsRefused() {
            writer.openStatementOutputs(ACCOUNT_ID, FIXED_MONTH);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeStatementLine(
                            "X".repeat(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH + 1)));
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeHtmlFragment(
                            "X".repeat(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH + 1)));
        }

        @Test
        @DisplayName("a record emitted with no statement open is refused")
        void recordWithoutOpenStatementIsRefused() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeStatementLine("anything"));
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeHtmlFragment("anything"));
        }

        @Test
        @DisplayName("opening a second statement while one is open is refused, so records cannot leak between accounts")
        void openingTwiceIsRefused() {
            writer.openStatementOutputs(ACCOUNT_ID, FIXED_MONTH);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.openStatementOutputs("00000000002", FIXED_MONTH))
                    .satisfies(abend -> assertThat(abend.getAbendReason()).contains("still open"));
        }

        @Test
        @DisplayName("a flush followed by a close does not upload twice")
        void flushThenCloseUploadsOnce() {
            writer.openStatementOutputs(ACCOUNT_ID, FIXED_MONTH);
            writer.writeStatementLine("ONE");
            writer.writeHtmlFragment("<p>one</p>");

            Map<String, String> flushed = writer.flushStatementOutputs();
            Map<String, String> closed = writer.closeStatementOutputs();

            assertThat(uploads).hasSize(2);
            assertThat(closed).isEqualTo(flushed);
        }

        @Test
        @DisplayName("a null line and a null fragment become all-spaces records rather than failing")
        void nullRecordsBecomeSpaces() {
            writer.openStatementOutputs(ACCOUNT_ID, FIXED_MONTH);
            writer.writeStatementLine(null);
            writer.writeHtmlFragment(null);
            writer.closeStatementOutputs();

            assertThat(uploads.get(0).content())
                    .isEqualTo(" ".repeat(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH));
            assertThat(uploads.get(1).content())
                    .isEqualTo(" ".repeat(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH));
        }

        @Test
        @DisplayName("a storage rejection becomes a FileAccessException carrying no record content")
        void storageRejectionBecomesFileAccessException() {
            Mockito.reset(s3Template);
            Mockito.when(s3Template.upload(Mockito.anyString(), Mockito.anyString(),
                            Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class)))
                    .thenThrow(new IllegalStateException("bucket unavailable"));
            writer.openStatementOutputs(ACCOUNT_ID, FIXED_MONTH);
            writer.writeStatementLine("SENSITIVE CUSTOMER NAME");

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> writer.flushStatementOutputs())
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain("SENSITIVE CUSTOMER NAME"));
        }
    }

    @Nested
    @DisplayName("M4 - every object-key segment is validated and canonical")
    class ObjectKeyValidation {

        @Test
        @DisplayName("a valid pair composes the documented key shape")
        void validPairComposesDocumentedKey() {
            writer.openStatementOutputs(ACCOUNT_ID, FIXED_MONTH);
            writer.writeStatementLine("ONE");
            writer.closeStatementOutputs();

            assertThat(uploads.get(0).key()).isEqualTo(
                    "statements/account=00000000001/month=2024-03/generation=0000000000000000000/"
                            + "STATEMNT.PS");
            assertThat(uploads.get(1).key()).isEqualTo(
                    "statements/account=00000000001/month=2024-03/generation=0000000000000000000/"
                            + "STATEMNT.HTML");
        }

        @ParameterizedTest
        @DisplayName("an account segment that is not exactly eleven ASCII digits is refused")
        @ValueSource(strings = {
            "1234567890",
            "123456789012",
            "0000000000A",
            "00000000 01",
            "../../etcxx",
            "0000000/001",
            "00000000\n1",
            "０００００００００００",
            "-0000000001",
            "0000000001.",
            "",
        })
        void invalidAccountSegmentIsRefused(String candidate) {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.openStatementOutputs(candidate, FIXED_MONTH))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason()).contains("ASCII digits");
                        // The rejected value is never echoed, so a hostile segment cannot forge a log line.
                        assertThat(abend.getAbendReason()).doesNotContain(candidate.isEmpty() ? "\u0000"
                                : candidate);
                    });
        }

        @Test
        @DisplayName("a null account segment is refused")
        void nullAccountSegmentIsRefused() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.openStatementOutputs(null, FIXED_MONTH));
        }

        @ParameterizedTest
        @DisplayName("a month segment that is not a strictly resolved uuuu-MM is refused")
        @ValueSource(strings = {
            "2024-3",
            "2024-13",
            "2024-00",
            "24-03",
            "2024/03",
            "2024-03-15",
            "2024-03 ",
            " 2024-03",
            "2024-03\n",
            "../..",
            ".",
            "..",
            "",
        })
        void invalidMonthSegmentIsRefused(String candidate) {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.openStatementOutputs(ACCOUNT_ID, candidate))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .contains("canonical uuuu-MM"));
        }

        @Test
        @DisplayName("a null month segment is refused")
        void nullMonthSegmentIsRefused() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.openStatementOutputs(ACCOUNT_ID, null))
                    .satisfies(abend -> assertThat(abend.getAbendReason()).contains("must not be null"));
        }

        @Test
        @DisplayName("a valid month segment is accepted for every month of the year")
        void everyValidMonthIsAccepted() {
            for (int month = 1; month <= 12; month++) {
                StatementWriter fresh = newWriter();
                String candidate = String.format("2024-%02d", Integer.valueOf(month));
                assertThatNoException()
                        .isThrownBy(() -> fresh.openStatementOutputs(ACCOUNT_ID, candidate));
            }
        }

        @Test
        @DisplayName("a negative generation is refused")
        void negativeGenerationIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> writer.openStatementOutputs(ACCOUNT_ID, FIXED_MONTH, -1L));
        }

        @Test
        @DisplayName("the generation is zero-padded to a fixed width so keys sort lexicographically")
        void generationIsZeroPadded() {
            writer.openStatementOutputs(ACCOUNT_ID, FIXED_MONTH, 42L);
            writer.writeStatementLine("ONE");
            writer.closeStatementOutputs();

            assertThat(uploads.get(0).key())
                    .as("nineteen digits, the width of Long.MAX_VALUE, so lexicographic order equals "
                            + "numeric order across the whole domain of a job instance identifier")
                    .contains("generation=0000000000000000042");
        }
    }

    @Nested
    @DisplayName("M2 - no per-execution state survives outside the step the writer belongs to")
    class StepScopedState {

        @Test
        @DisplayName("the statement month is derived once per step, not once per statement")
        void monthIsDerivedOncePerStep() throws Exception {
            writer.write(Chunk.of(statement("00000000001", 1, 1), statement("00000000002", 1, 1)));

            assertThat(writer.statementMonth()).isEqualTo(FIXED_MONTH);
            assertThat(uploads).allSatisfy(upload ->
                    assertThat(upload.key()).contains("month=" + FIXED_MONTH));
        }

        @Test
        @DisplayName("beforeStep resets the per-execution counter, so a fresh instance starts at zero")
        void beforeStepResetsCounter() throws Exception {
            writer.write(Chunk.of(statement(ACCOUNT_ID, 1, 1)));
            assertThat(writer.statementsWritten()).isEqualTo(1L);

            writer.beforeStep(null);

            assertThat(writer.statementsWritten()).isZero();
        }

        @Test
        @DisplayName("two writers do not share buffers, keys or counters")
        void twoWritersShareNothing() throws Exception {
            StatementWriter first = newWriter();
            StatementWriter second = newWriter();

            first.write(Chunk.of(statement("00000000001", 2, 2)));

            assertThat(first.statementsWritten()).isEqualTo(1L);
            assertThat(second.statementsWritten()).isZero();
            assertThat(second.createdObjectKeys()).isEmpty();
            assertThat(first.createdObjectKeys()).isNotEmpty();
        }

        @Test
        @DisplayName("afterStep publishes the created keys and leaves the exit status untouched")
        void afterStepPublishesKeys() throws Exception {
            writer.write(Chunk.of(statement(ACCOUNT_ID, 1, 1)));

            org.springframework.batch.core.StepExecution stepExecution =
                    new org.springframework.batch.core.StepExecution("statementStep",
                            new org.springframework.batch.core.JobExecution(1L));

            ExitStatus exitStatus = writer.afterStep(stepExecution);

            assertThat(exitStatus).isNull();
            assertThat(stepExecution.getExecutionContext()
                    .getString(StatementWriter.CONTEXT_KEY_TEXT_OBJECT)).endsWith("STATEMNT.PS");
            assertThat(stepExecution.getExecutionContext()
                    .getString(StatementWriter.CONTEXT_KEY_HTML_OBJECT)).endsWith("STATEMNT.HTML");
        }

        @Test
        @DisplayName("afterStep tolerates a null execution and an execution with nothing written")
        void afterStepToleratesNothingWritten() {
            assertThat(writer.afterStep(null)).isNull();

            org.springframework.batch.core.StepExecution stepExecution =
                    new org.springframework.batch.core.StepExecution("statementStep",
                            new org.springframework.batch.core.JobExecution(2L));
            assertThat(writer.afterStep(stepExecution)).isNull();
            assertThat(stepExecution.getExecutionContext().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("toString discloses counts only, never statement content")
        void toStringDisclosesNoStatementContent() throws Exception {
            writer.write(Chunk.of(statement(ACCOUNT_ID, 1, 1)));

            assertThat(writer.toString()).doesNotContain("TEXT LINE");
            assertThat(writer.toString()).contains(FIXED_MONTH);
        }

        @Test
        @DisplayName("two step executions running concurrently keep isolated buffers, keys and counters")
        void concurrentStepExecutionsAreIsolated() throws Exception {
            // Step scope gives each execution its own writer, so this proves the property the scope
            // provides: two executions writing at the same time cannot see each other's buffers. A
            // singleton holding the same state could not pass this even with every method synchronized,
            // because synchronization orders access without partitioning it.
            int executions = 8;
            int statementsPerExecution = 12;
            List<StatementWriter> writers = new ArrayList<>();
            for (int index = 0; index < executions; index++) {
                writers.add(newWriter());
            }

            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(executions);
            try {
                java.util.concurrent.CountDownLatch startLine =
                        new java.util.concurrent.CountDownLatch(1);
                List<java.util.concurrent.Future<List<String>>> futures = new ArrayList<>();
                for (int index = 0; index < executions; index++) {
                    final int executionIndex = index;
                    final StatementWriter writer = writers.get(index);
                    futures.add(pool.submit(() -> {
                        startLine.await();
                        List<String> keys = new ArrayList<>();
                        for (int statement = 0; statement < statementsPerExecution; statement++) {
                            // A distinct account per execution and per statement, so any leaked buffer
                            // or key from another execution is immediately visible.
                            String accountId = StatementRecordFixtures.digits(
                                    (executionIndex + 1L) * 1000L + statement, 11);
                            writer.write(Chunk.of(statement(accountId, 2, 3)));
                            keys.addAll(writer.createdObjectKeys().values());
                        }
                        return keys;
                    }));
                }

                startLine.countDown();
                List<List<String>> perExecutionKeys = new ArrayList<>();
                for (java.util.concurrent.Future<List<String>> future : futures) {
                    perExecutionKeys.add(future.get(60L, java.util.concurrent.TimeUnit.SECONDS));
                }

                // Every execution wrote exactly its own statements and counted only its own.
                for (int index = 0; index < executions; index++) {
                    assertThat(writers.get(index).statementsWritten())
                            .withFailMessage("execution %d counted another execution's statements",
                                    Integer.valueOf(index))
                            .isEqualTo(statementsPerExecution);
                    String expectedPrefix = "account=" + StatementRecordFixtures.digits(
                            (index + 1L) * 1000L, 11).substring(0, 8);
                    assertThat(perExecutionKeys.get(index))
                            .allSatisfy(key -> assertThat(key).contains(expectedPrefix));
                }

                // Two objects per statement, across every execution, with no object written twice and
                // none lost to interleaving.
                assertThat(uploads).hasSize(executions * statementsPerExecution * 2);
                assertThat(uploads.stream().map(Upload::key).distinct().count())
                        .isEqualTo(executions * statementsPerExecution * 2L);

                // No object's content mixes two statements: every text object is exactly two records and
                // every HTML object exactly three, which is what each statement supplied.
                assertThat(uploads).allSatisfy(upload -> {
                    int width = upload.key().endsWith("STATEMNT.PS")
                            ? StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH
                            : StatementTransaction.STATEMENT_HTML_RECORD_LENGTH;
                    int expectedRecords = upload.key().endsWith("STATEMNT.PS") ? 2 : 3;
                    assertThat(upload.content().length()).isEqualTo(expectedRecords * width);
                });
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("createdObjectKeys is empty before the first flush and ordered afterwards")
        void createdObjectKeysOrdering() {
            assertThat(writer.createdObjectKeys()).isEmpty();

            writer.openStatementOutputs(ACCOUNT_ID, FIXED_MONTH);
            writer.writeStatementLine("ONE");
            Map<String, String> keys = writer.closeStatementOutputs();

            Map<String, String> expectedOrder = new LinkedHashMap<>();
            expectedOrder.put(StatementWriter.STMTFILE_DD_NAME, keys.get(StatementWriter.STMTFILE_DD_NAME));
            expectedOrder.put(StatementWriter.HTMLFILE_DD_NAME, keys.get(StatementWriter.HTMLFILE_DD_NAME));
            assertThat(keys).containsExactlyEntriesOf(expectedOrder);
        }
    }
}
