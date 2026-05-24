/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.record;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Domain record translated from the COBOL {@code TRAN-CAT-RECORD} copybook at
 * {@code app/cpy/CVTRA04Y.cpy} (record length 60 bytes).
 *
 * <pre>{@code
 * 01 TRAN-CAT-RECORD.
 *    05 TRAN-CAT-KEY.
 *       10 TRAN-TYPE-CD            PIC X(02).
 *       10 TRAN-CAT-CD             PIC 9(04).
 *    05 TRAN-CAT-TYPE-DESC         PIC X(50).
 *    05 FILLER                     PIC X(04).
 * }</pre>
 *
 * <p>Loaded from the TRANCATG dataset (18 records); the composite key (6 bytes
 * = 2 + 4) identifies a transaction category. Per AAP &sect;0.6.10 transaction
 * categories are data-driven so this is a value-record (not sealed).
 */
@CobolProgram(
        value = "CVTRA04Y",
        sourcePath = "app/cpy/CVTRA04Y.cpy",
        notes = "60-byte TRAN-CAT-RECORD; 6-byte composite key + 50-byte description + 4-byte FILLER"
)
public record TranCatRecord(
        TranCatKey key,
        String tranCatTypeDesc,
        byte[] filler) {

    public static final int RECORD_LENGTH = 60;
    public static final int LEN_KEY = 6;
    public static final int OFFSET_DESC = 6;
    public static final int LEN_DESC = 50;
    public static final int OFFSET_FILLER = 56;
    public static final int LEN_FILLER = 4;

    /**
     * Composite key TRAN-CAT-KEY (6 bytes total): TRAN-TYPE-CD PIC X(02) + TRAN-CAT-CD PIC 9(04).
     */
    public record TranCatKey(String tranTypeCd, int tranCatCd) {

        public static final int LEN_TRAN_TYPE_CD = 2;
        public static final int LEN_TRAN_CAT_CD = 4;

        public TranCatKey {
            tranTypeCd = tranTypeCd == null ? "" : tranTypeCd;
            if (tranCatCd < 0 || tranCatCd > 9999) {
                throw new IllegalArgumentException("tranCatCd must be 0..9999");
            }
        }

        public byte[] encode() {
            byte[] buffer = new byte[LEN_TRAN_TYPE_CD + LEN_TRAN_CAT_CD];
            Arrays.fill(buffer, (byte) ' ');
            writeAscii(buffer, 0, LEN_TRAN_TYPE_CD, tranTypeCd);
            writeUnsignedLong(buffer, LEN_TRAN_TYPE_CD, LEN_TRAN_CAT_CD, tranCatCd);
            return buffer;
        }
    }

    public TranCatRecord {
        Objects.requireNonNull(key, "key");
        tranCatTypeDesc = tranCatTypeDesc == null ? "" : tranCatTypeDesc;
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

    public static TranCatRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "buffer must be " + RECORD_LENGTH + " bytes, got " + buffer.length);
        }
        int p = 0;
        String tranTypeCd = parseAscii(buffer, p, TranCatKey.LEN_TRAN_TYPE_CD); p += TranCatKey.LEN_TRAN_TYPE_CD;
        int tranCatCd = (int) parseUnsignedLong(buffer, p, TranCatKey.LEN_TRAN_CAT_CD); p += TranCatKey.LEN_TRAN_CAT_CD;
        String desc = parseAscii(buffer, OFFSET_DESC, LEN_DESC);
        byte[] filler = Arrays.copyOfRange(buffer, OFFSET_FILLER, OFFSET_FILLER + LEN_FILLER);
        return new TranCatRecord(new TranCatKey(tranTypeCd, tranCatCd), desc, filler);
    }

    public byte[] encode() {
        byte[] buffer = new byte[RECORD_LENGTH];
        Arrays.fill(buffer, (byte) ' ');
        int p = 0;
        writeAscii(buffer, p, TranCatKey.LEN_TRAN_TYPE_CD, key.tranTypeCd); p += TranCatKey.LEN_TRAN_TYPE_CD;
        writeUnsignedLong(buffer, p, TranCatKey.LEN_TRAN_CAT_CD, key.tranCatCd);
        writeAscii(buffer, OFFSET_DESC, LEN_DESC, tranCatTypeDesc);
        System.arraycopy(filler, 0, buffer, OFFSET_FILLER, LEN_FILLER);
        return buffer;
    }

    public String description() {
        return tranCatTypeDesc.stripTrailing();
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
