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
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.record.CardXrefRecord;

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
 * File-backed implementation of {@link CardXrefRepository} reading and
 * writing the {@code CARDXREF.VSAM.KSDS} dataset (and its
 * {@code .VSAM.AIX.PATH} secondary index path) as a fixed-width binary
 * file via {@link java.nio.file}.
 *
 * <h2>Source artefact</h2>
 * Implements the access path for the {@code CARD-XREF-RECORD} 01-level
 * group defined in {@code app/cpy/CVACT03Y.cpy} (50-byte fixed-width
 * record, 16-byte {@code XREF-CARD-NUM} primary key at offset 0, with
 * an {@code XREF-ACCT-ID} alternate key at offset 25). The COBOL programs
 * that consume this dataset (CBACT03C, CBACT04C, CBTRN02C) use
 * {@code EXEC CICS READ DATASET(XREFFILE)} for random PAN lookup and
 * {@code EXEC CICS READ DATASET(XREFFIL1)} (via the AIX path) for account-
 * id reverse lookup.
 *
 * <h2>AIX scan strategy</h2>
 * The KSDS primary index is on the 16-byte {@code XREF-CARD-NUM}; the
 * alternate index (AIX) is on the 11-byte {@code XREF-ACCT-ID} at offset
 * {@value CardXrefRecord#XREF_ACCT_ID_OFFSET}. Because file-based KSDS
 * does not have native AIX support in {@link FixedWidthReader}, this
 * adapter implements the {@code findByAccountId} and
 * {@code streamByAccountId} access paths via a sequential scan that
 * filters on the {@link CardXrefRecord#xrefAcctId()} field. Order is
 * preserved (file-scan order = KSDS primary-key order); this matches
 * the COBOL AIX-path browse where multiple records with the same
 * {@code XREF-ACCT-ID} are returned in primary-key order.
 *
 * <h2>Record layout</h2>
 * Each record is exactly {@value CardXrefRecord#RECORD_LENGTH} bytes;
 * see {@link CardXrefRecord} for the byte-by-byte layout.
 *
 * <h2>PAN masking</h2>
 * Per AAP &sect;0.7.2 no PAN is logged in full;
 * {@link CardXrefRecord#toString()} already masks the PAN to last-4. This
 * adapter additionally masks any {@code cardNumber} parameter in log
 * statements via the private {@link #maskPan(String)} helper.
 *
 * @see CardXrefRepository
 * @see CardXrefRecord
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVACT03Y",
        sourcePath = "app/cpy/CVACT03Y.cpy",
        translationDate = "2025-10-24",
        notes = "File-backed adapter for the CARDXREF VSAM KSDS (50-byte CARD-XREF-RECORD; "
                + "16-byte XREF-CARD-NUM primary key at offset 0; 11-byte XREF-ACCT-ID "
                + "alternate index at offset 25). AIX-path lookups (findByAccountId, "
                + "streamByAccountId) implemented as sequential filtering scan since file "
                + "adapter has no native AIX support. PAN masking applied in all log lines."
)
public final class FileCardXrefRepository implements CardXrefRepository {

    private static final Logger LOG = LoggerFactory.getLogger(FileCardXrefRepository.class);

    /**
     * The configuration key used by {@link EbcdicTranscoder} to look up
     * the per-file codepage override for this dataset.
     */
    public static final String DATASET_KEY = "cardxref";

    private final Path file;
    private final Charset charset;
    private final FixedWidthReader reader;
    private final FixedWidthWriter writer;

    /**
     * Constructs an adapter with the IBM-1047 default codepage.
     *
     * @param file the file path; must not be {@code null}
     */
    public FileCardXrefRepository(Path file) {
        this(file, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs an adapter with an explicit charset.
     *
     * @param file    the file path; must not be {@code null}
     * @param charset the charset; must not be {@code null}
     */
    public FileCardXrefRepository(Path file, Charset charset) {
        this.file = Objects.requireNonNull(file, "file");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(file, CardXrefRecord.RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(file, CardXrefRecord.RECORD_LENGTH, charset);
        LOG.debug("FileCardXrefRepository configured: file={}, charset={}, recordLength={}",
                file, charset, CardXrefRecord.RECORD_LENGTH);
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
    public Optional<CardXrefRecord> findByCardNumber(String cardNumber) {
        validateCardNumber(cardNumber);
        byte[] key = encodeCardKey(cardNumber);
        try {
            return reader.findByKey(key, CardXrefRecord.XREF_CARD_NUM_OFFSET)
                    .map(CardXrefRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read xref by cardNumber=" + maskPan(cardNumber)
                            + " from " + file, e);
        }
    }

    @Override
    public Optional<CardXrefRecord> findByAccountId(long accountId) {
        if (accountId < 0L) {
            throw new IllegalArgumentException(
                    "accountId must be non-negative; got " + accountId);
        }
        try (Stream<CardXrefRecord> stream = streamByAccountId(accountId)) {
            return stream.findFirst();
        }
    }

    @Override
    public Stream<CardXrefRecord> streamByAccountId(long accountId) {
        if (accountId < 0L) {
            throw new IllegalArgumentException(
                    "accountId must be non-negative; got " + accountId);
        }
        // AIX path: sequential scan filtered on XREF-ACCT-ID. Order is the
        // file scan order which is KSDS primary-key (PAN) order; that
        // matches the COBOL AIX-path browse behaviour.
        try {
            return reader.streamSequential()
                    .map(CardXrefRecord::parse)
                    .filter(r -> r.xrefAcctId() == accountId);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open AIX-path stream by accountId=" + accountId
                            + " over " + file, e);
        }
    }

    @Override
    public Stream<CardXrefRecord> streamSequential() {
        try {
            return reader.streamSequential().map(CardXrefRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open sequential stream over " + file, e);
        }
    }

    @Override
    public void save(CardXrefRecord record) {
        Objects.requireNonNull(record, "record");
        byte[] buffer = record.encode();
        byte[] key = encodeCardKey(record.xrefCardNum());
        try {
            writer.upsert(key, CardXrefRecord.XREF_CARD_NUM_OFFSET, buffer);
            LOG.debug("FileCardXrefRepository.save: xrefCardNum={} acctId={} written to {}",
                    maskPan(record.xrefCardNum()), record.xrefAcctId(), file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to save xref cardNum=" + maskPan(record.xrefCardNum())
                            + " to " + file, e);
        }
    }

    @Override
    public void delete(String cardNumber) {
        validateCardNumber(cardNumber);
        byte[] key = encodeCardKey(cardNumber);
        try {
            boolean removed = writer.deleteByKey(key, CardXrefRecord.XREF_CARD_NUM_OFFSET);
            if (!removed) {
                throw new java.util.NoSuchElementException(
                        "Xref record not found for cardNumber=" + maskPan(cardNumber));
            }
            LOG.debug("FileCardXrefRepository.delete: cardNum={} removed from {}",
                    maskPan(cardNumber), file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to delete xref cardNumber=" + maskPan(cardNumber)
                            + " from " + file, e);
        }
    }

    @Override
    public void close() {
        LOG.debug("FileCardXrefRepository closed (no-op): file={}", file);
    }

    /**
     * Encodes a 16-character {@code XREF-CARD-NUM} as a 16-byte ASCII
     * key (right-space-padded if shorter than 16 chars, matching COBOL
     * {@code PIC X(16)} convention).
     */
    private static byte[] encodeCardKey(String cardNumber) {
        byte[] key = new byte[CardXrefRecord.XREF_CARD_NUM_LENGTH];
        byte[] src = cardNumber.getBytes(StandardCharsets.US_ASCII);
        int copyLen = Math.min(src.length, key.length);
        System.arraycopy(src, 0, key, 0, copyLen);
        for (int i = copyLen; i < key.length; i++) {
            key[i] = (byte) ' ';
        }
        return key;
    }

    /**
     * Validates the {@code cardNumber} argument.
     */
    private static void validateCardNumber(String cardNumber) {
        Objects.requireNonNull(cardNumber, "cardNumber");
        if (cardNumber.isBlank()) {
            throw new IllegalArgumentException("cardNumber must not be blank");
        }
        if (cardNumber.length() > CardXrefRecord.XREF_CARD_NUM_LENGTH) {
            throw new IllegalArgumentException(
                    "cardNumber must be <= " + CardXrefRecord.XREF_CARD_NUM_LENGTH
                            + " chars; got length " + cardNumber.length());
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

    /**
     * Helper for callers that need to format an account id consistently.
     * Not used internally but exposed for adapter tests.
     *
     * @param accountId non-negative account id
     * @return 11-character zero-left-padded ASCII representation
     */
    public static String formatAccountId(long accountId) {
        return String.format(Locale.ROOT, "%011d", accountId);
    }
}
