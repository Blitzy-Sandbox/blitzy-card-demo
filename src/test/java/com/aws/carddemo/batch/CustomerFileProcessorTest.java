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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link CustomerFileProcessor} — the Java migration of
 * the COBOL {@code CBCUS01C.cbl} customer-file reader.
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>{@code CBCUS01C.cbl} reads every record from the CUSTFILE-FILE
 * sequentially and emits a formatted DISPLAY block. The Java
 * {@link CustomerFileProcessor} exposes pure-function seams for
 * processing, formatting, and counting.
 *
 * <h2>Test Strategy (AAP §0.10.1 Require Test Coverage rule)</h2>
 *
 * <p>Every test invokes the real production
 * {@link CustomerFileProcessor}. No mocks; the class has no
 * collaborators.
 *
 * @see CustomerFileProcessor
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerFileProcessor unit tests (CBCUS01C migration)")
class CustomerFileProcessorTest {

    /** Real SUT — fresh per test. */
    private CustomerFileProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new CustomerFileProcessor();
    }

    private static Customer buildCustomer() {
        Customer c = new Customer();
        c.setCustomerId("000000001");
        c.setFirstName("ALICE");
        c.setMiddleName("M");
        c.setLastName("ANDERSON");
        c.setAddressLine1("123 MAIN ST");
        c.setAddressLine2("APT 4B");
        c.setAddressStateCode("WA");
        c.setAddressCountryCode("USA");
        c.setAddressZip("98101");
        c.setPhoneNumber1("5555550100");
        c.setSsn("123456789");
        c.setGovernmentIssuedId("DL12345");
        c.setDateOfBirth("1980-01-15");
        return c;
    }

    // ============================================================
    // Nested test class 1 — process (EOF/skip semantics)
    // ============================================================

    @Nested
    @DisplayName("process — happy / EOF / skip semantics")
    class ProcessTests {

        @Test
        @DisplayName("process_typicalCustomer_returnsFormattedString")
        void process_typicalCustomer_returnsFormattedString() {
            Customer customer = buildCustomer();
            String result = processor.process(customer);
            assertThat(result)
                    .as("process must return non-null formatted output for non-null customer")
                    .isNotNull()
                    .isNotEmpty()
                    .contains("ALICE")
                    .contains("ANDERSON")
                    .contains("000000001");
        }

        @Test
        @DisplayName("process_nullCustomer_returnsNullForEofSkip")
        void process_nullCustomer_returnsNullForEofSkip() {
            // CBCUS01C EOF/skip semantics: null in → null out.
            String result = processor.process(null);
            assertThat(result).isNull();
        }
    }

    // ============================================================
    // Nested test class 2 — format (DISPLAY block layout)
    // ============================================================

    @Nested
    @DisplayName("format — 1100-DISPLAY-CUST-RECORD layout")
    class FormatTests {

        @Test
        @DisplayName("format_includesAllRequiredLabels")
        void format_includesAllRequiredLabels() {
            String formatted = processor.format(buildCustomer());

            // Verify all required CBCUS01C labels appear.
            assertThat(formatted).contains("CUST-ID");
            assertThat(formatted).contains("CUST-FIRST-NAME");
            assertThat(formatted).contains("CUST-MIDDLE-NAME");
            assertThat(formatted).contains("CUST-LAST-NAME");
            // All values are echoed.
            assertThat(formatted).contains("ALICE");
            assertThat(formatted).contains("ANDERSON");
            assertThat(formatted).contains("000000001");
        }

        @Test
        @DisplayName("format_nullCustomer_throws")
        void format_nullCustomer_throws() {
            assertThatThrownBy(() -> processor.format(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("customer");
        }

        @Test
        @DisplayName("format_nullFields_omittedGracefully")
        void format_nullFields_omittedGracefully() {
            Customer customer = new Customer();
            // All fields null.

            String formatted = processor.format(customer);
            // Format completes without throwing.
            assertThat(formatted)
                    .as("Format must succeed even when all fields are null")
                    .isNotNull()
                    .isNotEmpty();
        }
    }

    // ============================================================
    // Nested test class 3 — countRecord
    // ============================================================

    @Nested
    @DisplayName("countRecord — record counter")
    class CountRecordTests {

        @Test
        @DisplayName("countRecord_zero_returnsOne")
        void countRecord_zero_returnsOne() {
            assertThat(processor.countRecord(0)).isEqualTo(1);
        }

        @Test
        @DisplayName("countRecord_increment")
        void countRecord_increment() {
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
    // Nested test class 4 — Logging safety (PII protection critical)
    // ============================================================

    @Nested
    @DisplayName("Logging safety — no PII in logs (AAP §0.10.5)")
    class LoggingSafetyTests {

        @Test
        @DisplayName("processor_doesNotDeclareLoggerField")
        void processor_doesNotDeclareLoggerField() {
            assertThat(CustomerFileProcessor.class.getDeclaredFields())
                    .as("CustomerFileProcessor must not declare any logger fields "
                            + "(PII protection — AAP §0.10.5)")
                    .noneMatch(f -> f.getType().getName().toLowerCase().contains("log"));
        }
    }
}
