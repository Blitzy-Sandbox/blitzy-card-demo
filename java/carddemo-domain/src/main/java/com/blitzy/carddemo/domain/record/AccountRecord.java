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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Objects;

/**
 * Domain record translated from the COBOL {@code ACCOUNT-RECORD} copybook at
 * {@code app/cpy/CVACT01Y.cpy} (record length 300 bytes).
 *
 * <p>The COBOL layout is:
 *
 * <pre>{@code
 * 01 ACCOUNT-RECORD.
 *    05 ACCT-ID                 PIC 9(11).
 *    05 ACCT-ACTIVE-STATUS      PIC X(01).
 *    05 ACCT-CURR-BAL           PIC S9(10)V99.
 *    05 ACCT-CREDIT-LIMIT       PIC S9(10)V99.
 *    05 ACCT-CASH-CREDIT-LIMIT  PIC S9(10)V99.
 *    05 ACCT-OPEN-DATE          PIC X(10).
 *    05 ACCT-EXPIRAION-DATE     PIC X(10).
 *    05 ACCT-REISSUE-DATE       PIC X(10).
 *    05 ACCT-CURR-CYC-CREDIT    PIC S9(10)V99.
 *    05 ACCT-CURR-CYC-DEBIT     PIC S9(10)V99.
 *    05 ACCT-ADDR-ZIP           PIC X(10).
 *    05 ACCT-GROUP-ID           PIC X(10).
 *    05 FILLER                  PIC X(178).
 * }</pre>
 *
 * <p>The fixture {@code app/data/ASCII/acctdata.txt} encodes each monetary
 * field as ASCII zoned-decimal (12 bytes per {@code S9(10)V99} value with
 * sign overpunch on the rightmost digit). Dates are 10-character
 * {@code YYYY-MM-DD} strings; the {@code ACCT-GROUP-ID} field is often blank.
 *
 * <h2>{@code ACCT-EXPIRAION-DATE} typo preservation (AAP &sect;0.7.1)</h2>
 * <p>The COBOL copybook contains the typographical error
 * {@code EXPIRAION} (missing 'T'). Per AAP &sect;0.7.1 "translate
 * faithfully and flag it in MIGRATION_NOTES.md; do not 'fix' it in this
 * refactor". The Java field is therefore named
 * {@link #acctExpiraionDate()} verbatim. See
 * {@code java/MIGRATION_NOTES.md} &sect;1.4.6 for additional context.
 *
 * <h2>Byte fidelity</h2>
 * <p>Per AAP &sect;0.6.5 the {@link #parse(byte[])} factory and
 * {@link #encode()} method are inverses: {@code parse(b).encode()} equals
 * {@code b} for every valid 300-byte buffer produced by COBOL DISPLAY of an
 * account record. Monetary fields are decoded via {@link Decimals#parseZonedDecimal}
 * with scale 2; the 178-byte trailing FILLER is preserved verbatim as a
 * defensive copy ({@link #filler()}).
 */
@CobolProgram(
        value = "CVACT01Y",
        sourcePath = "app/cpy/CVACT01Y.cpy",
        notes = "300-byte ACCOUNT-RECORD; ACCT-EXPIRAION-DATE typo preserved verbatim per AAP §0.7.1"
)
public record AccountRecord(
        long acctId,
        char acctActiveStatus,
        BigDecimal acctCurrBal,
        BigDecimal acctCreditLimit,
        BigDecimal acctCashCreditLimit,
        LocalDate acctOpenDate,
        LocalDate acctExpiraionDate,
        LocalDate acctReissueDate,
        BigDecimal acctCurrCycCredit,
        BigDecimal acctCurrCycDebit,
        String acctAddrZip,
        String acctGroupId,
        byte[] filler) {

    /** Total record length in bytes per copybook. */
    public static final int RECORD_LENGTH = 300;

    /** Offset of {@code ACCT-ID}. */
    public static final int OFFSET_ACCT_ID = 0;
    /** Length of {@code ACCT-ID} (PIC 9(11)). */
    public static final int LEN_ACCT_ID = 11;
    /** Offset of {@code ACCT-ACTIVE-STATUS}. */
    public static final int OFFSET_ACCT_ACTIVE_STATUS = 11;
    /** Length of {@code ACCT-ACTIVE-STATUS}. */
    public static final int LEN_ACCT_ACTIVE_STATUS = 1;
    /** Offset of {@code ACCT-CURR-BAL}. */
    public static final int OFFSET_ACCT_CURR_BAL = 12;
    /** Length of each monetary field (PIC S9(10)V99) encoded as zoned-decimal ASCII. */
    public static final int LEN_MONETARY = 12;
    /** Offset of {@code ACCT-CREDIT-LIMIT}. */
    public static final int OFFSET_ACCT_CREDIT_LIMIT = 24;
    /** Offset of {@code ACCT-CASH-CREDIT-LIMIT}. */
    public static final int OFFSET_ACCT_CASH_CREDIT_LIMIT = 36;
    /** Offset of {@code ACCT-OPEN-DATE}. */
    public static final int OFFSET_ACCT_OPEN_DATE = 48;
    /** Length of date fields (PIC X(10) YYYY-MM-DD). */
    public static final int LEN_DATE = 10;
    /** Offset of {@code ACCT-EXPIRAION-DATE}. */
    public static final int OFFSET_ACCT_EXPIRAION_DATE = 58;
    /** Offset of {@code ACCT-REISSUE-DATE}. */
    public static final int OFFSET_ACCT_REISSUE_DATE = 68;
    /** Offset of {@code ACCT-CURR-CYC-CREDIT}. */
    public static final int OFFSET_ACCT_CURR_CYC_CREDIT = 78;
    /** Offset of {@code ACCT-CURR-CYC-DEBIT}. */
    public static final int OFFSET_ACCT_CURR_CYC_DEBIT = 90;
    /** Offset of {@code ACCT-ADDR-ZIP}. */
    public static final int OFFSET_ACCT_ADDR_ZIP = 102;
    /** Length of {@code ACCT-ADDR-ZIP}. */
    public static final int LEN_ACCT_ADDR_ZIP = 10;
    /** Offset of {@code ACCT-GROUP-ID}. */
    public static final int OFFSET_ACCT_GROUP_ID = 112;
    /** Length of {@code ACCT-GROUP-ID}. */
    public static final int LEN_ACCT_GROUP_ID = 10;
    /** Offset of trailing FILLER. */
    public static final int OFFSET_FILLER = 122;
    /** Length of trailing FILLER (PIC X(178)). */
    public static final int LEN_FILLER = 178;

    /** Monetary scale per COBOL {@code V99} (two decimal places). */
    public static final int MONETARY_SCALE = 2;

    /** ISO-8601 date formatter for {@code YYYY-MM-DD} dates. */
    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Compact canonical constructor (JEP 513 Flexible Constructor Bodies).
     * Validates required invariants:
     * <ul>
     *   <li>{@code acctId} non-negative</li>
     *   <li>{@code filler} present and exactly {@value #LEN_FILLER} bytes</li>
     *   <li>Strings normalized to non-null</li>
     *   <li>Monetary BigDecimals normalized to scale {@value #MONETARY_SCALE}</li>
     * </ul>
     */
    public AccountRecord {
        if (acctId < 0L) {
            throw new IllegalArgumentException("acctId must be non-negative, got " + acctId);
        }
        Objects.requireNonNull(acctCurrBal, "acctCurrBal");
        Objects.requireNonNull(acctCreditLimit, "acctCreditLimit");
        Objects.requireNonNull(acctCashCreditLimit, "acctCashCreditLimit");
        Objects.requireNonNull(acctCurrCycCredit, "acctCurrCycCredit");
        Objects.requireNonNull(acctCurrCycDebit, "acctCurrCycDebit");
        if (acctAddrZip == null) {
            acctAddrZip = "";
        }
        if (acctGroupId == null) {
            acctGroupId = "";
        }
        Objects.requireNonNull(filler, "filler");
        if (filler.length != LEN_FILLER) {
            throw new IllegalArgumentException(
                    "filler must be " + LEN_FILLER + " bytes, got " + filler.length);
        }
        // Defensive copy of the byte[] component to preserve immutability.
        filler = filler.clone();
        // Normalize monetary scale via Decimals utility.
        acctCurrBal = Decimals.scaled(acctCurrBal, MONETARY_SCALE, Decimals.DEFAULT_MODE);
        acctCreditLimit = Decimals.scaled(acctCreditLimit, MONETARY_SCALE, Decimals.DEFAULT_MODE);
        acctCashCreditLimit = Decimals.scaled(acctCashCreditLimit, MONETARY_SCALE, Decimals.DEFAULT_MODE);
        acctCurrCycCredit = Decimals.scaled(acctCurrCycCredit, MONETARY_SCALE, Decimals.DEFAULT_MODE);
        acctCurrCycDebit = Decimals.scaled(acctCurrCycDebit, MONETARY_SCALE, Decimals.DEFAULT_MODE);
    }

    /**
     * Defensive accessor: returns a clone of the FILLER bytes to preserve
     * record immutability.
     */
    @Override
    public byte[] filler() {
        return filler.clone();
    }

    /**
     * Factory: decodes a {@value #RECORD_LENGTH}-byte buffer into an
     * {@code AccountRecord}. Per AAP &sect;0.6.5 byte fidelity:
     * {@code parse(b).encode()} equals {@code b} for every well-formed
     * buffer.
     *
     * @param buffer fixed-width input bytes
     * @return parsed account record
     * @throws NullPointerException if {@code buffer} is null
     * @throws IllegalArgumentException if {@code buffer.length != 300}
     */
    public static AccountRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "buffer must be " + RECORD_LENGTH + " bytes, got " + buffer.length);
        }

        long acctId = parseUnsignedLong(buffer, OFFSET_ACCT_ID, LEN_ACCT_ID);
        char status = (char) (buffer[OFFSET_ACCT_ACTIVE_STATUS] & 0xFF);
        BigDecimal currBal = Decimals.parseZonedDecimal(buffer, OFFSET_ACCT_CURR_BAL, LEN_MONETARY, MONETARY_SCALE);
        BigDecimal creditLimit = Decimals.parseZonedDecimal(buffer, OFFSET_ACCT_CREDIT_LIMIT, LEN_MONETARY, MONETARY_SCALE);
        BigDecimal cashCreditLimit = Decimals.parseZonedDecimal(buffer, OFFSET_ACCT_CASH_CREDIT_LIMIT, LEN_MONETARY, MONETARY_SCALE);
        LocalDate openDate = parseDate(buffer, OFFSET_ACCT_OPEN_DATE);
        LocalDate expiraionDate = parseDate(buffer, OFFSET_ACCT_EXPIRAION_DATE);
        LocalDate reissueDate = parseDate(buffer, OFFSET_ACCT_REISSUE_DATE);
        BigDecimal currCycCredit = Decimals.parseZonedDecimal(buffer, OFFSET_ACCT_CURR_CYC_CREDIT, LEN_MONETARY, MONETARY_SCALE);
        BigDecimal currCycDebit = Decimals.parseZonedDecimal(buffer, OFFSET_ACCT_CURR_CYC_DEBIT, LEN_MONETARY, MONETARY_SCALE);
        String addrZip = parseAscii(buffer, OFFSET_ACCT_ADDR_ZIP, LEN_ACCT_ADDR_ZIP);
        String groupId = parseAscii(buffer, OFFSET_ACCT_GROUP_ID, LEN_ACCT_GROUP_ID);
        byte[] filler = Arrays.copyOfRange(buffer, OFFSET_FILLER, OFFSET_FILLER + LEN_FILLER);

        return new AccountRecord(
                acctId, status, currBal, creditLimit, cashCreditLimit,
                openDate, expiraionDate, reissueDate,
                currCycCredit, currCycDebit, addrZip, groupId, filler);
    }

    /**
     * Encodes this record as a {@value #RECORD_LENGTH}-byte ASCII buffer
     * suitable for sequential I/O via {@code java.nio.file}.
     *
     * @return new byte array of exactly {@value #RECORD_LENGTH} bytes
     */
    public byte[] encode() {
        byte[] buffer = new byte[RECORD_LENGTH];
        // Fill with spaces by default (COBOL DISPLAY default).
        Arrays.fill(buffer, (byte) ' ');

        writeUnsignedLong(buffer, OFFSET_ACCT_ID, LEN_ACCT_ID, acctId);
        buffer[OFFSET_ACCT_ACTIVE_STATUS] = (byte) acctActiveStatus;
        byte[] currBal = Decimals.encodeZonedDecimal(acctCurrBal, LEN_MONETARY, MONETARY_SCALE);
        System.arraycopy(currBal, 0, buffer, OFFSET_ACCT_CURR_BAL, LEN_MONETARY);
        byte[] creditLimit = Decimals.encodeZonedDecimal(acctCreditLimit, LEN_MONETARY, MONETARY_SCALE);
        System.arraycopy(creditLimit, 0, buffer, OFFSET_ACCT_CREDIT_LIMIT, LEN_MONETARY);
        byte[] cashCreditLimit = Decimals.encodeZonedDecimal(acctCashCreditLimit, LEN_MONETARY, MONETARY_SCALE);
        System.arraycopy(cashCreditLimit, 0, buffer, OFFSET_ACCT_CASH_CREDIT_LIMIT, LEN_MONETARY);
        writeDate(buffer, OFFSET_ACCT_OPEN_DATE, acctOpenDate);
        writeDate(buffer, OFFSET_ACCT_EXPIRAION_DATE, acctExpiraionDate);
        writeDate(buffer, OFFSET_ACCT_REISSUE_DATE, acctReissueDate);
        byte[] currCycCredit = Decimals.encodeZonedDecimal(acctCurrCycCredit, LEN_MONETARY, MONETARY_SCALE);
        System.arraycopy(currCycCredit, 0, buffer, OFFSET_ACCT_CURR_CYC_CREDIT, LEN_MONETARY);
        byte[] currCycDebit = Decimals.encodeZonedDecimal(acctCurrCycDebit, LEN_MONETARY, MONETARY_SCALE);
        System.arraycopy(currCycDebit, 0, buffer, OFFSET_ACCT_CURR_CYC_DEBIT, LEN_MONETARY);
        writeAscii(buffer, OFFSET_ACCT_ADDR_ZIP, LEN_ACCT_ADDR_ZIP, acctAddrZip);
        writeAscii(buffer, OFFSET_ACCT_GROUP_ID, LEN_ACCT_GROUP_ID, acctGroupId);
        System.arraycopy(filler, 0, buffer, OFFSET_FILLER, LEN_FILLER);

        return buffer;
    }

    /** Helper: parses an ASCII unsigned numeric field of fixed length. */
    private static long parseUnsignedLong(byte[] buffer, int offset, int length) {
        long value = 0L;
        for (int i = 0; i < length; i++) {
            int c = buffer[offset + i] & 0xFF;
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(String.format(
                        "Non-digit character at byte %d: 0x%02X ('%c')",
                        offset + i, c, (char) c));
            }
            value = value * 10L + (c - '0');
        }
        return value;
    }

    /** Helper: writes an ASCII unsigned numeric field of fixed length. */
    private static void writeUnsignedLong(byte[] buffer, int offset, int length, long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("Value must be non-negative: " + value);
        }
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

    /** Helper: parses a 10-byte ASCII date in {@code YYYY-MM-DD} format. */
    private static LocalDate parseDate(byte[] buffer, int offset) {
        String s = new String(buffer, offset, LEN_DATE, StandardCharsets.US_ASCII);
        return LocalDate.parse(s.trim(), DATE_FORMAT);
    }

    /** Helper: writes a 10-byte ASCII date in {@code YYYY-MM-DD} format. */
    private static void writeDate(byte[] buffer, int offset, LocalDate date) {
        if (date == null) {
            for (int i = 0; i < LEN_DATE; i++) {
                buffer[offset + i] = ' ';
            }
            return;
        }
        byte[] bytes = date.format(DATE_FORMAT).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, buffer, offset, LEN_DATE);
    }

    /** Helper: parses a fixed-width ASCII string, preserving trailing padding. */
    private static String parseAscii(byte[] buffer, int offset, int length) {
        return new String(buffer, offset, length, StandardCharsets.US_ASCII);
    }

    /** Helper: writes a fixed-width ASCII string, right-padding with spaces. */
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
