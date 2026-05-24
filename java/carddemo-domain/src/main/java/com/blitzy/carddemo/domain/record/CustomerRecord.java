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
 * Domain record translated from the COBOL {@code CUSTOMER-RECORD} copybook at
 * {@code app/cpy/CVCUS01Y.cpy} (record length 500 bytes).
 *
 * <pre>{@code
 * 01 CUSTOMER-RECORD.
 *    05 CUST-ID                 PIC 9(09).
 *    05 CUST-FIRST-NAME         PIC X(25).
 *    05 CUST-MIDDLE-NAME        PIC X(25).
 *    05 CUST-LAST-NAME          PIC X(25).
 *    05 CUST-ADDR-LINE-1        PIC X(50).
 *    05 CUST-ADDR-LINE-2        PIC X(50).
 *    05 CUST-ADDR-LINE-3        PIC X(50).
 *    05 CUST-ADDR-STATE-CD      PIC X(02).
 *    05 CUST-ADDR-COUNTRY-CD    PIC X(03).
 *    05 CUST-ADDR-ZIP           PIC X(10).
 *    05 CUST-PHONE-NUM-1        PIC X(15).
 *    05 CUST-PHONE-NUM-2        PIC X(15).
 *    05 CUST-SSN                PIC 9(09).
 *    05 CUST-GOVT-ISSUED-ID     PIC X(20).
 *    05 CUST-DOB-YYYY-MM-DD     PIC X(10).
 *    05 CUST-EFT-ACCOUNT-ID     PIC X(10).
 *    05 CUST-PRI-CARD-HOLDER-IND PIC X(01).
 *    05 CUST-FICO-CREDIT-SCORE  PIC 9(03).
 *    05 FILLER                  PIC X(168).
 * }</pre>
 *
 * <p>The SSN is stored as a 9-digit number; in {@link #toString()} it is
 * masked to the last 4 digits per AAP &sect;0.7.2 (parity with the PAN
 * masking rule for sensitive identifiers).
 */
@CobolProgram(
        value = "CVCUS01Y",
        sourcePath = "app/cpy/CVCUS01Y.cpy",
        notes = "500-byte CUSTOMER-RECORD; SSN masked in toString() per AAP §0.7.2"
)
public record CustomerRecord(
        long custId,
        String custFirstName,
        String custMiddleName,
        String custLastName,
        String custAddrLine1,
        String custAddrLine2,
        String custAddrLine3,
        String custAddrStateCd,
        String custAddrCountryCd,
        String custAddrZip,
        String custPhoneNum1,
        String custPhoneNum2,
        long custSsn,
        String custGovtIssuedId,
        String custDobYyyyMmDd,
        String custEftAccountId,
        char custPriCardHolderInd,
        int custFicoCreditScore,
        byte[] filler) {

    public static final int RECORD_LENGTH = 500;
    public static final int LEN_FILLER = 168;

    public CustomerRecord {
        if (custId < 0L) {
            throw new IllegalArgumentException("custId must be non-negative");
        }
        if (custSsn < 0L) {
            throw new IllegalArgumentException("custSsn must be non-negative");
        }
        if (custFicoCreditScore < 0 || custFicoCreditScore > 999) {
            throw new IllegalArgumentException(
                    "custFicoCreditScore must be 0..999, got " + custFicoCreditScore);
        }
        custFirstName = orEmpty(custFirstName);
        custMiddleName = orEmpty(custMiddleName);
        custLastName = orEmpty(custLastName);
        custAddrLine1 = orEmpty(custAddrLine1);
        custAddrLine2 = orEmpty(custAddrLine2);
        custAddrLine3 = orEmpty(custAddrLine3);
        custAddrStateCd = orEmpty(custAddrStateCd);
        custAddrCountryCd = orEmpty(custAddrCountryCd);
        custAddrZip = orEmpty(custAddrZip);
        custPhoneNum1 = orEmpty(custPhoneNum1);
        custPhoneNum2 = orEmpty(custPhoneNum2);
        custGovtIssuedId = orEmpty(custGovtIssuedId);
        custDobYyyyMmDd = orEmpty(custDobYyyyMmDd);
        custEftAccountId = orEmpty(custEftAccountId);
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

    /** Returns the SSN with all but the last 4 digits masked. */
    public String maskedSsn() {
        String full = String.format("%09d", custSsn);
        return "*****" + full.substring(5);
    }

    /**
     * String representation with SSN masked per AAP &sect;0.7.2 parity rule.
     */
    @Override
    public String toString() {
        return "CustomerRecord[custId=" + custId
                + ", name=" + custFirstName.trim() + " " + custLastName.trim()
                + ", custSsn=" + maskedSsn()
                + ", state=" + custAddrStateCd
                + ", country=" + custAddrCountryCd
                + ", ficoScore=" + custFicoCreditScore
                + ", priCardHolder=" + custPriCardHolderInd
                + ", filler=<" + LEN_FILLER + " bytes>]";
    }

    /** Factory: decodes a 500-byte buffer. */
    public static CustomerRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "buffer must be " + RECORD_LENGTH + " bytes, got " + buffer.length);
        }
        int p = 0;
        long custId = parseUnsignedLong(buffer, p, 9); p += 9;
        String fname = parseAscii(buffer, p, 25); p += 25;
        String mname = parseAscii(buffer, p, 25); p += 25;
        String lname = parseAscii(buffer, p, 25); p += 25;
        String a1 = parseAscii(buffer, p, 50); p += 50;
        String a2 = parseAscii(buffer, p, 50); p += 50;
        String a3 = parseAscii(buffer, p, 50); p += 50;
        String stateCd = parseAscii(buffer, p, 2); p += 2;
        String countryCd = parseAscii(buffer, p, 3); p += 3;
        String zip = parseAscii(buffer, p, 10); p += 10;
        String ph1 = parseAscii(buffer, p, 15); p += 15;
        String ph2 = parseAscii(buffer, p, 15); p += 15;
        long ssn = parseUnsignedLong(buffer, p, 9); p += 9;
        String govtId = parseAscii(buffer, p, 20); p += 20;
        String dob = parseAscii(buffer, p, 10); p += 10;
        String eft = parseAscii(buffer, p, 10); p += 10;
        char priInd = (char) (buffer[p] & 0xFF); p += 1;
        int fico = (int) parseUnsignedLong(buffer, p, 3); p += 3;
        byte[] filler = Arrays.copyOfRange(buffer, p, p + LEN_FILLER);

        return new CustomerRecord(
                custId, fname, mname, lname, a1, a2, a3, stateCd, countryCd, zip,
                ph1, ph2, ssn, govtId, dob, eft, priInd, fico, filler);
    }

    /** Encodes this record as a 500-byte buffer. */
    public byte[] encode() {
        byte[] buffer = new byte[RECORD_LENGTH];
        Arrays.fill(buffer, (byte) ' ');
        int p = 0;
        writeUnsignedLong(buffer, p, 9, custId); p += 9;
        writeAscii(buffer, p, 25, custFirstName); p += 25;
        writeAscii(buffer, p, 25, custMiddleName); p += 25;
        writeAscii(buffer, p, 25, custLastName); p += 25;
        writeAscii(buffer, p, 50, custAddrLine1); p += 50;
        writeAscii(buffer, p, 50, custAddrLine2); p += 50;
        writeAscii(buffer, p, 50, custAddrLine3); p += 50;
        writeAscii(buffer, p, 2, custAddrStateCd); p += 2;
        writeAscii(buffer, p, 3, custAddrCountryCd); p += 3;
        writeAscii(buffer, p, 10, custAddrZip); p += 10;
        writeAscii(buffer, p, 15, custPhoneNum1); p += 15;
        writeAscii(buffer, p, 15, custPhoneNum2); p += 15;
        writeUnsignedLong(buffer, p, 9, custSsn); p += 9;
        writeAscii(buffer, p, 20, custGovtIssuedId); p += 20;
        writeAscii(buffer, p, 10, custDobYyyyMmDd); p += 10;
        writeAscii(buffer, p, 10, custEftAccountId); p += 10;
        buffer[p] = (byte) custPriCardHolderInd; p += 1;
        writeUnsignedLong(buffer, p, 3, custFicoCreditScore); p += 3;
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
