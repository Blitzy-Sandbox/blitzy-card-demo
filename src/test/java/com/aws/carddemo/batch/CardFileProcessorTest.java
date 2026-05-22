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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link CardFileProcessor} — the Java migration of the
 * COBOL {@code CBACT02C} card-file reader (see {@code app/cbl/CBACT02C.cbl}).
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>{@code CBACT02C.cbl} reads each record from the CARDFILE VSAM KSDS
 * sequentially and emits a DISPLAY block.
 *
 * <h2>Test Strategy (AAP §0.10.1 Require Test Coverage rule)</h2>
 *
 * <p>Every test invokes the <strong>real production
 * {@link CardFileProcessor}</strong>. No internal logic
 * reimplementation.
 *
 * <h2>Coverage Categories per AAP §0.5.1</h2>
 * <ul>
 *   <li>Happy read — every Card → non-null formatted output.</li>
 *   <li>Malformed/EOF — null input → null output (skip semantic).</li>
 *   <li>Format layout — all CARD-* labels present, all fields echoed.</li>
 *   <li>PAN logging safety — production class declares no logger.</li>
 * </ul>
 *
 * @see CardFileProcessor
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardFileProcessor unit tests (CBACT02C migration)")
class CardFileProcessorTest {

    /** Real SUT — fresh per test. */
    private CardFileProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new CardFileProcessor();
    }

    /** Convenience — build a fully populated Card fixture. */
    private static Card buildCard() {
        Card c = new Card();
        c.setCardNumber("4111111111111111");
        c.setAccountId("00000000010");
        c.setCvvCode("123");
        c.setEmbossedName("ALICE M ANDERSON              ");
        c.setExpirationDate("2030-12-31");
        c.setActiveStatus("Y");
        return c;
    }

    // ============================================================
    // Nested test class 1 — process (happy read / EOF skip)
    // ============================================================

    @Nested
    @DisplayName("process — happy read / EOF skip semantics")
    class ProcessTests {

        @Test
        @DisplayName("process_typicalCard_returnsFormattedString")
        void process_typicalCard_returnsFormattedString() {
            Card card = buildCard();

            String result = processor.process(card);

            assertThat(result)
                    .as("process must return non-null formatted output for non-null card")
                    .isNotNull()
                    .isNotEmpty()
                    .contains("4111111111111111")
                    .contains("00000000010");
        }

        @Test
        @DisplayName("process_nullCard_returnsNullForEofSkip")
        void process_nullCard_returnsNullForEofSkip() {
            // CBACT02C EOF/skip semantics: when COBOL READ returns
            // STATUS '10', the next record is null and the Spring Batch
            // ItemProcessor returns null to signal "skip this iteration."
            String result = processor.process(null);
            assertThat(result)
                    .as("Null input must return null (EOF skip semantics)")
                    .isNull();
        }
    }

    // ============================================================
    // Nested test class 2 — format (DISPLAY block layout)
    // ============================================================

    @Nested
    @DisplayName("format — 1100-DISPLAY-CARD-RECORD layout")
    class FormatTests {

        @Test
        @DisplayName("format_includesAllCobolLabels")
        void format_includesAllCobolLabels() {
            String formatted = processor.format(buildCard());

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
            String formatted = processor.format(buildCard());

            assertThat(formatted).contains("4111111111111111");  // PAN
            assertThat(formatted).contains("00000000010");       // accountId
            assertThat(formatted).contains("123");               // CVV
            assertThat(formatted).contains("ALICE M ANDERSON");  // embossed name
            assertThat(formatted).contains("2030-12-31");        // expiration
            assertThat(formatted).contains("Y");                 // active status
        }

        @Test
        @DisplayName("format_nullCard_throws")
        void format_nullCard_throws() {
            assertThatThrownBy(() -> processor.format(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("card");
        }

        @Test
        @DisplayName("format_nullFields_omittedGracefully")
        void format_nullFields_omittedGracefully() {
            Card card = new Card();
            // All fields null.

            String formatted = processor.format(card);

            assertThat(formatted)
                    .as("Format must succeed even with null fields")
                    .isNotNull()
                    .isNotEmpty()
                    .contains("CARD-NUM");
        }

        @Test
        @DisplayName("format_consistentLineCount")
        void format_consistentLineCount() {
            String formatted = processor.format(buildCard());

            // 6 CARD-* labels each followed by '\n', then a separator line.
            long newlineCount = formatted.chars().filter(c -> c == '\n').count();
            assertThat(newlineCount)
                    .as("Format must produce exactly 6 newlines (one per CARD-* label)")
                    .isEqualTo(6);
        }
    }

    // ============================================================
    // Nested test class 3 — countRecord
    // ============================================================

    @Nested
    @DisplayName("countRecord — WS-RECORD-COUNT parity")
    class CountRecordTests {

        @Test
        @DisplayName("countRecord_zero_returnsOne")
        void countRecord_zero_returnsOne() {
            assertThat(processor.countRecord(0)).isEqualTo(1);
        }

        @Test
        @DisplayName("countRecord_typicalIncrement")
        void countRecord_typicalIncrement() {
            assertThat(processor.countRecord(49)).isEqualTo(50);
        }

        @Test
        @DisplayName("countRecord_negative_throws")
        void countRecord_negative_throws() {
            assertThatThrownBy(() -> processor.countRecord(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("previousCount");
        }
    }

    // ============================================================
    // Nested test class 4 — PAN logging safety (PCI-DSS critical)
    // ============================================================

    /**
     * Coverage for AAP §0.10.5 NON-NEGOTIABLE: "No financial data
     * written to logs at any level". For CARD records, the most
     * sensitive value is the Primary Account Number (PAN); this test
     * verifies the production class declares no logger field.
     */
    @Nested
    @DisplayName("PAN logging safety — no card data in logs (AAP §0.10.5)")
    class LoggingSafetyTests {

        @Test
        @DisplayName("processor_doesNotDeclareLoggerField")
        void processor_doesNotDeclareLoggerField() {
            assertThat(CardFileProcessor.class.getDeclaredFields())
                    .as("CardFileProcessor must not declare any logger fields "
                            + "(PCI-DSS PAN-protection per AAP §0.10.5)")
                    .noneMatch(f -> f.getType().getName().toLowerCase().contains("log"));
        }

        @Test
        @DisplayName("processor_doesNotEmitPanToStderr")
        void processor_doesNotEmitPanToStderr() {
            ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
            PrintStream originalErr = System.err;
            try {
                System.setErr(new PrintStream(capturedErr));

                Card card = buildCard();
                processor.process(card);

            } finally {
                System.setErr(originalErr);
            }

            // Even the test-fixture PAN must never appear in stderr.
            assertThat(capturedErr.toString())
                    .as("processor.process must not emit PAN to stderr")
                    .doesNotContain("4111111111111111");
        }
    }
}
