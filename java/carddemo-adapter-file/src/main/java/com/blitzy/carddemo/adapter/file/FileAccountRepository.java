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
import com.blitzy.carddemo.domain.port.AccountRepository;
import com.blitzy.carddemo.domain.record.AccountRecord;

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
 * File-backed implementation of {@link AccountRepository} reading and writing
 * the {@code ACCTDATA.VSAM.KSDS} dataset as a fixed-width binary file via
 * {@link java.nio.file}.
 *
 * <h2>Source artefact</h2>
 * Implements the access path for the {@code ACCOUNT-RECORD} 01-level group
 * defined in {@code app/cpy/CVACT01Y.cpy} (300-byte fixed-width record,
 * 11-byte zoned-decimal {@code ACCT-ID} primary key at offset 0). The COBOL
 * programs that consume this dataset (CBACT01C, CBACT04C, CBTRN02C,
 * COACTVWC, COACTUPC) issue {@code EXEC CICS READ DATASET(ACCTFILE)} for
 * random key lookup and sequential browse via {@code STARTBR}/{@code READNEXT}.
 *
 * <h2>Record layout</h2>
 * Each record is exactly {@value AccountRecord#RECORD_LENGTH} bytes; the
 * 11-byte {@code ACCT-ID} field is the KSDS primary key at offset 0. See
 * {@link AccountRecord} for the complete byte-by-byte layout.
 *
 * <h2>Adapter responsibilities</h2>
 * <ul>
 *   <li>Translate {@link AccountRecord} domain objects to and from the
 *       300-byte fixed-width byte image via
 *       {@link AccountRecord#parse(byte[])} and
 *       {@link AccountRecord#encode()}.</li>
 *   <li>Look up by 11-digit account ID via the {@link FixedWidthReader#findByKey}
 *       primitive (linear scan over the file).</li>
 *   <li>Stream all records in file (KSDS key) order via
 *       {@link FixedWidthReader#streamSequential()}.</li>
 *   <li>Persist account updates via {@link FixedWidthWriter#upsert} (the
 *       hexagonal-port {@link AccountRepository#save(AccountRecord)} method
 *       is upsert-by-key semantics).</li>
 *   <li>Delete records via {@link FixedWidthWriter#deleteByKey}, mirroring
 *       the COBOL {@code EXEC CICS DELETE} verb.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 * The underlying {@link FixedWidthReader} and {@link FixedWidthWriter}
 * primitives are individually thread-safe (the writer serialises mutations
 * on its internal lock). Cross-method atomicity (e.g., read-then-write)
 * MUST be enforced by callers; this adapter does not promise more than the
 * mainframe {@code READ ... UPDATE}/{@code REWRITE} sequence already did.
 *
 * <h2>No Spring, no ThreadLocal, no java.io.File</h2>
 * Per AAP &sect;0.6.5 and &sect;0.7.4, this adapter uses only
 * {@link java.nio.file} and depends on plain Java classes. No
 * {@code java.io.File}, no Spring framework, no {@code ThreadLocal}.
 *
 * @see AccountRepository
 * @see AccountRecord
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVACT01Y",
        sourcePath = "app/cpy/CVACT01Y.cpy",
        translationDate = "2025-10-24",
        notes = "File-backed adapter for the ACCTDATA VSAM KSDS (300-byte ACCOUNT-RECORD; "
                + "11-byte ACCT-ID primary key at offset 0). Translates COBOL EXEC CICS "
                + "READ DATASET(ACCTFILE) random reads to FixedWidthReader.findByKey() and "
                + "STARTBR/READNEXT sequential browses to FixedWidthReader.streamSequential(). "
                + "save() uses FixedWidthWriter.upsert() (rewrite-if-exists else append) "
                + "matching the CICS READ-UPDATE / REWRITE semantics."
)
public final class FileAccountRepository implements AccountRepository {

    private static final Logger LOG = LoggerFactory.getLogger(FileAccountRepository.class);

    /**
     * The configuration key used by {@link EbcdicTranscoder} to look up
     * the per-file codepage override for this dataset. The default
     * value (when no override is set) is
     * {@link EbcdicTranscoder#DEFAULT_CHARSET_NAME IBM-1047} per
     * AAP &sect;0.6.5.
     */
    public static final String DATASET_KEY = "acctdata";

    private final Path file;
    private final Charset charset;
    private final FixedWidthReader reader;
    private final FixedWidthWriter writer;

    /**
     * Constructs an adapter reading and writing the supplied file path with
     * the IBM-1047 default codepage (no override).
     *
     * @param file the absolute or relative path to the ACCTDATA file; must
     *             be non-{@code null}
     * @throws NullPointerException if {@code file} is {@code null}
     */
    public FileAccountRepository(Path file) {
        this(file, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs an adapter reading and writing the supplied file path with
     * the supplied charset.
     *
     * @param file    the absolute or relative path to the ACCTDATA file;
     *                must be non-{@code null}
     * @param charset the charset for any text-field transcoding; must be
     *                non-{@code null}. Note: the {@link AccountRecord}
     *                parse/encode methods themselves use
     *                {@link StandardCharsets#US_ASCII US-ASCII} for the
     *                ASCII fixtures shipped with the project; the
     *                {@code charset} parameter is documented here for
     *                forward compatibility with EBCDIC-sourced input where
     *                the {@link EbcdicTranscoder} would supply the override.
     * @throws NullPointerException if any argument is {@code null}
     */
    public FileAccountRepository(Path file, Charset charset) {
        this.file = Objects.requireNonNull(file, "file");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(file, AccountRecord.RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(file, AccountRecord.RECORD_LENGTH, charset);
        LOG.debug("FileAccountRepository configured: file={}, charset={}, recordLength={}",
                file, charset, AccountRecord.RECORD_LENGTH);
    }

    /**
     * The configured file path.
     *
     * @return the file path; never {@code null}
     */
    public Path file() {
        return file;
    }

    /**
     * The configured charset.
     *
     * @return the charset; never {@code null}
     */
    public Charset charset() {
        return charset;
    }

    @Override
    public Optional<AccountRecord> findById(long acctId) {
        if (acctId < 0L) {
            throw new IllegalArgumentException(
                    "acctId must be non-negative; got " + acctId);
        }
        byte[] key = encodeKey(acctId);
        try {
            return reader.findByKey(key, AccountRecord.ACCT_ID_OFFSET)
                    .map(AccountRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read account record acctId=" + acctId + " from " + file, e);
        }
    }

    @Override
    public Stream<AccountRecord> streamSequential() {
        try {
            return reader.streamSequential().map(AccountRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open sequential stream over " + file, e);
        }
    }

    @Override
    public void save(AccountRecord account) {
        Objects.requireNonNull(account, "account");
        byte[] buffer = account.encode();
        byte[] key = encodeKey(account.acctId());
        try {
            writer.upsert(key, AccountRecord.ACCT_ID_OFFSET, buffer);
            LOG.debug("FileAccountRepository.save: acctId={} written to {}",
                    account.acctId(), file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to save account record acctId=" + account.acctId()
                            + " to " + file, e);
        }
    }

    @Override
    public void delete(long acctId) {
        if (acctId < 0L) {
            throw new IllegalArgumentException(
                    "acctId must be non-negative; got " + acctId);
        }
        byte[] key = encodeKey(acctId);
        try {
            boolean removed = writer.deleteByKey(key, AccountRecord.ACCT_ID_OFFSET);
            if (!removed) {
                throw new java.util.NoSuchElementException(
                        "Account record not found for acctId=" + acctId);
            }
            LOG.debug("FileAccountRepository.delete: acctId={} removed from {}",
                    acctId, file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to delete account record acctId=" + acctId
                            + " from " + file, e);
        }
    }

    @Override
    public void close() {
        // FixedWidthReader and FixedWidthWriter open their own channels per
        // operation and close them deterministically; no long-lived
        // resources are held by this adapter, so close() is a no-op.
        LOG.debug("FileAccountRepository closed (no-op): file={}", file);
    }

    /**
     * Encodes a numeric {@code ACCT-ID} as an 11-byte ASCII zero-left-padded
     * key matching the COBOL {@code PIC 9(11)} field representation.
     *
     * @param acctId the non-negative account id (must fit in 11 digits)
     * @return an 11-byte ASCII buffer (e.g., {@code "00000012345"})
     */
    private static byte[] encodeKey(long acctId) {
        return String.format(Locale.ROOT, "%011d", acctId)
                .getBytes(StandardCharsets.US_ASCII);
    }
}
