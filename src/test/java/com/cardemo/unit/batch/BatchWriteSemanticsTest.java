/*
 * ******************************************************************
 * Program     : BatchWriteSemanticsTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Pins the batch write semantics that the review found
 *               unenforced. Proves every guarded paragraph flushes
 *               inside its own guard so a store failure keeps its
 *               FILE STATUS / literal / return-code attribution;
 *               proves exactly one component owns the relational
 *               insert of 2900-WRITE-TRANSACTION-FILE; proves the
 *               interest processor persists and restores the
 *               control-break state a restart depends on; proves both
 *               writers hold their per-execution state in step scope;
 *               and records, by measurement rather than assertion,
 *               that no negative zero exists anywhere in the
 *               substrate the reject writer serves.
 * Source      : app/cbl/CBTRN02C.cbl:L510,L528,L554  (guarded writes)
 *               app/cbl/CBTRN02C.cbl:L562-L579       (2900, writer)
 *               app/cbl/CBACT04C.cbl:L167,L169,L170,
 *                                    L172,L173       (control break)
 *               app/cbl/CBACT04C.cbl:L195-L207       (break + flush)
 *               app/cbl/CBACT04C.cbl:L350-L370       (cycle reset)
 *               app/cbl/COCRDUPC.cbl:1477-1491       (guarded rewrite)
 *               app/data/ASCII/dailytran.txt         (300 rows) @ 7756d89
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

import com.cardemo.batch.processors.InterestCalculationProcessor;
import com.cardemo.batch.processors.TransactionPostingProcessor;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.batch.writers.TransactionWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStream;

@DisplayName("Batch write semantics: guard attribution, insert ownership, restart state and scope")
class BatchWriteSemanticsTest {

    /** Repository-root-relative path of the frozen daily-transaction fixture. */
    private static final Path DAILY_TRANSACTION_FIXTURE = Path.of("app", "data", "ASCII", "dailytran.txt");

    /** One-based start column of {@code DALYTRAN-AMT} in the 350-byte record, per app/cpy/CVTRA06Y.cpy. */
    private static final int AMOUNT_OFFSET = 132;

    /** Declared width of {@code DALYTRAN-AMT PIC S9(09)V99} as zoned decimal: 10 digits plus an overpunch. */
    private static final int AMOUNT_WIDTH = 11;

    /** Overpunch characters denoting a positive value, index equalling the final digit. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** Overpunch characters denoting a negative value, index equalling the final digit. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /**
     * Reads the frozen fixture as fixed-width rows.
     *
     * @return the 350-character rows, never empty
     */
    private static List<String> dailyTransactionRows() {
        try {
            List<String> rows = new ArrayList<>();
            for (String line : Files.readAllLines(DAILY_TRANSACTION_FIXTURE, StandardCharsets.ISO_8859_1)) {
                if (!line.isBlank()) {
                    rows.add(line);
                }
            }
            return rows;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the frozen fixture " + DAILY_TRANSACTION_FIXTURE
                    + " must be readable; it is the parity oracle for this assertion", unreadable);
        }
    }

    /**
     * Reads the source of a production class so a test can assert on the shape of a guarded block.
     *
     * @param type the class whose source to read
     * @return the source text
     */
    private static String sourceOf(Class<?> type) {
        Path path = Path.of("src", "main", "java")
                .resolve(type.getName().replace('.', '/') + ".java");
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the source of " + type.getName() + " must be readable", unreadable);
        }
    }

    @Nested
    @DisplayName("H6 - every guarded paragraph flushes inside its own guard")
    class FlushInsideTheGuard {

        /**
         * Reports the guarded blocks of a class: the text of each {@code try { ... } catch}, in order.
         *
         * @param type the class to scan
         * @return one entry per try block
         */
        private List<String> tryBlocks(Class<?> type) {
            String source = sourceOf(type);
            List<String> blocks = new ArrayList<>();
            int from = 0;
            while (true) {
                int start = source.indexOf("try {", from);
                if (start < 0) {
                    break;
                }
                int end = source.indexOf("} catch", start);
                if (end < 0) {
                    break;
                }
                blocks.add(source.substring(start, end));
                from = end + 1;
            }
            return blocks;
        }

        /**
         * Asserts that every guarded block which persists also flushes, in the same block.
         *
         * @param type the class to check
         */
        private void assertEveryPersistingGuardFlushes(Class<?> type) {
            List<String> persistingBlocks = tryBlocks(type).stream()
                    .filter(block -> block.contains(".save("))
                    .toList();
            assertThat(persistingBlocks)
                    .as("%s must contain at least one guarded persist, or this assertion is vacuous",
                            type.getSimpleName())
                    .isNotEmpty();
            for (String block : persistingBlocks) {
                assertThat(block)
                        .as("a guarded save() without a flush() in the same block defers the statement to "
                                + "commit, outside the catch, losing the paragraph's FILE STATUS "
                                + "translation, source literal and return code. Offending block in %s",
                                type.getSimpleName())
                        .contains(".flush()");
            }
        }

        @Test
        @DisplayName("TransactionPostingProcessor flushes in all three of its guarded writes")
        void postingProcessorFlushesInEveryGuard() {
            // :L510 WRITE tcatbal, :L528 REWRITE tcatbal, :L554 REWRITE account.
            assertEveryPersistingGuardFlushes(TransactionPostingProcessor.class);
            assertThat(sourceOf(TransactionPostingProcessor.class).split("\\.flush\\(\\)", -1))
                    .as("one flush per guarded write, and no more")
                    .hasSize(4);
        }

        @Test
        @DisplayName("InterestCalculationProcessor flushes in its guarded account rewrite")
        void interestProcessorFlushesInItsGuard() {
            // :L365 REWRITE ACCOUNT-RECORD, whose guard supplies the :L367 DISPLAY literal.
            assertEveryPersistingGuardFlushes(InterestCalculationProcessor.class);
        }

        @Test
        @DisplayName("CardUpdateService flushes before it records the rewrite as successful")
        void cardUpdateServiceFlushesBeforeDeclaringSuccess() {
            String source = sourceOf(com.cardemo.service.card.CardUpdateService.class);
            int flush = source.indexOf("cardRepository.flush()");
            int success = source.indexOf("context.writeOutcome = WriteOutcome.COMPLETED");

            assertThat(flush).as("the write path must flush").isPositive();
            assertThat(success).as("the write path must record success").isPositive();
            // If the flush came after the success assignment, a version conflict would be reported as a
            // completed rewrite - which is exactly the defect H6 describes.
            assertThat(flush)
                    .as("the flush must precede the success state, or :1488-1489 would report a rewrite "
                            + "that had not yet been issued")
                    .isLessThan(success);
        }

        @Test
        @DisplayName("AccountUpdateService, the pattern the others follow, still flushes in both guards")
        void accountUpdateServiceRemainsThePattern() {
            assertEveryPersistingGuardFlushes(com.cardemo.service.account.AccountUpdateService.class);
        }
    }

    @Nested
    @DisplayName("H7 - exactly one component owns the 2900-WRITE-TRANSACTION-FILE insert")
    class InsertOwnership {

        @Test
        @DisplayName("the posting processor no longer holds a transaction repository at all")
        void postingProcessorHoldsNoTransactionRepository() {
            List<String> fieldTypes = new ArrayList<>();
            for (Field field : TransactionPostingProcessor.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    fieldTypes.add(field.getType().getSimpleName());
                }
            }
            // Removing the collaborator is what makes the duplicate insert unreachable by construction,
            // rather than merely unused. Rule 1 Clause B forbids keeping an injected collaborator that
            // nothing calls.
            assertThat(fieldTypes).doesNotContain("TransactionRepository");
        }

        @Test
        @DisplayName("no constructor of the posting processor accepts a transaction repository")
        void noConstructorAcceptsATransactionRepository() {
            for (Constructor<?> constructor : TransactionPostingProcessor.class.getDeclaredConstructors()) {
                List<String> parameterTypes = new ArrayList<>();
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    parameterTypes.add(parameter.getSimpleName());
                }
                assertThat(parameterTypes).doesNotContain("TransactionRepository");
            }
        }

        @Test
        @DisplayName("the writer is the owner: it inserts and flushes in one call")
        void writerIsTheOwner() {
            String source = sourceOf(TransactionWriter.class);
            assertThat(source)
                    .as("the owner performs the relational insert, flushing inside its own guard")
                    .contains("transactionRepository.saveAllAndFlush(items)");
            // The literal of :L574 travels with the write, so delegating did not lose the diagnostic.
            assertThat(source).contains("ERROR WRITING TO TRANSACTION FILE");
        }

        @Test
        @DisplayName("the processor's diagnostics for the delegated paragraph are gone, not orphaned")
        void delegatedDiagnosticsAreRemovedNotOrphaned() {
            String source = sourceOf(TransactionPostingProcessor.class);
            // Constants left behind after the delegation would be dead code under Rule 1 Clause B.
            assertThat(source).doesNotContain("TRANFILE_WRITE_FAILURE_TEXT");
            assertThat(source).doesNotContain("RELATION_TRANSACTION");
            assertThat(source).doesNotContain("DD_TRANFILE");
        }

        @Test
        @DisplayName("the processor still builds the record and returns it for the writer to persist")
        void processorStillBuildsAndReturnsTheRecord() throws NoSuchMethodException {
            Method process = TransactionPostingProcessor.class.getMethod("process",
                    com.cardemo.model.entity.DailyTransaction.class);
            // The chunk-oriented step carries the built record from processor to writer; that is the whole
            // mechanism by which one owner performs the insert.
            assertThat(process.getReturnType().getSimpleName()).isEqualTo("PostingResult");
        }
    }

    @Nested
    @DisplayName("H8 - the interest processor persists the control-break state a restart depends on")
    class RestartState {

        @Test
        @DisplayName("the processor is an ItemStream, so the step registers it and persists its context")
        void processorIsAnItemStream() {
            assertThat(ItemStream.class)
                    .as("SimpleStepBuilder registers reader, processor and writer as streams, so "
                            + "implementing ItemStream is what gets open/update/close called at all")
                    .isAssignableFrom(InterestCalculationProcessor.class);
        }

        @Test
        @DisplayName("a real chunk-oriented step registers the processor and persists its state to the context")
        void realStepRegistersTheProcessorAsAStream() throws Exception {
            // The decisive test for H8: implementing ItemStream only matters if Spring Batch actually calls
            // it. Rather than reading the framework's source, this drives a real step and then reads the
            // step execution context, which is the only place the state could have come from.
            InterestCalculationProcessor processor = newProcessor();
            setState(processor, 5L, 3, "00000000042", false, new BigDecimal("12.34"));

            org.springframework.batch.core.repository.JobRepository jobRepository =
                    new org.springframework.batch.core.repository.support.ResourcelessJobRepository();
            org.springframework.batch.support.transaction.ResourcelessTransactionManager transactionManager =
                    new org.springframework.batch.support.transaction.ResourcelessTransactionManager();

            // A reader that immediately reports end of data: the state under test is the processor's, and
            // nothing here needs a row to travel through it.
            org.springframework.batch.item.ItemReader<com.cardemo.model.entity.TransactionCategoryBalance>
                    reader = () -> null;

            org.springframework.batch.core.Step step =
                    new org.springframework.batch.core.step.builder.StepBuilder("intcalcStep", jobRepository)
                            .<com.cardemo.model.entity.TransactionCategoryBalance,
                                    com.cardemo.model.entity.Transaction>chunk(10, transactionManager)
                            .reader(reader)
                            .processor(processor)
                            .writer(chunk -> { })
                            .build();

            org.springframework.batch.core.JobExecution jobExecution =
                    jobRepository.createJobExecution("intcalcJob",
                            new org.springframework.batch.core.JobParameters());
            org.springframework.batch.core.StepExecution stepExecution =
                    jobExecution.createStepExecution("intcalcStep");
            jobRepository.add(stepExecution);

            step.execute(stepExecution);

            assertThat(stepExecution.getStatus())
                    .isEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
            ExecutionContext saved = stepExecution.getExecutionContext();
            assertThat(saved.containsKey("carddemo.intcalc.lastAccountNumber"))
                    .as("the step must have called update() on the processor; if this is false the "
                            + "ItemStream is never registered and the whole restart fix is inert")
                    .isTrue();
            assertThat(saved.getString("carddemo.intcalc.lastAccountNumber")).isEqualTo("00000000042");
            assertThat(saved.getInt("carddemo.intcalc.tranIdSuffix")).isEqualTo(3);
            assertThat(saved.getLong("carddemo.intcalc.recordCount")).isEqualTo(5L);
            assertThat(saved.getInt("carddemo.intcalc.firstTime")).isZero();
            assertThat(saved.getString("carddemo.intcalc.totalInterest")).isEqualTo("12.34");
        }

        @Test
        @DisplayName("update then open round-trips every correctness-bearing scalar")
        void updateThenOpenRoundTripsTheState() throws Exception {
            InterestCalculationProcessor first = newProcessor();
            setState(first, 42L, 7, "00000000123", false, new BigDecimal("135.79"));

            ExecutionContext context = new ExecutionContext();
            first.update(context);

            InterestCalculationProcessor resumed = newProcessor();
            // A fresh instance begins at the declared initial values, which is what a restart would use.
            assertThat(readLong(resumed, "recordCount")).isZero();
            assertThat(readBoolean(resumed, "firstTime")).isTrue();

            resumed.open(context);

            // Every field the reader's saved position implies is restored. Without this, the first resumed
            // row would suppress the flush (firstTime), be treated as a control break (lastAccountNumber),
            // lose the partial accumulation (totalInterest) and reissue identifiers (tranIdSuffix).
            assertThat(readLong(resumed, "recordCount")).isEqualTo(42L);
            assertThat(readInt(resumed, "tranIdSuffix")).isEqualTo(7);
            assertThat(readString(resumed, "lastAccountNumber")).isEqualTo("00000000123");
            assertThat(readBoolean(resumed, "firstTime")).isFalse();
            assertThat(readBigDecimal(resumed, "totalInterest"))
                    .isEqualByComparingTo(new BigDecimal("135.79"));
        }

        @Test
        @DisplayName("the accumulated interest survives as an exact decimal, never through a double")
        void accumulatedInterestSurvivesExactly() throws Exception {
            // A value chosen because it has no exact binary floating-point representation: a double round
            // trip would return 0.07000000000000001 or similar and the flush would write the wrong amount.
            BigDecimal awkward = new BigDecimal("999999.07");
            InterestCalculationProcessor first = newProcessor();
            setState(first, 1L, 1, "00000000001", false, awkward);

            ExecutionContext context = new ExecutionContext();
            first.update(context);
            InterestCalculationProcessor resumed = newProcessor();
            resumed.open(context);

            assertThat(readBigDecimal(resumed, "totalInterest")).isEqualByComparingTo(awkward);
            assertThat(readBigDecimal(resumed, "totalInterest").scale()).isEqualTo(2);
            // The stored form is text, not a floating-point number, which is what makes that exact.
            assertThat(context.getString("carddemo.intcalc.totalInterest")).isEqualTo("999999.07");
        }

        @Test
        @DisplayName("a first execution finds no saved state and starts from the declared initial values")
        void firstExecutionStartsClean() throws Exception {
            InterestCalculationProcessor fresh = newProcessor();

            fresh.open(new ExecutionContext());

            // A fresh run must behave exactly as it did before restart support existed.
            assertThat(readLong(fresh, "recordCount")).isZero();
            assertThat(readInt(fresh, "tranIdSuffix")).isZero();
            assertThat(readBoolean(fresh, "firstTime")).isTrue();
            assertThat(readString(fresh, "lastAccountNumber")).isEqualTo(" ".repeat(11));
            assertThat(readBigDecimal(fresh, "totalInterest")).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("no in-progress account means no account identifier is left in the context")
        void noInProgressAccountLeavesNoIdentifier() throws Exception {
            InterestCalculationProcessor processor = newProcessor();
            ExecutionContext context = new ExecutionContext();
            context.putLong("carddemo.intcalc.currentAccountId", 99L);

            processor.update(context);

            // A stale identifier would make open() re-read an account the run is no longer accumulating for.
            assertThat(context.containsKey("carddemo.intcalc.currentAccountId")).isFalse();
        }

        @Test
        @DisplayName("close releases nothing and is safe to call without an open")
        void closeIsSafe() throws Exception {
            InterestCalculationProcessor processor = newProcessor();

            processor.close();
            processor.close();

            // The end-of-data flush deliberately does NOT live here: close also runs on the failure path.
            assertThat(sourceOf(InterestCalculationProcessor.class))
                    .contains("Nothing to release; see the method documentation for why this is empty");
        }

        /**
         * Builds a processor over mocked collaborators and a fixed clock.
         *
         * <p>Mocks rather than {@code null}s because the constructor validates every collaborator, which is
         * itself correct. Neither {@code update} nor a state-only {@code open} reaches a repository, so the
         * mocks are never called and no stubbing is required.
         *
         * @return a processor instance
         */
        private InterestCalculationProcessor newProcessor() {
            return new InterestCalculationProcessor(
                    org.mockito.Mockito.mock(com.cardemo.repository.DisclosureGroupRepository.class),
                    org.mockito.Mockito.mock(com.cardemo.repository.AccountRepository.class),
                    org.mockito.Mockito.mock(com.cardemo.repository.CardCrossReferenceRepository.class),
                    new com.cardemo.service.shared.FileStatusMapper(),
                    "2024010100",
                    java.time.Clock.fixed(java.time.Instant.parse("2024-01-01T00:00:00Z"),
                            java.time.ZoneOffset.UTC));
        }

        /**
         * Sets the five correctness-bearing scalars directly.
         *
         * @param processor the instance to mutate
         * @param recordCount {@code WS-RECORD-COUNT}
         * @param tranIdSuffix {@code WS-TRANID-SUFFIX}
         * @param lastAccountNumber {@code WS-LAST-ACCT-NUM}
         * @param firstTime {@code WS-FIRST-TIME}
         * @param totalInterest {@code WS-TOTAL-INT}
         * @throws Exception if reflection fails
         */
        private void setState(InterestCalculationProcessor processor, long recordCount, int tranIdSuffix,
                String lastAccountNumber, boolean firstTime, BigDecimal totalInterest) throws Exception {
            write(processor, "recordCount", Long.valueOf(recordCount));
            write(processor, "tranIdSuffix", Integer.valueOf(tranIdSuffix));
            write(processor, "lastAccountNumber", lastAccountNumber);
            write(processor, "firstTime", Boolean.valueOf(firstTime));
            write(processor, "totalInterest", totalInterest);
        }

        /**
         * Writes one declared field.
         *
         * @param target the instance
         * @param name the field name
         * @param value the value
         * @throws Exception if reflection fails
         */
        private void write(Object target, String name, Object value) throws Exception {
            Field field = InterestCalculationProcessor.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        }

        /**
         * Reads one declared field.
         *
         * @param target the instance
         * @param name the field name
         * @return the value
         * @throws Exception if reflection fails
         */
        private Object read(Object target, String name) throws Exception {
            Field field = InterestCalculationProcessor.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        }

        /**
         * Reads a {@code long} field.
         *
         * @param target the instance
         * @param name the field name
         * @return the value
         * @throws Exception if reflection fails
         */
        private long readLong(Object target, String name) throws Exception {
            return ((Long) read(target, name)).longValue();
        }

        /**
         * Reads an {@code int} field.
         *
         * @param target the instance
         * @param name the field name
         * @return the value
         * @throws Exception if reflection fails
         */
        private int readInt(Object target, String name) throws Exception {
            return ((Integer) read(target, name)).intValue();
        }

        /**
         * Reads a {@code boolean} field.
         *
         * @param target the instance
         * @param name the field name
         * @return the value
         * @throws Exception if reflection fails
         */
        private boolean readBoolean(Object target, String name) throws Exception {
            return ((Boolean) read(target, name)).booleanValue();
        }

        /**
         * Reads a {@link String} field.
         *
         * @param target the instance
         * @param name the field name
         * @return the value
         * @throws Exception if reflection fails
         */
        private String readString(Object target, String name) throws Exception {
            return (String) read(target, name);
        }

        /**
         * Reads a {@link BigDecimal} field.
         *
         * @param target the instance
         * @param name the field name
         * @return the value
         * @throws Exception if reflection fails
         */
        private BigDecimal readBigDecimal(Object target, String name) throws Exception {
            return (BigDecimal) read(target, name);
        }
    }

    @Nested
    @DisplayName("M2 - per-execution writer state lives in step scope, not on a singleton")
    class StepScopedWriters {

        @ParameterizedTest
        @DisplayName("both writers are step-scoped")
        @ValueSource(classes = {TransactionWriter.class, StatementWriter.class})
        void bothWritersAreStepScoped(Class<?> writer) {
            assertThat(writer.getAnnotation(StepScope.class))
                    .as("%s holds per-execution state, so a singleton would let two executions overwrite "
                            + "each other's - and the object keys are derived from that state",
                            writer.getSimpleName())
                    .isNotNull();
        }

        @Test
        @DisplayName("no writer relies on a memory-visibility modifier in place of scoping")
        void noWriterUsesVolatileForIsolation() {
            for (Class<?> writer : List.of(TransactionWriter.class, StatementWriter.class)) {
                for (Field field : writer.getDeclaredFields()) {
                    assertThat(Modifier.isVolatile(field.getModifiers()))
                            .as("%s.%s is volatile: that publishes a reference between threads but does "
                                    + "not partition it between executions, which is the actual problem",
                                    writer.getSimpleName(), field.getName())
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("no writer holds static mutable state")
        void noWriterHoldsStaticMutableState() {
            for (Class<?> writer : List.of(TransactionWriter.class, StatementWriter.class)) {
                for (Field field : writer.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("%s.%s is static and not final, so it would be shared by every "
                                        + "execution in the JVM", writer.getSimpleName(), field.getName())
                                .isTrue();
                    }
                }
            }
        }

        @Test
        @DisplayName("neither writer serialises access instead of isolating it")
        void neitherWriterSynchronises() {
            for (Class<?> writer : List.of(TransactionWriter.class, StatementWriter.class)) {
                for (Method method : writer.getDeclaredMethods()) {
                    assertThat(Modifier.isSynchronized(method.getModifiers()))
                            .as("%s.%s is synchronized: mutual exclusion orders access without "
                                    + "partitioning it, so it cannot isolate two step executions",
                                    writer.getSimpleName(), method.getName())
                            .isFalse();
                }
            }
        }
    }

    @Nested
    @DisplayName("L4 - the negative-zero distinction is absent from the whole substrate, by measurement")
    class NegativeZeroFidelity {

        @Test
        @DisplayName("the frozen fixture contains no negative-zero amount, so the case cannot arise from it")
        void fixtureContainsNoNegativeZero() {
            List<String> rows = dailyTransactionRows();
            assertThat(rows).as("the fixture must be present for this measurement to mean anything")
                    .hasSize(300);

            List<String> negativeZeroFields = new ArrayList<>();
            int negativeRows = 0;
            for (String row : rows) {
                String field = row.substring(AMOUNT_OFFSET, AMOUNT_OFFSET + AMOUNT_WIDTH);
                char overpunch = field.charAt(AMOUNT_WIDTH - 1);
                int negativeIndex = NEGATIVE_OVERPUNCH.indexOf(overpunch);
                if (negativeIndex < 0) {
                    assertThat(POSITIVE_OVERPUNCH.indexOf(overpunch))
                            .as("every amount must carry a valid overpunch")
                            .isNotNegative();
                    continue;
                }
                negativeRows++;
                long magnitude = Long.parseLong(field.substring(0, AMOUNT_WIDTH - 1)
                        + negativeIndex);
                if (magnitude == 0L) {
                    negativeZeroFields.add(field);
                }
            }

            // The fixture genuinely exercises negative amounts - so the cycle-debit branch is covered -
            // while containing no negative zero at all.
            assertThat(negativeRows).as("the fixture must exercise negative amounts").isEqualTo(50);
            assertThat(negativeZeroFields)
                    .as("a negative-zero amount in the fixture would make L4 a required case rather than a "
                            + "theoretical one, and would oblige the raw-byte remediation")
                    .isEmpty();
        }

        @Test
        @DisplayName("the six rows whose overpunch is '}' all carry non-zero magnitudes")
        void everyMinusZeroOverpunchRowHasANonZeroMagnitude() {
            List<String> braceRows = new ArrayList<>();
            for (String row : dailyTransactionRows()) {
                String field = row.substring(AMOUNT_OFFSET, AMOUNT_OFFSET + AMOUNT_WIDTH);
                if (field.charAt(AMOUNT_WIDTH - 1) == '}') {
                    braceRows.add(field);
                }
            }

            // '}' means "negative, final digit zero" - not "negative zero". Conflating the two is the
            // reading that would make this finding look like a live defect.
            assertThat(braceRows).hasSize(6);
            assertThat(braceRows).allSatisfy(field ->
                    assertThat(Long.parseLong(field.substring(0, AMOUNT_WIDTH - 1) + "0")).isPositive());
        }

        @Test
        @DisplayName("BigDecimal has no signed zero, so no arithmetic path can preserve the distinction")
        void bigDecimalHasNoSignedZero() {
            BigDecimal negativeZero = new BigDecimal("-0.00");
            BigDecimal positiveZero = new BigDecimal("0.00");

            // This is the root of the finding, and it is a property of decimal arithmetic rather than of
            // any class in this tree: the sign is simply not carried for a zero significand.
            assertThat(negativeZero.signum()).isZero();
            assertThat(negativeZero).isEqualByComparingTo(positiveZero);
            assertThat(negativeZero.unscaledValue().signum()).isZero();
            assertThat(negativeZero.toPlainString()).isEqualTo("0.00");
        }

        @Test
        @DisplayName("the storage column cannot carry the distinction either, so no round trip could restore it")
        void theColumnCannotCarryTheDistinctionEither() {
            String schema;
            try {
                schema = Files.readString(
                        Path.of("src", "main", "resources", "db", "migration", "V1__create_schema.sql"),
                        StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("the schema migration must be readable", unreadable);
            }

            // The staged amount is a numeric column, which has no signed zero, so preserving the byte would
            // require a second raw-image column that the schema does not declare. Recording that here makes
            // the "Not available" disclosure on RejectWriter.signedZonedDecimal verifiable rather than
            // asserted.
            assertThat(schema.toLowerCase(Locale.ROOT)).contains("dalytran_amt");
            assertThat(schema.toLowerCase(Locale.ROOT)).doesNotContain("dalytran_amt_raw");
        }
    }
}
