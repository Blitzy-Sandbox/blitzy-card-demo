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

import com.aws.carddemo.entity.CardXref;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link CardXrefFileProcessor} — the Java migration of
 * the COBOL {@code CBACT03C} card-cross-reference reader (see
 * {@code app/cbl/CBACT03C.cbl}).
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>{@code CBACT03C.cbl} reads each record from the XREF-FILE VSAM KSDS
 * sequentially and emits a DISPLAY block. The cross-reference file maps
 * card numbers to customer IDs and account IDs.
 *
 * <h2>Test Strategy (AAP §0.10.1 Require Test Coverage rule)</h2>
 *
 * <p>Every test invokes the <strong>real production
 * {@link CardXrefFileProcessor}</strong>. No internal logic
 * reimplementation.
 *
 * <h2>Coverage Categories per AAP §0.5.1</h2>
 * <ul>
 *   <li>Happy read — every CardXref → non-null formatted output.</li>
 *   <li>EOF — null input → null output (skip semantic).</li>
 *   <li>Count parity — countRecord exact-by-one increment.</li>
 *   <li>Logging safety — no logger declared on the production class.</li>
 * </ul>
 *
 * @see CardXrefFileProcessor
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardXrefFileProcessor unit tests (CBACT03C migration)")
class CardXrefFileProcessorTest {

    /** Real SUT — fresh per test. */
    private CardXrefFileProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new CardXrefFileProcessor();
    }

    /** Convenience — build a fully populated CardXref fixture. */
    private static CardXref buildXref() {
        CardXref x = new CardXref();
        x.setCardNumber("4111111111111111");
        x.setCustomerId("000000001");
        x.setAccountId("00000000010");
        return x;
    }

    // ============================================================
    // Nested test class 1 — process (happy read / EOF skip)
    // ============================================================

    @Nested
    @DisplayName("process — happy read / EOF skip semantics")
    class ProcessTests {

        @Test
        @DisplayName("process_typicalXref_returnsFormattedString")
        void process_typicalXref_returnsFormattedString() {
            CardXref xref = buildXref();

            String result = processor.process(xref);

            assertThat(result)
                    .as("process must return non-null formatted output for non-null xref")
                    .isNotNull()
                    .isNotEmpty()
                    .contains("4111111111111111")
                    .contains("000000001")
                    .contains("00000000010");
        }

        @Test
        @DisplayName("process_nullXref_returnsNullForEofSkip")
        void process_nullXref_returnsNullForEofSkip() {
            // CBACT03C EOF/skip semantics.
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
    @DisplayName("format — 1100-DISPLAY-XREF-RECORD layout")
    class FormatTests {

        @Test
        @DisplayName("format_includesAllCobolLabels")
        void format_includesAllCobolLabels() {
            String formatted = processor.format(buildXref());

            assertThat(formatted).contains("XREF-CARD-NUM");
            assertThat(formatted).contains("XREF-CUST-NUM");
            assertThat(formatted).contains("XREF-ACCT-ID");
        }

        @Test
        @DisplayName("format_echoesAllFieldValues")
        void format_echoesAllFieldValues() {
            String formatted = processor.format(buildXref());

            assertThat(formatted).contains("4111111111111111");
            assertThat(formatted).contains("000000001");
            assertThat(formatted).contains("00000000010");
        }

        @Test
        @DisplayName("format_nullXref_throws")
        void format_nullXref_throws() {
            assertThatThrownBy(() -> processor.format(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("xref");
        }

        @Test
        @DisplayName("format_nullFields_omittedGracefully")
        void format_nullFields_omittedGracefully() {
            CardXref xref = new CardXref();
            // All fields null.

            String formatted = processor.format(xref);

            assertThat(formatted)
                    .as("Format must succeed even with null fields")
                    .isNotNull()
                    .isNotEmpty()
                    .contains("XREF-CARD-NUM");
        }

        @Test
        @DisplayName("format_threeLabeledLines")
        void format_threeLabeledLines() {
            String formatted = processor.format(buildXref());

            // 3 XREF-* labels each followed by '\n', then a separator line.
            long newlineCount = formatted.chars().filter(c -> c == '\n').count();
            assertThat(newlineCount)
                    .as("Format must produce exactly 3 newlines (one per XREF-* label)")
                    .isEqualTo(3);
        }
    }

    // ============================================================
    // Nested test class 3 — countRecord (count parity)
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

            // Verify ASCII fixture cardxref.txt's 50 records produce
            // an exact count of 50 across 50 sequential increments.
            int total = 0;
            for (int i = 0; i < 50; i++) {
                total = processor.countRecord(total);
            }
            assertThat(total)
                    .as("50 sequential countRecord calls must produce exactly 50")
                    .isEqualTo(50);
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
    // Nested test class 4 — Logging safety
    // ============================================================

    @Nested
    @DisplayName("Logging safety — no card-cross-reference data in logs (AAP §0.10.5)")
    class LoggingSafetyTests {

        @Test
        @DisplayName("processor_doesNotDeclareLoggerField")
        void processor_doesNotDeclareLoggerField() {
            assertThat(CardXrefFileProcessor.class.getDeclaredFields())
                    .as("CardXrefFileProcessor must not declare any logger fields")
                    .noneMatch(f -> f.getType().getName().toLowerCase().contains("log"));
        }
    }
}
