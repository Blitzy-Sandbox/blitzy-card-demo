/*
 * DailyTransactionReader.java — Spring Batch ItemReader for S3 Daily Transaction File
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 *   - CBTRN01C.cbl (491 lines) — Daily Transaction Validation Driver
 *   - CVTRA06Y.cpy — DALYTRAN-RECORD layout (350 bytes, 13 data fields + FILLER)
 *
 * This class replaces the COBOL sequential READ DALYTRAN-FILE pattern from
 * CBTRN01C.cbl. The original program opens a sequential VSAM file (DALYTRAN.PS)
 * and reads 350-byte fixed-width records into the DALYTRAN-RECORD structure
 * defined by CVTRA06Y.cpy. This Java implementation downloads the equivalent
 * ASCII file from AWS S3 and parses each line using the exact same field offsets.
 *
 * COBOL Paragraph → Java Method Mapping:
 *   0000-DALYTRAN-OPEN        → openDailyTransactionFile()
 *   1000-DALYTRAN-GET-NEXT    → read()
 *   9000-DALYTRAN-CLOSE       → closeDailyTransactionFile()
 *   Z-ABEND-PROGRAM           → IllegalStateException throw
 *   Z-DISPLAY-IO-STATUS       → SLF4J error logging
 *
 * Fixed-Width Record Layout (CVTRA06Y.cpy, 350 bytes total):
 *   Offset   Len  COBOL Field             PIC Clause        Java Type
 *   0        16   DALYTRAN-ID             PIC X(16)         String
 *   16        2   DALYTRAN-TYPE-CD        PIC X(02)         String
 *   18        4   DALYTRAN-CAT-CD         PIC 9(04)         Short
 *   22       10   DALYTRAN-SOURCE         PIC X(10)         String
 *   32      100   DALYTRAN-DESC           PIC X(100)        String
 *   132      11   DALYTRAN-AMT            PIC S9(09)V99     BigDecimal (DISPLAY, sign overpunch)
 *   143       9   DALYTRAN-MERCHANT-ID    PIC 9(09)         String
 *   152      50   DALYTRAN-MERCHANT-NAME  PIC X(50)         String
 *   202      50   DALYTRAN-MERCHANT-CITY  PIC X(50)         String
 *   252      10   DALYTRAN-MERCHANT-ZIP   PIC X(10)         String
 *   262      16   DALYTRAN-CARD-NUM       PIC X(16)         String
 *   278      26   DALYTRAN-ORIG-TS        PIC X(26)         LocalDateTime
 *   304      26   DALYTRAN-PROC-TS        PIC X(26)         LocalDateTime
 *   330      20   FILLER                  PIC X(20)         (ignored)
 *
 * CRITICAL: PIC S9(09)V99 in DISPLAY format = 11 characters (9 integer + 2 decimal,
 * sign overpunched on trailing digit using EBCDIC-to-ASCII zone encoding).
 * Amount MUST be parsed as BigDecimal — zero float/double (AAP §0.8.2).
 *
 * Decision Log References:
 *   D-003: S3 versioned objects for GDG replacement (DALYTRAN.PS → S3 object)
 */
package com.cardemo.batch.readers;

import com.cardemo.model.entity.DailyTransaction;

import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Spring Batch {@link ItemReader} that reads daily transaction records from an
 * AWS S3 object, parsing each line as a 350-byte fixed-width record matching
 * the COBOL {@code DALYTRAN-RECORD} layout from {@code CVTRA06Y.cpy}.
 *
 * <p>This reader is the primary data input component for the
 * {@code DailyTransactionPostingJob} (POSTTRAN batch pipeline stage). It replaces
 * the COBOL sequential file I/O pattern:</p>
 * <pre>
 * OPEN INPUT DALYTRAN-FILE              → openDailyTransactionFile() [lazy on first read()]
 * READ DALYTRAN-FILE INTO DALYTRAN-RECORD → read() [returns DailyTransaction or null for EOF]
 * CLOSE DALYTRAN-FILE                   → closeDailyTransactionFile() [at EOF or on error]
 * </pre>
 *
 * <h3>COBOL Zoned-Decimal Amount Parsing</h3>
 * <p>The {@code DALYTRAN-AMT} field uses COBOL DISPLAY format with trailing sign
 * overpunch encoding. The last character encodes both the digit value and the
 * sign of the number. Positive overpunch: {=0, A=1, B=2, C=3, D=4, E=5, F=6,
 * G=7, H=8, I=9. Negative overpunch: }=0, J=1, K=2, L=3, M=4, N=5, O=6,
 * P=7, Q=8, R=9. The implied decimal point (V99) places 2 digits after the
 * decimal. All amounts are parsed as {@link BigDecimal} per AAP §0.8.2.</p>
 *
 * <h3>S3 Integration</h3>
 * <p>The S3 bucket name and object key are configurable via Spring properties
 * ({@code carddemo.aws.s3.batch-input-bucket} and
 * {@code carddemo.aws.s3.daily-transaction-key}). Must be testable against
 * LocalStack with zero live AWS dependencies (AAP §0.7.7).</p>
 *
 * @see com.cardemo.model.entity.DailyTransaction
 * @see com.cardemo.config.AwsConfig
 */
@Component
public class DailyTransactionReader implements ItemReader<DailyTransaction> {

    private static final Logger log = LoggerFactory.getLogger(DailyTransactionReader.class);

    // =========================================================================
    // Fixed-Width Record Constants — CVTRA06Y.cpy (DALYTRAN-RECORD, 350 bytes)
    // =========================================================================
    // PIC S9(09)V99 in COBOL DISPLAY format = 11 characters:
    //   9 integer digits + 2 decimal digits = 11 total
    //   Sign is overpunched on the trailing (last) character.
    // This means the amount field is 11 chars, NOT 12.
    // =========================================================================

    /** Total record length in characters matching COBOL FD (FD-TRAN-ID X(16) + FD-CUST-DATA X(334) = 350). */
    private static final int RECORD_LENGTH = 350;

    // --- Field offset constants (0-based, for Java String.substring(start, end)) ---

    /** DALYTRAN-ID: PIC X(16) — Transaction identifier. */
    private static final int ID_OFFSET = 0;
    private static final int ID_END = 16;

    /** DALYTRAN-TYPE-CD: PIC X(02) — Transaction type code (e.g., "01"=Sale, "03"=Return). */
    private static final int TYPE_CD_OFFSET = 16;
    private static final int TYPE_CD_END = 18;

    /** DALYTRAN-CAT-CD: PIC 9(04) — Transaction category code. */
    private static final int CAT_CD_OFFSET = 18;
    private static final int CAT_CD_END = 22;

    /** DALYTRAN-SOURCE: PIC X(10) — Transaction source (e.g., "POS TERM", "OPERATOR"). */
    private static final int SOURCE_OFFSET = 22;
    private static final int SOURCE_END = 32;

    /** DALYTRAN-DESC: PIC X(100) — Transaction description. */
    private static final int DESC_OFFSET = 32;
    private static final int DESC_END = 132;

    /** DALYTRAN-AMT: PIC S9(09)V99 — Amount in DISPLAY format with sign overpunch (11 chars). */
    private static final int AMT_OFFSET = 132;
    private static final int AMT_END = 143;

    /** DALYTRAN-MERCHANT-ID: PIC 9(09) — Merchant identifier. */
    private static final int MERCHANT_ID_OFFSET = 143;
    private static final int MERCHANT_ID_END = 152;

    /** DALYTRAN-MERCHANT-NAME: PIC X(50) — Merchant name. */
    private static final int MERCHANT_NAME_OFFSET = 152;
    private static final int MERCHANT_NAME_END = 202;

    /** DALYTRAN-MERCHANT-CITY: PIC X(50) — Merchant city. */
    private static final int MERCHANT_CITY_OFFSET = 202;
    private static final int MERCHANT_CITY_END = 252;

    /** DALYTRAN-MERCHANT-ZIP: PIC X(10) — Merchant ZIP code. */
    private static final int MERCHANT_ZIP_OFFSET = 252;
    private static final int MERCHANT_ZIP_END = 262;

    /** DALYTRAN-CARD-NUM: PIC X(16) — Card number for cross-reference lookup. */
    private static final int CARD_NUM_OFFSET = 262;
    private static final int CARD_NUM_END = 278;

    /** DALYTRAN-ORIG-TS: PIC X(26) — Origination timestamp. */
    private static final int ORIG_TS_OFFSET = 278;
    private static final int ORIG_TS_END = 304;

    /** DALYTRAN-PROC-TS: PIC X(26) — Processing timestamp. */
    private static final int PROC_TS_OFFSET = 304;
    private static final int PROC_TS_END = 330;

    // FILLER: PIC X(20) at offset 330-349 — ignored (COBOL record padding)

    // =========================================================================
    // Timestamp Format — matches actual ASCII data file format
    // =========================================================================

    /**
     * Primary timestamp format matching the ASCII daily transaction file.
     * Example: {@code 2022-06-10 19:27:53.000000} (26 characters).
     */
    private static final DateTimeFormatter PRIMARY_TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /**
     * Fallback timestamp format for COBOL-style timestamps.
     * Example: {@code 2022-07-18-10.30.00.000000} (26 characters).
     */
    private static final DateTimeFormatter COBOL_TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    // =========================================================================
    // COBOL Zoned-Decimal Overpunch Lookup Tables
    // =========================================================================
    // In COBOL DISPLAY format with PIC S9(n)V99, the sign is encoded in the
    // zone nibble of the trailing (last) byte. When converted to ASCII, this
    // produces alphabetic characters instead of digits for the last position.
    //
    // Positive trailing overpunch: {=0 A=1 B=2 C=3 D=4 E=5 F=6 G=7 H=8 I=9
    // Negative trailing overpunch: }=0 J=1 K=2 L=3 M=4 N=5 O=6 P=7 Q=8 R=9
    // Plain digits 0-9 are treated as unsigned (positive).
    // =========================================================================

    /** Positive overpunch characters mapping to digits 0-9. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** Negative overpunch characters mapping to digits 0-9. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /** Number of implied decimal places for PIC S9(09)V99. */
    private static final int IMPLIED_DECIMAL_PLACES = 2;

    // =========================================================================
    // Spring DI — S3 Client and Configuration
    // =========================================================================

    /** AWS S3 client provided by {@code AwsConfig.s3Client()} bean. */
    @Autowired
    private S3Client s3Client;

    /**
     * S3 bucket name for batch input files. Defaults to {@code carddemo-batch-input}.
     * Maps from GDG {@code AWS.M2.CARDDEMO.TRANSACT.DALY} via DEFGDGB.jcl.
     */
    @Value("${carddemo.aws.s3.batch-input-bucket:carddemo-batch-input}")
    private String bucketName;

    /**
     * S3 object key for the daily transaction file. Defaults to {@code dailytran.txt}.
     * Maps from JCL DD {@code DALYTRAN DD} in POSTTRAN.jcl.
     */
    @Value("${carddemo.aws.s3.daily-transaction-key:dailytran.txt}")
    private String objectKey;

    // =========================================================================
    // Reader State
    // =========================================================================

    /** Buffered reader wrapping the S3 object input stream. */
    private BufferedReader reader;

    /** Lazy initialization flag — true after first successful S3 download. */
    private boolean initialized;

    /** Running count of records successfully read for logging and diagnostics. */
    private long recordCount;

    // =========================================================================
    // ItemReader<DailyTransaction> Implementation
    // =========================================================================

    /**
     * Reads the next daily transaction record from the S3 file.
     *
     * <p>Maps to COBOL paragraph {@code 1000-DALYTRAN-GET-NEXT} (CBTRN01C.cbl
     * lines 202–225). On the first invocation, lazily initializes the S3 file
     * stream (mapping to {@code 0000-DALYTRAN-OPEN}). Each subsequent call reads
     * the next line and parses it as a 350-byte fixed-width record.</p>
     *
     * <p>Returns {@code null} when the end of the file is reached, signaling
     * Spring Batch to end the step (equivalent to COBOL FILE STATUS '10' / EOF).
     * On read errors, throws an exception (mapping to COBOL {@code Z-ABEND-PROGRAM}).</p>
     *
     * @return a parsed {@link DailyTransaction} entity, or {@code null} for EOF
     * @throws Exception if S3 access fails or a record cannot be parsed
     */
    @Override
    public DailyTransaction read() throws Exception {
        // Lazy initialization — maps to COBOL 0000-DALYTRAN-OPEN
        if (!initialized) {
            openDailyTransactionFile();
        }

        // Read next line — maps to COBOL READ DALYTRAN-FILE INTO DALYTRAN-RECORD
        String line = reader.readLine();

        if (line == null) {
            // EOF — maps to COBOL DALYTRAN-STATUS = '10', MOVE 'Y' TO END-OF-DAILY-TRANS-FILE
            log.info("END OF EXECUTION OF PROGRAM CBTRN01C — {} records read from s3://{}/{}",
                    recordCount, bucketName, objectKey);
            closeDailyTransactionFile();
            return null;
        }

        // Skip blank lines that may appear in the file
        if (line.isBlank()) {
            log.debug("Skipping blank line at record position {}", recordCount + 1);
            return read();
        }

        recordCount++;
        DailyTransaction txn = parseFixedWidthRecord(line, recordCount);
        log.debug("Read daily transaction: ID={}, CARD={}, AMT={}",
                txn.getDalytranId(), txn.getDalytranCardNum(), txn.getDalytranAmt());
        return txn;
    }

    // =========================================================================
    // S3 File Lifecycle — Open / Close
    // =========================================================================

    /**
     * Opens the daily transaction file from S3 for sequential reading.
     *
     * <p>Maps to COBOL paragraph {@code 0000-DALYTRAN-OPEN} (CBTRN01C.cbl
     * lines 252–268):</p>
     * <pre>
     * 0000-DALYTRAN-OPEN.
     *     MOVE 8 TO APPL-RESULT.
     *     OPEN INPUT DALYTRAN-FILE
     *     IF DALYTRAN-STATUS = '00' MOVE 0 TO APPL-RESULT
     *     ELSE MOVE 12 TO APPL-RESULT ... PERFORM Z-ABEND-PROGRAM
     * </pre>
     *
     * <p>Downloads the S3 object and wraps the response in a {@link BufferedReader}
     * with UTF-8 encoding for line-oriented sequential reading.</p>
     *
     * @throws IllegalStateException if the S3 object does not exist or cannot be accessed
     */
    private void openDailyTransactionFile() {
        log.info("START OF EXECUTION OF PROGRAM CBTRN01C");
        log.info("Opening daily transaction file from S3: s3://{}/{}", bucketName, objectKey);

        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();

            ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(request);
            reader = new BufferedReader(new InputStreamReader(s3Object, StandardCharsets.UTF_8));
            initialized = true;

            log.info("Successfully opened daily transaction file from S3: s3://{}/{}",
                    bucketName, objectKey);
        } catch (NoSuchKeyException ex) {
            // Maps to COBOL: DISPLAY 'ERROR OPENING DAILY TRANSACTION FILE' → Z-ABEND-PROGRAM
            log.error("ERROR OPENING DAILY TRANSACTION FILE — S3 object not found: s3://{}/{}",
                    bucketName, objectKey);
            log.error("FILE STATUS IS: NoSuchKeyException — {}", ex.getMessage());
            throw new IllegalStateException(
                    "Daily transaction file not found in S3: s3://" + bucketName + "/" + objectKey, ex);
        } catch (Exception ex) {
            // Maps to COBOL: DISPLAY 'ERROR OPENING DAILY TRANSACTION FILE' → Z-ABEND-PROGRAM
            log.error("ERROR OPENING DAILY TRANSACTION FILE — S3 access failure: s3://{}/{}",
                    bucketName, objectKey);
            log.error("FILE STATUS IS: {} — {}", ex.getClass().getSimpleName(), ex.getMessage());
            throw new IllegalStateException(
                    "Failed to open daily transaction file from S3: s3://" + bucketName + "/" + objectKey, ex);
        }
    }

    /**
     * Closes the daily transaction file reader and releases resources.
     *
     * <p>Maps to COBOL paragraph {@code 9000-DALYTRAN-CLOSE} (CBTRN01C.cbl
     * lines 361–377). Closes the {@link BufferedReader} wrapping the S3 input
     * stream. Errors during close are logged but not propagated, matching the
     * COBOL pattern of non-fatal close error handling.</p>
     */
    private void closeDailyTransactionFile() {
        if (reader != null) {
            try {
                reader.close();
                log.debug("Closed daily transaction file reader");
            } catch (Exception ex) {
                log.warn("Error closing daily transaction file reader: {}", ex.getMessage());
            } finally {
                reader = null;
            }
        }
    }

    // =========================================================================
    // Fixed-Width Record Parser
    // =========================================================================

    /**
     * Parses a single fixed-width record line into a {@link DailyTransaction} entity.
     *
     * <p>Extracts all 13 data fields from the 350-character record line using the
     * exact offsets defined in {@code CVTRA06Y.cpy}. Lines shorter than 350
     * characters are right-padded with spaces to accommodate trailing-whitespace
     * truncation that may occur during file transfer.</p>
     *
     * <p>Field extraction uses {@link String#substring(int, int)} with the offset
     * constants defined in this class. Each string field is trimmed of trailing
     * whitespace. The amount field is decoded from COBOL zoned-decimal overpunch
     * format into {@link BigDecimal}. Timestamp fields are parsed into
     * {@link LocalDateTime} with graceful fallback to {@code null} for malformed
     * or empty values.</p>
     *
     * @param line        the raw fixed-width record line from the S3 file
     * @param recordNumber the 1-based record number for error reporting
     * @return a populated {@link DailyTransaction} entity
     * @throws IllegalArgumentException if the amount field cannot be parsed
     */
    private DailyTransaction parseFixedWidthRecord(String line, long recordNumber) {
        // Pad line to RECORD_LENGTH if shorter (handles trailing-whitespace truncation)
        String paddedLine = padToLength(line, RECORD_LENGTH);

        DailyTransaction txn = new DailyTransaction();

        // DALYTRAN-ID: PIC X(16) at offset 0
        txn.setDalytranId(paddedLine.substring(ID_OFFSET, ID_END).trim());

        // DALYTRAN-TYPE-CD: PIC X(02) at offset 16
        txn.setDalytranTypeCd(paddedLine.substring(TYPE_CD_OFFSET, TYPE_CD_END).trim());

        // DALYTRAN-CAT-CD: PIC 9(04) at offset 18 — entity field is Short
        String catCdStr = paddedLine.substring(CAT_CD_OFFSET, CAT_CD_END).trim();
        txn.setDalytranCatCd(parseCategoryCode(catCdStr, recordNumber));

        // DALYTRAN-SOURCE: PIC X(10) at offset 22
        txn.setDalytranSource(paddedLine.substring(SOURCE_OFFSET, SOURCE_END).trim());

        // DALYTRAN-DESC: PIC X(100) at offset 32
        txn.setDalytranDesc(paddedLine.substring(DESC_OFFSET, DESC_END).trim());

        // DALYTRAN-AMT: PIC S9(09)V99 at offset 132 — CRITICAL: BigDecimal only
        String amtStr = paddedLine.substring(AMT_OFFSET, AMT_END);
        txn.setDalytranAmt(parseCobolSignedDecimal(amtStr, IMPLIED_DECIMAL_PLACES, recordNumber));

        // DALYTRAN-MERCHANT-ID: PIC 9(09) at offset 143
        txn.setDalytranMerchantId(paddedLine.substring(MERCHANT_ID_OFFSET, MERCHANT_ID_END).trim());

        // DALYTRAN-MERCHANT-NAME: PIC X(50) at offset 152
        txn.setDalytranMerchantName(paddedLine.substring(MERCHANT_NAME_OFFSET, MERCHANT_NAME_END).trim());

        // DALYTRAN-MERCHANT-CITY: PIC X(50) at offset 202
        txn.setDalytranMerchantCity(paddedLine.substring(MERCHANT_CITY_OFFSET, MERCHANT_CITY_END).trim());

        // DALYTRAN-MERCHANT-ZIP: PIC X(10) at offset 252
        txn.setDalytranMerchantZip(paddedLine.substring(MERCHANT_ZIP_OFFSET, MERCHANT_ZIP_END).trim());

        // DALYTRAN-CARD-NUM: PIC X(16) at offset 262
        txn.setDalytranCardNum(paddedLine.substring(CARD_NUM_OFFSET, CARD_NUM_END).trim());

        // DALYTRAN-ORIG-TS: PIC X(26) at offset 278
        String origTsStr = paddedLine.substring(ORIG_TS_OFFSET, ORIG_TS_END).trim();
        txn.setDalytranOrigTs(parseTimestamp(origTsStr, recordNumber, "DALYTRAN-ORIG-TS"));

        // DALYTRAN-PROC-TS: PIC X(26) at offset 304
        String procTsStr = paddedLine.substring(PROC_TS_OFFSET, PROC_TS_END).trim();
        txn.setDalytranProcTs(parseTimestamp(procTsStr, recordNumber, "DALYTRAN-PROC-TS"));

        // FILLER: PIC X(20) at offset 330 — ignored (COBOL record padding)

        return txn;
    }

    // =========================================================================
    // COBOL Zoned-Decimal Overpunch Decoder
    // =========================================================================

    /**
     * Parses a COBOL PIC S9(n)V99 DISPLAY-format string with trailing sign
     * overpunch into a {@link BigDecimal} value.
     *
     * <p>In COBOL DISPLAY format, the sign of a signed numeric field is encoded
     * in the zone nibble of the trailing (last) byte. When the EBCDIC data is
     * converted to ASCII (as in the {@code app/data/ASCII/dailytran.txt} fixture),
     * the last character becomes an alphabetic overpunch character:</p>
     *
     * <table>
     * <tr><th>Sign</th><th>0</th><th>1</th><th>2</th><th>3</th><th>4</th>
     *     <th>5</th><th>6</th><th>7</th><th>8</th><th>9</th></tr>
     * <tr><td>+</td><td>{</td><td>A</td><td>B</td><td>C</td><td>D</td>
     *     <td>E</td><td>F</td><td>G</td><td>H</td><td>I</td></tr>
     * <tr><td>−</td><td>}</td><td>J</td><td>K</td><td>L</td><td>M</td>
     *     <td>N</td><td>O</td><td>P</td><td>Q</td><td>R</td></tr>
     * </table>
     *
     * <p>The implied decimal point (V99) is applied by dividing the raw integer
     * value by 10^decimalPlaces, preserving exact decimal precision via
     * {@link BigDecimal} arithmetic.</p>
     *
     * <p>Examples from the daily transaction data file:</p>
     * <ul>
     *   <li>{@code 0000005047G} → G=+7 → digits 00000050477 → $504.77</li>
     *   <li>{@code 0000009190}} → }=-0 → digits 00000091900 → -$919.00</li>
     *   <li>{@code 0000003250{} → {=+0 → digits 00000032500 → $325.00</li>
     * </ul>
     *
     * @param raw           the raw COBOL DISPLAY-format string (11 chars for S9(09)V99)
     * @param decimalPlaces number of implied decimal places (2 for V99)
     * @param recordNumber  1-based record number for error reporting
     * @return the parsed {@link BigDecimal} value with correct sign and scale
     * @throws IllegalArgumentException if the field is unparseable
     */
    private BigDecimal parseCobolSignedDecimal(String raw, int decimalPlaces, long recordNumber) {
        if (raw == null || raw.isBlank()) {
            log.warn("Record {}: DALYTRAN-AMT field is blank — defaulting to BigDecimal.ZERO", recordNumber);
            return BigDecimal.ZERO;
        }

        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            log.warn("Record {}: DALYTRAN-AMT field is empty after trim — defaulting to BigDecimal.ZERO",
                    recordNumber);
            return BigDecimal.ZERO;
        }

        // Extract the trailing character (overpunch sign + digit)
        char lastChar = trimmed.charAt(trimmed.length() - 1);
        String leadingDigits = trimmed.substring(0, trimmed.length() - 1);

        // Decode the trailing overpunch character into a digit and sign
        int trailingDigit;
        boolean negative;

        int posIndex = POSITIVE_OVERPUNCH.indexOf(lastChar);
        int negIndex = NEGATIVE_OVERPUNCH.indexOf(lastChar);

        if (posIndex >= 0) {
            // Positive overpunch: {=0, A=1, B=2, ..., I=9
            trailingDigit = posIndex;
            negative = false;
        } else if (negIndex >= 0) {
            // Negative overpunch: }=0, J=1, K=2, ..., R=9
            trailingDigit = negIndex;
            negative = true;
        } else if (Character.isDigit(lastChar)) {
            // Plain digit — unsigned, treated as positive
            trailingDigit = Character.getNumericValue(lastChar);
            negative = false;
        } else {
            // Unrecognized trailing character — log error and attempt recovery
            log.error("Record {}: Unrecognized COBOL overpunch character '{}' in DALYTRAN-AMT field '{}'",
                    recordNumber, lastChar, trimmed);
            throw new IllegalArgumentException(
                    "Record " + recordNumber + ": Cannot parse DALYTRAN-AMT — unrecognized overpunch '"
                            + lastChar + "' in value '" + trimmed + "'");
        }

        // Reconstruct the full digit string: leading digits + decoded trailing digit
        String allDigits = leadingDigits + trailingDigit;

        // Validate that all characters are numeric
        for (int i = 0; i < allDigits.length(); i++) {
            if (!Character.isDigit(allDigits.charAt(i))) {
                log.error("Record {}: Non-numeric character '{}' at position {} in DALYTRAN-AMT digits '{}'",
                        recordNumber, allDigits.charAt(i), i, allDigits);
                throw new IllegalArgumentException(
                        "Record " + recordNumber + ": Non-numeric character in DALYTRAN-AMT: '" + allDigits + "'");
            }
        }

        // Convert to BigDecimal with implied decimal point
        // PIC S9(09)V99: 11 digits total, last 2 are decimal → divide by 100
        BigDecimal value = new BigDecimal(allDigits);
        if (decimalPlaces > 0) {
            value = value.movePointLeft(decimalPlaces);
        }

        // Apply sign
        if (negative) {
            value = value.negate();
        }

        return value;
    }

    // =========================================================================
    // Timestamp Parser
    // =========================================================================

    /**
     * Parses a COBOL 26-character timestamp string into a {@link LocalDateTime}.
     *
     * <p>Attempts parsing with two formats:</p>
     * <ol>
     *   <li>Primary: {@code yyyy-MM-dd HH:mm:ss.SSSSSS} (ASCII data file format,
     *       e.g., {@code 2022-06-10 19:27:53.000000})</li>
     *   <li>Fallback: {@code yyyy-MM-dd-HH.mm.ss.SSSSSS} (COBOL-style format,
     *       e.g., {@code 2022-07-18-10.30.00.000000})</li>
     * </ol>
     *
     * <p>If both formats fail or the input is blank, returns {@code null} with a
     * warning log. This graceful handling prevents malformed timestamps from
     * aborting the entire batch job, matching the COBOL pattern where timestamp
     * fields may be uninitialized (all spaces).</p>
     *
     * @param tsString     the raw timestamp string (trimmed, may be empty)
     * @param recordNumber 1-based record number for error reporting
     * @param fieldName    field name for log messages (e.g., "DALYTRAN-ORIG-TS")
     * @return the parsed {@link LocalDateTime}, or {@code null} if unparseable or blank
     */
    private LocalDateTime parseTimestamp(String tsString, long recordNumber, String fieldName) {
        if (tsString == null || tsString.isBlank()) {
            return null;
        }

        // Try primary format: yyyy-MM-dd HH:mm:ss.SSSSSS
        try {
            return LocalDateTime.parse(tsString, PRIMARY_TS_FORMATTER);
        } catch (DateTimeParseException ignored) {
            // Fall through to try alternate format
        }

        // Try COBOL-style format: yyyy-MM-dd-HH.mm.ss.SSSSSS
        try {
            return LocalDateTime.parse(tsString, COBOL_TS_FORMATTER);
        } catch (DateTimeParseException ignored) {
            // Fall through to null
        }

        // Try ISO format as last resort
        try {
            return LocalDateTime.parse(tsString);
        } catch (DateTimeParseException ex) {
            log.warn("Record {}: Cannot parse {} value '{}' — setting to null. Error: {}",
                    recordNumber, fieldName, tsString, ex.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Utility Methods
    // =========================================================================

    /**
     * Parses a COBOL PIC 9(04) category code string into a {@link Short} value.
     *
     * <p>The COBOL field {@code DALYTRAN-CAT-CD PIC 9(04)} stores a 4-digit
     * unsigned numeric value. The Java entity maps this to {@link Short} to
     * match the DDL {@code SMALLINT} column type.</p>
     *
     * @param catCdStr     the trimmed category code string (e.g., "0001")
     * @param recordNumber 1-based record number for error reporting
     * @return the parsed {@link Short} value, or {@code null} if blank
     */
    private Short parseCategoryCode(String catCdStr, long recordNumber) {
        if (catCdStr == null || catCdStr.isBlank()) {
            log.warn("Record {}: DALYTRAN-CAT-CD is blank — setting to null", recordNumber);
            return null;
        }
        try {
            return Short.parseShort(catCdStr);
        } catch (NumberFormatException ex) {
            log.warn("Record {}: Cannot parse DALYTRAN-CAT-CD '{}' as Short — setting to null. Error: {}",
                    recordNumber, catCdStr, ex.getMessage());
            return null;
        }
    }

    /**
     * Right-pads a string with spaces to the specified minimum length.
     *
     * <p>Handles trailing-whitespace truncation that may occur during ASCII file
     * transfer. If the line is already at or beyond the target length, it is
     * returned unchanged. A warning is logged if padding is needed, as it
     * indicates a potentially truncated record.</p>
     *
     * @param line         the original line from the file
     * @param targetLength the minimum required length (350 for DALYTRAN-RECORD)
     * @return the line padded to at least targetLength characters
     */
    private String padToLength(String line, int targetLength) {
        if (line.length() >= targetLength) {
            return line;
        }

        log.debug("Padding record from {} to {} characters (trailing whitespace may have been truncated)",
                line.length(), targetLength);

        // Efficient padding using StringBuilder
        StringBuilder sb = new StringBuilder(targetLength);
        sb.append(line);
        while (sb.length() < targetLength) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Resets the reader state for reuse in subsequent job executions.
     *
     * <p>Closes any open resources and resets the initialization flag and record
     * counter. This allows the same reader bean to be reused across multiple
     * Spring Batch job executions without requiring a new bean instance.</p>
     */
    public void close() {
        closeDailyTransactionFile();
        initialized = false;
        recordCount = 0;
    }
}
