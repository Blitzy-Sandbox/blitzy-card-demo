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
import com.blitzy.carddemo.domain.port.TransactionCategoryBalanceRepository;
import com.blitzy.carddemo.domain.record.TranCatBalRecord;

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
 * File-backed implementation of {@link TransactionCategoryBalanceRepository}
 * reading and writing the {@code TCATBALF.VSAM.KSDS} dataset as a
 * fixed-width binary file via {@link java.nio.file}.
 *
 * <h2>Source artefact</h2>
 * Implements the access path for the {@code TRAN-CAT-BAL-RECORD}
 * 01-level group defined in {@code app/cpy/CVTRA01Y.cpy} (50-byte
 * fixed-width record, 17-byte composite KSDS primary key):
 * <ul>
 *   <li>bytes  0..10  {@code TRANCAT-ACCT-ID PIC 9(11)}</li>
 *   <li>bytes 11..12  {@code TRANCAT-TYPE-CD PIC X(02)}</li>
 *   <li>bytes 13..16  {@code TRANCAT-CD PIC 9(04)}</li>
 * </ul>
 *
 * <h2>UPSERT semantics (AAP &sect;0.6.9)</h2>
 * The {@link #save(TranCatBalRecord)} method dispatches to either
 * {@link FixedWidthWriter#upsert(byte[], int, byte[])} for both the
 * INSERT and REWRITE paths &mdash; collapsing the COBOL CBTRN02C
 * {@code WS-CREATE-TRANCAT-REC = 'Y'} (paragraph
 * {@code 2700-A-CREATE-TCATBAL-REC} {@code [app/cbl/CBTRN02C.cbl:L504-L515]})
 * and {@code WS-CREATE-TRANCAT-REC = 'N'} (paragraph
 * {@code 2700-B-UPDATE-TCATBAL-REC} {@code [app/cbl/CBTRN02C.cbl:L526-L540]})
 * branches into a single Java call.
 *
 * <h2>Composite key encoding</h2>
 * <pre>{@code
 *   TRANCAT-ACCT-ID  11 bytes  zero-left-padded ASCII (PIC 9(11))
 *   TRANCAT-TYPE-CD   2 bytes  right-space-padded ASCII (PIC X(02))
 *   TRANCAT-CD        4 bytes  zero-left-padded ASCII (PIC 9(04))
 * }</pre>
 *
 * @see TransactionCategoryBalanceRepository
 * @see TranCatBalRecord
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVTRA01Y",
        sourcePath = "app/cpy/CVTRA01Y.cpy",
        translationDate = "2025-10-24",
        notes = "File-backed adapter for the TCATBALF VSAM KSDS (50-byte TRAN-CAT-BAL-RECORD; "
                + "17-byte composite key: ACCT-ID PIC 9(11) + TYPE-CD PIC X(02) + CAT-CD "
                + "PIC 9(04)). save() uses FixedWidthWriter.upsert() to collapse COBOL "
                + "CBTRN02C paragraphs 2700-A-CREATE-TCATBAL-REC and 2700-B-UPDATE-TCATBAL-REC."
)
public final class FileTransactionCategoryBalanceRepository
        implements TransactionCategoryBalanceRepository {

    private static final Logger LOG =
            LoggerFactory.getLogger(FileTransactionCategoryBalanceRepository.class);

    /**
     * The configuration key used by {@link EbcdicTranscoder} to look up
     * the per-file codepage override for this dataset.
     */
    public static final String DATASET_KEY = "tcatbal";

    /**
     * Composite key offset in the record (always 0).
     */
    public static final int COMPOSITE_KEY_OFFSET = 0;

    /**
     * Composite key length (11 + 2 + 4 = 17 bytes).
     */
    public static final int COMPOSITE_KEY_LENGTH = 17;

    private final Path file;
    private final Charset charset;
    private final FixedWidthReader reader;
    private final FixedWidthWriter writer;

    /**
     * Constructs an adapter with the IBM-1047 default codepage.
     */
    public FileTransactionCategoryBalanceRepository(Path file) {
        this(file, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs an adapter with an explicit charset.
     */
    public FileTransactionCategoryBalanceRepository(Path file, Charset charset) {
        this.file = Objects.requireNonNull(file, "file");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(file, TranCatBalRecord.RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(file, TranCatBalRecord.RECORD_LENGTH, charset);
        LOG.debug("FileTransactionCategoryBalanceRepository configured: "
                + "file={}, charset={}, recordLength={}",
                file, charset, TranCatBalRecord.RECORD_LENGTH);
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
    public Optional<TranCatBalRecord> findByKey(long accountId,
                                                String tranTypeCd,
                                                int tranCatCd) {
        validateKey(accountId, tranTypeCd, tranCatCd);
        byte[] key = encodeKey(accountId, tranTypeCd, tranCatCd);
        try {
            return reader.findByKey(key, COMPOSITE_KEY_OFFSET)
                    .map(TranCatBalRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read tcatbal record (acctId=" + accountId
                            + ", typeCd=" + tranTypeCd
                            + ", catCd=" + tranCatCd + ") from " + file, e);
        }
    }

    @Override
    public Stream<TranCatBalRecord> streamSequential() {
        try {
            return reader.streamSequential().map(TranCatBalRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open sequential stream over " + file, e);
        }
    }

    @Override
    public void save(TranCatBalRecord record) {
        Objects.requireNonNull(record, "record");
        byte[] buffer = record.encode();
        TranCatBalRecord.TranCatKey k = record.tranCatKey();
        byte[] key = encodeKey(k.trancatAcctId(), k.trancatTypeCd(), k.trancatCd());
        try {
            writer.upsert(key, COMPOSITE_KEY_OFFSET, buffer);
            LOG.debug("FileTransactionCategoryBalanceRepository.save: "
                    + "key=({},{},{}) upserted to {}",
                    k.trancatAcctId(), k.trancatTypeCd(), k.trancatCd(), file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to save tcatbal record (acctId=" + k.trancatAcctId()
                            + ", typeCd=" + k.trancatTypeCd()
                            + ", catCd=" + k.trancatCd()
                            + ") to " + file, e);
        }
    }

    @Override
    public void delete(long accountId, String tranTypeCd, int tranCatCd) {
        validateKey(accountId, tranTypeCd, tranCatCd);
        byte[] key = encodeKey(accountId, tranTypeCd, tranCatCd);
        try {
            boolean removed = writer.deleteByKey(key, COMPOSITE_KEY_OFFSET);
            if (!removed) {
                throw new NoSuchElementException(
                        "TCATBAL record not found for key=(" + accountId + ","
                                + tranTypeCd + "," + tranCatCd + ")");
            }
            LOG.debug("FileTransactionCategoryBalanceRepository.delete: "
                    + "key=({},{},{}) removed from {}",
                    accountId, tranTypeCd, tranCatCd, file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to delete tcatbal record (acctId=" + accountId
                            + ", typeCd=" + tranTypeCd
                            + ", catCd=" + tranCatCd + ") from " + file, e);
        }
    }

    @Override
    public void close() {
        LOG.debug("FileTransactionCategoryBalanceRepository closed (no-op): file={}", file);
    }

    /**
     * Encodes the 17-byte composite key from the three logical key
     * components.
     */
    private static byte[] encodeKey(long accountId,
                                    String tranTypeCd,
                                    int tranCatCd) {
        byte[] key = new byte[COMPOSITE_KEY_LENGTH];

        // ACCT-ID PIC 9(11) zero-left-padded ASCII
        byte[] acctBytes = String.format(Locale.ROOT, "%011d", accountId)
                .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(acctBytes, 0, key,
                TranCatBalRecord.TRANCAT_ACCT_ID_OFFSET,
                TranCatBalRecord.TRANCAT_ACCT_ID_LENGTH);

        // TYPE-CD PIC X(02) right-space-padded
        byte[] typeBytes = (tranTypeCd == null ? "" : tranTypeCd)
                .getBytes(StandardCharsets.US_ASCII);
        int typeCopy = Math.min(typeBytes.length, TranCatBalRecord.TRANCAT_TYPE_CD_LENGTH);
        System.arraycopy(typeBytes, 0, key,
                TranCatBalRecord.TRANCAT_TYPE_CD_OFFSET, typeCopy);
        for (int i = TranCatBalRecord.TRANCAT_TYPE_CD_OFFSET + typeCopy;
             i < TranCatBalRecord.TRANCAT_TYPE_CD_OFFSET + TranCatBalRecord.TRANCAT_TYPE_CD_LENGTH;
             i++) {
            key[i] = (byte) ' ';
        }

        // CAT-CD PIC 9(04) zero-left-padded ASCII
        byte[] catBytes = String.format(Locale.ROOT, "%04d", tranCatCd)
                .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(catBytes, 0, key,
                TranCatBalRecord.TRANCAT_CD_OFFSET,
                TranCatBalRecord.TRANCAT_CD_LENGTH);

        return key;
    }

    /**
     * Validates the composite-key arguments against their respective
     * COBOL PIC bounds.
     */
    private static void validateKey(long accountId,
                                    String tranTypeCd,
                                    int tranCatCd) {
        Objects.requireNonNull(tranTypeCd, "tranTypeCd");
        if (accountId < 0L || accountId > 99_999_999_999L) {
            throw new IllegalArgumentException(
                    "accountId must be in [0, 99_999_999_999] per PIC 9(11); got "
                            + accountId);
        }
        if (tranTypeCd.length() > TranCatBalRecord.TRANCAT_TYPE_CD_LENGTH) {
            throw new IllegalArgumentException(
                    "tranTypeCd must be <= "
                            + TranCatBalRecord.TRANCAT_TYPE_CD_LENGTH + " chars");
        }
        if (tranCatCd < 0 || tranCatCd > 9999) {
            throw new IllegalArgumentException(
                    "tranCatCd must be in [0, 9999] per PIC 9(04); got " + tranCatCd);
        }
    }
}
