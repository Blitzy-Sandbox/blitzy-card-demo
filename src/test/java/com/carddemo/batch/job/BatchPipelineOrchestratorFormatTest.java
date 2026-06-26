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
package com.carddemo.batch.job;

import java.math.BigDecimal;

import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the PRTCATBL line formatting in {@link BatchPipelineOrchestrator}.
 *
 * <p>The category-balance pipeline produces two distinct records from the same
 * {@code TCATBALF} balance:</p>
 * <ul>
 *   <li>the {@code STEP05R} backup unload &mdash; the byte-for-byte IDCAMS
 *       {@code REPRO} image of the VSAM record, in which
 *       {@code TRAN-CAT-BAL PIC S9(09)V99} carries its trailing-byte overpunch
 *       sign;</li>
 *   <li>the {@code STEP10R} formatted print line &mdash; the DFSORT
 *       {@code EDIT=(TTTTTTTTT.TT)} magnitude rendering, which carries no
 *       sign.</li>
 * </ul>
 *
 * <p>These tests pin both records: the backup balance segment (the trailing
 * eleven characters of the 28-character significant content) for positive,
 * zero, and negative amounts including the exact overpunch byte, and the print
 * balance which renders the magnitude only. Sign byte mapping uses
 * {@code '{' / 'A'-'I'} for non-negative trailing digits 0-9 and
 * {@code '}' / 'J'-'R'} for negative trailing digits 0-9.</p>
 */
@DisplayName("BatchPipelineOrchestrator — PRTCATBL backup (50B) signed-zoned + print (40B) magnitude")
class BatchPipelineOrchestratorFormatTest {

    /** Account id (11 zoned digits), transaction type (2 chars), category code (4 zoned digits). */
    private static final long ACCT_ID = 12_345_678_901L;
    private static final String TYPE_CD = "PU";
    private static final int CAT_CD = 100;

    /** The 17-character key prefix preceding the 11-character balance segment. */
    private static final String KEY_PREFIX = "12345678901" + "PU" + "0100";

    /** Offset of the balance segment inside the 28-character significant content. */
    private static final int BALANCE_OFFSET = 17;

    /**
     * Builds a category balance on the fixed key with the supplied amount.
     *
     * @param amount the {@code TRAN-CAT-BAL} value (may be {@code null})
     * @return the populated entity
     */
    private static TransactionCategoryBalance bal(BigDecimal amount) {
        return new TransactionCategoryBalance(
                new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD), amount);
    }

    @Test
    @DisplayName("backup: a positive balance keeps a positive overpunch on the trailing byte")
    void backupPositiveBalanceOverpunch() {
        String line = BatchPipelineOrchestrator.formatBackupLine(bal(new BigDecimal("12345.67")));

        assertThat(line).hasSize(28).startsWith(KEY_PREFIX);
        // 12345.67 -> 1234567 cents -> 00001234567 (11 digits); trailing '7' positive -> 'G'.
        assertThat(line.substring(BALANCE_OFFSET)).isEqualTo("0000123456G");
        assertThat(line.charAt(27)).isEqualTo('G');
    }

    @Test
    @DisplayName("backup: a negative balance encodes a negative overpunch on the trailing byte")
    void backupNegativeBalanceOverpunch() {
        String line = BatchPipelineOrchestrator.formatBackupLine(bal(new BigDecimal("-12345.67")));

        assertThat(line).hasSize(28).startsWith(KEY_PREFIX);
        // -12345.67 -> magnitude 00001234567; trailing '7' negative -> 'P'.
        assertThat(line.substring(BALANCE_OFFSET)).isEqualTo("0000123456P");
        assertThat(line.charAt(27)).isEqualTo('P');
    }

    @Test
    @DisplayName("backup: a zero balance encodes the positive-zero overpunch '{'")
    void backupZeroBalanceOverpunch() {
        String line = BatchPipelineOrchestrator.formatBackupLine(bal(new BigDecimal("0.00")));

        assertThat(line.substring(BALANCE_OFFSET)).isEqualTo("0000000000{");
    }

    @Test
    @DisplayName("backup: a null balance is treated as positive zero")
    void backupNullBalanceTreatedAsZero() {
        String line = BatchPipelineOrchestrator.formatBackupLine(bal(null));

        assertThat(line.substring(BALANCE_OFFSET)).isEqualTo("0000000000{");
    }

    @Test
    @DisplayName("backup: a negative balance whose trailing digit is zero encodes '}'")
    void backupNegativeTrailingZeroOverpunch() {
        String line = BatchPipelineOrchestrator.formatBackupLine(bal(new BigDecimal("-10.00")));

        // -10.00 -> magnitude 1000 -> 00000001000; trailing '0' negative -> '}'.
        assertThat(line.substring(BALANCE_OFFSET)).isEqualTo("0000000100}");
    }

    @Test
    @DisplayName("backup: the maximum nine-integer-digit balance fills all eleven positions")
    void backupMaximumBalanceFillsAllPositions() {
        String line = BatchPipelineOrchestrator.formatBackupLine(bal(new BigDecimal("999999999.99")));

        // 99999999999 cents; trailing '9' positive -> 'I'.
        assertThat(line.substring(BALANCE_OFFSET)).isEqualTo("9999999999I");
    }

    @Test
    @DisplayName("backup: HALF_EVEN rounding is applied before the overpunch is taken")
    void backupRoundsHalfEvenBeforeEncoding() {
        // 1.005 rounds HALF_EVEN to 1.00 -> 100 cents -> 00000000100; trailing '0' positive -> '{'.
        String line = BatchPipelineOrchestrator.formatBackupLine(bal(new BigDecimal("1.005")));

        assertThat(line.substring(BALANCE_OFFSET)).isEqualTo("0000000010{");
    }

    @Test
    @DisplayName("print: the balance renders the magnitude with a decimal point and no sign")
    void printRendersMagnitudeWithDecimalPoint() {
        String line = BatchPipelineOrchestrator.formatPrintLine(bal(new BigDecimal("12345.67")));

        // 32 significant characters: 11 + ' ' + 2 + ' ' + 4 + ' ' + "000012345.67".
        assertThat(line).hasSize(32);
        assertThat(line).endsWith("000012345.67");
    }

    @Test
    @DisplayName("print: positive and negative magnitudes are identical (DFSORT EDIT drops the sign)")
    void printDropsSignWhileBackupKeepsIt() {
        BigDecimal positive = new BigDecimal("12345.67");
        BigDecimal negative = new BigDecimal("-12345.67");

        // The print line is sign-agnostic (magnitude only)...
        assertThat(BatchPipelineOrchestrator.formatPrintLine(bal(positive)))
                .isEqualTo(BatchPipelineOrchestrator.formatPrintLine(bal(negative)));

        // ...while the backup line preserves the sign in its trailing byte.
        assertThat(BatchPipelineOrchestrator.formatBackupLine(bal(positive)))
                .isNotEqualTo(BatchPipelineOrchestrator.formatBackupLine(bal(negative)));
    }
}
