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
 * Unit test for {@link StatementFileProcessor} — the Java migration
 * of the COBOL {@code CBSTM03B} statement-file orchestrator (230 lines;
 * see {@code app/cbl/CBSTM03B.cbl}).
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>{@code CBSTM03B.cbl} orchestrates per-customer statement generation
 * by driving {@code CBSTM03A} for each customer and writing the
 * dual-output streams. The Java
 * {@link StatementFileProcessor} delegates formatting to
 * {@link StatementProcessor} and exposes the orchestration entry point.
 *
 * <h2>Test Strategy (AAP §0.10.1 Require Test Coverage rule)</h2>
 *
 * <p>Every test invokes the <strong>real production
 * {@link StatementFileProcessor}</strong>. The delegate
 * {@link StatementProcessor} is also a real instance — no internal
 * business-logic mocking, in line with AAP §0.10.1.
 *
 * @see StatementFileProcessor
 * @see StatementProcessor
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementFileProcessor unit tests (CBSTM03B migration)")
class StatementFileProcessorTest {

    /** Real SUT — fresh per test. */
    private StatementFileProcessor processor;

    @BeforeEach
    void setUp() {
        // Use the convenience constructor that wires a real
        // StatementProcessor delegate — no internal business-logic
        // mocking (AAP §0.10.1).
        processor = new StatementFileProcessor();
    }

    private static Customer buildCustomer(String id, String firstName, String lastName) {
        Customer c = new Customer();
        c.setCustomerId(id);
        c.setFirstName(firstName);
        c.setLastName(lastName);
        c.setAddressLine1("123 MAIN ST");
        c.setAddressStateCode("WA");
        c.setAddressZip("98101");
        return c;
    }

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
    // Top-level sanity tests
    // ============================================================

    @Test
    @DisplayName("processor_canBeConstructedWithExplicitDelegate")
    void processor_canBeConstructedWithExplicitDelegate() {
        StatementProcessor delegate = new StatementProcessor();
        StatementFileProcessor explicit = new StatementFileProcessor(delegate);

        assertThat(explicit)
                .as("Constructor with explicit delegate must work")
                .isNotNull();
    }

    @Test
    @DisplayName("processor_explicitNullDelegate_throws")
    void processor_explicitNullDelegate_throws() {
        assertThatThrownBy(() -> new StatementFileProcessor(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("delegate");
    }

    // ============================================================
    // Nested test class 1 — process() orchestration
    // ============================================================

    @Nested
    @DisplayName("process — per-customer orchestration")
    class ProcessTests {

        @Test
        @DisplayName("process_typicalCustomer_producesDualOutputBundle")
        void process_typicalCustomer_producesDualOutputBundle() {
            Customer customer = buildCustomer("000000001", "ALICE", "ANDERSON");
            List<Transaction> txns = List.of(
                    buildTransaction("0000000000000001", "25.99"),
                    buildTransaction("0000000000000002", "10.50"));

            StatementFileProcessor.StatementBundle bundle = processor.process(customer, txns);

            // Bundle must be non-null and carry the customer ID.
            assertThat(bundle).isNotNull();
            assertThat(bundle.getCustomerId()).isEqualTo("000000001");
            // Both formats must be present.
            assertThat(bundle.getText())
                    .as("Text statement must be present and contain customer data")
                    .isNotEmpty()
                    .contains("ALICE")
                    .contains("ANDERSON");
            assertThat(bundle.getHtml())
                    .as("HTML statement must be present and well-formed")
                    .isNotEmpty()
                    .startsWith("<html>")
                    .contains("ANDERSON");
            // Both must include the total (25.99 + 10.50 = 36.49).
            assertThat(bundle.getText()).contains("36.49");
            assertThat(bundle.getHtml()).contains("36.49");
        }

        @Test
        @DisplayName("process_nullCustomer_returnsNull")
        void process_nullCustomer_returnsNull() {
            // EOF/skip semantics from the COBOL orchestrator.
            StatementFileProcessor.StatementBundle bundle =
                    processor.process(null, Collections.emptyList());
            assertThat(bundle)
                    .as("Null customer must return null (EOF semantics)")
                    .isNull();
        }

        @Test
        @DisplayName("process_nonNullCustomerNullTransactions_throws")
        void process_nonNullCustomerNullTransactions_throws() {
            Customer customer = buildCustomer("000000001", "B", "B");
            assertThatThrownBy(() -> processor.process(customer, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("transactions");
        }

        @Test
        @DisplayName("process_customerWithEmptyTransactionList_producesBundleWithZeroTotal")
        void process_customerWithEmptyTransactionList_producesBundleWithZeroTotal() {
            Customer customer = buildCustomer("000000002", "BOB", "BAKER");

            StatementFileProcessor.StatementBundle bundle =
                    processor.process(customer, Collections.emptyList());

            assertThat(bundle).isNotNull();
            assertThat(bundle.getCustomerId()).isEqualTo("000000002");
            // Both outputs include the zero total.
            assertThat(bundle.getText()).contains("0.00");
            assertThat(bundle.getHtml()).contains("0.00");
        }

        @Test
        @DisplayName("process_multipleCustomers_eachBundleCarriesCorrectId")
        void process_multipleCustomers_eachBundleCarriesCorrectId() {
            // Verify the orchestrator does not leak state between
            // customer processings (independence of consecutive calls).
            Customer alice = buildCustomer("000000001", "ALICE", "A");
            Customer bob = buildCustomer("000000002", "BOB", "B");

            StatementFileProcessor.StatementBundle aliceBundle =
                    processor.process(alice, Collections.emptyList());
            StatementFileProcessor.StatementBundle bobBundle =
                    processor.process(bob, Collections.emptyList());

            assertThat(aliceBundle.getCustomerId()).isEqualTo("000000001");
            assertThat(bobBundle.getCustomerId()).isEqualTo("000000002");
            // No cross-contamination.
            assertThat(aliceBundle.getText()).contains("ALICE");
            assertThat(aliceBundle.getText()).doesNotContain("BOB");
            assertThat(bobBundle.getText()).contains("BOB");
            assertThat(bobBundle.getText()).doesNotContain("ALICE");
        }
    }

    // ============================================================
    // Nested test class 2 — StatementBundle value object
    // ============================================================

    @Nested
    @DisplayName("StatementBundle — immutable value object")
    class StatementBundleTests {

        @Test
        @DisplayName("bundle_carriesAllFieldsAccessibly")
        void bundle_carriesAllFieldsAccessibly() {
            StatementFileProcessor.StatementBundle bundle =
                    new StatementFileProcessor.StatementBundle(
                            "C001", "text-content", "<html>html-content</html>");

            assertThat(bundle.getCustomerId()).isEqualTo("C001");
            assertThat(bundle.getText()).isEqualTo("text-content");
            assertThat(bundle.getHtml()).isEqualTo("<html>html-content</html>");
        }

        @Test
        @DisplayName("bundle_nullText_throws")
        void bundle_nullText_throws() {
            assertThatThrownBy(() ->
                    new StatementFileProcessor.StatementBundle("C001", null, "<html/>"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("text");
        }

        @Test
        @DisplayName("bundle_nullHtml_throws")
        void bundle_nullHtml_throws() {
            assertThatThrownBy(() ->
                    new StatementFileProcessor.StatementBundle("C001", "text", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("html");
        }

        @Test
        @DisplayName("bundle_nullCustomerIdAllowed")
        void bundle_nullCustomerIdAllowed() {
            // Defensive: customer ID may be null if the upstream record
            // was missing one (the CBSTM03B orchestrator carries this
            // through unchanged for downstream forensic analysis).
            StatementFileProcessor.StatementBundle bundle =
                    new StatementFileProcessor.StatementBundle(null, "text", "<html/>");
            assertThat(bundle.getCustomerId()).isNull();
        }
    }

    // ============================================================
    // Nested test class 3 — Logging safety
    // ============================================================

    @Nested
    @DisplayName("Logging safety — no financial data in logs (AAP §0.10.5)")
    class LoggingSafetyTests {

        @Test
        @DisplayName("processor_doesNotDeclareLoggerField")
        void processor_doesNotDeclareLoggerField() {
            assertThat(StatementFileProcessor.class.getDeclaredFields())
                    .as("StatementFileProcessor must not declare any logger fields")
                    .noneMatch(f -> f.getType().getName().toLowerCase().contains("log"));
        }
    }
}
