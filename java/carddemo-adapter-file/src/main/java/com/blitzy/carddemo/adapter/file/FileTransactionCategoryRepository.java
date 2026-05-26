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
import com.blitzy.carddemo.domain.port.TransactionCategoryRepository;
import com.blitzy.carddemo.domain.record.TranCatRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed implementation of {@link TransactionCategoryRepository}
 * reading and writing the {@code TRANCATG.VSAM.KSDS} dataset as a
 * fixed-width binary file via {@link java.nio.file}.
 *
 * <h2>Source artefact</h2>
 * Implements the access path for the {@code TRAN-CAT-RECORD} 01-level
 * group defined in {@code app/cpy/CVTRA04Y.cpy} (60-byte fixed-width
 * record, 6-byte composite KSDS primary key):
 * <ul>
 *   <li>bytes 0..1  {@code TRAN-TYPE-CD PIC X(02)}</li>
 *   <li>bytes 2..5  {@code TRAN-CAT-CD PIC 9(04)}</li>
 * </ul>
 *
 * <h2>Read-mostly access pattern</h2>
 * Loaded once from {@code app/data/ASCII/trancatg.txt} via
 * {@code app/jcl/TRANCATG.jcl IDCAMS REPRO}; consulted read-only by
 * CBTRN02C and CBTRN03C for category description lookup.
 *
 * <h2>Composite-key encoding</h2>
 * <pre>{@code
 *   TRAN-TYPE-CD  2 bytes  right-space-padded ASCII (PIC X(02))
 *   TRAN-CAT-CD   4 bytes  zero-left-padded  ASCII (PIC 9(04))
 * }</pre>
 *
 * <h2>AutoCloseable</h2>
 * {@link TransactionCategoryRepository} does <em>not</em> extend
 * {@link AutoCloseable}, matching the read-mostly access pattern.
 *
 * @see TransactionCategoryRepository
 * @see TranCatRecord
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVTRA04Y",
        sourcePath = "app/cpy/CVTRA04Y.cpy",
        translationDate = "2025-10-24",
        notes = "File-backed adapter for the TRANCATG VSAM KSDS (60-byte TRAN-CAT-RECORD; "
                + "6-byte composite key: TYPE-CD PIC X(02) + CAT-CD PIC 9(04)). Loaded once "
                + "at job setup time via TRANCATG.jcl IDCAMS REPRO; consulted read-only by "
                + "CBTRN02C and CBTRN03C."
)
public final class FileTransactionCategoryRepository
        implements TransactionCategoryRepository {

    private static final Logger LOG =
            LoggerFactory.getLogger(FileTransactionCategoryRepository.class);

    /**
     * The configuration key used by {@link EbcdicTranscoder} to look up
     * the per-file codepage override for this dataset.
     */
    public static final String DATASET_KEY = "trancatg";

    /**
     * Composite key offset in the record (always 0).
     */
    public static final int COMPOSITE_KEY_OFFSET = 0;

    /**
     * Composite key length (2 + 4 = 6 bytes).
     */
    public static final int COMPOSITE_KEY_LENGTH = 6;

    private final Path file;
    private final Charset charset;
    private final FixedWidthReader reader;
    private final FixedWidthWriter writer;

    /**
     * Constructs an adapter with the IBM-1047 default codepage.
     */
    public FileTransactionCategoryRepository(Path file) {
        this(file, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs an adapter with an explicit charset.
     */
    public FileTransactionCategoryRepository(Path file, Charset charset) {
        this.file = Objects.requireNonNull(file, "file");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(file, TranCatRecord.RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(file, TranCatRecord.RECORD_LENGTH, charset);
        LOG.debug("FileTransactionCategoryRepository configured: "
                + "file={}, charset={}, recordLength={}",
                file, charset, TranCatRecord.RECORD_LENGTH);
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
    public Optional<TranCatRecord> findByKey(String tranTypeCd, int tranCatCd) {
        validateKey(tranTypeCd, tranCatCd);
        byte[] key = encodeKey(tranTypeCd, tranCatCd);
        try {
            return reader.findByKey(key, COMPOSITE_KEY_OFFSET).map(TranCatRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read trancatg record (tranTypeCd=" + tranTypeCd
                            + ", tranCatCd=" + tranCatCd + ") from " + file, e);
        }
    }

    @Override
    public Stream<TranCatRecord> streamSequential() {
        try {
            return reader.streamSequential().map(TranCatRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open sequential stream over " + file, e);
        }
    }

    @Override
    public void save(TranCatRecord record) {
        Objects.requireNonNull(record, "record");
        byte[] buffer = record.encode();
        // Defensive check: the domain record's compact canonical constructor and
        // encode() implementation must always produce a 60-byte buffer per AAP
        // §0.6.5 (byte-for-byte fidelity). Surfacing a mismatch here protects
        // the on-disk dataset from corruption if a future refactor accidentally
        // breaks that invariant.
        if (buffer.length != TranCatRecord.RECORD_LENGTH) {
            throw new IllegalStateException(
                    "TranCatRecord.encode() returned " + buffer.length
                            + " bytes; expected " + TranCatRecord.RECORD_LENGTH);
        }
        TranCatRecord.TranCatKey k = record.tranCatKey();
        byte[] key = encodeKey(k.tranTypeCd(), k.tranCatCd());
        try {
            writer.upsert(key, COMPOSITE_KEY_OFFSET, buffer);
            LOG.debug("FileTransactionCategoryRepository.save: key=({},{}) upserted to {}",
                    k.tranTypeCd(), k.tranCatCd(), file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to save trancatg record (tranTypeCd=" + k.tranTypeCd()
                            + ", tranCatCd=" + k.tranCatCd() + ") to " + file, e);
        }
    }

    @Override
    public void delete(String tranTypeCd, int tranCatCd) {
        validateKey(tranTypeCd, tranCatCd);
        byte[] key = encodeKey(tranTypeCd, tranCatCd);
        try {
            boolean removed = writer.deleteByKey(key, COMPOSITE_KEY_OFFSET);
            if (!removed) {
                throw new NoSuchElementException(
                        "TRANCATG record not found for key=(" + tranTypeCd + ","
                                + tranCatCd + ")");
            }
            LOG.debug("FileTransactionCategoryRepository.delete: key=({},{}) removed from {}",
                    tranTypeCd, tranCatCd, file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to delete trancatg record (tranTypeCd=" + tranTypeCd
                            + ", tranCatCd=" + tranCatCd + ") from " + file, e);
        }
    }

    /**
     * Encodes the 6-byte composite key from the supplied components.
     */
    private static byte[] encodeKey(String tranTypeCd, int tranCatCd) {
        byte[] key = new byte[COMPOSITE_KEY_LENGTH];

        // TYPE-CD PIC X(02) right-space-padded
        byte[] typeBytes = tranTypeCd.getBytes(StandardCharsets.US_ASCII);
        int typeCopy = Math.min(typeBytes.length, TranCatRecord.TRAN_TYPE_CD_LENGTH);
        System.arraycopy(typeBytes, 0, key,
                TranCatRecord.TRAN_TYPE_CD_OFFSET, typeCopy);
        for (int i = TranCatRecord.TRAN_TYPE_CD_OFFSET + typeCopy;
             i < TranCatRecord.TRAN_TYPE_CD_OFFSET + TranCatRecord.TRAN_TYPE_CD_LENGTH;
             i++) {
            key[i] = (byte) ' ';
        }

        // CAT-CD PIC 9(04) zero-left-padded
        byte[] catBytes = String.format(Locale.ROOT, "%04d", tranCatCd)
                .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(catBytes, 0, key,
                TranCatRecord.TRAN_CAT_CD_OFFSET,
                TranCatRecord.TRAN_CAT_CD_LENGTH);

        return key;
    }

    private static void validateKey(String tranTypeCd, int tranCatCd) {
        Objects.requireNonNull(tranTypeCd, "tranTypeCd");
        if (tranTypeCd.length() > TranCatRecord.TRAN_TYPE_CD_LENGTH) {
            throw new IllegalArgumentException(
                    "tranTypeCd must be <= " + TranCatRecord.TRAN_TYPE_CD_LENGTH
                            + " chars");
        }
        if (tranCatCd < 0 || tranCatCd > 9999) {
            throw new IllegalArgumentException(
                    "tranCatCd must be in [0, 9999] per PIC 9(04); got " + tranCatCd);
        }
    }
}
