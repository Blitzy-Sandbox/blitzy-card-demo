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
 * Domain record translated from the COBOL {@code SEC-USER-DATA} copybook at
 * {@code app/cpy/CSUSR01Y.cpy} (record length 80 bytes).
 *
 * <pre>{@code
 * 01 SEC-USER-DATA.
 *    05 SEC-USR-ID                 PIC X(08).
 *    05 SEC-USR-FNAME              PIC X(20).
 *    05 SEC-USR-LNAME              PIC X(20).
 *    05 SEC-USR-PWD                PIC X(08).
 *    05 SEC-USR-TYPE               PIC X(01).
 *    05 SEC-USR-FILLER             PIC X(23).
 * }</pre>
 *
 * <h2>Plaintext password preservation</h2>
 * Per AAP &sect;0.1.3 and &sect;0.7.2, the COBOL system stores user
 * passwords in plaintext as {@code PIC X(08)}. This refactor preserves that
 * behavior for byte-for-byte parity with the COBOL baseline. Moving to a
 * password-hashing scheme (BCrypt / Argon2) is a separate enhancement
 * tracked in {@code MIGRATION_NOTES.md} &sect;1.5.1.
 *
 * <p>The password is masked in {@link #toString()} as {@code ********}
 * to prevent accidental logging of credentials.
 *
 * @see UnusedRecord  alternate layout with identical field structure
 */
@CobolProgram(
        value = "CSUSR01Y",
        sourcePath = "app/cpy/CSUSR01Y.cpy",
        notes = "80-byte SEC-USER-DATA; plaintext password preserved (masked in toString); 23-byte FILLER"
)
public record SecUserData(
        String secUsrId,
        String secUsrFname,
        String secUsrLname,
        String secUsrPwd,
        String secUsrType,
        byte[] filler) {

    public static final int RECORD_LENGTH = 80;
    public static final int OFFSET_USR_ID = 0;
    public static final int LEN_USR_ID = 8;
    public static final int OFFSET_USR_FNAME = 8;
    public static final int LEN_USR_FNAME = 20;
    public static final int OFFSET_USR_LNAME = 28;
    public static final int LEN_USR_LNAME = 20;
    public static final int OFFSET_USR_PWD = 48;
    public static final int LEN_USR_PWD = 8;
    public static final int OFFSET_USR_TYPE = 56;
    public static final int LEN_USR_TYPE = 1;
    public static final int OFFSET_USR_FILLER = 57;
    public static final int LEN_USR_FILLER = 23;

    private static final String PASSWORD_MASK = "********";

    public SecUserData {
        secUsrId = secUsrId == null ? "" : secUsrId;
        secUsrFname = secUsrFname == null ? "" : secUsrFname;
        secUsrLname = secUsrLname == null ? "" : secUsrLname;
        secUsrPwd = secUsrPwd == null ? "" : secUsrPwd;
        secUsrType = secUsrType == null ? "" : secUsrType;
        Objects.requireNonNull(filler, "filler");
        if (filler.length != LEN_USR_FILLER) {
            throw new IllegalArgumentException(
                    "filler must be " + LEN_USR_FILLER + " bytes, got " + filler.length);
        }
        filler = filler.clone();
    }

    @Override
    public byte[] filler() {
        return filler.clone();
    }

    /**
     * Returns the user ID with trailing spaces removed.
     */
    public String userId() {
        return secUsrId.stripTrailing();
    }

    /**
     * Returns the first name with trailing spaces removed.
     */
    public String firstName() {
        return secUsrFname.stripTrailing();
    }

    /**
     * Returns the last name with trailing spaces removed.
     */
    public String lastName() {
        return secUsrLname.stripTrailing();
    }

    /**
     * Returns {@code true} when the supplied candidate matches the stored
     * password byte-for-byte. Trailing spaces are NOT stripped because the
     * COBOL field is fixed-width {@code PIC X(08)} and the comparison must
     * preserve every byte.
     */
    public boolean passwordMatches(String candidate) {
        if (candidate == null) {
            return false;
        }
        // Right-pad candidate to 8 characters with spaces to mirror COBOL
        String padded = candidate.length() < LEN_USR_PWD
                ? candidate + " ".repeat(LEN_USR_PWD - candidate.length())
                : candidate.substring(0, Math.min(candidate.length(), LEN_USR_PWD));
        return padded.equals(secUsrPwd);
    }

    /**
     * Returns {@code true} for administrative users (type 'A'), {@code false} otherwise.
     */
    public boolean isAdmin() {
        return secUsrType.length() >= 1 && secUsrType.charAt(0) == 'A';
    }

    @Override
    public String toString() {
        return "SecUserData[usrId=" + secUsrId.stripTrailing()
                + ", fname=" + secUsrFname.stripTrailing()
                + ", lname=" + secUsrLname.stripTrailing()
                + ", pwd=" + PASSWORD_MASK
                + ", type=" + secUsrType
                + ", filler=<" + LEN_USR_FILLER + " bytes>]";
    }

    public static SecUserData parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "buffer must be " + RECORD_LENGTH + " bytes, got " + buffer.length);
        }
        String usrId = parseAscii(buffer, OFFSET_USR_ID, LEN_USR_ID);
        String fname = parseAscii(buffer, OFFSET_USR_FNAME, LEN_USR_FNAME);
        String lname = parseAscii(buffer, OFFSET_USR_LNAME, LEN_USR_LNAME);
        String pwd = parseAscii(buffer, OFFSET_USR_PWD, LEN_USR_PWD);
        String type = parseAscii(buffer, OFFSET_USR_TYPE, LEN_USR_TYPE);
        byte[] filler = Arrays.copyOfRange(buffer, OFFSET_USR_FILLER, OFFSET_USR_FILLER + LEN_USR_FILLER);
        return new SecUserData(usrId, fname, lname, pwd, type, filler);
    }

    public byte[] encode() {
        byte[] buffer = new byte[RECORD_LENGTH];
        Arrays.fill(buffer, (byte) ' ');
        writeAscii(buffer, OFFSET_USR_ID, LEN_USR_ID, secUsrId);
        writeAscii(buffer, OFFSET_USR_FNAME, LEN_USR_FNAME, secUsrFname);
        writeAscii(buffer, OFFSET_USR_LNAME, LEN_USR_LNAME, secUsrLname);
        writeAscii(buffer, OFFSET_USR_PWD, LEN_USR_PWD, secUsrPwd);
        writeAscii(buffer, OFFSET_USR_TYPE, LEN_USR_TYPE, secUsrType);
        System.arraycopy(filler, 0, buffer, OFFSET_USR_FILLER, LEN_USR_FILLER);
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
        byte[] filler = new byte[LEN_USR_FILLER];
        Arrays.fill(filler, (byte) ' ');
        return filler;
    }
}
