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
 * Domain record translated from the COBOL {@code TRAN-TYPE-RECORD} copybook
 * at {@code app/cpy/CVTRA03Y.cpy} (record length 60 bytes).
 *
 * <pre>{@code
 * 01 TRAN-TYPE-RECORD.
 *    05 TRAN-TYPE                  PIC X(02).
 *    05 TRAN-TYPE-DESC             PIC X(50).
 *    05 FILLER                     PIC X(08).
 * }</pre>
 *
 * <p>Loaded from the TRANTYPE dataset (7 records); the type code is the
 * primary key. Per AAP &sect;0.6.10, transaction-type taxonomy is data-driven
 * and loaded at runtime; the closed set materializes as the in-memory
 * collection of {@code TranTypeRecord} instances rather than as a sealed
 * hierarchy.
 */
@CobolProgram(
        value = "CVTRA03Y",
        sourcePath = "app/cpy/CVTRA03Y.cpy",
        notes = "60-byte TRAN-TYPE-RECORD; 2-byte type code + 50-byte description + 8-byte FILLER"
)
public record TranTypeRecord(
        String tranType,
        String tranTypeDesc,
        byte[] filler) {

    public static final int RECORD_LENGTH = 60;
    public static final int OFFSET_TRAN_TYPE = 0;
    public static final int LEN_TRAN_TYPE = 2;
    public static final int OFFSET_TRAN_TYPE_DESC = 2;
    public static final int LEN_TRAN_TYPE_DESC = 50;
    public static final int OFFSET_FILLER = 52;
    public static final int LEN_FILLER = 8;

    public TranTypeRecord {
        tranType = tranType == null ? "" : tranType;
        tranTypeDesc = tranTypeDesc == null ? "" : tranTypeDesc;
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

    public static TranTypeRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "buffer must be " + RECORD_LENGTH + " bytes, got " + buffer.length);
        }
        String tranType = parseAscii(buffer, OFFSET_TRAN_TYPE, LEN_TRAN_TYPE);
        String desc = parseAscii(buffer, OFFSET_TRAN_TYPE_DESC, LEN_TRAN_TYPE_DESC);
        byte[] filler = Arrays.copyOfRange(buffer, OFFSET_FILLER, OFFSET_FILLER + LEN_FILLER);
        return new TranTypeRecord(tranType, desc, filler);
    }

    public byte[] encode() {
        byte[] buffer = new byte[RECORD_LENGTH];
        Arrays.fill(buffer, (byte) ' ');
        writeAscii(buffer, OFFSET_TRAN_TYPE, LEN_TRAN_TYPE, tranType);
        writeAscii(buffer, OFFSET_TRAN_TYPE_DESC, LEN_TRAN_TYPE_DESC, tranTypeDesc);
        System.arraycopy(filler, 0, buffer, OFFSET_FILLER, LEN_FILLER);
        return buffer;
    }

    /** Returns the human-readable description, trimmed of trailing spaces. */
    public String description() {
        return tranTypeDesc.stripTrailing();
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
