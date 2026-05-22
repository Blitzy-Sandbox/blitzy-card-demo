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

import com.aws.carddemo.entity.Account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link AccountFileProcessor} — the Java migration of
 * the COBOL {@code CBACT01C} account-file reader (193 lines; see
 * {@code app/cbl/CBACT01C.cbl}).
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>{@code CBACT01C.cbl} reads each record sequentially from the
 * ACCTFILE VSAM KSDS via the {@code 1000-ACCTFILE-GET-NEXT} paragraph,
 * formats it via {@code 1100-DISPLAY-ACCT-RECORD}, and increments
 * {@code WS-RECORD-COUNT} via {@code ADD 1 TO WS-RECORD-COUNT}.
 *
 * <h2>Test Strategy (AAP §0.10.1 Require Test Coverage rule)</h2>
 *
 * <p>Every test invokes the <strong>real production
 * {@link AccountFileProcessor}</strong>. No internal logic
 * reimplementation.
 *
 * <h2>Coverage Categories per AAP §0.5.1</h2>
 * <ul>
 *   <li>Happy read — every Account → non-null formatted output.</li>
 *   <li>Malformed/EOF — null input → null output (skip semantic).</li>
 *   <li>Count parity — countRecord exact-by-one increment.</li>
 *   <li>Logging safety — no logger declared on the production class.</li>
 * </ul>
 *
 * @see AccountFileProcessor
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountFileProcessor unit tests (CBACT01C migration)")
class AccountFileProcessorTest {

    /** Real SUT — fresh per test (test isolation per AAP §0.10.9). */
    private AccountFileProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new AccountFileProcessor();
    }

    /** Convenience — build a fully populated Account fixture. */
    private static Account buildAccount() {
        Account a = new Account();
        a.setAccountId("00000000010");
        a.setActiveStatus("Y");
        a.setCurrentBalance(new BigDecimal("1234.56"));
        a.setCreditLimit(new BigDecimal("5000.00"));
        a.setCashCreditLimit(new BigDecimal("500.00"));
        a.setOpenDate("2020-01-15");
        a.setExpirationDate("2030-01-15");
        a.setReissueDate("2025-01-15");
        a.setCurrentCycleCredit(new BigDecimal("100.00"));
        a.setCurrentCycleDebit(new BigDecimal("200.00"));
        a.setAddressZip("98101");
        a.setGroupId("DEFAULT   ");
        a.setCustomerId("000000001");
        return a;
    }

    // ============================================================
    // Nested test class 1 — process (happy read / EOF skip)
    // ============================================================

    /**
     * Coverage for {@link AccountFileProcessor#process(Account)} — the
     * Java equivalent of CBACT01C's 1000-ACCTFILE-GET-NEXT → 1100-DISPLAY
     * dispatch.
     */
    @Nested
    @DisplayName("process — happy read / EOF skip semantics")
    class ProcessTests {

        @Test
        @DisplayName("process_typicalAccount_returnsFormattedString")
        void process_typicalAccount_returnsFormattedString() {
            Account account = buildAccount();

            String result = processor.process(account);

            assertThat(result)
                    .as("process must return non-null formatted output for non-null account")
                    .isNotNull()
                    .isNotEmpty()
                    .contains("00000000010")
                    .contains("1234.56");
        }

        @Test
        @DisplayName("process_nullAccount_returnsNullForEofSkip")
        void process_nullAccount_returnsNullForEofSkip() {
            // CBACT01C EOF/skip semantics: when the COBOL READ returns
            // STATUS '10' (end-of-file), the next record is null and
            // the migration's Spring Batch ItemProcessor returns null
            // to signal "skip this iteration."
            String result = processor.process(null);
            assertThat(result)
                    .as("Null input must return null (EOF skip semantics)")
                    .isNull();
        }
    }

    // ============================================================
    // Nested test class 2 — format (1100-DISPLAY-ACCT-RECORD layout)
    // ============================================================

    /**
     * Coverage for the {@link AccountFileProcessor#format(Account)}
     * method — the Java equivalent of {@code 1100-DISPLAY-ACCT-RECORD}.
     */
    @Nested
    @DisplayName("format — 1100-DISPLAY-ACCT-RECORD layout")
    class FormatTests {

        @Test
        @DisplayName("format_includesAllCobolLabels")
        void format_includesAllCobolLabels() {
            String formatted = processor.format(buildAccount());

            // Verify every CBACT01C label appears in the output.
            assertThat(formatted).contains("ACCT-ID");
            assertThat(formatted).contains("ACCT-ACTIVE-STATUS");
            assertThat(formatted).contains("ACCT-CURR-BAL");
            assertThat(formatted).contains("ACCT-CREDIT-LIMIT");
            assertThat(formatted).contains("ACCT-CASH-CREDIT-LIMIT");
            assertThat(formatted).contains("ACCT-OPEN-DATE");
            assertThat(formatted).contains("ACCT-EXPIRAION-DATE"); // verbatim COBOL spelling
            assertThat(formatted).contains("ACCT-REISSUE-DATE");
            assertThat(formatted).contains("ACCT-CURR-CYC-CREDIT");
            assertThat(formatted).contains("ACCT-CURR-CYC-DEBIT");
            assertThat(formatted).contains("ACCT-GROUP-ID");
        }

        @Test
        @DisplayName("format_includesAllAccountValues")
        void format_includesAllAccountValues() {
            String formatted = processor.format(buildAccount());

            // Verify every Account value appears in the output.
            assertThat(formatted).contains("00000000010");  // accountId
            assertThat(formatted).contains("Y");            // active status
            assertThat(formatted).contains("1234.56");      // currentBalance
            assertThat(formatted).contains("5000.00");      // creditLimit
            assertThat(formatted).contains("500.00");       // cashCreditLimit
            assertThat(formatted).contains("2020-01-15");   // openDate
            assertThat(formatted).contains("2030-01-15");   // expirationDate
            assertThat(formatted).contains("100.00");       // cycleCredit
            assertThat(formatted).contains("200.00");       // cycleDebit
            assertThat(formatted).contains("DEFAULT");      // groupId
        }

        @Test
        @DisplayName("format_monetaryValuesAtScaleTwo_noScientificNotation")
        void format_monetaryValuesAtScaleTwo_noScientificNotation() {
            Account account = new Account();
            account.setAccountId("00000000099");
            // Edge value where BigDecimal.toString() would render as
            // "1E+2" (scientific notation) if it had scale -2. The
            // production format() uses toPlainString() to defeat this.
            // Use new BigDecimal("1E+2") explicitly because new BigDecimal("100")
            // produces scale=0 which toString renders as "100" (no notation).
            account.setCurrentBalance(new BigDecimal("1E+2"));    // value 100, scale -2
            account.setCreditLimit(new BigDecimal("1E-2"));        // value 0.01, scale 2
            account.setCashCreditLimit(new BigDecimal("0.00"));
            account.setCurrentCycleCredit(new BigDecimal("0.00"));
            account.setCurrentCycleDebit(new BigDecimal("0.00"));

            String formatted = processor.format(account);

            // The monetary value section comes after "ACCT-CURR-BAL           :".
            // Extract that section and verify no scientific notation.
            int balanceStart = formatted.indexOf("ACCT-CURR-BAL");
            int balanceEnd = formatted.indexOf('\n', balanceStart);
            String balanceLine = formatted.substring(balanceStart, balanceEnd);
            assertThat(balanceLine)
                    .as("ACCT-CURR-BAL line must NOT contain scientific notation "
                            + "(toPlainString contract for monetary values)")
                    .doesNotContain("E+")
                    .doesNotContain("E-");

            // The 1E+2 input must render as plain "100".
            assertThat(balanceLine).contains("100");

            // Same check for ACCT-CREDIT-LIMIT line (1E-2 input).
            int limitStart = formatted.indexOf("ACCT-CREDIT-LIMIT");
            int limitEnd = formatted.indexOf('\n', limitStart);
            String limitLine = formatted.substring(limitStart, limitEnd);
            assertThat(limitLine)
                    .as("ACCT-CREDIT-LIMIT line must NOT contain scientific notation")
                    .doesNotContain("E+")
                    .doesNotContain("E-");
            assertThat(limitLine).contains("0.01");
        }

        @Test
        @DisplayName("format_nullAccount_throws")
        void format_nullAccount_throws() {
            assertThatThrownBy(() -> processor.format(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("account");
        }

        @Test
        @DisplayName("format_nullFields_omittedGracefully")
        void format_nullFields_omittedGracefully() {
            Account account = new Account();
            // All fields null — Account is uninitialised.

            String formatted = processor.format(account);

            // Format must succeed even when all fields are null.
            assertThat(formatted)
                    .as("Format must produce non-null/non-empty output for null fields")
                    .isNotNull()
                    .isNotEmpty()
                    .contains("ACCT-ID");
        }
    }

    // ============================================================
    // Nested test class 3 — countRecord (WS-RECORD-COUNT)
    // ============================================================

    /**
     * Coverage for {@link AccountFileProcessor#countRecord(int)} — the
     * Java equivalent of the COBOL {@code ADD 1 TO WS-RECORD-COUNT}.
     */
    @Nested
    @DisplayName("countRecord — WS-RECORD-COUNT parity")
    class CountRecordTests {

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
            assertThat(processor.countRecord(49)).isEqualTo(50);
            // Verify ASCII fixture acctdata.txt's 50 records produce
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

        @Test
        @DisplayName("countRecord_largeRunningTotal_acceptsValue")
        void countRecord_largeRunningTotal_acceptsValue() {
            // Real-world VSAM files commonly carry millions of records;
            // verify the counter still works at scale.
            assertThat(processor.countRecord(1_000_000)).isEqualTo(1_000_001);
        }
    }

    // ============================================================
    // Nested test class 4 — Logging safety (AAP §0.10.5)
    // ============================================================

    @Nested
    @DisplayName("Logging safety — no financial data in logs (AAP §0.10.5)")
    class LoggingSafetyTests {

        @Test
        @DisplayName("processor_doesNotDeclareLoggerField")
        void processor_doesNotDeclareLoggerField() {
            // Pure arithmetic/formatting seam — must have no logger field.
            // Production logging is the responsibility of the Spring Batch
            // StepExecutionListener wrapping this processor, NOT the
            // processor itself (defence-in-depth against AAP §0.10.5
            // violations).
            assertThat(AccountFileProcessor.class.getDeclaredFields())
                    .as("AccountFileProcessor must not declare any logger fields "
                            + "(AAP §0.10.5 defensive design)")
                    .noneMatch(f -> f.getType().getName().toLowerCase().contains("log"));
        }
    }
}
