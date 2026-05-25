/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.adapter.file;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.CustomerRepository;
import com.blitzy.carddemo.domain.record.CustomerRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed implementation of {@link CustomerRepository} reading and
 * writing the {@code CUSTDATA.VSAM.KSDS} dataset as a fixed-width binary
 * file via {@link java.nio.file}.
 *
 * <h2>Source artefact</h2>
 * Implements the access path for the {@code CUSTOMER-RECORD} 01-level
 * group defined in {@code app/cpy/CVCUS01Y.cpy} (500-byte fixed-width
 * record, 9-byte zoned-decimal {@code CUST-ID} primary key at offset 0).
 * The COBOL programs that consume this dataset (CBCUS01C, CBSTM03A,
 * COACTVWC, COACTUPC) issue {@code EXEC CICS READ DATASET(CUSTFILE)} for
 * random key lookup and {@code STARTBR}/{@code READNEXT} for sequential
 * browse.
 *
 * <h2>Record layout</h2>
 * Each record is exactly {@value CustomerRecord#RECORD_LENGTH} bytes; the
 * 9-byte {@code CUST-ID} field is the KSDS primary key at offset 0.
 * The record carries customer demographics (name, address, phone, SSN,
 * FICO, date of birth) plus 168 bytes of FILLER preserved byte-for-byte.
 *
 * <h2>PII masking</h2>
 * Per AAP &sect;0.7.2, {@link CustomerRecord#toString()} masks the SSN
 * to its last 4 digits and the date of birth to year-only. This adapter
 * does not log any PII directly; only the {@code custId} parameter is
 * logged.
 *
 * @see CustomerRepository
 * @see CustomerRecord
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVCUS01Y",
        sourcePath = "app/cpy/CVCUS01Y.cpy",
        translationDate = "2025-10-24",
        notes = "File-backed adapter for the CUSTDATA VSAM KSDS (500-byte CUSTOMER-RECORD; "
                + "9-byte CUST-ID primary key at offset 0). 168-byte trailing FILLER "
                + "preserved verbatim per AAP §0.6.5 byte-for-byte fidelity. No PII "
                + "logging — only custId appears in log lines."
)
public final class FileCustomerRepository implements CustomerRepository {

    private static final Logger LOG = LoggerFactory.getLogger(FileCustomerRepository.class);

    /**
     * The configuration key used by {@link EbcdicTranscoder} to look up
     * the per-file codepage override for this dataset.
     */
    public static final String DATASET_KEY = "custdata";

    private final Path file;
    private final Charset charset;
    private final FixedWidthReader reader;
    private final FixedWidthWriter writer;

    /**
     * Constructs an adapter with the IBM-1047 default codepage.
     *
     * @param file the file path; must not be {@code null}
     */
    public FileCustomerRepository(Path file) {
        this(file, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs an adapter with an explicit charset.
     *
     * @param file    the file path; must not be {@code null}
     * @param charset the charset; must not be {@code null}
     */
    public FileCustomerRepository(Path file, Charset charset) {
        this.file = Objects.requireNonNull(file, "file");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(file, CustomerRecord.RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(file, CustomerRecord.RECORD_LENGTH, charset);
        LOG.debug("FileCustomerRepository configured: file={}, charset={}, recordLength={}",
                file, charset, CustomerRecord.RECORD_LENGTH);
    }

    /**
     * Configured file path.
     *
     * @return the file path; never {@code null}
     */
    public Path file() {
        return file;
    }

    /**
     * Configured charset.
     *
     * @return the charset; never {@code null}
     */
    public Charset charset() {
        return charset;
    }

    @Override
    public Optional<CustomerRecord> findById(long custId) {
        if (custId < 0L) {
            throw new IllegalArgumentException(
                    "custId must be non-negative; got " + custId);
        }
        byte[] key = encodeKey(custId);
        try {
            return reader.findByKey(key, CustomerRecord.CUST_ID_OFFSET)
                    .map(CustomerRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read customer record custId=" + custId
                            + " from " + file, e);
        }
    }

    @Override
    public Stream<CustomerRecord> streamSequential() {
        try {
            return reader.streamSequential().map(CustomerRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open sequential stream over " + file, e);
        }
    }

    @Override
    public void save(CustomerRecord customer) {
        Objects.requireNonNull(customer, "customer");
        byte[] buffer = customer.encode();
        byte[] key = encodeKey(customer.custId());
        try {
            writer.upsert(key, CustomerRecord.CUST_ID_OFFSET, buffer);
            LOG.debug("FileCustomerRepository.save: custId={} written to {}",
                    customer.custId(), file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to save customer record custId=" + customer.custId()
                            + " to " + file, e);
        }
    }

    @Override
    public void delete(long custId) {
        if (custId < 0L) {
            throw new IllegalArgumentException(
                    "custId must be non-negative; got " + custId);
        }
        byte[] key = encodeKey(custId);
        try {
            boolean removed = writer.deleteByKey(key, CustomerRecord.CUST_ID_OFFSET);
            if (!removed) {
                throw new java.util.NoSuchElementException(
                        "Customer record not found for custId=" + custId);
            }
            LOG.debug("FileCustomerRepository.delete: custId={} removed from {}",
                    custId, file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to delete customer record custId=" + custId
                            + " from " + file, e);
        }
    }

    @Override
    public void close() {
        LOG.debug("FileCustomerRepository closed (no-op): file={}", file);
    }

    /**
     * Encodes a numeric {@code CUST-ID} as a 9-byte ASCII zero-left-padded
     * key matching the COBOL {@code PIC 9(09)} field representation.
     */
    private static byte[] encodeKey(long custId) {
        return String.format(Locale.ROOT, "%09d", custId)
                .getBytes(StandardCharsets.US_ASCII);
    }
}
