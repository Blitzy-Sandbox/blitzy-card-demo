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
import com.blitzy.carddemo.domain.port.UserSecurityRepository;
import com.blitzy.carddemo.domain.record.SecUserData;

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
 * File-backed implementation of {@link UserSecurityRepository} reading
 * and writing the {@code USRSEC.VSAM.KSDS} dataset as a fixed-width
 * binary file via {@link java.nio.file}.
 *
 * <h2>Source artefact</h2>
 * Implements the access path for the {@code SEC-USER-DATA} 01-level
 * group defined in {@code app/cpy/CSUSR01Y.cpy} (80-byte fixed-width
 * record, 8-byte KSDS primary key {@code SEC-USR-ID PIC X(08)} at
 * offset 0).
 *
 * <h2>Explicit insert / update / delete (not save-upsert)</h2>
 * Unlike most repository ports in this package, the COSGN00C /
 * COUSR0xC online programs branch on distinct {@code DFHRESP} codes
 * (DUPKEY, NOTFND) and produce different BMS user-visible messages.
 * The port therefore exposes the explicit triplet
 * {@link #insert(SecUserData)} / {@link #update(SecUserData)} /
 * {@link #delete(String)} rather than a single upsert. This adapter
 * implements that contract:
 * <ul>
 *   <li>{@code insert} fails with {@link IllegalStateException} when
 *       the primary key is already present (mirrors
 *       {@code DFHRESP(DUPKEY)} on
 *       {@code EXEC CICS WRITE} in COUSR01C
 *       {@code [app/cbl/COUSR01C.cbl:L240-L248]}).</li>
 *   <li>{@code update} fails with {@link NoSuchElementException} when
 *       the primary key is absent (mirrors
 *       {@code DFHRESP(NOTFND)} on
 *       {@code EXEC CICS REWRITE} in COUSR02C
 *       {@code [app/cbl/COUSR02C.cbl:L360-L366]}).</li>
 *   <li>{@code delete} fails with {@link NoSuchElementException} when
 *       the primary key is absent (mirrors
 *       {@code DFHRESP(NOTFND)} on
 *       {@code EXEC CICS DELETE} in COUSR03C
 *       {@code [app/cbl/COUSR03C.cbl:L307-L311]}).</li>
 * </ul>
 *
 * <h2>Plaintext password preservation and log masking (AAP &sect;0.1.3, &sect;0.7.2)</h2>
 * The {@code SEC-USR-PWD PIC X(08)} field is preserved as plaintext at
 * rest for byte-for-byte parity with the COBOL baseline. This adapter
 * NEVER writes the password to a log sink &mdash; the
 * {@link SecUserData#toString()} accessor already masks the password
 * with {@code "********"}, and this adapter only logs the user-id key
 * (PIC X(08), public identifier) when emitting INFO/DEBUG/TRACE level
 * messages.
 *
 * <h2>AutoCloseable</h2>
 * {@link UserSecurityRepository} does <em>not</em> extend
 * {@link AutoCloseable}; this adapter therefore does not implement
 * {@code close()}. The reader/writer primitives are open-on-demand
 * (no persistent channel held).
 *
 * @see UserSecurityRepository
 * @see SecUserData
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @since 1.0.0
 */
@CobolProgram(
        value = "CSUSR01Y",
        sourcePath = "app/cpy/CSUSR01Y.cpy",
        translationDate = "2025-10-24",
        notes = "File-backed adapter for the USRSEC VSAM KSDS (80-byte SEC-USER-DATA; "
                + "8-byte PIC X(08) primary key at offset 0). Implements explicit "
                + "insert/update/delete triplet preserving DFHRESP(DUPKEY) vs DFHRESP(NOTFND) "
                + "observability per COUSR01C/COUSR02C/COUSR03C behaviour. Password is "
                + "plaintext-preserved at rest per AAP §0.1.3 and never logged."
)
public final class FileUserSecurityRepository implements UserSecurityRepository {

    private static final Logger LOG =
            LoggerFactory.getLogger(FileUserSecurityRepository.class);

    /**
     * The configuration key used by {@link EbcdicTranscoder} to look up
     * the per-file codepage override for this dataset.
     */
    public static final String DATASET_KEY = "usrsec";

    /**
     * Primary-key offset in the record (always 0).
     */
    public static final int KEY_OFFSET = SecUserData.SEC_USR_ID_OFFSET;

    /**
     * Primary-key length (8 bytes).
     */
    public static final int KEY_LENGTH = SecUserData.SEC_USR_ID_LENGTH;

    private final Path file;
    private final Charset charset;
    private final FixedWidthReader reader;
    private final FixedWidthWriter writer;

    /**
     * Constructs an adapter with the IBM-1047 default codepage.
     */
    public FileUserSecurityRepository(Path file) {
        this(file, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs an adapter with an explicit charset.
     */
    public FileUserSecurityRepository(Path file, Charset charset) {
        this.file = Objects.requireNonNull(file, "file");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(file, SecUserData.RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(file, SecUserData.RECORD_LENGTH, charset);
        LOG.debug("FileUserSecurityRepository configured: file={}, charset={}, recordLength={}",
                file, charset, SecUserData.RECORD_LENGTH);
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
    public Optional<SecUserData> findById(String userId) {
        validateUserId(userId);
        byte[] key = encodeUserIdKey(userId);
        try {
            return reader.findByKey(key, KEY_OFFSET).map(SecUserData::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read usrsec record (userId=" + userId.strip()
                            + ") from " + file, e);
        }
    }

    @Override
    public Stream<SecUserData> streamSequential() {
        try {
            return reader.streamSequential().map(SecUserData::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open sequential stream over " + file, e);
        }
    }

    @Override
    public Stream<SecUserData> streamFrom(String startUserId) {
        if (startUserId == null || startUserId.isBlank()) {
            return streamSequential();
        }
        validateUserId(startUserId);
        byte[] startKey = encodeUserIdKey(startUserId);
        try {
            return reader.streamFromKey(startKey, KEY_OFFSET).map(SecUserData::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open keyed stream over " + file
                            + " (startUserId=" + startUserId.strip() + ")", e);
        }
    }

    @Override
    public void insert(SecUserData user) {
        Objects.requireNonNull(user, "user");
        String userId = user.secUsrId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user.secUsrId() must not be blank");
        }
        byte[] key = encodeUserIdKey(userId);
        try {
            if (reader.findByKey(key, KEY_OFFSET).isPresent()) {
                // DFHRESP(DUPKEY)
                throw new IllegalStateException(
                        "USRSEC record already exists for userId=" + userId.strip());
            }
            byte[] buffer = user.encode();
            writer.writeAppend(buffer);
            LOG.debug("FileUserSecurityRepository.insert: userId={} appended to {}",
                    userId.strip(), file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to insert usrsec record (userId=" + userId.strip()
                            + ") to " + file, e);
        }
    }

    @Override
    public void update(SecUserData user) {
        Objects.requireNonNull(user, "user");
        String userId = user.secUsrId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user.secUsrId() must not be blank");
        }
        byte[] key = encodeUserIdKey(userId);
        try {
            if (reader.findByKey(key, KEY_OFFSET).isEmpty()) {
                // DFHRESP(NOTFND)
                throw new NoSuchElementException(
                        "USRSEC record not found for userId=" + userId.strip());
            }
            byte[] buffer = user.encode();
            writer.upsert(key, KEY_OFFSET, buffer);
            LOG.debug("FileUserSecurityRepository.update: userId={} rewritten in {}",
                    userId.strip(), file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to update usrsec record (userId=" + userId.strip()
                            + ") to " + file, e);
        }
    }

    @Override
    public void delete(String userId) {
        validateUserId(userId);
        byte[] key = encodeUserIdKey(userId);
        try {
            boolean removed = writer.deleteByKey(key, KEY_OFFSET);
            if (!removed) {
                // DFHRESP(NOTFND)
                throw new NoSuchElementException(
                        "USRSEC record not found for userId=" + userId.strip());
            }
            LOG.debug("FileUserSecurityRepository.delete: userId={} removed from {}",
                    userId.strip(), file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to delete usrsec record (userId=" + userId.strip()
                            + ") from " + file, e);
        }
    }

    /**
     * Encodes the 8-byte primary key from the supplied user-id. Shorter
     * inputs are right-space-padded per COBOL PIC X(08) convention so a
     * caller passing {@code "ADMIN"} matches the stored value
     * {@code "ADMIN   "}.
     */
    private static byte[] encodeUserIdKey(String userId) {
        byte[] key = new byte[KEY_LENGTH];
        java.util.Arrays.fill(key, (byte) ' ');
        byte[] src = userId.getBytes(StandardCharsets.US_ASCII);
        int copy = Math.min(src.length, KEY_LENGTH);
        System.arraycopy(src, 0, key, 0, copy);
        return key;
    }

    private static void validateUserId(String userId) {
        Objects.requireNonNull(userId, "userId");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (userId.length() > KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "userId must be <= " + KEY_LENGTH + " chars; got " + userId.length());
        }
    }
}
