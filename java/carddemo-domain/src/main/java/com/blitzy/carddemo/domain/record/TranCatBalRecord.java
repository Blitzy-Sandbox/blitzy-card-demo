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
 * Domain record translated from the COBOL {@code TRAN-CAT-BAL-RECORD} copybook
 * at {@code app/cpy/CVTRA01Y.cpy} (record length 50 bytes).
 *
 * <pre>{@code
 * 01 TRAN-CAT-BAL-RECORD.
 *    05 TRAN-CAT-KEY.
 *       10 TRANCAT-ACCT-ID         PIC 9(11).
 *       10 TRANCAT-TYPE-CD         PIC X(02).
 *       10 TRANCAT-CD              PIC 9(04).
 *    05 TRAN-CAT-BAL               PIC S9(09)V99.
 *    05 FILLER                     PIC X(22).
 * }</pre>
 *
 * <p>The composite key (17 bytes: 11 + 2 + 4) uniquely identifies a balance
 * tracked per account / transaction-type / category combination, used to
 * accumulate per-category postings between statement cycles.
 */
@CobolProgram(
        value = "CVTRA01Y",
        sourcePath = "app/cpy/CVTRA01Y.cpy",
        notes = "50-byte TRAN-CAT-BAL-RECORD; 17-byte composite key + S9(09)V99 balance + 22-byte FILLER"
)
public record TranCatBalRecord(
        TranCatKey key,
        BigDecimal tranCatBal,
        byte[] filler) {

    public static final int RECORD_LENGTH = 50;
    public static final int LEN_KEY = 17;
    public static final int LEN_TRAN_CAT_BAL = 11;
    public static final int LEN_FILLER = 22;
    public static final int MONETARY_SCALE = 2;

    /**
     * Composite key TRAN-CAT-KEY (17 bytes total).
     */
    public record TranCatKey(long acctId, String typeCd, int catCd) {

        public static final int LEN_ACCT_ID = 11;
        public static final int LEN_TYPE_CD = 2;
        public static final int LEN_CAT_CD = 4;

        public TranCatKey {
            if (acctId < 0L) {
                throw new IllegalArgumentException("acctId must be non-negative");
            }
            typeCd = typeCd == null ? "" : typeCd;
            if (catCd < 0 || catCd > 9999) {
                throw new IllegalArgumentException("catCd must be 0..9999");
            }
        }

        public byte[] encode() {
            byte[] buffer = new byte[LEN_ACCT_ID + LEN_TYPE_CD + LEN_CAT_CD];
            Arrays.fill(buffer, (byte) ' ');
            writeUnsignedLong(buffer, 0, LEN_ACCT_ID, acctId);
            writeAscii(buffer, LEN_ACCT_ID, LEN_TYPE_CD, typeCd);
            writeUnsignedLong(buffer, LEN_ACCT_ID + LEN_TYPE_CD, LEN_CAT_CD, catCd);
            return buffer;
        }
    }

    public TranCatBalRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(tranCatBal, "tranCatBal");
        tranCatBal = Decimals.scaled(tranCatBal, MONETARY_SCALE, Decimals.DEFAULT_MODE);
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

    public static TranCatBalRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "buffer must be " + RECORD_LENGTH + " bytes, got " + buffer.length);
        }
        int p = 0;
        long acctId = parseUnsignedLong(buffer, p, TranCatKey.LEN_ACCT_ID); p += TranCatKey.LEN_ACCT_ID;
        String typeCd = parseAscii(buffer, p, TranCatKey.LEN_TYPE_CD); p += TranCatKey.LEN_TYPE_CD;
        int catCd = (int) parseUnsignedLong(buffer, p, TranCatKey.LEN_CAT_CD); p += TranCatKey.LEN_CAT_CD;
        BigDecimal bal = Decimals.parseZonedDecimal(buffer, p, LEN_TRAN_CAT_BAL, MONETARY_SCALE); p += LEN_TRAN_CAT_BAL;
        byte[] filler = Arrays.copyOfRange(buffer, p, p + LEN_FILLER);

        return new TranCatBalRecord(new TranCatKey(acctId, typeCd, catCd), bal, filler);
    }

    public byte[] encode() {
        byte[] buffer = new byte[RECORD_LENGTH];
        Arrays.fill(buffer, (byte) ' ');
        int p = 0;
        writeUnsignedLong(buffer, p, TranCatKey.LEN_ACCT_ID, key.acctId); p += TranCatKey.LEN_ACCT_ID;
        writeAscii(buffer, p, TranCatKey.LEN_TYPE_CD, key.typeCd); p += TranCatKey.LEN_TYPE_CD;
        writeUnsignedLong(buffer, p, TranCatKey.LEN_CAT_CD, key.catCd); p += TranCatKey.LEN_CAT_CD;
        byte[] bal = Decimals.encodeZonedDecimal(tranCatBal, LEN_TRAN_CAT_BAL, MONETARY_SCALE);
        System.arraycopy(bal, 0, buffer, p, LEN_TRAN_CAT_BAL); p += LEN_TRAN_CAT_BAL;
        System.arraycopy(filler, 0, buffer, p, LEN_FILLER);

        return buffer;
    }

    /** Returns a new record with the balance adjusted by the supplied delta. */
    public TranCatBalRecord withBalanceAdjustment(BigDecimal delta) {
        Objects.requireNonNull(delta, "delta");
        BigDecimal newBal = Decimals.add(tranCatBal, delta, MONETARY_SCALE, Decimals.DEFAULT_MODE);
        return new TranCatBalRecord(key, newBal, filler.clone());
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
