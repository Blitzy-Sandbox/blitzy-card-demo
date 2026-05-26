/*
 * Copyright 2024 AWS CardDemo Migration Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.awsm2.carddemo.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Validates the {@link CobolCodec} primitives against the canonical golden
 * fixtures used by {@code GoldenOutputDiffTest}. The fixture-derived
 * test vectors guarantee byte-identical parity for every PIC clause used in
 * the seven CardDemo copybooks.
 *
 * <p>Code Review CP7 FINAL — CRITICAL: these tests sit upstream of the
 * 9-fixture round-trip suite and are the primitive-level safety net.</p>
 */
class CobolCodecTest {

    @Nested
    @DisplayName("PIC X(n) — alphanumeric fields")
    class TextFieldsTest {

        @Test
        @DisplayName("parseText preserves trailing spaces verbatim")
        void parseText_keepsTrailingSpaces() {
            byte[] src = "Aniya Von                                         ".getBytes(StandardCharsets.US_ASCII);
            String text = CobolCodec.parseText(src, 0, 50);
            assertEquals(50, text.length());
            assertEquals("Aniya Von", text.stripTrailing());
        }

        @Test
        @DisplayName("formatText pads right with SPACE")
        void formatText_padsWithSpaces() {
            byte[] out = CobolCodec.formatText("Aniya Von", 50);
            assertEquals(50, out.length);
            assertEquals("Aniya Von                                         ", new String(out, StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("formatText null becomes 10 spaces for X(10)")
        void formatText_nullPadsToAllSpaces() {
            byte[] out = CobolCodec.formatText(null, 10);
            assertArrayEquals("          ".getBytes(StandardCharsets.US_ASCII), out);
        }

        @Test
        @DisplayName("parseText + formatText round-trip is byte-identical")
        void roundTrip_text() {
            byte[] src = "Purchase at Abshire-Lowe                                                                            ".getBytes(StandardCharsets.US_ASCII);
            assertEquals(100, src.length);
            String parsed = CobolCodec.parseText(src, 0, 100);
            byte[] formatted = CobolCodec.formatText(parsed, 100);
            assertArrayEquals(src, formatted);
        }
    }

    @Nested
    @DisplayName("PIC 9(n) — unsigned numeric fields")
    class NumericFieldsTest {

        @Test
        @DisplayName("parseLong reads ZERO-padded 11-digit account ID")
        void parseLong_decodesAccountId() {
            byte[] src = "00000000050".getBytes(StandardCharsets.US_ASCII);
            assertEquals(50L, CobolCodec.parseLong(src, 0, 11));
        }

        @Test
        @DisplayName("formatLong zero-pads to required width")
        void formatLong_padsToWidth() {
            byte[] out = CobolCodec.formatLong(50L, 11);
            assertArrayEquals("00000000050".getBytes(StandardCharsets.US_ASCII), out);
        }

        @Test
        @DisplayName("parseLong/formatLong round-trip is byte-identical")
        void roundTrip_long() {
            byte[] src = "00000000001".getBytes(StandardCharsets.US_ASCII);
            long v = CobolCodec.parseLong(src, 0, 11);
            byte[] out = CobolCodec.formatLong(v, 11);
            assertArrayEquals(src, out);
        }

        @Test
        @DisplayName("formatLong rejects negative numbers for PIC 9(n)")
        void formatLong_rejectsNegative() {
            assertThrows(IllegalArgumentException.class, () -> CobolCodec.formatLong(-1L, 9));
        }
    }

    @Nested
    @DisplayName("PIC S9(m)V9(s) — zoned-decimal fields")
    class ZonedDecimalTest {

        @Test
        @DisplayName("Decode +194.00 from '00000001940{' (S9(10)V99)")
        void parse_positiveOverpunchZero() {
            byte[] src = "00000001940{".getBytes(StandardCharsets.US_ASCII);
            BigDecimal v = CobolCodec.parseZonedDecimal(src, 0, 12, 2);
            assertEquals(new BigDecimal("194.00"), v);
        }

        @Test
        @DisplayName("Decode +504.77 from '0000005047G' (S9(09)V99 — last-byte sign overpunch encodes both sign and units digit)")
        void parse_positiveOverpunchSeven() {
            // S9(09)V99 = 11 bytes, V99 = last 2 digits decimal.
            // Sign overpunch 'G' = positive 7. Digits become "00000050477" → 504.77.
            byte[] src = "0000005047G".getBytes(StandardCharsets.US_ASCII);
            BigDecimal v = CobolCodec.parseZonedDecimal(src, 0, 11, 2);
            assertEquals(new BigDecimal("504.77"), v);
        }

        @Test
        @DisplayName("Decode +15.00 from '00150{' (S9(04)V99 — DIS-INT-RATE in discgrp.txt)")
        void parse_smallPositive() {
            // S9(04)V99 = 6 bytes, V99 = last 2 digits decimal.
            // Sign overpunch '{' = positive 0. Digits become "001500" → 15.00.
            byte[] src = "00150{".getBytes(StandardCharsets.US_ASCII);
            BigDecimal v = CobolCodec.parseZonedDecimal(src, 0, 6, 2);
            assertEquals(new BigDecimal("15.00"), v);
        }

        @Test
        @DisplayName("Encode +194.00 → '00000001940{' (S9(10)V99)")
        void format_positiveOverpunchZero() {
            byte[] out = CobolCodec.formatZonedDecimal(new BigDecimal("194.00"), 12, 2);
            assertArrayEquals("00000001940{".getBytes(StandardCharsets.US_ASCII), out);
        }

        @Test
        @DisplayName("Encode +504.77 → '0000005047G' (S9(09)V99)")
        void format_positiveOverpunchSeven() {
            byte[] out = CobolCodec.formatZonedDecimal(new BigDecimal("504.77"), 11, 2);
            assertArrayEquals("0000005047G".getBytes(StandardCharsets.US_ASCII), out);
        }

        @Test
        @DisplayName("Encode -1.50 → '0015}' wait — confirm negative encoding")
        void format_negative() {
            // -1.50 unscaled = -150; abs digits = "150"; pad to 6 = "000150"; last digit 0 → }
            byte[] out = CobolCodec.formatZonedDecimal(new BigDecimal("-1.50"), 6, 2);
            assertArrayEquals("00015}".getBytes(StandardCharsets.US_ASCII), out);
        }

        @Test
        @DisplayName("Encode -1.55 → '00015N' (5 → N for negative)")
        void format_negativeNonZeroLastDigit() {
            byte[] out = CobolCodec.formatZonedDecimal(new BigDecimal("-1.55"), 6, 2);
            assertArrayEquals("00015N".getBytes(StandardCharsets.US_ASCII), out);
        }

        @Test
        @DisplayName("Decode '0000000000{' (S9(09)V99) = +0.00")
        void parse_zero() {
            byte[] src = "0000000000{".getBytes(StandardCharsets.US_ASCII);
            BigDecimal v = CobolCodec.parseZonedDecimal(src, 0, 11, 2);
            assertEquals(0, v.compareTo(BigDecimal.ZERO));
            assertEquals(2, v.scale());
        }

        @Test
        @DisplayName("Encode/decode round-trip preserves bytes verbatim")
        void roundTrip_zonedDecimal() {
            String[] fixtures = {
                    "00000001940{",  // +194.00 (S9(10)V99)
                    "00000020200{",  // +2020.00
                    "00000010200{",  // +1020.00
                    "0000005047G",   // +50.47 (S9(09)V99)
                    "00150{",        // +1.50 (S9(04)V99)
                    "0000000000{"    // +0.00 (S9(09)V99)
            };
            int[] scales = {2, 2, 2, 2, 2, 2};
            for (int i = 0; i < fixtures.length; i++) {
                byte[] src = fixtures[i].getBytes(StandardCharsets.US_ASCII);
                BigDecimal v = CobolCodec.parseZonedDecimal(src, 0, src.length, scales[i]);
                byte[] out = CobolCodec.formatZonedDecimal(v, src.length, scales[i]);
                assertArrayEquals(src, out, "Round-trip failed for: " + fixtures[i]);
            }
        }
    }

    @Nested
    @DisplayName("PIC X(10) — ISO-8601 date fields")
    class IsoDateTest {

        @Test
        @DisplayName("parseLocalDate decodes 2014-11-20")
        void parse_date() {
            byte[] src = "2014-11-20".getBytes(StandardCharsets.US_ASCII);
            LocalDate d = CobolCodec.parseLocalDate(src, 0, 10);
            assertNotNull(d);
            assertEquals(LocalDate.of(2014, 11, 20), d);
        }

        @Test
        @DisplayName("parseLocalDate returns null for 10 SPACES")
        void parse_emptyDate() {
            byte[] src = "          ".getBytes(StandardCharsets.US_ASCII);
            assertNull(CobolCodec.parseLocalDate(src, 0, 10));
        }

        @Test
        @DisplayName("formatLocalDate encodes 2014-11-20")
        void format_date() {
            byte[] out = CobolCodec.formatLocalDate(LocalDate.of(2014, 11, 20));
            assertArrayEquals("2014-11-20".getBytes(StandardCharsets.US_ASCII), out);
        }

        @Test
        @DisplayName("formatLocalDate null → 10 SPACES")
        void format_nullDate() {
            byte[] out = CobolCodec.formatLocalDate(null);
            assertArrayEquals("          ".getBytes(StandardCharsets.US_ASCII), out);
        }
    }

    @Nested
    @DisplayName("Buffer assembly helpers")
    class BufferHelpersTest {

        @Test
        @DisplayName("put copies the source into the destination at the offset")
        void put_copiesAtOffset() {
            byte[] dst = new byte[10];
            CobolCodec.put(dst, 3, "ABC".getBytes(StandardCharsets.US_ASCII));
            assertEquals(0, dst[0]);
            assertEquals(0, dst[1]);
            assertEquals(0, dst[2]);
            assertEquals('A', dst[3]);
            assertEquals('B', dst[4]);
            assertEquals('C', dst[5]);
            assertEquals(0, dst[6]);
        }

        @Test
        @DisplayName("fillSpaces fills a range with ASCII 0x20")
        void fillSpaces_fillsRange() {
            byte[] dst = new byte[5];
            CobolCodec.fillSpaces(dst, 0, 5);
            assertArrayEquals("     ".getBytes(StandardCharsets.US_ASCII), dst);
        }

        @Test
        @DisplayName("fillZeros fills a range with ASCII 0x30")
        void fillZeros_fillsRange() {
            byte[] dst = new byte[5];
            CobolCodec.fillZeros(dst, 0, 5);
            assertArrayEquals("00000".getBytes(StandardCharsets.US_ASCII), dst);
        }
    }
}
