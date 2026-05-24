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
 * Domain record translated from the COBOL {@code TRNX-RECORD} copybook at
 * {@code app/cpy/COSTM01.CPY} (a transaction-reporting layout). Total record
 * length is 350 bytes (16 + 16 + 318), making it an alternate view of the
 * canonical TRAN-RECORD with the card-number+id pair promoted into a
 * composite key.
 *
 * <pre>{@code
 * 01 TRNX-RECORD.
 *    05 TRNX-KEY.
 *       10 TRNX-CARD-NUM           PIC X(16).
 *       10 TRNX-ID                 PIC X(16).
 *    05 TRNX-REST.
 *       10 TRNX-TYPE-CD            PIC X(02).
 *       10 TRNX-CAT-CD             PIC 9(04).
 *       10 TRNX-SOURCE             PIC X(10).
 *       10 TRNX-DESC               PIC X(100).
 *       10 TRNX-AMT                PIC S9(09)V99.
 *       10 TRNX-MERCHANT-ID        PIC 9(09).
 *       10 TRNX-MERCHANT-NAME      PIC X(50).
 *       10 TRNX-MERCHANT-CITY      PIC X(50).
 *       10 TRNX-MERCHANT-ZIP       PIC X(10).
 *       10 TRNX-ORIG-TS            PIC X(26).
 *       10 TRNX-PROC-TS            PIC X(26).
 *       10 FILLER                  PIC X(20).
 * }</pre>
 *
 * <p>Compared to {@link TranRecord}, TRNX-RECORD places the card number
 * before the transaction id and treats the pair as a composite key. This
 * layout is used by CBSTM03A statement generation per AAP &sect;0.4.1.
 */
@CobolProgram(
        value = "COSTM01",
        sourcePath = "app/cpy/COSTM01.CPY",
        notes = "350-byte TRNX-RECORD; card-num+id composite key; PAN masked in toString()"
)
public record TrnxRecord(
        TrnxKey key,
        String trnxTypeCd,
        int trnxCatCd,
        String trnxSource,
        String trnxDesc,
        BigDecimal trnxAmt,
        long trnxMerchantId,
        String trnxMerchantName,
        String trnxMerchantCity,
        String trnxMerchantZip,
        String trnxOrigTs,
        String trnxProcTs,
        byte[] filler) {

    public static final int RECORD_LENGTH = 350;
    public static final int LEN_FILLER = 20;
    public static final int LEN_TRNX_AMT = 11;
    public static final int MONETARY_SCALE = 2;

    /**
     * Composite key TRNX-KEY (32 bytes): card number + transaction id.
     */
    public record TrnxKey(String trnxCardNum, String trnxId) {

        public static final int LEN_CARD_NUM = 16;
        public static final int LEN_ID = 16;

        public TrnxKey {
            trnxCardNum = trnxCardNum == null ? "" : trnxCardNum;
            trnxId = trnxId == null ? "" : trnxId;
        }

        /** PAN portion masked to last 4 digits. */
        public String maskedCardNum() {
            String trimmed = trnxCardNum.trim();
            if (trimmed.length() <= 4) {
                return trimmed;
            }
            return "*".repeat(trimmed.length() - 4) + trimmed.substring(trimmed.length() - 4);
        }
    }

    public TrnxRecord {
        Objects.requireNonNull(key, "key");
        trnxTypeCd = trnxTypeCd == null ? "" : trnxTypeCd;
        if (trnxCatCd < 0 || trnxCatCd > 9999) {
            throw new IllegalArgumentException("trnxCatCd must be 0..9999");
        }
        trnxSource = trnxSource == null ? "" : trnxSource;
        trnxDesc = trnxDesc == null ? "" : trnxDesc;
        Objects.requireNonNull(trnxAmt, "trnxAmt");
        trnxAmt = Decimals.scaled(trnxAmt, MONETARY_SCALE, Decimals.DEFAULT_MODE);
        if (trnxMerchantId < 0L) {
            throw new IllegalArgumentException("trnxMerchantId must be non-negative");
        }
        trnxMerchantName = trnxMerchantName == null ? "" : trnxMerchantName;
        trnxMerchantCity = trnxMerchantCity == null ? "" : trnxMerchantCity;
        trnxMerchantZip = trnxMerchantZip == null ? "" : trnxMerchantZip;
        trnxOrigTs = trnxOrigTs == null ? "" : trnxOrigTs;
        trnxProcTs = trnxProcTs == null ? "" : trnxProcTs;
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
        return "TrnxRecord[key=(cardNum=" + key.maskedCardNum() + ", id=" + key.trnxId
                + "), typeCd=" + trnxTypeCd
                + ", catCd=" + trnxCatCd
                + ", amt=" + trnxAmt
                + ", merchantId=" + trnxMerchantId
                + ", origTs=" + trnxOrigTs
                + ", procTs=" + trnxProcTs
                + ", filler=<" + LEN_FILLER + " bytes>]";
    }

    public static TrnxRecord parse(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "buffer must be " + RECORD_LENGTH + " bytes, got " + buffer.length);
        }
        int p = 0;
        String cardNum = parseAscii(buffer, p, TrnxKey.LEN_CARD_NUM); p += TrnxKey.LEN_CARD_NUM;
        String id = parseAscii(buffer, p, TrnxKey.LEN_ID); p += TrnxKey.LEN_ID;
        String typeCd = parseAscii(buffer, p, 2); p += 2;
        int catCd = (int) parseUnsignedLong(buffer, p, 4); p += 4;
        String source = parseAscii(buffer, p, 10); p += 10;
        String desc = parseAscii(buffer, p, 100); p += 100;
        BigDecimal amt = Decimals.parseZonedDecimal(buffer, p, LEN_TRNX_AMT, MONETARY_SCALE); p += LEN_TRNX_AMT;
        long merchantId = parseUnsignedLong(buffer, p, 9); p += 9;
        String merchantName = parseAscii(buffer, p, 50); p += 50;
        String merchantCity = parseAscii(buffer, p, 50); p += 50;
        String merchantZip = parseAscii(buffer, p, 10); p += 10;
        String origTs = parseAscii(buffer, p, 26); p += 26;
        String procTs = parseAscii(buffer, p, 26); p += 26;
        byte[] filler = Arrays.copyOfRange(buffer, p, p + LEN_FILLER);
        return new TrnxRecord(new TrnxKey(cardNum, id), typeCd, catCd, source, desc, amt,
                merchantId, merchantName, merchantCity, merchantZip, origTs, procTs, filler);
    }

    public byte[] encode() {
        byte[] buffer = new byte[RECORD_LENGTH];
        Arrays.fill(buffer, (byte) ' ');
        int p = 0;
        writeAscii(buffer, p, TrnxKey.LEN_CARD_NUM, key.trnxCardNum); p += TrnxKey.LEN_CARD_NUM;
        writeAscii(buffer, p, TrnxKey.LEN_ID, key.trnxId); p += TrnxKey.LEN_ID;
        writeAscii(buffer, p, 2, trnxTypeCd); p += 2;
        writeUnsignedLong(buffer, p, 4, trnxCatCd); p += 4;
        writeAscii(buffer, p, 10, trnxSource); p += 10;
        writeAscii(buffer, p, 100, trnxDesc); p += 100;
        byte[] amt = Decimals.encodeZonedDecimal(trnxAmt, LEN_TRNX_AMT, MONETARY_SCALE);
        System.arraycopy(amt, 0, buffer, p, LEN_TRNX_AMT); p += LEN_TRNX_AMT;
        writeUnsignedLong(buffer, p, 9, trnxMerchantId); p += 9;
        writeAscii(buffer, p, 50, trnxMerchantName); p += 50;
        writeAscii(buffer, p, 50, trnxMerchantCity); p += 50;
        writeAscii(buffer, p, 10, trnxMerchantZip); p += 10;
        writeAscii(buffer, p, 26, trnxOrigTs); p += 26;
        writeAscii(buffer, p, 26, trnxProcTs); p += 26;
        System.arraycopy(filler, 0, buffer, p, LEN_FILLER);
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

    public static byte[] emptyFiller() {
        byte[] filler = new byte[LEN_FILLER];
        Arrays.fill(filler, (byte) ' ');
        return filler;
    }
}
