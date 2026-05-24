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
 * Domain record translated from the COBOL {@code CARD-XREF-RECORD} copybook at
 * {@code app/cpy/CVACT03Y.cpy}.
 *
 * <pre>{@code
 * 01 CARD-XREF-RECORD.
 *    05 XREF-CARD-NUM  PIC X(16).
 *    05 XREF-CUST-ID   PIC 9(09).
 *    05 XREF-ACCT-ID   PIC 9(11).
 *    05 FILLER         PIC X(14).
 * }</pre>
 *
 * <p>The COBOL record length is 50 bytes (16 + 9 + 11 + 14 FILLER). However,
 * the fixture at {@code app/data/ASCII/cardxref.txt} is 36 bytes per record
 * (no trailing FILLER). Per AAP &sect;0.7.1 the discrepancy is documented in
 * {@code java/MIGRATION_NOTES.md} &sect;1.4.9 and the
 * {@link #parse(byte[])} factory accepts both lengths to preserve fixture
 * compatibility while {@link #encode()} always produces the canonical
 * 50-byte form.
 */
@CobolProgram(
        value = "CVACT03Y",
        sourcePath = "app/cpy/CVACT03Y.cpy",
        notes = "50-byte CARD-XREF-RECORD per COBOL; 36-byte fixture variant accepted by parse(). "
                + "See MIGRATION_NOTES.md §1.4.9"
)
public record CardXrefRecord(
        String xrefCardNum,
        long xrefCustId,
        long xrefAcctId,
        byte[] filler) {

    /** Canonical record length per COBOL copybook (with FILLER). */
    public static final int RECORD_LENGTH = 50;

    /** Trimmed record length matching {@code app/data/ASCII/cardxref.txt} (no FILLER). */
    public static final int FIXTURE_LENGTH = 36;

    public static final int OFFSET_XREF_CARD_NUM = 0;
    public static final int LEN_XREF_CARD_NUM = 16;
    public static final int OFFSET_XREF_CUST_ID = 16;
    public static final int LEN_XREF_CUST_ID = 9;
    public static final int OFFSET_XREF_ACCT_ID = 25;
    public static final int LEN_XREF_ACCT_ID = 11;
    public static final int OFFSET_FILLER = 36;
    public static final int LEN_FILLER = 14;

    public CardXrefRecord {
        if (xrefCardNum == null) {
            xrefCardNum = "";
        }
        if (xrefCustId < 0L) {
            throw new IllegalArgumentException("xrefCustId must be non-negative");
        }
        if (xrefAcctId < 0L) {
            throw new IllegalArgumentException("xrefAcctId must be non-negative");
        }
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

    /**
     * Returns the PAN masked to show only the last 4 digits.
     */
    public String maskedPan() {
        String trimmed = xrefCardNum.trim();
        if (trimmed.length() <= 4) {
            return trimmed;
        }
        return "*".repeat(trimmed.length() - 4) + trimmed.substring(trimmed.length() - 4);
    }

    /**
     * String representation with PAN masking per AAP &sect;0.7.2.
     */
    @Override
    public String toString() {
        return "CardXrefRecord[xrefCardNum=" + maskedPan()
                + ", xrefCustId=" + xrefCustId
                + ", xrefAcctId=" + xrefAcctId
                + ", filler=<" + LEN_FILLER + " bytes>]";
    }

    /**
     * Factory: decodes either a 50-byte canonical or 36-byte fixture buffer.
     * When the input is 36 bytes the trailing FILLER is synthesized as spaces.
     *
     * @param buffer either {@value #RECORD_LENGTH} bytes (canonical) or
     *               {@value #FIXTURE_LENGTH} bytes (fixture variant)
     */
    public static CardXrefRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH && buffer.length != FIXTURE_LENGTH) {
            throw new IllegalArgumentException(
                    "buffer must be " + RECORD_LENGTH + " or " + FIXTURE_LENGTH
                            + " bytes, got " + buffer.length);
        }

        String cardNum = parseAscii(buffer, OFFSET_XREF_CARD_NUM, LEN_XREF_CARD_NUM);
        long custId = parseUnsignedLong(buffer, OFFSET_XREF_CUST_ID, LEN_XREF_CUST_ID);
        long acctId = parseUnsignedLong(buffer, OFFSET_XREF_ACCT_ID, LEN_XREF_ACCT_ID);

        byte[] filler;
        if (buffer.length == RECORD_LENGTH) {
            filler = Arrays.copyOfRange(buffer, OFFSET_FILLER, OFFSET_FILLER + LEN_FILLER);
        } else {
            filler = new byte[LEN_FILLER];
            Arrays.fill(filler, (byte) ' ');
        }

        return new CardXrefRecord(cardNum, custId, acctId, filler);
    }

    /**
     * Encodes this record as the canonical {@value #RECORD_LENGTH}-byte
     * buffer (with trailing FILLER). To obtain the 36-byte fixture form,
     * call {@link #encodeFixture()} instead.
     */
    public byte[] encode() {
        byte[] buffer = new byte[RECORD_LENGTH];
        Arrays.fill(buffer, (byte) ' ');
        writeAscii(buffer, OFFSET_XREF_CARD_NUM, LEN_XREF_CARD_NUM, xrefCardNum);
        writeUnsignedLong(buffer, OFFSET_XREF_CUST_ID, LEN_XREF_CUST_ID, xrefCustId);
        writeUnsignedLong(buffer, OFFSET_XREF_ACCT_ID, LEN_XREF_ACCT_ID, xrefAcctId);
        System.arraycopy(filler, 0, buffer, OFFSET_FILLER, LEN_FILLER);
        return buffer;
    }

    /**
     * Encodes this record as the fixture {@value #FIXTURE_LENGTH}-byte
     * buffer (no trailing FILLER), matching
     * {@code app/data/ASCII/cardxref.txt}.
     */
    public byte[] encodeFixture() {
        byte[] buffer = new byte[FIXTURE_LENGTH];
        writeAscii(buffer, OFFSET_XREF_CARD_NUM, LEN_XREF_CARD_NUM, xrefCardNum);
        writeUnsignedLong(buffer, OFFSET_XREF_CUST_ID, LEN_XREF_CUST_ID, xrefCustId);
        writeUnsignedLong(buffer, OFFSET_XREF_ACCT_ID, LEN_XREF_ACCT_ID, xrefAcctId);
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

    /** Returns an empty FILLER byte array. */
    public static byte[] emptyFiller() {
        byte[] filler = new byte[LEN_FILLER];
        Arrays.fill(filler, (byte) ' ');
        return filler;
    }
}
