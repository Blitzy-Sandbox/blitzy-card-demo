/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.record;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Objects;

/**
 * Domain record translated from the COBOL {@code CARD-RECORD} copybook at
 * {@code app/cpy/CVACT02Y.cpy} (record length 150 bytes).
 *
 * <pre>{@code
 * 01 CARD-RECORD.
 *    05 CARD-NUM             PIC X(16).
 *    05 CARD-ACCT-ID         PIC 9(11).
 *    05 CARD-CVV-CD          PIC 9(03).
 *    05 CARD-EMBOSSED-NAME   PIC X(50).
 *    05 CARD-EXPIRAION-DATE  PIC X(10).
 *    05 CARD-ACTIVE-STATUS   PIC X(01).
 *    05 FILLER               PIC X(59).
 * }</pre>
 *
 * <h2>{@code CARD-EXPIRAION-DATE} typo preservation (AAP &sect;0.7.1)</h2>
 * The COBOL copybook contains the same typographical error as
 * {@code ACCT-EXPIRAION-DATE} in {@code CVACT01Y.cpy}: {@code EXPIRAION}
 * (missing 'T'). Per AAP &sect;0.7.1 the typo is preserved verbatim in the
 * Java field name {@link #cardExpiraionDate()}.
 *
 * <h2>PAN handling (AAP &sect;0.7.2)</h2>
 * The {@link #cardNum()} field carries the full 16-digit PAN. Per AAP
 * &sect;0.7.2 the PAN MUST NOT appear in logs except masked (last 4
 * digits visible only). The {@link #toString()} override below applies
 * that mask.
 */
@CobolProgram(
        value = "CVACT02Y",
        sourcePath = "app/cpy/CVACT02Y.cpy",
        notes = "150-byte CARD-RECORD; CARD-EXPIRAION-DATE typo preserved per AAP §0.7.1"
)
public record CardRecord(
        String cardNum,
        long cardAcctId,
        int cardCvvCd,
        String cardEmbossedName,
        LocalDate cardExpiraionDate,
        char cardActiveStatus,
        byte[] filler) {

    /** Total record length in bytes per copybook. */
    public static final int RECORD_LENGTH = 150;

    public static final int OFFSET_CARD_NUM = 0;
    public static final int LEN_CARD_NUM = 16;
    public static final int OFFSET_CARD_ACCT_ID = 16;
    public static final int LEN_CARD_ACCT_ID = 11;
    public static final int OFFSET_CARD_CVV_CD = 27;
    public static final int LEN_CARD_CVV_CD = 3;
    public static final int OFFSET_CARD_EMBOSSED_NAME = 30;
    public static final int LEN_CARD_EMBOSSED_NAME = 50;
    public static final int OFFSET_CARD_EXPIRAION_DATE = 80;
    public static final int LEN_CARD_EXPIRAION_DATE = 10;
    public static final int OFFSET_CARD_ACTIVE_STATUS = 90;
    public static final int LEN_CARD_ACTIVE_STATUS = 1;
    public static final int OFFSET_FILLER = 91;
    public static final int LEN_FILLER = 59;

    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Compact canonical constructor (JEP 513 Flexible Constructor Bodies).
     */
    public CardRecord {
        if (cardNum == null) {
            cardNum = "";
        }
        if (cardAcctId < 0L) {
            throw new IllegalArgumentException("cardAcctId must be non-negative, got " + cardAcctId);
        }
        if (cardCvvCd < 0 || cardCvvCd > 999) {
            throw new IllegalArgumentException("cardCvvCd must be 0..999, got " + cardCvvCd);
        }
        if (cardEmbossedName == null) {
            cardEmbossedName = "";
        }
        Objects.requireNonNull(filler, "filler");
        if (filler.length != LEN_FILLER) {
            throw new IllegalArgumentException(
                    "filler must be " + LEN_FILLER + " bytes, got " + filler.length);
        }
        filler = filler.clone();
    }

    /** Defensive accessor returning a clone. */
    @Override
    public byte[] filler() {
        return filler.clone();
    }

    /**
     * Returns the PAN masked to show only the last 4 digits, e.g.
     * {@code "************1234"} for {@link #cardNum()} of
     * {@code "4111111111111234"}. Used by {@link #toString()}.
     *
     * @return PAN with all but the last 4 digits replaced by {@code '*'}
     */
    public String maskedPan() {
        String trimmed = cardNum.trim();
        if (trimmed.length() <= 4) {
            return trimmed;
        }
        int prefixLen = trimmed.length() - 4;
        return "*".repeat(prefixLen) + trimmed.substring(prefixLen);
    }

    /**
     * String representation that masks the PAN per AAP &sect;0.7.2. The
     * cleartext PAN is NEVER printed.
     */
    @Override
    public String toString() {
        return "CardRecord[cardNum=" + maskedPan()
                + ", cardAcctId=" + cardAcctId
                + ", cardCvvCd=***"
                + ", cardEmbossedName=" + cardEmbossedName
                + ", cardExpiraionDate=" + cardExpiraionDate
                + ", cardActiveStatus=" + cardActiveStatus
                + ", filler=<" + LEN_FILLER + " bytes>]";
    }

    /**
     * Factory: decodes a {@value #RECORD_LENGTH}-byte buffer into a
     * {@link CardRecord}.
     */
    public static CardRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "buffer must be " + RECORD_LENGTH + " bytes, got " + buffer.length);
        }

        String cardNum = parseAscii(buffer, OFFSET_CARD_NUM, LEN_CARD_NUM);
        long acctId = parseUnsignedLong(buffer, OFFSET_CARD_ACCT_ID, LEN_CARD_ACCT_ID);
        int cvv = (int) parseUnsignedLong(buffer, OFFSET_CARD_CVV_CD, LEN_CARD_CVV_CD);
        String embossedName = parseAscii(buffer, OFFSET_CARD_EMBOSSED_NAME, LEN_CARD_EMBOSSED_NAME);
        LocalDate expiraionDate = parseDate(buffer, OFFSET_CARD_EXPIRAION_DATE);
        char status = (char) (buffer[OFFSET_CARD_ACTIVE_STATUS] & 0xFF);
        byte[] filler = Arrays.copyOfRange(buffer, OFFSET_FILLER, OFFSET_FILLER + LEN_FILLER);

        return new CardRecord(cardNum, acctId, cvv, embossedName, expiraionDate, status, filler);
    }

    /** Encodes this record as a {@value #RECORD_LENGTH}-byte buffer. */
    public byte[] encode() {
        byte[] buffer = new byte[RECORD_LENGTH];
        Arrays.fill(buffer, (byte) ' ');

        writeAscii(buffer, OFFSET_CARD_NUM, LEN_CARD_NUM, cardNum);
        writeUnsignedLong(buffer, OFFSET_CARD_ACCT_ID, LEN_CARD_ACCT_ID, cardAcctId);
        writeUnsignedLong(buffer, OFFSET_CARD_CVV_CD, LEN_CARD_CVV_CD, cardCvvCd);
        writeAscii(buffer, OFFSET_CARD_EMBOSSED_NAME, LEN_CARD_EMBOSSED_NAME, cardEmbossedName);
        writeDate(buffer, OFFSET_CARD_EXPIRAION_DATE, cardExpiraionDate);
        buffer[OFFSET_CARD_ACTIVE_STATUS] = (byte) cardActiveStatus;
        System.arraycopy(filler, 0, buffer, OFFSET_FILLER, LEN_FILLER);

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
            throw new IllegalArgumentException(
                    "Value " + value + " exceeds " + length + "-digit field");
        }
    }

    private static LocalDate parseDate(byte[] buffer, int offset) {
        String s = new String(buffer, offset, LEN_CARD_EXPIRAION_DATE, StandardCharsets.US_ASCII);
        return LocalDate.parse(s.trim(), DATE_FORMAT);
    }

    private static void writeDate(byte[] buffer, int offset, LocalDate date) {
        if (date == null) {
            for (int i = 0; i < LEN_CARD_EXPIRAION_DATE; i++) {
                buffer[offset + i] = ' ';
            }
            return;
        }
        byte[] bytes = date.format(DATE_FORMAT).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, buffer, offset, LEN_CARD_EXPIRAION_DATE);
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

    /** Returns an empty FILLER byte array (all spaces). */
    public static byte[] emptyFiller() {
        byte[] filler = new byte[LEN_FILLER];
        Arrays.fill(filler, (byte) ' ');
        return filler;
    }
}
