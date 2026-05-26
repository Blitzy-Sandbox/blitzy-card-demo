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
import com.blitzy.carddemo.domain.port.CardRepository;
import com.blitzy.carddemo.domain.record.CardRecord;

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
 * File-backed implementation of {@link CardRepository} &mdash; reads and writes
 * the {@code CARDDATA} VSAM KSDS dataset as a fixed-width binary file via
 * {@link java.nio.file}. Per AAP &sect;0.6.5 ("All file I/O uses
 * {@code java.nio.file} ... {@code java.io.File} is forbidden in new code")
 * the adapter routes every disk operation through the {@link FixedWidthReader}
 * and {@link FixedWidthWriter} primitives, which in turn use
 * {@link java.nio.file.Files#newByteChannel(Path,
 * java.nio.file.OpenOption...)} exclusively.
 *
 * <h2>Source artefact</h2>
 * Implements the access path for the {@code CARD-RECORD} 01-level group
 * defined in {@code app/cpy/CVACT02Y.cpy} (150-byte fixed-width record,
 * 16-byte {@code CARD-NUM} primary key at offset 0). The COBOL programs that
 * consume this dataset are:
 * <ul>
 *   <li><b>CBACT02C</b> &mdash; sequential read of CARDDATA
 *       ({@code OPEN INPUT CARDFILE-FILE} + {@code READ CARDFILE-FILE INTO
 *       CARD-RECORD AT END SET END-OF-FILE} loop, {@code app/cbl/CBACT02C.cbl}
 *       paragraph 1000-CARDFILE-GET-NEXT). Maps to
 *       {@link #streamSequential()}.</li>
 *   <li><b>COCRDLIC</b> &mdash; paginated online browse via
 *       {@code EXEC CICS STARTBR DATASET(LIT-CARD-FILE)
 *       RIDFLD(WS-CARD-RID-CARDNUM) KEYLENGTH(LENGTH OF
 *       WS-CARD-RID-CARDNUM) GTEQ} followed by a {@code READNEXT} loop
 *       ({@code app/cbl/COCRDLIC.cbl:L1124-L1154}). Maps to
 *       {@link #streamFrom(String)} which exposes "GTEQ"
 *       (greater-than-or-equal-to) semantics.</li>
 *   <li><b>COCRDSLC</b> &mdash; random online detail view via
 *       {@code EXEC CICS READ FILE(LIT-CARDFILENAME)
 *       RIDFLD(WS-CARD-RID-CARDNUM) INTO(CARD-RECORD)} with
 *       {@code DFHRESP(NOTFND)} fall-through
 *       ({@code app/cbl/COCRDSLC.cbl:L742-L755}). Maps to
 *       {@link #findByCardNumber(String)} returning
 *       {@link Optional#empty()} on NOTFND.</li>
 *   <li><b>COCRDUPC</b> &mdash; random online read at
 *       {@code app/cbl/COCRDUPC.cbl:L1382-L1395} followed by
 *       {@code EXEC CICS READ ... UPDATE} at L1427-L1436 and the
 *       subsequent REWRITE in {@code 9200-WRITE-PROCESSING}. Maps to
 *       {@link #findByCardNumber(String)} + {@link #save(CardRecord)}
 *       (where {@code save} is upsert-by-key).</li>
 *   <li><b>CARDFILE.jcl</b> &mdash; IDCAMS {@code DELETE} /
 *       {@code DEFINE} / {@code REPRO} cycle. Maps to
 *       {@link #delete(String)} (pre-define cleanup) and
 *       {@link #save(CardRecord)} (bulk load).</li>
 * </ul>
 *
 * <h2>Record layout</h2>
 * Each record is exactly 150 bytes (see {@link CardRecord#RECORD_LENGTH}).
 * The 16-byte {@code CARD-NUM} field is the KSDS primary key at offset 0;
 * see {@link CardRecord} for the complete byte-by-byte layout, including
 * the 50-byte embossed name, 10-byte expiration date, and the
 * PAN/CVV that are masked in {@link CardRecord#toString()} per AAP
 * &sect;0.7.2.
 *
 * <h2>PCI / PAN masking (AAP &sect;0.7.2)</h2>
 * Per AAP &sect;0.7.2 <em>"No card PAN logged in full; mask all but last 4
 * digits in logs and error messages."</em> Every log statement and every
 * exception message in this class that references a card number routes
 * the value through {@link #maskPan(String)}, which replaces all but the
 * trailing 4 characters with asterisks. Sentinel values
 * ({@code "[empty]"}, {@code "[truncated]"}) are used for null/blank or
 * shorter-than-4-character inputs so that even abnormal log paths
 * cannot leak a partial PAN.
 *
 * <h2>Adapter responsibilities</h2>
 * <ul>
 *   <li>Translate {@link CardRecord} domain objects to and from the
 *       150-byte fixed-width byte image via
 *       {@link CardRecord#parse(byte[])} (in read paths) and
 *       {@link CardRecord#encode()} (in write paths).</li>
 *   <li>Look up by 16-character card number via
 *       {@link FixedWidthReader#findByKey(byte[], int)}.</li>
 *   <li>Stream all records in file (KSDS key) order via
 *       {@link FixedWidthReader#streamSequential()}.</li>
 *   <li>Browse from a starting key (inclusive, GTEQ) via
 *       {@link FixedWidthReader#streamFromKey(byte[], int)}.</li>
 *   <li>Persist card updates via {@link FixedWidthWriter#upsert(byte[],
 *       int, byte[])} (rewrite-if-exists else insert in key-sorted
 *       position).</li>
 *   <li>Delete records via {@link FixedWidthWriter#deleteByKey(byte[],
 *       int)}, translating a {@code false} return into
 *       {@link NoSuchElementException}.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 * The underlying {@link FixedWidthWriter} primitive is individually
 * thread-safe (it serializes mutations on its own internal lock). This
 * adapter adds an additional repository-level {@code writeLock} that
 * serializes the encode-and-write sequence in {@link #save(CardRecord)}
 * and the validate-encode-key-and-delete sequence in
 * {@link #delete(String)}. This wider critical section guards against
 * any future repository-level invariants (cross-method atomicity is a
 * deliberately limited contract; callers requiring multi-record
 * atomicity must orchestrate at the application layer just as the
 * COBOL {@code SYNCPOINT} flows did).
 *
 * <h2>No framework / no forbidden idioms (AAP &sect;0.6.5, &sect;0.7.4)</h2>
 * Per AAP &sect;0.6.5 and &sect;0.7.4 this adapter:
 * <ul>
 *   <li>Uses only {@link java.nio.file} primitives (no
 *       {@link java.io.File}, {@code FileInputStream},
 *       {@code FileOutputStream}, {@code FileReader},
 *       {@code RandomAccessFile}).</li>
 *   <li>Has no dependency on Spring, Hibernate, JPA, or Lombok.</li>
 *   <li>Uses no {@code ThreadLocal} (the writer-level lock is the only
 *       in-process serialization primitive; ScopedValue would be used at
 *       the application layer if context propagation were needed).</li>
 *   <li>Uses no preview features and no reflection.</li>
 *   <li>Uses no {@code double} or {@code float} (the byte-level adapter
 *       does not touch monetary values, but the prohibition stands).</li>
 * </ul>
 *
 * @see CardRepository
 * @see CardRecord
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @since 1.0.0
 */
@CobolProgram(
        value = "CARDDATA",
        sourcePath = "app/cpy/CVACT02Y.cpy",
        translationDate = "2025-10-24",
        notes = "File-backed adapter for the CARDDATA VSAM KSDS "
                + "(150-byte CARD-RECORD; 16-byte CARD-NUM primary key at "
                + "offset 0). PAN MASKED in logs per AAP §0.7.2 "
                + "(last 4 digits only) — every log line and every "
                + "exception message that references the card number "
                + "routes through the maskPan(String) helper. "
                + "See CBACT02C (sequential), COCRDLIC (browse), "
                + "COCRDSLC (random read), COCRDUPC (update)."
)
public final class FileCardRepository implements CardRepository {

    /**
     * SLF4J logger for this adapter. Used for DEBUG-level operational log
     * lines on successful save and delete outcomes. Every log statement
     * that references the card number argument routes the value through
     * the package-private {@link #maskPan(String)} helper, which exposes
     * only the trailing 4 digits per AAP &sect;0.7.2 (PCI compliance).
     * The concrete logging backend (logback-classic 1.5.19) is provided
     * at runtime by the composition root in {@code carddemo-app}; this
     * adapter binds only to the SLF4J facade per AAP &sect;0.6.12.
     */
    private static final Logger LOG = LoggerFactory.getLogger(FileCardRepository.class);

    /**
     * Fixed CARD-RECORD length in bytes per copybook
     * {@code app/cpy/CVACT02Y.cpy}: 16 + 11 + 3 + 50 + 10 + 1 + 59 = 150.
     * Matches {@link CardRecord#RECORD_LENGTH}; declared locally to keep
     * this adapter's byte-level contract self-documenting and to avoid
     * the indirect dependency on the domain record's static constant for
     * a value that is part of this adapter's own fixed-width I/O
     * configuration.
     */
    private static final int RECORD_LENGTH = 150;

    /**
     * Byte offset of the {@code CARD-NUM} primary key field within each
     * 150-byte record. The key is the first field in the record
     * structure per {@code app/cpy/CVACT02Y.cpy} line 5:
     * {@code 05  CARD-NUM  PIC X(16).}
     */
    private static final int KEY_OFFSET = 0;

    /**
     * Length of the {@code CARD-NUM} primary key field in bytes. The
     * COBOL declaration {@code PIC X(16)} is exactly 16 alphanumeric
     * bytes &mdash; this is a fixed-width string, NOT a variable-length
     * key. Strict equal-length validation on the {@code cardNumber}
     * parameter is enforced by {@link #validateCardNumber(String)} per
     * AAP Phase 12 Key Insight #4 ("16-byte exact length").
     */
    private static final int KEY_LENGTH = 16;

    /**
     * The CARDDATA data file path. Held as a {@link Path} per AAP
     * &sect;0.6.5 (no {@code java.io.File} in new code). Configurable at
     * the composition root via the {@code carddemo.file.carddata.path}
     * property (12-factor configuration per AAP &sect;0.5.4).
     */
    private final Path dataFile;

    /**
     * Configured codepage for byte-level key encoding. Defaults to
     * {@code IBM-1047} (EBCDIC) per AAP &sect;0.6.5 with per-file
     * override via {@code carddemo.file.carddata.charset}. Used in
     * {@link #formatKey(String)} to encode the 16-character
     * {@code CARD-NUM} into the 16-byte key buffer that
     * {@link FixedWidthReader#findByKey(byte[], int)} and
     * {@link FixedWidthWriter#upsert(byte[], int, byte[])} consume.
     */
    private final Charset charset;

    /**
     * Foundational byte-level reader for the CARDDATA file. Instantiated
     * once in the constructor and reused across every read operation.
     * Thread-safe per its own contract (opens a fresh channel per call;
     * shares no mutable state between calls).
     */
    private final FixedWidthReader reader;

    /**
     * Foundational byte-level writer for the CARDDATA file. Instantiated
     * once in the constructor and reused across every write operation.
     * Internally synchronizes its own mutations on its private lock; this
     * adapter additionally wraps {@code upsert} and {@code deleteByKey}
     * in the repository-level {@link #writeLock} (below) so that the
     * encode-and-write sequence in {@link #save(CardRecord)} cannot
     * interleave with another concurrent write.
     */
    private final FixedWidthWriter writer;

    /**
     * Repository-level write lock. Serializes the encode-and-upsert
     * sequence in {@link #save(CardRecord)} and the
     * validate-encode-key-and-deleteByKey sequence in
     * {@link #delete(String)}. Held only across the smallest possible
     * critical section (a single port-method invocation); not
     * re-entrant; not exposed via a public accessor (which would tempt
     * callers to perform cross-method critical sections that this class
     * is not designed to support).
     */
    private final Object writeLock = new Object();

    /**
     * Convenience constructor delegating to the canonical
     * {@link #FileCardRepository(Path, Charset)} constructor with
     * {@link EbcdicTranscoder#DEFAULT_CHARSET_NAME} ({@code IBM-1047}
     * EBCDIC) as the default codepage per AAP &sect;0.6.5 ("The default
     * codepage for EBCDIC-to-ASCII transcoding is
     * {@code Charset.forName(\"IBM-1047\")}"). Provided to preserve
     * source-code compatibility with the established codebase convention
     * used by {@code Read*DumpApp} composition roots, which construct
     * repositories with a single {@link Path} argument and rely on the
     * mandated EBCDIC default.
     *
     * <p>Routes through {@link EbcdicTranscoder#DEFAULT_CHARSET_NAME}
     * rather than the hardcoded literal {@code "IBM-1047"} per
     * MIGRATION_NOTES.md &sect;1.12.17 so any future codepage default
     * change flows uniformly through a single constant in the
     * {@code carddemo-adapter-file} module. Matches the same constant
     * usage in {@code FileAccountRepository}, {@code FileCustomerRepository},
     * and the other 9 file-backed repository adapters.
     *
     * <p>For an ASCII-encoded input file (e.g., the ASCII fixtures
     * shipped under {@code app/data/ASCII/}), use the canonical two-arg
     * constructor and pass
     * {@link java.nio.charset.StandardCharsets#US_ASCII US-ASCII}
     * explicitly &mdash; or set the
     * {@code carddemo.file.carddata.charset} property at the composition
     * root, which the calling code then resolves and forwards to the
     * two-arg form.
     *
     * @param dataFile path to the CARDDATA file (e.g.,
     *                 {@code data/carddata}); must not be {@code null}
     * @throws NullPointerException if {@code dataFile} is {@code null}
     */
    public FileCardRepository(Path dataFile) {
        this(dataFile, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs a new file-backed CARDDATA adapter. The data file need
     * NOT exist at construction time; read methods handle missing files
     * as NOTFND parity ({@link Optional#empty()} or empty streams), and
     * write methods create the file on first call if absent.
     *
     * @param dataFile path to the CARDDATA file (e.g.,
     *                 {@code data/carddata}); must not be {@code null}
     * @param charset  codepage used to encode the 16-character
     *                 {@code CARD-NUM} key into bytes (typically
     *                 {@code IBM-1047} EBCDIC or
     *                 {@link java.nio.charset.StandardCharsets#US_ASCII
     *                 US-ASCII}); must not be {@code null}
     * @throws NullPointerException if {@code dataFile} or {@code charset}
     *                              is {@code null}
     */
    public FileCardRepository(Path dataFile, Charset charset) {
        this.dataFile = Objects.requireNonNull(dataFile, "dataFile");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(dataFile, RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(dataFile, RECORD_LENGTH, charset);
    }

    // ------------------------------------------------------------------
    // Port methods — read paths
    // ------------------------------------------------------------------

    /**
     * Random read by {@code CARD-NUM} primary key. Corresponds to
     * {@code EXEC CICS READ FILE(LIT-CARDFILENAME)
     * RIDFLD(WS-CARD-RID-CARDNUM) INTO(CARD-RECORD)} in COCRDSLC
     * ({@code app/cbl/COCRDSLC.cbl:L742}) and COCRDUPC
     * ({@code app/cbl/COCRDUPC.cbl:L1382-L1390}).
     *
     * <p>Returns {@link Optional#empty()} when the key is not found
     * &mdash; this maps to the COBOL {@code WHEN DFHRESP(NOTFND)} branch
     * ({@code app/cbl/COCRDSLC.cbl:L752-L755},
     * {@code app/cbl/COCRDUPC.cbl:L1392-L1395}).
     *
     * <p>Implementation note: the underlying
     * {@link FixedWidthReader#findByKey(byte[], int)} performs a linear
     * scan over the file (O(N/2) on average). For CardDemo's
     * small-to-medium CARDDATA file this cost is acceptable.
     *
     * @param cardNumber the 16-character {@code CARD-NUM} primary key
     * @return an {@link Optional} carrying the matching
     *         {@link CardRecord}, or {@link Optional#empty()} if no
     *         record exists with that key
     * @throws NullPointerException     if {@code cardNumber} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code cardNumber} is not
     *                                  exactly {@value #KEY_LENGTH}
     *                                  characters
     * @throws UncheckedIOException     if the file cannot be read
     */
    @Override
    public Optional<CardRecord> findByCardNumber(String cardNumber) {
        Objects.requireNonNull(cardNumber, "cardNumber");
        validateCardNumber(cardNumber);
        byte[] keyBytes = formatKey(cardNumber);
        try {
            return reader.findByKey(keyBytes, KEY_OFFSET)
                    .map(CardRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error reading CARDDATA for cardNumber=" + maskPan(cardNumber), e);
        }
    }

    /**
     * Sequential dump of every record in ascending {@code CARD-NUM}
     * order. Corresponds to the CBACT02C main loop
     * ({@code app/cbl/CBACT02C.cbl}, paragraph 1000-CARDFILE-GET-NEXT).
     *
     * <p>The returned {@link Stream} backs an underlying file channel.
     * Callers MUST close the stream (typically via try-with-resources)
     * to release the channel:
     *
     * <pre>{@code
     *   try (Stream<CardRecord> cards = repo.streamSequential()) {
     *       cards.forEach(this::process);
     *   }
     * }</pre>
     *
     * @return a closeable {@link Stream} of every {@link CardRecord} in
     *         the dataset, in ascending {@code CARD-NUM} key order
     * @throws UncheckedIOException if the file cannot be opened
     */
    @Override
    public Stream<CardRecord> streamSequential() {
        try {
            return reader.streamSequential().map(CardRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error opening CARDDATA for sequential read: " + dataFile, e);
        }
    }

    /**
     * GTEQ browse: stream every record whose {@code CARD-NUM} is greater
     * than or equal to {@code startCardNumber}, in ascending key order.
     * Corresponds to the COCRDLIC {@code 9000-READ-FORWARD} paragraph
     * ({@code app/cbl/COCRDLIC.cbl:L1129-L1154}): {@code EXEC CICS STARTBR
     * DATASET(LIT-CARD-FILE) RIDFLD(WS-CARD-RID-CARDNUM)
     * KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM) GTEQ} followed by a
     * {@code READNEXT} loop. The {@code GTEQ} clause positions the
     * browse cursor at the first record AT OR AFTER the supplied key.
     *
     * <p>If {@code startCardNumber} is {@code null} or
     * {@link String#isBlank() blank}, this method behaves identically to
     * {@link #streamSequential()} (the cursor starts at the lowest key
     * in the dataset). This matches the COCRDLIC convention of
     * initialising the browse with {@code MOVE LOW-VALUES TO WS-ALL-ROWS}
     * before {@code STARTBR} ({@code app/cbl/COCRDLIC.cbl:L1124}), which
     * positions the cursor at the beginning of the file when no explicit
     * starting key has been supplied by the user.
     *
     * <p>The returned {@link Stream} backs an underlying file channel.
     * Callers MUST close the stream (typically via try-with-resources)
     * to release the channel.
     *
     * @param startCardNumber the inclusive lower-bound {@code CARD-NUM}
     *                        key, or {@code null} / blank to start from
     *                        the beginning of the dataset
     * @return a closeable {@link Stream} of cards with key &gt;=
     *         {@code startCardNumber} in ascending order
     * @throws IllegalArgumentException if a non-blank
     *                                  {@code startCardNumber} is not
     *                                  exactly {@value #KEY_LENGTH}
     *                                  characters
     * @throws UncheckedIOException     if the file cannot be opened
     */
    @Override
    public Stream<CardRecord> streamFrom(String startCardNumber) {
        // Null or blank means "start at the lowest key" per port contract.
        // COCRDLIC initialises WS-CARD-RID-CARDNUM with LOW-VALUES
        // (app/cbl/COCRDLIC.cbl:L1124) before STARTBR; an equivalent
        // empty start-key positions the cursor at the first record.
        if (startCardNumber == null || startCardNumber.isBlank()) {
            return streamSequential();
        }
        validateCardNumber(startCardNumber);
        byte[] startKey = formatKey(startCardNumber);
        try {
            return reader.streamFromKey(startKey, KEY_OFFSET).map(CardRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error browsing CARDDATA from cardNumber="
                            + maskPan(startCardNumber), e);
        }
    }

    // ------------------------------------------------------------------
    // Port methods — write paths
    // ------------------------------------------------------------------

    /**
     * Upserts a {@link CardRecord} into CARDDATA: rewrites the existing
     * record in place if a record with the same {@code CARD-NUM} already
     * exists, or inserts a new record in the correct key-sorted position
     * otherwise. Corresponds to:
     * <ul>
     *   <li>{@code EXEC CICS REWRITE FILE(LIT-CARDFILENAME)
     *       FROM(CARD-RECORD)} in COCRDUPC {@code 9200-WRITE-PROCESSING}
     *       at {@code app/cbl/COCRDUPC.cbl:L1427-L1436}.</li>
     *   <li>The bulk IDCAMS REPRO load of CARDDATA driven by
     *       {@code app/jcl/CARDFILE.jcl}.</li>
     * </ul>
     *
     * <p>The write is wrapped in the repository-level
     * {@link #writeLock} so that the encode-and-write critical section
     * is atomic with respect to other concurrent {@code save} or
     * {@code delete} calls on this instance. The underlying
     * {@link FixedWidthWriter#upsert(byte[], int, byte[])} provides
     * crash-safety via atomic temp-file + rename.
     *
     * <p>A debug log line is emitted on success; the card number is
     * always masked via {@link #maskPan(String)} per AAP &sect;0.7.2.
     *
     * @param record the card record to upsert; never {@code null}
     * @throws NullPointerException  if {@code record} is {@code null}
     * @throws IllegalStateException if {@code record.encode()} returns a
     *                               buffer whose length is not exactly
     *                               {@value #RECORD_LENGTH} bytes
     *                               (indicates a defect in
     *                               {@link CardRecord})
     * @throws UncheckedIOException  if the underlying write fails
     */
    @Override
    public void save(CardRecord record) {
        Objects.requireNonNull(record, "record");
        byte[] encoded = record.encode();
        // Defensive length assertion: CardRecord.encode() is contracted
        // to always return exactly RECORD_LENGTH bytes (AAP §0.6.5
        // byte-for-byte round-trip invariant). A mismatch here would
        // indicate a defect in the domain record's encode implementation
        // and would silently corrupt the data file if not caught.
        if (encoded.length != RECORD_LENGTH) {
            throw new IllegalStateException(
                    "CardRecord.encode() returned " + encoded.length
                            + " bytes; expected " + RECORD_LENGTH);
        }
        byte[] keyBytes = formatKey(record.cardNum());
        synchronized (writeLock) {
            try {
                writer.upsert(keyBytes, KEY_OFFSET, encoded);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Error upserting CARDDATA cardNumber="
                                + maskPan(record.cardNum()), e);
            }
        }
        LOG.debug("save cardNumber={} status=ok", maskPan(record.cardNum()));
    }

    /**
     * Deletes the record with the supplied {@code CARD-NUM} primary key.
     * Throws {@link NoSuchElementException} if no such record exists
     * &mdash; this mirrors the COBOL {@code DELETE} with
     * {@code INVALID KEY} branch (NOTFND is not a silent no-op).
     *
     * <p>No active COBOL program issues {@code EXEC CICS DELETE} against
     * CARDDATA at runtime; this operation is provided for adapter
     * completeness and to support the IDCAMS
     * {@code DELETE} / {@code DEFINE} cycle exercised by
     * {@code app/jcl/CARDFILE.jcl} during dataset (re-)load.
     *
     * <p>The delete is wrapped in the repository-level
     * {@link #writeLock} so that the validate-encode-and-delete
     * critical section is atomic with respect to other concurrent
     * {@code save} or {@code delete} calls on this instance. The
     * underlying {@link FixedWidthWriter#deleteByKey(byte[], int)}
     * provides crash-safety via atomic temp-file + rename.
     *
     * <p>A debug log line is emitted on success; the card number is
     * always masked via {@link #maskPan(String)} per AAP &sect;0.7.2.
     *
     * @param cardNumber the 16-character {@code CARD-NUM} primary key
     * @throws NullPointerException     if {@code cardNumber} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code cardNumber} is not
     *                                  exactly {@value #KEY_LENGTH}
     *                                  characters
     * @throws NoSuchElementException   if no record exists with that key
     * @throws UncheckedIOException     if the underlying delete fails
     */
    @Override
    public void delete(String cardNumber) {
        Objects.requireNonNull(cardNumber, "cardNumber");
        validateCardNumber(cardNumber);
        byte[] keyBytes = formatKey(cardNumber);
        synchronized (writeLock) {
            try {
                boolean removed = writer.deleteByKey(keyBytes, KEY_OFFSET);
                if (!removed) {
                    throw new NoSuchElementException(
                            "No CARDDATA record found for cardNumber="
                                    + maskPan(cardNumber));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Error deleting CARDDATA cardNumber="
                                + maskPan(cardNumber), e);
            }
        }
        LOG.debug("delete cardNumber={} status=ok", maskPan(cardNumber));
    }

    // ------------------------------------------------------------------
    // AutoCloseable — required by CardRepository
    // ------------------------------------------------------------------

    /**
     * Releases any resources held by this repository. This adapter's
     * underlying {@link FixedWidthReader} and {@link FixedWidthWriter}
     * open a fresh {@link java.nio.channels.SeekableByteChannel} per
     * operation and close it deterministically (via try-with-resources
     * in the foundational primitives, or via {@code Stream.onClose} hook
     * for streaming methods); no long-lived resources are held by this
     * adapter, so {@code close()} is a no-op.
     *
     * <p>Idempotent: callers may defensively invoke this method multiple
     * times without observable side effects, consistent with the
     * {@link CardRepository#close()} contract.
     */
    @Override
    public void close() {
        // No long-lived resources to release.
        // FixedWidthReader and FixedWidthWriter manage their own channel
        // lifecycle per operation; nothing to close at the repository
        // level. Method present to satisfy the AutoCloseable / port
        // contract and to allow future adapter implementations to add
        // resource cleanup without changing the call sites.
        LOG.debug("FileCardRepository closed (no-op): dataFile={}", dataFile);
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Returns the masked form of a card number (PAN) suitable for
     * logging. Shows only the last 4 characters; all earlier characters
     * are replaced with asterisks.
     *
     * <p>Per AAP &sect;0.7.2: <em>"No card PAN logged in full; mask all
     * but last 4 digits in logs and error messages."</em> Every log
     * statement and every exception message in this class that mentions
     * a card number variable routes the value through this helper. The
     * method is package-private (not {@code private}) so that
     * companion-package unit tests can verify the masking behaviour
     * without reflection.
     *
     * <p>Sentinels are used for abnormal inputs so that even error
     * paths cannot leak a partial PAN:
     * <ul>
     *   <li>{@code null} or {@link String#isBlank() blank}: returns
     *       {@code "[empty]"}</li>
     *   <li>shorter than 4 characters: returns {@code "[truncated]"}
     *       (avoids exposing 1&ndash;3 trailing characters of what
     *       might still be a sensitive identifier)</li>
     *   <li>otherwise: returns the value with all but the trailing 4
     *       characters replaced by {@code '*'} (e.g., a 16-digit
     *       {@code "4111111111111234"} becomes
     *       {@code "************1234"}).</li>
     * </ul>
     *
     * @param pan the card number; may be {@code null}, blank, or
     *            shorter than 4 characters
     * @return the masked card number; never {@code null} and never the
     *         original PAN in full
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

    /**
     * Validates that the supplied {@code cardNumber} is exactly
     * {@value #KEY_LENGTH} characters &mdash; the fixed width of the
     * COBOL {@code CARD-NUM PIC X(16)} field. Per AAP Phase 12 Key
     * Insight #4 ("16-byte exact length"), strict length validation is
     * mandatory; this is a fixed-width string, NOT a variable-length
     * key, and a shorter or longer value would corrupt the byte-level
     * key buffer that the reader and writer expect.
     *
     * <p>Note: this method assumes {@code cardNumber} is non-null;
     * callers MUST invoke {@link Objects#requireNonNull(Object, String)
     * Objects.requireNonNull} on the parameter beforehand.
     *
     * @param cardNumber the candidate card number (must not be
     *                   {@code null})
     * @throws IllegalArgumentException if the length is not exactly
     *                                  {@value #KEY_LENGTH} characters
     */
    private static void validateCardNumber(String cardNumber) {
        if (cardNumber.length() != KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "cardNumber must be exactly " + KEY_LENGTH + " characters; got "
                            + cardNumber.length());
        }
    }

    /**
     * Encodes a 16-character {@code CARD-NUM} into the 16-byte key
     * buffer that the {@link FixedWidthReader#findByKey} and
     * {@link FixedWidthWriter#upsert} primitives expect. Uses the
     * configured {@link #charset} (typically {@code IBM-1047} EBCDIC,
     * but configurable per-file via {@code application.properties}).
     *
     * <p>Includes a defensive byte-length check: ASCII and IBM-1047
     * both encode each digit character in exactly one byte, so the
     * resulting buffer is exactly {@value #KEY_LENGTH} bytes. If a
     * caller misconfigures the adapter with a multi-byte charset
     * (e.g., UTF-16), the encoded length will not match and this
     * helper raises an explicit {@link IllegalArgumentException}
     * rather than silently producing a malformed key.
     *
     * <p>Note: the caller is responsible for validating that
     * {@code cardNumber} is exactly {@value #KEY_LENGTH} characters
     * (via {@link #validateCardNumber(String)}); this helper performs
     * only the post-encoding byte-length sanity check.
     *
     * @param cardNumber the 16-character {@code CARD-NUM} key
     * @return a freshly allocated {@value #KEY_LENGTH}-byte key buffer
     * @throws IllegalArgumentException if the encoded byte length is
     *                                  not exactly {@value #KEY_LENGTH}
     */
    private byte[] formatKey(String cardNumber) {
        // CARD-NUM is PIC X(16) — already 16 chars; encode using the
        // configured charset (IBM-1047 / US-ASCII / etc.).
        byte[] bytes = cardNumber.getBytes(charset);
        if (bytes.length != KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "cardNumber encoded to " + bytes.length
                            + " bytes with charset " + charset.name()
                            + "; expected " + KEY_LENGTH);
        }
        return bytes;
    }
}
