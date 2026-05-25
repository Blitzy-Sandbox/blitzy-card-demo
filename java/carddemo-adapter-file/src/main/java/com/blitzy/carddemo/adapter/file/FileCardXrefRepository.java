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
// backend (logback-classic 1.5.12) is supplied at runtime by the composition
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

// Charset is injected via the constructor and held as a final field. It is
// used to convert the 16-char cardNumber argument and the zero-padded
// 11-digit accountId AIX scan key to bytes for byte-for-byte key comparison
// against the underlying record buffer. The charset is also forwarded to the
// FixedWidthReader and FixedWidthWriter constructors for symmetry. Default
// (per AAP §0.6.5) is IBM-1047 (EBCDIC) for production parity; per-file
// overrides via application.properties keys like
// {@code carddemo.file.cardxref.charset}.
import java.nio.charset.Charset;

// NIO.2 Path used as the constructor argument identifying the CARDXREF data
// file. Stored as a private final field, forwarded to the FixedWidthReader /
// FixedWidthWriter constructors, and referenced in error messages. Mandated
// by AAP §0.6.5 ("All file I/O uses java.nio.file ... java.io.File is
// forbidden in new code").
import java.nio.file.Path;

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
     * runtime backend (logback-classic 1.5.12 per AAP &sect;0.5.1) is
     * resolved at composition-root startup, not at adapter
     * construction. All card-number values logged through this logger
     * MUST be passed through {@link #maskPan(String)} first per AAP
     * &sect;0.7.2.
     */
    private static final Logger LOG = LoggerFactory.getLogger(FileCardXrefRepository.class);

    /**
     * Total {@code CARD-XREF-RECORD} length in bytes per copybook
     * {@code app/cpy/CVACT03Y.cpy}: 16 (PAN) + 9 (CUST-ID) + 11
     * (ACCT-ID) + 14 (FILLER) = 50. Mirrors
     * {@link CardXrefRecord#RECORD_LENGTH} but is restated here as a
     * local constant because the adapter holds the value at three
     * independent sites (constructor, save-length check, key offset
     * arithmetic) and the duplication is cheaper than a static
     * cross-module reference for a single integer literal.
     */
    private static final int RECORD_LENGTH = 50;

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
        this.reader = new FixedWidthReader(dataFile, RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(dataFile, RECORD_LENGTH, charset);
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
            return reader.findByKey(keyBytes, PK_OFFSET).map(CardXrefRecord::parse);
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
                    .map(CardXrefRecord::parse)
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
                    .map(CardXrefRecord::parse);
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
            return reader.streamSequential().map(CardXrefRecord::parse);
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
     * <h3>Byte-fidelity check</h3>
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
        byte[] keyBytes = record.xrefCardNum().getBytes(charset);
        synchronized (writeLock) {
            try {
                writer.upsert(keyBytes, PK_OFFSET, encoded);
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
