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
package com.carddemo.batch.processor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Objects;

import com.carddemo.entity.DailyTransaction;

/**
 * Reconstructs the exact 350-byte {@code CVTRA06Y DALYTRAN-RECORD} image from a
 * staged {@link DailyTransaction} entity, reproducing the byte layout the COBOL
 * posting engine {@code CBTRN02C} moves into {@code REJECT-TRAN-DATA} in
 * paragraph {@code 2500-WRITE-REJECT-REC} at source commit {@code 27d6c6f}.
 *
 * <p>The staging table stores the daily-transaction feed as parsed columns, so
 * the original fixed-width record image is not retained; it must be
 * reconstructed to write a byte-equivalent {@code DALYREJS} reject record. This
 * renderer is the exact inverse of the fixed-width parse: it places every field
 * at its copybook offset and width, so for clean fixture data the reconstructed
 * image is identical to the original input record (Validation Gates 1 and 4).</p>
 *
 * <p>The {@code CVTRA06Y} layout (RECLN 350) is, in order:</p>
 * <pre>
 * DALYTRAN-ID            PIC X(16)      offset   0
 * DALYTRAN-TYPE-CD       PIC X(02)      offset  16
 * DALYTRAN-CAT-CD        PIC 9(04)      offset  18
 * DALYTRAN-SOURCE        PIC X(10)      offset  22
 * DALYTRAN-DESC          PIC X(100)     offset  32
 * DALYTRAN-AMT           PIC S9(09)V99  offset 132   (11 bytes, trailing zoned sign)
 * DALYTRAN-MERCHANT-ID   PIC 9(09)      offset 143
 * DALYTRAN-MERCHANT-NAME PIC X(50)      offset 152
 * DALYTRAN-MERCHANT-CITY PIC X(50)      offset 202
 * DALYTRAN-MERCHANT-ZIP  PIC X(10)      offset 252
 * DALYTRAN-CARD-NUM      PIC X(16)      offset 262
 * DALYTRAN-ORIG-TS       PIC X(26)      offset 278
 * DALYTRAN-PROC-TS       PIC X(26)      offset 304
 * FILLER                 PIC X(20)      offset 330
 * </pre>
 *
 * <p><strong>Alphanumeric fields ({@code PIC X})</strong> are left-justified and
 * right space-padded (and right-truncated if over width), matching a COBOL
 * alphanumeric {@code MOVE}. <strong>Unsigned numeric fields ({@code PIC 9})</strong>
 * are right-justified zero-filled. The <strong>signed amount</strong>
 * ({@code PIC S9(09)V99} {@code USAGE DISPLAY}) is rendered as eleven digits (nine
 * integer + two implied-decimal) with the sign encoded as a trailing-byte zoned
 * overpunch on the final digit: positive {@code 0-9} render as
 * {@code {ABCDEFGHI} and negative {@code 0-9} render as {@code }JKLMNOPQR},
 * exactly as observed in {@code app/data/ASCII/dailytran.txt} (for example
 * {@code 504.77} renders {@code 0000005047G} and {@code -919.00} renders
 * {@code 0000009190}}).</p>
 *
 * <p>This is a stateless utility; it is not instantiable.</p>
 */
public final class DailyTransactionRecordImage {

    /** {@code DALYTRAN-ID PIC X(16)}. */
    private static final int ID_WIDTH = 16;

    /** {@code DALYTRAN-TYPE-CD PIC X(02)}. */
    private static final int TYPE_WIDTH = 2;

    /** {@code DALYTRAN-CAT-CD PIC 9(04)}. */
    private static final int CAT_WIDTH = 4;

    /** {@code DALYTRAN-SOURCE PIC X(10)}. */
    private static final int SOURCE_WIDTH = 10;

    /** {@code DALYTRAN-DESC PIC X(100)}. */
    private static final int DESC_WIDTH = 100;

    /** {@code DALYTRAN-AMT PIC S9(09)V99} occupies eleven character positions. */
    private static final int AMT_WIDTH = 11;

    /** Implied decimal scale of {@code DALYTRAN-AMT} ({@code V99}). */
    private static final int AMT_SCALE = 2;

    /** {@code DALYTRAN-MERCHANT-ID PIC 9(09)}. */
    private static final int MERCHANT_ID_WIDTH = 9;

    /** {@code DALYTRAN-MERCHANT-NAME PIC X(50)}. */
    private static final int MERCHANT_NAME_WIDTH = 50;

    /** {@code DALYTRAN-MERCHANT-CITY PIC X(50)}. */
    private static final int MERCHANT_CITY_WIDTH = 50;

    /** {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}. */
    private static final int MERCHANT_ZIP_WIDTH = 10;

    /** {@code DALYTRAN-CARD-NUM PIC X(16)}. */
    private static final int CARD_WIDTH = 16;

    /** {@code DALYTRAN-ORIG-TS PIC X(26)}. */
    private static final int ORIG_TS_WIDTH = 26;

    /** {@code DALYTRAN-PROC-TS PIC X(26)}. */
    private static final int PROC_TS_WIDTH = 26;

    /** Trailing {@code FILLER PIC X(20)}. */
    private static final int FILLER_WIDTH = 20;

    /** Total record length ({@code CVTRA06Y} RECLN). */
    private static final int RECORD_WIDTH = 350;

    /** Zoned-decimal overpunch characters for a positive trailing digit {@code 0-9}. */
    private static final char[] POSITIVE_OVERPUNCH =
            {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

    /** Zoned-decimal overpunch characters for a negative trailing digit {@code 0-9}. */
    private static final char[] NEGATIVE_OVERPUNCH =
            {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

    private DailyTransactionRecordImage() {
        // Utility class: not instantiable.
    }

    /**
     * Renders the staged daily transaction as its exact 350-byte
     * {@code CVTRA06Y} record image.
     *
     * @param item the staged daily transaction to render; must not be
     *             {@code null}
     * @return a string of exactly {@value #RECORD_WIDTH} characters
     * @throws NullPointerException  if {@code item} is {@code null}
     * @throws IllegalStateException if the assembled image is not exactly
     *                               {@value #RECORD_WIDTH} characters (a coding
     *                               error in the field widths)
     */
    public static String render(DailyTransaction item) {
        Objects.requireNonNull(item, "item");

        StringBuilder image = new StringBuilder(RECORD_WIDTH);
        image.append(alpha(item.getTranId(), ID_WIDTH));
        image.append(alpha(item.getTranTypeCd(), TYPE_WIDTH));
        image.append(unsignedNumeric(toLong(item.getTranCatCd()), CAT_WIDTH));
        image.append(alpha(item.getTranSource(), SOURCE_WIDTH));
        image.append(alpha(item.getTranDesc(), DESC_WIDTH));
        image.append(signedZonedAmount(item.getTranAmt()));
        image.append(unsignedNumeric(toLong(item.getMerchantId()), MERCHANT_ID_WIDTH));
        image.append(alpha(item.getMerchantName(), MERCHANT_NAME_WIDTH));
        image.append(alpha(item.getMerchantCity(), MERCHANT_CITY_WIDTH));
        image.append(alpha(item.getMerchantZip(), MERCHANT_ZIP_WIDTH));
        image.append(alpha(item.getCardNum(), CARD_WIDTH));
        image.append(alpha(item.getOrigTs(), ORIG_TS_WIDTH));
        image.append(alpha(item.getProcTs(), PROC_TS_WIDTH));
        image.append(" ".repeat(FILLER_WIDTH));

        String rendered = image.toString();
        if (rendered.length() != RECORD_WIDTH) {
            throw new IllegalStateException(
                    "DALYTRAN-RECORD image width violation: expected " + RECORD_WIDTH
                            + " but was " + rendered.length());
        }
        return rendered;
    }

    /**
     * Renders an alphanumeric ({@code PIC X}) field: left-justified, right
     * space-padded, and right-truncated when over width. A {@code null} value is
     * treated as an empty field.
     *
     * @param value the source value (may be {@code null})
     * @param width the exact target width
     * @return a string of exactly {@code width} characters
     */
    private static String alpha(String value, int width) {
        String safe = (value == null) ? "" : value;
        if (safe.length() >= width) {
            return safe.substring(0, width);
        }
        StringBuilder field = new StringBuilder(width);
        field.append(safe);
        while (field.length() < width) {
            field.append(' ');
        }
        return field.toString();
    }

    /**
     * Renders an unsigned numeric ({@code PIC 9}) field: right-justified and
     * zero-filled. A value with more digits than {@code width} keeps its
     * low-order digits, matching a COBOL numeric {@code MOVE} truncation.
     *
     * @param value the non-negative value to render
     * @param width the exact target width
     * @return a string of exactly {@code width} digit characters
     */
    private static String unsignedNumeric(long value, int width) {
        String digits = Long.toString(Math.abs(value));
        if (digits.length() >= width) {
            return digits.substring(digits.length() - width);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Renders the signed amount as the eleven-character {@code PIC S9(09)V99}
     * {@code USAGE DISPLAY} zoned-decimal field: nine integer digits and two
     * implied-decimal digits, with the sign encoded as a trailing-byte overpunch
     * on the final digit.
     *
     * @param value the monetary value (may be {@code null}, treated as zero)
     * @return a string of exactly {@value #AMT_WIDTH} characters
     */
    private static String signedZonedAmount(BigDecimal value) {
        BigDecimal scaled = (value == null ? BigDecimal.ZERO : value).setScale(AMT_SCALE, RoundingMode.HALF_EVEN);
        boolean negative = scaled.signum() < 0;
        BigInteger cents = scaled.abs().unscaledValue();

        String digits = cents.toString();
        if (digits.length() > AMT_WIDTH) {
            digits = digits.substring(digits.length() - AMT_WIDTH);
        } else if (digits.length() < AMT_WIDTH) {
            digits = "0".repeat(AMT_WIDTH - digits.length()) + digits;
        }

        int lastDigit = digits.charAt(AMT_WIDTH - 1) - '0';
        char overpunch = (negative ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH)[lastDigit];
        return digits.substring(0, AMT_WIDTH - 1) + overpunch;
    }

    /**
     * Converts a possibly-{@code null} {@link Number} to a {@code long}, treating
     * {@code null} as zero.
     *
     * @param value the value to convert (may be {@code null})
     * @return the {@code long} value, or zero when {@code value} is {@code null}
     */
    private static long toLong(Number value) {
        return (value == null) ? 0L : value.longValue();
    }
}
