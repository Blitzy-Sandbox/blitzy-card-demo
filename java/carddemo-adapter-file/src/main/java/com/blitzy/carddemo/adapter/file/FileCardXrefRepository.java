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

// Internal imports — strictly limited to depends_on_files per AAP §0.5.1 and
// the file's binding internal_imports schema. The COBOL traceability
// annotation, the port interface this adapter implements, and the 50-byte
// fixed-width domain record are all sourced from carddemo-domain.
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.record.CardXrefRecord;

// SLF4J facade (slf4j-api 2.0.16 per parent POM dependencyManagement) for
// DEBUG-level audit lines from save() and delete(). The concrete logging
// backend (logback-classic 1.5.19) is supplied at runtime by the composition
// root in carddemo-app, keeping this adapter free of binding to a specific
// backend per AAP §0.6.12. All card-number values are masked via maskPan(...)
// before logging per AAP §0.7.2 (PCI compliance: no full PAN in logs).
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// java.io exception types. IOException is the checked exception thrown by the
// FixedWidthReader / FixedWidthWriter at the underlying file-channel
// boundary; it is caught in every port method and re-thrown as
// UncheckedIOException with a contextual message (including the masked PAN
// where a card number is in scope) so the public port interface signature
// remains checked-exception-free. NO java.io.File, FileInputStream,
// FileOutputStream, FileReader, FileWriter, or RandomAccessFile imports —
// those are explicitly FORBIDDEN per AAP §0.6.5 (file I/O exactness:
// java.nio.file only).
import java.io.IOException;
import java.io.UncheckedIOException;

// java.nio.ByteBuffer used by detectFileRecordLength() to read the head of the
// data file at construction time and probe for an ASCII line separator at one
// of the candidate record-length offsets (36 vs 50). Selecting the buffer-
// based read here keeps the helper free of allocations on the hot path while
// still using the AAP §0.6.5-mandated java.nio.* API surface.
import java.nio.ByteBuffer;

// java.nio.channels.SeekableByteChannel is the type returned by
// Files.newByteChannel(...) in detectFileRecordLength(). Held only for the
// probe operation and immediately released via try-with-resources; the
// reader/writer continue to manage their own per-operation channels.
import java.nio.channels.SeekableByteChannel;

// Charset is injected via the constructor and held as a final field. It is
// used to convert the 16-char cardNumber argument and the zero-padded
// 11-digit accountId AIX scan key to bytes for byte-for-byte key comparison
// against the underlying record buffer. The charset is also forwarded to the
// FixedWidthReader and FixedWidthWriter constructors for symmetry. Default
// (per AAP §0.6.5) is IBM-1047 (EBCDIC) for production parity; per-file
// overrides via application.properties keys like
// {@code carddemo.file.cardxref.charset}.
import java.nio.charset.Charset;

// NIO.2 Path / Files / StandardOpenOption: Path is the constructor argument
// identifying the CARDXREF data file; Files is used by detectFileRecordLength
// to size and read the head of the data file at construction time;
// StandardOpenOption.READ is the open mode for the format-probe channel.
// Mandated by AAP §0.6.5 ("All file I/O uses java.nio.file ... java.io.File
// is forbidden in new code").
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

// java.util.Arrays used by parsePadded() and toFileFormat() to copy and
// space-pad the 36-byte ASCII fixture buffer up to the canonical 50-byte
// CardXrefRecord layout before delegating to CardXrefRecord.parse(byte[]),
// and to truncate a 50-byte encoded record buffer down to the 36-byte
// file-format width when the underlying file uses the fixture layout. The
// fixed pattern is the AAP §0.6.5-mandated byte-for-byte round-trip approach
// recorded in MIGRATION_NOTES.md §1.4.9 (dual-format CARDXREF handling).
import java.util.Arrays;

// java.util utilities:
//   NoSuchElementException — thrown by delete(String) when
//       FixedWidthWriter.deleteByKey returns false (no matching
//       XREF-CARD-NUM record), translating the COBOL DELETE INVALID KEY
//       (FILE STATUS '23'/'24') semantics into a typed runtime exception
//       with a masked-PAN message per AAP §0.7.2.
//   Objects                — Objects.requireNonNull for eager non-null
//       validation in the constructor and every public method, per AAP
//       §0.7.2 (preserve current behavior with minimal risk).
//   Optional               — return type of findByCardNumber and
//       findByAccountId, expressing the NOTFND-equivalent absence:
//       Optional.empty() ↔ COBOL DFHRESP(NOTFND) on the EXEC CICS READ
//       paths in CBTRN02C 1500-A-LOOKUP-XREF, COBIL00C, COTRN02C, and
//       COACTVWC.
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

// Lazy AutoCloseable stream type used as the return type of streamByAccountId
// (AIX iteration order: all CARDXREF records whose XREF-ACCT-ID at offset 25
// matches the requested accountId) and streamSequential (ascending
// XREF-CARD-NUM order, mirrors CBACT03C's READ ... AT END loop). The streams
// pipe FixedWidthReader.streamSequential()'s raw byte[] buffers through
// .filter(keyMatches at offset 25) and/or .map(CardXrefRecord::parse).
// Callers MUST close the returned streams via try-with-resources to release
// the underlying SeekableByteChannel registered with onClose by the reader.
import java.util.stream.Stream;

/**
 * File-backed adapter for the {@code CARDXREF} VSAM KSDS dataset — the
 * {@link java.nio.file}-backed implementation of the
 * {@link CardXrefRepository} port. Persists 50-byte fixed-width
 * {@link CardXrefRecord} instances (copybook
 * {@code app/cpy/CVACT03Y.cpy}) keyed by the 16-byte {@code XREF-CARD-NUM}
 * primary key to the CARDXREF data file, and provides an Alternate
 * Index (AIX) on {@code XREF-ACCT-ID} for account-id lookups via
 * sequential scan + filter.
 *
 * <h2>Source lineage (per AAP &sect;0.4.1)</h2>
 * <ul>
 *   <li>{@code app/cpy/CVACT03Y.cpy} &mdash; canonical 50-byte
 *       {@code CARD-XREF-RECORD} layout:
 *       <pre>{@code
 *       01  CARD-XREF-RECORD.
 *           05  XREF-CARD-NUM       PIC X(16).
 *           05  XREF-CUST-ID        PIC 9(09).
 *           05  XREF-ACCT-ID        PIC 9(11).
 *           05  FILLER              PIC X(14).
 *       }</pre>
 *       Total: 50 bytes. Primary key {@code XREF-CARD-NUM} at offset 0
 *       length 16; AIX key {@code XREF-ACCT-ID} at offset 25 length 11
 *       (offsets: 16 byte PAN + 9 byte CUST-ID = 25).</li>
 *   <li>{@code app/cbl/CBACT03C.cbl} &mdash; sequential reader; drives
 *       {@link #streamSequential()}.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} paragraph
 *       {@code 1500-A-LOOKUP-XREF} &mdash; random read by PAN; drives
 *       {@link #findByCardNumber(String)}.</li>
 *   <li>{@code app/cbl/COBIL00C.cbl} {@code READ-CXACAIX-FILE} &mdash;
 *       random read by account id via AIX; drives
 *       {@link #findByAccountId(long)}.</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} {@code READ-CXACAIX-FILE} and
 *       {@code READ-CCXREF-FILE} &mdash; both AIX and PAN access
 *       paths; drives both
 *       {@link #findByAccountId(long)} and
 *       {@link #findByCardNumber(String)}.</li>
 *   <li>{@code app/cbl/COACTVWC.cbl} paragraph
 *       {@code 9200-GETCARDXREF-BYACCT} &mdash; AIX read by account
 *       id; drives {@link #findByAccountId(long)}.</li>
 *   <li>{@code app/jcl/XREFFILE.jcl} &mdash; IDCAMS
 *       {@code DELETE / DEFINE / REPRO} bulk load; drives
 *       {@link #save(CardXrefRecord)} and {@link #delete(String)}.</li>
 * </ul>
 *
 * <h2>AIX implementation strategy</h2>
 * The file-backed adapter has no native AIX support; the AIX path
 * {@link #findByAccountId(long)} and {@link #streamByAccountId(long)} are
 * implemented as a sequential scan filtered on the {@code XREF-ACCT-ID}
 * field at offset {@value #AIX_ACCTID_OFFSET}. This is O(N) per lookup,
 * which is acceptable for CardDemo's CARDXREF size (one row per card).
 * A future {@code carddemo-adapter-db} implementation may use a real
 * index. Records are returned in file-scan order = ascending
 * {@code XREF-CARD-NUM} (KSDS primary-key order), which matches the
 * COBOL AIX browse behavior where multiple cards on a single account
 * appear in primary-key order.
 *
 * <h2>AIX consistency on writes</h2>
 * Because the AIX field ({@code XREF-ACCT-ID}) is co-located within the
 * same 50-byte record buffer as the primary key, the AIX is implicitly
 * maintained whenever {@link #save(CardXrefRecord)} rewrites a record;
 * no separate AIX index file is maintained by this adapter. Per AAP
 * &sect;0.6.5 the entire 50-byte buffer (including the trailing 14-byte
 * FILLER) is persisted verbatim.
 *
 * <h2>PAN masking (AAP &sect;0.7.2)</h2>
 * The primary key handled by this adapter is the Primary Account Number
 * (PAN). Per AAP &sect;0.7.2 the cleartext PAN MUST NOT appear in logs
 * or error messages: only the last 4 digits may be visible. This
 * adapter routes every card-number reference through the private
 * {@link #maskPan(String)} helper before logging or constructing
 * exception messages. The {@link CardXrefRecord#toString()} method is
 * separately overridden by the domain layer to render the PAN-equivalent
 * field masked, so any accidental {@code log.info("{}", record)} also
 * remains compliant.
 *
 * <h2>Concurrency</h2>
 * Reads are thread-safe: each public read method opens a fresh
 * {@link java.nio.channels.SeekableByteChannel} via the underlying
 * {@link FixedWidthReader}. Writes are serialised through the private
 * {@code writeLock} monitor so that concurrent
 * {@link #save(CardXrefRecord)} and {@link #delete(String)} invocations
 * cannot interleave with each other; this preserves the
 * read-modify-write atomicity of the underlying
 * {@link FixedWidthWriter#upsert} and
 * {@link FixedWidthWriter#deleteByKey} operations.
 *
 * <h2>Byte fidelity (AAP &sect;0.6.5)</h2>
 * The save path persists the entire 50-byte fixed-width record image
 * including the trailing 14-byte FILLER. The
 * {@code parse(b).encode() == b} round-trip invariant is preserved
 * end-to-end so external file consumers (the COBOL baseline output, the
 * golden-record harness) observe identical bytes.
 *
 * @see CardXrefRepository
 * @see CardXrefRecord
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @since 1.0.0
 */
@CobolProgram(
        value = "CARDXREF",
        sourcePath = "app/cpy/CVACT03Y.cpy",
        notes = "VSAM KSDS + AIX, 50-byte records. Primary key XREF-CARD-NUM "
                + "(X(16) at offset 0); AIX on XREF-ACCT-ID (9(11) at offset 25). "
                + "PAN MASKED in logs per AAP §0.7.2. AIX consistency maintained on "
                + "writes (same buffer holds both keys). See CBACT03C, "
                + "CBTRN02C 1500-A-LOOKUP-XREF, COBIL00C, COTRN02C, COACTVWC."
)
public final class FileCardXrefRepository implements CardXrefRepository {

    /**
     * SLF4J logger for DEBUG-level audit lines. Acquired via
     * {@link LoggerFactory#getLogger(Class)} so that the underlying
     * runtime backend (logback-classic 1.5.19 per AAP &sect;0.5.1) is
     * resolved at composition-root startup, not at adapter
     * construction. All card-number values logged through this logger
     * MUST be passed through {@link #maskPan(String)} first per AAP
     * &sect;0.7.2.
     */
    private static final Logger LOG = LoggerFactory.getLogger(FileCardXrefRepository.class);

    /**
     * Canonical {@code CARD-XREF-RECORD} length in bytes per copybook
     * {@code app/cpy/CVACT03Y.cpy}: 16 (PAN) + 9 (CUST-ID) + 11
     * (ACCT-ID) + 14 (FILLER) = 50. Mirrors
     * {@link CardXrefRecord#RECORD_LENGTH} but is restated here as a
     * local constant because the adapter holds the value at three
     * independent sites (constructor, save-length check, key offset
     * arithmetic) and the duplication is cheaper than a static
     * cross-module reference for a single integer literal.
     *
     * <p>This is the SCHEMA-MANDATED canonical width and is always
     * what {@link CardXrefRecord#encode()} emits and what
     * {@link CardXrefRecord#parse(byte[])} consumes. The actual file
     * width on disk may be {@link #FIXTURE_RECORD_LENGTH} when the
     * adapter is pointed at the ASCII fixture format produced by
     * loading {@code app/data/ASCII/cardxref.txt} via REPRO; see
     * {@link #fileRecordLength} and the discussion in
     * {@code java/MIGRATION_NOTES.md §1.4.9}.
     */
    private static final int RECORD_LENGTH = 50;

    /**
     * Alternate {@code CARD-XREF-RECORD} length in bytes used by the
     * ASCII fixture format produced from
     * {@code app/data/ASCII/cardxref.txt}: 16 (PAN) + 9 (CUST-ID) + 11
     * (ACCT-ID) = 36, omitting the trailing 14-byte FILLER region.
     * The fixture omits FILLER because the {@code cardxref.txt} file
     * was originally generated as a flat-text REPRO source without the
     * VSAM RECORD_LENGTH(50) padding. See
     * {@code java/MIGRATION_NOTES.md §1.4.9} for the dual-format
     * rationale and AAP &sect;0.6.5 for the byte-for-byte round-trip
     * mandate that requires both layouts be supported.
     *
     * <p>When the file is in fixture format the adapter still exposes
     * canonical 50-byte {@link CardXrefRecord} instances; the
     * {@link #parsePadded(byte[])} helper space-pads the 36-byte
     * buffer up to {@link #RECORD_LENGTH} before delegating to
     * {@link CardXrefRecord#parse(byte[])}, and {@link #save} truncates
     * the encoded 50-byte buffer down to {@code FIXTURE_RECORD_LENGTH}
     * via {@link #toFileFormat(byte[])} to preserve the on-disk format
     * round-trip-identical to the source.
     */
    private static final int FIXTURE_RECORD_LENGTH = 36;

    /**
     * Byte offset of the {@code XREF-CARD-NUM} primary key within the
     * 50-byte {@code CARD-XREF-RECORD}. The PAN is the first field
     * (offset 0) per {@code app/cpy/CVACT03Y.cpy:L5}.
     */
    private static final int PK_OFFSET = 0;

    /**
     * Byte length of the {@code XREF-CARD-NUM} primary key
     * ({@code PIC X(16)} per {@code app/cpy/CVACT03Y.cpy:L5}). Used to
     * validate the {@code cardNumber} argument in
     * {@link #findByCardNumber(String)} and {@link #delete(String)}
     * before encoding the key bytes.
     */
    private static final int PK_LENGTH = 16;

    /**
     * Byte offset of the {@code XREF-ACCT-ID} alternate-index key
     * within the 50-byte {@code CARD-XREF-RECORD}: 16 (PAN) + 9
     * (CUST-ID) = 25 per {@code app/cpy/CVACT03Y.cpy:L7}. Used by
     * {@link #findByAccountId(long)} and
     * {@link #streamByAccountId(long)} to filter the sequential
     * scan stream on the AIX field.
     */
    private static final int AIX_ACCTID_OFFSET = 25;

    /**
     * Byte length of the {@code XREF-ACCT-ID} alternate-index key
     * ({@code PIC 9(11)} per {@code app/cpy/CVACT03Y.cpy:L7}). Used
     * to size the AIX scan-key byte array produced by
     * {@link #formatAcctIdKey(long)}.
     */
    private static final int AIX_ACCTID_LENGTH = 11;

    /**
     * Inclusive upper bound for an {@code XREF-ACCT-ID} value so that
     * it fits in the {@value #AIX_ACCTID_LENGTH}-digit field
     * ({@code PIC 9(11)}): {@code 10^11 - 1 = 99_999_999_999}.
     * Enforced by {@link #validateAccountId(long)}.
     */
    private static final long MAX_ACCT_ID = 99_999_999_999L;

    /**
     * The CARDXREF data file path injected via the constructor. Held
     * for reference in error messages (e.g., the sequential-read open
     * failure path) and never mutated after construction.
     */
    private final Path dataFile;

    /**
     * The on-disk fixed-width record length resolved at construction
     * time by {@link #detectFileRecordLength(Path, int)}. Equals
     * either {@link #RECORD_LENGTH} (50 bytes &mdash; canonical KSDS
     * format) or {@link #FIXTURE_RECORD_LENGTH} (36 bytes &mdash;
     * ASCII fixture format produced from
     * {@code app/data/ASCII/cardxref.txt}). All public read methods
     * decode the on-disk buffer via {@link #parsePadded(byte[])} so
     * callers continue to receive canonical 50-byte
     * {@link CardXrefRecord} instances regardless of the file's
     * native width.
     *
     * <p>When the file does not yet exist at construction time this
     * field defaults to {@link #RECORD_LENGTH}, matching the
     * AAP-mandated canonical layout that {@link #save(CardXrefRecord)}
     * will write. The detection is deliberately one-shot at
     * construction (rather than per-operation) because the file
     * format is sticky once written: a {@code REPRO}-loaded fixture
     * file stays in 36-byte format and a {@code save}-created file
     * stays in 50-byte format for the lifetime of the adapter
     * instance.
     */
    private final int fileRecordLength;

    /**
     * The charset used to convert {@link String} card-number keys and
     * the zero-padded account-id AIX scan key to bytes for byte-for-byte
     * comparison against the underlying record buffer. Held final;
     * never reassigned. Default {@code IBM-1047} (EBCDIC) per
     * AAP &sect;0.6.5; per-file overrides supplied by the composition
     * root via {@code application.properties}.
     */
    private final Charset charset;

    /**
     * The foundational fixed-width record reader. Constructed once in
     * the constructor; reused across {@link #findByCardNumber(String)},
     * {@link #findByAccountId(long)}, {@link #streamByAccountId(long)},
     * and {@link #streamSequential()}. The reader is thread-safe for
     * reads (each call opens a fresh channel).
     */
    private final FixedWidthReader reader;

    /**
     * The foundational fixed-width record writer. Constructed once in
     * the constructor; reused across {@link #save(CardXrefRecord)} and
     * {@link #delete(String)}. The underlying
     * {@link FixedWidthWriter#upsert(byte[], int, byte[])} and
     * {@link FixedWidthWriter#deleteByKey(byte[], int)} operations are
     * crash-safe (temp-file + atomic move) but NOT atomic across
     * concurrent invocations; the {@link #writeLock} monitor
     * serialises them.
     */
    private final FixedWidthWriter writer;

    /**
     * Monitor object serialising all write operations
     * ({@link #save(CardXrefRecord)}, {@link #delete(String)}). Held
     * final and never exposed via an accessor (which would tempt a
     * caller to take the lock externally and deadlock). Concurrent
     * read operations do not synchronize on this monitor.
     */
    private final Object writeLock = new Object();

    /**
     * AAP-mandated default codepage name (AAP &sect;0.6.5: <em>"The
     * default codepage for EBCDIC-to-ASCII transcoding is
     * Charset.forName(\"IBM-1047\")"</em>). Held as a {@code String}
     * literal rather than a {@code Charset} so the {@link Charset}
     * lookup occurs only on the convenience constructor path and a
     * misspelling would be caught by the constructor's invocation,
     * not at class-load time.
     */
    private static final String DEFAULT_CHARSET_NAME = "IBM-1047";

    /**
     * Convenience constructor that defaults the charset to the
     * AAP-mandated {@code IBM-1047} (EBCDIC) production codepage
     * defined in AAP &sect;0.6.5. Equivalent to:
     * <pre>{@code
     * new FileCardXrefRepository(dataFile, Charset.forName("IBM-1047"));
     * }</pre>
     *
     * <p>Matches the dual-constructor convention used by every sister
     * adapter in this package ({@code FileAccountRepository},
     * {@code FileCardRepository}, {@code FileCustomerRepository},
     * {@code FileTransactionRepository}, etc.) so that the composition
     * root in {@code carddemo-app} can wire adapters uniformly without
     * threading a {@link Charset} through every call site. Per-file
     * codepage overrides are supplied by the caller via the canonical
     * {@link #FileCardXrefRepository(Path, Charset)} constructor
     * (typically resolved from
     * {@code application.properties} key
     * {@code carddemo.file.cardxref.charset} by the composition root).
     *
     * @param dataFile the CARDXREF data file path; never
     *                 {@code null}. The file need not exist at
     *                 construction time; read methods handle missing
     *                 files as NOTFND parity (empty stream / empty
     *                 Optional).
     * @throws NullPointerException     if {@code dataFile} is
     *                                  {@code null}
     * @throws java.nio.charset.UnsupportedCharsetException
     *         if the JVM is missing the {@code IBM-1047} codepage
     *         (always present in standard JDK distributions; this
     *         clause documents the contract rather than warning
     *         about a realistic failure mode)
     */
    public FileCardXrefRepository(Path dataFile) {
        this(dataFile, Charset.forName(DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs a new file-backed CARDXREF adapter — the canonical
     * constructor exposed per the file schema's
     * {@code members_exposed} contract.
     *
     * @param dataFile the CARDXREF data file path (typically the JCL
     *                 DD ddname equivalent — a filesystem path
     *                 resolved via {@code application.properties} key
     *                 {@code carddemo.file.cardxref.path}); never
     *                 {@code null}. The file need not exist at
     *                 construction time; read methods handle missing
     *                 files as NOTFND parity (empty stream / empty
     *                 Optional).
     * @param charset  the charset to use for key encoding and to
     *                 forward to the underlying reader/writer; never
     *                 {@code null}. The codepage default per AAP
     *                 &sect;0.6.5 is {@code IBM-1047} (EBCDIC); the
     *                 composition root is responsible for resolving
     *                 the per-file charset from
     *                 {@code application.properties} (key
     *                 {@code carddemo.file.cardxref.charset}) before
     *                 calling this constructor.
     * @throws NullPointerException if {@code dataFile} or
     *                              {@code charset} is {@code null}
     */
    public FileCardXrefRepository(Path dataFile, Charset charset) {
        this.dataFile = Objects.requireNonNull(dataFile, "dataFile");
        this.charset = Objects.requireNonNull(charset, "charset");
        // Detect the on-disk record length once at construction time.
        // Returns RECORD_LENGTH (50) for a canonical KSDS format file,
        // FIXTURE_RECORD_LENGTH (36) for an ASCII fixture file loaded
        // from app/data/ASCII/cardxref.txt, and defaults to
        // RECORD_LENGTH when the file does not yet exist or is empty
        // (the future write path will produce a canonical 50-byte
        // format file). See MIGRATION_NOTES.md §1.4.9.
        this.fileRecordLength = detectFileRecordLength(dataFile, RECORD_LENGTH);
        this.reader = new FixedWidthReader(dataFile, fileRecordLength, charset);
        this.writer = new FixedWidthWriter(dataFile, fileRecordLength, charset);
    }

    // ------------------------------------------------------------------
    // File-format detection (canonical 50-byte KSDS vs 36-byte fixture)
    // ------------------------------------------------------------------

    /**
     * Probes the first ~64 bytes of the data file to determine the
     * on-disk record length. Returns {@link #FIXTURE_RECORD_LENGTH}
     * (36 bytes) when an ASCII line separator (LF or CRLF) is present
     * at byte offset 36 (the fixture format produced from
     * {@code app/data/ASCII/cardxref.txt}), otherwise returns
     * {@code canonicalLength} (50 bytes &mdash; the canonical KSDS
     * format mandated by {@code app/cpy/CVACT03Y.cpy} and the schema
     * sidecar).
     *
     * <p>Detection rules in priority order:
     * <ol>
     *   <li>File does not exist or has zero size &rarr;
     *       {@code canonicalLength} (the next write will use the
     *       canonical layout).</li>
     *   <li>LF (0x0A) byte at offset {@link #FIXTURE_RECORD_LENGTH} of
     *       the file head &rarr; 36-byte fixture format.</li>
     *   <li>CRLF (0x0D 0x0A) at offsets 36 and 37 of the file head
     *       &rarr; 36-byte fixture format.</li>
     *   <li>File size is exactly divisible by 37 (fixture format with
     *       LF separators: 36 data + 1 LF) &rarr; 36-byte fixture
     *       format.</li>
     *   <li>File size is exactly divisible by 36 (fixture format
     *       without separators) and NOT divisible by
     *       {@code canonicalLength} &rarr; 36-byte fixture format.</li>
     *   <li>Otherwise &rarr; {@code canonicalLength}.</li>
     * </ol>
     *
     * <p>The detection is deliberately conservative: when in doubt
     * the canonical 50-byte format is selected, mirroring the
     * {@code app/cpy/CVACT03Y.cpy} schema and matching what
     * {@link #save(CardXrefRecord)} produces from a freshly-encoded
     * 50-byte buffer. The fixture format is selected only when the
     * file head contains an unambiguous LF/CRLF marker at the
     * expected offset, or the file size is a clean multiple of the
     * fixture-with-LF stride and NOT a multiple of the canonical
     * width.
     *
     * <p>Any I/O exception during detection is swallowed and
     * {@code canonicalLength} is returned; read methods will surface
     * the underlying error on their next open.
     *
     * @param dataFile        the data file path to probe
     * @param canonicalLength the canonical KSDS record length to
     *                        return when the file is missing, empty,
     *                        or does not match the fixture pattern
     * @return either {@code canonicalLength} or
     *         {@link #FIXTURE_RECORD_LENGTH}
     */
    private static int detectFileRecordLength(Path dataFile, int canonicalLength) {
        if (!Files.exists(dataFile)) {
            return canonicalLength;
        }
        try {
            long size = Files.size(dataFile);
            if (size == 0L) {
                return canonicalLength;
            }
            // Probe the file head for an LF or CRLF separator at the
            // candidate fixture-format offset. Read up to 64 bytes
            // (enough to see byte 36 + a potential CRLF tail at 37) or
            // the file's actual size if smaller.
            int probeLen = (int) Math.min(64L, size);
            byte[] head = new byte[probeLen];
            try (SeekableByteChannel ch =
                         Files.newByteChannel(dataFile, StandardOpenOption.READ)) {
                ByteBuffer buf = ByteBuffer.wrap(head);
                while (buf.hasRemaining()) {
                    int n = ch.read(buf);
                    if (n < 0) {
                        break;
                    }
                }
            }
            // Rule 2: LF at offset 36 (fixture with LF separators).
            if (probeLen > FIXTURE_RECORD_LENGTH
                    && head[FIXTURE_RECORD_LENGTH] == (byte) 0x0A) {
                return FIXTURE_RECORD_LENGTH;
            }
            // Rule 3: CRLF at offset 36 (fixture with CRLF separators
            // on Windows-edited files).
            if (probeLen > FIXTURE_RECORD_LENGTH + 1
                    && head[FIXTURE_RECORD_LENGTH] == (byte) 0x0D
                    && head[FIXTURE_RECORD_LENGTH + 1] == (byte) 0x0A) {
                return FIXTURE_RECORD_LENGTH;
            }
            // Rule 4: pure-stride match for fixture format with LF.
            if (size % (long) (FIXTURE_RECORD_LENGTH + 1) == 0L
                    && size % (long) canonicalLength != 0L) {
                return FIXTURE_RECORD_LENGTH;
            }
            // Rule 5: pure-stride match for fixture format without
            // separators.
            if (size % (long) FIXTURE_RECORD_LENGTH == 0L
                    && size % (long) canonicalLength != 0L) {
                return FIXTURE_RECORD_LENGTH;
            }
            // Rule 6: default to canonical.
            return canonicalLength;
        } catch (IOException e) {
            // Detection failure falls through to canonical. The next
            // actual read will surface the underlying error to the
            // caller via UncheckedIOException.
            return canonicalLength;
        }
    }

    // ------------------------------------------------------------------
    // Dual-format record buffer adaptation
    // ------------------------------------------------------------------

    /**
     * Pads a raw on-disk record buffer up to the canonical
     * {@link #RECORD_LENGTH} (50 bytes) with ASCII spaces (0x20) so
     * it can be passed to {@link CardXrefRecord#parse(byte[])}, which
     * requires an exact 50-byte buffer per the
     * {@code app/cpy/CVACT03Y.cpy} schema.
     *
     * <p>When the file is in fixture format ({@link #FIXTURE_RECORD_LENGTH}
     * = 36) the reader yields 36-byte buffers omitting the trailing
     * 14-byte FILLER region. {@code parsePadded} reconstructs the
     * canonical layout by appending 14 ASCII spaces &mdash; the same
     * value {@link CardXrefRecord#encode()} writes into FILLER when
     * the source layout has none. The padding choice (ASCII space
     * 0x20) is the AAP &sect;0.6.5-mandated round-trip-safe filler
     * because {@code parse(b).encode()} on the padded buffer yields
     * a 50-byte buffer whose first 36 bytes equal the original
     * fixture record and whose trailing 14 bytes are spaces, matching
     * the encoded FILLER convention.
     *
     * <p>When the file is in canonical format
     * ({@link #RECORD_LENGTH} = 50) this method returns the input
     * buffer unchanged.
     *
     * @param onDisk the raw on-disk record buffer, length equal to
     *               {@link #fileRecordLength}
     * @return a buffer of exactly {@link #RECORD_LENGTH} bytes ready
     *         for {@link CardXrefRecord#parse(byte[])}
     */
    private byte[] padToCanonical(byte[] onDisk) {
        if (onDisk.length >= RECORD_LENGTH) {
            return onDisk;
        }
        byte[] padded = Arrays.copyOf(onDisk, RECORD_LENGTH);
        Arrays.fill(padded, onDisk.length, RECORD_LENGTH, (byte) 0x20);
        return padded;
    }

    /**
     * Convenience helper that pads {@code onDisk} via
     * {@link #padToCanonical(byte[])} and delegates to
     * {@link CardXrefRecord#parse(byte[])}. Used as the
     * {@code .map(this::parsePadded)} terminal in every stream
     * pipeline below so the dual-format handling is centralised at
     * one call site.
     *
     * @param onDisk the raw on-disk record buffer
     * @return the decoded {@link CardXrefRecord}
     */
    private CardXrefRecord parsePadded(byte[] onDisk) {
        return CardXrefRecord.parse(padToCanonical(onDisk));
    }

    /**
     * Converts a canonical 50-byte encoded record buffer to the
     * on-disk format width. When the file is in fixture format
     * ({@link #FIXTURE_RECORD_LENGTH} = 36) the canonical buffer is
     * truncated to 36 bytes, dropping the trailing 14-byte FILLER
     * region; when the file is in canonical format (50 bytes) the
     * input is returned unchanged.
     *
     * <p>The truncation is byte-loss-safe because the dropped 14
     * bytes are the canonical FILLER which is space-padded
     * (0x20 0x20 ... 0x20) by {@link CardXrefRecord#encode()} when no
     * explicit FILLER was supplied at construction. Records that
     * carry caller-supplied FILLER bytes will lose those bytes when
     * persisted to a fixture-format file; this is the AAP
     * &sect;0.6.5-mandated trade-off documented in
     * {@code MIGRATION_NOTES.md §1.4.9}: byte-for-byte fidelity to
     * the on-disk source format takes precedence over canonical
     * FILLER preservation.
     *
     * @param encoded the canonical {@link #RECORD_LENGTH}-byte
     *                encoded buffer from
     *                {@link CardXrefRecord#encode()}
     * @return a buffer of exactly {@link #fileRecordLength} bytes
     *         ready for {@link FixedWidthWriter} consumption
     */
    private byte[] toFileFormat(byte[] encoded) {
        if (encoded.length == fileRecordLength) {
            return encoded;
        }
        return Arrays.copyOf(encoded, fileRecordLength);
    }

    // ------------------------------------------------------------------
    // PAN masking helper (AAP §0.7.2)
    // ------------------------------------------------------------------

    /**
     * Masks a card-number-shaped string so that all but the last 4
     * characters are replaced with the literal {@code '*'} character.
     * Used by every log line and exception message that references a
     * card-number argument or a {@link CardXrefRecord#xrefCardNum()}
     * value per AAP &sect;0.7.2 (PCI compliance: no full PAN in logs).
     *
     * <p>The helper is intentionally lenient about the input shape so
     * that diagnostic logging never fails on a corrupted or partial
     * card number:
     * <ul>
     *   <li>{@code null} or blank input → {@code "[empty]"}</li>
     *   <li>shorter than 4 characters → {@code "[truncated]"}</li>
     *   <li>otherwise → asterisks for every character except the last
     *       4, which are revealed verbatim</li>
     * </ul>
     *
     * <p>Package-private (not {@code private}) so that adapter unit
     * tests living in the same package can assert the masking shape
     * directly without resorting to reflection.
     *
     * @param pan the card number to mask; may be {@code null}, blank,
     *            short, or full-length
     * @return a masked string suitable for logging; never {@code null}
     */
    static String maskPan(String pan) {
        if (pan == null || pan.isBlank()) {
            return "[empty]";
        }
        if (pan.length() < 4) {
            return "[truncated]";
        }
        int prefixLen = pan.length() - 4;
        StringBuilder sb = new StringBuilder(pan.length());
        for (int i = 0; i < prefixLen; i++) {
            sb.append('*');
        }
        sb.append(pan, prefixLen, pan.length());
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Primary-key access (XREF-CARD-NUM)
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Translates the COBOL access pattern exercised by:
     * <ul>
     *   <li>CBTRN02C paragraph {@code 1500-A-LOOKUP-XREF}
     *       ({@code app/cbl/CBTRN02C.cbl:L380-L392}):
     *       {@code MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM}
     *       followed by
     *       {@code READ XREF-FILE INTO CARD-XREF-RECORD INVALID KEY
     *       ...}. {@link Optional#empty()} ↔ {@code INVALID KEY} ↔
     *       {@code WS-VALIDATION-FAIL-REASON = 100}.</li>
     *   <li>COTRN02C paragraph {@code READ-CCXREF-FILE}
     *       ({@code app/cbl/COTRN02C.cbl:L611-L619}):
     *       {@code EXEC CICS READ DATASET(WS-CCXREF-FILE)
     *       INTO(CARD-XREF-RECORD) RIDFLD(XREF-CARD-NUM) ...}.
     *       {@link Optional#empty()} ↔ {@code DFHRESP(NOTFND)} ↔
     *       {@code WS-MESSAGE = 'Card Number NOT found...'}.</li>
     * </ul>
     *
     * <p>The {@code cardNumber} argument MUST be exactly
     * {@value #PK_LENGTH} characters long (COBOL {@code PIC X(16)}).
     * Shorter or longer keys are rejected with
     * {@link IllegalArgumentException} so that adapters cannot
     * silently right-pad or truncate the search key and return a
     * mismatched record — the COBOL idiom is byte-exact.
     *
     * @throws NullPointerException     if {@code cardNumber} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code cardNumber} length is
     *                                  not exactly {@value #PK_LENGTH}
     * @throws UncheckedIOException     on underlying I/O error (the
     *                                  cause carries the original
     *                                  {@link IOException}); the
     *                                  message references the masked
     *                                  PAN never the cleartext value
     */
    @Override
    public Optional<CardXrefRecord> findByCardNumber(String cardNumber) {
        Objects.requireNonNull(cardNumber, "cardNumber");
        if (cardNumber.length() != PK_LENGTH) {
            throw new IllegalArgumentException(
                    "cardNumber must be " + PK_LENGTH + " chars; got " + cardNumber.length());
        }
        byte[] keyBytes = cardNumber.getBytes(charset);
        try {
            // parsePadded centralizes the dual-format (36 vs 50 byte)
            // adaptation: when the file is in 36-byte fixture format
            // the on-disk buffer is space-padded to the canonical
            // 50-byte width before delegating to CardXrefRecord.parse.
            return reader.findByKey(keyBytes, PK_OFFSET).map(this::parsePadded);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error reading CARDXREF for cardNumber=" + maskPan(cardNumber), e);
        }
    }

    // ------------------------------------------------------------------
    // AIX (alternate-index) access (XREF-ACCT-ID)
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Translates the COBOL access pattern exercised by:
     * <ul>
     *   <li>COBIL00C {@code READ-CXACAIX-FILE}
     *       ({@code app/cbl/COBIL00C.cbl:L408-L436})</li>
     *   <li>COTRN02C {@code READ-CXACAIX-FILE}
     *       ({@code app/cbl/COTRN02C.cbl:L578-L586})</li>
     *   <li>COACTVWC {@code 9200-GETCARDXREF-BYACCT}
     *       ({@code app/cbl/COACTVWC.cbl:L723-L735})</li>
     * </ul>
     * All three use {@code EXEC CICS READ DATASET(...AIX path...)
     * INTO(CARD-XREF-RECORD) RIDFLD(XREF-ACCT-ID)} which returns the
     * first record per AIX iteration order. The file-backed
     * implementation reproduces this by streaming all records in
     * primary-key order and selecting the first record whose
     * {@code XREF-ACCT-ID} field at offset
     * {@value #AIX_ACCTID_OFFSET} matches the supplied
     * {@code accountId} — same observable result as VSAM AIX where the
     * AIX is naturally ordered within an account by ascending
     * {@code XREF-CARD-NUM}.
     *
     * <p>The underlying stream is closed via try-with-resources so
     * that the {@link java.nio.channels.SeekableByteChannel} is
     * released even on a {@code RuntimeException} downstream.
     *
     * @throws IllegalArgumentException if {@code accountId} is
     *                                  negative or exceeds
     *                                  {@value #MAX_ACCT_ID}
     * @throws UncheckedIOException     on underlying I/O error
     */
    @Override
    public Optional<CardXrefRecord> findByAccountId(long accountId) {
        validateAccountId(accountId);
        byte[] aixKey = formatAcctIdKey(accountId);
        try (Stream<byte[]> stream = reader.streamSequential()) {
            return stream
                    .filter(buf -> keyMatches(buf, AIX_ACCTID_OFFSET, aixKey))
                    // parsePadded centralizes the dual-format (36 vs
                    // 50 byte) adaptation; see field-level Javadoc on
                    // fileRecordLength for the on-disk width contract
                    // and MIGRATION_NOTES.md §1.4.9 for the rationale.
                    .map(this::parsePadded)
                    .findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error scanning CARDXREF for accountId=" + accountId, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns every matching record per AIX iteration order
     * (ascending {@code XREF-CARD-NUM} within the account, matching
     * VSAM AIX implementation order). The implementation is a
     * sequential scan of the entire CARDXREF file filtered on the
     * {@code XREF-ACCT-ID} field at offset
     * {@value #AIX_ACCTID_OFFSET}; this is O(N) per call, which is
     * acceptable for CardDemo's CARDXREF size (one row per card).
     *
     * <p>Callers MUST close the returned stream via try-with-resources
     * so that the underlying {@link java.nio.channels.SeekableByteChannel}
     * is released. The stream {@code onClose} hook installed by the
     * {@link FixedWidthReader} closes the channel for us; failure to
     * close the returned stream will leak the file descriptor.
     *
     * <p>If the underlying file does not exist the returned stream is
     * empty (NOTFND parity), never {@code null}.
     *
     * @throws IllegalArgumentException if {@code accountId} is
     *                                  negative or exceeds
     *                                  {@value #MAX_ACCT_ID}
     * @throws UncheckedIOException     on underlying I/O error while
     *                                  opening the file channel
     */
    @Override
    public Stream<CardXrefRecord> streamByAccountId(long accountId) {
        validateAccountId(accountId);
        byte[] aixKey = formatAcctIdKey(accountId);
        try {
            return reader.streamSequential()
                    .filter(buf -> keyMatches(buf, AIX_ACCTID_OFFSET, aixKey))
                    // parsePadded centralizes the dual-format (36 vs
                    // 50 byte) adaptation.
                    .map(this::parsePadded);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error scanning CARDXREF for accountId=" + accountId, e);
        }
    }

    /**
     * Returns {@code true} iff {@code recordBuffer} contains exactly
     * {@code expectedKey} starting at {@code offset}. Defensive about
     * buffer length: returns {@code false} (rather than throwing) when
     * the buffer is shorter than {@code offset + expectedKey.length},
     * so a corrupt or short record does not abort the scan stream.
     *
     * <p>Marked {@code private static} so that the
     * {@code .filter(buf -> keyMatches(...))} lambdas in
     * {@link #findByAccountId(long)} and
     * {@link #streamByAccountId(long)} do not capture {@code this} —
     * keeping the lambda lightweight and avoiding accidental retention
     * of the adapter instance via the stream's spliterator.
     *
     * @param recordBuffer the candidate record buffer
     * @param offset       the byte offset where the key should begin
     * @param expectedKey  the exact key bytes to match
     * @return {@code true} iff {@code recordBuffer} contains
     *         {@code expectedKey} at {@code offset}
     */
    private static boolean keyMatches(byte[] recordBuffer, int offset, byte[] expectedKey) {
        if (recordBuffer.length < offset + expectedKey.length) {
            return false;
        }
        for (int i = 0; i < expectedKey.length; i++) {
            if (recordBuffer[offset + i] != expectedKey[i]) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Sequential read and mutation paths
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Translates the COBOL access pattern exercised by CBACT03C's
     * main loop ({@code app/cbl/CBACT03C.cbl:L74-L116}):
     * {@code OPEN INPUT XREFFILE-FILE} +
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'
     *   READ XREFFILE-FILE INTO CARD-XREF-RECORD
     *   AT END SET END-OF-FILE TO TRUE}
     * loop + {@code CLOSE XREFFILE-FILE}. The Java translation must
     * emit records in ascending {@code XREF-CARD-NUM} primary-key
     * order so that any downstream
     * {@code DISPLAY CARD-XREF-RECORD} consumer sees a byte-stream
     * identical to the COBOL baseline per AAP &sect;0.6.5.
     *
     * <p>Callers MUST close the returned stream via try-with-resources
     * so that the underlying file channel is released.
     *
     * @throws UncheckedIOException on underlying I/O error while
     *                              opening the file channel
     */
    @Override
    public Stream<CardXrefRecord> streamSequential() {
        try {
            // parsePadded centralizes the dual-format (36 vs 50 byte)
            // adaptation so CBACT03C's sequential dump sees canonical
            // 50-byte records regardless of the on-disk file width.
            return reader.streamSequential().map(this::parsePadded);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error opening CARDXREF for sequential read: " + dataFile, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Upserts a CARDXREF record — inserts when no record exists
     * for the supplied {@code XREF-CARD-NUM} primary key, rewrites
     * otherwise. Translates the {@code IDCAMS REPRO} bulk-load path
     * exercised by {@code app/jcl/XREFFILE.jcl}; no active COBOL
     * online or batch program writes to CARDXREF at runtime.
     *
     * <p>The underlying
     * {@link FixedWidthWriter#upsert(byte[], int, byte[])} is
     * crash-safe (temp-file + atomic move per the writer's
     * documented contract). Concurrent {@link #save(CardXrefRecord)}
     * and {@link #delete(String)} invocations are serialised through
     * the {@code writeLock} monitor so that the read-modify-write
     * cycle of the upsert is atomic across threads.
     *
     * <p>The DEBUG log line emitted after a successful save includes
     * the masked PAN, the account id, and the customer id — never the
     * cleartext PAN. Per AAP &sect;0.7.2 the cleartext PAN MUST NOT
     * appear in any log line.
     *
     * <h4>Byte-fidelity check</h4>
     * The encoded buffer length is asserted to be exactly
     * {@value #RECORD_LENGTH} bytes before writing; this is a defence
     * against a future refactor of {@link CardXrefRecord#encode()}
     * that might change the record geometry without updating this
     * adapter, which would silently corrupt the underlying file.
     *
     * @param record the cross-reference record to upsert; never
     *               {@code null}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalStateException if {@code record.encode()} returns
     *                               a buffer that is not exactly
     *                               {@value #RECORD_LENGTH} bytes
     *                               (defence against an incompatible
     *                               domain refactor)
     * @throws UncheckedIOException  on underlying I/O error
     */
    @Override
    public void save(CardXrefRecord record) {
        Objects.requireNonNull(record, "record");
        byte[] encoded = record.encode();
        if (encoded.length != RECORD_LENGTH) {
            throw new IllegalStateException(
                    "CardXrefRecord.encode() returned " + encoded.length
                            + " bytes; expected " + RECORD_LENGTH);
        }
        // Adapt to the on-disk file width: when the file is in 36-byte
        // fixture format the trailing 14-byte FILLER region is dropped
        // so the file stays in fixture format and a subsequent
        // read+parse remains byte-for-byte round-trip identical.
        // toFileFormat is a no-op when fileRecordLength == RECORD_LENGTH.
        byte[] toWrite = toFileFormat(encoded);
        byte[] keyBytes = record.xrefCardNum().getBytes(charset);
        synchronized (writeLock) {
            try {
                writer.upsert(keyBytes, PK_OFFSET, toWrite);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Error upserting CARDXREF cardNumber="
                                + maskPan(record.xrefCardNum()), e);
            }
        }
        LOG.debug("save cardNumber={} acctId={} custId={} status=ok",
                maskPan(record.xrefCardNum()), record.xrefAcctId(), record.xrefCustId());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deletes the CARDXREF record with the supplied
     * {@code XREF-CARD-NUM} primary key. Translates the
     * {@code IDCAMS DELETE} pre-load cleanup path exercised by
     * {@code app/jcl/XREFFILE.jcl}; no active COBOL program issues
     * {@code EXEC CICS DELETE} against CARDXREF at runtime.
     *
     * <p>The {@code cardNumber} argument MUST be exactly
     * {@value #PK_LENGTH} characters long (COBOL {@code PIC X(16)}).
     * Shorter or longer keys are rejected with
     * {@link IllegalArgumentException} so that the adapter cannot
     * silently right-pad or truncate the search key and delete a
     * mismatched record — the COBOL idiom is byte-exact.
     *
     * <p>If no record matches the supplied key,
     * {@link NoSuchElementException} is thrown — mirroring the
     * COBOL {@code DELETE INVALID KEY} (FILE STATUS '23'/'24') flow.
     * The exception message references the masked PAN never the
     * cleartext value.
     *
     * @throws NullPointerException     if {@code cardNumber} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code cardNumber} length
     *                                  is not exactly
     *                                  {@value #PK_LENGTH}
     * @throws NoSuchElementException   if no CARDXREF record exists
     *                                  with the supplied key
     * @throws UncheckedIOException     on underlying I/O error
     */
    @Override
    public void delete(String cardNumber) {
        Objects.requireNonNull(cardNumber, "cardNumber");
        if (cardNumber.length() != PK_LENGTH) {
            throw new IllegalArgumentException(
                    "cardNumber must be " + PK_LENGTH + " chars; got " + cardNumber.length());
        }
        byte[] keyBytes = cardNumber.getBytes(charset);
        synchronized (writeLock) {
            try {
                boolean removed = writer.deleteByKey(keyBytes, PK_OFFSET);
                if (!removed) {
                    throw new NoSuchElementException(
                            "No CARDXREF record found for cardNumber=" + maskPan(cardNumber));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Error deleting CARDXREF cardNumber=" + maskPan(cardNumber), e);
            }
        }
        LOG.debug("delete cardNumber={} status=ok", maskPan(cardNumber));
    }

    /**
     * Releases any underlying resources held by this repository. This
     * adapter holds no persistent file channel between method calls
     * (the {@link FixedWidthReader} and {@link FixedWidthWriter} open
     * a fresh channel per operation and close it via
     * try-with-resources or {@code Stream.onClose}), so {@link #close}
     * is a documented no-op.
     *
     * <p>The implementation is idempotent (per the
     * {@link CardXrefRepository} contract): callers may invoke it
     * multiple times without observable side-effects.
     */
    @Override
    public void close() {
        // No persistent channel held; the reader/writer open a fresh
        // channel per operation. Logged at DEBUG so that operational
        // diagnostics can confirm the close was invoked.
        LOG.debug("FileCardXrefRepository.close(): file={}", dataFile);
    }

    // ------------------------------------------------------------------
    // Internal validation and key-formatting helpers
    // ------------------------------------------------------------------

    /**
     * Validates that {@code accountId} fits in the
     * {@value #AIX_ACCTID_LENGTH}-digit {@code XREF-ACCT-ID} field
     * ({@code PIC 9(11)}, range {@code 0..} {@value #MAX_ACCT_ID}).
     * Rejecting an out-of-range key here surfaces the misuse at the
     * adapter boundary rather than letting it propagate as a confusing
     * scan-with-no-results downstream.
     *
     * @param accountId the candidate account id
     * @throws IllegalArgumentException if {@code accountId} is
     *                                  negative or exceeds
     *                                  {@value #MAX_ACCT_ID}
     */
    private static void validateAccountId(long accountId) {
        if (accountId < 0L || accountId > MAX_ACCT_ID) {
            throw new IllegalArgumentException(
                    "accountId out of PIC 9(11) range [0.." + MAX_ACCT_ID + "]: " + accountId);
        }
    }

    /**
     * Formats {@code accountId} as a {@value #AIX_ACCTID_LENGTH}-byte
     * zero-left-padded ASCII digit sequence matching COBOL
     * {@code PIC 9(11)} DISPLAY representation, then converts to bytes
     * via the configured {@link #charset}. The result is the exact
     * byte pattern that appears at offset
     * {@value #AIX_ACCTID_OFFSET} in any CARDXREF record whose
     * {@code XREF-ACCT-ID} equals {@code accountId}.
     *
     * <p>{@code String.format("%011d", accountId)} produces the
     * 11-digit form with leading zeros; {@code accountId} is already
     * range-validated by {@link #validateAccountId(long)} so the
     * format never overflows the field.
     *
     * @param accountId the validated account id
     * @return the {@value #AIX_ACCTID_LENGTH}-byte AIX scan key
     */
    private byte[] formatAcctIdKey(long accountId) {
        return String.format("%011d", accountId).getBytes(charset);
    }
}
