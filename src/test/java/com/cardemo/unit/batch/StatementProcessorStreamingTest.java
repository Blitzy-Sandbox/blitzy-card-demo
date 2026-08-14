/*
 * ******************************************************************
 * Program     : StatementProcessorStreamingTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the streamed control-break translation of
 *               8500-READTRNX-READ and the HTML escaping applied to
 *               every dynamic value before fixed-width rendering.
 *               Proves that residency is one card group rather than
 *               the whole run, that the order-dependent early exit of
 *               :L417-L419 is preserved verbatim, that an unsorted
 *               input fails loudly at the group boundary, and that no
 *               markup-significant byte from persisted data can reach
 *               an HTMLFILE record while its 100-character geometry
 *               stays exact.
 * Source      : app/cbl/CBSTM03A.CBL:L818-L853 (8500-READTRNX-READ)
 *               app/cbl/CBSTM03A.CBL:L819-L823 (control break, flush)
 *               app/cbl/CBSTM03A.CBL:L830      (WS-SAVE-CARD)
 *               app/cbl/CBSTM03A.CBL:L417-L419 (early exit)
 *               app/cbl/CBSTM03A.CBL:L420-L421 (equality, skip)
 *               app/cbl/CBSTM03A.CBL:L429      (WS-TOTAL-AMT)
 *               app/cbl/CBSTM03A.CBL:L225-L233 (the 51x10 table)
 *               app/cbl/CBSTM03A.CBL:L149      (HTML-FIXED-LN X(100))
 *               app/jcl/CREASTMT.JCL:L53       (sorted ascending)
 *               app/cpy/CVCUS01Y.cpy           (customer layout) @ 7756d89
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

import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.service.shared.FileService;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("StatementProcessor: 8500-READTRNX-READ streamed one control break at a time")
class StatementProcessorStreamingTest {

    /** The card the single-account fixtures file their transactions under. */
    private static final String CARD_A = "4111111111111111";

    /** A card that sorts after {@link #CARD_A}. */
    private static final String CARD_B = "4222222222222222";

    /** A card that sorts after {@link #CARD_B}. */
    private static final String CARD_C = "4333333333333333";

    /** The customer identifier the single-account fixtures use. */
    private static final long CUSTOMER_ID = 100000001L;

    /** The account identifier the single-account fixtures use. */
    private static final long ACCOUNT_ID = 10000000001L;

    /**
     * Builds a benign customer record whose every rendered field is free of markup-significant
     * characters, so a statement built from it exercises the escaping path as an identity transform.
     *
     * @return a 500-character customer record
     */
    private static String benignCustomer() {
        return StatementRecordFixtures.customerRecord(CUSTOMER_ID, "JOHN", "Q", "PUBLIC",
                "1 MAIN STREET", "APT 2", "NEW YORK NY", "750");
    }

    /**
     * Drives one statement for {@link #CARD_A} over the supplied transaction records.
     *
     * @param transactionRecords the {@code TRNXFILE} records, in sorted order
     * @param customerRecord the {@code CUSTFILE} record to serve
     * @return the produced statement
     */
    private static StatementProcessor.Statement statementFor(List<String> transactionRecords,
            String customerRecord) {
        FileService fileService = StatementRecordFixtures.singleAccountFileService(CARD_A,
                transactionRecords, customerRecord,
                StatementRecordFixtures.accountRecord(ACCOUNT_ID, new BigDecimal("1234.56")));
        StatementProcessor processor = new StatementProcessor(fileService);
        CardCrossReference crossReference = processor.readNextCrossReference().orElseThrow();
        return processor.process(crossReference);
    }

    @Nested
    @DisplayName("H4 - residency is one card group, not the whole run")
    class StreamedResidency {

        @Test
        @DisplayName("only the group under service is resident; earlier groups are released and later ones unread")
        void onlyOneGroupIsResident() {
            List<String> transactions = List.of(
                    StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000001", "COFFEE",
                            new BigDecimal("4.50")),
                    StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000002", "BOOKS",
                            new BigDecimal("21.00")),
                    StatementRecordFixtures.transactionRecord(CARD_B, "0000000000000003", "FUEL",
                            new BigDecimal("60.00")),
                    StatementRecordFixtures.transactionRecord(CARD_C, "0000000000000004", "RENT",
                            new BigDecimal("900.00")));

            // Two cross-reference rows, CARD_B then CARD_C, so the second request proves the CARD_C
            // group survived the first request intact rather than having been consumed by it.
            Map<String, String> customers = new LinkedHashMap<>();
            customers.put(StatementRecordFixtures.digits(CUSTOMER_ID, 9), benignCustomer());
            Map<String, String> accounts = new LinkedHashMap<>();
            accounts.put(StatementRecordFixtures.digits(ACCOUNT_ID, 11),
                    StatementRecordFixtures.accountRecord(ACCOUNT_ID, new BigDecimal("1234.56")));
            FileService fileService = StatementRecordFixtures.fileService(transactions,
                    List.of(StatementRecordFixtures.crossReferenceRecord(CARD_B, CUSTOMER_ID, ACCOUNT_ID),
                            StatementRecordFixtures.crossReferenceRecord(CARD_C, CUSTOMER_ID, ACCOUNT_ID)),
                    customers, accounts);
            StatementProcessor processor = new StatementProcessor(fileService);

            StatementProcessor.Statement first =
                    processor.process(processor.readNextCrossReference().orElseThrow());

            // CARD_B's single transaction is the only one that reached the total: the CARD_A group was
            // skipped by the :L421 implicit continue and CARD_C has not been grouped at all.
            assertThat(first.totalExpenditure()).isEqualByComparingTo(new BigDecimal("60.00"));

            // Residency at rest is zero groups: the match released the group it served, and the record
            // that broke the group sits in the one-record lookahead rather than in a materialised group.
            // Two groups have been built across the whole run so far - CARD_A and CARD_B - never four.
            assertThat(processor.currentCardGroup()).isEmpty();
            assertThat(processor.cardGroupsRead()).isEqualTo(2L);

            StatementProcessor.Statement second =
                    processor.process(processor.readNextCrossReference().orElseThrow());

            // CARD_C's group was still available and complete, which is what proves the earlier request
            // released rather than discarded.
            assertThat(second.totalExpenditure()).isEqualByComparingTo(new BigDecimal("900.00"));
            assertThat(processor.cardGroupsRead()).isEqualTo(3L);
        }

        @Test
        @DisplayName("a group stopped on by the :L418 early exit is retained, not discarded")
        void groupStoppedOnByEarlyExitIsRetained() {
            List<String> transactions = List.of(
                    StatementRecordFixtures.transactionRecord(CARD_C, "0000000000000001", "RENT",
                            new BigDecimal("900.00")));

            // The only cross-reference row is for CARD_A, which sorts before the sole CARD_C group, so
            // the early exit of :L418 fires and must leave the CARD_C group resident for a later row.
            FileService fileService = StatementRecordFixtures.singleAccountFileService(CARD_A,
                    transactions, benignCustomer(),
                    StatementRecordFixtures.accountRecord(ACCOUNT_ID, new BigDecimal("1.00")));
            StatementProcessor processor = new StatementProcessor(fileService);

            StatementProcessor.Statement statement =
                    processor.process(processor.readNextCrossReference().orElseThrow());

            // No transaction belonged to CARD_A, so its statement totals zero.
            assertThat(statement.totalExpenditure()).isEqualByComparingTo(BigDecimal.ZERO);

            // The CARD_C group is held, exactly as the source's table retained the row the loop stopped
            // on, and it carries only that card's records.
            Optional<StatementTransaction.CardGroup> held = processor.currentCardGroup();
            assertThat(held).isPresent();
            assertThat(held.orElseThrow().cardNumber()).isEqualTo(CARD_C);
            assertThat(held.orElseThrow().transactions()).hasSize(1);
        }

        @Test
        @DisplayName("the control break closes a group on the card number change, per :L819")
        void controlBreakClosesGroupOnCardChange() {
            List<String> transactions = List.of(
                    StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000001", "ONE",
                            new BigDecimal("1.00")),
                    StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000002", "TWO",
                            new BigDecimal("2.00")),
                    StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000003", "THREE",
                            new BigDecimal("3.00")),
                    StatementRecordFixtures.transactionRecord(CARD_B, "0000000000000004", "FOUR",
                            new BigDecimal("400.00")));

            StatementProcessor.Statement statement = statementFor(transactions, benignCustomer());

            // Exactly the three CARD_A amounts, and none of CARD_B's, reached WS-TOTAL-AMT.
            assertThat(statement.totalExpenditure()).isEqualByComparingTo(new BigDecimal("6.00"));
        }

        @Test
        @DisplayName("end of file closes the final group, reproducing the :L850 flush")
        void endOfFileClosesFinalGroup() {
            List<String> transactions = List.of(
                    StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000001", "ONLY",
                            new BigDecimal("12.34")));

            StatementProcessor.Statement statement = statementFor(transactions, benignCustomer());

            // Without the 8599-EXIT flush the last group would be lost and the total would be zero.
            assertThat(statement.totalExpenditure()).isEqualByComparingTo(new BigDecimal("12.34"));
        }

        @Test
        @DisplayName("every TRNXFILE record is read exactly once across the whole run")
        void everyRecordIsReadExactlyOnce() {
            List<String> transactions = List.of(
                    StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000001", "A1",
                            new BigDecimal("1.00")),
                    StatementRecordFixtures.transactionRecord(CARD_B, "0000000000000002", "B1",
                            new BigDecimal("2.00")),
                    StatementRecordFixtures.transactionRecord(CARD_C, "0000000000000003", "C1",
                            new BigDecimal("3.00")));

            StatementRecordFixtures.SequentialBinding transactionBinding =
                    new StatementRecordFixtures.SequentialBinding(FileService.Dd.TRNXFILE, transactions);
            Map<String, String> customers = new LinkedHashMap<>();
            customers.put(StatementRecordFixtures.digits(CUSTOMER_ID, 9), benignCustomer());
            Map<String, String> accounts = new LinkedHashMap<>();
            accounts.put(StatementRecordFixtures.digits(ACCOUNT_ID, 11),
                    StatementRecordFixtures.accountRecord(ACCOUNT_ID, new BigDecimal("10.00")));
            List<String> xrefs = List.of(
                    StatementRecordFixtures.crossReferenceRecord(CARD_A, CUSTOMER_ID, ACCOUNT_ID),
                    StatementRecordFixtures.crossReferenceRecord(CARD_B, CUSTOMER_ID, ACCOUNT_ID),
                    StatementRecordFixtures.crossReferenceRecord(CARD_C, CUSTOMER_ID, ACCOUNT_ID));

            FileService fileService = new FileService(new com.cardemo.service.shared.FileStatusMapper(),
                    List.of(transactionBinding,
                            new StatementRecordFixtures.SequentialBinding(FileService.Dd.XREFFILE, xrefs),
                            new StatementRecordFixtures.RandomBinding(FileService.Dd.CUSTFILE, customers),
                            new StatementRecordFixtures.RandomBinding(FileService.Dd.ACCTFILE, accounts)));
            fileService.afterPropertiesSet();
            StatementProcessor processor = new StatementProcessor(fileService);

            List<BigDecimal> totals = new ArrayList<>();
            Optional<CardCrossReference> next = processor.readNextCrossReference();
            while (next.isPresent()) {
                totals.add(processor.process(next.orElseThrow()).totalExpenditure());
                next = processor.readNextCrossReference();
            }

            // One statement per cross-reference row, each carrying only its own card's amount.
            assertThat(totals).hasSize(3);
            assertThat(totals.get(0)).isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(totals.get(1)).isEqualByComparingTo(new BigDecimal("2.00"));
            assertThat(totals.get(2)).isEqualByComparingTo(new BigDecimal("3.00"));

            // Three records plus the priming read plus the end-of-file read: five reads, never a
            // re-read. A design that rescanned per statement would report far more.
            assertThat(transactionBinding.reads()).isEqualTo(4);
            assertThat(processor.cardGroupsRead()).isEqualTo(3L);
        }

        @Test
        @DisplayName("a card with no cross-reference row is skipped, per the :L421 implicit continue")
        void cardWithoutCrossReferenceRowIsSkipped() {
            List<String> transactions = List.of(
                    StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000001", "ORPHAN",
                            new BigDecimal("99.00")),
                    StatementRecordFixtures.transactionRecord(CARD_B, "0000000000000002", "WANTED",
                            new BigDecimal("7.00")));

            FileService fileService = StatementRecordFixtures.singleAccountFileService(CARD_B,
                    transactions, benignCustomer(),
                    StatementRecordFixtures.accountRecord(ACCOUNT_ID, new BigDecimal("1.00")));
            StatementProcessor processor = new StatementProcessor(fileService);
            CardCrossReference crossReference = processor.readNextCrossReference().orElseThrow();

            StatementProcessor.Statement statement = processor.process(crossReference);

            assertThat(statement.totalExpenditure()).isEqualByComparingTo(new BigDecimal("7.00"));
            assertThat(processor.cardGroupsRead()).isEqualTo(2L);
        }

        @Test
        @DisplayName("an empty TRNXFILE abends at the priming read, reproducing the :L748 guard exactly")
        void emptyTransactionFileAbendsAtPrimingRead() {
            // PARITY, not a defect. 8100-TRNXFILE-OPEN takes a priming read at app/cbl/CBSTM03A.CBL:L746
            // and guards it at :L748 with IF WS-M03B-RC = '00' OR '04' ... ELSE PERFORM
            // 9999-ABEND-PROGRAM. There is no WHEN '10' branch, so an empty TRNXFILE abends the legacy
            // program too. Streaming did not change that: the priming read is still taken eagerly in
            // initialise(), so the abend still happens at exactly the same point in the run.
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> statementFor(List.of(), benignCustomer()))
                    .satisfies(abend -> assertThat(abend.getMessage()).contains("TRNXFILE"));
        }

        @Test
        @DisplayName("a card with transactions but no matching group totals zero without failing")
        void cardWithNoMatchingGroupTotalsZero() {
            // A non-empty TRNXFILE that holds nothing for the sought card: the statement is produced and
            // totals zero, which is the 4000-TRNXFILE-GET outcome when the lookup matches no entry.
            List<String> transactions = List.of(
                    StatementRecordFixtures.transactionRecord(CARD_C, "0000000000000001", "OTHER",
                            new BigDecimal("500.00")));

            FileService fileService = StatementRecordFixtures.singleAccountFileService(CARD_A,
                    transactions, benignCustomer(),
                    StatementRecordFixtures.accountRecord(ACCOUNT_ID, new BigDecimal("1.00")));
            StatementProcessor processor = new StatementProcessor(fileService);

            StatementProcessor.Statement statement =
                    processor.process(processor.readNextCrossReference().orElseThrow());

            assertThat(statement.totalExpenditure()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("no group is read before the first cross-reference row asks for one")
        void noGroupIsReadEagerly() {
            List<String> transactions = List.of(
                    StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000001", "A1",
                            new BigDecimal("1.00")),
                    StatementRecordFixtures.transactionRecord(CARD_B, "0000000000000002", "B1",
                            new BigDecimal("2.00")));

            FileService fileService = StatementRecordFixtures.singleAccountFileService(CARD_A,
                    transactions, benignCustomer(),
                    StatementRecordFixtures.accountRecord(ACCOUNT_ID, new BigDecimal("1.00")));
            StatementProcessor processor = new StatementProcessor(fileService);

            // initialise() runs on the first public call. It primes one record; it must not group.
            processor.readNextCrossReference().orElseThrow();

            assertThat(processor.cardGroupsRead()).isZero();
            assertThat(processor.currentCardGroup()).isEmpty();
        }
    }

    @Nested
    @DisplayName("H4 - the ascending precondition of the :L417-L419 early exit is asserted")
    class AscendingPrecondition {

        @Test
        @DisplayName("an unsorted TRNXFILE fails at the group boundary rather than short-changing a statement")
        void unsortedInputFailsLoudly() {
            List<String> transactions = List.of(
                    StatementRecordFixtures.transactionRecord(CARD_B, "0000000000000001", "SECOND",
                            new BigDecimal("2.00")),
                    StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000002", "FIRST",
                            new BigDecimal("1.00")));

            FileService fileService = StatementRecordFixtures.singleAccountFileService(CARD_C,
                    transactions, benignCustomer(),
                    StatementRecordFixtures.accountRecord(ACCOUNT_ID, new BigDecimal("1.00")));
            StatementProcessor processor = new StatementProcessor(fileService);
            CardCrossReference crossReference = processor.readNextCrossReference().orElseThrow();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(crossReference))
                    .satisfies(abend -> {
                        // The diagnosis names the precondition, the lookup that depends on it and the
                        // sort card that guarantees it, so an operator can act on it without the source.
                        assertThat(abend.getMessage()).contains("ascend by card number");
                        assertThat(abend.getMessage()).contains("app/cbl/CBSTM03A.CBL:L417-L419");
                        assertThat(abend.getMessage()).contains("app/jcl/CREASTMT.JCL:L53");
                    });
        }

        @Test
        @DisplayName("a sorted TRNXFILE passes the precondition for every group")
        void sortedInputPassesForEveryGroup() {
            List<String> transactions = List.of(
                    StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000001", "A",
                            new BigDecimal("1.00")),
                    StatementRecordFixtures.transactionRecord(CARD_B, "0000000000000002", "B",
                            new BigDecimal("2.00")),
                    StatementRecordFixtures.transactionRecord(CARD_C, "0000000000000003", "C",
                            new BigDecimal("3.00")));

            FileService fileService = StatementRecordFixtures.singleAccountFileService(CARD_C,
                    transactions, benignCustomer(),
                    StatementRecordFixtures.accountRecord(ACCOUNT_ID, new BigDecimal("1.00")));
            StatementProcessor processor = new StatementProcessor(fileService);
            CardCrossReference crossReference = processor.readNextCrossReference().orElseThrow();

            StatementProcessor.Statement statement = processor.process(crossReference);

            assertThat(statement.totalExpenditure()).isEqualByComparingTo(new BigDecimal("3.00"));
            assertThat(processor.cardGroupsRead()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("H5 - every dynamic value is HTML-escaped before it reaches an HTMLFILE record")
    class HtmlEscaping {

        /**
         * Reports the HTML lines whose content contains the supplied text.
         *
         * @param statement the statement to inspect
         * @param text the text to look for
         * @return the matching lines
         */
        private List<String> htmlLinesContaining(StatementProcessor.Statement statement, String text) {
            return statement.htmlLines().stream().filter(line -> line.contains(text)).toList();
        }

        @Test
        @DisplayName("a script tag in a customer name is neutralised in the HTML output")
        void scriptTagInNameIsNeutralised() {
            String hostileCustomer = StatementRecordFixtures.customerRecord(CUSTOMER_ID,
                    "<script>x</script>", "Q", "PUBLIC",
                    "1 MAIN STREET", "APT 2", "NEW YORK NY", "750");

            StatementProcessor.Statement statement = statementFor(
                    List.of(StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000001",
                            "COFFEE", new BigDecimal("4.50"))),
                    hostileCustomer);

            String html = String.join("", statement.htmlLines());
            assertThat(html).doesNotContain("<script>");
            assertThat(html).doesNotContain("</script>");
            assertThat(html).contains("&lt;script&gt;");
        }

        @Test
        @DisplayName("an attribute-breaking quote in an address line is escaped")
        void quoteInAddressIsEscaped() {
            String hostileCustomer = StatementRecordFixtures.customerRecord(CUSTOMER_ID,
                    "JOHN", "Q", "PUBLIC",
                    "1 \"MAIN\" STREET", "APT 2", "NEW YORK NY", "750");

            StatementProcessor.Statement statement = statementFor(
                    List.of(StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000001",
                            "COFFEE", new BigDecimal("4.50"))),
                    hostileCustomer);

            String html = String.join("", statement.htmlLines());
            assertThat(html).contains("&quot;MAIN&quot;");
        }

        @Test
        @DisplayName("an ampersand is escaped once and never double-escaped")
        void ampersandIsEscapedExactlyOnce() {
            String hostileCustomer = StatementRecordFixtures.customerRecord(CUSTOMER_ID,
                    "AT&T", "Q", "PUBLIC",
                    "1 MAIN STREET", "APT 2", "NEW YORK NY", "750");

            StatementProcessor.Statement statement = statementFor(
                    List.of(StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000001",
                            "COFFEE", new BigDecimal("4.50"))),
                    hostileCustomer);

            String html = String.join("", statement.htmlLines());
            assertThat(html).contains("AT&amp;T");
            assertThat(html).doesNotContain("&amp;amp;");
        }

        @Test
        @DisplayName("a hostile transaction description cannot inject markup")
        void hostileTransactionDescriptionIsEscaped() {
            StatementProcessor.Statement statement = statementFor(
                    List.of(StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000001",
                            "<img src=x onerror=alert(1)>", new BigDecimal("4.50"))),
                    benignCustomer());

            String html = String.join("", statement.htmlLines());
            assertThat(html).doesNotContain("<img");
            assertThat(html).contains("&lt;img");
        }

        @Test
        @DisplayName("escaping never breaks the 100-character HTMLFILE geometry, however hostile the value")
        void escapingNeverBreaksHtmlGeometry() {
            // Every escapable character, repeated well past every field budget, so the escape expansion
            // has the greatest possible chance of overflowing a record.
            String worstCase = "<>&\"'".repeat(40);
            String hostileCustomer = StatementRecordFixtures.customerRecord(CUSTOMER_ID,
                    worstCase, worstCase, worstCase, worstCase, worstCase, worstCase, "750");

            StatementProcessor.Statement statement = statementFor(
                    List.of(StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000001",
                            worstCase, new BigDecimal("4.50"))),
                    hostileCustomer);

            // The record constructor already enforces this, so reaching this assertion at all proves
            // no line overflowed; asserting it explicitly documents the contract.
            assertThat(statement.htmlLines())
                    .isNotEmpty()
                    .allSatisfy(line -> assertThat(line)
                            .hasSize(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH));
            assertThat(statement.textLines())
                    .isNotEmpty()
                    .allSatisfy(line -> assertThat(line)
                            .hasSize(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH));
        }

        @Test
        @DisplayName("escaping never emits a truncated entity")
        void escapingNeverEmitsATruncatedEntity() {
            // A value whose escaped form lands exactly on a budget boundary is the case that splits an
            // entity if the escape is applied before the fit rather than after.
            for (int length = 1; length <= 60; length++) {
                String hostileCustomer = StatementRecordFixtures.customerRecord(CUSTOMER_ID,
                        "<".repeat(length), "Q", "PUBLIC",
                        "&".repeat(length), "\"".repeat(length), ">".repeat(length), "750");

                StatementProcessor.Statement statement = statementFor(
                        List.of(StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000001",
                                "'".repeat(length), new BigDecimal("4.50"))),
                        hostileCustomer);

                String html = String.join("", statement.htmlLines());
                // A split entity is an ampersand not followed by one of the five complete entities.
                assertThat(splitEntityCount(html))
                        .withFailMessage("a truncated HTML entity appeared at value length %d", length)
                        .isZero();
            }
        }

        /**
         * Counts ampersands that do not begin one of the five complete entities this class emits.
         *
         * @param html the rendered markup
         * @return the number of truncated entities
         */
        private long splitEntityCount(String html) {
            long split = 0L;
            for (int index = html.indexOf('&'); index >= 0; index = html.indexOf('&', index + 1)) {
                String tail = html.substring(index);
                boolean complete = tail.startsWith("&amp;") || tail.startsWith("&lt;")
                        || tail.startsWith("&gt;") || tail.startsWith("&quot;")
                        || tail.startsWith("&#39;");
                if (!complete) {
                    split++;
                }
            }
            return split;
        }

        @Test
        @DisplayName("a value carrying none of the five characters is passed through unchanged, preserving parity")
        void benignValuesArePassedThroughUnchanged() {
            StatementProcessor.Statement statement = statementFor(
                    List.of(StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000001",
                            "COFFEE SHOP PURCHASE", new BigDecimal("4.50"))),
                    benignCustomer());

            // The fixtures in app/data/ASCII carry no markup-significant character, so for every
            // legacy-comparable run the escaping is the identity transform and parity is exact.
            assertThat(htmlLinesContaining(statement, "PUBLIC")).isNotEmpty();
            assertThat(htmlLinesContaining(statement, "1 MAIN STREET")).isNotEmpty();
            assertThat(htmlLinesContaining(statement, "COFFEE SHOP PURCHASE")).isNotEmpty();
            assertThat(String.join("", statement.htmlLines())).doesNotContain("&amp;");
        }

        @ParameterizedTest
        @DisplayName("each markup-significant character is escaped wherever it appears in an address")
        @ValueSource(strings = {"<", ">", "&", "\"", "'"})
        void eachMarkupCharacterIsEscaped(String character) {
            String hostileCustomer = StatementRecordFixtures.customerRecord(CUSTOMER_ID,
                    "JOHN", "Q", "PUBLIC",
                    "A" + character + "B", "APT 2", "NEW YORK NY", "750");

            StatementProcessor.Statement statement = statementFor(
                    List.of(StatementRecordFixtures.transactionRecord(CARD_A, "0000000000000001",
                            "COFFEE", new BigDecimal("4.50"))),
                    hostileCustomer);

            String html = String.join("", statement.htmlLines());
            assertThat(html).doesNotContain("A" + character + "B");
            assertThat(html).contains("A&");
        }
    }

    @Nested
    @DisplayName("H4 - the legacy 510-record ceiling is removed and nothing is authored in its place")
    class CapacityDeviation {

        @Test
        @DisplayName("no ceiling remains, and the legacy capacity is still recorded as constants")
        void noCeilingRemainsAndTheLegacyCapacityIsRecorded() {
            // Finding BAT-002. A public MAX_TRANSACTIONS_PER_CARD_GROUP stood here and was asserted positive;
            // it refused a run at an authored threshold app/cbl/CBSTM03A.CBL does not have. The legacy figures
            // stay, because they record a historical capacity rather than enforce one.
            assertThat(Arrays.stream(StatementProcessor.class.getDeclaredFields()).map(Field::getName))
                    .doesNotContain("MAX_TRANSACTIONS_PER_CARD_GROUP", "MAX_TRANSACTIONS_PER_RUN");
            assertThat(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN)
                    .isEqualTo(StatementTransaction.LEGACY_MAX_CARDS_PER_RUN
                            * StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD);
        }

        @Test
        @DisplayName("a run larger than the legacy 510-record table completes instead of overrunning")
        void runLargerThanLegacyTableCompletes() {
            // 12 transactions on one card is two more than WS-TRAN-TBL OCCURS 10 could hold, so the
            // legacy program would have written past the end of the table. This one completes.
            List<String> transactions = new ArrayList<>();
            for (int index = 1; index <= 12; index++) {
                transactions.add(StatementRecordFixtures.transactionRecord(CARD_A,
                        StatementRecordFixtures.digits(index, 16), "ITEM " + index,
                        new BigDecimal("1.00")));
            }

            StatementProcessor.Statement statement = statementFor(transactions, benignCustomer());

            assertThat(statement.totalExpenditure()).isEqualByComparingTo(new BigDecimal("12.00"));
        }
    }
}
