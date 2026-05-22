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

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Java migration of the COBOL batch program {@code CBACT01C.cbl} — read and
 * print the {@code ACCTDAT} VSAM KSDS account-master file.
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code app/cbl/CBACT01C.cbl} reads every record from the ACCTFILE-FILE
 * sequentially and emits a formatted human-readable display block to
 * SYSOUT for each. The COBOL program is the canonical example of a
 * read-only batch utility in the CardDemo codebase: no validation, no
 * mutation, no joins — just iterate and print.
 *
 * <p>Relevant COBOL paragraphs:
 * <pre>
 *   PROCEDURE DIVISION
 *      0000-ACCTFILE-OPEN
 *      PERFORM UNTIL END-OF-FILE = 'Y'
 *          1000-ACCTFILE-GET-NEXT
 *              READ ACCTFILE-FILE INTO ACCOUNT-RECORD
 *              ON STATUS '00' -> 1100-DISPLAY-ACCT-RECORD
 *              ON STATUS '10' -> END-OF-FILE = 'Y'
 *              ON OTHER       -> ABEND
 *          1100-DISPLAY-ACCT-RECORD
 *              DISPLAY 'ACCT-ID                 :' ACCT-ID
 *              DISPLAY 'ACCT-ACTIVE-STATUS      :' ACCT-ACTIVE-STATUS
 *              DISPLAY 'ACCT-CURR-BAL           :' ACCT-CURR-BAL
 *              ... and so on for every ACCT-* field
 *      9000-ACCTFILE-CLOSE
 * </pre>
 *
 * <h2>Java Migration Shape</h2>
 *
 * <p>The COBOL "open file, loop, print, close" pattern translates to a
 * Spring Batch step where:
 * <ul>
 *   <li>The {@code FlatFileItemReader<Account>} (or JPA-driven reader)
 *       replaces the {@code OPEN INPUT ACCTFILE-FILE} + sequential
 *       {@code READ}.</li>
 *   <li>The {@link AccountFileProcessor#format(Account)} method below is
 *       the {@code ItemProcessor} that replaces the
 *       {@code 1100-DISPLAY-ACCT-RECORD} paragraph — it converts one
 *       {@link Account} into a multi-line human-readable string with the
 *       same field labels and field order as the COBOL DISPLAY block.</li>
 *   <li>The {@code FlatFileItemWriter<String>} replaces the
 *       {@code CLOSE} step's implicit SYSOUT flush.</li>
 *   <li>The {@link AccountFileProcessor#process(Account)} method is the
 *       full read-loop semantics: validate the input is non-null, then
 *       delegate to {@link #format(Account)}; null input is treated as
 *       end-of-file and yields {@code null} so Spring Batch's
 *       null-skipping convention drops the record (matches COBOL's
 *       END-OF-FILE = 'Y' branch).</li>
 * </ul>
 *
 * <h2>Contract</h2>
 *
 * <p>{@link #format(Account)} is a pure function — no I/O, no clock, no
 * randomness — taking one {@link Account} and returning a deterministic
 * multi-line {@link String} suitable for SYSOUT display. The output line
 * order matches the COBOL paragraph's DISPLAY order exactly so the SYSOUT
 * byte-equality parity check (subsequent baseline-parity IT) can compare
 * line-by-line against the COBOL reference output.
 *
 * <p>{@link #process(Account)} is a pure function — null in yields null
 * out (skip / EOF semantics); non-null in yields the formatted display
 * string from {@link #format(Account)}.
 *
 * <p>{@link #countRecord(int)} is a pure function — adds 1 to the given
 * count and returns the new count. This is the Java equivalent of the
 * COBOL {@code ADD 1 TO WS-RECORD-COUNT} step that the surrounding
 * Spring Batch StepExecutionListener uses to assert input/output count
 * parity.
 *
 * <h2>Minimal Change Clause Compliance</h2>
 *
 * <p>Per AAP §0.10.2, this class preserves the COBOL business logic
 * structure: each public method maps directly to a named COBOL paragraph
 * ({@code 1100-DISPLAY-ACCT-RECORD} → {@link #format(Account)},
 * implicit read-loop body → {@link #process(Account)},
 * {@code ADD 1 TO WS-RECORD-COUNT} → {@link #countRecord(int)}). No
 * abstractions are introduced beyond the standard ItemProcessor seam that
 * Spring Batch requires.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.3.1 (Test Target Identification — AccountFileProcessor for
 * the {@code CBACT01C} migration), §0.5.1 (File-by-File Test Plan), and
 * §0.10.2 (Minimal Change Clause — no abstractions beyond what the
 * migration requires).
 *
 * @see com.aws.carddemo.entity.Account
 * @see AccountFileProcessorTest
 */
public class AccountFileProcessor {

    /**
     * Constructs a new processor.
     *
     * <p>This class has no external collaborators (no repository, no
     * writer, no clock) — every public method is a pure function over
     * its {@link Account} argument. The no-arg constructor exists to
     * keep the class Spring-instantiable when wired as an
     * {@code @Bean} in a {@code @Configuration} class.
     */
    public AccountFileProcessor() {
        // No collaborators to inject — see class-level Javadoc.
    }

    /**
     * Process one {@link Account} item — the {@code ItemProcessor}
     * equivalent of the COBOL read-loop body (lines 75-86 of
     * {@code CBACT01C.cbl}).
     *
     * <p>Semantics:
     * <ul>
     *   <li>{@code account == null} → return {@code null}. Spring Batch
     *       treats null as "skip this item"; the COBOL equivalent is the
     *       {@code IF END-OF-FILE = 'N'} guard inside the
     *       PERFORM UNTIL loop.</li>
     *   <li>{@code account != null} → delegate to
     *       {@link #format(Account)}, returning the multi-line DISPLAY
     *       block.</li>
     * </ul>
     *
     * <p>This method does NOT mutate {@code account}. The COBOL paragraph
     * is read-only with respect to the ACCOUNT-RECORD; the Java
     * implementation preserves that invariant.
     *
     * @param account the input account record from the file reader, or
     *                {@code null} if the reader has reached EOF
     * @return the multi-line DISPLAY block for {@code account}, or
     *         {@code null} if {@code account} is {@code null}
     */
    public String process(Account account) {
        if (account == null) {
            return null;
        }
        return format(account);
    }

    /**
     * Format one {@link Account} as a multi-line human-readable DISPLAY
     * block — the Java equivalent of COBOL paragraph
     * {@code 1100-DISPLAY-ACCT-RECORD}.
     *
     * <p>The output line order matches the COBOL paragraph's
     * {@code DISPLAY} statements exactly:
     * <pre>
     *   ACCT-ID                 :{accountId}
     *   ACCT-ACTIVE-STATUS      :{activeStatus}
     *   ACCT-CURR-BAL           :{currentBalance}
     *   ACCT-CREDIT-LIMIT       :{creditLimit}
     *   ACCT-CASH-CREDIT-LIMIT  :{cashCreditLimit}
     *   ACCT-OPEN-DATE          :{openDate}
     *   ACCT-EXPIRAION-DATE     :{expirationDate}
     *   ACCT-REISSUE-DATE       :{reissueDate}
     *   ACCT-CURR-CYC-CREDIT    :{currentCycleCredit}
     *   ACCT-CURR-CYC-DEBIT     :{currentCycleDebit}
     *   ACCT-GROUP-ID           :{groupId}
     *   -------------------------------------------------
     * </pre>
     *
     * <p>{@link BigDecimal} fields are rendered via {@link BigDecimal#toPlainString()}
     * to avoid scientific notation; the COBOL DISPLAY of a {@code PIC
     * S9(10)V99} field emits the value with its implied decimal at the
     * fixed position (e.g., {@code -000001234.56}), so the Java
     * representation must likewise be a fixed-point decimal string. Per
     * AAP §0.10.3 (No float/double for monetary values), monetary fields
     * remain {@link BigDecimal} end-to-end through this method.
     *
     * @param account the account to format; must not be {@code null}
     * @return the multi-line DISPLAY block (lines separated by {@code "\n"}; no leading or trailing newline)
     * @throws NullPointerException if {@code account} is {@code null}
     */
    public String format(Account account) {
        Objects.requireNonNull(account, "account must not be null");

        StringBuilder sb = new StringBuilder(512);
        sb.append("ACCT-ID                 :").append(nullSafe(account.getAccountId())).append('\n');
        sb.append("ACCT-ACTIVE-STATUS      :").append(nullSafe(account.getActiveStatus())).append('\n');
        sb.append("ACCT-CURR-BAL           :").append(formatMoney(account.getCurrentBalance())).append('\n');
        sb.append("ACCT-CREDIT-LIMIT       :").append(formatMoney(account.getCreditLimit())).append('\n');
        sb.append("ACCT-CASH-CREDIT-LIMIT  :").append(formatMoney(account.getCashCreditLimit())).append('\n');
        sb.append("ACCT-OPEN-DATE          :").append(nullSafe(account.getOpenDate())).append('\n');
        sb.append("ACCT-EXPIRAION-DATE     :").append(nullSafe(account.getExpirationDate())).append('\n');
        sb.append("ACCT-REISSUE-DATE       :").append(nullSafe(account.getReissueDate())).append('\n');
        sb.append("ACCT-CURR-CYC-CREDIT    :").append(formatMoney(account.getCurrentCycleCredit())).append('\n');
        sb.append("ACCT-CURR-CYC-DEBIT     :").append(formatMoney(account.getCurrentCycleDebit())).append('\n');
        sb.append("ACCT-GROUP-ID           :").append(nullSafe(account.getGroupId())).append('\n');
        sb.append("-------------------------------------------------");
        return sb.toString();
    }

    /**
     * Increment a record counter — the Java equivalent of COBOL
     * {@code ADD 1 TO WS-RECORD-COUNT}.
     *
     * <p>This method exists as a public seam so the surrounding Spring
     * Batch {@code StepExecutionListener} (or test code) can verify the
     * read-count invariant: every record returned non-null from
     * {@link #process(Account)} corresponds to exactly one increment of
     * the read counter. This is the Java replacement for the COBOL
     * {@code ADD 1 TO WS-RECORD-COUNT} step at line 76 of
     * {@code CBACT01C.cbl}.
     *
     * @param previousCount the running total before this record (must be {@code >= 0})
     * @return {@code previousCount + 1}
     * @throws IllegalArgumentException if {@code previousCount < 0}
     */
    public int countRecord(int previousCount) {
        if (previousCount < 0) {
            throw new IllegalArgumentException(
                    "previousCount must be non-negative; got " + previousCount);
        }
        return previousCount + 1;
    }

    /**
     * Returns {@code value} if non-null, otherwise the empty string.
     *
     * <p>COBOL {@code DISPLAY} of an uninitialised PIC X field would
     * print spaces; the Java equivalent for an uninitialised String
     * field is empty-string display. This null-safety is purely a
     * defence-in-depth measure — a well-formed {@link Account} from a
     * normal file read will never carry nulls.
     */
    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Renders a monetary {@link BigDecimal} as a fixed-point string with
     * scale preserved (e.g., {@code "0.00"}, not {@code "0"}; {@code "1234.56"},
     * not {@code "1.23456E+3"}).
     *
     * <p>Uses {@link BigDecimal#toPlainString()} so trailing zeros at the
     * declared scale are preserved (e.g., a balance of zero at scale 2
     * displays as {@code "0.00"}, matching the COBOL DISPLAY of
     * {@code PIC S9(10)V99} value zero). Null inputs render as the
     * empty string (no NPE).
     */
    private static String formatMoney(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
