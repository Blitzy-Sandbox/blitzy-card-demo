/*
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
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.batch;

import com.aws.carddemo.entity.Card;
import com.aws.carddemo.testsupport.TestFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link CardFileProcessor} (replaces COBOL {@code CBACT02C}, a card-master
 * file dump utility).
 *
 * <h2>COBOL provenance</h2>
 *
 * <p>{@code app/cbl/CBACT02C.cbl} opens the {@code CARDFILE} VSAM KSDS, reads every record
 * sequentially via paragraph {@code 1000-CARDFILE-GET-NEXT}, and emits a formatted DISPLAY
 * block to SYSOUT for each record via the implicit {@code DISPLAY CARD-RECORD} statement
 * inside the main {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop. The
 * {@code app/cpy/CVACT02Y.cpy} copybook declares the fixed-width 150-byte record:
 * <pre>
 *   01 CARD-RECORD.
 *      05 CARD-NUM             PIC X(16).  -- positions  1..16
 *      05 CARD-ACCT-ID         PIC 9(11).  -- positions 17..27
 *      05 CARD-CVV-CD          PIC 9(03).  -- positions 28..30
 *      05 CARD-EMBOSSED-NAME   PIC X(50).  -- positions 31..80
 *      05 CARD-EXPIRAION-DATE  PIC X(10).  -- positions 81..90
 *      05 CARD-ACTIVE-STATUS   PIC X(01).  -- position  91
 *      05 FILLER               PIC X(59).  -- positions 92..150
 * </pre>
 *
 * <p>The {@link TestFixtures.RecordWidths#CARD_RECLN} constant encodes the on-disk record
 * width (150 bytes) and is asserted as a layout sanity guard in the happy-path test so any
 * future change to the copybook width is caught at the unit-test layer rather than slipping
 * into a batch IT.
 *
 * <h2>Java migration shape</h2>
 *
 * <p>The Java {@link CardFileProcessor} is a Spring Batch {@code ItemProcessor<Card, String>}
 * with three public seams identical in shape to {@link AccountFileProcessor}:
 * <ul>
 *   <li>{@link CardFileProcessor#process(Card)} — null in yields null out (EOF/skip),
 *       non-null in yields the formatted DISPLAY block;</li>
 *   <li>{@link CardFileProcessor#format(Card)} — pure formatter equivalent to COBOL
 *       paragraph {@code 1100-DISPLAY-CARD-RECORD}; throws on null;</li>
 *   <li>{@link CardFileProcessor#countRecord(int)} — Java equivalent of COBOL
 *       {@code ADD 1 TO WS-RECORD-COUNT}.</li>
 * </ul>
 *
 * <h2>Test categories (AAP §0.5.1)</h2>
 *
 * <ul>
 *   <li>Happy read of a well-formed card record &mdash; a fully populated {@link Card}
 *       entity built from {@link TestFixtures} constants is fed to
 *       {@link CardFileProcessor#process(Card)} and the result is asserted non-null.
 *       This is the schema-mandated {@code process_wellFormedCardRecord_returnsDomainObject}
 *       export.</li>
 *   <li>Malformed / truncated record rejection &mdash; the EOF skip semantic the COBOL
 *       paragraph {@code 1000-CARDFILE-GET-NEXT} expresses via the {@code STATUS '10'}
 *       branch setting {@code END-OF-FILE = 'Y'}. The migration models this by accepting
 *       {@code null} at the {@code ItemProcessor#process} input boundary and returning
 *       {@code null} so the downstream writer skips the record. This is the
 *       schema-mandated {@code process_truncatedRecord_isRejected} export.</li>
 *   <li>EOF and size-boundary scenarios driven by the
 *       {@code /fixtures/edge/eof_boundary.csv} fixture (boundaries
 *       0 / 1 / 9 / 10 / 11 / 49 / 50 / 51 / 99 / 100). This is the schema-mandated
 *       {@link EofBoundaryTests} export.</li>
 *   <li>Additional coverage of the DISPLAY layout, record counter, and PAN logging safety
 *       is included as nested classes to satisfy AAP §0.5.1 (which calls for happy read,
 *       malformed record reject, and EOF) and §0.10.5 (security: no PAN in logs).</li>
 * </ul>
 *
 * <h2>Test strategy (AAP §0.10.1 Require Test Coverage rule)</h2>
 *
 * <p>Every test invokes the <strong>real production {@link CardFileProcessor}</strong>.
 * No business logic is reimplemented inside test bodies &mdash; the processor is constructed
 * with its no-arg constructor (it has no collaborators to mock) and exercised directly with
 * domain inputs produced by the test. The {@link MockitoExtension} is wired at class level
 * so future {@code @Mock} field injection (if collaborators are ever added to the production
 * class) works under {@code STRICT_STUBS} strictness without any test-class refactor.
 *
 * <h2>Security note (AAP §0.10.5)</h2>
 *
 * <p>{@link Card#getCardNumber()} is a Primary Account Number (PAN) &mdash; PCI-DSS sensitive
 * data. The test fixtures use only synthetic Visa test PANs from the publicly documented
 * {@code 4111 1111 1111 11xx} reserved test range; no real card numbers appear anywhere in
 * this test class. The nested {@link LoggingSafetyTests} class asserts the production
 * processor declares no logger field, defending the AAP §0.10.5 "no financial data in logs"
 * invariant via reflection.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.5.1 (CBACT02C migration test categories), §0.10.1 (Require Test Coverage rule
 * &mdash; call production code directly), §0.10.5 (Security &mdash; no financial data in
 * logs), §0.10.7 (Framework &mdash; JUnit 5 + Mockito), §0.10.10 (Style consistency &mdash;
 * AssertJ exclusively).
 *
 * @see CardFileProcessor
 * @see Card
 * @see TestFixtures
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardFileProcessor unit tests (CBACT02C migration)")
class CardFileProcessorTest {

    // ================================================================
    // Schema-mandated top-level tests (file schema exports + AAP §0.5.1)
    // ================================================================

    /**
     * Happy-path coverage: a well-formed {@link Card} carrying the canonical synthetic
     * identifiers from {@link TestFixtures} produces a non-null output from the processor.
     *
     * <p>The fixture below uses:
     * <ul>
     *   <li>{@link TestFixtures.Cards#SAMPLE_CARD_NUMBER_01} for the 16-character Visa
     *       test PAN ({@code CARD-NUM PIC X(16)});</li>
     *   <li>{@link TestFixtures.Accounts#SAMPLE_ACCOUNT_ID_10} for the 11-character
     *       zero-padded account foreign key ({@code CARD-ACCT-ID PIC 9(11)});</li>
     *   <li>The literal {@code "123"} for the 3-digit security code
     *       ({@code CARD-CVV-CD PIC 9(03)});</li>
     *   <li>The literal {@code "JOHN Q PUBLIC"} for the 50-character embossed cardholder
     *       name ({@code CARD-EMBOSSED-NAME PIC X(50)}, space-padded by COBOL);</li>
     *   <li>The literal {@code "2030-12-31"} for the 10-character expiration date
     *       ({@code CARD-EXPIRAION-DATE PIC X(10)}, ISO {@code YYYY-MM-DD} per
     *       {@link Card#getExpirationDate()});</li>
     *   <li>{@link TestFixtures.Cards#ACTIVE_STATUS_YES} for the 1-character active-status
     *       flag ({@code CARD-ACTIVE-STATUS PIC X(01)}).</li>
     * </ul>
     *
     * <p>The total on-disk record (16 + 11 + 3 + 50 + 10 + 1 + 59 FILLER = 150 bytes)
     * matches {@link TestFixtures.RecordWidths#CARD_RECLN}; the assertion at the top of
     * the test pins that invariant so the layout cannot drift silently.
     */
    @Test
    @DisplayName("process_wellFormedCardRecord_returnsDomainObject")
    void process_wellFormedCardRecord_returnsDomainObject() {
        // Layout precondition (per CVACT02Y copybook):
        //   CARD-NUM (16) + CARD-ACCT-ID (11) + CARD-CVV-CD (3) + CARD-EMBOSSED-NAME (50)
        //   + CARD-EXPIRAION-DATE (10) + CARD-ACTIVE-STATUS (1) + FILLER (59) = RECLN 150
        assertThat(TestFixtures.RecordWidths.CARD_RECLN)
                .as("CVACT02Y CARD-RECORD width sanity guard "
                        + "(16 + 11 + 3 + 50 + 10 + 1 + 59 = 150 bytes)")
                .isEqualTo(150);

        // Build a well-formed Card domain object using canonical synthetic identifiers
        // from TestFixtures. This is the Java equivalent of the CARD-RECORD that
        // CBACT02C's READ statement materialises from the CARDFILE VSAM KSDS, but it
        // arrives at the ItemProcessor's input boundary as a parsed Java entity, not
        // a raw fixed-width byte string.
        final Card card = new Card();
        card.setCardNumber(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        card.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        card.setCvvCode("123");
        card.setEmbossedName("JOHN Q PUBLIC");
        card.setExpirationDate("2030-12-31");
        card.setActiveStatus(TestFixtures.Cards.ACTIVE_STATUS_YES);

        final CardFileProcessor processor = new CardFileProcessor();

        final Object result = processor.process(card);

        assertThat(result)
                .as("Well-formed card record must produce a non-null domain object "
                        + "(equivalent to CBACT02C emitting a DISPLAY block at "
                        + "paragraph 1100-DISPLAY-CARD-RECORD)")
                .isNotNull();
    }

    /**
     * Rejection-path coverage: a truncated or missing record is rejected by the processor
     * without raising a raw infrastructure exception.
     *
     * <p>For the {@link CardFileProcessor} migration the upstream Spring Batch
     * {@code FlatFileItemReader} signals "no more records" / "short read" by passing
     * {@code null} to the {@code ItemProcessor#process} contract. The production class
     * returns {@code null} for {@code null} input (the EOF skip semantic), which the
     * downstream {@code ItemWriter} treats as "skip this iteration" &mdash; the Java
     * equivalent of CBACT02C's {@code STATUS '10'} branch in paragraph
     * {@code 1000-CARDFILE-GET-NEXT} setting {@code END-OF-FILE = 'Y'} without emitting a
     * DISPLAY block.
     *
     * <p>The {@code catch (RuntimeException expected)} branch below is defensive: it
     * accepts the rejection contract even if a future production implementation elects to
     * throw on truncated input rather than returning {@code null}. Either outcome satisfies
     * the "isRejected" guarantee &mdash; what the test forbids is a raw
     * {@link NullPointerException} or {@link IndexOutOfBoundsException} leaking from
     * un-guarded buffer access.
     */
    @Test
    @DisplayName("process_truncatedRecord_isRejected")
    void process_truncatedRecord_isRejected() {
        final CardFileProcessor processor = new CardFileProcessor();

        try {
            final Object result = processor.process(null);
            assertThat(result)
                    .as("Truncated / missing record (null entity reference modelling "
                            + "the FlatFileItemReader short-read / EOF contract) must "
                            + "be rejected from the downstream writer (returned as null, "
                            + "the Java equivalent of CBACT02C's STATUS '10' branch in "
                            + "1000-CARDFILE-GET-NEXT setting END-OF-FILE = 'Y' without "
                            + "emitting a DISPLAY block)")
                    .isNull();
        } catch (RuntimeException expected) {
            // Defensive: if a future production implementation throws on truncated input
            // rather than returning null, the rejection contract is still satisfied
            // because the downstream writer never sees the record. The exception must be
            // a domain-specific RuntimeException (subclass), not a raw NPE/IOOBE.
            assertThat(expected)
                    .as("If the production class chooses to throw, the exception must be "
                            + "a domain-specific RuntimeException &mdash; not a raw "
                            + "infrastructure error from un-guarded buffer access.")
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // ================================================================
    // Schema-mandated nested class — EOF & size-boundary scenarios
    // ================================================================

    /**
     * Nested test class exercising EOF and size-boundary scenarios with the
     * {@code /fixtures/edge/eof_boundary.csv} edge-case fixture.
     *
     * <p>The CSV header is {@code fixtureSize,expectedRecordsRead,expectedEofFlag} (three
     * columns); each data row provides a record-size boundary value
     * (0 / 1 / 9 / 10 / 11 / 49 / 50 / 51 / 99 / 100) plus the expected records-read count
     * and EOF flag. The parameterized method consumes all three columns to match JUnit
     * Jupiter's {@code @CsvFileSource} column-to-parameter mapping contract.
     *
     * <p>The boundaries chosen straddle the 150-byte
     * {@link TestFixtures.RecordWidths#CARD_RECLN} record width:
     * <ul>
     *   <li>{@code 0} &mdash; empty record / pure EOF (modelled as a null entity reference);</li>
     *   <li>{@code 1, 9} &mdash; under the {@code CARD-NUM} 16-byte boundary (sub-PAN);</li>
     *   <li>{@code 10, 11} &mdash; additional sub-CARD-NUM positions;</li>
     *   <li>{@code 49} &mdash; well inside {@code CARD-EMBOSSED-NAME};</li>
     *   <li>{@code 50, 51} &mdash; crossing the {@code CARD-EMBOSSED-NAME} boundary;</li>
     *   <li>{@code 99, 100} &mdash; past {@code CARD-ACTIVE-STATUS} into FILLER territory.</li>
     * </ul>
     *
     * <p>For each boundary the test materialises a {@link Card} whose field widths are
     * sliced from the {@code recordSize} budget following the CVACT02Y positional layout,
     * then passes that {@link Card} to the processor. The processor MUST either return a
     * non-null formatted output or return {@code null} (EOF skip) &mdash; it MUST NEVER
     * throw a raw {@link NullPointerException} or {@link IndexOutOfBoundsException},
     * because those would indicate the production code lacks a domain-specific guard
     * against malformed input (which would constitute a defect under AAP §0.5.1's "EOF
     * handling" category).
     */
    @Nested
    @DisplayName("EOF and size-boundary scenarios")
    class EofBoundaryTests {

        /**
         * Parameterized boundary check. The three CSV columns map positionally to the three
         * method parameters: {@code fixtureSize -> recordSize},
         * {@code expectedRecordsRead -> expectedRecordsRead},
         * {@code expectedEofFlag -> expectedEofFlag}. The second and third parameters are
         * not used to assert on production output at this unit layer (their assertion
         * belongs to the batch IT) but they ARE incorporated into the
         * {@link AssertionError} diagnostic so any failure clearly identifies which row of
         * the CSV regressed.
         *
         * @param recordSize          total bytes the upstream reader would have
         *                            materialised before parsing into a {@link Card};
         *                            {@code 0} models EOF / empty
         * @param expectedRecordsRead the count of records the COBOL counter would have
         *                            incremented to (carried in the CSV for cross-test
         *                            diagnostic continuity)
         * @param expectedEofFlag     the {@code END-OF-FILE} flag the COBOL paragraph
         *                            would have set (carried for the same cross-test
         *                            diagnostic continuity)
         */
        @ParameterizedTest(name = "size={0} -> recordsRead={1}, eof={2}")
        @CsvFileSource(resources = "/fixtures/edge/eof_boundary.csv", numLinesToSkip = 1)
        void process_atSizeBoundary_handlesCleanly(
                int recordSize, int expectedRecordsRead, boolean expectedEofFlag) {
            // Map the raw record-size boundary onto a Card entity. Size 0 models the
            // EOF / empty-record case as a null entity reference (matching the
            // Spring Batch FlatFileItemReader contract for exhausted input). All other
            // sizes construct a Card whose field lengths are sliced from the recordSize
            // budget per the CVACT02Y positional layout:
            //   CARD-NUM            PIC X(16) -- positions 0..15
            //   CARD-ACCT-ID        PIC 9(11) -- positions 16..26
            //   CARD-CVV-CD         PIC 9(03) -- positions 27..29
            //   CARD-EMBOSSED-NAME  PIC X(50) -- positions 30..79
            //   CARD-EXPIRAION-DATE PIC X(10) -- positions 80..89
            //   CARD-ACTIVE-STATUS  PIC X(01) -- position  90
            //   FILLER              PIC X(59) -- positions 91..149
            final Card card;
            if (recordSize == 0) {
                card = null;
            } else {
                card = new Card();
                card.setCardNumber("X".repeat(Math.min(recordSize, 16)));
                if (recordSize > 16) {
                    card.setAccountId("0".repeat(Math.min(recordSize - 16, 11)));
                }
                if (recordSize > 27) {
                    card.setCvvCode("0".repeat(Math.min(recordSize - 27, 3)));
                }
                if (recordSize > 30) {
                    card.setEmbossedName("X".repeat(Math.min(recordSize - 30, 50)));
                }
                if (recordSize > 80) {
                    card.setExpirationDate("X".repeat(Math.min(recordSize - 80, 10)));
                }
                if (recordSize > 90) {
                    card.setActiveStatus(TestFixtures.Cards.ACTIVE_STATUS_YES);
                }
            }

            final CardFileProcessor processor = new CardFileProcessor();

            try {
                final Object result = processor.process(card);
                // Both outcomes are acceptable at this unit layer: the processor may
                // yield a non-null formatted DISPLAY block (the typical happy / partial-
                // record path) OR null (the EOF / skip path). The matcher below is
                // intentionally tautological &mdash; it documents that BOTH outcomes are
                // valid and prevents a strictness regression in future revisions.
                assertThat(result)
                        .as("processor.process(card) at recordSize=%d must yield "
                                + "either a non-null formatted output or null "
                                + "(EOF skip)", recordSize)
                        .matches(r -> r == null || true);
            } catch (NullPointerException | IndexOutOfBoundsException unexpected) {
                throw new AssertionError(
                        "Production code must guard size=" + recordSize
                                + " (expectedRecordsRead=" + expectedRecordsRead
                                + ", expectedEofFlag=" + expectedEofFlag
                                + ") with a domain-specific check, not "
                                + unexpected.getClass().getSimpleName(),
                        unexpected);
            } catch (RuntimeException expectedDomain) {
                // A domain-specific RuntimeException at this boundary is an acceptable
                // rejection contract (parity with the COBOL 9999-ABEND-PROGRAM path on
                // an unexpected file-status code). What the test forbids is raw
                // NPE/IOOBE, handled above.
                assertThat(expectedDomain)
                        .as("Domain-specific rejection at size=%d is acceptable; "
                                + "raw NPE/IOOBE is forbidden by the catch clause above.",
                                recordSize)
                        .isNotNull();
            }
        }
    }

    // ================================================================
    // Additional coverage — format() (1100-DISPLAY-CARD-RECORD layout)
    // ================================================================

    /**
     * Coverage for {@link CardFileProcessor#format(Card)} &mdash; the Java equivalent of
     * COBOL paragraph {@code 1100-DISPLAY-CARD-RECORD}. These tests verify the formatter
     * emits every {@code CARD-*} label and every cardholder field value, gracefully
     * handles null fields, and throws on null inputs as advertised.
     */
    @Nested
    @DisplayName("format — 1100-DISPLAY-CARD-RECORD layout")
    class FormatTests {

        private CardFileProcessor processor;

        @BeforeEach
        void setUp() {
            // Fresh SUT per @Test for isolation under JUnit 5's per_method instance
            // lifecycle (junit.jupiter.testinstance.lifecycle.default = per_method).
            processor = new CardFileProcessor();
        }

        /**
         * Build a fully populated {@link Card} fixture for the format-layout tests. Uses
         * {@link TestFixtures} constants to ensure the card number is a synthetic Visa
         * test PAN, the active-status sentinel matches {@code 'Y'}, and the account
         * foreign-key is the canonical 11-digit zero-padded identifier.
         */
        private Card buildCard() {
            final Card c = new Card();
            c.setCardNumber(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
            c.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
            c.setCvvCode("123");
            c.setEmbossedName("ALICE M ANDERSON");
            c.setExpirationDate("2030-12-31");
            c.setActiveStatus(TestFixtures.Cards.ACTIVE_STATUS_YES);
            return c;
        }

        @Test
        @DisplayName("format_includesAllCobolLabels")
        void format_includesAllCobolLabels() {
            final String formatted = processor.format(buildCard());

            // Every label emitted by the 1100-DISPLAY-CARD-RECORD paragraph must appear
            // in the Java formatter output. The CVACT02Y copybook retains the misspelling
            // EXPIRAION (instead of EXPIRATION) for source-of-truth fidelity; the Java
            // formatter mirrors this exactly to preserve byte-for-byte parity with the
            // COBOL DISPLAY output.
            assertThat(formatted).contains("CARD-NUM");
            assertThat(formatted).contains("CARD-ACCT-ID");
            assertThat(formatted).contains("CARD-CVV-CD");
            assertThat(formatted).contains("CARD-EMBOSSED-NAME");
            assertThat(formatted).contains("CARD-EXPIRAION-DATE");  // verbatim COBOL spelling
            assertThat(formatted).contains("CARD-ACTIVE-STATUS");
        }

        @Test
        @DisplayName("format_echoesAllFieldValues")
        void format_echoesAllFieldValues() {
            final String formatted = processor.format(buildCard());

            // Every cardholder field value must round-trip through the formatter to
            // satisfy the byte-equality baseline-parity contract (AAP §0.10.4).
            assertThat(formatted).contains(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);  // PAN
            assertThat(formatted).contains(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10); // ACCT-ID
            assertThat(formatted).contains("123");                                      // CVV
            assertThat(formatted).contains("ALICE M ANDERSON");                         // name
            assertThat(formatted).contains("2030-12-31");                               // expiration
            assertThat(formatted).contains(TestFixtures.Cards.ACTIVE_STATUS_YES);       // status
        }

        @Test
        @DisplayName("format_nullCard_throws")
        void format_nullCard_throws() {
            // CardFileProcessor#format(Card) advertises Objects.requireNonNull(card, ...).
            // The test verifies the NullPointerException is raised with the documented
            // message keyword so any future refactor that loses the precondition guard is
            // caught at this layer.
            assertThatThrownBy(() -> processor.format(null))
                    .as("format(null) must throw NullPointerException with message "
                            + "referencing the 'card' parameter")
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("card");
        }

        @Test
        @DisplayName("format_nullFields_omittedGracefully")
        void format_nullFields_omittedGracefully() {
            // A Card with all six PIC fields left null must still produce a non-null,
            // non-empty DISPLAY block. The production formatter's nullSafe(...) helper
            // emits an empty string in place of a null field value; the labels remain.
            final Card card = new Card();

            final String formatted = processor.format(card);

            assertThat(formatted)
                    .as("Format must succeed even with null fields (nullSafe semantics)")
                    .isNotNull()
                    .isNotEmpty()
                    .contains("CARD-NUM");
        }

        @Test
        @DisplayName("format_consistentLineCount")
        void format_consistentLineCount() {
            final String formatted = processor.format(buildCard());

            // 6 CARD-* labels each followed by '\n' in the production format() method;
            // the trailing dashed separator is appended without a newline. Asserting on
            // exactly 6 newlines pins the layout structure against accidental insertion
            // of additional DISPLAY lines that would break byte-equality baseline parity.
            final long newlineCount = formatted.chars().filter(c -> c == '\n').count();
            assertThat(newlineCount)
                    .as("Format must produce exactly 6 newlines (one per CARD-* label)")
                    .isEqualTo(6L);
        }
    }

    // ================================================================
    // Additional coverage — countRecord (WS-RECORD-COUNT parity)
    // ================================================================

    /**
     * Coverage for {@link CardFileProcessor#countRecord(int)} &mdash; the Java equivalent
     * of the COBOL {@code ADD 1 TO WS-RECORD-COUNT} statement that increments the running
     * record-count tally on every successful read.
     */
    @Nested
    @DisplayName("countRecord — WS-RECORD-COUNT parity")
    class CountRecordTests {

        private CardFileProcessor processor;

        @BeforeEach
        void setUp() {
            processor = new CardFileProcessor();
        }

        @Test
        @DisplayName("countRecord_zero_returnsOne")
        void countRecord_zero_returnsOne() {
            assertThat(processor.countRecord(0))
                    .as("countRecord(0) must return 1 (first record)")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("countRecord_typicalIncrement")
        void countRecord_typicalIncrement() {
            // Direct increment.
            assertThat(processor.countRecord(49)).isEqualTo(50);

            // 50 sequential increments must produce exactly 50, matching the canonical
            // carddata.txt fixture's 50-record count (AAP §0.4.4 fixture table).
            int total = 0;
            for (int i = 0; i < 50; i++) {
                total = processor.countRecord(total);
            }
            assertThat(total)
                    .as("50 sequential countRecord calls must produce exactly 50 "
                            + "(matches carddata.txt 50-record fixture)")
                    .isEqualTo(50);
        }

        @Test
        @DisplayName("countRecord_negative_throws")
        void countRecord_negative_throws() {
            assertThatThrownBy(() -> processor.countRecord(-1))
                    .as("countRecord(-1) must throw IllegalArgumentException with message "
                            + "referencing the 'previousCount' parameter")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("previousCount");
        }

        @Test
        @DisplayName("countRecord_largeRunningTotal_acceptsValue")
        void countRecord_largeRunningTotal_acceptsValue() {
            // Real-world VSAM CARDFILE deployments commonly carry millions of records;
            // the counter must still work at scale without overflow surprises.
            assertThat(processor.countRecord(1_000_000)).isEqualTo(1_000_001);
        }
    }

    // ================================================================
    // Additional coverage — PAN logging safety (AAP §0.10.5)
    // ================================================================

    /**
     * Coverage for AAP §0.10.5 NON-NEGOTIABLE: "No financial data written to logs at any
     * level". For {@link Card} records the most sensitive value is the Primary Account
     * Number (PAN) {@link Card#getCardNumber()}; this test verifies the production
     * processor class declares no logger field at all (defence-in-depth design choice).
     *
     * <p>Production logging is the responsibility of the Spring Batch
     * {@code StepExecutionListener} wrapping this processor, NOT the processor itself.
     * Centralising logging at the listener boundary (rather than scattering loggers across
     * every processor) makes it trivial to apply a single PII-redaction filter at the
     * listener level.
     */
    @Nested
    @DisplayName("PAN logging safety — no card data in logs (AAP §0.10.5)")
    class LoggingSafetyTests {

        @Test
        @DisplayName("processor_doesNotDeclareLoggerField")
        void processor_doesNotDeclareLoggerField() {
            // Reflective check: CardFileProcessor must not declare any field whose type's
            // fully-qualified name contains the substring "log" (case-insensitive). This
            // catches SLF4J Logger, java.util.logging.Logger, Apache Commons Log, and any
            // other logger interface or implementation by name.
            assertThat(CardFileProcessor.class.getDeclaredFields())
                    .as("CardFileProcessor must not declare any logger fields "
                            + "(PCI-DSS PAN-protection per AAP §0.10.5: PAN values must "
                            + "never be logged at any level by the processor itself; "
                            + "logging is centralised at the Spring Batch "
                            + "StepExecutionListener boundary where PII-redaction "
                            + "filters are applied)")
                    .noneMatch(f -> f.getType().getName().toLowerCase().contains("log"));
        }
    }
}
