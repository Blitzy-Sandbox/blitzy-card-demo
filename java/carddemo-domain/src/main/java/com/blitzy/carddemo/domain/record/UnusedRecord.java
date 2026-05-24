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
 * Domain record translated from the COBOL {@code UNUSED-DATA} copybook at
 * {@code app/cpy/UNUSED1Y.cpy} (record length 80 bytes; structurally identical
 * to {@code SEC-USER-DATA}).
 *
 * <pre>{@code
 * 01 UNUSED-DATA.
 *    05 UNUSED-ID                  PIC X(08).
 *    05 UNUSED-FNAME               PIC X(20).
 *    05 UNUSED-LNAME               PIC X(20).
 *    05 UNUSED-PWD                 PIC X(08).
 *    05 UNUSED-TYPE                PIC X(01).
 *    05 UNUSED-FILLER              PIC X(23).
 * }</pre>
 *
 * <h2>Why this record exists</h2>
 * Per AAP &sect;0.2.1 (the AAP explicitly lists UNUSED1Y in the in-scope
 * copybook inventory) and AAP &sect;0.7.1 (Refactor Discipline: dead code
 * is translated faithfully and flagged), this copybook is preserved even
 * though no COBOL program references it. See
 * {@code MIGRATION_NOTES.md} &sect;1.4.2 for the discovery rationale.
 */
@CobolProgram(
        value = "UNUSED1Y",
        sourcePath = "app/cpy/UNUSED1Y.cpy",
        notes = "80-byte UNUSED-DATA; unused in COBOL but translated for completeness per AAP §0.7.1"
)
public record UnusedRecord(
        String unusedId,
        String unusedFname,
        String unusedLname,
        String unusedPwd,
        String unusedType,
        byte[] filler) {

    public static final int RECORD_LENGTH = 80;
    public static final int OFFSET_ID = 0;
    public static final int LEN_ID = 8;
    public static final int OFFSET_FNAME = 8;
    public static final int LEN_FNAME = 20;
    public static final int OFFSET_LNAME = 28;
    public static final int LEN_LNAME = 20;
    public static final int OFFSET_PWD = 48;
    public static final int LEN_PWD = 8;
    public static final int OFFSET_TYPE = 56;
    public static final int LEN_TYPE = 1;
    public static final int OFFSET_FILLER = 57;
    public static final int LEN_FILLER = 23;

    private static final String PASSWORD_MASK = "********";

    public UnusedRecord {
        unusedId = unusedId == null ? "" : unusedId;
        unusedFname = unusedFname == null ? "" : unusedFname;
        unusedLname = unusedLname == null ? "" : unusedLname;
        unusedPwd = unusedPwd == null ? "" : unusedPwd;
        unusedType = unusedType == null ? "" : unusedType;
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

    @Override
    public String toString() {
        return "UnusedRecord[id=" + unusedId.stripTrailing()
                + ", fname=" + unusedFname.stripTrailing()
                + ", lname=" + unusedLname.stripTrailing()
                + ", pwd=" + PASSWORD_MASK
                + ", type=" + unusedType
                + ", filler=<" + LEN_FILLER + " bytes>]";
    }

    public static UnusedRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "buffer must be " + RECORD_LENGTH + " bytes, got " + buffer.length);
        }
        String id = parseAscii(buffer, OFFSET_ID, LEN_ID);
        String fname = parseAscii(buffer, OFFSET_FNAME, LEN_FNAME);
        String lname = parseAscii(buffer, OFFSET_LNAME, LEN_LNAME);
        String pwd = parseAscii(buffer, OFFSET_PWD, LEN_PWD);
        String type = parseAscii(buffer, OFFSET_TYPE, LEN_TYPE);
        byte[] filler = Arrays.copyOfRange(buffer, OFFSET_FILLER, OFFSET_FILLER + LEN_FILLER);
        return new UnusedRecord(id, fname, lname, pwd, type, filler);
    }

    public byte[] encode() {
        byte[] buffer = new byte[RECORD_LENGTH];
        Arrays.fill(buffer, (byte) ' ');
        writeAscii(buffer, OFFSET_ID, LEN_ID, unusedId);
        writeAscii(buffer, OFFSET_FNAME, LEN_FNAME, unusedFname);
        writeAscii(buffer, OFFSET_LNAME, LEN_LNAME, unusedLname);
        writeAscii(buffer, OFFSET_PWD, LEN_PWD, unusedPwd);
        writeAscii(buffer, OFFSET_TYPE, LEN_TYPE, unusedType);
        System.arraycopy(filler, 0, buffer, OFFSET_FILLER, LEN_FILLER);
        return buffer;
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
