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
 * Domain record translated from the COBOL {@code DIS-GROUP-RECORD} copybook
 * at {@code app/cpy/CVTRA02Y.cpy} (record length 50 bytes).
 *
 * <pre>{@code
 * 01 DIS-GROUP-RECORD.
 *    05 DIS-GROUP-KEY.
 *       10 DIS-ACCT-GROUP-ID       PIC X(10).
 *       10 DIS-TRAN-TYPE-CD        PIC X(02).
 *       10 DIS-TRAN-CAT-CD         PIC 9(04).
 *    05 DIS-INT-RATE               PIC S9(04)V99.
 *    05 FILLER                     PIC X(28).
 * }</pre>
 *
 * <p>The composite key (16 bytes: 10 + 2 + 4) identifies an interest-rate
 * tier; the rate field is a 6-digit zoned decimal scaled to 2.
 */
@CobolProgram(
        value = "CVTRA02Y",
        sourcePath = "app/cpy/CVTRA02Y.cpy",
        notes = "50-byte DIS-GROUP-RECORD; 16-byte composite key + S9(04)V99 rate + 28-byte FILLER"
)
public record DisGroupRecord(
        DisGroupKey key,
        BigDecimal disIntRate,
        byte[] filler) {

    public static final int RECORD_LENGTH = 50;
    public static final int LEN_KEY = 16;
    public static final int LEN_DIS_INT_RATE = 6;
    public static final int LEN_FILLER = 28;
    public static final int MONETARY_SCALE = 2;

    /**
     * Composite key DIS-GROUP-KEY (16 bytes total).
     */
    public record DisGroupKey(String acctGroupId, String tranTypeCd, int tranCatCd) {

        public static final int LEN_ACCT_GROUP_ID = 10;
        public static final int LEN_TRAN_TYPE_CD = 2;
        public static final int LEN_TRAN_CAT_CD = 4;

        public DisGroupKey {
            acctGroupId = acctGroupId == null ? "" : acctGroupId;
            tranTypeCd = tranTypeCd == null ? "" : tranTypeCd;
            if (tranCatCd < 0 || tranCatCd > 9999) {
                throw new IllegalArgumentException("tranCatCd must be 0..9999");
            }
        }

        public byte[] encode() {
            byte[] buffer = new byte[LEN_ACCT_GROUP_ID + LEN_TRAN_TYPE_CD + LEN_TRAN_CAT_CD];
            Arrays.fill(buffer, (byte) ' ');
            writeAscii(buffer, 0, LEN_ACCT_GROUP_ID, acctGroupId);
            writeAscii(buffer, LEN_ACCT_GROUP_ID, LEN_TRAN_TYPE_CD, tranTypeCd);
            writeUnsignedLong(buffer, LEN_ACCT_GROUP_ID + LEN_TRAN_TYPE_CD, LEN_TRAN_CAT_CD, tranCatCd);
            return buffer;
        }
    }

    public DisGroupRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(disIntRate, "disIntRate");
        disIntRate = Decimals.scaled(disIntRate, MONETARY_SCALE, Decimals.DEFAULT_MODE);
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

    public static DisGroupRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "buffer must be " + RECORD_LENGTH + " bytes, got " + buffer.length);
        }
        int p = 0;
        String acctGroupId = parseAscii(buffer, p, DisGroupKey.LEN_ACCT_GROUP_ID); p += DisGroupKey.LEN_ACCT_GROUP_ID;
        String tranTypeCd = parseAscii(buffer, p, DisGroupKey.LEN_TRAN_TYPE_CD); p += DisGroupKey.LEN_TRAN_TYPE_CD;
        int tranCatCd = (int) parseUnsignedLong(buffer, p, DisGroupKey.LEN_TRAN_CAT_CD); p += DisGroupKey.LEN_TRAN_CAT_CD;
        BigDecimal rate = Decimals.parseZonedDecimal(buffer, p, LEN_DIS_INT_RATE, MONETARY_SCALE); p += LEN_DIS_INT_RATE;
        byte[] filler = Arrays.copyOfRange(buffer, p, p + LEN_FILLER);

        return new DisGroupRecord(new DisGroupKey(acctGroupId, tranTypeCd, tranCatCd), rate, filler);
    }

    public byte[] encode() {
        byte[] buffer = new byte[RECORD_LENGTH];
        Arrays.fill(buffer, (byte) ' ');
        int p = 0;
        writeAscii(buffer, p, DisGroupKey.LEN_ACCT_GROUP_ID, key.acctGroupId); p += DisGroupKey.LEN_ACCT_GROUP_ID;
        writeAscii(buffer, p, DisGroupKey.LEN_TRAN_TYPE_CD, key.tranTypeCd); p += DisGroupKey.LEN_TRAN_TYPE_CD;
        writeUnsignedLong(buffer, p, DisGroupKey.LEN_TRAN_CAT_CD, key.tranCatCd); p += DisGroupKey.LEN_TRAN_CAT_CD;
        byte[] rate = Decimals.encodeZonedDecimal(disIntRate, LEN_DIS_INT_RATE, MONETARY_SCALE);
        System.arraycopy(rate, 0, buffer, p, LEN_DIS_INT_RATE); p += LEN_DIS_INT_RATE;
        System.arraycopy(filler, 0, buffer, p, LEN_FILLER);

        return buffer;
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

    public static byte[] emptyFiller() {
        byte[] filler = new byte[LEN_FILLER];
        Arrays.fill(filler, (byte) ' ');
        return filler;
    }
}
