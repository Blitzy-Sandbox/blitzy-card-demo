/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.text;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Consolidated system-message constants translated from
 * {@code app/cpy/CSMSG01Y.cpy} (CCDA-COMMON-MESSAGES) and
 * {@code app/cpy/CSMSG02Y.cpy} (ABEND-DATA work area).
 *
 * <pre>{@code
 * 01 CCDA-COMMON-MESSAGES.
 *    05 CCDA-MSG-THANK-YOU   PIC X(50)
 *       VALUE 'Thank you for using CardDemo application...      '.
 *    05 CCDA-MSG-INVALID-KEY PIC X(50)
 *       VALUE 'Invalid key pressed. Please see below...         '.
 *
 * 01 ABEND-DATA.
 *    05 ABEND-CODE      PIC X(4)  VALUE SPACES.
 *    05 ABEND-CULPRIT   PIC X(8)  VALUE SPACES.
 *    05 ABEND-REASON    PIC X(50) VALUE SPACES.
 *    05 ABEND-MSG       PIC X(72) VALUE SPACES.
 * }</pre>
 *
 * <p>Common messages are exposed as 50-character constants (matching the
 * COBOL fixed widths). The ABEND-DATA structure is rendered as a nested
 * record so the abend-routine translation can populate it.
 */
@CobolProgram(
        value = "CSMSG01Y/CSMSG02Y",
        sourcePath = "app/cpy/CSMSG01Y.cpy, app/cpy/CSMSG02Y.cpy",
        notes = "Common messages (CSMSG01Y) + ABEND-DATA work area (CSMSG02Y); fixed-width strings preserved"
)
public final class SystemMessages {

    public static final int LEN_MSG = 50;

    /** CCDA-MSG-THANK-YOU — 50 chars including trailing spaces. */
    public static final String MSG_THANK_YOU = "Thank you for using CardDemo application...      ";

    /** CCDA-MSG-INVALID-KEY — 50 chars including trailing spaces. */
    public static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...         ";

    private SystemMessages() {
        // Utility class
    }

    static {
        if (MSG_THANK_YOU.length() != LEN_MSG) {
            throw new AssertionError("MSG_THANK_YOU length != " + LEN_MSG);
        }
        if (MSG_INVALID_KEY.length() != LEN_MSG) {
            throw new AssertionError("MSG_INVALID_KEY length != " + LEN_MSG);
        }
    }

    /**
     * ABEND-DATA work area (134 bytes total: 4 + 8 + 50 + 72) from CSMSG02Y.
     */
    public record AbendData(String abendCode, String abendCulprit, String abendReason, String abendMsg) {

        public static final int LEN_CODE = 4;
        public static final int LEN_CULPRIT = 8;
        public static final int LEN_REASON = 50;
        public static final int LEN_MSG_FIELD = 72;
        public static final int RECORD_LENGTH = LEN_CODE + LEN_CULPRIT + LEN_REASON + LEN_MSG_FIELD;

        public AbendData {
            abendCode = abendCode == null ? "" : abendCode;
            abendCulprit = abendCulprit == null ? "" : abendCulprit;
            abendReason = abendReason == null ? "" : abendReason;
            abendMsg = abendMsg == null ? "" : abendMsg;
        }

        /** Returns the empty (all-spaces) initial value mirroring COBOL VALUE SPACES. */
        public static AbendData empty() {
            return new AbendData(
                    " ".repeat(LEN_CODE),
                    " ".repeat(LEN_CULPRIT),
                    " ".repeat(LEN_REASON),
                    " ".repeat(LEN_MSG_FIELD));
        }

        public static AbendData parse(byte[] buffer) {
            Objects.requireNonNull(buffer, "buffer");
            if (buffer.length != RECORD_LENGTH) {
                throw new IllegalArgumentException(
                        "buffer must be " + RECORD_LENGTH + " bytes, got " + buffer.length);
            }
            int p = 0;
            String code = new String(buffer, p, LEN_CODE, StandardCharsets.US_ASCII); p += LEN_CODE;
            String culprit = new String(buffer, p, LEN_CULPRIT, StandardCharsets.US_ASCII); p += LEN_CULPRIT;
            String reason = new String(buffer, p, LEN_REASON, StandardCharsets.US_ASCII); p += LEN_REASON;
            String msg = new String(buffer, p, LEN_MSG_FIELD, StandardCharsets.US_ASCII);
            return new AbendData(code, culprit, reason, msg);
        }

        public byte[] encode() {
            byte[] buffer = new byte[RECORD_LENGTH];
            Arrays.fill(buffer, (byte) ' ');
            int p = 0;
            writeFixed(buffer, p, LEN_CODE, abendCode); p += LEN_CODE;
            writeFixed(buffer, p, LEN_CULPRIT, abendCulprit); p += LEN_CULPRIT;
            writeFixed(buffer, p, LEN_REASON, abendReason); p += LEN_REASON;
            writeFixed(buffer, p, LEN_MSG_FIELD, abendMsg);
            return buffer;
        }

        private static void writeFixed(byte[] buffer, int offset, int length, String value) {
            byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.US_ASCII);
            int copyLen = Math.min(bytes.length, length);
            System.arraycopy(bytes, 0, buffer, offset, copyLen);
            for (int i = copyLen; i < length; i++) {
                buffer[offset + i] = (byte) ' ';
            }
        }
    }
}
