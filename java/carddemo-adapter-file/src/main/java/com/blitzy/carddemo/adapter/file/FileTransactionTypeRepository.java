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
import com.blitzy.carddemo.domain.port.TransactionTypeRepository;
import com.blitzy.carddemo.domain.record.TranTypeRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed implementation of {@link TransactionTypeRepository}
 * reading and writing the {@code TRANTYPE.VSAM.KSDS} dataset as a
 * fixed-width binary file via {@link java.nio.file}.
 *
 * <h2>Source artefact</h2>
 * Implements the access path for the {@code TRAN-TYPE-RECORD}
 * 01-level group defined in {@code app/cpy/CVTRA03Y.cpy} (60-byte
 * fixed-width record, 2-byte KSDS primary key
 * {@code TRAN-TYPE PIC X(02)} at offset 0).
 *
 * <h2>Read-mostly access pattern</h2>
 * The dataset is loaded once from {@code app/data/ASCII/trantype.txt}
 * via {@code app/jcl/TRANTYPE.jcl IDCAMS REPRO} and is consulted
 * read-only by CBTRN02C (paragraph
 * {@code 1500-VALIDATE-TRAN} {@code [app/cbl/CBTRN02C.cbl:L389-L416]})
 * and CBTRN03C (transaction-type description lookup for the
 * paginated detail report).
 *
 * <h2>Key encoding</h2>
 * {@code TRAN-TYPE PIC X(02)} is encoded as a 2-byte right-space
 * padded ASCII slice at offset 0.
 *
 * <h2>AutoCloseable</h2>
 * {@link TransactionTypeRepository} does <em>not</em> extend
 * {@link AutoCloseable}, so this adapter does not implement
 * {@code close()}. The reader/writer primitives are open-on-demand
 * (no persistent channel held).
 *
 * @see TransactionTypeRepository
 * @see TranTypeRecord
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVTRA03Y",
        sourcePath = "app/cpy/CVTRA03Y.cpy",
        translationDate = "2025-10-24",
        notes = "File-backed adapter for the TRANTYPE VSAM KSDS (60-byte TRAN-TYPE-RECORD; "
                + "2-byte PIC X(02) primary key at offset 0). Loaded once at job setup time "
                + "via TRANTYPE.jcl IDCAMS REPRO; consulted read-only by CBTRN02C "
                + "validation and CBTRN03C report description lookup."
)
public final class FileTransactionTypeRepository implements TransactionTypeRepository {

    private static final Logger LOG =
            LoggerFactory.getLogger(FileTransactionTypeRepository.class);

    /**
     * The configuration key used by {@link EbcdicTranscoder} to look up
     * the per-file codepage override for this dataset.
     */
    public static final String DATASET_KEY = "trantype";

    /**
     * Primary-key offset in the record (always 0).
     */
    public static final int KEY_OFFSET = TranTypeRecord.TRAN_TYPE_OFFSET;

    /**
     * Primary-key length (2 bytes).
     */
    public static final int KEY_LENGTH = TranTypeRecord.TRAN_TYPE_LENGTH;

    private final Path file;
    private final Charset charset;
    private final FixedWidthReader reader;
    private final FixedWidthWriter writer;

    /**
     * Constructs an adapter with the IBM-1047 default codepage.
     */
    public FileTransactionTypeRepository(Path file) {
        this(file, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs an adapter with an explicit charset.
     */
    public FileTransactionTypeRepository(Path file, Charset charset) {
        this.file = Objects.requireNonNull(file, "file");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(file, TranTypeRecord.RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(file, TranTypeRecord.RECORD_LENGTH, charset);
        LOG.debug("FileTransactionTypeRepository configured: file={}, charset={}, recordLength={}",
                file, charset, TranTypeRecord.RECORD_LENGTH);
    }

    /**
     * Configured file path.
     */
    public Path file() {
        return file;
    }

    /**
     * Configured charset.
     */
    public Charset charset() {
        return charset;
    }

    @Override
    public Optional<TranTypeRecord> findByCode(String tranTypeCd) {
        validateKey(tranTypeCd);
        byte[] key = encodeKey(tranTypeCd);
        try {
            return reader.findByKey(key, KEY_OFFSET).map(TranTypeRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read trantype record (tranTypeCd=" + tranTypeCd
                            + ") from " + file, e);
        }
    }

    @Override
    public Stream<TranTypeRecord> streamSequential() {
        try {
            return reader.streamSequential().map(TranTypeRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open sequential stream over " + file, e);
        }
    }

    @Override
    public void save(TranTypeRecord record) {
        Objects.requireNonNull(record, "record");
        byte[] buffer = record.encode();
        // Defensive check: the domain record's compact canonical constructor and
        // encode() implementation must always produce a 60-byte buffer per AAP
        // §0.6.5 (byte-for-byte fidelity). Surfacing a mismatch here protects
        // the on-disk dataset from corruption if a future refactor accidentally
        // breaks that invariant.
        if (buffer.length != TranTypeRecord.RECORD_LENGTH) {
            throw new IllegalStateException(
                    "TranTypeRecord.encode() returned " + buffer.length
                            + " bytes; expected " + TranTypeRecord.RECORD_LENGTH);
        }
        byte[] key = encodeKey(record.tranType());
        try {
            writer.upsert(key, KEY_OFFSET, buffer);
            LOG.debug("FileTransactionTypeRepository.save: tranTypeCd={} upserted to {}",
                    record.tranType(), file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to save trantype record (tranTypeCd=" + record.tranType()
                            + ") to " + file, e);
        }
    }

    @Override
    public void delete(String tranTypeCd) {
        validateKey(tranTypeCd);
        byte[] key = encodeKey(tranTypeCd);
        try {
            boolean removed = writer.deleteByKey(key, KEY_OFFSET);
            if (!removed) {
                throw new NoSuchElementException(
                        "TRANTYPE record not found for tranTypeCd=" + tranTypeCd);
            }
            LOG.debug("FileTransactionTypeRepository.delete: tranTypeCd={} removed from {}",
                    tranTypeCd, file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to delete trantype record (tranTypeCd=" + tranTypeCd
                            + ") from " + file, e);
        }
    }

    /**
     * Encodes the 2-byte primary key from the supplied transaction-type
     * code. Inputs shorter than 2 chars are right-space padded.
     */
    private static byte[] encodeKey(String tranTypeCd) {
        byte[] key = new byte[KEY_LENGTH];
        java.util.Arrays.fill(key, (byte) ' ');
        byte[] src = tranTypeCd.getBytes(StandardCharsets.US_ASCII);
        int copy = Math.min(src.length, KEY_LENGTH);
        System.arraycopy(src, 0, key, 0, copy);
        return key;
    }

    private static void validateKey(String tranTypeCd) {
        Objects.requireNonNull(tranTypeCd, "tranTypeCd");
        if (tranTypeCd.length() > KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "tranTypeCd must be <= " + KEY_LENGTH + " chars; got "
                            + tranTypeCd.length());
        }
    }
}
