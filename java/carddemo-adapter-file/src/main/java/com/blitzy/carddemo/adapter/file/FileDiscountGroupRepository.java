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
import com.blitzy.carddemo.domain.port.DiscountGroupRepository;
import com.blitzy.carddemo.domain.record.DisGroupRecord;

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
 * File-backed implementation of {@link DiscountGroupRepository} reading
 * and writing the {@code DISCGRP.VSAM.KSDS} dataset as a fixed-width
 * binary file via {@link java.nio.file}.
 *
 * <h2>Source artefact</h2>
 * Implements the access path for the {@code DIS-GROUP-RECORD} 01-level
 * group defined in {@code app/cpy/CVTRA02Y.cpy} (50-byte fixed-width
 * record, 16-byte composite KSDS primary key:
 * <ul>
 *   <li>bytes  0..9   {@code DIS-ACCT-GROUP-ID PIC X(10)}</li>
 *   <li>bytes 10..11  {@code DIS-TRAN-TYPE-CD PIC X(02)}</li>
 *   <li>bytes 12..15  {@code DIS-TRAN-CAT-CD PIC 9(04)}</li>
 * </ul>
 * The COBOL CBACT04C interest calculation engine consumes this dataset
 * via {@code EXEC CICS READ DATASET(DISCGRP)} for both the account-
 * group-specific rate lookup and the {@code "DEFAULT"} fallback (see
 * {@code app/cbl/CBACT04C.cbl:&sect;1200-A-GET-DEFAULT-INT-RATE}).
 *
 * <h2>DEFAULT fallback policy</h2>
 * Per AAP &sect;0.6.10, the DEFAULT-fallback logic stays in the
 * application layer (CbAct04C) &mdash; this adapter simply returns
 * {@link Optional#empty()} on a not-found lookup. The application
 * layer then retries with {@code "DEFAULT"} as the group id.
 *
 * <h2>Composite key encoding</h2>
 * The composite key spans the first 16 bytes of the record:
 * <pre>{@code
 *   DIS-ACCT-GROUP-ID  10 bytes  right-space-padded ASCII (PIC X(10))
 *   DIS-TRAN-TYPE-CD    2 bytes  right-space-padded ASCII (PIC X(02))
 *   DIS-TRAN-CAT-CD     4 bytes  zero-left-padded  ASCII (PIC 9(04))
 * }</pre>
 *
 * @see DiscountGroupRepository
 * @see DisGroupRecord
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVTRA02Y",
        sourcePath = "app/cpy/CVTRA02Y.cpy",
        translationDate = "2025-10-24",
        notes = "File-backed adapter for the DISCGRP VSAM KSDS (50-byte DIS-GROUP-RECORD; "
                + "16-byte composite key: GROUP-ID PIC X(10) + TYPE-CD PIC X(02) + CAT-CD "
                + "PIC 9(04)). findByKey returns Optional.empty() on not-found; DEFAULT "
                + "fallback policy lives in CbAct04C application layer per AAP §0.6.10."
)
public final class FileDiscountGroupRepository implements DiscountGroupRepository {

    private static final Logger LOG =
            LoggerFactory.getLogger(FileDiscountGroupRepository.class);

    /**
     * The configuration key used by {@link EbcdicTranscoder} to look up
     * the per-file codepage override for this dataset.
     */
    public static final String DATASET_KEY = "discgrp";

    /**
     * Composite key offset in the record (always 0).
     */
    public static final int COMPOSITE_KEY_OFFSET = 0;

    /**
     * Composite key length (10 + 2 + 4 = 16 bytes).
     */
    public static final int COMPOSITE_KEY_LENGTH = 16;

    private final Path file;
    private final Charset charset;
    private final FixedWidthReader reader;
    private final FixedWidthWriter writer;

    /**
     * Constructs an adapter with the IBM-1047 default codepage.
     */
    public FileDiscountGroupRepository(Path file) {
        this(file, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs an adapter with an explicit charset.
     */
    public FileDiscountGroupRepository(Path file, Charset charset) {
        this.file = Objects.requireNonNull(file, "file");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(file, DisGroupRecord.RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(file, DisGroupRecord.RECORD_LENGTH, charset);
        LOG.debug("FileDiscountGroupRepository configured: file={}, charset={}, recordLength={}",
                file, charset, DisGroupRecord.RECORD_LENGTH);
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
    public Optional<DisGroupRecord> findByKey(String accountGroupId,
                                              String tranTypeCd,
                                              int tranCatCd) {
        validateGroupKey(accountGroupId, tranTypeCd, tranCatCd);
        byte[] key = encodeKey(accountGroupId, tranTypeCd, tranCatCd);
        try {
            return reader.findByKey(key, COMPOSITE_KEY_OFFSET)
                    .map(DisGroupRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read discount-group record (groupId=" + accountGroupId
                            + ", typeCd=" + tranTypeCd + ", catCd=" + tranCatCd
                            + ") from " + file, e);
        }
    }

    @Override
    public Stream<DisGroupRecord> streamSequential() {
        try {
            return reader.streamSequential().map(DisGroupRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open sequential stream over " + file, e);
        }
    }

    @Override
    public void save(DisGroupRecord record) {
        Objects.requireNonNull(record, "record");
        byte[] buffer = record.encode();
        DisGroupRecord.DisGroupKey k = record.disGroupKey();
        byte[] key = encodeKey(k.disAcctGroupId(), k.disTranTypeCd(), k.disTranCatCd());
        try {
            writer.upsert(key, COMPOSITE_KEY_OFFSET, buffer);
            LOG.debug("FileDiscountGroupRepository.save: key=({},{},{}) written to {}",
                    k.disAcctGroupId(), k.disTranTypeCd(), k.disTranCatCd(), file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to save discount-group record (groupId=" + k.disAcctGroupId()
                            + ", typeCd=" + k.disTranTypeCd()
                            + ", catCd=" + k.disTranCatCd()
                            + ") to " + file, e);
        }
    }

    @Override
    public void delete(String accountGroupId, String tranTypeCd, int tranCatCd) {
        validateGroupKey(accountGroupId, tranTypeCd, tranCatCd);
        byte[] key = encodeKey(accountGroupId, tranTypeCd, tranCatCd);
        try {
            boolean removed = writer.deleteByKey(key, COMPOSITE_KEY_OFFSET);
            if (!removed) {
                throw new java.util.NoSuchElementException(
                        "Discount-group record not found for key=("
                                + accountGroupId + "," + tranTypeCd + ","
                                + tranCatCd + ")");
            }
            LOG.debug("FileDiscountGroupRepository.delete: key=({},{},{}) removed from {}",
                    accountGroupId, tranTypeCd, tranCatCd, file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to delete discount-group record (groupId="
                            + accountGroupId + ", typeCd=" + tranTypeCd
                            + ", catCd=" + tranCatCd + ") from " + file, e);
        }
    }

    @Override
    public void close() {
        LOG.debug("FileDiscountGroupRepository closed (no-op): file={}", file);
    }

    /**
     * Encodes the 16-byte composite key from the three logical key
     * components.
     *
     * @param accountGroupId 1- to 10-character group id; null/blank
     *                       becomes 10 spaces
     * @param tranTypeCd     1- to 2-character type code; null/blank
     *                       becomes 2 spaces
     * @param tranCatCd      0- to 9999 category code; zero-left-padded
     *                       to 4 digits
     * @return a 16-byte composite key buffer
     */
    private static byte[] encodeKey(String accountGroupId,
                                    String tranTypeCd,
                                    int tranCatCd) {
        byte[] key = new byte[COMPOSITE_KEY_LENGTH];

        // GROUP-ID PIC X(10) right-space-padded
        byte[] groupBytes = (accountGroupId == null ? "" : accountGroupId)
                .getBytes(StandardCharsets.US_ASCII);
        int groupCopy = Math.min(groupBytes.length, DisGroupRecord.DIS_ACCT_GROUP_ID_LENGTH);
        System.arraycopy(groupBytes, 0, key,
                DisGroupRecord.DIS_ACCT_GROUP_ID_OFFSET, groupCopy);
        for (int i = DisGroupRecord.DIS_ACCT_GROUP_ID_OFFSET + groupCopy;
             i < DisGroupRecord.DIS_ACCT_GROUP_ID_OFFSET + DisGroupRecord.DIS_ACCT_GROUP_ID_LENGTH;
             i++) {
            key[i] = (byte) ' ';
        }

        // TYPE-CD PIC X(02) right-space-padded
        byte[] typeBytes = (tranTypeCd == null ? "" : tranTypeCd)
                .getBytes(StandardCharsets.US_ASCII);
        int typeCopy = Math.min(typeBytes.length, DisGroupRecord.DIS_TRAN_TYPE_CD_LENGTH);
        System.arraycopy(typeBytes, 0, key,
                DisGroupRecord.DIS_TRAN_TYPE_CD_OFFSET, typeCopy);
        for (int i = DisGroupRecord.DIS_TRAN_TYPE_CD_OFFSET + typeCopy;
             i < DisGroupRecord.DIS_TRAN_TYPE_CD_OFFSET + DisGroupRecord.DIS_TRAN_TYPE_CD_LENGTH;
             i++) {
            key[i] = (byte) ' ';
        }

        // CAT-CD PIC 9(04) zero-left-padded
        byte[] catBytes = String.format(Locale.ROOT, "%04d", tranCatCd)
                .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(catBytes, 0, key,
                DisGroupRecord.DIS_TRAN_CAT_CD_OFFSET,
                DisGroupRecord.DIS_TRAN_CAT_CD_LENGTH);

        return key;
    }

    /**
     * Validates the composite-key arguments against their respective
     * COBOL PIC bounds.
     */
    private static void validateGroupKey(String accountGroupId,
                                         String tranTypeCd,
                                         int tranCatCd) {
        Objects.requireNonNull(accountGroupId, "accountGroupId");
        Objects.requireNonNull(tranTypeCd, "tranTypeCd");
        if (accountGroupId.length() > DisGroupRecord.DIS_ACCT_GROUP_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "accountGroupId must be <= "
                            + DisGroupRecord.DIS_ACCT_GROUP_ID_LENGTH + " chars");
        }
        if (tranTypeCd.length() > DisGroupRecord.DIS_TRAN_TYPE_CD_LENGTH) {
            throw new IllegalArgumentException(
                    "tranTypeCd must be <= "
                            + DisGroupRecord.DIS_TRAN_TYPE_CD_LENGTH + " chars");
        }
        if (tranCatCd < 0 || tranCatCd > 9999) {
            throw new IllegalArgumentException(
                    "tranCatCd must be in [0, 9999] per PIC 9(04); got " + tranCatCd);
        }
    }
}
