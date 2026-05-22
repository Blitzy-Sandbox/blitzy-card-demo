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

import com.aws.carddemo.entity.Transaction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link TransactionReportProcessor} — the Java migration
 * of the COBOL {@code CBTRN03C} transaction-detail report program (649
 * lines; see {@code app/cbl/CBTRN03C.cbl}).
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>{@code CBTRN03C.cbl} reads daily transaction records and emits a
 * paginated report. Page-break, header, trailer, formatting, and
 * total-accumulation behaviour are all preserved by the migration via
 * pure-function seams on {@link TransactionReportProcessor}.
 *
 * <h2>Test Strategy (AAP §0.10.1 Require Test Coverage rule)</h2>
 *
 * <p>Every test invokes the <strong>real production
 * {@link TransactionReportProcessor}</strong> via constructor
 * instantiation. No formatting logic is reimplemented in the test body
 * — expected strings are literals captured from the COBOL contract.
 *
 * @see TransactionReportProcessor
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionReportProcessor unit tests (CBTRN03C migration)")
class TransactionReportProcessorTest {

    /** Real SUT — fresh per test. */
    private TransactionReportProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TransactionReportProcessor();
    }

    // ============================================================
    // Top-level sanity tests — COBOL contract constants
    // ============================================================

    @Test
    @DisplayName("constants_alignWithCobolContract")
    void constants_alignWithCobolContract() {
        // CBTRN03C standard pagination: 66 lines per page (12-pt fixed-width
        // line printer carriage convention).
        assertThat(TransactionReportProcessor.LINES_PER_PAGE).isEqualTo(66);
        assertThat(TransactionReportProcessor.HEADER_LINES).isEqualTo(4);
        assertThat(TransactionReportProcessor.TRAILER_LINES).isEqualTo(1);
    }

    // ============================================================
    // Nested test class 1 — formatRecord (47-char fixed-width row)
    // ============================================================

    @Nested
    @DisplayName("formatRecord — 47-char fixed-width report row")
    class FormatRecordTests {

        @Test
        @DisplayName("formatRecord_typicalTransaction_producesFixedWidthRow")
        void formatRecord_typicalTransaction_producesFixedWidthRow() {
            Transaction t = new Transaction();
            t.setTransactionId("0000000000000001");
            t.setTransactionTypeCode("01");
            t.setTransactionCategoryCode("0001");
            t.setAmount(new BigDecimal("25.99"));
            t.setOriginTimestamp("2024-01-15-10.30.45.123456");

            String formatted = processor.formatRecord(t);

            // Layout: 16+1+2+1+4+1+12+1+10 = 48 chars (5 spaces in between).
            // Verify total length.
            assertThat(formatted)
                    .as("formatRecord must produce 48-character row")
                    .hasSize(48);
            // ID at columns 1-16.
            assertThat(formatted.substring(0, 16))
                    .isEqualTo("0000000000000001");
            // Separator space at column 17.
            assertThat(formatted.charAt(16)).isEqualTo(' ');
            // Type at columns 18-19.
            assertThat(formatted.substring(17, 19)).isEqualTo("01");
            // Separator space at column 20.
            assertThat(formatted.charAt(19)).isEqualTo(' ');
            // Category at columns 21-24.
            assertThat(formatted.substring(20, 24)).isEqualTo("0001");
            // Separator space at column 25.
            assertThat(formatted.charAt(24)).isEqualTo(' ');
            // Amount at columns 26-37 (right-justified, 12 chars).
            assertThat(formatted.substring(25, 37))
                    .as("Amount section padded to 12 chars right-justified")
                    .endsWith("25.99");
            assertThat(formatted.substring(25, 37)).hasSize(12);
            // Separator space at column 38.
            assertThat(formatted.charAt(37)).isEqualTo(' ');
            // Date at columns 39-48 (10 chars, first 10 of origin timestamp).
            assertThat(formatted.substring(38, 48)).isEqualTo("2024-01-15");
        }

        @Test
        @DisplayName("formatRecord_nullFields_padsWithSpaces")
        void formatRecord_nullFields_padsWithSpaces() {
            Transaction t = new Transaction();
            // All fields null.

            String formatted = processor.formatRecord(t);

            // Total width preserved even when all fields are null.
            assertThat(formatted)
                    .as("formatRecord must produce 48-char row even with nulls")
                    .hasSize(48);
        }

        @Test
        @DisplayName("formatRecord_nullTransaction_throws")
        void formatRecord_nullTransaction_throws() {
            assertThatThrownBy(() -> processor.formatRecord(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("transaction");
        }

        @Test
        @DisplayName("formatRecord_negativeAmount_preservesSign")
        void formatRecord_negativeAmount_preservesSign() {
            Transaction t = new Transaction();
            t.setTransactionId("0000000000000099");
            t.setTransactionTypeCode("99");
            t.setTransactionCategoryCode("9999");
            t.setAmount(new BigDecimal("-1234.56"));
            t.setOriginTimestamp("2024-12-31-23.59.59.999999");

            String formatted = processor.formatRecord(t);

            // Negative sign must appear in the amount section.
            assertThat(formatted)
                    .as("Negative amounts must include the minus sign")
                    .contains("-1234.56");
        }
    }

    // ============================================================
    // Nested test class 2 — formatHeader (4-line page header)
    // ============================================================

    @Nested
    @DisplayName("formatHeader — 4-line page header")
    class FormatHeaderTests {

        @Test
        @DisplayName("formatHeader_page1_producesExpectedLines")
        void formatHeader_page1_producesExpectedLines() {
            String header = processor.formatHeader(1, "2024-01-15");

            assertThat(header)
                    .as("Header line 1 — report title")
                    .startsWith("TRANSACTION DETAIL REPORT");
            assertThat(header).contains("Run date: 2024-01-15");
            assertThat(header).contains("Page: 1");
            // Three newlines separate the 4 header lines.
            assertThat(header.chars().filter(c -> c == '\n').count())
                    .as("Header must contain 3 newlines (separates 4 lines)")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("formatHeader_largePageNumber_includesPageNumber")
        void formatHeader_largePageNumber_includesPageNumber() {
            String header = processor.formatHeader(9999, "2024-01-15");
            assertThat(header).contains("Page: 9999");
        }

        @Test
        @DisplayName("formatHeader_zeroOrNegativePage_throws")
        void formatHeader_zeroOrNegativePage_throws() {
            assertThatThrownBy(() -> processor.formatHeader(0, "2024-01-15"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pageNumber");
            assertThatThrownBy(() -> processor.formatHeader(-1, "2024-01-15"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pageNumber");
        }

        @Test
        @DisplayName("formatHeader_nullRunDate_throws")
        void formatHeader_nullRunDate_throws() {
            assertThatThrownBy(() -> processor.formatHeader(1, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("runDate");
        }
    }

    // ============================================================
    // Nested test class 3 — formatTrailer (1-line page footer)
    // ============================================================

    @Nested
    @DisplayName("formatTrailer — per-page totals footer")
    class FormatTrailerTests {

        @Test
        @DisplayName("formatTrailer_typical_carriesRecordCountAndTotal")
        void formatTrailer_typical_carriesRecordCountAndTotal() {
            String trailer = processor.formatTrailer(15, new BigDecimal("1234.56"));

            assertThat(trailer).contains("15");
            assertThat(trailer)
                    .as("Trailer must contain the page-total as plain string at scale 2")
                    .contains("1234.56");
        }

        @Test
        @DisplayName("formatTrailer_zeroRecords_acceptsZero")
        void formatTrailer_zeroRecords_acceptsZero() {
            String trailer = processor.formatTrailer(0, BigDecimal.ZERO);
            assertThat(trailer).contains("0");
        }

        @Test
        @DisplayName("formatTrailer_negativeRecordCount_throws")
        void formatTrailer_negativeRecordCount_throws() {
            assertThatThrownBy(() -> processor.formatTrailer(-1, BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("formatTrailer_nullTotal_throws")
        void formatTrailer_nullTotal_throws() {
            assertThatThrownBy(() -> processor.formatTrailer(0, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("formatTrailer_largeTotal_preservesScaleTwo")
        void formatTrailer_largeTotal_preservesScaleTwo() {
            // Verify scale-2 normalisation: 100.5 → "100.50".
            String trailer = processor.formatTrailer(5, new BigDecimal("100.5"));
            assertThat(trailer)
                    .as("Trailer total must be normalised to scale 2")
                    .contains("100.50");
        }
    }

    // ============================================================
    // Nested test class 4 — isPageBreak (every 66 lines)
    // ============================================================

    @Nested
    @DisplayName("isPageBreak — every LINES_PER_PAGE (66) lines")
    class IsPageBreakTests {

        @ParameterizedTest(name = "[{index}] lineCount={0} expectedPageBreak={1}")
        @CsvSource({
                "0, false",     // start — no break yet
                "1, false",
                "65, false",
                "66, true",     // first break
                "67, false",
                "131, false",
                "132, true",    // second break
                "198, true",    // third break
                "264, true",    // fourth break
        })
        void isPageBreak_lineCount_matchesExpectation(int lineCount, boolean expected) {
            assertThat(processor.isPageBreak(lineCount))
                    .as("isPageBreak(%d) must be %s", lineCount, expected)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("isPageBreak_negativeLineCount_throws")
        void isPageBreak_negativeLineCount_throws() {
            assertThatThrownBy(() -> processor.isPageBreak(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lineCount");
        }
    }

    // ============================================================
    // Nested test class 5 — accumulateTotal
    // ============================================================

    @Nested
    @DisplayName("accumulateTotal — running per-page total")
    class AccumulateTotalTests {

        @Test
        @DisplayName("accumulateTotal_sumsAtScaleTwo")
        void accumulateTotal_sumsAtScaleTwo() {
            BigDecimal result = processor.accumulateTotal(
                    new BigDecimal("100.00"), new BigDecimal("25.99"));
            assertThat(result).isEqualByComparingTo("125.99");
            assertThat(result.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("accumulateTotal_zeroIncrement_preservesRunning")
        void accumulateTotal_zeroIncrement_preservesRunning() {
            BigDecimal result = processor.accumulateTotal(
                    new BigDecimal("50.00"), BigDecimal.ZERO);
            assertThat(result).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("accumulateTotal_nullArgs_throws")
        void accumulateTotal_nullArgs_throws() {
            assertThatThrownBy(() -> processor.accumulateTotal(null, BigDecimal.ZERO))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> processor.accumulateTotal(BigDecimal.ZERO, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ============================================================
    // Nested test class 6 — Logging safety
    // ============================================================

    @Nested
    @DisplayName("Logging safety — no financial data in logs (AAP §0.10.5)")
    class LoggingSafetyTests {

        @Test
        @DisplayName("processor_doesNotDeclareLoggerField")
        void processor_doesNotDeclareLoggerField() {
            assertThat(TransactionReportProcessor.class.getDeclaredFields())
                    .as("TransactionReportProcessor must not declare any logger fields")
                    .noneMatch(f -> f.getType().getName().toLowerCase().contains("log"));
        }
    }
}
