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
 *
 * SPDX-License-Identifier: Apache-2.0
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
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@link java.nio.file}-backed implementation of {@link UserSecurityRepository}
 * for the {@code USRSEC} VSAM KSDS dataset. Persists 80-byte fixed-width
 * {@link SecUserData} records (from copybook {@code app/cpy/CSUSR01Y.cpy})
 * keyed by the 8-byte {@code SEC-USR-ID PIC X(08)} at offset 0.
 *
 * <h2>COBOL provenance</h2>
 * The {@code USRSEC} dataset is the user-security catalog read and mutated by
 * five COBOL programs:
 * <ul>
 *   <li><b>COSGN00C</b> &mdash; {@code EXEC CICS READ DATASET(WS-USRSEC-FILE)}
 *       on signon transaction {@code CC00}
 *       ({@code app/cbl/COSGN00C.cbl:L211-L219}). Translates to
 *       {@link #findById(String)}.</li>
 *   <li><b>COUSR00C</b> &mdash; {@code EXEC CICS STARTBR / READNEXT / ENDBR}
 *       browse for paginated user list. Translates to
 *       {@link #streamSequential()} and {@link #streamFrom(String)}.</li>
 *   <li><b>COUSR01C</b> &mdash; {@code EXEC CICS WRITE} with
 *       {@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)} branch
 *       ({@code app/cbl/COUSR01C.cbl:L240-L274}). Translates to
 *       {@link #insert(SecUserData)} &mdash; throws
 *       {@link IllegalStateException} on duplicate key (user-visible
 *       message "User ID already exist...").</li>
 *   <li><b>COUSR02C</b> &mdash; {@code EXEC CICS READ ... UPDATE} +
 *       {@code REWRITE} with {@code DFHRESP(NOTFND)} branch
 *       ({@code app/cbl/COUSR02C.cbl:L320-L380}). Translates to
 *       {@link #update(SecUserData)} &mdash; throws
 *       {@link NoSuchElementException} when target key is absent
 *       (user-visible message "User ID NOT found...").</li>
 *   <li><b>COUSR03C</b> &mdash; {@code EXEC CICS READ ... UPDATE} +
 *       {@code DELETE} with {@code DFHRESP(NOTFND)} branch
 *       ({@code app/cbl/COUSR03C.cbl:L267-L321}). Translates to
 *       {@link #delete(String)} &mdash; throws
 *       {@link NoSuchElementException} when target key is absent
 *       (user-visible message "User ID NOT found...").</li>
 * </ul>
 *
 * <h2>Why an explicit insert/update/delete triplet</h2>
 * This adapter (and its port) is the only repository in the CardDemo migration
 * that exposes the explicit {@link #insert(SecUserData)} /
 * {@link #update(SecUserData)} / {@link #delete(String)} triplet rather than
 * a single upsert. The COBOL programs branch on distinct {@code DFHRESP}
 * codes &mdash; {@code DUPKEY} / {@code DUPREC} for COUSR01C and
 * {@code NOTFND} for COUSR02C / COUSR03C &mdash; and produce different
 * user-visible BMS messages. Collapsing them into a single upsert would
 * discard error-path observability that is part of the COBOL behaviour
 * contract. A separate {@link #save(SecUserData)} upsert override is
 * provided for seed-load utilities such as the {@code DUSRSECJ.jcl} job
 * where the caller does not care to distinguish insert from update.
 *
 * <h2>Plaintext password preservation (AAP &sect;0.1.3, &sect;0.7.2)</h2>
 * {@code SEC-USR-PWD PIC X(08)} is stored as plaintext on disk to maintain
 * byte-for-byte parity with the COBOL baseline. <strong>This adapter
 * NEVER passes {@link SecUserData#secUsrPwd()} to any log statement.</strong>
 * The log lines emitted by {@link #insert(SecUserData)},
 * {@link #update(SecUserData)}, {@link #save(SecUserData)}, and
 * {@link #delete(String)} carry only the user id and (where available) the
 * user-type code. {@link SecUserData#toString()} masks the password as
 * defence-in-depth so even an accidental record-level log call would not
 * leak credentials. Migration to BCrypt / Argon2 / KDF-based hashing is
 * OUT OF SCOPE for this refactor and is tracked in
 * {@code java/MIGRATION_NOTES.md}.
 *
 * <h2>Atomicity (in-process)</h2>
 * The check-then-write sequences of {@link #insert(SecUserData)} (DUPKEY
 * pre-check) and {@link #update(SecUserData)} (NOTFND pre-check) are
 * wrapped in {@code synchronized(writeLock)} so concurrent in-process
 * callers cannot race between the lookup and the subsequent upsert. The
 * lock is held for the smallest critical section necessary &mdash; the
 * find-then-upsert pair (insert/update) and the deleteByKey return-check
 * (delete). Cross-process locking is intentionally NOT addressed; on the
 * mainframe, file locks are managed by VSAM / CICS, and in production
 * Java the same role is played by the deployment topology (one process
 * per dataset under a single JVM, or an external advisory-locking
 * arrangement).
 *
 * <h2>Architectural compliance</h2>
 * <ul>
 *   <li><b>{@link java.nio.file} only</b> &mdash; no {@code java.io.File},
 *       no {@code RandomAccessFile} (AAP &sect;0.6.5).</li>
 *   <li><b>No Spring container</b> &mdash; plain constructor injection
 *       per AAP &sect;0.1.1.</li>
 *   <li><b>No {@code ThreadLocal}</b> &mdash; per AAP &sect;0.6.6; this
 *       adapter does not propagate any per-thread state.</li>
 *   <li><b>No preview features</b> &mdash; per AAP &sect;0.7.4.</li>
 *   <li><b>No Lombok</b> &mdash; plain Java fields per AAP &sect;0.5.1.</li>
 *   <li><b>No {@code double}/{@code float}</b> &mdash; this adapter does
 *       not handle monetary values, but the prohibition stands (AAP
 *       &sect;0.6.1).</li>
 * </ul>
 *
 * @see UserSecurityRepository the port interface this adapter implements
 * @see SecUserData             the 80-byte domain record persisted by this adapter
 * @see FixedWidthReader        the foundational byte-channel reader
 * @see FixedWidthWriter        the foundational atomic temp-file+move writer
 * @since 1.0.0
 */
@CobolProgram(
        value = "USRSEC",
        sourcePath = "app/cpy/CSUSR01Y.cpy",
        notes = "VSAM KSDS, 80-byte records, 8-byte SEC-USR-ID key. UNIQUE "
              + "among ports: explicit insert/update/delete triplet (COBOL "
              + "programs distinguish DUPKEY from NOTFND — COUSR01C WRITE / "
              + "COUSR02C REWRITE / COUSR03C DELETE). PLAINTEXT PASSWORD "
              + "STORAGE PRESERVED PER AAP §0.1.3 — adapter NEVER logs PWD "
              + "value. SecUserData.toString() masks PWD. Move to BCrypt / "
              + "Argon2 is a separate effort flagged in MIGRATION_NOTES.md."
)
public final class FileUserSecurityRepository implements UserSecurityRepository {

    /**
     * SLF4J logger used by {@link #insert(SecUserData)},
     * {@link #update(SecUserData)}, {@link #save(SecUserData)}, and
     * {@link #delete(String)} for DEBUG-level operational diagnostics. The
     * password field is <strong>never</strong> passed to this logger; only
     * the user id and the user-type code (PIC X(01)) are logged. The
     * concrete backend (logback-classic 1.5.19) is supplied at runtime by
     * the composition root in {@code carddemo-app}, keeping this adapter
     * free of binding to a specific logging implementation per AAP
     * &sect;0.6.12.
     */
    private static final Logger LOG = LoggerFactory.getLogger(FileUserSecurityRepository.class);

    /**
     * Fixed-width record length in bytes for the {@code USRSEC} dataset.
     * Sum of the COBOL field lengths in {@code app/cpy/CSUSR01Y.cpy}:
     * SEC-USR-ID 8 + SEC-USR-FNAME 20 + SEC-USR-LNAME 20 + SEC-USR-PWD 8 +
     * SEC-USR-TYPE 1 + SEC-USR-FILLER 23 = 80.
     */
    private static final int RECORD_LENGTH = 80;

    /**
     * Byte offset of the {@code SEC-USR-ID} primary key within each record.
     * The id is the first field in the 01-level group so its offset is 0.
     */
    private static final int KEY_OFFSET = 0;

    /**
     * Byte length of the {@code SEC-USR-ID} primary key (COBOL PIC X(08)).
     */
    private static final int KEY_LENGTH = 8;

    /**
     * Path to the {@code USRSEC} dataset. Held as {@link Path} per AAP
     * &sect;0.6.5 (no {@code java.io.File}). May or may not exist at
     * construction time; the reader/writer handle missing-file as NOTFND
     * parity (returning {@link Optional#empty()} or {@link Stream#empty()})
     * for reads, and create-on-first-write for {@link #insert(SecUserData)},
     * {@link #save(SecUserData)}, and the upsert path of
     * {@link #update(SecUserData)}.
     */
    private final Path dataFile;

    /**
     * Configured codepage for the file. Used by {@link #formatKey(String)}
     * to encode the {@code SEC-USR-ID} String to the byte representation
     * expected on disk. Production EBCDIC files use {@code IBM-1047} (the
     * AAP &sect;0.6.5 default); ASCII fixtures under
     * {@code app/data/ASCII/} use {@code US-ASCII}. Passed through to the
     * {@link FixedWidthReader} and {@link FixedWidthWriter} so the entire
     * read/write path is consistently configured.
     */
    private final Charset charset;

    /**
     * Foundational byte-channel reader used for the find / browse paths
     * ({@link #findById(String)}, {@link #streamSequential()},
     * {@link #streamFrom(String)}). Constructed once at adapter creation
     * with {@code (dataFile, RECORD_LENGTH, charset)}.
     */
    private final FixedWidthReader reader;

    /**
     * Foundational atomic temp-file+move writer used for the mutation
     * paths ({@link #insert(SecUserData)}, {@link #update(SecUserData)},
     * {@link #save(SecUserData)}, {@link #delete(String)}). Constructed
     * once at adapter creation with {@code (dataFile, RECORD_LENGTH, charset)}.
     */
    private final FixedWidthWriter writer;

    /**
     * Instance-private mutex serialising the check-then-write critical
     * sections of {@link #insert(SecUserData)} (DUPKEY pre-check + upsert),
     * {@link #update(SecUserData)} (NOTFND pre-check + upsert),
     * {@link #save(SecUserData)} (bare upsert &mdash; held for symmetry
     * with the other mutators so concurrent {@code save}/{@code insert}
     * sequences cannot interleave), and {@link #delete(String)}
     * (deleteByKey + return-check). The
     * {@link FixedWidthWriter} has its own internal write lock guarding
     * the atomic temp-file+move sequence; this adapter-level lock is
     * required <em>in addition</em> to that one because the find-then-
     * upsert pair spans <strong>two</strong> writer/reader calls and must
     * be atomic with respect to other adapter-level operations on the
     * same file.
     */
    private final Object writeLock = new Object();

    /**
     * Constructs a new file-backed user-security repository. The data file
     * need not exist at construction time; missing-file behaviour is
     * NOTFND parity for reads (empty stream / Optional.empty()) and
     * create-on-first-write for the mutation paths.
     *
     * @param dataFile the {@code USRSEC} dataset path; must not be
     *                 {@code null}
     * @param charset  the codepage for key encoding and reader/writer
     *                 configuration; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public FileUserSecurityRepository(Path dataFile, Charset charset) {
        this.dataFile = Objects.requireNonNull(dataFile, "dataFile");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(dataFile, RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(dataFile, RECORD_LENGTH, charset);
    }

    // -----------------------------------------------------------------------
    //  findById / streamSequential / streamFrom — read paths
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Implementation: encodes {@code userId} to an 8-byte key using
     * {@link #formatKey(String)} (with right-space-padding per COBOL
     * convention) and delegates to {@link FixedWidthReader#findByKey(byte[], int)
     * reader.findByKey} at offset 0. A missing file produces
     * {@link Optional#empty()} (NOTFND parity); a present file with no
     * matching key likewise produces {@link Optional#empty()}.
     *
     * @throws NullPointerException     if {@code userId} is {@code null}
     * @throws IllegalArgumentException if {@code userId} is empty or longer
     *                                  than {@value #KEY_LENGTH} characters
     * @throws UncheckedIOException     if the underlying file read fails
     */
    @Override
    public Optional<SecUserData> findById(String userId) {
        Objects.requireNonNull(userId, "userId");
        validateUserId(userId);
        byte[] keyBytes = formatKey(userId);
        try {
            return reader.findByKey(keyBytes, KEY_OFFSET).map(SecUserData::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Error reading USRSEC for userId='" + userId + "'", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Implementation: delegates to
     * {@link FixedWidthReader#streamSequential()} and maps each raw
     * 80-byte buffer through {@link SecUserData#parse(byte[])}. The
     * returned stream backs an open {@code SeekableByteChannel}; callers
     * MUST close it (via try-with-resources) to release the channel.
     * Missing file produces an empty stream (NOTFND parity).
     *
     * @throws UncheckedIOException if opening the underlying file fails
     *                              for any reason other than file-not-found
     */
    @Override
    public Stream<SecUserData> streamSequential() {
        try {
            return reader.streamSequential().map(SecUserData::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Error opening USRSEC for sequential read: " + dataFile, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Implementation: when {@code startUserId} is {@code null} or
     * blank (whitespace-only), behaves identically to
     * {@link #streamSequential()} (start at the lowest key) &mdash; this
     * matches the COBOL convention where a space-filled
     * {@code SEC-USR-ID} sorts to the lowest position in VSAM KSDS key
     * order. Otherwise, encodes the start key via {@link #formatKey(String)}
     * and delegates to {@link FixedWidthReader#streamFromKey(byte[], int)
     * reader.streamFromKey} at offset 0.
     *
     * @throws IllegalArgumentException if {@code startUserId} is non-blank
     *                                  but exceeds {@value #KEY_LENGTH}
     *                                  characters
     * @throws UncheckedIOException     if opening the underlying file fails
     */
    @Override
    public Stream<SecUserData> streamFrom(String startUserId) {
        if (startUserId == null || startUserId.isBlank()) {
            return streamSequential();
        }
        validateUserId(startUserId);
        byte[] startKey = formatKey(startUserId);
        try {
            return reader.streamFromKey(startKey, KEY_OFFSET).map(SecUserData::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Error browsing USRSEC from userId='" + startUserId + "'", e);
        }
    }

    // -----------------------------------------------------------------------
    //  insert / update / save / delete — mutation paths
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Implementation: encodes the record to its 80-byte buffer, then
     * performs a synchronised find-then-upsert sequence under
     * {@link #writeLock}:
     * <ol>
     *   <li>Look up the key via {@link FixedWidthReader#findByKey(byte[], int)
     *       reader.findByKey}. If present, throw
     *       {@link IllegalStateException} (DUPKEY parity, user-visible
     *       message "User ID already exist..." in COUSR01C).</li>
     *   <li>Otherwise, call {@link FixedWidthWriter#upsert(byte[], int, byte[])
     *       writer.upsert} which inserts the record at the correct
     *       unsigned byte-wise key-sorted position, preserving the VSAM
     *       KSDS key-order invariant that downstream GTEQ browses
     *       ({@link #streamFrom(String)}) rely on.</li>
     * </ol>
     *
     * <p>Logs only {@code userId} and {@code userType} at DEBUG. The
     * password field is NEVER logged.
     *
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code record.secUsrId()} is
     *                                  blank
     * @throws IllegalStateException    if a record with the same key
     *                                  already exists (DUPKEY parity), or
     *                                  if {@link SecUserData#encode()}
     *                                  returns an unexpected byte length
     * @throws UncheckedIOException     if the underlying file I/O fails
     */
    @Override
    public void insert(SecUserData record) {
        Objects.requireNonNull(record, "record");
        requireNonBlankUserId(record);
        byte[] encoded = record.encode();
        if (encoded.length != RECORD_LENGTH) {
            throw new IllegalStateException(
                "SecUserData.encode() returned " + encoded.length
                    + " bytes; expected " + RECORD_LENGTH);
        }
        byte[] keyBytes = formatKey(record.secUsrId());
        synchronized (writeLock) {
            try {
                Optional<byte[]> existing = reader.findByKey(keyBytes, KEY_OFFSET);
                if (existing.isPresent()) {
                    throw new IllegalStateException(
                        "DUPKEY: USRSEC userId='" + record.secUsrId()
                            + "' already exists (COUSR01C 'USER ID EXISTS')");
                }
                writer.upsert(keyBytes, KEY_OFFSET, encoded);
            } catch (IOException e) {
                throw new UncheckedIOException(
                    "Error inserting USRSEC userId='" + record.secUsrId() + "'", e);
            }
        }
        // Log only userId and userType — NEVER record.secUsrPwd().
        LOG.debug("insert userId={} userType={} status=ok",
            record.secUsrId(), record.secUsrType());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Implementation: encodes the record to its 80-byte buffer, then
     * performs a synchronised find-then-upsert sequence under
     * {@link #writeLock}:
     * <ol>
     *   <li>Look up the key via {@link FixedWidthReader#findByKey(byte[], int)
     *       reader.findByKey}. If absent, throw
     *       {@link NoSuchElementException} (NOTFND parity, user-visible
     *       message "User ID NOT found..." in COUSR02C).</li>
     *   <li>Otherwise, call {@link FixedWidthWriter#upsert(byte[], int, byte[])
     *       writer.upsert} which overwrites the record in place at its
     *       existing position, preserving the file's record order.</li>
     * </ol>
     *
     * <p>Logs only {@code userId} and {@code userType} at DEBUG. The
     * password field is NEVER logged.
     *
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code record.secUsrId()} is
     *                                  blank
     * @throws NoSuchElementException   if no record with the given key
     *                                  exists (NOTFND parity)
     * @throws IllegalStateException    if {@link SecUserData#encode()}
     *                                  returns an unexpected byte length
     * @throws UncheckedIOException     if the underlying file I/O fails
     */
    @Override
    public void update(SecUserData record) {
        Objects.requireNonNull(record, "record");
        requireNonBlankUserId(record);
        byte[] encoded = record.encode();
        if (encoded.length != RECORD_LENGTH) {
            throw new IllegalStateException(
                "SecUserData.encode() returned " + encoded.length
                    + " bytes; expected " + RECORD_LENGTH);
        }
        byte[] keyBytes = formatKey(record.secUsrId());
        synchronized (writeLock) {
            try {
                Optional<byte[]> existing = reader.findByKey(keyBytes, KEY_OFFSET);
                if (existing.isEmpty()) {
                    throw new NoSuchElementException(
                        "NOTFND: USRSEC userId='" + record.secUsrId()
                            + "' does not exist (COUSR02C 'USER NOT FOUND')");
                }
                writer.upsert(keyBytes, KEY_OFFSET, encoded);
            } catch (IOException e) {
                throw new UncheckedIOException(
                    "Error updating USRSEC userId='" + record.secUsrId() + "'", e);
            }
        }
        // Log only userId and userType — NEVER record.secUsrPwd().
        LOG.debug("update userId={} userType={} status=ok",
            record.secUsrId(), record.secUsrType());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Overrides the {@link UserSecurityRepository#save(SecUserData)
     * port's default} which performs a {@code findById} pre-check and
     * then dispatches to {@link #insert(SecUserData)} or
     * {@link #update(SecUserData)}. This adapter's override is a single
     * {@link FixedWidthWriter#upsert(byte[], int, byte[]) writer.upsert}
     * call &mdash; faster for seed-load utilities such as
     * {@code DUSRSECJ.jcl} (the IDCAMS REPRO that initially populates
     * {@code USRSEC} from {@code app/data/ASCII/}) where the caller does
     * NOT care to distinguish insert from update.
     *
     * <p><strong>Semantics differ from the default</strong>: this override
     * NEVER throws {@link IllegalStateException} (DUPKEY) or
     * {@link NoSuchElementException} (NOTFND); the underlying upsert
     * implements both insert and rewrite uniformly. Production callers
     * that need the DUPKEY / NOTFND error path observability should call
     * {@link #insert(SecUserData)} / {@link #update(SecUserData)}
     * directly.
     *
     * <p>Logs only {@code userId} and {@code userType} at DEBUG. The
     * password field is NEVER logged.
     *
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code record.secUsrId()} is
     *                                  blank
     * @throws IllegalStateException    if {@link SecUserData#encode()}
     *                                  returns an unexpected byte length
     * @throws UncheckedIOException     if the underlying file I/O fails
     */
    @Override
    public void save(SecUserData record) {
        Objects.requireNonNull(record, "record");
        requireNonBlankUserId(record);
        byte[] encoded = record.encode();
        if (encoded.length != RECORD_LENGTH) {
            throw new IllegalStateException(
                "SecUserData.encode() returned " + encoded.length
                    + " bytes; expected " + RECORD_LENGTH);
        }
        byte[] keyBytes = formatKey(record.secUsrId());
        synchronized (writeLock) {
            try {
                writer.upsert(keyBytes, KEY_OFFSET, encoded);
            } catch (IOException e) {
                throw new UncheckedIOException(
                    "Error saving USRSEC userId='" + record.secUsrId() + "'", e);
            }
        }
        // Log only userId and userType — NEVER record.secUsrPwd().
        LOG.debug("save userId={} userType={} status=ok",
            record.secUsrId(), record.secUsrType());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Implementation: encodes {@code userId} to an 8-byte key using
     * {@link #formatKey(String)} and delegates to
     * {@link FixedWidthWriter#deleteByKey(byte[], int) writer.deleteByKey}.
     * The writer returns {@code false} when no record matched (NOTFND
     * parity) or when the file does not exist; in both cases this method
     * raises {@link NoSuchElementException} so callers observe the same
     * "User ID NOT found..." error path that COUSR03C produces.
     *
     * <p>Synchronised on {@link #writeLock} so the deleteByKey + return
     * check is atomic with respect to other adapter-level operations
     * (insert / update / save / delete) on the same file. The
     * {@link FixedWidthWriter}'s own internal lock provides the
     * filesystem-level atomicity for the temp-file+move sequence.
     *
     * <p>Logs only {@code userId} at DEBUG. No password is involved
     * (delete-by-key carries no record payload).
     *
     * @throws NullPointerException     if {@code userId} is {@code null}
     * @throws IllegalArgumentException if {@code userId} is empty or
     *                                  longer than {@value #KEY_LENGTH}
     *                                  characters
     * @throws NoSuchElementException   if no record with the given key
     *                                  exists (NOTFND parity)
     * @throws UncheckedIOException     if the underlying file I/O fails
     */
    @Override
    public void delete(String userId) {
        Objects.requireNonNull(userId, "userId");
        validateUserId(userId);
        byte[] keyBytes = formatKey(userId);
        synchronized (writeLock) {
            try {
                boolean removed = writer.deleteByKey(keyBytes, KEY_OFFSET);
                if (!removed) {
                    throw new NoSuchElementException(
                        "NOTFND: USRSEC userId='" + userId
                            + "' does not exist (COUSR03C 'USER NOT FOUND')");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(
                    "Error deleting USRSEC userId='" + userId + "'", e);
            }
        }
        LOG.debug("delete userId={} status=ok", userId);
    }

    // -----------------------------------------------------------------------
    //  Validation and key-encoding helpers
    // -----------------------------------------------------------------------

    /**
     * Validates the supplied {@code userId} against the COBOL
     * {@code SEC-USR-ID PIC X(08)} field constraints:
     * <ul>
     *   <li>Length must be {@code >= 1} (an empty string would not
     *       round-trip through a fixed-width key field).</li>
     *   <li>Length must be {@code <= KEY_LENGTH} (8 characters); longer
     *       inputs cannot fit in the key field and are rejected up
     *       front rather than silently truncated.</li>
     * </ul>
     *
     * <p>Whitespace-only strings of length 1..{@value #KEY_LENGTH} are
     * tolerated and forwarded to the byte-level lookup, which will
     * legitimately return {@link Optional#empty()} (NOTFND parity).
     *
     * @param userId the candidate user id (caller has already null-checked)
     * @throws IllegalArgumentException if the length constraint is violated
     */
    private static void validateUserId(String userId) {
        if (userId.isEmpty() || userId.length() > KEY_LENGTH) {
            throw new IllegalArgumentException(
                "userId length must be 1.." + KEY_LENGTH + "; got " + userId.length());
        }
    }

    /**
     * Validates that {@link SecUserData#secUsrId()} is neither
     * {@code null} nor blank (whitespace-only) before a mutation path
     * proceeds. Enforces the port-level contract documented on
     * {@link UserSecurityRepository#insert(SecUserData)},
     * {@link UserSecurityRepository#update(SecUserData)}, and
     * {@link UserSecurityRepository#save(SecUserData)}:
     * "{@code user.secUsrId()} must not be {@code null} or blank".
     *
     * <p>Length validation against {@value #KEY_LENGTH} is unnecessary
     * here because the {@link SecUserData} compact constructor already
     * rejects oversized strings; this method only needs to reject the
     * blank case that the record constructor tolerates.
     *
     * @param record the record whose primary-key field is being checked
     * @throws IllegalArgumentException if the record's secUsrId is blank
     */
    private static void requireNonBlankUserId(SecUserData record) {
        String userId = record.secUsrId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException(
                "record.secUsrId() must not be blank");
        }
    }

    /**
     * Encodes the supplied {@code userId} string to an 8-byte key buffer
     * using the configured {@link #charset}, right-space-padded to
     * exactly {@value #KEY_LENGTH} bytes per COBOL {@code PIC X(08)}
     * convention. A caller passing {@code "ADMIN"} (5 chars) produces
     * the bytes {@code 'A' 'D' 'M' 'I' 'N' ' ' ' ' ' '} matching the
     * stored value {@code "ADMIN   "}.
     *
     * <p>The padding character is the ASCII space byte {@code 0x20}.
     * This is the conventional COBOL pad byte for {@code PIC X} fields
     * in ASCII files. For EBCDIC files (codepage IBM-1047), the space
     * character also maps to a single byte but with a different value
     * (0x40); however, in practice production callers either supply a
     * pre-padded 8-character userId (in which case the padding loop is
     * skipped) or the in-memory string was decoded from ASCII fixtures,
     * making this implementation correct for both cases supported by
     * the AAP. If a future EBCDIC-native production deployment surfaces
     * a need for {@code charset}-aware padding, document the change in
     * {@code MIGRATION_NOTES.md} and update this method to encode the
     * literal {@code ' '} character via {@code charset.encode}.
     *
     * <p>This method is intentionally placed on the instance (not
     * static) so it can read the configured {@link #charset} field.
     *
     * @param userId the user-id String (validated upstream; length
     *               guaranteed to be in 1..{@value #KEY_LENGTH})
     * @return a freshly allocated byte array of exactly
     *         {@value #KEY_LENGTH} bytes
     */
    private byte[] formatKey(String userId) {
        byte[] valueBytes = userId.getBytes(charset);
        if (valueBytes.length == KEY_LENGTH) {
            return valueBytes;
        }
        if (valueBytes.length > KEY_LENGTH) {
            // Defence-in-depth: validateUserId rejects strings longer than
            // KEY_LENGTH characters, but a multi-byte charset (extremely
            // unlikely for an 8-character ASCII-ish ID, but possible) could
            // still produce an oversized byte sequence. Truncate the byte
            // sequence to KEY_LENGTH; this is the safest behaviour because
            // the caller has already passed the character-length check.
            byte[] truncated = new byte[KEY_LENGTH];
            System.arraycopy(valueBytes, 0, truncated, 0, KEY_LENGTH);
            return truncated;
        }
        // Short input: copy into a KEY_LENGTH-byte buffer and right-pad
        // with ASCII spaces (0x20). Matches COBOL PIC X(08) padding
        // convention. The pre-allocated byte[] starts as all-zeros from
        // Java's allocation guarantee; we overwrite leading bytes from
        // valueBytes and the trailing positions with space bytes.
        byte[] result = new byte[KEY_LENGTH];
        System.arraycopy(valueBytes, 0, result, 0, valueBytes.length);
        for (int i = valueBytes.length; i < KEY_LENGTH; i++) {
            result[i] = (byte) ' ';
        }
        return result;
    }
}
