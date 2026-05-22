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
// The concrete logging backend (logback-classic 1.5.12) is supplied at runtime
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
import java.nio.file.Files;
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
 * in this package. Provides upsert and delete-by-key operations, mirroring
 * COBOL VSAM KSDS write patterns:
 * <ul>
 *   <li>{@link #upsert(byte[], int, byte[])} &mdash; COBOL {@code WRITE} (new
 *       key) or {@code REWRITE} (existing key) on a file opened in {@code I-O}
 *       mode. Examples from the COBOL reference implementation:
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
 * <p><strong>Concurrency:</strong> instances are NOT thread-safe by themselves.
 * Concurrent mutation MUST be serialized by the caller via {@code synchronized}
 * on a per-repository {@code writeLock} object. Cross-process locking is NOT
 * addressed; that is a deployment concern, consistent with COBOL/CICS file
 * management where file locks are externally managed by the OS or the
 * transaction manager.
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
 * <p><strong>Performance:</strong> O(N) per write where N is the total record
 * count in the file. Acceptable for CardDemo's small-to-medium VSAM KSDS
 * datasets (largest file is {@code acctdata} at ~10,000 accounts &times; 300
 * bytes = ~3&nbsp;MB). Full-file rewrite is dramatically simpler than
 * implementing record-level seek + insertion logic and avoids subtle bugs
 * around partial overwrites.
 *
 * <p><strong>Append vs. sorted insert:</strong> when {@link #upsert} adds a
 * brand-new key (no existing match), it APPENDS the record at end-of-file to
 * preserve I/O efficiency. Callers requiring sorted-on-write semantics
 * (matching KSDS exact behavior) MUST sort independently after upserts; that
 * is intentionally out of scope for this foundational writer.
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
 * a record handed to {@link #upsert(byte[], int, byte[])} is written
 * verbatim, byte-for-byte. The {@link #charset()} accessor is retained for
 * symmetry with {@code FixedWidthReader} and to enable consistent codepage
 * configuration via {@code carddemo.file.<dataset>.charset} application
 * properties (resolved by {@link EbcdicTranscoder}); future field-level
 * write operations may consult the charset, but the current byte-level
 * write paths are intentionally charset-agnostic.
 */
public final class FixedWidthWriter {

    /**
     * SLF4J logger; static so all instances share the same logger and so the
     * binding cost is paid once at class-load time. DEBUG lines document
     * upsert REWROTE/APPENDED outcomes and delete removals (with file path
     * and total record count); a single WARN line is emitted when the
     * underlying filesystem does not support {@link StandardCopyOption#ATOMIC_MOVE}
     * and the writer falls back to a non-atomic replace.
     */
    private static final Logger LOG = LoggerFactory.getLogger(FixedWidthWriter.class);

    /**
     * The target data file. Non-null. May or may not exist at construction
     * time; methods on this class handle the missing-file case explicitly
     * ({@link #upsert(byte[], int, byte[])} creates a new file with the
     * record as its sole entry; {@link #deleteByKey(byte[], int)} returns
     * {@code false}).
     */
    private final Path file;

    /**
     * The fixed-width record length, in bytes. Strictly positive. Every
     * record handed to {@link #upsert(byte[], int, byte[])} MUST be exactly
     * this length, and every record read from {@link #file} MUST be exactly
     * this length (a partial trailing record causes
     * {@link #readAllRecords()} to throw {@link IOException} with a
     * "Truncated record" message).
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
     * Constructs a new writer for the given {@link Path} with the given
     * fixed record length and {@link Charset}. The file is NOT created or
     * touched by the constructor; the first write operation
     * ({@link #upsert(byte[], int, byte[])}) creates it if absent.
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
     *   <li>If no matching key is found, APPENDS {@code record} at end of
     *       file. Mirrors COBOL {@code WRITE FILE-NAME}. Callers requiring
     *       sorted-on-write semantics (KSDS exact behavior) MUST sort
     *       independently after upserts; we preserve append-order to
     *       minimize I/O churn.</li>
     * </ul>
     *
     * <p>Writes go to a sibling temp file then atomically move into place
     * via {@link StandardCopyOption#ATOMIC_MOVE}. After any crash the file
     * is either entirely the old contents or entirely the new contents
     * (never partially written). On filesystems without atomic-move
     * support, falls back to a non-atomic replace and logs WARN.
     *
     * <p>NOT thread-safe; concurrent mutation MUST be serialized by the
     * caller. Caller should hold a {@code synchronized(writeLock)} block
     * during any read-modify-write sequence (e.g., "check exists, then
     * upsert").
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
        if (keyOffset < 0 || keyOffset + key.length > recordLength) {
            throw new IllegalArgumentException(
                "keyOffset (" + keyOffset + ") + key.length (" + key.length
                    + ") out of range for recordLength (" + recordLength + ")");
        }

        // Read all existing records into memory. For CardDemo's largest file
        // (~3 MB) this is well within heap budget; for the general case the
        // O(N) read+write cost is the documented trade-off (AAP §0.6.5 Key
        // Insight #1 in the agent prompt).
        List<byte[]> records = readAllRecords();

        // Replace first matching key, or append. We deliberately stop at the
        // first match to mirror VSAM KSDS unique-key semantics (a duplicate
        // key would be an INVALID KEY = DUPKEY condition in COBOL, never
        // silently duplicated).
        boolean replaced = false;
        for (int i = 0; i < records.size(); i++) {
            if (keyMatches(records.get(i), keyOffset, key)) {
                records.set(i, record);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            records.add(record);
        }

        writeAllAtomically(records);
        LOG.debug("upsert file={} {} 1 record (totalRecords={})",
            file, replaced ? "REWROTE" : "APPENDED", records.size());
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
     * consistent with VSAM NOTFND parity.
     *
     * <p>Successful deletes go through the same atomic temp-write + move
     * path as upserts (full-file rewrite). NOT thread-safe; concurrent
     * mutation MUST be serialized by the caller.
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
        if (keyOffset < 0 || keyOffset + key.length > recordLength) {
            throw new IllegalArgumentException(
                "keyOffset (" + keyOffset + ") + key.length (" + key.length
                    + ") out of range for recordLength (" + recordLength + ")");
        }
        if (!Files.exists(file)) {
            return false;
        }

        List<byte[]> records = readAllRecords();
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

    /**
     * Reads the entire file into memory as a list of fixed-width record
     * buffers. Returns an empty mutable list if the file does not exist
     * (consistent with COBOL parity: opening an empty/absent file for
     * reading yields end-of-file immediately, not an error).
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
     * buffer, per AAP §0.6.5) by refusing to silently lose data.
     *
     * @return a fresh mutable {@link ArrayList} of record buffers
     * @throws IOException on channel open/read errors or on detection of a
     *                     truncated trailing record
     */
    private List<byte[]> readAllRecords() throws IOException {
        List<byte[]> records = new ArrayList<>();
        if (!Files.exists(file)) {
            return records;
        }
        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(recordLength);
            while (true) {
                buf.clear();
                int total = 0;
                while (total < recordLength) {
                    int r = ch.read(buf);
                    if (r < 0) {
                        // End-of-file reached.
                        if (total == 0) {
                            // Clean EOF on a record boundary: normal termination.
                            return records;
                        }
                        // Partial record: file is truncated. Refuse to silently
                        // lose data; bubble up as IOException so the caller can
                        // surface a COBOL FILE STATUS equivalent at the
                        // application layer.
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
        // Ensure parent directory exists for first-write. Using
        // toAbsolutePath() so that relative file paths like "data/acctdata"
        // resolve their parent against the JVM working directory rather
        // than returning null from getParent().
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        // Create a sibling temp file. Same directory => same filesystem volume
        // => Files.move with ATOMIC_MOVE is guaranteed to be supported on POSIX
        // (rename(2)) and on Windows (MoveFileEx with MOVEFILE_REPLACE_EXISTING).
        // The "." fallback handles the rare case of a file with no parent
        // (e.g., bare filename "x" in current directory) defensively.
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
                    // Defensive invariant check. Should be unreachable in the
                    // normal flow because upsert validates record.length and
                    // readAllRecords always returns byte[recordLength] copies,
                    // but a corrupt caller (or future contributor) bypassing
                    // upsert MUST be caught here, not after the atomic move
                    // produces a corrupted file.
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
     * @param buffer   the record buffer to check; non-null
     * @param offset   the key field offset within {@code buffer}; non-negative
     * @param expected the exact key bytes to match; non-null
     * @return {@code true} iff every byte at
     *         {@code buffer[offset+i] == expected[i]} for {@code 0 <= i < expected.length}
     */
    private static boolean keyMatches(byte[] buffer, int offset, byte[] expected) {
        if (buffer.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (buffer[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
