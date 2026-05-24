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
// a single WARN-level diagnostic in the streamSequential() Stream.onClose() hook
// if the underlying SeekableByteChannel cannot be released cleanly. The concrete
// logging backend (logback-classic 1.5.12) is supplied at runtime by the
// composition root in carddemo-app, keeping this adapter free of binding to a
// specific backend per AAP §0.6.12.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// AAP §0.6.5 binding constraint: this module uses java.nio.file exclusively.
// java.io.File is forbidden in new code. IOException is the canonical checked
// exception surface for file I/O. UncheckedIOException wraps a checked
// IOException raised from within an Iterator.hasNext() lambda, where the
// Iterator API does not permit declaring checked exceptions.
import java.io.IOException;
import java.io.UncheckedIOException;

// ByteBuffer is the fixed-size record buffer used by findByKey() and the
// helper readOneRecord() to fill exactly recordLength bytes from the
// SeekableByteChannel, supporting the byte-for-byte fidelity contract per
// AAP §0.6.5. The backing byte[] is exposed via ByteBuffer.array() for direct
// comparison and return to callers.
import java.nio.ByteBuffer;

// SeekableByteChannel is the primary I/O abstraction returned by
// Files.newByteChannel(...) and used to read fixed-width records byte-by-byte
// in findByKey, readOneRecord, and streamSequential (via channel captured by
// the onClose hook on the returned Stream). Mandated by AAP §0.6.5 file I/O
// exactness: "All file I/O uses java.nio.file (Files.newByteChannel,
// SeekableByteChannel, ...)".
import java.nio.channels.SeekableByteChannel;

// Charset is held as a final constructor-injected field for symmetry with
// FixedWidthWriter (which uses it for byte-level transcoding decisions) and
// for future EBCDIC byte-level options (IBM-1047 default per AAP §0.6.5). The
// read paths in this class are intentionally charset-agnostic because records
// are parsed by the domain layer's record.parse(byte[]) factories per AAP
// §0.6.5; the reader's responsibility ends at producing exact byte buffers.
import java.nio.charset.Charset;

// Foundational java.nio.file primitives mandated by AAP §0.6.5:
//   Path                — immutable file location held as a final field
//   Files.exists(...)   — used to return Optional.empty()/Stream.empty() on
//                         missing files (NOTFND parity with COBOL VSAM)
//   Files.newByteChannel(file, READ) — opens a SeekableByteChannel in read-only
//                                       mode (try-with-resources in findByKey;
//                                       ownership transferred to the returned
//                                       Stream's onClose hook in streamSequential).
// NO java.io.File, NO java.io.FileInputStream, NO java.io.RandomAccessFile,
// NO java.io.FileReader — all explicitly FORBIDDEN per AAP §0.6.5 and the
// file's agent_prompt Phase 2 forbidden-imports list.
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

// java.util collection utilities for the streaming and key-lookup pipelines:
//   Iterator                — anonymous inner class wired into streamSequential
//                             to lazily read one record at a time.
//   NoSuchElementException  — thrown by the iterator's next() when called
//                             after hasNext() returned false (Iterator contract).
//   Objects                 — Objects.requireNonNull(...) for eager argument
//                             validation in the constructor and public methods.
//   Optional                — return type of findByKey to express NOTFND-equivalent
//                             absence (parity with COBOL VSAM READ KEY IS WS-KEY
//                             returning FILE STATUS '23').
//   Spliterator, Spliterators — Spliterators.spliteratorUnknownSize(iterator,
//                                ORDERED | NONNULL) preserves COBOL physical
//                                record order and excludes null elements for
//                                StreamSupport.stream(...).
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;

// java.util.stream primitives:
//   Stream<byte[]>          — lazy return type of streamSequential and
//                              streamFromKey representing a COBOL sequential
//                              read loop / GTEQ browse.
//   StreamSupport.stream(spliterator, parallel=false) — bridge from the
//                              iterator/spliterator into a sequential Stream
//                              pipeline. Parallelism is forbidden here because
//                              record order is observable and must match the
//                              COBOL baseline byte-for-byte per AAP §0.6.5.
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Foundational fixed-width record reader, used by every {@code File*Repository}
 * in this package. Provides sequential streaming, random key lookup, and GTEQ
 * browse modes, mirroring COBOL VSAM KSDS access patterns:
 * <ul>
 *   <li>{@link #streamSequential()} &mdash; COBOL {@code READ FILE-NAME} loop
 *       with {@code AT END} detection. Examples from the COBOL reference
 *       implementation:
 *       <ul>
 *         <li>{@code READ ACCTFILE-FILE INTO ACCOUNT-RECORD}
 *             ({@code app/cbl/CBACT01C.cbl} paragraph 1000-ACCTFILE-GET-NEXT)</li>
 *         <li>{@code READ CARDFILE-FILE INTO CARD-RECORD}
 *             ({@code app/cbl/CBACT02C.cbl} paragraph 1000-CARDFILE-GET-NEXT)</li>
 *         <li>{@code READ XREFFILE-FILE INTO CARD-XREF-RECORD}
 *             ({@code app/cbl/CBACT03C.cbl} paragraph 1000-XREFFILE-GET-NEXT)</li>
 *         <li>{@code READ CUSTFILE-FILE INTO CUSTOMER-RECORD}
 *             ({@code app/cbl/CBCUS01C.cbl} paragraph 1000-CUSTFILE-GET-NEXT)</li>
 *       </ul></li>
 *   <li>{@link #findByKey(byte[], int)} &mdash; COBOL
 *       {@code READ FILE-NAME KEY IS WS-KEY} (KSDS direct random read by key).
 *       Returns {@link Optional#empty()} on FILE STATUS '23' (NOTFND).</li>
 *   <li>{@link #streamFromKey(byte[], int)} &mdash; COBOL
 *       {@code STARTBR FILE-NAME RIDFLD(WS-KEY) GTEQ} followed by a
 *       {@code READNEXT} loop. Yields all records whose key is greater than or
 *       equal to the supplied {@code startKey}, in physical order.</li>
 * </ul>
 *
 * <p><strong>Architectural constraints</strong> (binding per AAP §0.6.5 and
 * §0.7.4):
 * <ul>
 *   <li>{@code java.nio.file} exclusively; {@code java.io.File},
 *       {@link java.io.FileInputStream}, {@link java.io.FileReader}, and
 *       {@link java.io.RandomAccessFile} are forbidden.</li>
 *   <li>No preview features; no reflection; no dynamic proxies.</li>
 *   <li>Idiom-for-idiom COBOL translation; per-record work is read serially to
 *       preserve observable order. Stream parallelism is intentionally NOT
 *       enabled.</li>
 * </ul>
 *
 * <p><strong>Concurrency:</strong> this class is thread-safe for read
 * operations: each public method opens a fresh {@link SeekableByteChannel} and
 * shares no mutable state between calls. Long-lived
 * {@link #streamSequential() streamSequential} and
 * {@link #streamFromKey(byte[], int) streamFromKey} operations transfer
 * ownership of the underlying channel to the returned {@link Stream}; callers
 * MUST close the stream (via try-with-resources) to release the channel.
 *
 * <p><strong>NOTFND parity:</strong> A missing file is treated as functionally
 * equivalent to a populated file in which no record matches the given key:
 * {@link #findByKey(byte[], int) findByKey} returns {@link Optional#empty()};
 * {@link #streamSequential() streamSequential} returns {@link Stream#empty()};
 * {@link #streamFromKey(byte[], int) streamFromKey} returns
 * {@link Stream#empty()}. This matches COBOL {@code READ} on an empty or
 * missing dataset where the program checks FILE STATUS '23' (NOTFND) or '10'
 * (EOF) and treats both as "no data". The check is implemented race-safely
 * (CWE-367 TOCTOU hardening): missing files are detected by catching
 * {@link java.nio.file.NoSuchFileException} from
 * {@link Files#newByteChannel(Path, java.nio.file.OpenOption...)}, never by
 * a separate {@code Files.exists(...)} pre-check, so concurrent rename or
 * unlink between two filesystem calls cannot break the NOTFND contract.
 *
 * <p><strong>Truncated record detection:</strong> If end-of-file is reached
 * mid-record, an {@link IOException} is thrown. This is stricter than COBOL,
 * which may silently process partial records, but represents a clear
 * data-integrity violation that should surface immediately. Documented as
 * DEVIATION in {@code MIGRATION_NOTES.md}.
 *
 * <p><strong>Performance:</strong>
 * {@link #findByKey(byte[], int) findByKey} is O(N/2) on average (linear scan)
 * &mdash; acceptable for CardDemo's small-to-medium datasets (the largest is
 * {@code acctdata} at ~10,000 accounts &times; 300 bytes = ~3&nbsp;MB).
 * {@link #streamSequential() streamSequential} is O(N) and lazy.
 *
 * <p><strong>Unsigned key comparison:</strong>
 * {@link #streamFromKey(byte[], int) streamFromKey} uses unsigned byte-wise
 * comparison ({@code byte & 0xFF}) to match COBOL collating sequence for ASCII
 * text keys and zoned-decimal numeric keys (digits '0'..'9' = 0x30..0x39 in
 * ASCII). For values &ge; 0x80 (e.g., binary or EBCDIC keys), unsigned
 * comparison is required for correct ordering.
 *
 * <p><strong>Source-of-truth reference programs</strong> (informing this class
 * design): {@code app/cbl/CBACT01C.cbl} (account file sequential read),
 * {@code app/cbl/CBACT02C.cbl} (card file sequential read),
 * {@code app/cbl/CBACT03C.cbl} (card cross-reference sequential read),
 * {@code app/cbl/CBCUS01C.cbl} (customer file sequential read). All four
 * follow the same VSAM KSDS sequential-read idiom:
 * {@code OPEN INPUT} + {@code READ ... AT END SET APPL-EOF} loop + {@code CLOSE}.
 */
public final class FixedWidthReader {

    /**
     * SLF4J logger for this reader. Used only on the
     * {@link #streamSequential() streamSequential} {@link Stream#onClose(Runnable)
     * onClose} hook when the underlying channel close fails &mdash; a rare,
     * non-fatal condition that should be surfaced as a WARN so operations can
     * investigate filesystem or descriptor-leak issues without aborting the
     * caller's batch.
     */
    private static final Logger LOG = LoggerFactory.getLogger(FixedWidthReader.class);

    /**
     * Path to the data file. Held as a {@link Path} (per AAP §0.6.5 mandate to
     * avoid {@link java.io.File}). Used in error messages and by
     * {@link Files#exists(Path, java.nio.file.LinkOption...) Files.exists} /
     * {@link Files#newByteChannel(Path, java.nio.file.OpenOption...)
     * Files.newByteChannel} calls.
     */
    private final Path file;

    /**
     * Fixed record length in bytes. Every record in the underlying file is
     * exactly {@code recordLength} bytes; {@link #readOneRecord(SeekableByteChannel)
     * readOneRecord} loops until {@code recordLength} bytes are accumulated or
     * EOF is observed on a record boundary. Validated &gt; 0 in the constructor.
     */
    private final int recordLength;

    /**
     * Configured {@link Charset}. Retained for symmetry with
     * {@code FixedWidthWriter} and for future EBCDIC byte-level transcoding
     * decisions (IBM-1047 default per AAP §0.6.5). The byte-level read paths
     * in this class are intentionally charset-agnostic because records are
     * parsed downstream by the domain layer's {@code record.parse(byte[])}
     * factories. Exposed via the {@link #charset() charset} accessor for
     * callers that need to forward the value to a parser or transcoder.
     */
    private final Charset charset;

    /**
     * Constructs a new fixed-width reader bound to {@code file}.
     *
     * @param file         the data file to read; must not be {@code null}.
     *                     The file need not exist at construction time;
     *                     read methods handle missing files as NOTFND parity.
     * @param recordLength the fixed record length in bytes; must be {@code > 0}.
     * @param charset      the informational charset; must not be {@code null}.
     *                     Not used by byte-level read paths; held for accessor
     *                     symmetry with {@code FixedWidthWriter}.
     * @throws NullPointerException     if {@code file} or {@code charset} is null
     * @throws IllegalArgumentException if {@code recordLength <= 0}
     */
    public FixedWidthReader(Path file, int recordLength, Charset charset) {
        this.file = Objects.requireNonNull(file, "file");
        if (recordLength <= 0) {
            throw new IllegalArgumentException(
                "recordLength must be > 0; got " + recordLength);
        }
        this.recordLength = recordLength;
        this.charset = Objects.requireNonNull(charset, "charset");
    }

    /**
     * @return the data file path supplied to the constructor (never {@code null}).
     */
    public Path file() {
        return file;
    }

    /**
     * @return the fixed record length in bytes (always {@code > 0}).
     */
    public int recordLength() {
        return recordLength;
    }

    /**
     * @return the configured charset (never {@code null}); informational only,
     *         not used by byte-level read paths.
     */
    public Charset charset() {
        return charset;
    }

    /**
     * Returns the first record whose key field at {@code keyOffset} equals
     * {@code key} byte-for-byte, or {@link Optional#empty()} if none is found.
     * Mirrors COBOL {@code READ FILE-NAME KEY IS WS-KEY} (KSDS direct random
     * read by key).
     *
     * <p>If the file does not exist, returns {@link Optional#empty()} (parity
     * with COBOL VSAM behavior where a NOTFND on an empty file is equivalent
     * to a NOTFND on a populated file with no matching key &mdash; both yield
     * FILE STATUS '23').
     *
     * <p>Implementation note: scans the file sequentially from the start,
     * comparing the key field at the given offset of each record. Returns a
     * defensive copy of the first matching record so the caller cannot mutate
     * the reader's internal {@link ByteBuffer} backing array between calls.
     *
     * @param key       the exact key bytes to match (its length defines the
     *                  key field length; must satisfy
     *                  {@code keyOffset + key.length <= recordLength})
     * @param keyOffset the byte offset of the key field within each record;
     *                  must be {@code >= 0}
     * @return the matching record buffer (length == {@code recordLength}), or
     *         {@link Optional#empty()} if not found
     * @throws IOException              on read errors other than file-not-found
     *                                  (e.g., a truncated record at end-of-file)
     * @throws NullPointerException     if {@code key} is null
     * @throws IllegalArgumentException if {@code keyOffset < 0} or
     *                                  {@code keyOffset + key.length > recordLength}
     */
    public Optional<byte[]> findByKey(byte[] key, int keyOffset) throws IOException {
        Objects.requireNonNull(key, "key");
        // Overflow-safe key bounds validation (CWE-20 + CWE-190 hardening).
        // The naive form `keyOffset + key.length > recordLength` is incorrect
        // when keyOffset is large enough that `keyOffset + key.length` overflows
        // a 32-bit signed int and wraps to a negative value, which would pass
        // the > recordLength comparison. Validate the key length against the
        // record length first (so `recordLength - key.length` is non-negative
        // and overflow-free), then compare `keyOffset` against that subtracted
        // form, which can never overflow because both operands are non-negative.
        if (keyOffset < 0) {
            throw new IllegalArgumentException(
                "keyOffset must be >= 0; got " + keyOffset);
        }
        if (key.length < 0 || key.length > recordLength) {
            throw new IllegalArgumentException(
                "key.length (" + key.length + ") exceeds recordLength ("
                    + recordLength + ")");
        }
        if (keyOffset > recordLength - key.length) {
            throw new IllegalArgumentException(
                "keyOffset (" + keyOffset + ") + key.length (" + key.length
                    + ") exceeds recordLength (" + recordLength + ")");
        }
        // Race-safe missing-file handling (CWE-367 TOCTOU hardening). Replacing
        // an exists-check + open pair with a single open + NoSuchFileException
        // catch eliminates the window in which a concurrent rename, unlink, or
        // replace could change the file between the check and the open. NOTFND
        // parity is preserved: a missing file still yields Optional.empty().
        final SeekableByteChannel ch;
        try {
            ch = Files.newByteChannel(file, StandardOpenOption.READ);
        } catch (java.nio.file.NoSuchFileException missing) {
            // Missing file is functionally equivalent to a populated file with
            // no matching key. COBOL programs check FILE STATUS = '23' (record
            // not found) or '10' (end-of-file) and treat both as "no data".
            return Optional.empty();
        }
        try (SeekableByteChannel auto = ch) {
            ByteBuffer buf = ByteBuffer.allocate(recordLength);
            while (true) {
                buf.clear();
                int total = 0;
                while (total < recordLength) {
                    int r = auto.read(buf);
                    if (r < 0) {
                        if (total == 0) {
                            // Clean EOF on a record boundary => not found.
                            return Optional.empty();
                        }
                        // EOF mid-record => the file is malformed (its length
                        // is not a whole multiple of recordLength). Surface
                        // this immediately rather than silently truncating.
                        // Per MIGRATION_NOTES.md §1.4.x this is a DEVIATION:
                        // COBOL VSAM may silently process partial records,
                        // whereas the Java translation refuses to lose data.
                        throw new IOException(
                            "Truncated record in " + file + " (read " + total
                                + " bytes; expected " + recordLength + ")");
                    }
                    total += r;
                }
                byte[] record = buf.array();
                if (keyMatches(record, keyOffset, key)) {
                    // Return a defensive copy so that subsequent reads cannot
                    // mutate the caller's view of the matched record (the
                    // backing array is reused across iterations of the loop
                    // via buf.clear() above, but we are returning early here).
                    // The copy also decouples the caller from any future
                    // optimization that reuses ByteBuffer instances.
                    byte[] copy = new byte[recordLength];
                    System.arraycopy(record, 0, copy, 0, recordLength);
                    return Optional.of(copy);
                }
            }
        }
    }

    /**
     * Returns a {@link Stream} of all records in the file, in physical order.
     * Mirrors COBOL {@code OPEN INPUT} + {@code PERFORM UNTIL APPL-EOF / READ
     * FILE-NAME / AT END SET APPL-EOF} + {@code CLOSE}.
     *
     * <p>The stream is lazy: records are read one at a time as the stream is
     * consumed. The caller MUST close the stream (use try-with-resources) to
     * release the underlying channel:
     *
     * <pre>{@code
     * try (Stream<byte[]> s = reader.streamSequential()) {
     *     s.forEach(record -> { ... });
     * }
     * }</pre>
     *
     * <p>If the file does not exist, returns {@link Stream#empty()} (parity
     * with COBOL where {@code READ} on a missing/empty dataset immediately
     * yields end-of-file).
     *
     * <p>Parallelism is intentionally NOT enabled: per AAP §0.6.6 "Virtual
     * threads are NOT a license to reorder records, change sort orders, or
     * break sequencing. Any reordering changes observable output and is
     * FORBIDDEN." This reader is the foundational sequential scan; reordering
     * (if ever safe at the use-case level) must be applied by the caller after
     * collecting records.
     *
     * @return a lazy stream of record buffers; each buffer has length
     *         {@code recordLength}
     * @throws IOException on file open errors other than file-not-found
     */
    public Stream<byte[]> streamSequential() throws IOException {
        // Race-safe missing-file handling (CWE-367 TOCTOU hardening). Open the
        // channel directly and translate NoSuchFileException into an empty
        // stream; this eliminates the exists-check + open window in which a
        // concurrent rename, unlink, or replace could change the file between
        // the check and the open call.
        //
        // The channel is captured in a final local because the onClose
        // Runnable below must be able to release it after the caller exhausts
        // or explicitly closes the returned Stream. Ownership of this channel
        // transfers to the returned Stream's onClose lifecycle hook.
        final SeekableByteChannel ch;
        try {
            ch = Files.newByteChannel(file, StandardOpenOption.READ);
        } catch (java.nio.file.NoSuchFileException missing) {
            // NOTFND parity: a missing file is functionally equivalent to a
            // populated but empty file. Both yield zero records.
            return Stream.empty();
        }
        Iterator<byte[]> it = new Iterator<byte[]>() {
            // Holds a one-record look-ahead computed by hasNext(); consumed by
            // next(). null when no look-ahead is buffered.
            private byte[] next = null;
            // True once a clean EOF has been observed on a record boundary;
            // prevents subsequent hasNext() calls from attempting another read
            // on a closed/exhausted stream.
            private boolean done = false;

            @Override
            public boolean hasNext() {
                if (done) {
                    return false;
                }
                if (next != null) {
                    return true;
                }
                try {
                    next = readOneRecord(ch);
                } catch (IOException e) {
                    // Iterator.hasNext() does not declare checked exceptions;
                    // wrap in UncheckedIOException so callers downstream of
                    // Stream operations can still observe and handle the
                    // underlying I/O failure.
                    throw new UncheckedIOException(
                        "Error reading " + file, e);
                }
                if (next == null) {
                    done = true;
                    return false;
                }
                return true;
            }

            @Override
            public byte[] next() {
                if (!hasNext()) {
                    // Required by the Iterator contract for next() called when
                    // hasNext() returns false.
                    throw new NoSuchElementException();
                }
                byte[] out = next;
                next = null;
                return out;
            }
        };
        // ORDERED | NONNULL is the correct characteristic set:
        //   ORDERED — preserves COBOL physical record sequence (mandatory).
        //   NONNULL — readOneRecord() never returns null elements in the
        //             stream; null signals EOF and ends iteration.
        // Parallelism is forbidden (parallel=false) per AAP §0.6.6.
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(
                    it, Spliterator.ORDERED | Spliterator.NONNULL),
                false)
            .onClose(() -> {
                // Release the channel when the caller closes the Stream
                // (typically via try-with-resources). Failure is logged as
                // WARN rather than thrown because Stream.close() callbacks
                // that throw obscure the caller's primary exception path.
                try {
                    ch.close();
                } catch (IOException e) {
                    LOG.warn("Error closing channel for {}: {}", file, e.toString());
                }
            });
    }

    /**
     * Returns a {@link Stream} of all records whose key field at
     * {@code keyOffset} is greater than or equal to {@code startKey}, in
     * physical order. Mirrors COBOL
     * {@code STARTBR FILE-NAME RIDFLD(WS-KEY) GTEQ} followed by a
     * {@code READNEXT} loop.
     *
     * <p>Because the underlying file is sorted by key (VSAM KSDS guarantee),
     * this method yields all records from the first GTEQ position onward.
     * Implemented as a filtered pass over {@link #streamSequential()
     * streamSequential}: the filter discards records strictly less than
     * {@code startKey} and keeps everything else. For a strictly-sorted file
     * this is equivalent to a true GTEQ browse; for an unsorted file the
     * filter still returns the correct subset, albeit without any ordering
     * guarantee beyond the file's physical order.
     *
     * <p>The caller MUST close the stream (use try-with-resources) to release
     * the underlying channel:
     *
     * <pre>{@code
     * try (Stream<byte[]> s = reader.streamFromKey(startKey, 0)) {
     *     s.forEach(record -> { ... });
     * }
     * }</pre>
     *
     * <p>Comparison is unsigned byte-wise (see {@link #compareKey}) to match
     * COBOL collating sequence for ASCII, zoned-decimal, and binary keys.
     *
     * @param startKey  the inclusive lower bound for the key field; must not
     *                  be {@code null} and must satisfy
     *                  {@code keyOffset + startKey.length <= recordLength}
     * @param keyOffset the byte offset of the key field within each record;
     *                  must be {@code >= 0}
     * @return a lazy stream of records with key &ge; {@code startKey}
     * @throws IOException              on file open errors other than
     *                                  file-not-found
     * @throws NullPointerException     if {@code startKey} is null
     * @throws IllegalArgumentException if {@code keyOffset < 0} or
     *                                  {@code keyOffset + startKey.length > recordLength}
     */
    public Stream<byte[]> streamFromKey(byte[] startKey, int keyOffset) throws IOException {
        Objects.requireNonNull(startKey, "startKey");
        // Overflow-safe key bounds validation (CWE-20 + CWE-190 hardening),
        // matching the form used by findByKey above. Both validations follow
        // the same pattern: check `key.length` against `recordLength` first
        // so subtraction is non-negative, then compare `keyOffset` against
        // `recordLength - key.length`, which can never overflow.
        if (keyOffset < 0) {
            throw new IllegalArgumentException(
                "keyOffset must be >= 0; got " + keyOffset);
        }
        if (startKey.length < 0 || startKey.length > recordLength) {
            throw new IllegalArgumentException(
                "startKey.length (" + startKey.length
                    + ") exceeds recordLength (" + recordLength + ")");
        }
        if (keyOffset > recordLength - startKey.length) {
            throw new IllegalArgumentException(
                "keyOffset (" + keyOffset + ") + startKey.length ("
                    + startKey.length + ") exceeds recordLength ("
                    + recordLength + ")");
        }
        // Defensive copy of startKey so a subsequent mutation by the caller
        // cannot change the filter predicate after the stream has been
        // returned (records are evaluated lazily, so the predicate must
        // observe a stable bound).
        final byte[] startKeyCopy = new byte[startKey.length];
        System.arraycopy(startKey, 0, startKeyCopy, 0, startKey.length);
        return streamSequential()
            .filter(buf -> compareKey(buf, keyOffset, startKeyCopy) >= 0);
    }

    /**
     * Reads exactly one fixed-width record from {@code ch} into a freshly
     * allocated {@code byte[recordLength]}.
     *
     * <p>Returns {@code null} on a clean end-of-file observed exactly on a
     * record boundary (i.e., zero bytes were available to start the record).
     * Throws {@link IOException} if EOF is observed mid-record &mdash; that
     * indicates a malformed file whose length is not a whole multiple of
     * {@code recordLength}, which must surface as an explicit error rather
     * than a silently truncated read.
     *
     * <p>The inner loop accumulates bytes until the buffer is full, because
     * {@link SeekableByteChannel#read(ByteBuffer) SeekableByteChannel.read}
     * may legitimately return fewer bytes than requested (it returns the
     * number actually transferred), even for local files. Looping is required
     * for correctness.
     *
     * @param ch the open channel to read from
     * @return the {@code recordLength}-byte record, or {@code null} on clean EOF
     * @throws IOException on channel read errors or on truncated records
     */
    private byte[] readOneRecord(SeekableByteChannel ch) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(recordLength);
        int total = 0;
        while (total < recordLength) {
            int r = ch.read(buf);
            if (r < 0) {
                if (total == 0) {
                    return null; // clean EOF on record boundary
                }
                throw new IOException(
                    "Truncated record in " + file + " (read " + total
                        + " bytes; expected " + recordLength + ")");
            }
            total += r;
        }
        return buf.array();
    }

    /**
     * Returns {@code true} iff
     * {@code buffer[offset..offset + expected.length)} equals {@code expected}
     * byte-for-byte. Charset-agnostic byte comparison; no transcoding occurs.
     *
     * @param buffer   the record buffer
     * @param offset   the byte offset within {@code buffer} where the key
     *                 field begins
     * @param expected the expected key bytes
     * @return {@code true} on exact byte-for-byte match
     */
    private static boolean keyMatches(byte[] buffer, int offset, byte[] expected) {
        // Length guard prevents AIOOBE if a malformed buffer were passed
        // (impossible in current call sites because the buffer always equals
        // recordLength, but documenting the precondition is cheap and safe).
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

    /**
     * Unsigned byte-wise comparison of
     * {@code buffer[offset..offset + key.length)} versus {@code key}.
     * Returns a negative integer, zero, or a positive integer if the buffer
     * slice is less than, equal to, or greater than {@code key} respectively,
     * a la {@link Comparable#compareTo(Object) Comparable.compareTo}.
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
