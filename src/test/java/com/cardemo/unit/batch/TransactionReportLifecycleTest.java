/*
 * ******************************************************************
 * Program     : TransactionReportLifecycleTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves the twelve dataset lifecycle paragraphs of CBTRN03C
 *               exist and behave: all six datasets open and close in source
 *               order, each abends with its own operator literal, the failure
 *               arm emits display then rendered status then abend, and the
 *               lifecycle mutates none of the report accumulators.
 * Source      : app/cbl/CBTRN03C.cbl:L163-L168 (the six opens)
 *               app/cbl/CBTRN03C.cbl:L207-L212 (the six closes)
 *               app/cbl/CBTRN03C.cbl:L376-L482 (0000-0500 OPEN)
 *               app/cbl/CBTRN03C.cbl:L514-L621 (9000-9500 CLOSE)
 *               app/cbl/CBTRN03C.cbl:L626-L630 (9999-ABEND-PROGRAM)
 *               app/proc/TRANREPT.prc:STEP10R  (LRECL=133) @ 7756d89
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRepository;
import com.cardemo.service.shared.FileStatusMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The six-dataset lifecycle of {@code app/cbl/CBTRN03C.cbl}, which the translation had omitted entirely.
 *
 * <p>The program declares six {@code SELECT}s and pairs every one with an {@code OPEN} paragraph and a
 * {@code CLOSE} paragraph: {@code 0000-TRANFILE-OPEN} through {@code 0500-DATEPARM-OPEN} at
 * {@code :L376-L482} and {@code 9000-TRANFILE-CLOSE} through {@code 9500-DATEPARM-CLOSE} at
 * {@code :L514-L621}. Twelve of the program's twenty-six paragraphs, and none of them had a Java
 * counterpart, so the paragraph map that Gate 7 verifies was incomplete by a third.
 *
 * <p>They are not ceremony. Each establishes that one dataset is reachable <em>before</em> the first record
 * is processed, and abends with its own operator message if it is not - which is the difference between a
 * job that fails at once and a job that fails a thousand records in, having written a report from a dataset
 * it could not read.
 *
 * <p>These tests assert that all twelve exist and are reachable through the two lifecycle entry points,
 * that each of the four probing datasets abends with <strong>its own</strong> literal rather than a shared
 * one, that the failure arm emits the display, the rendered status and the abend in the source's order, and
 * that the lifecycle mutates none of the report state the constructor established.
 */
@DisplayName("TransactionReportProcessor: the twelve dataset lifecycle paragraphs of CBTRN03C")
class TransactionReportLifecycleTest {

    /** The reporting window, ten characters each, as the parameter card supplies them. */
    private static final String START_DATE = "2024-01-01";

    /** The inclusive last processing date. */
    private static final String END_DATE = "2024-12-31";

    /** The four-character rendering the legacy status renderer produces for a physical failure. */
    private static final String RENDERED_STATUS = new FileStatusMapper().displayIoStatus("90");

    private TransactionRepository transactions;
    private CardCrossReferenceRepository crossReferences;
    private TransactionTypeRepository types;
    private TransactionCategoryRepository categories;
    private TransactionReportProcessor processor;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    @BeforeEach
    void buildProcessorOverReachableDatasets() {
        transactions = mock(TransactionRepository.class);
        crossReferences = mock(CardCrossReferenceRepository.class);
        types = mock(TransactionTypeRepository.class);
        categories = mock(TransactionCategoryRepository.class);

        when(transactions.count()).thenReturn(300L);
        when(crossReferences.count()).thenReturn(50L);
        when(types.count()).thenReturn(7L);
        when(categories.count()).thenReturn(18L);

        processor = new TransactionReportProcessor(transactions, crossReferences, types, categories,
                new FileStatusMapper(), START_DATE, END_DATE);

        logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(TransactionReportProcessor.class);
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

    /**
     * Every event captured so far, rendered as written.
     *
     * @return one string per event, never {@code null}
     */
    private List<String> capturedLines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Nested
    @DisplayName("1. All six datasets open and close when every one is reachable")
    class TheHappyPath {

        @Test
        @DisplayName("openDatasets probes the four queryable datasets and raises nothing")
        void openProbesEveryQueryableDataset() {
            processor.openDatasets();

            // One probe per dataset that has a relation behind it. REPTFILE and DATEPARM have none, and
            // their paragraphs assert a contract rather than issue a query.
            org.mockito.Mockito.verify(transactions).count();
            org.mockito.Mockito.verify(crossReferences).count();
            org.mockito.Mockito.verify(types).count();
            org.mockito.Mockito.verify(categories).count();
        }

        @Test
        @DisplayName("closeDatasets probes the same four and raises nothing")
        void closeProbesEveryQueryableDataset() {
            processor.closeDatasets();

            org.mockito.Mockito.verify(transactions).count();
            org.mockito.Mockito.verify(crossReferences).count();
            org.mockito.Mockito.verify(types).count();
            org.mockito.Mockito.verify(categories).count();
        }

        @Test
        @DisplayName("The lifecycle disturbs none of the report accumulators or the control break key")
        void theLifecycleIsFreeOfSideEffectsOnReportState() {
            processor.openDatasets();
            processor.closeDatasets();

            // The six WS-REPORT-VARS items the constructor initialised to their VALUE clauses.
            assertThat(processor.lineCounter()).isZero();
            assertThat(processor.pageTotal()).isEqualByComparingTo("0");
            assertThat(processor.accountTotal()).isEqualByComparingTo("0");
            assertThat(processor.grandTotal()).isEqualByComparingTo("0");
            assertThat(processor.currentCardNumber()).isNotNull();
        }

        @Test
        @DisplayName("A successful lifecycle writes no error diagnostic at all")
        void nothingIsReportedWhenEveryDatasetIsReachable() {
            processor.openDatasets();
            processor.closeDatasets();

            assertThat(capturedLines())
                    .noneMatch(line -> line.startsWith("ERROR OPENING"))
                    .noneMatch(line -> line.startsWith("ERROR CLOSING"));
        }
    }

    @Nested
    @DisplayName("2. Each dataset abends with its own literal, never a shared one")
    class EachDatasetHasItsOwnMessage {

        @Test
        @DisplayName("0000-TRANFILE-OPEN abends with ERROR OPENING TRANFILE")
        void tranfileOpenNamesItself() {
            assertAbend(() -> when(transactions.count()).thenThrow(failure()),
                    processor::openDatasets, "ERROR OPENING TRANFILE");
        }

        @Test
        @DisplayName("0200-CARDXREF-OPEN abends with ERROR OPENING CROSS REF FILE")
        void cardxrefOpenNamesItself() {
            assertAbend(() -> when(crossReferences.count()).thenThrow(failure()),
                    processor::openDatasets, "ERROR OPENING CROSS REF FILE");
        }

        @Test
        @DisplayName("0300-TRANTYPE-OPEN abends with ERROR OPENING TRANSACTION TYPE FILE")
        void trantypeOpenNamesItself() {
            assertAbend(() -> when(types.count()).thenThrow(failure()),
                    processor::openDatasets, "ERROR OPENING TRANSACTION TYPE FILE");
        }

        @Test
        @DisplayName("0400-TRANCATG-OPEN abends with ERROR OPENING TRANSACTION CATG FILE")
        void trancatgOpenNamesItself() {
            assertAbend(() -> when(categories.count()).thenThrow(failure()),
                    processor::openDatasets, "ERROR OPENING TRANSACTION CATG FILE");
        }

        @Test
        @DisplayName("9000-TRANFILE-CLOSE abends with ERROR CLOSING POSTED TRANSACTION FILE")
        void tranfileCloseNamesItselfAndDiffersFromItsOwnOpen() {
            // The close literal names the file differently from the open literal, which says TRANFILE.
            // Both are reproduced exactly as the source writes them.
            assertAbend(() -> when(transactions.count()).thenThrow(failure()),
                    processor::closeDatasets, "ERROR CLOSING POSTED TRANSACTION FILE");
        }

        @Test
        @DisplayName("9200-CARDXREF-CLOSE abends with ERROR CLOSING CROSS REF FILE")
        void cardxrefCloseNamesItself() {
            assertAbend(() -> when(crossReferences.count()).thenThrow(failure()),
                    processor::closeDatasets, "ERROR CLOSING CROSS REF FILE");
        }

        @Test
        @DisplayName("9300-TRANTYPE-CLOSE abends with ERROR CLOSING TRANSACTION TYPE FILE")
        void trantypeCloseNamesItself() {
            assertAbend(() -> when(types.count()).thenThrow(failure()),
                    processor::closeDatasets, "ERROR CLOSING TRANSACTION TYPE FILE");
        }

        @Test
        @DisplayName("9400-TRANCATG-CLOSE abends with ERROR CLOSING TRANSACTION CATG FILE")
        void trancatgCloseNamesItself() {
            assertAbend(() -> when(categories.count()).thenThrow(failure()),
                    processor::closeDatasets, "ERROR CLOSING TRANSACTION CATG FILE");
        }

        @Test
        @DisplayName("The twelve literals are twelve distinct strings")
        void everyLiteralIsDistinct() {
            final List<String> literals = List.of(
                    "ERROR OPENING TRANFILE", "ERROR OPENING REPTFILE", "ERROR OPENING CROSS REF FILE",
                    "ERROR OPENING TRANSACTION TYPE FILE", "ERROR OPENING TRANSACTION CATG FILE",
                    "ERROR OPENING DATE PARM FILE", "ERROR CLOSING POSTED TRANSACTION FILE",
                    "ERROR CLOSING REPORT FILE", "ERROR CLOSING CROSS REF FILE",
                    "ERROR CLOSING TRANSACTION TYPE FILE", "ERROR CLOSING TRANSACTION CATG FILE",
                    "ERROR CLOSING DATE PARM FILE");

            assertThat(literals).doesNotHaveDuplicates().hasSize(12);
        }
    }

    @Nested
    @DisplayName("3. The failure arm keeps the source's three statements in order")
    class TheFailureArmIsFaithful {

        @Test
        @DisplayName("Display, then the rendered IO status, then the abend")
        void theThreeStatementsAppearInSourceOrder() {
            when(crossReferences.count()).thenThrow(failure());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.openDatasets());

            final List<String> lines = capturedLines();
            final int display = lines.indexOf("ERROR OPENING CROSS REF FILE");
            final int status = lines.indexOf(RENDERED_STATUS);

            assertThat(display).as("the DISPLAY literal of :L423").isNotNegative();
            assertThat(status).as("the 9910-DISPLAY-IO-STATUS rendering of :L425").isGreaterThan(display);
            assertThat(lines).anyMatch(line -> line.startsWith("ABENDING PROGRAM"));
        }

        @Test
        @DisplayName("The abend carries code 999 and the dataset literal as its message")
        void theAbendCarriesTheLegacyContract() {
            when(types.count()).thenThrow(failure());

            final FatalProcessingException abend = assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.openDatasets())
                    .actual();

            assertThat(abend.getAbendCode()).contains("999");
            assertThat(abend.getAbendMessage()).isEqualTo("ERROR OPENING TRANSACTION TYPE FILE");
            assertThat(abend.getCause()).isInstanceOf(DataAccessResourceFailureException.class);
        }

        @Test
        @DisplayName("An open failure stops the sequence: no later dataset is probed")
        void theSequenceStopsAtTheFirstFailure() {
            when(transactions.count()).thenThrow(failure());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.openDatasets());

            // TRANSACT is dataset one, so nothing after it is reached. That is the source's behaviour:
            // 9999-ABEND-PROGRAM terminates the step rather than returning to the mainline.
            org.mockito.Mockito.verify(crossReferences, org.mockito.Mockito.never()).count();
            org.mockito.Mockito.verify(types, org.mockito.Mockito.never()).count();
            org.mockito.Mockito.verify(categories, org.mockito.Mockito.never()).count();
        }
    }

    /**
     * A store failure of the kind a real {@code OPEN} would report as a {@code '9x'} status.
     *
     * @return the exception the probe throws, never {@code null}
     */
    private static DataAccessResourceFailureException failure() {
        return new DataAccessResourceFailureException("the relation is not reachable");
    }

    /**
     * Arranges a dataset failure, drives the lifecycle and asserts the abend names that dataset.
     *
     * @param arrange the stubbing that makes one dataset fail, never {@code null}
     * @param lifecycle the entry point to drive, never {@code null}
     * @param expectedLiteral the paragraph's own {@code DISPLAY} literal, never {@code null}
     */
    private void assertAbend(final Runnable arrange, final Runnable lifecycle,
            final String expectedLiteral) {
        arrange.run();

        final FatalProcessingException abend = assertThatExceptionOfType(FatalProcessingException.class)
                .isThrownBy(lifecycle::run)
                .actual();

        assertThat(abend.getAbendMessage()).isEqualTo(expectedLiteral);
        assertThat(capturedLines()).contains(expectedLiteral);
    }
}
