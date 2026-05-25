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

// Internal domain imports (depends_on_files whitelist per AAP §0.5.1):
//   CobolProgram          — Javadoc-style traceability annotation tying this class
//                           to its COBOL source artefact (TRANSACT VSAM KSDS).
//   TransactionRepository — the port interface this adapter implements; six
//                           methods + AutoCloseable.close() override.
//   TranRecord            — the 350-byte domain record this adapter persists;
//                           provides parse(byte[]) / encode() / tranId() /
//                           tranCardNum() / tranAmt() and the static layout
//                           constants (RECORD_LENGTH=350, TRAN_ID_OFFSET=0,
//                           TRAN_ID_LENGTH=16, TRAN_CARD_NUM_OFFSET=262,
//                           TRAN_CARD_NUM_LENGTH=16).
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.record.TranRecord;

// SLF4J facade (slf4j-api 2.0.16 per parent POM dependencyManagement per AAP
// §0.5.1). Used for DEBUG-level logging after successful save() operations
// documenting the tranId, MASKED PAN (per AAP §0.7.2 PCI compliance — never
// log the full PAN), and amount. The concrete logging backend (logback-classic
// 1.5.12) is supplied at runtime by the composition root in carddemo-app,
// keeping this adapter free of binding to any specific backend per AAP §0.6.12.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// AAP §0.6.5 binding constraint: NO java.io.File anywhere in this module.
// IOException is the canonical checked exception surface for file I/O thrown
// by FixedWidthReader / FixedWidthWriter. UncheckedIOException wraps caught
// IOException with contextual messages before propagating up to callers, since
// the TransactionRepository port methods do not declare checked exceptions
// (the port spec mandates unchecked surfacing of underlying I/O failures).
import java.io.IOException;
import java.io.UncheckedIOException;

// Charset is held as an injected final field. Used to encode String keys to
// bytes (tranId.getBytes(charset), cardNumber.getBytes(charset)) for byte-
// level key comparison and lookup, and is forwarded to the FixedWidthReader /
// FixedWidthWriter constructors for consistent codepage configuration. The
// default codepage is IBM-1047 (EBCDIC) per AAP §0.6.5; per-file overrides
// flow in via the application.properties carddemo.file.transact.charset key
// resolved at the composition root.
import java.nio.charset.Charset;

// Path identifies the TRANSACT data file location. Held as a final field and
// passed to the FixedWidthReader / FixedWidthWriter constructors. The actual
// file open via Files.newByteChannel(...) is performed inside those reader /
// writer instances per their internal contracts; this class only holds Path
// as a value type per AAP §0.6.5.
import java.nio.file.Path;

// java.util utilities. Objects.requireNonNull(...) provides eager null
// validation in the constructor and on every public method per the AAP §0.6.5
// preserve-as-is mandate. Optional<TranRecord> is the return type of findById
// (matching the TransactionRepository port contract — empty Optional ↔ COBOL
// DFHRESP(NOTFND)) and findHighestId (empty ↔ COBOL DFHRESP(ENDFILE) +
// MOVE ZEROS TO TRAN-ID fallback per app/cbl/COTRN02C.cbl:L689).
import java.util.Objects;
import java.util.Optional;

// Stream<TranRecord> is the lazy, closeable return type for the three browse
// methods (streamSequential, streamFrom, streamByCardNumber) matching the
// TransactionRepository port contract. Used by findHighestId in a try-with-
// resources block calling stream.reduce((a, b) -> b) to retrieve the last
// record (highest TRAN-ID due to KSDS ascending key order), faithfully
// translating the COBOL STARTBR + READPREV idiom of app/cbl/COTRN02C.cbl:
// L644-L697. streamByCardNumber chains a byte-level filter on offset 262
// (AIX_CARDNUM_OFFSET) before mapping bytes to TranRecord via TranRecord::parse.
import java.util.stream.Stream;

/**
 * File-backed implementation of {@link TransactionRepository} for the
 * {@code TRANSACT} VSAM KSDS dataset (350-byte fixed-width records, 16-byte
 * {@code TRAN-ID} primary key, alternate index on {@code TRAN-CARD-NUM} at
 * offset 262), copybook {@code app/cpy/CVTRA05Y.cpy}. The adapter composes
 * a {@link FixedWidthReader} for read paths and a {@link FixedWidthWriter}
 * for write paths, both targeting the same data file so that AIX consistency
 * is maintained by single-buffer upsert (both primary key {@code TRAN-ID} and
 * AIX key {@code TRAN-CARD-NUM} live inside the same 350-byte buffer).
 *
 * <h2>COBOL source provenance</h2>
 * Implements the access path for the {@code TRAN-RECORD} 01-level group
 * defined at {@code app/cpy/CVTRA05Y.cpy}, consumed by the following COBOL
 * programs:
 * <ul>
 *   <li><b>CBTRN02C</b> &mdash; posting engine, {@code WRITE FD-TRANFILE-REC
 *       FROM TRAN-RECORD} at {@code app/cbl/CBTRN02C.cbl:L564} (paragraph
 *       {@code 2900-WRITE-TRANSACTION-FILE}). Maps to {@link #save(TranRecord)}.</li>
 *   <li><b>CBTRN03C</b> &mdash; paginated detail report writer, sequential
 *       {@code READ TRANSACT-FILE INTO TRAN-RECORD} drives the report main
 *       loop. Maps to {@link #streamSequential()}.</li>
 *   <li><b>COTRN00C</b> &mdash; online transaction-list browse,
 *       {@code STARTBR}/{@code READNEXT} forward pagination cursor. Maps
 *       to {@link #streamFrom(String)}.</li>
 *   <li><b>COTRN01C</b> &mdash; online transaction-view (random read by key),
 *       {@code EXEC CICS READ DATASET(WS-TRANSACT-FILE) ... RIDFLD(TRAN-ID)}
 *       at {@code app/cbl/COTRN01C.cbl:L269}. Maps to {@link #findById(String)}.</li>
 *   <li><b>COTRN02C</b> &mdash; online transaction-add, uses
 *       {@code STARTBR} + {@code READPREV} idiom at
 *       {@code app/cbl/COTRN02C.cbl:L644-L697} to retrieve the
 *       lexicographically largest existing {@code TRAN-ID}; falls back to
 *       {@code MOVE ZEROS TO TRAN-ID} at {@code app/cbl/COTRN02C.cbl:L689}
 *       when the file is empty. Maps to {@link #findHighestId()} +
 *       {@link #save(TranRecord)} for the subsequent
 *       {@code WRITE-TRANSACT-FILE} at
 *       {@code app/cbl/COTRN02C.cbl:L713-L721}.</li>
 *   <li><b>COBIL00C</b> &mdash; online bill-payment, writes the bill-payment
 *       transaction record via {@code EXEC CICS WRITE DATASET(WS-TRANSACT-FILE)}
 *       at {@code app/cbl/COBIL00C.cbl:L512-L520}. Maps to
 *       {@link #save(TranRecord)}.</li>
 * </ul>
 *
 * <h2>Record layout</h2>
 * Per {@code app/cpy/CVTRA05Y.cpy} (cumulative offsets verified against the
 * source copybook and {@link TranRecord}'s static layout constants):
 * <pre>{@code
 * offset  length  field
 *      0      16  TRAN-ID            PIC X(16)   (primary key)
 *     16       2  TRAN-TYPE-CD       PIC X(02)
 *     18       4  TRAN-CAT-CD        PIC 9(04)
 *     22      10  TRAN-SOURCE        PIC X(10)
 *     32     100  TRAN-DESC          PIC X(100)
 *    132      11  TRAN-AMT           PIC S9(09)V99 (zoned-decimal)
 *    143       9  TRAN-MERCHANT-ID   PIC 9(09)
 *    152      50  TRAN-MERCHANT-NAME PIC X(50)
 *    202      50  TRAN-MERCHANT-CITY PIC X(50)
 *    252      10  TRAN-MERCHANT-ZIP  PIC X(10)
 *    262      16  TRAN-CARD-NUM      PIC X(16)   (AIX key)
 *    278      26  TRAN-ORIG-TS       PIC X(26)
 *    304      26  TRAN-PROC-TS       PIC X(26)
 *    330      20  FILLER             PIC X(20)
 * Total: 350 bytes.
 * }</pre>
 *
 * <h2>AIX consistency on writes</h2>
 * Both the primary key ({@code TRAN-ID} at offset 0) and the AIX key
 * ({@code TRAN-CARD-NUM} at offset 262) live inside the same 350-byte
 * record buffer. Each {@link #save(TranRecord)} call performs a single
 * {@link FixedWidthWriter#upsert(byte[], int, byte[])} that writes the entire
 * buffer at once, so the AIX is implicitly consistent with the primary
 * index. There is no separate AIX file to keep in lockstep; the
 * {@link #streamByCardNumber(String)} method scans the primary file with a
 * byte-level filter on offset 262 to satisfy the AIX query contract.
 *
 * <h2>{@code findHighestId} implementation</h2>
 * Translates the COBOL {@code STARTBR} + {@code READPREV} idiom at
 * {@code app/cbl/COTRN02C.cbl:L644-L697}: open a browse cursor, read
 * backward from the cursor, and the first successful READPREV yields the
 * record with the lexicographically largest existing {@code TRAN-ID}. The
 * Java translation streams sequentially over the file and retains only the
 * last record via {@code stream.reduce((a, b) -> b)}; because VSAM KSDS
 * physically stores records in ascending primary-key order (and the file
 * adapter preserves that order via {@link FixedWidthWriter#upsert} key-sorted
 * insertion), the last record is the lexicographically largest. When the
 * file is empty, an empty {@link Optional} is returned &mdash; mirroring the
 * COBOL {@code DFHRESP(ENDFILE)} fallback at
 * {@code app/cbl/COTRN02C.cbl:L689} ({@code MOVE ZEROS TO TRAN-ID}).
 *
 * <h3>Why {@code reduce} and not {@code max(Comparator)}</h3>
 * Both are functionally equivalent for a sorted KSDS file, but
 * {@code reduce((a, b) -> b)} is the more direct translation of the COBOL
 * STARTBR + READPREV pattern: it relies on the file's physical ordering
 * (the same ordering CICS READPREV walks). A {@code max(Comparator)}
 * variant would impose an additional O(N) comparison cost that is
 * unnecessary given the KSDS ordering guarantee.
 *
 * <h2>{@code streamByCardNumber} implementation (AIX scan)</h2>
 * Performs a sequential scan over the primary file with a byte-level
 * filter on the 16-byte field at offset {@link TranRecord#TRAN_CARD_NUM_OFFSET
 * 262}. The cardNumber argument is encoded once via
 * {@code cardNumber.getBytes(charset)} and compared byte-for-byte against
 * each record's AIX slot via {@link #keyMatches(byte[], int, byte[])}.
 * Filtering at the byte level avoids the cost of parsing every record
 * (which would invoke {@link com.blitzy.carddemo.domain.util.Decimals#parseZonedDecimal},
 * timestamp parsers, and field validation on records that will be
 * immediately discarded) and matches the access cost of a COBOL CICS
 * STARTBR on the AIX-PATH dataset.
 *
 * <h2>No delete method</h2>
 * The {@link TransactionRepository} port does not declare a delete operation
 * because no COBOL program in the source tree issues {@code EXEC CICS DELETE}
 * or {@code DELETE FILE} against {@code TRANSACT} &mdash; the dataset is
 * append-only at the program level (the IDCAMS {@code DELETE CLUSTER} in
 * {@code TRANFILE.jcl} operates at the dataset level, not the record level,
 * and is reconstructed in {@code DefineTransactionFileApp} rather than via
 * this port). Per AAP &sect;0.7.1 Preserve-As-Is mandate, exposing a
 * record-level delete that the COBOL system does not require would
 * constitute a behavior change beyond migration scope.
 *
 * <h2>PCI compliance (AAP &sect;0.7.2)</h2>
 * {@code TRAN-CARD-NUM} is PAN (Primary Account Number) data. This adapter
 * NEVER logs the full PAN; the {@link #maskPan(String)} helper masks all
 * but the last four characters of the card number for log output (e.g.,
 * {@code "************1234"}). The {@link #save(TranRecord)} DEBUG log line
 * uses {@code maskPan(record.tranCardNum())} so that error messages from
 * the {@link #streamByCardNumber(String)} method's
 * {@link UncheckedIOException} wrapper also use masked PAN.
 *
 * <h2>Concurrency</h2>
 * <ul>
 *   <li>Read paths ({@link #findById(String)}, {@link #findHighestId()},
 *       {@link #streamSequential()}, {@link #streamFrom(String)},
 *       {@link #streamByCardNumber(String)}) are thread-safe by virtue of
 *       {@link FixedWidthReader}'s per-call channel open/close pattern;
 *       multiple virtual threads may invoke them concurrently per AAP
 *       &sect;0.6.6.</li>
 *   <li>The {@link #save(TranRecord)} write path is serialised by an
 *       instance-private {@link #writeLock} monitor (in addition to
 *       {@link FixedWidthWriter}'s own internal write lock); this gives
 *       repository-level callers the same observable single-writer
 *       guarantee as the COBOL CICS {@code WRITE} pattern, even when
 *       callers fan out per-record posting work onto virtual threads.</li>
 * </ul>
 *
 * <h2>Architectural constraints</h2>
 * Binding per AAP &sect;0.6.5 and &sect;0.7.4:
 * <ul>
 *   <li>{@code java.nio.file} exclusively; {@code java.io.File} forbidden.</li>
 *   <li>{@link java.math.BigDecimal} via
 *       {@link com.blitzy.carddemo.domain.util.Decimals} for any monetary
 *       value (this adapter does not perform arithmetic directly but
 *       persists {@link TranRecord#tranAmt()} byte-for-byte via
 *       {@link TranRecord#encode()}).</li>
 *   <li>No Spring; no Hibernate; no Lombok; no Apache Commons.</li>
 *   <li>No {@code double} or {@code float}; no {@link ThreadLocal}; no
 *       preview features; no reflection.</li>
 * </ul>
 *
 * @see TransactionRepository
 * @see TranRecord
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @since 1.0.0
 */
@CobolProgram(
        value = "TRANSACT",
        sourcePath = "app/cpy/CVTRA05Y.cpy",
        translationDate = "2025-10-24",
        notes = "File-backed adapter for the TRANSACT VSAM KSDS + AIX (350-byte TRAN-RECORD). "
                + "Primary key TRAN-ID (PIC X(16) at offset 0); AIX key TRAN-CARD-NUM "
                + "(PIC X(16) at offset 262). AIX consistency is maintained on writes via "
                + "single-buffer upsert (both keys live inside the same 350-byte buffer). "
                + "findHighestId translates the COBOL STARTBR + READPREV pattern at "
                + "app/cbl/COTRN02C.cbl:L644-L697 via stream.reduce((a, b) -> b) over the "
                + "KSDS-ordered sequential scan. streamByCardNumber implements the AIX-PATH "
                + "access via byte-level filter at offset 262 to avoid parsing discarded "
                + "records. PAN is MASKED in logs per AAP §0.7.2 (TRAN-CARD-NUM is PCI data). "
                + "No delete method per the port spec — no COBOL program issues EXEC CICS "
                + "DELETE against TRANSACT (dataset is append-only at the program level)."
)
public final class FileTransactionRepository implements TransactionRepository {

    /**
     * SLF4J logger for this adapter. Used for DEBUG-level lines after
     * successful {@link #save(TranRecord)} operations documenting the
     * {@code tranId}, MASKED PAN (per AAP &sect;0.7.2), and amount, and
     * for a single DEBUG line on construction documenting the resolved
     * {@link Path} and {@link Charset}.
     */
    private static final Logger LOG = LoggerFactory.getLogger(FileTransactionRepository.class);

    /**
     * The configuration key used by {@link EbcdicTranscoder} to look up
     * the per-file codepage override for this dataset, via
     * {@code carddemo.file.transact.charset} in
     * {@code application.properties}. Resolved at the composition root
     * (carddemo-app) and forwarded to the canonical
     * {@link #FileTransactionRepository(Path, Charset)} constructor.
     */
    public static final String DATASET_KEY = "transact";

    /**
     * Total fixed-width record length in bytes. Mirrors
     * {@link TranRecord#RECORD_LENGTH} = 350, derived from the cumulative
     * offsets in {@code app/cpy/CVTRA05Y.cpy}. Repeated locally as a
     * {@code public static final} for callers that need the constant
     * without traversing the {@link TranRecord} dependency.
     */
    public static final int RECORD_LENGTH = TranRecord.RECORD_LENGTH;

    /**
     * Byte offset of the {@code TRAN-ID} primary key within each record.
     * Mirrors {@link TranRecord#TRAN_ID_OFFSET} = 0.
     */
    public static final int PK_OFFSET = TranRecord.TRAN_ID_OFFSET;

    /**
     * Byte length of the {@code TRAN-ID} primary key. Mirrors
     * {@link TranRecord#TRAN_ID_LENGTH} = 16.
     */
    public static final int PK_LENGTH = TranRecord.TRAN_ID_LENGTH;

    /**
     * Byte offset of the {@code TRAN-CARD-NUM} alternate-index key within
     * each record. Mirrors {@link TranRecord#TRAN_CARD_NUM_OFFSET} = 262.
     * Derived from cumulative copybook offsets:
     * 16 (TRAN-ID) + 2 (TYPE-CD) + 4 (CAT-CD) + 10 (SOURCE) + 100 (DESC)
     * + 11 (AMT zoned-decimal) + 9 (MERCHANT-ID) + 50 (NAME) + 50 (CITY)
     * + 10 (ZIP) = 262.
     */
    public static final int AIX_CARDNUM_OFFSET = TranRecord.TRAN_CARD_NUM_OFFSET;

    /**
     * Byte length of the {@code TRAN-CARD-NUM} alternate-index key.
     * Mirrors {@link TranRecord#TRAN_CARD_NUM_LENGTH} = 16.
     */
    public static final int AIX_CARDNUM_LENGTH = TranRecord.TRAN_CARD_NUM_LENGTH;

    /**
     * Number of trailing PAN characters preserved by {@link #maskPan(String)};
     * the leading characters are replaced with {@code '*'}. Matches the
     * convention used by {@link TranRecord#maskedPan()} for log output per
     * AAP &sect;0.7.2.
     */
    private static final int PAN_VISIBLE_TAIL = 4;

    /**
     * The TRANSACT data file. Held as a {@link Path} per AAP &sect;0.6.5
     * (no {@link java.io.File}). The file need not exist at construction
     * time; read methods handle missing files as NOTFND parity
     * ({@link Optional#empty()} / {@link Stream#empty()}), and the first
     * {@link #save(TranRecord)} call creates it if absent.
     */
    private final Path file;

    /**
     * Configured {@link Charset} for byte-level key encoding and for
     * propagation to the underlying {@link FixedWidthReader} /
     * {@link FixedWidthWriter}. The default codepage is IBM-1047 (EBCDIC)
     * per AAP &sect;0.6.5; per-file overrides are configurable via the
     * {@code carddemo.file.transact.charset} property in
     * {@code application.properties}. Used to encode String keys via
     * {@code tranId.getBytes(charset)} and {@code cardNumber.getBytes(charset)}
     * for byte-level lookup, matching the file's actual storage encoding.
     */
    private final Charset charset;

    /**
     * Foundational fixed-width reader, used for all three TRANSACT read
     * modes:
     * <ul>
     *   <li>{@link FixedWidthReader#findByKey(byte[], int) reader.findByKey}
     *       at {@link #PK_OFFSET}=0 for random {@code TRAN-ID} lookup in
     *       {@link #findById(String)}, mirroring COBOL
     *       {@code READ TRANSACT-FILE KEY IS TRAN-ID}.</li>
     *   <li>{@link FixedWidthReader#streamSequential() reader.streamSequential}
     *       for {@link #streamSequential()}, {@link #findHighestId()}, and
     *       the byte-level filtered scan used by {@link #streamByCardNumber(String)}.</li>
     *   <li>{@link FixedWidthReader#streamFromKey(byte[], int) reader.streamFromKey}
     *       at {@link #PK_OFFSET}=0 for the GTEQ browse used by
     *       {@link #streamFrom(String)}, mirroring COBOL
     *       {@code STARTBR ... GTEQ} + {@code READNEXT}.</li>
     * </ul>
     */
    private final FixedWidthReader reader;

    /**
     * Foundational fixed-width writer, used by {@link #save(TranRecord)}
     * to perform atomic upsert via
     * {@link FixedWidthWriter#upsert(byte[], int, byte[]) writer.upsert}
     * at {@link #PK_OFFSET}=0. The 350-byte buffer carries both the
     * primary key (TRAN-ID at offset 0) and the AIX key (TRAN-CARD-NUM at
     * offset 262), so a single upsert maintains both index views
     * automatically.
     */
    private final FixedWidthWriter writer;

    /**
     * Repository-level write lock. Serialises {@link #save(TranRecord)}
     * across in-process callers (including virtual-thread fan-out per
     * AAP &sect;0.6.6) to give callers a single-writer observable
     * guarantee even though {@link FixedWidthWriter} already provides its
     * own per-writer-instance synchronisation. Held only across the
     * smallest possible critical section (a single
     * {@link FixedWidthWriter#upsert(byte[], int, byte[])} invocation);
     * not re-entrant; not exposed via a public accessor.
     */
    private final Object writeLock = new Object();

    /**
     * Convenience constructor delegating to the canonical
     * {@link #FileTransactionRepository(Path, Charset)} constructor with
     * {@code IBM-1047} (EBCDIC) as the default codepage per AAP
     * &sect;0.6.5 ("The default codepage for EBCDIC-to-ASCII transcoding
     * is {@code Charset.forName(\"IBM-1047\")}"). Provided to preserve
     * source-code compatibility with the established codebase convention
     * used by all sibling {@code File*Repository} classes and by the
     * composition-root {@code PostTransactionsApp},
     * {@code CreateStatementsApp}, and {@code DefineTransactionFileApp}
     * main classes, which construct repositories with a single
     * {@link Path} argument and rely on the mandated EBCDIC default.
     *
     * <p>For an ASCII-encoded input file (e.g., the ASCII fixtures
     * shipped under {@code app/data/ASCII/}), use the canonical two-arg
     * constructor and pass {@code Charset.forName("US-ASCII")} explicitly
     * &mdash; or set the {@code carddemo.file.transact.charset} property
     * at the composition root, which the calling code then resolves and
     * forwards to the two-arg form.
     *
     * @param file absolute filesystem path of the TRANSACT data file;
     *             must not be {@code null}. The file need NOT exist at
     *             construction time; read methods handle missing files
     *             as NOTFND parity ({@link Optional#empty()} or empty
     *             streams), and write methods create the file on first
     *             call if absent.
     * @throws NullPointerException if {@code file} is {@code null}
     */
    public FileTransactionRepository(Path file) {
        this(file, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Canonical constructor. Validates arguments eagerly and pre-builds
     * the {@link FixedWidthReader} and {@link FixedWidthWriter}
     * collaborators so subsequent read/write calls do not pay
     * construction cost.
     *
     * <p>Neither the reader nor the writer touches the file at
     * construction time; both defer file access to their first
     * read/write invocation. This permits the adapter to be wired into
     * a composition root before the TRANSACT data file exists (e.g.,
     * for the initial-load JCL flow {@code TRANFILE.jcl}, which
     * deletes the cluster, defines the cluster, and then bulk-loads via
     * {@link #save(TranRecord)}-equivalent IDCAMS REPRO).
     *
     * @param file    absolute filesystem path of the TRANSACT data file;
     *                must not be {@code null}
     * @param charset character set used for byte-level key encoding;
     *                defaults to IBM-1047 per AAP &sect;0.6.5;
     *                configurable via
     *                {@code carddemo.file.transact.charset} in
     *                {@code application.properties}
     * @throws NullPointerException if either argument is {@code null}
     */
    public FileTransactionRepository(Path file, Charset charset) {
        this.file = Objects.requireNonNull(file, "file");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(file, RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(file, RECORD_LENGTH, charset);
        LOG.debug("FileTransactionRepository configured: file={}, charset={}, recordLength={}",
                file, charset, RECORD_LENGTH);
    }

    /**
     * Returns the configured TRANSACT data file path. Provided for
     * diagnostic and test purposes; not part of the
     * {@link TransactionRepository} port contract.
     *
     * @return the TRANSACT data file {@link Path}; never {@code null}
     */
    public Path file() {
        return file;
    }

    /**
     * Returns the configured {@link Charset}. Provided for diagnostic
     * and test purposes; not part of the {@link TransactionRepository}
     * port contract.
     *
     * @return the configured {@link Charset}; never {@code null}
     */
    public Charset charset() {
        return charset;
    }

    // ---------------------------------------------------------------------
    // findById — random read by 16-character TRAN-ID primary key.
    // Translates COBOL: EXEC CICS READ DATASET(WS-TRANSACT-FILE)
    //                   INTO(TRAN-RECORD) RIDFLD(TRAN-ID) UPDATE
    //                   RESP(WS-RESP-CD)
    // at app/cbl/COTRN01C.cbl:L269. NOTFND ↔ Optional.empty().
    // ---------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Implementation: encodes the 16-character {@code tranId} into a
     * 16-byte key via {@code tranId.getBytes(charset)}, delegates to
     * {@link FixedWidthReader#findByKey(byte[], int)} at offset
     * {@link #PK_OFFSET}=0, and maps the matched byte buffer through
     * {@link TranRecord#parse(byte[])}. A missing file or no-match key
     * yields {@link Optional#empty()} (matching COBOL
     * {@code DFHRESP(NOTFND)} at {@code app/cbl/COTRN01C.cbl:L283-L288}).
     *
     * @throws NullPointerException     if {@code tranId} is {@code null}
     * @throws IllegalArgumentException if {@code tranId.length() != 16}
     *                                  (the {@code PIC X(16)} field width)
     * @throws UncheckedIOException     on underlying file I/O failure
     */
    @Override
    public Optional<TranRecord> findById(String tranId) {
        Objects.requireNonNull(tranId, "tranId");
        if (tranId.length() != PK_LENGTH) {
            throw new IllegalArgumentException(
                    "tranId must be exactly " + PK_LENGTH + " chars (PIC X(16)); got length "
                            + tranId.length() + " for value '" + tranId + "'");
        }
        byte[] keyBytes = tranId.getBytes(charset);
        try {
            return reader.findByKey(keyBytes, PK_OFFSET).map(TranRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error reading TRANSACT for tranId=" + tranId + " from " + file, e);
        }
    }

    // ---------------------------------------------------------------------
    // findHighestId — STARTBR + READPREV idiom for next-ID generation.
    // Translates COBOL: STARTBR-TRANSACT-FILE / READPREV-TRANSACT-FILE /
    //                   ENDBR-TRANSACT-FILE  at app/cbl/COTRN02C.cbl:
    //                   L644-L697. Empty file ↔ Optional.empty() ↔ COBOL
    //                   MOVE ZEROS TO TRAN-ID at L689.
    // ---------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Implementation: opens a try-with-resources sequential stream
     * via {@link #streamSequential()} and consumes it with
     * {@code stream.reduce((a, b) -> b)}, which retains only the last
     * element. Because the underlying {@link FixedWidthWriter#upsert}
     * preserves VSAM KSDS unsigned byte-wise key-sorted physical order,
     * the last record in the file has the lexicographically largest
     * {@code TRAN-ID}, faithfully translating the observable effect of
     * the COBOL STARTBR + READPREV sequence at
     * {@code app/cbl/COTRN02C.cbl:L644-L697}.
     *
     * <p>The try-with-resources block guarantees the underlying file
     * channel is closed even if the reduce throws, matching the COBOL
     * {@code ENDBR-TRANSACT-FILE} verb at
     * {@code app/cbl/COTRN02C.cbl:L702}.
     *
     * @throws UncheckedIOException on underlying file I/O failure
     */
    @Override
    public Optional<TranRecord> findHighestId() {
        try (Stream<TranRecord> stream = streamSequential()) {
            // stream.reduce((a, b) -> b) keeps only the last element of
            // the stream (BinaryOperator that discards the accumulator
            // and returns the second argument). For a KSDS-ordered file
            // this is the record with the lexicographically largest
            // TRAN-ID. Equivalent to a no-arg variant: "return the last
            // element if present, otherwise empty."
            return stream.reduce((a, b) -> b);
        }
    }

    // ---------------------------------------------------------------------
    // streamSequential — full sequential scan in ascending TRAN-ID order.
    // Translates COBOL: READ TRANSACT-FILE INTO TRAN-RECORD loop at
    //                   app/cbl/CBTRN03C.cbl:L249. Missing/empty file
    //                   ↔ Stream.empty().
    // ---------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Implementation: delegates to
     * {@link FixedWidthReader#streamSequential()} and maps each
     * 350-byte buffer through {@link TranRecord#parse(byte[])}. The
     * returned stream is lazy and closeable; the caller MUST close it
     * via try-with-resources to release the underlying file channel.
     *
     * <p>Ordering is ascending lexicographic {@code TRAN-ID} (the VSAM
     * KSDS physical order), preserved by
     * {@link FixedWidthReader#streamSequential()} via the
     * {@link java.util.Spliterator#ORDERED} characteristic.
     *
     * @throws UncheckedIOException on underlying file I/O failure
     */
    @Override
    public Stream<TranRecord> streamSequential() {
        try {
            return reader.streamSequential().map(TranRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error opening TRANSACT for sequential read: " + file, e);
        }
    }

    // ---------------------------------------------------------------------
    // streamFrom — GTEQ browse starting at a specific TRAN-ID.
    // Translates COBOL: STARTBR ... RIDFLD(TRAN-ID) [GTEQ] / READNEXT
    //                   at app/cbl/COTRN00C.cbl:L593-L626. null/blank
    //                   startTranId ↔ top-of-file (full scan).
    // ---------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Implementation:
     * <ul>
     *   <li>{@code null} or blank {@code startTranId} &mdash; delegates
     *       to {@link #streamSequential()} (return the entire dataset
     *       from the start), matching the COBOL convention of
     *       {@code STARTBR} positioned at low-values.</li>
     *   <li>Otherwise &mdash; encodes the 16-character {@code startTranId}
     *       into a 16-byte key via {@code startTranId.getBytes(charset)}
     *       and delegates to
     *       {@link FixedWidthReader#streamFromKey(byte[], int)} at
     *       offset {@link #PK_OFFSET}=0. The reader filters the
     *       sequential scan via unsigned byte-wise key comparison,
     *       yielding all records with
     *       {@code TRAN-ID >= startTranId} in ascending order.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if {@code startTranId} is non-blank
     *                                  but not exactly 16 characters
     * @throws UncheckedIOException     on underlying file I/O failure
     */
    @Override
    public Stream<TranRecord> streamFrom(String startTranId) {
        if (startTranId == null || startTranId.isBlank()) {
            // COBOL STARTBR at low-values ↔ full sequential scan.
            return streamSequential();
        }
        if (startTranId.length() != PK_LENGTH) {
            throw new IllegalArgumentException(
                    "startTranId must be exactly " + PK_LENGTH
                            + " chars (PIC X(16)); got length " + startTranId.length()
                            + " for value '" + startTranId + "'");
        }
        byte[] startKey = startTranId.getBytes(charset);
        try {
            return reader.streamFromKey(startKey, PK_OFFSET).map(TranRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error browsing TRANSACT from startTranId=" + startTranId
                            + " over " + file, e);
        }
    }

    // ---------------------------------------------------------------------
    // streamByCardNumber — AIX scan on TRAN-CARD-NUM (offset 262).
    // Translates COBOL: EXEC CICS STARTBR DATASET(TRANFIL2)
    //                   RIDFLD(TRAN-CARD-NUM) on the AIX-PATH.
    // ---------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Implementation: scans the primary file sequentially with a
     * byte-level filter at {@link #AIX_CARDNUM_OFFSET}=262. The
     * {@code cardNumber} argument is encoded once via
     * {@code cardNumber.getBytes(charset)} and compared byte-for-byte
     * against each record's 16-byte AIX slot via
     * {@link #keyMatches(byte[], int, byte[])}. Records whose AIX slot
     * does not match are discarded before parsing, avoiding the cost
     * of constructing a {@link TranRecord} for records that will be
     * immediately filtered out.
     *
     * <p>Output order is the file's physical (primary-key) order
     * &mdash; ascending {@code TRAN-ID}. This matches the COBOL CICS
     * AIX-PATH access where multiple records sharing the same card
     * number are returned in primary-key order via {@code READNEXT}
     * over the AIX-PATH cursor.
     *
     * <p>The full PAN is NEVER logged; the
     * {@link UncheckedIOException} wrapper around any underlying I/O
     * failure uses {@link #maskPan(String)} to mask the card number in
     * the error message per AAP &sect;0.7.2.
     *
     * @throws NullPointerException     if {@code cardNumber} is {@code null}
     * @throws IllegalArgumentException if {@code cardNumber.length() != 16}
     *                                  (the {@code PIC X(16)} field width)
     * @throws UncheckedIOException     on underlying file I/O failure
     */
    @Override
    public Stream<TranRecord> streamByCardNumber(String cardNumber) {
        Objects.requireNonNull(cardNumber, "cardNumber");
        if (cardNumber.length() != AIX_CARDNUM_LENGTH) {
            throw new IllegalArgumentException(
                    "cardNumber must be exactly " + AIX_CARDNUM_LENGTH
                            + " chars (PIC X(16)); got length " + cardNumber.length());
        }
        // Encode once outside the filter lambda — the resulting byte[]
        // is captured as an effectively-final variable and reused for
        // every record's keyMatches comparison.
        final byte[] aixKey = cardNumber.getBytes(charset);
        try {
            return reader.streamSequential()
                    // Byte-level filter at offset 262: discard records
                    // whose AIX slot does NOT match aixKey BEFORE
                    // parsing. This avoids constructing a TranRecord
                    // (which invokes Decimals.parseZonedDecimal,
                    // LocalDateTime parsing, and field validation) for
                    // records that will be immediately discarded.
                    .filter(buffer -> keyMatches(buffer, AIX_CARDNUM_OFFSET, aixKey))
                    // Parse surviving buffers into TranRecord instances.
                    .map(TranRecord::parse);
        } catch (IOException e) {
            // PAN is masked in the error message per AAP §0.7.2.
            throw new UncheckedIOException(
                    "Error scanning TRANSACT for cardNumber=" + maskPan(cardNumber)
                            + " over " + file, e);
        }
    }

    // ---------------------------------------------------------------------
    // save — upsert; preserves AIX consistency via single-buffer write.
    // Translates COBOL: WRITE FD-TRANFILE-REC FROM TRAN-RECORD
    //                   (CBTRN02C, app/cbl/CBTRN02C.cbl:L564) and
    //                   EXEC CICS WRITE DATASET(WS-TRANSACT-FILE)
    //                   FROM(TRAN-RECORD) RIDFLD(TRAN-ID)
    //                   (COTRN02C, app/cbl/COTRN02C.cbl:L713-L721;
    //                    COBIL00C, app/cbl/COBIL00C.cbl:L512-L520).
    // ---------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Implementation: encodes the supplied {@link TranRecord} to its
     * 350-byte representation via {@link TranRecord#encode()}, computes
     * the 16-byte primary key from {@link TranRecord#tranId()} via
     * {@code tranId.getBytes(charset)}, and delegates to
     * {@link FixedWidthWriter#upsert(byte[], int, byte[])} at offset
     * {@link #PK_OFFSET}=0. The upsert is atomic per
     * {@link FixedWidthWriter#upsert} contract (full-file rewrite to a
     * sibling temp file then atomic move via
     * {@link java.nio.file.StandardCopyOption#ATOMIC_MOVE}); after any
     * crash the file is either entirely the old contents or entirely
     * the new contents.
     *
     * <p>AIX consistency is implicit: both the primary key
     * ({@code TRAN-ID} at offset 0) and the AIX key
     * ({@code TRAN-CARD-NUM} at offset 262) live inside the same
     * 350-byte buffer. The single {@link FixedWidthWriter#upsert} call
     * writes the entire buffer, so both index views are updated
     * atomically.
     *
     * <p>The operation is serialised by the instance-private
     * {@link #writeLock} monitor to give callers a single-writer
     * observable guarantee even when posting work is fanned out across
     * virtual threads per AAP &sect;0.6.6 (in addition to
     * {@link FixedWidthWriter}'s own internal write lock).
     *
     * <p>A successful {@link #save(TranRecord)} emits a DEBUG-level log
     * line documenting the {@code tranId}, MASKED PAN (per AAP
     * &sect;0.7.2), and amount.
     *
     * @throws NullPointerException  if {@code transaction} is {@code null}
     * @throws IllegalStateException if {@code transaction.encode()} returns
     *                               a byte array of unexpected length
     *                               (defensive invariant check)
     * @throws UncheckedIOException  on underlying file I/O failure
     */
    @Override
    public void save(TranRecord transaction) {
        Objects.requireNonNull(transaction, "transaction");
        // Encode the record to its 350-byte canonical byte image.
        // TranRecord.encode() is guaranteed to return exactly
        // RECORD_LENGTH bytes per the AAP §0.6.5 byte-fidelity
        // invariant; the defensive length check below surfaces any
        // future regression in TranRecord immediately rather than
        // silently corrupting the file.
        byte[] encoded = transaction.encode();
        if (encoded.length != RECORD_LENGTH) {
            throw new IllegalStateException(
                    "TranRecord.encode() returned " + encoded.length
                            + " bytes; expected " + RECORD_LENGTH
                            + " (record-fidelity invariant violated)");
        }
        // Compute the primary key bytes once outside the synchronized
        // block. The Charset is immutable; getBytes is thread-safe.
        final String tranId = transaction.tranId();
        final byte[] keyBytes = tranId.getBytes(charset);
        // Serialise the upsert under the repository-level writeLock so
        // that concurrent callers see a strict single-writer ordering
        // (matching the COBOL CICS WRITE semantic). FixedWidthWriter
        // also has its own internal writeLock; the two-level locking is
        // intentional for defense in depth and matches the established
        // sibling File*Repository pattern.
        synchronized (writeLock) {
            try {
                writer.upsert(keyBytes, PK_OFFSET, encoded);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Error upserting TRANSACT tranId=" + tranId + " to " + file, e);
            }
        }
        // PAN is masked in the DEBUG log per AAP §0.7.2.
        // tranAmt is scale-preserved BigDecimal (e.g., "1.20" never "1.2")
        // per AAP §0.1.3 — safe to print without further formatting.
        if (LOG.isDebugEnabled()) {
            LOG.debug("save tranId={} cardNumber={} amount={} file={} status=ok",
                    tranId, maskPan(transaction.tranCardNum()), transaction.tranAmt(), file);
        }
    }

    // ---------------------------------------------------------------------
    // close — release resources held by this repository.
    // ---------------------------------------------------------------------

    /**
     * Releases any resources held by this repository.
     *
     * <p>This adapter holds no persistent file channels (each public
     * method opens and closes its own channel via the underlying
     * {@link FixedWidthReader} / {@link FixedWidthWriter}), so this
     * implementation is a no-op DEBUG-log statement. Provided to
     * satisfy the {@link TransactionRepository} port's
     * {@link AutoCloseable} contract and to support symmetric usage
     * patterns (e.g., try-with-resources at the composition root).
     *
     * <p>Idempotent: safe to call multiple times. No-op on subsequent
     * invocations beyond the first DEBUG line.
     *
     * <p>Corresponds to the COBOL {@code CLOSE TRANSACT-FILE} verb at
     * the end of CBTRN02C and CBTRN03C
     * ({@code app/cbl/CBTRN03C.cbl:L516}) and to the CICS
     * {@code EXEC CICS ENDBR DATASET(WS-TRANSACT-FILE)} closures of
     * the COTRN00C / COTRN02C browse cursors at
     * {@code app/cbl/COTRN02C.cbl:L702}.
     */
    @Override
    public void close() {
        LOG.debug("FileTransactionRepository closed (no-op): file={}", file);
    }

    // ---------------------------------------------------------------------
    // Helper methods — package-private/static so they can be unit-tested
    // without introducing reflection.
    // ---------------------------------------------------------------------

    /**
     * Returns {@code true} iff the 16-byte slice of {@code buffer}
     * starting at {@code offset} equals {@code expectedKey} byte-for-byte.
     * Used by the {@link #streamByCardNumber(String)} AIX scan filter to
     * decide whether each record buffer matches the supplied card number
     * before parsing.
     *
     * <p>Byte-level comparison is charset-agnostic: the comparison
     * succeeds iff the encoded forms of the search key and the stored
     * key are byte-identical. Provided the caller encodes the search
     * key with the same {@link Charset} as the file's storage encoding
     * (which this adapter ensures by using the constructor-injected
     * {@link #charset}), the comparison is correct for both ASCII and
     * EBCDIC files.
     *
     * <p>Returns {@code false} (rather than throwing
     * {@link ArrayIndexOutOfBoundsException}) when the buffer is shorter
     * than {@code offset + expectedKey.length} bytes &mdash; defensive
     * against malformed input even though
     * {@link FixedWidthReader#streamSequential()} guarantees every
     * yielded buffer is exactly {@link #RECORD_LENGTH}=350 bytes.
     *
     * @param buffer      the 350-byte record buffer
     * @param offset      the byte offset of the key field within
     *                    {@code buffer}; non-negative
     * @param expectedKey the expected key bytes
     * @return {@code true} on exact byte-for-byte match
     */
    static boolean keyMatches(byte[] buffer, int offset, byte[] expectedKey) {
        if (buffer == null || expectedKey == null) {
            return false;
        }
        // Length guard prevents AIOOBE if a malformed buffer were passed.
        if (buffer.length < offset + expectedKey.length) {
            return false;
        }
        for (int i = 0; i < expectedKey.length; i++) {
            if (buffer[offset + i] != expectedKey[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Masks a PAN (Primary Account Number) to show only the last
     * {@value #PAN_VISIBLE_TAIL} characters per AAP &sect;0.7.2 PCI
     * compliance: the leading characters are replaced with {@code '*'}
     * and the trailing four characters are preserved (e.g.,
     * {@code "4111111111111234"} becomes {@code "************1234"}).
     *
     * <p>Behavior matrix:
     * <ul>
     *   <li>{@code null} &rarr; {@code "[null]"}</li>
     *   <li>empty or all-whitespace &rarr; {@code "[empty]"}</li>
     *   <li>length &lt; {@value #PAN_VISIBLE_TAIL} (post-trim)
     *       &rarr; {@code "[truncated]"} (cannot safely show last 4
     *       digits without revealing entire value)</li>
     *   <li>length == {@value #PAN_VISIBLE_TAIL} (post-trim) &rarr;
     *       all 4 characters preserved (no masking adds value)</li>
     *   <li>length &gt; {@value #PAN_VISIBLE_TAIL} (post-trim) &rarr;
     *       leading characters replaced with {@code '*'}; trailing
     *       4 characters preserved</li>
     * </ul>
     *
     * <p>The PAN is trimmed before masking so that {@code TRAN-CARD-NUM}
     * values padded to 16 characters with trailing spaces produce a
     * clean masked form without trailing space artifacts.
     *
     * @param pan the raw PAN string (may be {@code null} or blank)
     * @return a masked PAN suitable for log output
     */
    static String maskPan(String pan) {
        if (pan == null) {
            return "[null]";
        }
        String trimmed = pan.trim();
        if (trimmed.isEmpty()) {
            return "[empty]";
        }
        if (trimmed.length() < PAN_VISIBLE_TAIL) {
            // Cannot safely show last 4 chars without revealing the
            // entire short value; mask in full and tag as truncated.
            return "[truncated]";
        }
        if (trimmed.length() == PAN_VISIBLE_TAIL) {
            // Exactly 4 chars: masking would leave the value unchanged;
            // returning the trimmed form is the most informative option
            // without breaking the no-full-PAN rule (any value of
            // length 4 is not a complete PAN by PCI definition).
            return trimmed;
        }
        int maskLen = trimmed.length() - PAN_VISIBLE_TAIL;
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < maskLen; i++) {
            sb.append('*');
        }
        sb.append(trimmed, maskLen, trimmed.length());
        return sb.toString();
    }
}
