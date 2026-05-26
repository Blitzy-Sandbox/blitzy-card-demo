/*
 * Copyright 2022 The CardDemo Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.blitzy.carddemo.adapter.file;

// SLF4J facade (slf4j-api 2.0.16 per parent POM dependencyManagement) is used for
// DEBUG-level operation logging and a single WARN line on atomic-move fallback.
// The concrete logging backend (logback-classic 1.5.19) is supplied at runtime
// by the composition root in carddemo-app, keeping this adapter free of binding
// to a specific backend per AAP §0.6.12.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// AAP §0.6.5 binding constraint: this module uses java.nio.file exclusively.
// java.io.File is forbidden in new code. IOException is the canonical checked
// exception surface for file I/O; ByteBuffer + SeekableByteChannel give us
// deterministic, channel-based reads/writes aligned to exact record boundaries.
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

// ArrayList is the concrete mutable list backing readAllRecords (we MUST be able
// to call set(i, record), add(record), and removeIf(...) on the result).
// List<byte[]> is the abstract type used in helper signatures.
// Objects.requireNonNull provides fast-failure with clear messages for the
// constructor and public-method parameter checks per AAP §0.6.5.
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Foundational fixed-width record writer, used by every {@code File*Repository}
 * in this package. Provides four orthogonal write primitives plus two
 * convenience operations:
 * <ul>
 *   <li>{@link #writeAppend(byte[])} &mdash; lowest-level append primitive:
 *       opens the file with {@link StandardOpenOption#WRITE},
 *       {@link StandardOpenOption#CREATE}, {@link StandardOpenOption#APPEND}
 *       and writes a single record to end-of-file. Mirrors a COBOL
 *       {@code WRITE FILE-NAME} on a file opened in {@code EXTEND} or
 *       {@code OUTPUT} mode with no key-ordering constraint, or a CICS
 *       {@code EXEC CICS WRITE} on an ESDS-style file.</li>
 *   <li>{@link #writeAtPosition(long, byte[])} &mdash; positional update
 *       primitive: opens the file with {@link StandardOpenOption#WRITE} and
 *       overwrites exactly one record at a given byte offset. The offset
 *       must be a whole multiple of {@link #recordLength()}. Mirrors a COBOL
 *       {@code REWRITE FILE-NAME} on a positionally-addressable file.</li>
 *   <li>{@link #upsert(byte[], int, byte[])} &mdash; KSDS-style insert/update
 *       primitive: matches the record whose key field equals the supplied
 *       key bytes and either {@code REWRITE}s it in place or inserts the new
 *       record in unsigned byte-wise key order. Mirrors COBOL
 *       {@code WRITE FILE-NAME} or {@code REWRITE FILE-NAME} on a file
 *       opened in {@code I-O} mode with {@code ORGANIZATION IS INDEXED}.
 *       Examples from the COBOL reference implementation:
 *       <ul>
 *         <li>{@code WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD}
 *             ({@code app/cbl/CBTRN02C.cbl} paragraph 2700-A-CREATE-TCATBAL-REC)</li>
 *         <li>{@code REWRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD}
 *             ({@code app/cbl/CBTRN02C.cbl} paragraph 2700-B-UPDATE-TCATBAL-REC)</li>
 *         <li>{@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD}
 *             ({@code app/cbl/CBTRN02C.cbl} paragraph 2800-UPDATE-ACCOUNT-REC)</li>
 *         <li>{@code EXEC CICS WRITE ...} on USRSEC
 *             ({@code app/cbl/COUSR01C.cbl} paragraph WRITE-USER-SEC-FILE)</li>
 *         <li>{@code EXEC CICS REWRITE ...} on USRSEC
 *             ({@code app/cbl/COUSR02C.cbl} paragraph UPDATE-USER-INFO)</li>
 *         <li>{@code EXEC CICS REWRITE FILE(LIT-ACCTFILENAME) ...}
 *             ({@code app/cbl/COACTUPC.cbl} paragraph 9600-WRITE-PROCESSING)</li>
 *       </ul></li>
 *   <li>{@link #deleteByKey(byte[], int)} &mdash; COBOL {@code DELETE} with
 *       {@code INVALID KEY} semantics. Examples:
 *       <ul>
 *         <li>{@code EXEC CICS DELETE ...} on USRSEC
 *             ({@code app/cbl/COUSR03C.cbl} paragraph DELETE-USER-SEC-FILE)</li>
 *       </ul></li>
 * </ul>
 *
 * <p><strong>Concurrency &mdash; class-owned write lock:</strong> all mutating
 * operations on this class are serialised by an instance-private write lock
 * {@link #writeLock}. Callers do NOT need to provide external synchronisation
 * for in-process concurrency; multiple threads (including virtual threads
 * fanned out per AAP §0.6.6) may concurrently invoke
 * {@link #writeAppend(byte[])}, {@link #writeAtPosition(long, byte[])},
 * {@link #upsert(byte[], int, byte[])}, and {@link #deleteByKey(byte[], int)}
 * on the same instance without corrupting the underlying file. Per-process
 * serialisation is intentional: cross-process file locking is a deployment
 * concern out of scope for this primitive, consistent with COBOL/CICS file
 * management where file locks are externally managed by the OS or the
 * transaction manager.
 *
 * <p>The lock is held for the smallest scope possible &mdash; only across the
 * read-modify-write or read-modify-move sequence of a single public method
 * invocation. The lock is NOT re-entrant (the public methods do not call each
 * other), and it is NOT exposed via a public accessor (which would tempt
 * callers to perform cross-method critical sections that this class is not
 * designed to support).
 *
 * <p><strong>Atomicity:</strong> {@link #upsert(byte[], int, byte[])} and
 * {@link #deleteByKey(byte[], int)} are both implemented as full-file rewrite
 * via a sibling temp file followed by an atomic
 * {@link Files#move(Path, Path, java.nio.file.CopyOption...)} using
 * {@link StandardCopyOption#ATOMIC_MOVE}. This provides crash-safety: after
 * any crash the file is either entirely the old contents or entirely the new
 * contents &mdash; never a half-written mess. This is critical for
 * {@code COACTUPC}'s {@code SYNCPOINT ROLLBACK} reconstruction at the
 * application layer (the sole rollback in the COBOL source per AAP §0.6.8).
 * If the underlying filesystem does not support atomic moves (e.g., crossing
 * mount points or some network filesystems), the writer falls back to a
 * non-atomic {@link Files#move(Path, Path, java.nio.file.CopyOption...)} with
 * {@link StandardCopyOption#REPLACE_EXISTING} and logs a WARN line.
 *
 * <p>{@link #writeAppend(byte[])} is NOT atomic in the same sense (a partial
 * write at end-of-file may leave a half-record on disk if the JVM crashes
 * mid-write), but the underlying OS write of a single record-sized buffer
 * is typically atomic in practice on POSIX filesystems with a single
 * {@code write(2)} syscall. {@link #writeAtPosition(long, byte[])} has the
 * same characteristic: a single record-sized in-place overwrite is the
 * smallest possible unit of disk mutation and is the COBOL {@code REWRITE}
 * idiom's direct analog.
 *
 * <p><strong>Performance:</strong>
 * <ul>
 *   <li>{@link #writeAppend(byte[])}: O(1) &mdash; constant-time append.</li>
 *   <li>{@link #writeAtPosition(long, byte[])}: O(1) &mdash; constant-time
 *       in-place overwrite.</li>
 *   <li>{@link #upsert(byte[], int, byte[])} and
 *       {@link #deleteByKey(byte[], int)}: O(N) per write where N is the total
 *       record count in the file. Acceptable for CardDemo's small-to-medium
 *       VSAM KSDS datasets (largest file is {@code acctdata} at ~10,000
 *       accounts &times; 300 bytes = ~3&nbsp;MB). Full-file rewrite is
 *       dramatically simpler than implementing record-level seek + insertion
 *       logic and avoids subtle bugs around partial overwrites.</li>
 * </ul>
 *
 * <p><strong>KSDS key-sorted insertion:</strong> when {@link #upsert} adds a
 * brand-new key (no existing match), the new record is inserted in the
 * correct unsigned byte-wise key-sorted position rather than appended at
 * end-of-file. This matches the VSAM KSDS guarantee that records are
 * physically stored in key order, which downstream {@link FixedWidthReader}
 * GTEQ browse logic ({@code streamFromKey}) relies on for correct cut-off
 * behavior. Callers requiring pure append semantics with no key-ordering
 * constraint (matching ESDS-style files) MUST use {@link #writeAppend(byte[])}
 * directly.
 *
 * <p><strong>Architectural constraints</strong> (binding per AAP §0.6.5 and
 * §0.7.4):
 * <ul>
 *   <li>{@code java.nio.file} exclusively; {@code java.io.File},
 *       {@link java.io.FileOutputStream}, {@link java.io.RandomAccessFile},
 *       and {@link java.io.FileWriter} are forbidden.</li>
 *   <li>No preview features; no reflection; no dynamic proxies.</li>
 *   <li>No Spring; no Hibernate; no Lombok; no Apache Commons IO; no Guava.</li>
 *   <li>No {@code double} or {@code float} (this writer is byte-level and
 *       does not touch monetary values, but the prohibition stands).</li>
 *   <li>No {@link ThreadLocal} (use {@code ScopedValue} if context propagation
 *       is needed at the calling layer).</li>
 * </ul>
 *
 * <p><strong>Resource management:</strong> all {@link SeekableByteChannel}
 * instances are opened inside try-with-resources blocks to guarantee
 * deterministic resource release, required by AAP §0.6.5
 * (no framework-managed resource scopes per §0.6.12). The temp file created
 * by {@link #writeAllAtomically(List)} is cleaned up on write failure via
 * {@link Files#deleteIfExists(Path)}; the original file is never modified
 * unless the temp file is fully written and the move succeeds.
 *
 * <p><strong>Byte-for-byte round-trip guarantee</strong> (AAP §0.6.5): this
 * writer never transcodes, normalizes, or otherwise modifies record bytes;
 * a record handed to any of the four write primitives is written verbatim,
 * byte-for-byte. The {@link #charset()} accessor is retained for symmetry
 * with {@code FixedWidthReader} and to enable consistent codepage
 * configuration via {@code carddemo.file.<dataset>.charset} application
 * properties (resolved by {@link EbcdicTranscoder}); future field-level
 * write operations may consult the charset, but the current byte-level
 * write paths are intentionally charset-agnostic.
 */
public final class FixedWidthWriter {

    /**
     * SLF4J logger; static so all instances share the same logger and so the
     * binding cost is paid once at class-load time. DEBUG lines document
     * upsert REWROTE/INSERTED/APPENDED outcomes and delete removals (with file
     * path and total record count); a single WARN line is emitted when the
     * underlying filesystem does not support
     * {@link StandardCopyOption#ATOMIC_MOVE} and the writer falls back to a
     * non-atomic replace.
     */
    private static final Logger LOG = LoggerFactory.getLogger(FixedWidthWriter.class);

    /**
     * The target data file. Non-null. May or may not exist at construction
     * time; methods on this class handle the missing-file case explicitly
     * ({@link #writeAppend(byte[])} creates the file via
     * {@link StandardOpenOption#CREATE}; {@link #deleteByKey(byte[], int)}
     * returns {@code false} on a missing file via the
     * {@link NoSuchFileException} catch).
     */
    private final Path file;

    /**
     * The fixed-width record length, in bytes. Strictly positive. Every
     * record handed to any of the four write primitives MUST be exactly
     * this length, and every record read from {@link #file} MUST be exactly
     * this length (a partial trailing record causes
     * {@link #readAllRecords()} to throw {@link IOException} with a
     * "Truncated record" message; see MIGRATION_NOTES.md §1.4.8).
     */
    private final int recordLength;

    /**
     * The configured {@link Charset} for this writer. Held for symmetry with
     * {@code FixedWidthReader} and for future field-level write operations.
     * The current byte-level write paths are charset-agnostic; the byte
     * arrays handed in by callers are written verbatim. Resolved via the
     * {@link EbcdicTranscoder} at the composition root (defaults to
     * {@code IBM-1047} per AAP §0.6.5).
     */
    private final Charset charset;

    /**
     * Instance-private mutex serializing all in-process mutations of
     * {@link #file}. Held only across the smallest possible critical section
     * of each public mutating method:
     * <ul>
     *   <li>{@link #writeAppend(byte[])}: held across the {@code newByteChannel}
     *       open + write + close sequence.</li>
     *   <li>{@link #writeAtPosition(long, byte[])}: held across the
     *       {@code newByteChannel} open + position + write + close
     *       sequence.</li>
     *   <li>{@link #upsert(byte[], int, byte[])}: held across the read-all +
     *       insert/replace + write-all-atomically sequence.</li>
     *   <li>{@link #deleteByKey(byte[], int)}: held across the read-all +
     *       remove + write-all-atomically sequence.</li>
     * </ul>
     *
     * <p>Held as a {@link Object} sentinel (the most idiomatic Java
     * monitor-based mutex; no need for the heavier
     * {@link java.util.concurrent.locks.ReentrantLock} because the lock is
     * not re-entrant in the current call graph and the critical sections do
     * not need {@code tryLock}/{@code lockInterruptibly} affordances). The
     * lock is initialized eagerly at construction so every instance has a
     * unique mutex, ruling out the cross-instance aliasing that would
     * occur if the lock were a class-level static.
     *
     * <p>Per AAP §0.6.6, this lock is the only in-process serialisation
     * primitive used here; no {@link ThreadLocal} and no {@code ScopedValue}
     * is needed because the lock guards a class instance (not a per-thread
     * variable).
     */
    private final Object writeLock = new Object();

    /**
     * Constructs a new writer for the given {@link Path} with the given
     * fixed record length and {@link Charset}. The file is NOT created or
     * touched by the constructor; the first write operation
     * ({@link #writeAppend(byte[])} or {@link #upsert(byte[], int, byte[])})
     * creates it if absent.
     *
     * @param file         the target data file; must not be {@code null}
     * @param recordLength the exact byte length of every record in the file;
     *                     must be strictly positive
     * @param charset      the configured codepage for this file; must not be
     *                     {@code null} (retained for symmetry with reader)
     * @throws NullPointerException     if {@code file} or {@code charset} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code recordLength <= 0}
     */
    public FixedWidthWriter(Path file, int recordLength, Charset charset) {
        this.file = Objects.requireNonNull(file, "file");
        if (recordLength <= 0) {
            throw new IllegalArgumentException(
                "recordLength must be > 0; got " + recordLength);
        }
        this.recordLength = recordLength;
        this.charset = Objects.requireNonNull(charset, "charset");
    }

    /**
     * Returns the target data file.
     *
     * @return the data file {@link Path}; never {@code null}
     */
    public Path file() {
        return file;
    }

    /**
     * Returns the fixed-width record length in bytes.
     *
     * @return the record length, strictly positive
     */
    public int recordLength() {
        return recordLength;
    }

    /**
     * Returns the configured {@link Charset} for this writer. Retained for
     * symmetry with {@code FixedWidthReader} and for consistent codepage
     * configuration via {@code carddemo.file.<dataset>.charset} properties
     * (resolved by {@link EbcdicTranscoder} at the composition root).
     *
     * @return the configured {@link Charset}; never {@code null}
     */
    public Charset charset() {
        return charset;
    }

    /**
     * Appends a single record at end-of-file. Lowest-level append primitive
     * mirroring a COBOL {@code WRITE FILE-NAME} on a file opened in
     * {@code EXTEND} mode (or {@code OUTPUT} mode for the first write) with
     * no key-ordering constraint.
     *
     * <p>Opens the file with {@link StandardOpenOption#WRITE},
     * {@link StandardOpenOption#CREATE}, and {@link StandardOpenOption#APPEND}.
     * The {@code CREATE} flag means the file is created if it does not
     * already exist; the {@code APPEND} flag means every write is
     * automatically positioned at end-of-file regardless of the channel's
     * current position. Parent directories are created on demand.
     *
     * <p>Synchronised on the instance-private {@link #writeLock} for the
     * duration of the channel open + write + close sequence: concurrent
     * in-process callers see a strict serial ordering of appends, matching
     * the COBOL single-process WRITE idiom.
     *
     * <p>Use cases:
     * <ul>
     *   <li>Loading an empty dataset from a captured COBOL fixture (every
     *       record is a fresh insert; no key-matching read-modify-write
     *       needed).</li>
     *   <li>Writing an audit-log or daily-reject file where records are
     *       sequential and never updated in place (e.g. the DALYREJS
     *       output of {@code app/cbl/CBTRN02C.cbl} paragraph
     *       {@code 2500-WRITE-REJECT-REC}).</li>
     *   <li>Implementing higher-level repository methods that maintain
     *       key-order themselves and prefer the cheap append path over
     *       the O(N) full-file rewrite of {@link #upsert}.</li>
     * </ul>
     *
     * @param record the record bytes to append; length must equal
     *               {@link #recordLength()}
     * @throws IOException              on filesystem errors (channel open,
     *                                  channel write, directory creation)
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code record.length != recordLength}
     */
    public void writeAppend(byte[] record) throws IOException {
        Objects.requireNonNull(record, "record");
        if (record.length != recordLength) {
            throw new IllegalArgumentException(
                "record.length (" + record.length + ") != recordLength ("
                    + recordLength + ")");
        }
        synchronized (writeLock) {
            ensureParentDirectoryExists();
            try (SeekableByteChannel ch = Files.newByteChannel(
                    file,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                ByteBuffer out = ByteBuffer.wrap(record);
                while (out.hasRemaining()) {
                    // Loop to handle short writes from the channel (rare on
                    // local filesystems but documented on the
                    // SeekableByteChannel contract).
                    ch.write(out);
                }
            }
            LOG.debug("writeAppend file={} appended 1 record (recordLength={})",
                file, recordLength);
        }
    }

    /**
     * Overwrites exactly one record at the given byte offset. Positional
     * update primitive mirroring a COBOL {@code REWRITE FILE-NAME} on a
     * positionally-addressable file, or a CICS {@code REWRITE} after a
     * preceding {@code READ FOR UPDATE} that established the record's
     * disk position.
     *
     * <p>The {@code position} argument MUST be a non-negative multiple of
     * {@link #recordLength()}; otherwise the call would overwrite the
     * boundary of two adjacent records and silently corrupt the file. This
     * alignment is validated upfront with a clear error message.
     *
     * <p>Opens the file with {@link StandardOpenOption#WRITE} (NOT
     * {@link StandardOpenOption#CREATE}; positional writes against a
     * non-existent file would create a sparse file whose leading bytes are
     * zero-filled, which would silently corrupt the data file). A
     * {@link NoSuchFileException} on a missing file is surfaced verbatim to
     * the caller because there is no meaningful "create" semantics for a
     * positional update.
     *
     * <p>Synchronised on the instance-private {@link #writeLock} for the
     * duration of the channel open + position + write + close sequence.
     *
     * @param position byte offset in the file at which to begin the
     *                 overwrite; must be a non-negative multiple of
     *                 {@link #recordLength()}
     * @param record   the record bytes to write; length must equal
     *                 {@link #recordLength()}
     * @throws IOException              on filesystem errors (file not
     *                                  found, channel open, channel write)
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code position < 0},
     *                                  {@code position} is not a multiple of
     *                                  {@code recordLength}, or
     *                                  {@code record.length != recordLength}
     */
    public void writeAtPosition(long position, byte[] record) throws IOException {
        Objects.requireNonNull(record, "record");
        if (position < 0L) {
            throw new IllegalArgumentException(
                "position must be >= 0; got " + position);
        }
        if (position % recordLength != 0L) {
            throw new IllegalArgumentException(
                "position (" + position + ") is not aligned to recordLength ("
                    + recordLength + "); overwriting at a non-record boundary "
                    + "would silently corrupt the file");
        }
        if (record.length != recordLength) {
            throw new IllegalArgumentException(
                "record.length (" + record.length + ") != recordLength ("
                    + recordLength + ")");
        }
        synchronized (writeLock) {
            try (SeekableByteChannel ch = Files.newByteChannel(
                    file,
                    StandardOpenOption.WRITE)) {
                ch.position(position);
                ByteBuffer out = ByteBuffer.wrap(record);
                while (out.hasRemaining()) {
                    ch.write(out);
                }
            }
            LOG.debug("writeAtPosition file={} position={} overwrote 1 record",
                file, position);
        }
    }

    /**
     * Inserts or overwrites the record whose key field at {@code keyOffset}
     * equals the key bytes within the record (or the supplied {@code key}
     * argument). Behavior:
     * <ul>
     *   <li>If the file does not exist, creates it (and any missing parent
     *       directories) and writes {@code record} as the sole entry.
     *       Mirrors first-load semantics on an empty KSDS.</li>
     *   <li>If a record with the same key already exists, overwrites it
     *       in place at its existing position, preserving the file's
     *       record order. Mirrors COBOL {@code REWRITE FILE-NAME}.</li>
     *   <li>If no matching key is found, INSERTS {@code record} at the
     *       correct unsigned byte-wise key-sorted position. Mirrors COBOL
     *       {@code WRITE FILE-NAME} on a VSAM KSDS, which physically
     *       stores records in key order.</li>
     * </ul>
     *
     * <p>Writes go to a sibling temp file then atomically move into place
     * via {@link StandardCopyOption#ATOMIC_MOVE}. After any crash the file
     * is either entirely the old contents or entirely the new contents
     * (never partially written). On filesystems without atomic-move
     * support, falls back to a non-atomic replace and logs WARN.
     *
     * <p>Synchronised on the instance-private {@link #writeLock} for the
     * entire read-all + modify + write-all-atomically sequence: in-process
     * concurrent {@code upsert} / {@code deleteByKey} calls cannot
     * interleave with each other and corrupt the file.
     *
     * @param key       the exact key bytes to match (length must equal the
     *                  key field length in the file's records). The {@code key}
     *                  argument is matched against bytes
     *                  {@code [keyOffset, keyOffset + key.length)} of every
     *                  existing record; it is NOT extracted from {@code record}.
     *                  Callers are responsible for providing a {@code key}
     *                  that is consistent with the key portion of {@code record}.
     * @param keyOffset the byte offset of the key field within each record;
     *                  non-negative and {@code keyOffset + key.length} must
     *                  not exceed {@code recordLength}
     * @param record    the full record bytes to insert or rewrite; length
     *                  must equal {@link #recordLength()}
     * @throws IOException              on read/write/move errors (filesystem
     *                                  failures, truncated records during
     *                                  read-modify-write)
     * @throws NullPointerException     if {@code key} or {@code record} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code record.length != recordLength},
     *                                  {@code keyOffset < 0}, or
     *                                  {@code keyOffset + key.length > recordLength}
     */
    public void upsert(byte[] key, int keyOffset, byte[] record) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(record, "record");
        if (record.length != recordLength) {
            throw new IllegalArgumentException(
                "record.length (" + record.length + ") != recordLength ("
                    + recordLength + ")");
        }
        validateKeyBounds(keyOffset, key.length);

        synchronized (writeLock) {
            // Read all existing records into memory. For CardDemo's largest
            // file (~3 MB) this is well within heap budget; for the general
            // case the O(N) read+write cost is the documented trade-off (AAP
            // §0.6.5 Key Insight #1 in the agent prompt).
            List<byte[]> records = readAllRecords();

            // Replace first matching key, or insert in key-sorted position
            // to preserve the VSAM KSDS physical-ordering guarantee that
            // downstream FixedWidthReader.streamFromKey GTEQ browse logic
            // relies on. We deliberately stop at the first match to mirror
            // VSAM KSDS unique-key semantics (a duplicate key would be an
            // INVALID KEY = DUPKEY condition in COBOL, never silently
            // duplicated).
            String outcome = "INSERTED";
            int insertIdx = records.size(); // default: append at end
            for (int i = 0; i < records.size(); i++) {
                int cmp = compareKey(records.get(i), keyOffset, key);
                if (cmp == 0) {
                    // Exact key match → overwrite in place.
                    records.set(i, record);
                    insertIdx = -1; // signal "no insert needed"
                    outcome = "REWROTE";
                    break;
                }
                if (cmp > 0) {
                    // First record with key strictly greater than the new
                    // key → new record belongs at index i (insert before
                    // this record, preserving key-sorted order).
                    insertIdx = i;
                    break;
                }
            }
            if (insertIdx >= 0) {
                records.add(insertIdx, record);
            }

            writeAllAtomically(records);
            LOG.debug("upsert file={} {} 1 record (totalRecords={})",
                file, outcome, records.size());
        }
    }

    /**
     * Removes the record whose key field at {@code keyOffset} equals
     * {@code key}. Returns {@code true} if a record was removed, {@code false}
     * if no matching key exists. Mirrors COBOL {@code DELETE FILE-NAME} with
     * {@code INVALID KEY} semantics: the boolean return value lets the
     * caller distinguish NOTFND from a successful delete and throw
     * {@link java.util.NoSuchElementException} (or repository-specific
     * equivalent) at the application layer.
     *
     * <p>If the file does not exist, returns {@code false} without
     * throwing &mdash; a missing file is treated as "no records to match",
     * consistent with VSAM NOTFND parity. The missing-file check is
     * race-safe (CWE-367 TOCTOU hardening): handled by catching
     * {@link NoSuchFileException} from the underlying channel open inside
     * {@link #readAllRecords()}, never by a separate {@code Files.exists}
     * pre-check.
     *
     * <p>Successful deletes go through the same atomic temp-write + move
     * path as upserts (full-file rewrite). Synchronised on the
     * instance-private {@link #writeLock} for the entire read-all + remove
     * + write-all-atomically sequence.
     *
     * @param key       the exact key bytes to match; must not be {@code null}
     * @param keyOffset the byte offset of the key field within each record;
     *                  non-negative and {@code keyOffset + key.length} must
     *                  not exceed {@code recordLength}
     * @return {@code true} if exactly one record was removed; {@code false}
     *         if no matching key existed (NOTFND parity) or if the file
     *         does not exist
     * @throws IOException              on read/write/move errors
     * @throws NullPointerException     if {@code key} is {@code null}
     * @throws IllegalArgumentException if {@code keyOffset < 0} or
     *                                  {@code keyOffset + key.length > recordLength}
     */
    public boolean deleteByKey(byte[] key, int keyOffset) throws IOException {
        Objects.requireNonNull(key, "key");
        validateKeyBounds(keyOffset, key.length);

        synchronized (writeLock) {
            List<byte[]> records;
            try {
                records = readAllRecords();
            } catch (NoSuchFileException missing) {
                // Defensive: readAllRecords already handles this internally,
                // but if a future refactor surfaces NoSuchFileException
                // here, preserve NOTFND parity rather than propagating.
                return false;
            }
            int sizeBefore = records.size();
            boolean removed = records.removeIf(r -> keyMatches(r, keyOffset, key));
            if (!removed) {
                return false;
            }

            writeAllAtomically(records);
            LOG.debug("delete file={} removed=1 records (was {}, now {})",
                file, sizeBefore, records.size());
            return true;
        }
    }

    // ------------------------------------------------------------------
    //  Private helpers
    // ------------------------------------------------------------------

    /**
     * Overflow-safe key-bounds validation shared by {@link #upsert} and
     * {@link #deleteByKey} (CWE-20 + CWE-190 hardening). The naive form
     * {@code keyOffset + keyLength > recordLength} is incorrect when
     * {@code keyOffset} or {@code keyLength} is large enough that
     * {@code keyOffset + keyLength} overflows a 32-bit signed int and wraps
     * to a negative value, which would pass the {@code > recordLength}
     * comparison. The corrected form validates {@code keyLength} against
     * {@code recordLength} first (so the subtraction is non-negative and
     * overflow-free), then compares {@code keyOffset} against the subtracted
     * form, which can never overflow because both operands are non-negative.
     *
     * @param keyOffset the byte offset of the key field within each record
     * @param keyLength the length of the key in bytes
     * @throws IllegalArgumentException if the bounds are invalid for this
     *                                  writer's {@link #recordLength}
     */
    private void validateKeyBounds(int keyOffset, int keyLength) {
        if (keyOffset < 0) {
            throw new IllegalArgumentException(
                "keyOffset (" + keyOffset + ") < 0");
        }
        if (keyLength < 0 || keyLength > recordLength) {
            throw new IllegalArgumentException(
                "keyLength (" + keyLength + ") exceeds recordLength ("
                    + recordLength + ")");
        }
        if (keyOffset > recordLength - keyLength) {
            throw new IllegalArgumentException(
                "keyOffset (" + keyOffset + ") + keyLength (" + keyLength
                    + ") out of range for recordLength (" + recordLength + ")");
        }
    }

    /**
     * Creates the parent directory of {@link #file} if it does not exist.
     * Race-safe (CWE-367 TOCTOU hardening): uses
     * {@link Files#createDirectories(Path, java.nio.file.attribute.FileAttribute...)},
     * which is documented to be a no-op if the directory already exists,
     * instead of an {@code exists}-check + {@code createDirectory} pair that
     * a concurrent {@code rmdir} could break between the check and the
     * create. The {@link FileAlreadyExistsException} that {@code createDirectory}
     * (without the 's') would throw if a file (not a directory) exists at
     * the target path is also handled by {@code createDirectories}: it
     * passes through as a checked {@code IOException} with a clear message.
     *
     * @throws IOException if the parent directory cannot be created (e.g.,
     *                     because a regular file exists at the target path)
     */
    private void ensureParentDirectoryExists() throws IOException {
        // Use toAbsolutePath() so that relative file paths like
        // "data/acctdata" resolve their parent against the JVM working
        // directory rather than returning null from getParent().
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            // Files.createDirectories is idempotent and race-safe: it walks
            // the path and creates any missing intermediate directories,
            // tolerating a concurrent third party that creates the same
            // directories between calls. It throws FileAlreadyExistsException
            // (a checked IOException subtype) only if a regular file exists
            // at the target path, which is a legitimate hard error worth
            // surfacing immediately.
            Files.createDirectories(parent);
        }
    }

    /**
     * Reads the entire file into memory as a list of fixed-width record
     * buffers. Returns an empty mutable list if the file does not exist
     * (consistent with COBOL parity: opening an empty/absent file for
     * reading yields end-of-file immediately, not an error).
     *
     * <p>Race-safe missing-file detection (CWE-367 TOCTOU hardening):
     * handled by catching {@link NoSuchFileException} on the channel open
     * rather than by a separate {@code Files.exists} pre-check, eliminating
     * the window in which a concurrent rename or unlink could change the
     * file between the check and the open.
     *
     * <p>Each returned buffer is a fresh {@code byte[recordLength]} (not a
     * shared slice of a larger buffer) so that callers may mutate the
     * returned list (e.g., via {@code set(i, newRecord)} in
     * {@link #upsert(byte[], int, byte[])}) without aliasing issues.
     *
     * <p>The read loop handles short reads from the channel by looping
     * until {@code recordLength} bytes are accumulated (or EOF). A partial
     * trailing record (some but not all of {@code recordLength} bytes
     * remaining at EOF) is treated as file corruption and surfaces as
     * {@link IOException}; this preserves the byte-for-byte round-trip
     * invariant ({@code parse(record).encode()} MUST equal the original
     * buffer, per AAP §0.6.5) by refusing to silently lose data. See
     * MIGRATION_NOTES.md §1.4.8 for the documented DEVIATION.
     *
     * @return a fresh mutable {@link ArrayList} of record buffers
     * @throws IOException on channel open/read errors or on detection of a
     *                     truncated trailing record
     */
    private List<byte[]> readAllRecords() throws IOException {
        List<byte[]> records = new ArrayList<>();
        final SeekableByteChannel ch;
        try {
            ch = Files.newByteChannel(file, StandardOpenOption.READ);
        } catch (NoSuchFileException missing) {
            // Race-safe NOTFND parity: opening a non-existent file yields
            // an empty list, the same behavior a COBOL OPEN INPUT followed
            // by a single READ AT END would produce on an empty dataset.
            return records;
        }
        try (SeekableByteChannel auto = ch) {
            ByteBuffer buf = ByteBuffer.allocate(recordLength);
            while (true) {
                buf.clear();
                int total = 0;
                while (total < recordLength) {
                    int r = auto.read(buf);
                    if (r < 0) {
                        // End-of-file reached.
                        if (total == 0) {
                            // Clean EOF on a record boundary: normal
                            // termination.
                            return records;
                        }
                        // Partial record: file is truncated. Refuse to
                        // silently lose data; bubble up as IOException so
                        // the caller can surface a COBOL FILE STATUS
                        // equivalent at the application layer (see
                        // MIGRATION_NOTES.md §1.4.8).
                        throw new IOException(
                            "Truncated record in " + file + " (read " + total
                                + " bytes; expected " + recordLength + ")");
                    }
                    total += r;
                }
                byte[] copy = new byte[recordLength];
                System.arraycopy(buf.array(), 0, copy, 0, recordLength);
                records.add(copy);
            }
        }
    }

    /**
     * Atomically replaces {@link #file} with the concatenation of
     * {@code records}. The procedure is:
     * <ol>
     *   <li>Ensure the parent directory of {@link #file} exists (creating
     *       it if necessary; supports first-write of a fresh dataset).</li>
     *   <li>Create a sibling temp file (same directory as {@link #file},
     *       prefix = {@code <fileName>.}, suffix = {@code .tmp}) so that the
     *       atomic move stays within a single filesystem volume.</li>
     *   <li>Open the temp file with
     *       {@link StandardOpenOption#WRITE} + {@link StandardOpenOption#TRUNCATE_EXISTING}
     *       and stream each record into it via channel writes, looping while
     *       {@link ByteBuffer#hasRemaining()} to handle short writes.</li>
     *   <li>If any record's length does not match {@link #recordLength},
     *       throw {@link IllegalStateException} (invariant violation) and
     *       clean up the temp file.</li>
     *   <li>{@link Files#move(Path, Path, java.nio.file.CopyOption...)} the
     *       temp file over {@link #file} with
     *       {@link StandardCopyOption#ATOMIC_MOVE} and
     *       {@link StandardCopyOption#REPLACE_EXISTING}. If atomic move is
     *       unsupported (e.g., cross-mount or some network filesystems),
     *       fall back to a non-atomic replace and log WARN.</li>
     * </ol>
     *
     * <p>If any step before the move fails, the temp file is deleted via
     * {@link Files#deleteIfExists(Path)} (suppression-chained on the
     * original exception so neither cause is lost). The original
     * {@link #file} is never modified unless the temp file is fully
     * written.
     *
     * @param records the records to write (in order); every element must
     *                have length {@link #recordLength}
     * @throws IOException           on filesystem errors (channel open,
     *                               channel write, directory creation,
     *                               temp-file creation, move)
     * @throws IllegalStateException if any record's length does not match
     *                               {@link #recordLength}
     */
    private void writeAllAtomically(List<byte[]> records) throws IOException {
        // Ensure parent directory exists for first-write via the race-safe
        // helper. Files.createDirectories is the idempotent primitive that
        // replaces the previous exists-check + createDirectory pair.
        ensureParentDirectoryExists();
        Path parent = file.toAbsolutePath().getParent();

        // Create a sibling temp file. Same directory => same filesystem
        // volume => Files.move with ATOMIC_MOVE is guaranteed to be
        // supported on POSIX (rename(2)) and on Windows (MoveFileEx with
        // MOVEFILE_REPLACE_EXISTING). The "." fallback handles the rare
        // case of a file with no parent (e.g., bare filename "x" in
        // current directory) defensively.
        Path tmp = Files.createTempFile(
            parent != null ? parent : Path.of("."),
            file.getFileName().toString() + ".",
            ".tmp");
        try (SeekableByteChannel ch = Files.newByteChannel(
                tmp,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            for (byte[] r : records) {
                if (r.length != recordLength) {
                    // Defensive invariant check. Should be unreachable in
                    // the normal flow because upsert validates record.length
                    // and readAllRecords always returns byte[recordLength]
                    // copies, but a corrupt caller (or future contributor)
                    // bypassing upsert MUST be caught here, not after the
                    // atomic move produces a corrupted file.
                    throw new IllegalStateException(
                        "Invariant violated: record length " + r.length
                            + " != expected " + recordLength);
                }
                ByteBuffer out = ByteBuffer.wrap(r);
                while (out.hasRemaining()) {
                    // Loop to handle short writes from the channel (rare on
                    // local filesystems but documented possibility on the
                    // SeekableByteChannel contract).
                    ch.write(out);
                }
            }
        } catch (IOException e) {
            // Clean up the temp file on any write failure so we don't leak
            // orphaned ".tmp" files. Suppression-chain the cleanup exception
            // (if any) so neither cause is lost.
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException suppress) {
                e.addSuppressed(suppress);
            }
            throw e;
        } catch (IllegalStateException e) {
            // Same cleanup path for invariant violations; the original
            // exception is re-thrown after best-effort temp removal.
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException suppress) {
                e.addSuppressed(suppress);
            }
            throw e;
        }

        try {
            Files.move(tmp, file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Filesystem (e.g., some network mounts, some FUSE drivers) does
            // not support atomic moves. Fall back to a non-atomic replace,
            // which is still safer than direct overwrite: the temp file is
            // either fully written or fully absent, so even the non-atomic
            // move only ever exposes a complete copy (it just may not be
            // atomic with respect to concurrent readers on the original).
            LOG.warn("Atomic move not supported for {}; falling back to replace", file);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Byte-equality check between the {@code expected} key bytes and the
     * slice of {@code buffer} at {@code [offset, offset + expected.length)}.
     * Charset-agnostic: matches at the raw byte level, so EBCDIC, ASCII,
     * and binary keys all work uniformly. Returns {@code false} (rather
     * than throwing) if {@code buffer} is shorter than
     * {@code offset + expected.length}, treating undersized buffers as
     * non-matches.
     *
     * <p>The undersized-buffer guard uses overflow-safe arithmetic
     * ({@code buffer.length - expected.length} compared against
     * {@code offset}) so a pathologically large {@code offset} or
     * {@code expected.length} cannot bypass the check (CWE-20 + CWE-190
     * hardening, matching the form used by
     * {@link #validateKeyBounds(int, int)}).
     *
     * @param buffer   the record buffer to check; non-null
     * @param offset   the key field offset within {@code buffer}; non-negative
     * @param expected the exact key bytes to match; non-null
     * @return {@code true} iff every byte at
     *         {@code buffer[offset+i] == expected[i]} for {@code 0 <= i < expected.length}
     */
    private static boolean keyMatches(byte[] buffer, int offset, byte[] expected) {
        // Overflow-safe undersized-buffer guard. The naive
        // `buffer.length < offset + expected.length` overflows when
        // offset + expected.length wraps to a negative value, which would
        // make the comparison pass and lead to AIOOBE inside the loop.
        if (offset < 0 || expected.length < 0
            || expected.length > buffer.length
            || offset > buffer.length - expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (buffer[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Unsigned byte-wise comparison of
     * {@code buffer[offset..offset + key.length)} versus {@code key}.
     * Returns a negative integer, zero, or a positive integer if the buffer
     * slice is less than, equal to, or greater than {@code key} respectively,
     * a la {@link Comparable#compareTo(Object) Comparable.compareTo}. Used
     * by {@link #upsert(byte[], int, byte[])} to maintain unsigned-key
     * ordering when inserting a new record.
     *
     * <p>Unsigned comparison ({@code byte & 0xFF}) matches COBOL collating
     * sequence for ASCII text keys, zoned-decimal numeric keys ('0'..'9' =
     * 0x30..0x39 in ASCII), and binary keys whose bytes may exceed 0x7F. For
     * pure ASCII (0x00..0x7F) the result is identical to signed comparison;
     * the unsigned form is required for correctness when the source data is
     * EBCDIC or binary.
     *
     * @param buffer the record buffer
     * @param offset the byte offset within {@code buffer} where the key field
     *               begins
     * @param key    the key bytes to compare against
     * @return {@code < 0}, {@code 0}, or {@code > 0} per {@link Comparable}
     */
    private static int compareKey(byte[] buffer, int offset, byte[] key) {
        for (int i = 0; i < key.length; i++) {
            int a = buffer[offset + i] & 0xFF;
            int b = key[i] & 0xFF;
            if (a != b) {
                return a - b;
            }
        }
        return 0;
    }
}
