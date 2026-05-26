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
import com.blitzy.carddemo.domain.port.TransactionCategoryBalanceRepository;
import com.blitzy.carddemo.domain.record.TranCatBalRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed adapter implementation of
 * {@link TransactionCategoryBalanceRepository} that reads and writes
 * 50-byte fixed-width records keyed by a 17-byte composite key
 * ({@code TRANCAT-ACCT-ID PIC 9(11) + TRANCAT-TYPE-CD PIC X(02) +
 * TRANCAT-CD PIC 9(04)}) from the {@code TCATBALF} VSAM KSDS dataset.
 *
 * <h2>Authority and source lineage</h2>
 * <ul>
 *   <li><b>AAP &sect;0.1.1, &sect;0.3.1</b> &mdash; file-based batch
 *       processing by default (no PostgreSQL); this adapter is the
 *       default outer-ring repository implementation for
 *       {@code TCATBALF} per the hexagonal architecture diagram at
 *       AAP &sect;0.3.6.</li>
 *   <li><b>AAP &sect;0.6.5</b> &mdash; file I/O exactness. This
 *       adapter uses only {@link java.nio.file} primitives delegated
 *       through {@link FixedWidthReader} and {@link FixedWidthWriter};
 *       the {@code java.io.File} class is FORBIDDEN.</li>
 *   <li><b>AAP &sect;0.4.1</b> (transformation map) &mdash;
 *       {@code FileTransactionCategoryBalanceRepository} translates
 *       the COBOL VSAM KSDS access patterns demonstrated in:
 *       <ul>
 *         <li>{@code app/cpy/CVTRA01Y.cpy} &mdash; 50-byte
 *             {@code TRAN-CAT-BAL-RECORD} layout with 17-byte
 *             composite {@code TRAN-CAT-KEY} primary key at
 *             offset 0.</li>
 *         <li>{@code app/cbl/CBTRN02C.cbl} paragraph
 *             {@code 2700-UPDATE-TCATBAL}
 *             ({@code app/cbl/CBTRN02C.cbl:L467-L501}) &mdash; random
 *             read by 17-byte composite key, then dispatching to
 *             either the create branch
 *             ({@code 2700-A-CREATE-TCATBAL-REC} at
 *             {@code app/cbl/CBTRN02C.cbl:L503-L524}) or the update
 *             branch ({@code 2700-B-UPDATE-TCATBAL-REC} at
 *             {@code app/cbl/CBTRN02C.cbl:L526-L540}). The
 *             {@link #save(TranCatBalRecord)} method collapses both
 *             COBOL branches into a single upsert call.</li>
 *         <li>{@code app/cbl/CBACT04C.cbl} paragraph
 *             {@code 1000-TCATBALF-GET-NEXT} &mdash; sequential read
 *             over every {@code TCATBALF} record for the monthly
 *             interest computation. Maps to
 *             {@link #streamSequential()}.</li>
 *         <li>{@code app/data/ASCII/tcatbal.txt} &mdash; ASCII
 *             fixture used by the golden-record harness.</li>
 *       </ul></li>
 * </ul>
 *
 * <h2>Byte-for-byte fidelity</h2>
 * <p>This adapter delegates all field-level serialization to
 * {@link TranCatBalRecord#parse(byte[])} and
 * {@link TranCatBalRecord#encode()}, preserving the trailing 22-byte
 * FILLER verbatim. Re-encoding a parsed record MUST produce a
 * byte-identical buffer (AAP &sect;0.6.5 round-trip invariant). The
 * {@link #save(TranCatBalRecord)} method asserts the encoded length
 * equals {@link #RECORD_LENGTH} as a defense-in-depth check.
 *
 * <h2>Composite key encoding</h2>
 * <pre>{@code
 *   TRANCAT-ACCT-ID  11 bytes  zero-left-padded ASCII (PIC 9(11))
 *   TRANCAT-TYPE-CD   2 bytes  right-space-padded ASCII (PIC X(02))
 *   TRANCAT-CD        4 bytes  zero-left-padded  ASCII (PIC 9(04))
 *   Total            17 bytes
 * }</pre>
 *
 * <p>Per {@code app/cbl/CBTRN02C.cbl:L93-L96} the key is laid out
 * sequentially with no separators and no padding between fields. The
 * {@link #buildKey(long, String, int)} helper assembles the key via
 * a {@link ByteBuffer} of exactly {@link #KEY_LENGTH} bytes, matching
 * the binding {@code external_imports} schema requirement.
 *
 * <h2>UPSERT semantics &mdash; one Java {@code save} replaces two
 * COBOL paragraphs</h2>
 * <p>The {@link #save(TranCatBalRecord)} method dispatches to
 * {@link FixedWidthWriter#upsert(byte[], int, byte[])} which atomically
 * inserts a brand-new record at the correct key-sorted position OR
 * overwrites an existing record with the same composite key. This
 * collapses two COBOL paragraphs into a single Java entry point:
 * <ul>
 *   <li>{@code 2700-A-CREATE-TCATBAL-REC}
 *       ({@code app/cbl/CBTRN02C.cbl:L503-L524}) &mdash;
 *       {@code WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD}
 *       on the create branch.</li>
 *   <li>{@code 2700-B-UPDATE-TCATBAL-REC}
 *       ({@code app/cbl/CBTRN02C.cbl:L526-L540}) &mdash;
 *       {@code REWRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD}
 *       on the update branch.</li>
 * </ul>
 *
 * <h2>Behavioral contracts (mirroring COBOL FILE STATUS)</h2>
 * <table>
 *   <caption>FILE STATUS &rarr; Java return mapping</caption>
 *   <tr><th>COBOL outcome</th>             <th>Java mapping</th></tr>
 *   <tr><td>FILE STATUS '00' (success)</td><td>{@link Optional#of} (find) /
 *                                              normal return (save/delete)</td></tr>
 *   <tr><td>FILE STATUS '23' (NOTFND)</td> <td>{@link Optional#empty()} (find) /
 *                                              {@link NoSuchElementException}
 *                                              (delete)</td></tr>
 *   <tr><td>FILE STATUS '10' (EOF)</td>    <td>Stream natural termination
 *                                              for {@link #streamSequential()}</td></tr>
 *   <tr><td>Other (I/O error)</td>         <td>{@link UncheckedIOException}
 *                                              wrapping the underlying
 *                                              {@link IOException}</td></tr>
 * </table>
 *
 * <h2>Thread safety</h2>
 * <p>Instances are safe for read operations from any number of
 * platform or virtual threads &mdash; {@link FixedWidthReader} opens
 * a fresh {@link java.nio.channels.SeekableByteChannel} per call and
 * shares no mutable state between calls (AAP &sect;0.6.6 virtual-thread
 * fan-out is supported on the read path).
 *
 * <p>Write operations ({@link #save(TranCatBalRecord)} and
 * {@link #delete(long, String, int)}) are wrapped in an
 * instance-private {@link #writeLock} so that the encode-and-write
 * critical section is atomic with respect to other concurrent
 * {@code save} or {@code delete} calls on this instance. This is in
 * addition to the {@link FixedWidthWriter}'s own internal write lock
 * which serializes the channel open + atomic temp-file rewrite + move
 * sequence; the repository-level lock here ensures that the encode and
 * key derivation steps are also part of the critical section, matching
 * the conventional VSAM single-process WRITE/REWRITE idiom established
 * by sibling {@code File*Repository} adapters
 * ({@link FileTransactionRepository}, {@link FileAccountRepository}).
 *
 * <h2>No Spring, no ThreadLocal, no java.io.File</h2>
 * Per AAP &sect;0.6.5 and &sect;0.7.4, this adapter uses only
 * {@link java.nio.file} primitives and depends on plain Java classes:
 * no {@code java.io.File}, no Spring framework, no Lombok, no
 * {@code ThreadLocal}, no {@code double}/{@code float} (monetary
 * values are persisted via {@link TranCatBalRecord#encode()} using
 * {@link java.math.BigDecimal} per AAP &sect;0.6.1).
 *
 * @see TransactionCategoryBalanceRepository
 * @see TranCatBalRecord
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @since 1.0.0
 */
@CobolProgram(
        value = "TCATBALF",
        sourcePath = "app/cpy/CVTRA01Y.cpy",
        translationDate = "2025-10-24",
        notes = "File-backed adapter for the TCATBALF VSAM KSDS "
                + "(50-byte TRAN-CAT-BAL-RECORD; 17-byte composite key: "
                + "TRANCAT-ACCT-ID PIC 9(11) + TRANCAT-TYPE-CD PIC X(02) + "
                + "TRANCAT-CD PIC 9(04)). save() covers BOTH the WRITE "
                + "branch (2700-A-CREATE-TCATBAL-REC at "
                + "app/cbl/CBTRN02C.cbl:L503-L524) and the REWRITE branch "
                + "(2700-B-UPDATE-TCATBAL-REC at "
                + "app/cbl/CBTRN02C.cbl:L526-L540) by dispatching to "
                + "FixedWidthWriter.upsert(). TRAN-CAT-BAL is BigDecimal "
                + "scale 2 (S9(09)V99) per AAP §0.6.1."
)
public final class FileTransactionCategoryBalanceRepository
        implements TransactionCategoryBalanceRepository {

    /**
     * SLF4J logger; DEBUG-level on every successful save and delete.
     * Bound to slf4j-api 2.0.16 per AAP &sect;0.5.1; the concrete
     * backend (logback-classic 1.5.19) is supplied at runtime by the
     * composition root in {@code carddemo-app}, keeping this adapter
     * free of backend binding per AAP &sect;0.6.12.
     *
     * <p>Note: no PAN/PAN-like values are logged here. The composite
     * key components ({@code accountId}, {@code tranTypeCd},
     * {@code tranCatCd}) are internal lookup keys, not card numbers.
     */
    private static final Logger LOG =
            LoggerFactory.getLogger(FileTransactionCategoryBalanceRepository.class);

    /**
     * The configuration key used by {@link EbcdicTranscoder} to look
     * up the per-file codepage override for this dataset (e.g.,
     * {@code carddemo.file.tcatbalf.charset}). The default value (when
     * no override is set) is {@link EbcdicTranscoder#DEFAULT_CHARSET_NAME
     * IBM-1047} per AAP &sect;0.6.5.
     */
    public static final String DATASET_KEY = "tcatbalf";

    /**
     * Fixed {@code TRAN-CAT-BAL-RECORD} length in bytes per copybook
     * {@code app/cpy/CVTRA01Y.cpy}: 11 + 2 + 4 + 11 + 22 = 50. Matches
     * {@link TranCatBalRecord#RECORD_LENGTH}; declared locally to keep
     * this adapter's byte-level contract self-documenting per the
     * established sibling pattern ({@link FileTransactionRepository},
     * {@link FileAccountRepository}).
     */
    public static final int RECORD_LENGTH = TranCatBalRecord.RECORD_LENGTH;

    /**
     * Byte offset of the composite key field within each 50-byte
     * record. The key is the first field in the record structure per
     * {@code app/cpy/CVTRA01Y.cpy:L5-L8}:
     * <pre>{@code
     * 05 TRAN-CAT-KEY.
     *    10 TRANCAT-ACCT-ID  PIC 9(11).
     *    10 TRANCAT-TYPE-CD  PIC X(02).
     *    10 TRANCAT-CD       PIC 9(04).
     * }</pre>
     */
    public static final int KEY_OFFSET = 0;

    /**
     * Total length of the composite key in bytes: 11 + 2 + 4 = 17.
     * Matches the IDCAMS {@code KEYS(17 0)} declaration in
     * {@code app/jcl/TCATBALF.jcl}.
     */
    public static final int KEY_LENGTH = 17;

    /**
     * Byte length of the {@code TRANCAT-ACCT-ID PIC 9(11)} field.
     * Eleven zero-padded ASCII digit characters.
     */
    private static final int ACCT_ID_LENGTH = 11;

    /**
     * Byte length of the {@code TRANCAT-TYPE-CD PIC X(02)} field.
     * Exactly two ASCII characters, right-space-padded if narrower.
     */
    private static final int TYPE_CD_LENGTH = 2;

    /**
     * Byte length of the {@code TRANCAT-CD PIC 9(04)} field. Four
     * zero-padded ASCII digit characters.
     */
    private static final int CAT_CD_LENGTH = 4;

    /**
     * Upper inclusive bound for {@code TRANCAT-ACCT-ID PIC 9(11)}:
     * eleven nines = {@code 99,999,999,999}. Values outside the
     * {@code [0, 99_999_999_999]} domain cannot be encoded as a valid
     * 11-digit zoned-decimal key.
     */
    private static final long ACCT_ID_MAX = 99_999_999_999L;

    /**
     * Upper inclusive bound for {@code TRANCAT-CD PIC 9(04)}: four
     * nines = {@code 9999}. Values outside the {@code [0, 9999]}
     * domain cannot be encoded as a valid 4-digit zoned-decimal field
     * per the COBOL semantics in {@code app/cpy/CVTRA01Y.cpy:L8}.
     */
    private static final int CAT_CD_MAX = 9_999;

    /**
     * ASCII space byte ({@code 0x20}) used for {@code PIC X(n)}
     * right-padding when the supplied value is shorter than the
     * declared field length.
     */
    private static final byte SPACE = (byte) ' ';

    /**
     * The TCATBALF data file path. Held as a {@link Path} per AAP
     * &sect;0.6.5 (no {@code java.io.File} in new code). Configurable
     * at the composition root via the
     * {@code carddemo.file.tcatbalf.path} property (12-factor
     * configuration per AAP &sect;0.5.4).
     */
    private final Path dataFile;

    /**
     * Configured codepage forwarded to the underlying
     * {@link FixedWidthReader} and {@link FixedWidthWriter}. Defaults
     * to {@code IBM-1047} (EBCDIC) per AAP &sect;0.6.5 with per-file
     * override via {@code carddemo.file.tcatbalf.charset}. The key
     * itself is encoded as ASCII (see
     * {@link #buildKey(long, String, int)}) because the underlying
     * {@link TranCatBalRecord#encode()} method writes record bytes
     * as ASCII via {@link StandardCharsets#US_ASCII}; using the same
     * encoding for the lookup key is required so that
     * {@link FixedWidthWriter#upsert} can byte-match the in-record
     * key bytes against the supplied key bytes.
     */
    private final Charset charset;

    /**
     * Foundational byte-level reader for the TCATBALF file.
     * Instantiated once in the constructor and reused across every
     * read operation. Thread-safe per its own contract (opens a fresh
     * channel per call; shares no mutable state between calls).
     */
    private final FixedWidthReader reader;

    /**
     * Foundational byte-level writer for the TCATBALF file.
     * Instantiated once in the constructor and reused across every
     * write operation. Internally synchronizes its own mutations on
     * its private lock; this adapter additionally wraps
     * {@link FixedWidthWriter#upsert(byte[], int, byte[]) upsert} and
     * {@link FixedWidthWriter#deleteByKey(byte[], int) deleteByKey}
     * in the repository-level {@link #writeLock} so that the
     * encode-and-write sequence in {@link #save(TranCatBalRecord)}
     * cannot interleave with another concurrent write at the adapter
     * level.
     */
    private final FixedWidthWriter writer;

    /**
     * Repository-level write lock. Serializes the
     * validate-encode-key-and-upsert sequence in
     * {@link #save(TranCatBalRecord)} and the
     * validate-encode-key-and-deleteByKey sequence in
     * {@link #delete(long, String, int)}. Held only across the
     * smallest possible critical section (a single port-method
     * invocation); not re-entrant; not exposed via a public accessor
     * (which would tempt callers to perform cross-method critical
     * sections that this class is not designed to support).
     *
     * <p>Per AAP &sect;0.6.6, this lock is the only in-process
     * serialisation primitive used here; no {@link ThreadLocal} and
     * no {@code ScopedValue} is needed because the lock guards a
     * class instance (not a per-thread variable).
     */
    private final Object writeLock = new Object();

    /**
     * Convenience constructor delegating to the canonical
     * {@link #FileTransactionCategoryBalanceRepository(Path, Charset)}
     * constructor with {@code IBM-1047} (EBCDIC) as the default
     * codepage per AAP &sect;0.6.5. Preserves source-code
     * compatibility with the established codebase convention used by
     * {@code PostTransactionsApp} and {@code InterestCalculationApp}
     * composition roots, which construct repositories with a single
     * {@link Path} argument and rely on the mandated EBCDIC default.
     *
     * <p>For an ASCII-encoded input file (e.g., the ASCII fixtures
     * shipped under {@code app/data/ASCII/}), use the canonical
     * two-arg constructor and pass
     * {@link java.nio.charset.StandardCharsets#US_ASCII US-ASCII}
     * explicitly &mdash; or set the
     * {@code carddemo.file.tcatbalf.charset} property at the
     * composition root.
     *
     * @param dataFile absolute filesystem path of the TCATBALF file
     *                 (e.g., {@code ./data/tcatbal.dat}); must not be
     *                 {@code null}. The file need NOT exist at
     *                 construction time; read methods handle missing
     *                 files as NOTFND parity, and write methods
     *                 create the file on first call if absent.
     * @throws NullPointerException if {@code dataFile} is {@code null}
     */
    public FileTransactionCategoryBalanceRepository(Path dataFile) {
        this(dataFile, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs a new file-backed TCATBALF adapter. The data file
     * need NOT exist at construction time; read methods handle
     * missing files as NOTFND parity ({@link Optional#empty()} or
     * empty streams), and write methods create the file on first
     * call if absent.
     *
     * @param dataFile absolute filesystem path of the TCATBALF file
     *                 (e.g., {@code ./data/tcatbal.dat}); must not be
     *                 {@code null}
     * @param charset  the character set forwarded to the underlying
     *                 {@link FixedWidthReader} and
     *                 {@link FixedWidthWriter}; defaults to
     *                 {@code IBM-1047} per AAP &sect;0.6.5;
     *                 configurable via
     *                 {@code carddemo.file.tcatbalf.charset} in
     *                 {@code application.properties}. Note that the
     *                 17-byte composite key is encoded via
     *                 {@link StandardCharsets#US_ASCII US-ASCII} (see
     *                 {@link #buildKey(long, String, int)}) regardless
     *                 of this parameter so that the key bytes match
     *                 the in-record key bytes produced by
     *                 {@link TranCatBalRecord#encode()} (also
     *                 US-ASCII); the {@code charset} parameter is
     *                 propagated to the underlying readers/writers for
     *                 symmetry with sibling repositories and for
     *                 future codepage-aware operations.
     * @throws NullPointerException if any argument is {@code null}
     */
    public FileTransactionCategoryBalanceRepository(Path dataFile, Charset charset) {
        this.dataFile = Objects.requireNonNull(dataFile, "dataFile");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(dataFile, RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(dataFile, RECORD_LENGTH, charset);
        LOG.debug("FileTransactionCategoryBalanceRepository configured: "
                + "dataFile={}, charset={}, recordLength={}",
                dataFile, charset, RECORD_LENGTH);
    }

    /**
     * Returns the configured data file path.
     *
     * @return the path to the TCATBALF file; never {@code null}
     */
    public Path file() {
        return dataFile;
    }

    /**
     * Returns the configured character set.
     *
     * @return the charset; never {@code null}
     */
    public Charset charset() {
        return charset;
    }

    // ------------------------------------------------------------------
    // TransactionCategoryBalanceRepository port methods
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Translates the COBOL random read in
     * {@code app/cbl/CBTRN02C.cbl} paragraph
     * {@code 2700-UPDATE-TCATBAL} at
     * {@code app/cbl/CBTRN02C.cbl:L474-L479}:
     * <pre>{@code
     *   READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD
     *      INVALID KEY
     *        DISPLAY 'TCATBAL record not found for key : '
     *           FD-TRAN-CAT-KEY '.. Creating.'
     *        MOVE 'Y' TO WS-CREATE-TRANCAT-REC
     *   END-READ.
     * }</pre>
     *
     * <h3>Implementation</h3>
     * <ol>
     *   <li>Builds the 17-byte composite key via
     *       {@link #buildKey(long, String, int)} which validates the
     *       composite-key components against their respective COBOL
     *       PIC bounds and assembles the byte image via a
     *       {@link ByteBuffer} of exactly {@link #KEY_LENGTH} bytes.</li>
     *   <li>Delegates to {@link FixedWidthReader#findByKey(byte[], int)}
     *       which scans the file sequentially comparing the key field
     *       at {@link #KEY_OFFSET} of each record.</li>
     *   <li>Maps the returned byte buffer via
     *       {@link TranCatBalRecord#parse(byte[])}.</li>
     * </ol>
     *
     * <p>Returns {@link Optional#empty()} on NOTFND (file status
     * {@code '23'}) which is the trigger for the create branch in the
     * COBOL flow. The {@link IOException} surface from the reader is
     * wrapped in {@link UncheckedIOException} per the port contract.
     *
     * @throws NullPointerException     if {@code tranTypeCd} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code accountId} is
     *                                  outside {@code [0, 99_999_999_999]},
     *                                  {@code tranTypeCd} exceeds 2
     *                                  characters, or {@code tranCatCd}
     *                                  is outside {@code [0, 9999]}
     * @throws UncheckedIOException     if the underlying file read
     *                                  fails
     */
    @Override
    public Optional<TranCatBalRecord> findByKey(long accountId, String tranTypeCd, int tranCatCd) {
        byte[] keyBytes = buildKey(accountId, tranTypeCd, tranCatCd);
        try {
            return reader.findByKey(keyBytes, KEY_OFFSET).map(TranCatBalRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error reading TCATBALF for accountId=" + accountId
                            + " tranTypeCd='" + tranTypeCd
                            + "' tranCatCd=" + tranCatCd + " from " + dataFile, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Translates the COBOL sequential traversal of the
     * {@code TCATBALF} dataset used by:
     * <ul>
     *   <li>{@code app/cbl/CBACT04C.cbl} paragraph
     *       {@code 1000-TCATBALF-GET-NEXT}
     *       ({@code app/cbl/CBACT04C.cbl:L325-L348}) for the monthly
     *       interest computation.</li>
     *   <li>{@code app/jcl/PRTCATBL.jcl} sequential print of the
     *       entire dataset.</li>
     * </ul>
     *
     * <p>The returned {@link Stream} is lazy and backed by the
     * underlying {@link java.nio.channels.SeekableByteChannel};
     * callers MUST close the stream &mdash; typically via
     * try-with-resources &mdash; so that the channel is released
     * promptly. Records are streamed in ascending composite-key
     * order; reordering is forbidden per AAP &sect;0.1.3.
     *
     * @return a lazy {@link Stream} of every {@link TranCatBalRecord}
     *         in ascending composite-key order; never {@code null}
     *         (empty stream when the dataset has no records)
     * @throws UncheckedIOException if the underlying file open fails
     */
    @Override
    public Stream<TranCatBalRecord> streamSequential() {
        try {
            return reader.streamSequential().map(TranCatBalRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error opening TCATBALF for sequential read: " + dataFile, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Upsert semantics &mdash; one Java {@code save} collapses
     * both COBOL paragraphs:
     * <ul>
     *   <li><b>WRITE (create) branch</b> &mdash; corresponds to
     *       {@code WRITE FD-TRAN-CAT-BAL-RECORD FROM
     *       TRAN-CAT-BAL-RECORD} in paragraph
     *       {@code 2700-A-CREATE-TCATBAL-REC} at
     *       {@code app/cbl/CBTRN02C.cbl:L510}.</li>
     *   <li><b>REWRITE (update) branch</b> &mdash; corresponds to
     *       {@code REWRITE FD-TRAN-CAT-BAL-RECORD FROM
     *       TRAN-CAT-BAL-RECORD} in paragraph
     *       {@code 2700-B-UPDATE-TCATBAL-REC} at
     *       {@code app/cbl/CBTRN02C.cbl:L528}.</li>
     * </ul>
     * The underlying {@link FixedWidthWriter#upsert(byte[], int, byte[])}
     * inspects existing records and either inserts the new record in
     * the correct key-sorted position (matching VSAM KSDS physical
     * ordering) or overwrites the existing record with the same key.
     *
     * <h3>Implementation</h3>
     * <ol>
     *   <li>Verifies the supplied {@link TranCatBalRecord} is
     *       non-null.</li>
     *   <li>Encodes the record to its canonical 50-byte byte image
     *       via {@link TranCatBalRecord#encode()}. The compact
     *       canonical constructor of {@link TranCatBalRecord} has
     *       already enforced field-level invariants
     *       (composite-key components in range, balance scale
     *       exactly 2, FILLER length exactly 22).</li>
     *   <li>Asserts the encoded length equals {@link #RECORD_LENGTH}
     *       as a defense-in-depth check protecting the dataset from
     *       corruption if a future refactor accidentally breaks the
     *       record-fidelity invariant.</li>
     *   <li>Builds the 17-byte composite key via
     *       {@link #buildKey(long, String, int)} using the key
     *       components carried on the record.</li>
     *   <li>Under the repository-level {@link #writeLock}, delegates
     *       to {@link FixedWidthWriter#upsert(byte[], int, byte[])}
     *       to perform the atomic insert-or-replace.</li>
     * </ol>
     *
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalStateException if {@link TranCatBalRecord#encode()}
     *                               returns a buffer whose length
     *                               differs from {@link #RECORD_LENGTH}
     *                               (defensive invariant check)
     * @throws UncheckedIOException  on underlying file I/O failure
     */
    @Override
    public void save(TranCatBalRecord record) {
        Objects.requireNonNull(record, "record");
        // Encode the record to its 50-byte canonical byte image.
        // TranCatBalRecord.encode() is guaranteed to return exactly
        // RECORD_LENGTH bytes per the AAP §0.6.5 byte-fidelity
        // invariant; the defensive length check below surfaces any
        // future regression in TranCatBalRecord immediately rather
        // than silently corrupting the file.
        byte[] encoded = record.encode();
        if (encoded.length != RECORD_LENGTH) {
            throw new IllegalStateException(
                    "TranCatBalRecord.encode() returned " + encoded.length
                            + " bytes; expected " + RECORD_LENGTH
                            + " (record-fidelity invariant violated)");
        }
        // The nested TranCatKey exposes trancatAcctId(),
        // trancatTypeCd(), trancatCd() — see CVTRA01Y.cpy:L5-L8.
        TranCatBalRecord.TranCatKey k = record.tranCatKey();
        byte[] keyBytes = buildKey(k.trancatAcctId(), k.trancatTypeCd(), k.trancatCd());
        // Serialise the upsert under the repository-level writeLock
        // so that concurrent callers see a strict single-writer
        // ordering (matching the COBOL FILE-OPEN-I-O single-process
        // WRITE/REWRITE semantic). FixedWidthWriter also has its own
        // internal writeLock; the two-level locking is intentional
        // for defense in depth and matches the established sibling
        // File*Repository pattern (FileTransactionRepository,
        // FileAccountRepository).
        synchronized (writeLock) {
            try {
                writer.upsert(keyBytes, KEY_OFFSET, encoded);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Error upserting TCATBALF accountId="
                                + k.trancatAcctId()
                                + " tranTypeCd='" + k.trancatTypeCd()
                                + "' tranCatCd=" + k.trancatCd()
                                + " to " + dataFile, e);
            }
        }
        if (LOG.isDebugEnabled()) {
            // tranCatBal is scale-preserved BigDecimal (e.g., "1.20"
            // never "1.2") per AAP §0.1.3 — safe to print without
            // further formatting.
            LOG.debug("save accountId={} tranTypeCd='{}' tranCatCd={} balance={} file={} status=ok",
                    k.trancatAcctId(), k.trancatTypeCd(), k.trancatCd(),
                    record.tranCatBal(), dataFile);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Removes the {@link TranCatBalRecord} keyed by the composite
     * {@code (accountId, tranTypeCd, tranCatCd)} triple. The port
     * contract specifies that this operation exists exclusively to
     * support the IDCAMS {@code DELETE CLUSTER} / re-{@code DEFINE}
     * cycle declared in {@code app/jcl/TCATBALF.jcl}; no translated
     * COBOL application program issues a runtime {@code DELETE}
     * against {@code TCATBALF}. The {@code DefineTcatBalApp}
     * composition root uses this method to express the reset-and-reload
     * idiom symmetrically with sibling apps.
     *
     * <h3>Implementation</h3>
     * <ol>
     *   <li>Builds the 17-byte composite key via
     *       {@link #buildKey(long, String, int)} (which also performs
     *       PIC-bound validation on the components).</li>
     *   <li>Under the repository-level {@link #writeLock}, delegates
     *       to {@link FixedWidthWriter#deleteByKey(byte[], int)} which
     *       returns {@code false} on NOTFND (no matching record).</li>
     *   <li>Translates the NOTFND outcome into a
     *       {@link NoSuchElementException}, mirroring the COBOL
     *       {@code INVALID KEY} clause on {@code DELETE} (file
     *       status {@code '23'}).</li>
     * </ol>
     *
     * @throws NullPointerException     if {@code tranTypeCd} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code accountId} is
     *                                  outside {@code [0, 99_999_999_999]},
     *                                  {@code tranTypeCd} exceeds 2
     *                                  characters, or {@code tranCatCd}
     *                                  is outside {@code [0, 9999]}
     * @throws NoSuchElementException   if no record with the supplied
     *                                  composite key exists
     * @throws UncheckedIOException     if the underlying file delete
     *                                  fails
     */
    @Override
    public void delete(long accountId, String tranTypeCd, int tranCatCd) {
        byte[] keyBytes = buildKey(accountId, tranTypeCd, tranCatCd);
        synchronized (writeLock) {
            try {
                boolean removed = writer.deleteByKey(keyBytes, KEY_OFFSET);
                if (!removed) {
                    throw new NoSuchElementException(
                            "No TCATBALF record found for accountId=" + accountId
                                    + " tranTypeCd='" + tranTypeCd
                                    + "' tranCatCd=" + tranCatCd);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Error deleting TCATBALF accountId=" + accountId
                                + " tranTypeCd='" + tranTypeCd
                                + "' tranCatCd=" + tranCatCd
                                + " from " + dataFile, e);
            }
        }
        LOG.debug("delete accountId={} tranTypeCd='{}' tranCatCd={} file={} status=ok",
                accountId, tranTypeCd, tranCatCd, dataFile);
    }

    // ------------------------------------------------------------------
    // AutoCloseable — required by TransactionCategoryBalanceRepository
    // extending AutoCloseable
    // ------------------------------------------------------------------

    /**
     * Releases any resources held by this repository. This adapter's
     * underlying {@link FixedWidthReader} and {@link FixedWidthWriter}
     * open a fresh {@link java.nio.channels.SeekableByteChannel} per
     * operation and close it deterministically (via try-with-resources
     * in the foundational primitives, or via {@code Stream.onClose}
     * hook for streaming methods); no long-lived resources are held
     * by this adapter, so {@code close()} is a no-op DEBUG-log
     * statement.
     *
     * <p>Idempotent: callers may defensively invoke this method
     * multiple times without observable side effects, consistent with
     * the {@link TransactionCategoryBalanceRepository#close()}
     * contract.
     *
     * <p>Corresponds to the COBOL {@code CLOSE TCATBAL-FILE} verb in
     * the {@code 9000-TCATBALF-CLOSE} paragraph of CBACT04C
     * ({@code app/cbl/CBACT04C.cbl:L522-L540}) and the analogous
     * {@code CLOSE TCATBAL-FILE} at the tail of CBTRN02C's
     * processing flow.
     */
    @Override
    public void close() {
        // No long-lived resources to release.
        // FixedWidthReader and FixedWidthWriter manage their own
        // channel lifecycle per operation; nothing to close at the
        // repository level. Method present to satisfy the
        // AutoCloseable / port contract and to allow future adapter
        // implementations to add resource cleanup without changing
        // the call sites.
        LOG.debug("FileTransactionCategoryBalanceRepository closed (no-op): dataFile={}", dataFile);
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /**
     * Builds the 17-byte composite key from the three logical key
     * components. Layout per
     * {@code app/cpy/CVTRA01Y.cpy:L5-L8} and
     * {@code app/cbl/CBTRN02C.cbl:L93-L96}:
     * <pre>{@code
     *   bytes  0..10  TRANCAT-ACCT-ID  PIC 9(11)  zero-left-padded
     *   bytes 11..12  TRANCAT-TYPE-CD  PIC X(02)  right-space-padded
     *   bytes 13..16  TRANCAT-CD       PIC 9(04)  zero-left-padded
     * }</pre>
     *
     * <p>The byte image is assembled via a {@link ByteBuffer} of
     * exactly {@link #KEY_LENGTH} bytes followed by three {@code put}
     * calls in COBOL declaration order &mdash; matching the binding
     * {@code external_imports} schema requirement that
     * {@link ByteBuffer#allocate(int) ByteBuffer.allocate(KEY_LENGTH)}
     * is used here.
     *
     * <p>The key bytes are encoded as ASCII via
     * {@link StandardCharsets#US_ASCII}, NOT via the configured
     * {@link #charset} field. This is required so that the lookup
     * key bytes match the in-record key bytes produced by
     * {@link TranCatBalRecord#encode()} (which always writes ASCII
     * via its private {@code writeAscii} / {@code writeUnsignedLong}
     * helpers). Using the configured charset (e.g., IBM-1047 EBCDIC)
     * would produce key bytes that never match the ASCII record
     * bytes, causing every {@link FixedWidthWriter#upsert} and
     * {@link FixedWidthReader#findByKey} call to behave as a NOTFND.
     *
     * <p>Performs eager argument validation against the COBOL PIC
     * bounds:
     * <ul>
     *   <li>{@code accountId} must satisfy
     *       {@code 0 <= accountId <= 99_999_999_999} per
     *       {@code TRANCAT-ACCT-ID PIC 9(11)}.</li>
     *   <li>{@code tranTypeCd} must be non-null and at most
     *       {@code TYPE_CD_LENGTH} ({@value #TYPE_CD_LENGTH})
     *       characters per {@code TRANCAT-TYPE-CD PIC X(02)}.</li>
     *   <li>{@code tranCatCd} must satisfy
     *       {@code 0 <= tranCatCd <= 9999} per
     *       {@code TRANCAT-CD PIC 9(04)}.</li>
     * </ul>
     *
     * @param accountId  the unsigned 11-digit account ID
     * @param tranTypeCd the 2-character transaction type code
     * @param tranCatCd  the unsigned 4-digit category code
     * @return a freshly allocated 17-byte ASCII-encoded key buffer
     * @throws NullPointerException     if {@code tranTypeCd} is
     *                                  {@code null}
     * @throws IllegalArgumentException if any component is outside
     *                                  its COBOL PIC bound
     */
    private byte[] buildKey(long accountId, String tranTypeCd, int tranCatCd) {
        Objects.requireNonNull(tranTypeCd, "tranTypeCd");
        if (accountId < 0L || accountId > ACCT_ID_MAX) {
            throw new IllegalArgumentException(
                    "accountId out of PIC 9(" + ACCT_ID_LENGTH + ") range "
                            + "[0, " + ACCT_ID_MAX + "]: " + accountId);
        }
        if (tranTypeCd.length() > TYPE_CD_LENGTH) {
            throw new IllegalArgumentException(
                    "tranTypeCd max length " + TYPE_CD_LENGTH
                            + " per PIC X(" + TYPE_CD_LENGTH + "); got "
                            + tranTypeCd.length());
        }
        if (tranCatCd < 0 || tranCatCd > CAT_CD_MAX) {
            throw new IllegalArgumentException(
                    "tranCatCd out of PIC 9(" + CAT_CD_LENGTH + ") range "
                            + "[0, " + CAT_CD_MAX + "]: " + tranCatCd);
        }

        // Assemble the 17-byte composite key via ByteBuffer per the
        // binding external_imports schema requirement. Locale.ROOT
        // pins decimal-digit formatting to ASCII '0'..'9' independent
        // of the JVM default locale (which could otherwise produce
        // non-ASCII numerals in some locales).
        ByteBuffer buffer = ByteBuffer.allocate(KEY_LENGTH);
        buffer.put(String.format(Locale.ROOT, "%0" + ACCT_ID_LENGTH + "d", accountId)
                .getBytes(StandardCharsets.US_ASCII));
        buffer.put(padRightSpaces(tranTypeCd, TYPE_CD_LENGTH));
        buffer.put(String.format(Locale.ROOT, "%0" + CAT_CD_LENGTH + "d", tranCatCd)
                .getBytes(StandardCharsets.US_ASCII));
        return buffer.array();
    }

    /**
     * Right-space-pads (or truncates to) {@code value} to exactly
     * {@code length} ASCII bytes. Models the COBOL
     * {@code PIC X(length)} encoding where shorter values are padded
     * with ASCII spaces on the RIGHT.
     *
     * <p>Encodes the supplied {@code value} via
     * {@link StandardCharsets#US_ASCII} (matching
     * {@link TranCatBalRecord#encode()}'s record-byte encoding). If
     * the encoded value is longer than {@code length}, only the
     * first {@code length} bytes are copied; the canonical
     * constructor of {@link TranCatBalRecord.TranCatKey} already
     * rejects values longer than {@link #TYPE_CD_LENGTH} at the
     * domain layer (see
     * {@code TranCatBalRecord.TranCatKey} constructor in
     * {@code TranCatBalRecord.java}), but this helper applies
     * defense-in-depth truncation rather than overflowing the key
     * buffer.
     *
     * @param value  the value to encode and pad; must not be
     *               {@code null}
     * @param length the target field length in bytes
     * @return a freshly allocated {@code byte[length]} containing
     *         the ASCII-encoded value followed by ASCII-space padding
     */
    private byte[] padRightSpaces(String value, int length) {
        byte[] result = new byte[length];
        byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
        int copy = Math.min(valueBytes.length, length);
        System.arraycopy(valueBytes, 0, result, 0, copy);
        for (int i = copy; i < length; i++) {
            result[i] = SPACE;
        }
        return result;
    }
}
