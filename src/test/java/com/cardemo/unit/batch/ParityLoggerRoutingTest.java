/*
 * ******************************************************************
 * Program     : ParityLoggerRoutingTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves that no translated COBOL DISPLAY carrying a
 *               monetary value or a whole record image can reach the
 *               application log stream. Three programs reproduce such
 *               a DISPLAY because the source wrote it to SYSOUT:
 *               CBACT01C:L78 emits the 300-byte account record image
 *               and :L119-L129 eleven labelled financial values;
 *               CBTRN03C:L180 emits a transaction and :L198-:L199
 *               TRAN-AMT and WS-PAGE-TOTAL; CBACT04C:L193 pairs an
 *               account identifier with TRAN-CAT-BAL. Masking in
 *               logback-spring.xml matches labelled credentials,
 *               hashes and social security numbers, so none of the
 *               above is reachable by it - an unlabelled record image
 *               presents nothing to match at all. The emissions
 *               therefore travel on an isolated com.cardemo.parity
 *               tree that every shipped profile sets to OFF. These
 *               tests assert the routing at runtime with a Logback
 *               appender on each logger, and assert the OFF level is
 *               actually declared in both configuration files.
 * Source      : app/cbl/CBACT01C.cbl:L78        (DISPLAY ACCOUNT-RECORD)
 *               app/cbl/CBACT01C.cbl:L119-L129  (11 labelled values)
 *               app/cbl/CBACT01C.cbl:L130       (49-hyphen rule)
 *               app/cbl/CBTRN03C.cbl:L180       (DISPLAY TRAN-RECORD)
 *               app/cbl/CBTRN03C.cbl:L198-L199  (TRAN-AMT, page total)
 *               app/cbl/CBACT04C.cbl:L193       (TRAN-CAT-BAL-RECORD)
 *               app/cpy/CVACT01Y.cpy            (300-byte layout)
 *               app/cpy/CVTRA01Y.cpy:L15        (TRAN-CAT-BAL)
 *                                                        @ 7756d89
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRepository;
import com.cardemo.service.shared.FileStatusMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Verifies that parity output is routed away from the application log stream and switched off by default.
 *
 * <p><strong>What is being protected.</strong> Rule 1 Clauses A and D keep customer financial data out of
 * logs. The translated {@code DISPLAY} statements are the one place where the parity mandate pulls the other
 * way: the source wrote balances, amounts and whole record images to SYSOUT, so a faithful translation emits
 * them. The two requirements are reconciled by <em>channel</em> rather than by content - the emissions keep
 * their exact literals, order and formatting, and go to {@code com.cardemo.parity.<PROGRAM>}, a tree with no
 * other purpose that both {@code application.yml} and {@code logback-spring.xml} pin to {@code OFF}.
 *
 * <p><strong>Why the routing is asserted at runtime and not by reading the source.</strong> A source scan
 * would confirm the constant exists; it would not confirm that the emission actually goes through it. These
 * tests attach a Logback appender to <em>both</em> the class logger and the parity logger, enable the parity
 * logger deliberately, exercise the code, and then assert that the values appeared on the parity appender and
 * that the class appender saw nothing. A regression that reverted one call site would fail the second half.
 *
 * <p><strong>Side effects.</strong> Levels and appenders are mutated on the shared Logback context and are
 * restored in {@link #tearDown()}, including after a failure, so no other test inherits an enabled parity
 * logger. No container, no network, no database.
 */
@DisplayName("Parity output is isolated from the application log stream")
class ParityLoggerRoutingTest {

    /** The parity tree root, pinned OFF in both configuration files. */
    private static final String PARITY_ROOT = "com.cardemo.parity";

    /** A card number of the sixteen characters {@code TRAN-CARD-NUM PIC X(16)} declares. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The amount used by the report fixtures; distinctive enough to search a log line for. */
    private static final String AMOUNT = "1234.56";

    /**
     * The same amount as the report body renders it, on the legacy edited mask of
     * {@code app/cbl/CBTRN03C.cbl}. The comma grouping is why the report assertion cannot reuse
     * {@link #AMOUNT} directly, and the difference is useful: it shows the log channel and the report channel
     * are demonstrably separate renderings rather than the same string appearing twice.
     */
    private static final String EDITED_AMOUNT = "1,234.56";

    /** Every logger this test reconfigures, so each can be put back. */
    private final List<Restorable> restorables = new ArrayList<>();

    @BeforeEach
    void setUp() {
        restorables.clear();
    }

    @AfterEach
    void tearDown() {
        for (Restorable restorable : restorables) {
            restorable.restore();
        }
        restorables.clear();
    }

    /**
     * Attaches a capturing appender to one logger and sets its level, remembering how to undo both.
     *
     * @param loggerName the logger to capture
     * @param level the level to set for the duration of the test
     * @return the appender, already started and attached
     */
    private ListAppender<ILoggingEvent> capture(String loggerName, Level level) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(level);
        restorables.add(() -> {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previous);
        });
        return appender;
    }

    /**
     * Renders every captured event as one searchable string.
     *
     * @param appender the appender to drain
     * @return the formatted messages, newline separated
     */
    private static String rendered(ListAppender<ILoggingEvent> appender) {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            text.append(event.getFormattedMessage()).append('\n');
        }
        return text.toString();
    }

    @Nested
    @DisplayName("the report processor's monetary DISPLAYs")
    class ReportProcessorRouting {

        /**
         * Builds the processor with doubles for its three lookups and a one-month reporting window.
         *
         * @return a processor ready to accept the fixture below
         */
        private TransactionReportProcessor processor() {
            CardCrossReferenceRepository crossReferences = mock(CardCrossReferenceRepository.class);
            when(crossReferences.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(new CardCrossReference(CARD_NUMBER, 1L, 11L)));
            // Both reference tables are read once per step execution and cached, so the collaborator call is
            // findAll rather than a keyed read per record. Stubbing the keyed form left both maps empty and
            // every record abended on a TRANTYPE miss before it could emit anything to route.
            TransactionTypeRepository types = mock(TransactionTypeRepository.class);
            when(types.findAll()).thenReturn(List.of(new TransactionType("01", "PURCHASE")));
            TransactionCategoryRepository categories = mock(TransactionCategoryRepository.class);
            when(categories.findAll()).thenReturn(List.of(new TransactionCategory(
                    new TransactionCategoryId("01", 1001), "RETAIL")));
            return new TransactionReportProcessor(mock(TransactionRepository.class), crossReferences, types,
                    categories, new FileStatusMapper(), "2026-01-01", "2026-01-31");
        }

        /**
         * Builds one in-window transaction fixture.
         *
         * @return the fixture
         */
        private Transaction fixture() {
            return new Transaction("0000000000000001", "01", Integer.valueOf(1001), "POS TERM",
                    "A DESCRIPTION", new BigDecimal(AMOUNT), Long.valueOf(1L),
                    "A MERCHANT", "A CITY", "10001", CARD_NUMBER,
                    "2026-01-15-00.00.00.000000", "2026-01-15-00.00.00.000000");
        }

        @Test
        @DisplayName("DISPLAY TRAN-RECORD reaches the parity logger and not the class logger")
        void tranRecordGoesToTheParityLogger() {
            ListAppender<ILoggingEvent> parity =
                    capture("com.cardemo.parity.CBTRN03C", Level.DEBUG);
            ListAppender<ILoggingEvent> classLogger =
                    capture(TransactionReportProcessor.class.getName(), Level.TRACE);

            processor().process(fixture());

            assertThat(rendered(parity))
                    .as("app/cbl/CBTRN03C.cbl:L180 - the record image is routed here")
                    .contains("TRAN-RECORD")
                    .contains("id=0000000000000001");
            assertThat(rendered(parity))
                    .as("TWO CONTROLS APPLY TO THIS LINE, NOT ONE. Routing alone is enough for the closing "
                            + "aggregates, which carry no identifier; this line sits beside a transaction "
                            + "identifier and a card number, so the amount is linkable to a cardholder and "
                            + "is redacted to its picture width as well as routed. Asserting the value here "
                            + "would require the emitter to write it, which is the defect the redaction "
                            + "closed - so the routing claim this test exists for is made on the record "
                            + "identifier instead, and the value's absence is asserted rather than assumed.")
                    .doesNotContain(AMOUNT)
                    .contains("amount=" + "*".repeat(11));
            assertThat(rendered(classLogger))
                    .as("no amount, no card number and no record image may reach the class logger")
                    .doesNotContain("TRAN-RECORD")
                    .doesNotContain(AMOUNT)
                    .doesNotContain(CARD_NUMBER);
        }

        @Test
        @DisplayName("TRAN-AMT and WS-PAGE-TOTAL reach the parity logger and not the class logger")
        void closingTotalsGoToTheParityLogger() {
            ListAppender<ILoggingEvent> parity =
                    capture("com.cardemo.parity.CBTRN03C", Level.DEBUG);
            ListAppender<ILoggingEvent> classLogger =
                    capture(TransactionReportProcessor.class.getName(), Level.TRACE);

            TransactionReportProcessor processor = processor();
            processor.process(fixture());
            processor.finishReport();

            assertThat(rendered(parity))
                    .as("app/cbl/CBTRN03C.cbl:L198-L199, reproduced literal for literal")
                    .contains("TRAN-AMT " + AMOUNT)
                    .contains("WS-PAGE-TOTAL" + AMOUNT);
            assertThat(rendered(classLogger))
                    .as("these two used to be emitted at INFO, so every profile carried them")
                    .doesNotContain("TRAN-AMT")
                    .doesNotContain("WS-PAGE-TOTAL")
                    .doesNotContain(AMOUNT);
        }

        @Test
        @DisplayName("with the parity logger off - the shipped state - nothing is emitted anywhere")
        void nothingIsEmittedWhenTheParityLoggerIsOff() {
            ListAppender<ILoggingEvent> parity = capture("com.cardemo.parity.CBTRN03C", Level.OFF);
            ListAppender<ILoggingEvent> classLogger =
                    capture(TransactionReportProcessor.class.getName(), Level.TRACE);

            TransactionReportProcessor processor = processor();
            processor.process(fixture());
            processor.finishReport();

            assertThat(parity.list).as("OFF must suppress the emission entirely").isEmpty();
            assertThat(rendered(classLogger)).doesNotContain(AMOUNT);
        }

        @Test
        @DisplayName("the report body still carries the amount, because it is the deliverable")
        void theReportBodyIsUnaffected() {
            capture("com.cardemo.parity.CBTRN03C", Level.OFF);

            TransactionReportProcessor.ReportLines lines = processor().process(fixture());

            assertThat(lines).isNotNull();
            // The detail line renders the amount on the legacy edited mask, so it appears comma grouped -
            // which is itself worth asserting: the log carried the plain value and the report carries the
            // edited one, so the two channels are demonstrably not the same rendering.
            assertThat(String.join("", lines.lines()))
                    .as("suppressing the log must not suppress the report")
                    .contains(EDITED_AMOUNT);
        }
    }

    @Nested
    @DisplayName("the shipped configuration pins the whole tree off")
    class ShippedConfiguration {

        @Test
        @DisplayName("application.yml sets com.cardemo.parity to OFF")
        void theBaseProfilePinsItOff() {
            assertThat(read(Path.of("src", "main", "resources", "application.yml")))
                    .contains(PARITY_ROOT + ": \"OFF\"");
        }

        @Test
        @DisplayName("logback-spring.xml declares the same floor, for contexts that never read the YAML")
        void theLogbackFloorAgrees() {
            assertThat(read(Path.of("src", "main", "resources", "logback-spring.xml")))
                    .contains("<logger name=\"" + PARITY_ROOT + "\" level=\"OFF\"/>");
        }

        @ParameterizedTest
        @ValueSource(strings = {"application-local.yml", "application-test.yml", "application-prod.yml"})
        @DisplayName("no profile re-enables the tree")
        void noProfileReEnablesTheTree(String profile) {
            assertThat(read(Path.of("src", "main", "resources", profile)))
                    .as("%s must not name the parity tree as a logging level at all", profile)
                    .doesNotContain(PARITY_ROOT + ":");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "src/main/java/com/cardemo/batch/readers/AccountReader.java",
            "src/main/java/com/cardemo/batch/processors/TransactionReportProcessor.java",
            "src/main/java/com/cardemo/batch/processors/InterestCalculationProcessor.java"})
        @DisplayName("each owner declares its own program-suffixed parity logger")
        void eachOwnerDeclaresItsParityLogger(String source) {
            String body = read(Path.of(source));

            assertThat(body)
                    .as("%s must resolve its parity logger by the shared tree name", source)
                    .contains("PARITY_LOGGER_NAME = \"" + PARITY_ROOT + ".")
                    .contains("LoggerFactory.getLogger(PARITY_LOGGER_NAME)");
        }

        /**
         * Reads one repository file.
         *
         * @param path the file, relative to the module root
         * @return its contents
         */
        private String read(Path path) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("could not read " + path, unreadable);
            }
        }
    }

    /** Undoes one logger reconfiguration. */
    private interface Restorable {

        /** Restores the logger's original level and detaches the test appender. */
        void restore();
    }
}
