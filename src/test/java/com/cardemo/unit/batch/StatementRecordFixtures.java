/*
 * ******************************************************************
 * Program     : StatementRecordFixtures.java
 * Application : CardDemo
 * Type        : JUnit 5 test support - Java 25 / Spring Boot 3.5.11
 * Function    : Composes the four fixed-width record images the
 *               CBSTM03B file service hands to CBSTM03A, at their
 *               exact declared widths, so that a test can drive the
 *               statement processor through its real parse path
 *               instead of reaching past it. Also supplies the
 *               in-memory FileService.Dataset bindings that stand in
 *               for TRNXFILE, XREFFILE, CUSTFILE and ACCTFILE.
 * Source      : app/cpy/COSTM01.CPY              (TRNX 32+318 = 350)
 *               app/cpy/CVACT03Y.cpy             (XREF 16+9+11 -> 50)
 *               app/cpy/CVCUS01Y.cpy             (CUST 332 -> 500)
 *               app/cpy/CVACT01Y.cpy             (ACCT 122 -> 300)
 *               app/cbl/CBSTM03B.CBL:L136-L221   (OPEN/READ/CLOSE)
 *               app/cbl/CBSTM03A.CBL:L347-L351   ('00' or '04' ok)
 *               app/jcl/CREASTMT.JCL:L53         (sorted ascending)
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.batch;

import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.service.shared.FileService;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Record-image builders and in-memory dataset bindings for the statement pipeline tests.
 *
 * <p>Every image is produced at the exact declared width of its copybook, and every money field is
 * encoded through {@link StatementProcessor#encodeZonedDecimal(BigDecimal, int)} — the single
 * definition of the encode half of the {@code CBSTM03B} record-image contract — so a fixture cannot
 * drift from the decoder it feeds.
 *
 * <p>The bindings are deliberately simple and deterministic: a sequential binding replays a supplied
 * list in order and answers {@code "10"} past the end; a random binding answers from a map and
 * {@code "23"} for an absent key. That is the whole of the {@code CBSTM03B} surface the statement
 * program uses.
 */
final class StatementRecordFixtures {

    /** {@code TRNX-CARD-NUM PIC X(16)}, {@code app/cpy/COSTM01.CPY}. */
    static final int CARD_NUMBER_WIDTH = StatementTransaction.CARD_NUMBER_LENGTH;

    /** {@code FILE STATUS} for a successful operation. */
    static final String STATUS_SUCCESS = FileStatus.SUCCESS.code().orElseThrow();

    /** {@code FILE STATUS} at end of file, {@code WHEN '10'}. */
    static final String STATUS_END_OF_FILE = FileStatus.END_OF_FILE.code().orElseThrow();

    /** {@code FILE STATUS} for an absent key, {@code WHEN '23'}. */
    static final String STATUS_RECORD_NOT_FOUND = FileStatus.RECORD_NOT_FOUND.code().orElseThrow();

    /** Not instantiable. */
    private StatementRecordFixtures() {
        throw new AssertionError("StatementRecordFixtures is a static fixture factory");
    }

    // =============================================================================================
    // Record images.
    // =============================================================================================

    /**
     * Builds one 350-character projected transaction record in the {@code app/cpy/COSTM01.CPY} shape
     * that {@code app/jcl/CREASTMT.JCL} STEP020 REPROs into the work cluster.
     *
     * @param cardNumber the card number, fitted to 16 characters
     * @param transactionId the transaction identifier, fitted to 16 characters
     * @param description the description, fitted to 100 characters
     * @param amount the amount, encoded as trailing zoned-decimal overpunch at scale 2
     * @return exactly {@value StatementTransaction#RECORD_LENGTH} characters
     */
    static String transactionRecord(String cardNumber, String transactionId, String description,
            BigDecimal amount) {
        StringBuilder record = new StringBuilder(StatementTransaction.RECORD_LENGTH);
        record.append(fit(cardNumber, StatementTransaction.CARD_NUMBER_LENGTH));
        record.append(fit(transactionId, StatementTransaction.TRANSACTION_ID_LENGTH));
        record.append(fit("01", StatementTransaction.TYPE_CODE_LENGTH));
        record.append(fit("0001", StatementTransaction.CATEGORY_CODE_LENGTH));
        record.append(fit("POS TERM", StatementTransaction.SOURCE_LENGTH));
        record.append(fit(description, StatementTransaction.DESCRIPTION_LENGTH));
        record.append(StatementProcessor.encodeZonedDecimal(amount, StatementTransaction.AMOUNT_LENGTH));
        record.append(fit("000000001", StatementTransaction.MERCHANT_ID_LENGTH));
        record.append(fit("TEST MERCHANT", StatementTransaction.MERCHANT_NAME_LENGTH));
        record.append(fit("TEST CITY", StatementTransaction.MERCHANT_CITY_LENGTH));
        record.append(fit("10001", StatementTransaction.MERCHANT_ZIP_LENGTH));
        record.append(fit("2024-01-15-10.30.00.0000", StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH));
        record.append(fit("2024-01-15-10.30.00.0000", StatementTransaction.PROCESSING_TIMESTAMP_LENGTH));
        record.append(fit("", StatementTransaction.FILLER_LENGTH));
        return record.toString();
    }

    /**
     * Builds one 50-character cross-reference record in the {@code app/cpy/CVACT03Y.cpy} shape.
     *
     * @param cardNumber the card number, fitted to 16 characters
     * @param customerId the customer identifier, zero-padded to 9 digits
     * @param accountId the account identifier, zero-padded to 11 digits
     * @return exactly 50 characters
     */
    static String crossReferenceRecord(String cardNumber, long customerId, long accountId) {
        return fit(cardNumber, StatementTransaction.CARD_NUMBER_LENGTH)
                + digits(customerId, 9)
                + digits(accountId, 11)
                + " ".repeat(14);
    }

    /**
     * Builds one 500-character customer record in the {@code app/cpy/CVCUS01Y.cpy} shape.
     *
     * <p>The name and address parts are the values a statement renders, so they are the fixture's
     * parameters; every remaining field is a fixed, valid filler value.
     *
     * @param customerId the customer identifier, zero-padded to 9 digits
     * @param firstName {@code CUST-FIRST-NAME X(25)}
     * @param middleName {@code CUST-MIDDLE-NAME X(25)}
     * @param lastName {@code CUST-LAST-NAME X(25)}
     * @param addressLine1 {@code CUST-ADDR-LINE-1 X(50)}
     * @param addressLine2 {@code CUST-ADDR-LINE-2 X(50)}
     * @param addressLine3 {@code CUST-ADDR-LINE-3 X(50)}
     * @param ficoScore {@code CUST-FICO-CREDIT-SCORE 9(03)}
     * @return exactly 500 characters
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    static String customerRecord(long customerId, String firstName, String middleName, String lastName,
            String addressLine1, String addressLine2, String addressLine3, String ficoScore) {
        StringBuilder record = new StringBuilder(500);
        record.append(digits(customerId, 9));
        record.append(fit(firstName, 25));
        record.append(fit(middleName, 25));
        record.append(fit(lastName, 25));
        record.append(fit(addressLine1, 50));
        record.append(fit(addressLine2, 50));
        record.append(fit(addressLine3, 50));
        record.append(fit("NY", 2));
        record.append(fit("USA", 3));
        record.append(fit("10001", 10));
        record.append(fit("(212)555-0100", 15));
        record.append(fit("(212)555-0101", 15));
        record.append(fit("123456789", 9));
        record.append(fit("NY-DL-0099", 20));
        record.append(fit("1980-04-11", 10));
        record.append(fit("0000000001", 10));
        record.append(fit("Y", 1));
        record.append(fit(ficoScore, 3));
        return padRight(record.toString(), 500);
    }

    /**
     * Builds one 300-character account record in the {@code app/cpy/CVACT01Y.cpy} shape, with every
     * {@code PIC S9(10)V99} money field encoded as trailing zoned-decimal overpunch.
     *
     * @param accountId the account identifier, zero-padded to 11 digits
     * @param currentBalance {@code ACCT-CURR-BAL}
     * @return exactly 300 characters
     */
    static String accountRecord(long accountId, BigDecimal currentBalance) {
        StringBuilder record = new StringBuilder(300);
        record.append(digits(accountId, 11));
        record.append("Y");
        record.append(StatementProcessor.encodeZonedDecimal(currentBalance, 12));
        record.append(StatementProcessor.encodeZonedDecimal(new BigDecimal("5000.00"), 12));
        record.append(StatementProcessor.encodeZonedDecimal(new BigDecimal("1000.00"), 12));
        record.append(fit("2020-01-01", 10));
        record.append(fit("2030-01-01", 10));
        record.append(fit("2025-01-01", 10));
        record.append(StatementProcessor.encodeZonedDecimal(new BigDecimal("0.00"), 12));
        record.append(StatementProcessor.encodeZonedDecimal(new BigDecimal("0.00"), 12));
        record.append(fit("10001", 10));
        record.append(fit("DEFAULT", 10));
        return padRight(record.toString(), 300);
    }

    // =============================================================================================
    // Dataset bindings.
    // =============================================================================================

    /**
     * Assembles a {@link FileService} over the four bindings, wired exactly as
     * {@code com.cardemo.config.BatchConfig} wires the production ones.
     *
     * @param transactionRecords the {@code TRNXFILE} records, in sorted order
     * @param crossReferenceRecords the {@code XREFFILE} records, in key order
     * @param customerRecords {@code CUSTFILE}, keyed on the 9-digit customer identifier
     * @param accountRecords {@code ACCTFILE}, keyed on the 11-digit account identifier
     * @return a fully bound service, never {@code null}
     */
    static FileService fileService(List<String> transactionRecords, List<String> crossReferenceRecords,
            Map<String, String> customerRecords, Map<String, String> accountRecords) {
        FileService service = new FileService(new com.cardemo.service.shared.FileStatusMapper(),
                List.of(new SequentialBinding(FileService.Dd.TRNXFILE, transactionRecords),
                        new SequentialBinding(FileService.Dd.XREFFILE, crossReferenceRecords),
                        new RandomBinding(FileService.Dd.CUSTFILE, customerRecords),
                        new RandomBinding(FileService.Dd.ACCTFILE, accountRecords)));
        service.afterPropertiesSet();
        return service;
    }

    /**
     * Assembles a {@link FileService} for a single-account run: one customer, one account, and the
     * supplied transaction records, all reachable from one cross-reference row.
     *
     * @param cardNumber the card number the cross-reference row carries
     * @param transactionRecords the {@code TRNXFILE} records, in sorted order
     * @param customerRecord the single {@code CUSTFILE} record
     * @param accountRecord the single {@code ACCTFILE} record
     * @return a fully bound service, never {@code null}
     */
    static FileService singleAccountFileService(String cardNumber, List<String> transactionRecords,
            String customerRecord, String accountRecord) {
        Map<String, String> customers = new LinkedHashMap<>();
        customers.put(customerRecord.substring(0, 9), customerRecord);
        Map<String, String> accounts = new LinkedHashMap<>();
        accounts.put(accountRecord.substring(0, 11), accountRecord);
        String xref = crossReferenceRecord(cardNumber,
                Long.parseLong(customerRecord.substring(0, 9)),
                Long.parseLong(accountRecord.substring(0, 11)));
        return fileService(transactionRecords, List.of(xref), customers, accounts);
    }

    /**
     * A {@link FileService.AccessMode#SEQUENTIAL} binding that replays a fixed list of records.
     *
     * <p>Reopening rewinds, exactly as {@code OPEN INPUT} rewinds a sequential dataset.
     */
    static final class SequentialBinding implements FileService.Dataset {

        /** The DD this binding answers for. */
        private final FileService.Dd dd;

        /** The records this binding replays, in order. */
        private final List<String> records;

        /** The records not yet returned. */
        private final Deque<String> remaining = new ArrayDeque<>();

        /** Count of {@link #readNext()} calls, so a test can prove nothing is read twice. */
        private int reads;

        /**
         * Creates the binding.
         *
         * @param dd the DD served
         * @param records the records to replay
         */
        SequentialBinding(FileService.Dd dd, List<String> records) {
            this.dd = dd;
            this.records = List.copyOf(records);
        }

        @Override
        public FileService.Dd dd() {
            return this.dd;
        }

        @Override
        public String openInput() {
            this.remaining.clear();
            this.remaining.addAll(this.records);
            return STATUS_SUCCESS;
        }

        @Override
        public String close() {
            this.remaining.clear();
            return STATUS_SUCCESS;
        }

        @Override
        public FileService.DatasetRead readNext() {
            this.reads++;
            if (this.remaining.isEmpty()) {
                return FileService.DatasetRead.withoutRecord(STATUS_END_OF_FILE);
            }
            return FileService.DatasetRead.of(STATUS_SUCCESS, this.remaining.removeFirst());
        }

        @Override
        public FileService.DatasetRead readByKey(String recordKey) {
            throw new UnsupportedOperationException(
                    this.dd.ddName() + " is sequential; a keyed read is not part of its contract");
        }

        /**
         * Reports how many sequential reads this binding has served.
         *
         * @return the read count, never negative
         */
        int reads() {
            return this.reads;
        }
    }

    /**
     * A {@link FileService.AccessMode#RANDOM} binding backed by a map from record key to record.
     */
    static final class RandomBinding implements FileService.Dataset {

        /** The DD this binding answers for. */
        private final FileService.Dd dd;

        /** The records, keyed on the DD's record key. */
        private final Map<String, String> records;

        /** The keys requested, in request order, so a test can prove which keys were read. */
        private final List<String> requestedKeys = new ArrayList<>();

        /**
         * Creates the binding.
         *
         * @param dd the DD served
         * @param records the records, keyed on the DD's record key
         */
        RandomBinding(FileService.Dd dd, Map<String, String> records) {
            this.dd = dd;
            this.records = Map.copyOf(records);
        }

        @Override
        public FileService.Dd dd() {
            return this.dd;
        }

        @Override
        public String openInput() {
            return STATUS_SUCCESS;
        }

        @Override
        public String close() {
            return STATUS_SUCCESS;
        }

        @Override
        public FileService.DatasetRead readNext() {
            throw new UnsupportedOperationException(
                    this.dd.ddName() + " is random; a sequential read is not part of its contract");
        }

        @Override
        public FileService.DatasetRead readByKey(String recordKey) {
            this.requestedKeys.add(recordKey);
            String record = this.records.get(recordKey);
            return record == null
                    ? FileService.DatasetRead.withoutRecord(STATUS_RECORD_NOT_FOUND)
                    : FileService.DatasetRead.of(STATUS_SUCCESS, record);
        }

        /**
         * Reports the keys this binding was asked for, in order.
         *
         * @return an unmodifiable view of the requested keys
         */
        List<String> requestedKeys() {
            return List.copyOf(this.requestedKeys);
        }
    }

    // =============================================================================================
    // Primitives.
    // =============================================================================================

    /**
     * Fits a value to an exact width, truncating a longer one and space-padding a shorter one.
     *
     * @param value the value, never {@code null}
     * @param width the exact target width
     * @return exactly {@code width} characters
     */
    static String fit(String value, int width) {
        return value.length() > width ? value.substring(0, width) : padRight(value, width);
    }

    /**
     * Renders an unsigned value zero-padded to an exact digit count.
     *
     * @param value the value, never negative
     * @param width the exact digit count
     * @return exactly {@code width} ASCII digits
     */
    static String digits(long value, int width) {
        String rendered = Long.toString(value);
        return rendered.length() >= width
                ? rendered.substring(rendered.length() - width)
                : "0".repeat(width - rendered.length()) + rendered;
    }

    /**
     * Right-pads a value with spaces to an exact width.
     *
     * @param value the value, never {@code null}
     * @param width the target width
     * @return at least {@code width} characters
     */
    static String padRight(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }
}
