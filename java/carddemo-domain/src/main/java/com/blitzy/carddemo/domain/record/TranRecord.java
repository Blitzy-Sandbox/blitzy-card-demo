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
 * Domain record translated from the COBOL {@code TRAN-RECORD} copybook at
 * {@code app/cpy/CVTRA05Y.cpy} (record length 350 bytes).
 *
 * <pre>{@code
 * 01 TRAN-RECORD.
 *    05 TRAN-ID              PIC X(16).
 *    05 TRAN-TYPE-CD         PIC X(02).
 *    05 TRAN-CAT-CD          PIC 9(04).
 *    05 TRAN-SOURCE          PIC X(10).
 *    05 TRAN-DESC            PIC X(100).
 *    05 TRAN-AMT             PIC S9(09)V99.
 *    05 TRAN-MERCHANT-ID     PIC 9(09).
 *    05 TRAN-MERCHANT-NAME   PIC X(50).
 *    05 TRAN-MERCHANT-CITY   PIC X(50).
 *    05 TRAN-MERCHANT-ZIP    PIC X(10).
 *    05 TRAN-CARD-NUM        PIC X(16).
 *    05 TRAN-ORIG-TS         PIC X(26).
 *    05 TRAN-PROC-TS         PIC X(26).
 *    05 FILLER               PIC X(20).
 * }</pre>
 *
 * <p>Per AAP &sect;0.6.4 the two timestamp fields use {@code PIC X(26)}
 * format (e.g., {@code "2023-01-15 12:34:56.123456"}); they are carried as
 * {@link String} components and translated to {@code java.time.LocalDateTime}
 * by the consuming application code where required. The TRAN-AMT is decoded
 * via {@link Decimals#parseZonedDecimal} with scale 2.
 *
 * <p>The PAN ({@code TRAN-CARD-NUM}) is masked in {@link #toString()} per
 * AAP &sect;0.7.2.
 */
@CobolProgram(
        value = "CVTRA05Y",
        sourcePath = "app/cpy/CVTRA05Y.cpy",
        notes = "350-byte TRAN-RECORD; PAN masked in toString() per AAP §0.7.2"
)
public record TranRecord(
        String tranId,
        String tranTypeCd,
        int tranCatCd,
        String tranSource,
        String tranDesc,
        BigDecimal tranAmt,
        long tranMerchantId,
        String tranMerchantName,
        String tranMerchantCity,
        String tranMerchantZip,
        String tranCardNum,
        String tranOrigTs,
        String tranProcTs,
        byte[] filler) {

    public static final int RECORD_LENGTH = 350;
    public static final int LEN_FILLER = 20;
    public static final int MONETARY_SCALE = 2;
    public static final int LEN_TRAN_AMT = 11;  // S9(09)V99 = 9+2 = 11 zoned digits

    public TranRecord {
        tranId = orEmpty(tranId);
        tranTypeCd = orEmpty(tranTypeCd);
        if (tranCatCd < 0 || tranCatCd > 9999) {
            throw new IllegalArgumentException("tranCatCd must be 0..9999, got " + tranCatCd);
        }
        tranSource = orEmpty(tranSource);
        tranDesc = orEmpty(tranDesc);
        Objects.requireNonNull(tranAmt, "tranAmt");
        tranAmt = Decimals.scaled(tranAmt, MONETARY_SCALE, Decimals.DEFAULT_MODE);
        if (tranMerchantId < 0L) {
            throw new IllegalArgumentException("tranMerchantId must be non-negative");
        }
        tranMerchantName = orEmpty(tranMerchantName);
        tranMerchantCity = orEmpty(tranMerchantCity);
        tranMerchantZip = orEmpty(tranMerchantZip);
        tranCardNum = orEmpty(tranCardNum);
        tranOrigTs = orEmpty(tranOrigTs);
        tranProcTs = orEmpty(tranProcTs);
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
        String trimmed = tranCardNum.trim();
        if (trimmed.length() <= 4) {
            return trimmed;
        }
        return "*".repeat(trimmed.length() - 4) + trimmed.substring(trimmed.length() - 4);
    }

    @Override
    public String toString() {
        return "TranRecord[tranId=" + tranId
                + ", tranTypeCd=" + tranTypeCd
                + ", tranCatCd=" + tranCatCd
                + ", tranAmt=" + tranAmt
                + ", merchantId=" + tranMerchantId
                + ", cardNum=" + maskedPan()
                + ", origTs=" + tranOrigTs
                + ", procTs=" + tranProcTs
                + ", filler=<" + LEN_FILLER + " bytes>]";
    }

    public static TranRecord parse(byte[] buffer) {
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
        BigDecimal amt = Decimals.parseZonedDecimal(buffer, p, LEN_TRAN_AMT, MONETARY_SCALE); p += LEN_TRAN_AMT;
        long merchantId = parseUnsignedLong(buffer, p, 9); p += 9;
        String merchantName = parseAscii(buffer, p, 50); p += 50;
        String merchantCity = parseAscii(buffer, p, 50); p += 50;
        String merchantZip = parseAscii(buffer, p, 10); p += 10;
        String cardNum = parseAscii(buffer, p, 16); p += 16;
        String origTs = parseAscii(buffer, p, 26); p += 26;
        String procTs = parseAscii(buffer, p, 26); p += 26;
        byte[] filler = Arrays.copyOfRange(buffer, p, p + LEN_FILLER);

        return new TranRecord(id, typeCd, catCd, source, desc, amt, merchantId,
                merchantName, merchantCity, merchantZip, cardNum, origTs, procTs, filler);
    }

    public byte[] encode() {
        byte[] buffer = new byte[RECORD_LENGTH];
        Arrays.fill(buffer, (byte) ' ');
        int p = 0;
        writeAscii(buffer, p, 16, tranId); p += 16;
        writeAscii(buffer, p, 2, tranTypeCd); p += 2;
        writeUnsignedLong(buffer, p, 4, tranCatCd); p += 4;
        writeAscii(buffer, p, 10, tranSource); p += 10;
        writeAscii(buffer, p, 100, tranDesc); p += 100;
        byte[] amt = Decimals.encodeZonedDecimal(tranAmt, LEN_TRAN_AMT, MONETARY_SCALE);
        System.arraycopy(amt, 0, buffer, p, LEN_TRAN_AMT); p += LEN_TRAN_AMT;
        writeUnsignedLong(buffer, p, 9, tranMerchantId); p += 9;
        writeAscii(buffer, p, 50, tranMerchantName); p += 50;
        writeAscii(buffer, p, 50, tranMerchantCity); p += 50;
        writeAscii(buffer, p, 10, tranMerchantZip); p += 10;
        writeAscii(buffer, p, 16, tranCardNum); p += 16;
        writeAscii(buffer, p, 26, tranOrigTs); p += 26;
        writeAscii(buffer, p, 26, tranProcTs); p += 26;
        System.arraycopy(filler, 0, buffer, p, LEN_FILLER);

        return buffer;
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
