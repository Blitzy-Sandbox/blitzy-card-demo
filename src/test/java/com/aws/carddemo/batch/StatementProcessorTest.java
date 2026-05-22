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

import com.aws.carddemo.entity.Customer;
import com.aws.carddemo.entity.Transaction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link StatementProcessor} — the Java migration of the
 * COBOL {@code CBSTM03A} customer-statement program (924 lines; see
 * {@code app/cbl/CBSTM03A.cbl}).
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>{@code CBSTM03A.cbl} produces dual-output (plain-text + HTML)
 * customer statements. The Java migration exposes pure-function seams
 * on {@link StatementProcessor}.
 *
 * <h2>Test Strategy (AAP §0.10.1 Require Test Coverage rule)</h2>
 *
 * <p>Every test invokes the <strong>real production
 * {@link StatementProcessor}</strong>. No formatting logic, total
 * arithmetic, or HTML-escape behaviour is reimplemented in the test
 * body — expected strings are literals and expected totals are
 * pre-computed scale-2 values.
 *
 * @see StatementProcessor
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementProcessor unit tests (CBSTM03A migration)")
class StatementProcessorTest {

    /** Real SUT — fresh per test. */
    private StatementProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new StatementProcessor();
    }

    /** Convenience — build a fully populated Customer fixture. */
    private static Customer buildCustomer() {
        Customer c = new Customer();
        c.setCustomerId("000000001");
        c.setFirstName("ALICE");
        c.setMiddleName("M");
        c.setLastName("ANDERSON");
        c.setAddressLine1("123 MAIN ST");
        c.setAddressStateCode("WA");
        c.setAddressZip("98101");
        return c;
    }

    /** Convenience — build a single transaction fixture. */
    private static Transaction buildTransaction(String id, String amount) {
        Transaction t = new Transaction();
        t.setTransactionId(id);
        t.setTransactionTypeCode("01");
        t.setTransactionCategoryCode("0001");
        t.setAmount(new BigDecimal(amount));
        t.setOriginTimestamp("2024-01-15-10.30.45.123456");
        return t;
    }

    // ============================================================
    // Nested test class 1 — buildTextStatement
    // ============================================================

    @Nested
    @DisplayName("buildTextStatement — plain-text customer statement")
    class TextStatementTests {

        @Test
        @DisplayName("buildTextStatement_typicalCustomer_includesHeaderAndTransactions")
        void buildTextStatement_typicalCustomer_includesHeaderAndTransactions() {
            Customer customer = buildCustomer();
            List<Transaction> txns = List.of(
                    buildTransaction("0000000000000001", "25.99"),
                    buildTransaction("0000000000000002", "10.50"));

            String text = processor.buildTextStatement(customer, txns);

            // Verify the production output contains the header.
            assertThat(text).contains("CUSTOMER STATEMENT");
            // Customer details.
            assertThat(text).contains("Customer ID: 000000001");
            assertThat(text).contains("ALICE");
            assertThat(text).contains("ANDERSON");
            assertThat(text).contains("123 MAIN ST");
            assertThat(text).contains("WA");
            assertThat(text).contains("98101");
            // Both transactions appear.
            assertThat(text).contains("0000000000000001");
            assertThat(text).contains("0000000000000002");
            // Total appears at the bottom (pre-computed: 25.99 + 10.50 = 36.49).
            assertThat(text).contains("Total: 36.49");
        }

        @Test
        @DisplayName("buildTextStatement_emptyTransactions_producesZeroTotal")
        void buildTextStatement_emptyTransactions_producesZeroTotal() {
            Customer customer = buildCustomer();

            String text = processor.buildTextStatement(customer, Collections.emptyList());

            // Empty list → zero total at scale 2.
            assertThat(text).contains("Total: 0.00");
        }

        @Test
        @DisplayName("buildTextStatement_includesAddressLine2WhenPresent")
        void buildTextStatement_includesAddressLine2WhenPresent() {
            Customer customer = buildCustomer();
            customer.setAddressLine2("APT 4B");

            String text = processor.buildTextStatement(customer, Collections.emptyList());
            assertThat(text).contains("APT 4B");
        }

        @Test
        @DisplayName("buildTextStatement_nullCustomer_throws")
        void buildTextStatement_nullCustomer_throws() {
            assertThatThrownBy(() ->
                    processor.buildTextStatement(null, Collections.emptyList()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("customer");
        }

        @Test
        @DisplayName("buildTextStatement_nullTransactions_throws")
        void buildTextStatement_nullTransactions_throws() {
            assertThatThrownBy(() ->
                    processor.buildTextStatement(buildCustomer(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("transactions");
        }
    }

    // ============================================================
    // Nested test class 2 — buildHtmlStatement with escape verification
    // ============================================================

    @Nested
    @DisplayName("buildHtmlStatement — dual-output HTML rendering")
    class HtmlStatementTests {

        @Test
        @DisplayName("buildHtmlStatement_typicalCustomer_producesWellFormedHtml")
        void buildHtmlStatement_typicalCustomer_producesWellFormedHtml() {
            Customer customer = buildCustomer();
            List<Transaction> txns = List.of(
                    buildTransaction("0000000000000001", "100.00"));

            String html = processor.buildHtmlStatement(customer, txns);

            // Well-formed HTML structure markers.
            assertThat(html).startsWith("<html>");
            assertThat(html).endsWith("</html>");
            assertThat(html).contains("<h1>Customer Statement</h1>");
            assertThat(html).contains("<table");
            assertThat(html).contains("</table>");
            // Customer and transaction data.
            assertThat(html).contains("000000001");
            assertThat(html).contains("ALICE");
            assertThat(html).contains("ANDERSON");
            assertThat(html).contains("0000000000000001");
            // Total.
            assertThat(html).contains("100.00");
        }

        @Test
        @DisplayName("buildHtmlStatement_escapesXmlSpecialCharacters")
        void buildHtmlStatement_escapesXmlSpecialCharacters() {
            // Customer-controlled fields that include HTML-significant chars
            // must be escaped to defeat reflected-XSS attacks.
            Customer customer = buildCustomer();
            customer.setFirstName("ALICE <script>");
            customer.setLastName("\"O'Brien\" & Co");
            customer.setAddressLine1("123 \"Main\" St");

            String html = processor.buildHtmlStatement(customer, Collections.emptyList());

            // Raw special chars must NOT appear unescaped.
            assertThat(html)
                    .as("buildHtmlStatement must escape < character")
                    .doesNotContain("<script>");
            // Escape entities must appear.
            assertThat(html).contains("&lt;script&gt;");
            assertThat(html).contains("&quot;");
            assertThat(html).contains("&amp;");
            assertThat(html).contains("&#39;");
        }

        @Test
        @DisplayName("buildHtmlStatement_nullCustomer_throws")
        void buildHtmlStatement_nullCustomer_throws() {
            assertThatThrownBy(() ->
                    processor.buildHtmlStatement(null, Collections.emptyList()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("buildHtmlStatement_emptyTransactions_stillEmitsTotal")
        void buildHtmlStatement_emptyTransactions_stillEmitsTotal() {
            String html = processor.buildHtmlStatement(
                    buildCustomer(), Collections.emptyList());
            assertThat(html).contains("0.00");
        }
    }

    // ============================================================
    // Nested test class 3 — computeTotal
    // ============================================================

    @Nested
    @DisplayName("computeTotal — per-customer aggregation")
    class ComputeTotalTests {

        @Test
        @DisplayName("computeTotal_typical_sumsAtScaleTwo")
        void computeTotal_typical_sumsAtScaleTwo() {
            List<Transaction> txns = List.of(
                    buildTransaction("01", "10.00"),
                    buildTransaction("02", "20.00"),
                    buildTransaction("03", "30.00"));

            BigDecimal total = processor.computeTotal(txns);

            assertThat(total).isEqualByComparingTo("60.00");
            assertThat(total.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("computeTotal_emptyList_returnsZero")
        void computeTotal_emptyList_returnsZero() {
            BigDecimal total = processor.computeTotal(Collections.emptyList());
            assertThat(total).isEqualByComparingTo("0.00");
            assertThat(total.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("computeTotal_nullAmountsIgnored")
        void computeTotal_nullAmountsIgnored() {
            Transaction nullAmtTxn = new Transaction();
            nullAmtTxn.setTransactionId("XX");
            // amount left null.
            List<Transaction> txns = List.of(
                    buildTransaction("01", "10.00"),
                    nullAmtTxn);

            BigDecimal total = processor.computeTotal(txns);

            assertThat(total).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("computeTotal_nullList_throws")
        void computeTotal_nullList_throws() {
            assertThatThrownBy(() -> processor.computeTotal(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("computeTotal_negativeAmounts_summedCorrectly")
        void computeTotal_negativeAmounts_summedCorrectly() {
            List<Transaction> txns = List.of(
                    buildTransaction("01", "100.00"),
                    buildTransaction("02", "-30.00"),
                    buildTransaction("03", "20.00"));

            BigDecimal total = processor.computeTotal(txns);

            assertThat(total).isEqualByComparingTo("90.00");
        }
    }

    // ============================================================
    // Nested test class 4 — formatTransactionLine
    // ============================================================

    @Nested
    @DisplayName("formatTransactionLine — single-line transaction format")
    class FormatTransactionLineTests {

        @Test
        @DisplayName("formatTransactionLine_typicalTransaction_producesExpectedFormat")
        void formatTransactionLine_typicalTransaction_producesExpectedFormat() {
            Transaction t = buildTransaction("0000000000000001", "25.99");

            String line = processor.formatTransactionLine(t);

            assertThat(line)
                    .as("Line must contain id, type, category, amount, date components")
                    .contains("0000000000000001")
                    .contains("01")    // type
                    .contains("0001")  // category
                    .contains("25.99")
                    .contains("2024-01-15");
        }

        @Test
        @DisplayName("formatTransactionLine_nullTransaction_throws")
        void formatTransactionLine_nullTransaction_throws() {
            assertThatThrownBy(() -> processor.formatTransactionLine(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ============================================================
    // Nested test class 5 — Logging safety
    // ============================================================

    @Nested
    @DisplayName("Logging safety — no financial data in logs (AAP §0.10.5)")
    class LoggingSafetyTests {

        @Test
        @DisplayName("processor_doesNotDeclareLoggerField")
        void processor_doesNotDeclareLoggerField() {
            assertThat(StatementProcessor.class.getDeclaredFields())
                    .as("StatementProcessor must not declare any logger fields")
                    .noneMatch(f -> f.getType().getName().toLowerCase().contains("log"));
        }
    }
}
