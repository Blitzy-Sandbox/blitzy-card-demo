/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.record;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.util.Decimals;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Domain record translated from the COBOL {@code DALYTRAN-RECORD} copybook at
 * {@code app/cpy/CVTRA06Y.cpy} (record length 350 bytes).
 *
 * <pre>{@code
 * 01 DALYTRAN-RECORD.
 *    05 DALYTRAN-ID              PIC X(16).
 *    05 DALYTRAN-TYPE-CD         PIC X(02).
 *    05 DALYTRAN-CAT-CD          PIC 9(04).
 *    05 DALYTRAN-SOURCE          PIC X(10).
 *    05 DALYTRAN-DESC            PIC X(100).
 *    05 DALYTRAN-AMT             PIC S9(09)V99.
 *    05 DALYTRAN-MERCHANT-ID     PIC 9(09).
 *    05 DALYTRAN-MERCHANT-NAME   PIC X(50).
 *    05 DALYTRAN-MERCHANT-CITY   PIC X(50).
 *    05 DALYTRAN-MERCHANT-ZIP    PIC X(10).
 *    05 DALYTRAN-CARD-NUM        PIC X(16).
 *    05 DALYTRAN-ORIG-TS         PIC X(26).
 *    05 DALYTRAN-PROC-TS         PIC X(26).
 *    05 FILLER                   PIC X(20).
 * }</pre>
 *
 * <p>The layout is byte-identical to {@link TranRecord} (CVTRA05Y); this
 * record is a separate type to make incoming daily transactions distinct
 * from already-posted transactions at compile time per AAP &sect;0.4.1.
 */
@CobolProgram(
        value = "CVTRA06Y",
        sourcePath = "app/cpy/CVTRA06Y.cpy",
        notes = "350-byte DALYTRAN-RECORD; byte-identical to TranRecord; PAN masked in toString()"
)
public record DalyTranRecord(
        String dalytranId,
        String dalytranTypeCd,
        int dalytranCatCd,
        String dalytranSource,
        String dalytranDesc,
        BigDecimal dalytranAmt,
        long dalytranMerchantId,
        String dalytranMerchantName,
        String dalytranMerchantCity,
        String dalytranMerchantZip,
        String dalytranCardNum,
        String dalytranOrigTs,
        String dalytranProcTs,
        byte[] filler) {

    public static final int RECORD_LENGTH = 350;
    public static final int LEN_FILLER = 20;
    public static final int MONETARY_SCALE = 2;
    public static final int LEN_DALYTRAN_AMT = 11;

    public DalyTranRecord {
        dalytranId = orEmpty(dalytranId);
        dalytranTypeCd = orEmpty(dalytranTypeCd);
        if (dalytranCatCd < 0 || dalytranCatCd > 9999) {
            throw new IllegalArgumentException("dalytranCatCd must be 0..9999");
        }
        dalytranSource = orEmpty(dalytranSource);
        dalytranDesc = orEmpty(dalytranDesc);
        Objects.requireNonNull(dalytranAmt, "dalytranAmt");
        dalytranAmt = Decimals.scaled(dalytranAmt, MONETARY_SCALE, Decimals.DEFAULT_MODE);
        if (dalytranMerchantId < 0L) {
            throw new IllegalArgumentException("dalytranMerchantId must be non-negative");
        }
        dalytranMerchantName = orEmpty(dalytranMerchantName);
        dalytranMerchantCity = orEmpty(dalytranMerchantCity);
        dalytranMerchantZip = orEmpty(dalytranMerchantZip);
        dalytranCardNum = orEmpty(dalytranCardNum);
        dalytranOrigTs = orEmpty(dalytranOrigTs);
        dalytranProcTs = orEmpty(dalytranProcTs);
        Objects.requireNonNull(filler, "filler");
        if (filler.length != LEN_FILLER) {
            throw new IllegalArgumentException(
                    "filler must be " + LEN_FILLER + " bytes, got " + filler.length);
        }
        filler = filler.clone();
    }

    @Override
    public byte[] filler() {
        return filler.clone();
    }

    /** PAN masked to last 4 digits. */
    public String maskedPan() {
        String trimmed = dalytranCardNum.trim();
        if (trimmed.length() <= 4) {
            return trimmed;
        }
        return "*".repeat(trimmed.length() - 4) + trimmed.substring(trimmed.length() - 4);
    }

    @Override
    public String toString() {
        return "DalyTranRecord[id=" + dalytranId
                + ", typeCd=" + dalytranTypeCd
                + ", catCd=" + dalytranCatCd
                + ", amt=" + dalytranAmt
                + ", merchantId=" + dalytranMerchantId
                + ", cardNum=" + maskedPan()
                + ", origTs=" + dalytranOrigTs
                + ", procTs=" + dalytranProcTs
                + ", filler=<" + LEN_FILLER + " bytes>]";
    }

    public static DalyTranRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "buffer must be " + RECORD_LENGTH + " bytes, got " + buffer.length);
        }
        int p = 0;
        String id = parseAscii(buffer, p, 16); p += 16;
        String typeCd = parseAscii(buffer, p, 2); p += 2;
        int catCd = (int) parseUnsignedLong(buffer, p, 4); p += 4;
        String source = parseAscii(buffer, p, 10); p += 10;
        String desc = parseAscii(buffer, p, 100); p += 100;
        BigDecimal amt = Decimals.parseZonedDecimal(buffer, p, LEN_DALYTRAN_AMT, MONETARY_SCALE); p += LEN_DALYTRAN_AMT;
        long merchantId = parseUnsignedLong(buffer, p, 9); p += 9;
        String merchantName = parseAscii(buffer, p, 50); p += 50;
        String merchantCity = parseAscii(buffer, p, 50); p += 50;
        String merchantZip = parseAscii(buffer, p, 10); p += 10;
        String cardNum = parseAscii(buffer, p, 16); p += 16;
        String origTs = parseAscii(buffer, p, 26); p += 26;
        String procTs = parseAscii(buffer, p, 26); p += 26;
        byte[] filler = Arrays.copyOfRange(buffer, p, p + LEN_FILLER);

        return new DalyTranRecord(id, typeCd, catCd, source, desc, amt, merchantId,
                merchantName, merchantCity, merchantZip, cardNum, origTs, procTs, filler);
    }

    public byte[] encode() {
        byte[] buffer = new byte[RECORD_LENGTH];
        Arrays.fill(buffer, (byte) ' ');
        int p = 0;
        writeAscii(buffer, p, 16, dalytranId); p += 16;
        writeAscii(buffer, p, 2, dalytranTypeCd); p += 2;
        writeUnsignedLong(buffer, p, 4, dalytranCatCd); p += 4;
        writeAscii(buffer, p, 10, dalytranSource); p += 10;
        writeAscii(buffer, p, 100, dalytranDesc); p += 100;
        byte[] amt = Decimals.encodeZonedDecimal(dalytranAmt, LEN_DALYTRAN_AMT, MONETARY_SCALE);
        System.arraycopy(amt, 0, buffer, p, LEN_DALYTRAN_AMT); p += LEN_DALYTRAN_AMT;
        writeUnsignedLong(buffer, p, 9, dalytranMerchantId); p += 9;
        writeAscii(buffer, p, 50, dalytranMerchantName); p += 50;
        writeAscii(buffer, p, 50, dalytranMerchantCity); p += 50;
        writeAscii(buffer, p, 10, dalytranMerchantZip); p += 10;
        writeAscii(buffer, p, 16, dalytranCardNum); p += 16;
        writeAscii(buffer, p, 26, dalytranOrigTs); p += 26;
        writeAscii(buffer, p, 26, dalytranProcTs); p += 26;
        System.arraycopy(filler, 0, buffer, p, LEN_FILLER);

        return buffer;
    }

    /**
     * Converts this daily transaction record to a permanent {@link TranRecord}.
     *
     * <p>The {@code DALYTRAN-ORIG-TS} and {@code DALYTRAN-PROC-TS} fields
     * (held here as {@link String} for legacy reasons) are converted to
     * {@link java.time.LocalDateTime} via {@link TranRecord#parseTimestamp(String)}
     * to satisfy the {@code TranRecord} schema (AAP &sect;0.6.4 mandates
     * {@code java.time.LocalDateTime} for {@code PIC X(26)} timestamps).
     * Blank or null timestamps map to {@code null} (the COBOL all-spaces
     * sentinel for an unset timestamp).
     *
     * @return a {@link TranRecord} with the same business values
     */
    public TranRecord toTranRecord() {
        return new TranRecord(
                dalytranId, dalytranTypeCd, dalytranCatCd, dalytranSource, dalytranDesc,
                dalytranAmt, dalytranMerchantId, dalytranMerchantName, dalytranMerchantCity,
                dalytranMerchantZip, dalytranCardNum,
                TranRecord.parseTimestamp(dalytranOrigTs),
                TranRecord.parseTimestamp(dalytranProcTs),
                filler);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static long parseUnsignedLong(byte[] buffer, int offset, int length) {
        long value = 0L;
        for (int i = 0; i < length; i++) {
            int c = buffer[offset + i] & 0xFF;
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(String.format(
                        "Non-digit at byte %d: 0x%02X", offset + i, c));
            }
            value = value * 10L + (c - '0');
        }
        return value;
    }

    private static void writeUnsignedLong(byte[] buffer, int offset, int length, long value) {
        long remaining = value;
        for (int i = length - 1; i >= 0; i--) {
            buffer[offset + i] = (byte) ('0' + (remaining % 10));
            remaining /= 10;
        }
        if (remaining != 0L) {
            throw new IllegalArgumentException("Value " + value + " exceeds field width");
        }
    }

    private static String parseAscii(byte[] buffer, int offset, int length) {
        return new String(buffer, offset, length, StandardCharsets.US_ASCII);
    }

    private static void writeAscii(byte[] buffer, int offset, int length, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.US_ASCII);
        int copyLen = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, buffer, offset, copyLen);
        for (int i = copyLen; i < length; i++) {
            buffer[offset + i] = (byte) ' ';
        }
    }

    /** Returns an empty FILLER byte array. */
    public static byte[] emptyFiller() {
        byte[] filler = new byte[LEN_FILLER];
        Arrays.fill(filler, (byte) ' ');
        return filler;
    }
}
