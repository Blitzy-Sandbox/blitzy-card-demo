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
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.record.TranRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed implementation of {@link TransactionRepository} reading and
 * writing the {@code TRANSACT.VSAM.KSDS} dataset (and its
 * {@code .VSAM.AIX.PATH} secondary index on {@code TRAN-CARD-NUM}) as a
 * fixed-width binary file via {@link java.nio.file}.
 *
 * <h2>Source artefact</h2>
 * Implements the access path for the {@code TRAN-RECORD} 01-level group
 * defined in {@code app/cpy/CVTRA05Y.cpy} (350-byte fixed-width record,
 * 16-byte {@code TRAN-ID} primary key at offset 0, 16-byte
 * {@code TRAN-CARD-NUM} AIX key at offset 262). The COBOL programs that
 * consume this dataset (CBTRN02C, CBTRN03C, COTRN00C, COTRN01C, COTRN02C)
 * use {@code EXEC CICS WRITE DATASET(TRANFILE)} (CBTRN02C posting) and
 * {@code EXEC CICS READ DATASET(TRANFIL2)} (AIX-path PAN lookup).
 *
 * <h2>AIX scan strategy</h2>
 * The KSDS primary index is on {@code TRAN-ID}; the AIX is on
 * {@code TRAN-CARD-NUM}. Because the file adapter has no native AIX
 * support in {@link FixedWidthReader}, {@code streamByCardNumber} is
 * implemented as a sequential scan filtered on
 * {@link TranRecord#tranCardNum()}. Output order is the file scan order
 * (KSDS primary-key order), matching the COBOL AIX-path behavior where
 * multiple records sharing a card number are returned in primary-key
 * order.
 *
 * <h2>findHighestId implementation</h2>
 * The {@link #findHighestId()} method returns the {@link TranRecord}
 * with the lexicographically largest {@code TRAN-ID} via a single
 * sequential scan. The COBOL CBTRN02C posting engine uses this to
 * compute the next-available {@code TRAN-ID} for newly-posted
 * transactions (see {@code app/cbl/CBTRN02C.cbl:&sect;2900-WRITE-TRANSACTION-FILE}).
 *
 * <h2>PAN masking</h2>
 * Per AAP &sect;0.7.2 no PAN is logged in full;
 * {@link TranRecord#toString()} already masks PAN to last-4. This adapter
 * logs only {@code tranId} (the primary key) and masks any PAN in log
 * messages via the private {@link #maskPan(String)} helper.
 *
 * @see TransactionRepository
 * @see TranRecord
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVTRA05Y",
        sourcePath = "app/cpy/CVTRA05Y.cpy",
        translationDate = "2025-10-24",
        notes = "File-backed adapter for the TRANSACT VSAM KSDS (350-byte TRAN-RECORD; "
                + "16-byte TRAN-ID primary key at offset 0; 16-byte TRAN-CARD-NUM AIX key "
                + "at offset 262). streamByCardNumber uses sequential filtering scan. "
                + "findHighestId computes max TRAN-ID via single scan."
)
public final class FileTransactionRepository implements TransactionRepository {

    private static final Logger LOG = LoggerFactory.getLogger(FileTransactionRepository.class);

    /**
     * The configuration key used by {@link EbcdicTranscoder} to look up
     * the per-file codepage override for this dataset.
     */
    public static final String DATASET_KEY = "transact";

    private final Path file;
    private final Charset charset;
    private final FixedWidthReader reader;
    private final FixedWidthWriter writer;

    /**
     * Constructs an adapter with the IBM-1047 default codepage.
     */
    public FileTransactionRepository(Path file) {
        this(file, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs an adapter with an explicit charset.
     */
    public FileTransactionRepository(Path file, Charset charset) {
        this.file = Objects.requireNonNull(file, "file");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(file, TranRecord.RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(file, TranRecord.RECORD_LENGTH, charset);
        LOG.debug("FileTransactionRepository configured: file={}, charset={}, recordLength={}",
                file, charset, TranRecord.RECORD_LENGTH);
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
    public Optional<TranRecord> findById(String tranId) {
        validateTranId(tranId);
        byte[] key = encodeTranKey(tranId);
        try {
            return reader.findByKey(key, TranRecord.TRAN_ID_OFFSET)
                    .map(TranRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read transaction tranId=" + tranId + " from " + file, e);
        }
    }

    @Override
    public Optional<TranRecord> findHighestId() {
        try (Stream<TranRecord> stream = streamSequential()) {
            return stream.max(Comparator.comparing(TranRecord::tranId));
        }
    }

    @Override
    public Stream<TranRecord> streamSequential() {
        try {
            return reader.streamSequential().map(TranRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open sequential stream over " + file, e);
        }
    }

    @Override
    public Stream<TranRecord> streamFrom(String startTranId) {
        if (startTranId == null || startTranId.isBlank()) {
            return streamSequential();
        }
        byte[] key = encodeTranKey(startTranId);
        try {
            return reader.streamFromKey(key, TranRecord.TRAN_ID_OFFSET)
                    .map(TranRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open ranged stream from startTranId=" + startTranId
                            + " over " + file, e);
        }
    }

    @Override
    public Stream<TranRecord> streamByCardNumber(String cardNumber) {
        Objects.requireNonNull(cardNumber, "cardNumber");
        if (cardNumber.isBlank()) {
            throw new IllegalArgumentException("cardNumber must not be blank");
        }
        // AIX path on TRAN-CARD-NUM: sequential scan filtered on tranCardNum.
        // Output order is KSDS primary-key (tran-id) order. The COBOL
        // counterpart issues EXEC CICS STARTBR DATASET(TRANFIL2)
        // RIDFLD(TRAN-CARD-NUM) which on z/OS uses the AIX-PATH to retrieve
        // records sharing the same card number in primary-key order.
        try {
            return reader.streamSequential()
                    .map(TranRecord::parse)
                    .filter(r -> cardNumber.equals(r.tranCardNum()));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open AIX-path stream by cardNumber="
                            + maskPan(cardNumber) + " over " + file, e);
        }
    }

    @Override
    public void save(TranRecord transaction) {
        Objects.requireNonNull(transaction, "transaction");
        byte[] buffer = transaction.encode();
        byte[] key = encodeTranKey(transaction.tranId());
        try {
            writer.upsert(key, TranRecord.TRAN_ID_OFFSET, buffer);
            LOG.debug("FileTransactionRepository.save: tranId={} written to {}",
                    transaction.tranId(), file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to save transaction tranId=" + transaction.tranId()
                            + " to " + file, e);
        }
    }

    @Override
    public void close() {
        LOG.debug("FileTransactionRepository closed (no-op): file={}", file);
    }

    /**
     * Encodes a 16-character {@code TRAN-ID} as a 16-byte ASCII key.
     * Right-space-pads if shorter than 16 chars (COBOL {@code PIC X(16)}
     * convention).
     */
    private static byte[] encodeTranKey(String tranId) {
        byte[] key = new byte[TranRecord.TRAN_ID_LENGTH];
        byte[] src = tranId.getBytes(StandardCharsets.US_ASCII);
        int copyLen = Math.min(src.length, key.length);
        System.arraycopy(src, 0, key, 0, copyLen);
        for (int i = copyLen; i < key.length; i++) {
            key[i] = (byte) ' ';
        }
        return key;
    }

    /**
     * Validates the {@code tranId} argument.
     */
    private static void validateTranId(String tranId) {
        Objects.requireNonNull(tranId, "tranId");
        if (tranId.isBlank()) {
            throw new IllegalArgumentException("tranId must not be blank");
        }
        if (tranId.length() > TranRecord.TRAN_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "tranId must be <= " + TranRecord.TRAN_ID_LENGTH
                            + " chars; got length " + tranId.length());
        }
    }

    /**
     * Masks a card number to last-4 for safe logging per AAP &sect;0.7.2.
     */
    private static String maskPan(String cardNumber) {
        if (cardNumber == null) {
            return "null";
        }
        int len = cardNumber.length();
        if (len <= 4) {
            return "*".repeat(len);
        }
        return "*".repeat(len - 4) + cardNumber.substring(len - 4);
    }
}
