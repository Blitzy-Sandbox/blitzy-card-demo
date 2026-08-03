/*
 * ******************************************************************
 * Program     : StatementStepContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Runs a concrete chunk-oriented Spring Batch step that
 *               wires the real statement reader, processor and writer
 *               together, proving the three halves share one contract
 *               and that complete per-account output reaches storage.
 *               This is the end-to-end shape CREASTMT.JCL STEP040
 *               drives, executed in-process against a resourceless
 *               job repository so no database is required.
 * Source      : app/jcl/CREASTMT.JCL:L88-L96  (STEP040 runs CBSTM03A)
 *               app/cbl/CBSTM03A.CBL:L317-L330 (the driving loop)
 *               app/cbl/CBSTM03A.CBL:L345-L366 (1000-XREFFILE-GET-NEXT)
 *               app/cbl/CBSTM03A.CBL:L293      (OPEN OUTPUT both)
 *               app/cbl/CBSTM03A.CBL:L339      (CLOSE both) @ 7756d89
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

import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.service.shared.FileService;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Resource;
import io.awspring.cloud.s3.S3Template;
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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;

@DisplayName("Statement step: reader, processor and writer share one contract end to end")
class StatementStepContractTest {

    /** The three cards the fixture files transactions under, in sorted order. */
    private static final List<String> CARDS =
            List.of("4111111111111111", "4222222222222222", "4333333333333333");

    /** The customer identifier every cross-reference row points at. */
    private static final long CUSTOMER_ID = 100000001L;

    /** The base account identifier; one account per card. */
    private static final long BASE_ACCOUNT_ID = 10000000001L;

    /** The instant the fixed clock reports, fixing the statement month at 2024-03. */
    private static final Instant FIXED_INSTANT = Instant.parse("2024-03-15T10:30:00Z");

    /** Records the uploads the writer performs, in order. */
    private final List<Upload> uploads = new ArrayList<>();

    /** The recording storage boundary. */
    private S3Template s3Template;

    /**
     * One recorded upload.
     *
     * @param key the object key
     * @param content the object content as ISO-8859-1 text
     */
    private record Upload(String key, String content) {
    }

    @BeforeEach
    void setUp() {
        this.uploads.clear();
        this.s3Template = Mockito.mock(S3Template.class);
        Mockito.when(this.s3Template.upload(Mockito.anyString(), Mockito.anyString(),
                        Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class)))
                .thenAnswer(invocation -> {
                    InputStream body = invocation.getArgument(2);
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    body.transferTo(buffer);
                    this.uploads.add(new Upload(invocation.getArgument(1),
                            buffer.toString(StandardCharsets.ISO_8859_1)));
                    return Mockito.mock(S3Resource.class);
                });
    }

    /**
     * Builds the fully bound file service for a three-card, three-account run.
     *
     * @param transactionsPerCard how many transactions each card carries
     * @return a bound service, never {@code null}
     */
    private static FileService fixtureFileService(int transactionsPerCard) {
        List<String> transactions = new ArrayList<>();
        List<String> crossReferences = new ArrayList<>();
        Map<String, String> customers = new LinkedHashMap<>();
        Map<String, String> accounts = new LinkedHashMap<>();

        customers.put(StatementRecordFixtures.digits(CUSTOMER_ID, 9),
                StatementRecordFixtures.customerRecord(CUSTOMER_ID, "JOHN", "Q", "PUBLIC",
                        "1 MAIN STREET", "APT 2", "NEW YORK NY", "750"));

        int transactionSequence = 0;
        for (int cardIndex = 0; cardIndex < CARDS.size(); cardIndex++) {
            String card = CARDS.get(cardIndex);
            long accountId = BASE_ACCOUNT_ID + cardIndex;
            accounts.put(StatementRecordFixtures.digits(accountId, 11),
                    StatementRecordFixtures.accountRecord(accountId, new BigDecimal("1000.00")));
            crossReferences.add(
                    StatementRecordFixtures.crossReferenceRecord(card, CUSTOMER_ID, accountId));
            for (int index = 0; index < transactionsPerCard; index++) {
                transactionSequence++;
                transactions.add(StatementRecordFixtures.transactionRecord(card,
                        StatementRecordFixtures.digits(transactionSequence, 16),
                        "PURCHASE " + transactionSequence, new BigDecimal("10.00")));
            }
        }
        return StatementRecordFixtures.fileService(transactions, crossReferences, customers, accounts);
    }

    /**
     * Runs a real chunk-oriented step over the supplied collaborators.
     *
     * @param processor the statement processor, driving both the read and the render
     * @param writer the statement writer
     * @param chunkSize the commit interval
     * @return the completed step execution
     * @throws Exception if the step infrastructure fails
     */
    private static StepExecution runStep(StatementProcessor processor, StatementWriter writer,
            int chunkSize) throws Exception {
        JobRepository jobRepository = new ResourcelessJobRepository();
        ResourcelessTransactionManager transactionManager = new ResourcelessTransactionManager();

        // 1000-XREFFILE-GET-NEXT is the driving stream, so the reader delegates to it rather than
        // duplicating its guard. This is the wiring a job would declare.
        ItemReader<CardCrossReference> reader = () -> processor.readNextCrossReference().orElse(null);

        Step step = new StepBuilder("statementStep", jobRepository)
                .<CardCrossReference, StatementProcessor.Statement>chunk(chunkSize, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .listener(writer)
                .build();

        SimpleJob job = new SimpleJob("statementJob");
        job.setJobRepository(jobRepository);
        job.addStep(step);

        org.springframework.batch.core.JobExecution jobExecution =
                jobRepository.createJobExecution("statementJob",
                        new org.springframework.batch.core.JobParameters());
        StepExecution stepExecution = jobExecution.createStepExecution("statementStep");
        jobRepository.add(stepExecution);
        step.execute(stepExecution);
        return stepExecution;
    }

    /**
     * Builds a writer over the recording storage boundary.
     *
     * @return a new writer
     */
    private StatementWriter newWriter() {
        return new StatementWriter(this.s3Template, new MetricsConfig(new SimpleMeterRegistry()),
                new FileStatusMapper(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                "carddemo-statements");
    }

    @Test
    @DisplayName("H3 - a concrete step produces one complete object pair per account")
    void concreteStepProducesOnePairPerAccount() throws Exception {
        StatementProcessor processor = new StatementProcessor(fixtureFileService(2));
        StatementWriter writer = newWriter();

        StepExecution stepExecution = runStep(processor, writer, 10);

        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExecution.getReadCount()).isEqualTo(3L);
        assertThat(stepExecution.getWriteCount()).isEqualTo(3L);

        // Three accounts, each with a text object and an HTML object: six objects, no more, no fewer.
        assertThat(uploads).hasSize(6);
        assertThat(writer.statementsWritten()).isEqualTo(3L);

        List<String> textKeys = uploads.stream().map(Upload::key)
                .filter(key -> key.endsWith("STATEMNT.PS")).toList();
        List<String> htmlKeys = uploads.stream().map(Upload::key)
                .filter(key -> key.endsWith("STATEMNT.HTML")).toList();
        assertThat(textKeys).hasSize(3);
        assertThat(htmlKeys).hasSize(3);

        // Each account gets its own key prefix, so no statement overwrites another.
        assertThat(textKeys).doesNotHaveDuplicates();
        assertThat(textKeys).allSatisfy(key -> assertThat(key).contains("month=2024-03"));
        assertThat(textKeys.stream().map(key -> key.split("/")[1]).toList())
                .containsExactlyInAnyOrder("account=10000000001", "account=10000000002",
                        "account=10000000003");
    }

    @Test
    @DisplayName("H3 - every persisted record keeps its declared fixed width through the step")
    void persistedRecordsKeepDeclaredWidths() throws Exception {
        StatementProcessor processor = new StatementProcessor(fixtureFileService(3));
        StatementWriter writer = newWriter();

        runStep(processor, writer, 10);

        for (Upload upload : uploads) {
            int width = upload.key().endsWith("STATEMNT.PS")
                    ? StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH
                    : StatementTransaction.STATEMENT_HTML_RECORD_LENGTH;
            assertThat(upload.content().length() % width)
                    .withFailMessage("object %s is %d characters, not a whole multiple of %d",
                            upload.key(), Integer.valueOf(upload.content().length()),
                            Integer.valueOf(width))
                    .isZero();
            assertThat(upload.content()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("H3 - the step is chunk-size independent: the same objects result at any commit interval")
    void stepIsChunkSizeIndependent() throws Exception {
        StatementProcessor firstProcessor = new StatementProcessor(fixtureFileService(2));
        StatementWriter firstWriter = newWriter();
        runStep(firstProcessor, firstWriter, 1);
        List<String> keysAtChunkOne = uploads.stream().map(Upload::key).sorted().toList();
        List<Integer> sizesAtChunkOne = uploads.stream()
                .map(upload -> Integer.valueOf(upload.content().length())).sorted().toList();

        this.uploads.clear();
        StatementProcessor secondProcessor = new StatementProcessor(fixtureFileService(2));
        StatementWriter secondWriter = newWriter();
        runStep(secondProcessor, secondWriter, 5);
        List<String> keysAtChunkFive = uploads.stream().map(Upload::key).sorted().toList();
        List<Integer> sizesAtChunkFive = uploads.stream()
                .map(upload -> Integer.valueOf(upload.content().length())).sorted().toList();

        assertThat(keysAtChunkFive).isEqualTo(keysAtChunkOne);
        assertThat(sizesAtChunkFive).isEqualTo(sizesAtChunkOne);
    }

    @Test
    @DisplayName("H3 - the writer's afterStep publishes the object keys the step created")
    void afterStepPublishesKeysThroughTheStep() throws Exception {
        StatementProcessor processor = new StatementProcessor(fixtureFileService(1));
        StatementWriter writer = newWriter();

        StepExecution stepExecution = runStep(processor, writer, 10);

        assertThat(stepExecution.getExecutionContext()
                .getString(StatementWriter.CONTEXT_KEY_TEXT_OBJECT)).endsWith("STATEMNT.PS");
        assertThat(stepExecution.getExecutionContext()
                .getString(StatementWriter.CONTEXT_KEY_HTML_OBJECT)).endsWith("STATEMNT.HTML");
    }

    @Test
    @DisplayName("H4 - a step over many cards never holds more than one card group")
    void stepNeverHoldsMoreThanOneCardGroup() throws Exception {
        StatementProcessor processor = new StatementProcessor(fixtureFileService(4));
        StatementWriter writer = newWriter();

        runStep(processor, writer, 2);

        // Three groups were read across the run, one per card, and at rest none is resident: the last
        // group was released by the match that served it.
        assertThat(processor.cardGroupsRead()).isEqualTo(3L);
        assertThat(processor.currentCardGroup()).isEmpty();
    }
}
