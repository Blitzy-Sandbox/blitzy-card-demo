/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.unit.batch.processor;

import java.math.BigDecimal;

import com.carddemo.batch.processor.DailyTransactionRecordImage;
import com.carddemo.entity.DailyTransaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Byte-exactness unit tests for {@link DailyTransactionRecordImage}, the renderer
 * that reconstructs the 350-byte {@code CVTRA06Y DALYTRAN-RECORD} image from a
 * staged {@link DailyTransaction} entity so the reject side-channel can emit a
 * byte-equivalent {@code DALYREJS} record (Validation Gates 1 and 4).
 *
 * <p>The expected values are taken verbatim from the authoritative ASCII fixture
 * {@code app/data/ASCII/dailytran.txt} at source commit {@code 27d6c6f}; every
 * one of its 300 records round-trips (decode &rarr; re-encode) with zero
 * mismatches, and all twenty zoned-decimal overpunch characters
 * ({@code {ABCDEFGHI} / {@code }JKLMNOPQR}) appear in that data. The amounts used
 * below are the exact fixture amounts for each overpunch class (for example the
 * {@code G} record renders {@code 504.77} and the {@code }} record renders
 * {@code -919.00}).</p>
 */
@DisplayName("DailyTransactionRecordImage — 350-byte CVTRA06Y image (Gate 1/4)")
class DailyTransactionRecordImageTest {

    private static final int RECORD_WIDTH = 350;

    /** Right-justified, space-padded to {@code width} (independent of production padding). */
    private static String rpad(String value, int width) {
        return String.format("%-" + width + "s", value == null ? "" : value);
    }

    /**
     * Builds a daily transaction carrying only the amount, leaving every other
     * field {@code null}, to isolate the signed zoned-decimal amount encoding.
     */
    private static DailyTransaction amountOnly(BigDecimal amount) {
        DailyTransaction item = new DailyTransaction();
        item.setTranAmt(amount);
        return item;
    }

    /** Extracts the 11-character {@code DALYTRAN-AMT} field (offset 132). */
    private static String amountField(String image) {
        return image.substring(132, 143);
    }

    @Nested
    @DisplayName("Full record byte layout")
    class FullRecord {

        /**
         * Reconstructs the first authoritative fixture record from its parsed
         * field values and asserts the rendered image is byte-identical to the
         * original 350-byte input record, field by field and as a whole.
         */
        @Test
        @DisplayName("renders the first dailytran.txt record byte-exactly")
        void rendersFirstFixtureRecordByteExactly() {
            DailyTransaction item = new DailyTransaction(
                    "0000000000683580",            // DALYTRAN-ID    X(16)
                    "01",                          // TYPE-CD        X(02)
                    1,                             // CAT-CD         9(04) -> 0001
                    "POS TERM",                    // SOURCE         X(10)
                    "Purchase at Abshire-Lowe",    // DESC           X(100)
                    new BigDecimal("504.77"),      // AMT            S9(09)V99 -> 0000005047G
                    800000000L,                    // MERCHANT-ID    9(09)
                    "Abshire-Lowe",                // MERCHANT-NAME  X(50)
                    "North Enoshaven",             // MERCHANT-CITY  X(50)
                    "72112",                       // MERCHANT-ZIP   X(10)
                    "4859452612877065",            // CARD-NUM       X(16)
                    "2022-06-10 19:27:53.000000",  // ORIG-TS        X(26)
                    "");                           // PROC-TS        X(26) -> spaces

            String image = DailyTransactionRecordImage.render(item);

            String expected =
                    "0000000000683580"
                    + "01"
                    + "0001"
                    + rpad("POS TERM", 10)
                    + rpad("Purchase at Abshire-Lowe", 100)
                    + "0000005047G"
                    + "800000000"
                    + rpad("Abshire-Lowe", 50)
                    + rpad("North Enoshaven", 50)
                    + rpad("72112", 10)
                    + "4859452612877065"
                    + "2022-06-10 19:27:53.000000"
                    + " ".repeat(26)
                    + " ".repeat(20);

            assertThat(image).hasSize(RECORD_WIDTH);
            assertThat(image).isEqualTo(expected);

            // Field-level offsets (defensive, pinpoints any drift).
            assertThat(image.substring(0, 16)).isEqualTo("0000000000683580");
            assertThat(image.substring(16, 18)).isEqualTo("01");
            assertThat(image.substring(18, 22)).isEqualTo("0001");
            assertThat(image.substring(22, 32)).isEqualTo("POS TERM  ");
            assertThat(image.substring(132, 143)).isEqualTo("0000005047G");
            assertThat(image.substring(143, 152)).isEqualTo("800000000");
            assertThat(image.substring(252, 262)).isEqualTo("72112     ");
            assertThat(image.substring(262, 278)).isEqualTo("4859452612877065");
            assertThat(image.substring(278, 304)).isEqualTo("2022-06-10 19:27:53.000000");
            assertThat(image.substring(304, 330)).isEqualTo(" ".repeat(26));
            assertThat(image.substring(330, 350)).isEqualTo(" ".repeat(20));
        }
    }

    @Nested
    @DisplayName("Signed zoned-decimal amount (PIC S9(09)V99 trailing overpunch)")
    class AmountEncoding {

        @Test
        @DisplayName("positive amounts encode the trailing-digit overpunch (fixture values)")
        void positiveOverpunch() {
            // Fixture: G=+7 (504.77), F=+6 (454.66), H=+8 (67.88), I=+9 (849.99), {=+0 (325.00).
            assertThat(amountField(DailyTransactionRecordImage.render(amountOnly(new BigDecimal("504.77")))))
                    .isEqualTo("0000005047G");
            assertThat(amountField(DailyTransactionRecordImage.render(amountOnly(new BigDecimal("454.66")))))
                    .isEqualTo("0000004546F");
            assertThat(amountField(DailyTransactionRecordImage.render(amountOnly(new BigDecimal("67.88")))))
                    .isEqualTo("0000000678H");
            assertThat(amountField(DailyTransactionRecordImage.render(amountOnly(new BigDecimal("849.99")))))
                    .isEqualTo("0000008499I");
            assertThat(amountField(DailyTransactionRecordImage.render(amountOnly(new BigDecimal("325.00")))))
                    .isEqualTo("0000003250{");
        }

        @Test
        @DisplayName("negative amounts encode the trailing-digit overpunch (fixture values)")
        void negativeOverpunch() {
            // Fixture: }=-0 (-919.00), J=-1 (-835.11), R=-9 (-70.99).
            assertThat(amountField(DailyTransactionRecordImage.render(amountOnly(new BigDecimal("-919.00")))))
                    .isEqualTo("0000009190}");
            assertThat(amountField(DailyTransactionRecordImage.render(amountOnly(new BigDecimal("-835.11")))))
                    .isEqualTo("0000008351J");
            assertThat(amountField(DailyTransactionRecordImage.render(amountOnly(new BigDecimal("-70.99")))))
                    .isEqualTo("0000000709R");
        }

        @Test
        @DisplayName("zero renders as positive zero overpunch '{'")
        void zeroIsPositive() {
            assertThat(amountField(DailyTransactionRecordImage.render(amountOnly(BigDecimal.ZERO))))
                    .isEqualTo("0000000000{");
        }

        @Test
        @DisplayName("null amount is treated as zero")
        void nullAmountIsZero() {
            assertThat(amountField(DailyTransactionRecordImage.render(amountOnly(null))))
                    .isEqualTo("0000000000{");
        }

        @Test
        @DisplayName("scale > 2 is rounded HALF_EVEN to two implied decimals")
        void roundsHalfEven() {
            // 1.005 -> HALF_EVEN -> 1.00 (unscaled 100 -> "00000000100" -> "0000000010{");
            // 1.015 -> HALF_EVEN -> 1.02 (unscaled 102 -> "00000000102" -> "0000000010B").
            assertThat(amountField(DailyTransactionRecordImage.render(amountOnly(new BigDecimal("1.005")))))
                    .isEqualTo("0000000010{");
            assertThat(amountField(DailyTransactionRecordImage.render(amountOnly(new BigDecimal("1.015")))))
                    .isEqualTo("0000000010B");
        }
    }

    @Nested
    @DisplayName("Field justification, padding, and null handling")
    class FieldRules {

        @Test
        @DisplayName("alphanumeric fields are left-justified, space-padded, and right-truncated")
        void alphanumericPaddingAndTruncation() {
            DailyTransaction item = new DailyTransaction();
            // 18 chars into a 16-char DALYTRAN-ID: keep the first 16 (right-truncate).
            item.setTranId("ABCDEFGHIJKLMNOPQR");
            item.setTranSource("X");
            String image = DailyTransactionRecordImage.render(item);
            assertThat(image.substring(0, 16)).isEqualTo("ABCDEFGHIJKLMNOP");
            assertThat(image.substring(22, 32)).isEqualTo("X         ");
        }

        @Test
        @DisplayName("null alphanumeric fields render as spaces")
        void nullAlphanumericIsSpaces() {
            String image = DailyTransactionRecordImage.render(new DailyTransaction());
            assertThat(image).hasSize(RECORD_WIDTH);
            assertThat(image.substring(0, 16)).isEqualTo(" ".repeat(16));
            assertThat(image.substring(262, 278)).isEqualTo(" ".repeat(16));
        }

        @Test
        @DisplayName("unsigned numeric fields are right-justified and zero-filled")
        void unsignedNumericZeroFill() {
            DailyTransaction item = new DailyTransaction();
            item.setTranCatCd(7);
            item.setMerchantId(42L);
            String image = DailyTransactionRecordImage.render(item);
            assertThat(image.substring(18, 22)).isEqualTo("0007");
            assertThat(image.substring(143, 152)).isEqualTo("000000042");
        }

        @Test
        @DisplayName("null unsigned numeric fields render as zeros")
        void nullNumericIsZero() {
            String image = DailyTransactionRecordImage.render(new DailyTransaction());
            assertThat(image.substring(18, 22)).isEqualTo("0000");
            assertThat(image.substring(143, 152)).isEqualTo("000000000");
        }
    }

    @Test
    @DisplayName("render rejects a null item")
    void renderRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> DailyTransactionRecordImage.render(null));
    }
}
