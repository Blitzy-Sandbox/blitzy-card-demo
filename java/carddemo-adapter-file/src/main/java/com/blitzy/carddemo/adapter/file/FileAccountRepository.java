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
import com.blitzy.carddemo.domain.port.AccountRepository;
import com.blitzy.carddemo.domain.record.AccountRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed adapter implementation of {@link AccountRepository} that reads
 * and writes 300-byte fixed-width records keyed by an 11-digit
 * {@code ACCT-ID} from the {@code ACCTDATA} VSAM KSDS dataset.
 *
 * <h2>Authority and source lineage</h2>
 * <ul>
 *   <li><b>AAP &sect;0.1.1</b> &mdash; file-based batch processing by default
 *       (no PostgreSQL); this adapter is the default outer-ring repository
 *       implementation for ACCTDATA per the hexagonal architecture diagram
 *       at AAP &sect;0.3.6.</li>
 *   <li><b>AAP &sect;0.6.5</b> &mdash; file I/O exactness. This adapter uses
 *       only {@link java.nio.file} primitives delegated through
 *       {@link FixedWidthReader} / {@link FixedWidthWriter}; the
 *       {@code java.io.File} class is FORBIDDEN.</li>
 *   <li><b>AAP &sect;0.4.1</b> (transformation map) &mdash;
 *       {@code FileAccountRepository} translates the COBOL VSAM KSDS access
 *       patterns demonstrated in:
 *       <ul>
 *         <li>{@code app/cpy/CVACT01Y.cpy} &mdash; 300-byte
 *             {@code ACCOUNT-RECORD} layout with 11-byte
 *             {@code ACCT-ID PIC 9(11)} primary key at offset 0.</li>
 *         <li>{@code app/cbl/CBACT01C.cbl} &mdash; sequential read pattern
 *             ({@code SELECT/ASSIGN}, {@code OPEN INPUT}, {@code READ NEXT},
 *             EOF handling via {@code FILE STATUS '10'} at
 *             {@code app/cbl/CBACT01C.cbl:L29-L33}).</li>
 *         <li>{@code app/cbl/CBTRN02C.cbl} paragraphs
 *             {@code 1500-B-LOOKUP-ACCT}
 *             ({@code app/cbl/CBTRN02C.cbl:L393-L444}, random read by key)
 *             and {@code 2800-UPDATE-ACCOUNT-REC}
 *             ({@code app/cbl/CBTRN02C.cbl:L545-L560}, {@code REWRITE}).</li>
 *         <li>{@code app/cbl/COACTUPC.cbl} paragraph
 *             {@code 9700-CHECK-CHANGE-IN-REC}
 *             ({@code app/cbl/COACTUPC.cbl:L4065-L4071}) &mdash;
 *             {@code REWRITE} within {@code SYNCPOINT} (the sole
 *             {@code EXEC CICS SYNCPOINT ROLLBACK} in the entire COBOL
 *             codebase, at {@code app/cbl/COACTUPC.cbl:L4099-L4100};
 *             multi-record transactional boundary is reconstructed at the
 *             application layer per AAP &sect;0.4.1, NOT at this adapter).</li>
 *         <li>{@code app/data/ASCII/acctdata.txt} &mdash; ASCII fixture
 *             used by the golden-record harness.</li>
 *       </ul></li>
 * </ul>
 *
 * <h2>Byte-for-byte fidelity</h2>
 * <p>This adapter delegates all field-level serialization to
 * {@link AccountRecord#parse(byte[])} and {@link AccountRecord#encode()},
 * preserving the 178-byte FILLER verbatim. Re-encoding a parsed record MUST
 * produce a byte-identical buffer (AAP &sect;0.6.5 round-trip invariant).
 *
 * <h2>Behavioral contracts (mirroring COBOL FILE STATUS / DFHRESP)</h2>
 * <table>
 *   <caption>FILE STATUS / DFHRESP &rarr; Java return mapping</caption>
 *   <tr><th>COBOL outcome</th>            <th>Java mapping</th></tr>
 *   <tr><td>FILE STATUS '00' (success)</td><td>{@link Optional#of} (find) /
 *                                              normal return (save/delete)</td></tr>
 *   <tr><td>FILE STATUS '23' (NOTFND)</td><td>{@link Optional#empty()} (find) /
 *                                              {@link NoSuchElementException}
 *                                              (delete)</td></tr>
 *   <tr><td>FILE STATUS '10' (EOF)</td>   <td>Stream natural termination
 *                                              for {@link #streamSequential()}</td></tr>
 *   <tr><td>Other (I/O error)</td>        <td>{@link UncheckedIOException}
 *                                              wrapping the underlying
 *                                              {@link IOException}</td></tr>
 * </table>
 *
 * <h2>Thread safety</h2>
 * <p>Instances are safe for read operations from any number of platform or
 * virtual threads &mdash; {@link FixedWidthReader} opens a fresh
 * {@link java.nio.channels.SeekableByteChannel} per call and shares no
 * mutable state between calls (AAP &sect;0.6.6 virtual-thread fan-out is
 * supported on the read path).
 *
 * <p>Write operations ({@link #save(AccountRecord)} and
 * {@link #delete(long)}) are wrapped in an instance-private
 * {@link #writeLock} so that the encode-and-write critical section is
 * atomic with respect to other concurrent {@code save} or {@code delete}
 * calls on this instance. This is in addition to the
 * {@link FixedWidthWriter}'s own internal {@code writeLock} which
 * serializes the channel open + atomic temp-file rewrite + move sequence;
 * the repository-level lock here ensures that the encode and key
 * derivation steps are also part of the critical section, matching the
 * conventional VSAM single-process WRITE/REWRITE idiom.
 *
 * <h2>No Spring, no ThreadLocal, no java.io.File</h2>
 * Per AAP &sect;0.6.5 and &sect;0.7.4, this adapter uses only
 * {@link java.nio.file} primitives and depends on plain Java classes:
 * no {@code java.io.File}, no Spring framework, no Lombok, no
 * {@code ThreadLocal}, no {@code double}/{@code float} (monetary values
 * are persisted via {@link AccountRecord#encode()} using
 * {@link java.math.BigDecimal} per AAP &sect;0.6.1).
 *
 * @see AccountRepository
 * @see AccountRecord
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @since 1.0.0
 */
@CobolProgram(
        value = "ACCTDATA",
        sourcePath = "app/cpy/CVACT01Y.cpy",
        translationDate = "2025-10-24",
        notes = "File-backed adapter for the ACCTDATA VSAM KSDS "
                + "(300-byte ACCOUNT-RECORD; 11-byte ACCT-ID primary key at "
                + "offset 0). See CBACT01C (sequential read), CBACT04C "
                + "(interest-posting REWRITE), CBTRN02C 1500-B-LOOKUP-ACCT "
                + "and 2800-UPDATE-ACCOUNT-REC (random read + REWRITE), "
                + "COACTVWC (online account view), COACTUPC "
                + "9700-CHECK-CHANGE-IN-REC (read + REWRITE within SYNCPOINT; "
                + "sole CICS SYNCPOINT ROLLBACK in COBOL codebase), and "
                + "COBIL00C (bill-payment REWRITE). The two-file rollback "
                + "boundary in COACTUPC is reconstructed at the application "
                + "layer (CoActUpC) via try/finally with compensating writes, "
                + "NOT in this adapter."
)
public final class FileAccountRepository implements AccountRepository {

    /**
     * SLF4J logger for save/delete debug logging. Bound to slf4j-api 2.0.16
     * per AAP &sect;0.5.1; the concrete backend (logback-classic 1.5.19)
     * is supplied at runtime by the composition root in {@code carddemo-app}.
     *
     * <p>Note: no PAN/PAN-like values are logged here. The {@code ACCT-ID}
     * is an internal account identifier and not a card number, so masking
     * per AAP &sect;0.7.2 is not required at this site (the
     * card-number-bearing adapter {@code FileCardRepository} performs PAN
     * masking).
     */
    private static final Logger LOG = LoggerFactory.getLogger(FileAccountRepository.class);

    /**
     * Fixed {@code ACCOUNT-RECORD} length in bytes per copybook
     * {@code app/cpy/CVACT01Y.cpy}:
     * 11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10 + 178 = 300.
     * Matches {@link AccountRecord#RECORD_LENGTH}; declared locally to keep
     * this adapter's byte-level contract self-documenting and to avoid the
     * indirect dependency on the domain record's static constant for a
     * value that is part of this adapter's own fixed-width I/O
     * configuration (AAP &sect;0.6.5).
     */
    private static final int RECORD_LENGTH = 300;

    /**
     * Byte offset of the {@code ACCT-ID} primary key field within each
     * 300-byte record. The key is the first field in the record structure
     * per {@code app/cpy/CVACT01Y.cpy:L5}:
     * {@code 05 ACCT-ID PIC 9(11).}
     */
    private static final int KEY_OFFSET = 0;

    /**
     * Length of the {@code ACCT-ID} primary key field in bytes. The COBOL
     * declaration {@code PIC 9(11)} encodes as 11 zero-padded ASCII digits
     * (zoned-decimal / {@code USAGE DISPLAY}) in the ASCII fixtures shipped
     * under {@code app/data/ASCII/acctdata.txt}.
     */
    private static final int KEY_LENGTH = 11;

    /**
     * Upper inclusive bound for an {@code ACCT-ID} value: {@code PIC 9(11)}
     * is an unsigned 11-digit numeric, hence the maximum is
     * {@code 99,999,999,999} (eleven nines). Values outside the
     * {@code [0, 99_999_999_999]} domain cannot be encoded as a valid
     * 11-digit zoned-decimal key per the COBOL semantics in
     * {@code app/cpy/CVACT01Y.cpy}.
     */
    private static final long ACCT_ID_MAX = 99_999_999_999L;

    /**
     * Configuration key prefix used by {@link EbcdicTranscoder} to look up
     * the per-file codepage override for this dataset (e.g.,
     * {@code carddemo.file.acctdata.charset}). The default value (when no
     * override is set) is {@link EbcdicTranscoder#DEFAULT_CHARSET_NAME
     * IBM-1047} per AAP &sect;0.6.5.
     */
    public static final String DATASET_KEY = "acctdata";

    /**
     * The ACCTDATA data file path. Held as a {@link Path} per AAP
     * &sect;0.6.5 (no {@code java.io.File} in new code). Configurable at
     * the composition root via the {@code carddemo.file.acctdata.path}
     * property (12-factor configuration per AAP &sect;0.5.4).
     */
    private final Path dataFile;

    /**
     * Configured codepage for byte-level key encoding. Defaults to
     * {@code IBM-1047} (EBCDIC) per AAP &sect;0.6.5 with per-file override
     * via {@code carddemo.file.acctdata.charset}. Forwarded to
     * {@link FixedWidthReader} and {@link FixedWidthWriter}. Note that
     * {@link AccountRecord#parse(byte[])} and {@link AccountRecord#encode()}
     * themselves operate against ASCII fixtures shipped with the project
     * ({@link StandardCharsets#US_ASCII US-ASCII}); the {@code charset}
     * parameter is forwarded primarily for symmetry with the other
     * file-backed repositories and for future EBCDIC-sourced input
     * compatibility where the {@link EbcdicTranscoder} would supply the
     * appropriate override.
     */
    private final Charset charset;

    /**
     * Foundational byte-level reader for the ACCTDATA file. Instantiated
     * once in the constructor and reused across every read operation.
     * Thread-safe per its own contract (opens a fresh channel per call;
     * shares no mutable state between calls).
     */
    private final FixedWidthReader reader;

    /**
     * Foundational byte-level writer for the ACCTDATA file. Instantiated
     * once in the constructor and reused across every write operation.
     * Internally synchronizes its own mutations on its private lock; this
     * adapter additionally wraps {@code upsert} and {@code deleteByKey}
     * in the repository-level {@link #writeLock} (below) so that the
     * encode-and-write sequence in {@link #save(AccountRecord)} cannot
     * interleave with another concurrent write at the adapter level.
     */
    private final FixedWidthWriter writer;

    /**
     * Repository-level write lock. Serializes the encode-and-upsert
     * sequence in {@link #save(AccountRecord)} and the
     * validate-encode-key-and-deleteByKey sequence in
     * {@link #delete(long)}. Held only across the smallest possible
     * critical section (a single port-method invocation); not
     * re-entrant; not exposed via a public accessor (which would tempt
     * callers to perform cross-method critical sections that this class
     * is not designed to support).
     */
    private final Object writeLock = new Object();

    /**
     * Convenience constructor delegating to the canonical
     * {@link #FileAccountRepository(Path, Charset)} constructor with
     * {@code IBM-1047} (EBCDIC) as the default codepage per AAP
     * &sect;0.6.5 ("The default codepage for EBCDIC-to-ASCII transcoding
     * is {@code Charset.forName(\"IBM-1047\")}"). Provided to preserve
     * source-code compatibility with the established codebase convention
     * used by {@code Read*DumpApp}, {@code PostTransactionsApp},
     * {@code InterestCalculationApp}, {@code CreateStatementsApp},
     * {@code OpenFileApp}, and {@code DefineAccountFileApp} composition
     * roots, which construct repositories with a single {@link Path}
     * argument and rely on the mandated EBCDIC default.
     *
     * <p>For an ASCII-encoded input file (e.g., the ASCII fixtures shipped
     * under {@code app/data/ASCII/}), use the canonical two-arg constructor
     * and pass {@link java.nio.charset.StandardCharsets#US_ASCII US-ASCII}
     * explicitly &mdash; or set the {@code carddemo.file.acctdata.charset}
     * property at the composition root, which the calling code then
     * resolves and forwards to the two-arg form.
     *
     * @param dataFile absolute filesystem path of the ACCTDATA file
     *                 (e.g., {@code ./data/acctdata.dat}); must not be
     *                 {@code null}. The file need NOT exist at construction
     *                 time; read methods handle missing files as NOTFND
     *                 parity ({@link Optional#empty()} or empty streams),
     *                 and write methods create the file on first call if
     *                 absent.
     * @throws NullPointerException if {@code dataFile} is {@code null}
     */
    public FileAccountRepository(Path dataFile) {
        this(dataFile, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs a new file-backed ACCTDATA adapter. The data file need
     * NOT exist at construction time; read methods handle missing files
     * as NOTFND parity ({@link Optional#empty()} or empty streams), and
     * write methods create the file on first call if absent.
     *
     * @param dataFile absolute filesystem path of the ACCTDATA file
     *                 (e.g., {@code ./data/acctdata.dat}); must not be
     *                 {@code null}
     * @param charset  the character set used for ASCII zoned-decimal key
     *                 formatting; defaults to {@code IBM-1047} per
     *                 AAP &sect;0.6.5; configurable via
     *                 {@code carddemo.file.acctdata.charset} in
     *                 {@code application.properties}. Note that the key
     *                 itself is encoded via
     *                 {@link StandardCharsets#US_ASCII US-ASCII} (see
     *                 {@link #formatKey(long)}) because {@code ACCT-ID}
     *                 in the ASCII fixture is unambiguously a zero-padded
     *                 ASCII decimal string; the {@code charset} parameter
     *                 is propagated to the underlying
     *                 {@link FixedWidthReader} and {@link FixedWidthWriter}
     *                 for symmetry and future EBCDIC compatibility.
     * @throws NullPointerException if any argument is {@code null}
     */
    public FileAccountRepository(Path dataFile, Charset charset) {
        this.dataFile = Objects.requireNonNull(dataFile, "dataFile");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(dataFile, RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(dataFile, RECORD_LENGTH, charset);
        LOG.debug("FileAccountRepository configured: dataFile={}, charset={}, recordLength={}",
                dataFile, charset, RECORD_LENGTH);
    }

    /**
     * Returns the configured data file path.
     *
     * @return the path to the ACCTDATA file; never {@code null}
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
    // AccountRepository port methods
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Translates the COBOL random reads against the {@code ACCTDATA}
     * dataset:
     * <ul>
     *   <li>Batch: {@code READ ACCOUNT-FILE INTO ACCOUNT-RECORD INVALID KEY ...}
     *       at {@code app/cbl/CBTRN02C.cbl:L395} and
     *       {@code app/cbl/CBACT04C.cbl:L373}.</li>
     *   <li>Online: {@code EXEC CICS READ DATASET(LIT-ACCTFILENAME) ...}
     *       at {@code app/cbl/COACTVWC.cbl:L776-L784} and
     *       {@code app/cbl/COBIL00C.cbl:L345-L354}.</li>
     * </ul>
     *
     * <h4>Implementation</h4>
     * <ol>
     *   <li>Validates {@code acctId} is in the {@code PIC 9(11)} range
     *       {@code [0, 99_999_999_999]} (fail-fast per AAP &sect;0.6.3
     *       canonical-constructor invariant on {@link AccountRecord}).</li>
     *   <li>Formats the key as an 11-byte zero-padded ASCII string via
     *       {@link #formatKey(long)}.</li>
     *   <li>Delegates to {@link FixedWidthReader#findByKey(byte[], int)}.</li>
     *   <li>Maps the returned byte buffer via
     *       {@link AccountRecord#parse(byte[])}.</li>
     * </ol>
     *
     * @throws IllegalArgumentException if {@code acctId} is outside the
     *         {@code PIC 9(11)} range {@code [0, 99_999_999_999]}
     * @throws UncheckedIOException     if the underlying file read fails
     */
    @Override
    public Optional<AccountRecord> findById(long acctId) {
        validateAcctId(acctId);
        byte[] keyBytes = formatKey(acctId);
        try {
            return reader.findByKey(keyBytes, KEY_OFFSET)
                    .map(AccountRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error reading ACCTDATA for acctId=" + acctId
                            + " from " + dataFile, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Translates the CBACT01C main loop
     * ({@code PERFORM UNTIL END-OF-FILE = 'Y' ... 1000-ACCTFILE-GET-NEXT ...
     * DISPLAY ACCOUNT-RECORD ...} at {@code app/cbl/CBACT01C.cbl:L74-L80})
     * against the {@code SELECT ACCTFILE-FILE ASSIGN TO ACCTFILE
     * ORGANIZATION IS INDEXED ACCESS MODE IS SEQUENTIAL RECORD KEY IS
     * FD-ACCT-ID} cursor at {@code app/cbl/CBACT01C.cbl:L29-L33}.
     *
     * <p>Also drives the CBACT04C interest-posting sequential scan
     * (iterates every account, computes interest from transaction-category
     * balances, then rewrites via {@link #save(AccountRecord)}).
     *
     * <h4>Resource lifecycle</h4>
     * The returned stream wraps an underlying
     * {@link java.nio.channels.SeekableByteChannel}; callers MUST close
     * the stream (typically via try-with-resources) to release the
     * channel. See {@link AccountRepository#streamSequential()} for the
     * full close contract.
     *
     * @throws UncheckedIOException if the underlying file open fails
     */
    @Override
    public Stream<AccountRecord> streamSequential() {
        try {
            return reader.streamSequential().map(AccountRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error opening ACCTDATA for sequential read: " + dataFile, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Translates the COBOL REWRITE/WRITE statements against the
     * {@code ACCTDATA} dataset, including the
     * {@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD INVALID KEY ...}
     * at {@code app/cbl/CBTRN02C.cbl:L554-L559} in paragraph
     * {@code 2800-UPDATE-ACCOUNT-REC} and the
     * {@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD} at
     * {@code app/cbl/CBACT04C.cbl:L356} in paragraph
     * {@code 1050-UPDATE-ACCOUNT}, as well as the online variants
     * {@code EXEC CICS REWRITE FILE(LIT-ACCTFILENAME) ...} at
     * {@code app/cbl/COACTUPC.cbl:L4065-L4071} and
     * {@code app/cbl/COBIL00C.cbl:L379-L385}, and the IDCAMS REPRO bulk
     * load from {@code app/data/ASCII/acctdata.txt}.
     *
     * <h4>Implementation</h4>
     * <ol>
     *   <li>Null-checks the record argument (fail-fast per port contract).</li>
     *   <li>Encodes the record via {@link AccountRecord#encode()} and
     *       defensively validates the resulting buffer length equals
     *       {@value #RECORD_LENGTH}; a mismatch would silently corrupt the
     *       file and is therefore promoted to an
     *       {@link IllegalStateException}.</li>
     *   <li>Formats the key from {@link AccountRecord#acctId()} via
     *       {@link #formatKey(long)}.</li>
     *   <li>Acquires {@link #writeLock} for the duration of the upsert so
     *       that the encode-and-write sequence is atomic with respect to
     *       other concurrent {@code save} or {@code delete} calls on this
     *       instance.</li>
     *   <li>Delegates to {@link FixedWidthWriter#upsert(byte[], int, byte[])}
     *       which performs the rewrite-if-exists else append (matching
     *       CICS READ-UPDATE / REWRITE and IDCAMS REPRO semantics).</li>
     * </ol>
     *
     * @throws NullPointerException  if {@code account} is {@code null}
     * @throws IllegalStateException if {@link AccountRecord#encode()}
     *                               returns a buffer whose length is not
     *                               exactly {@value #RECORD_LENGTH} bytes
     *                               (indicates a defect in
     *                               {@link AccountRecord})
     * @throws UncheckedIOException  if the underlying write fails
     */
    @Override
    public void save(AccountRecord account) {
        Objects.requireNonNull(account, "account");
        byte[] encoded = account.encode();
        // Defensive length assertion: AccountRecord.encode() is contracted
        // to always return exactly RECORD_LENGTH bytes (AAP §0.6.5
        // byte-for-byte round-trip invariant). A mismatch here would
        // indicate a defect in the domain record's encode implementation
        // and would silently corrupt the data file if not caught.
        if (encoded.length != RECORD_LENGTH) {
            throw new IllegalStateException(
                    "AccountRecord.encode() returned " + encoded.length
                            + " bytes; expected " + RECORD_LENGTH);
        }
        byte[] keyBytes = formatKey(account.acctId());
        synchronized (writeLock) {
            try {
                writer.upsert(keyBytes, KEY_OFFSET, encoded);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Error upserting ACCTDATA acctId=" + account.acctId()
                                + " to " + dataFile, e);
            }
        }
        LOG.debug("save acctId={} status=ok", account.acctId());
    }

    /**
     * {@inheritDoc}
     *
     * <p>No active COBOL program issues {@code EXEC CICS DELETE} against
     * {@code ACCTDATA} at runtime; this operation is provided for adapter
     * completeness and to support the IDCAMS {@code DELETE} / {@code DEFINE}
     * cycle exercised by {@code app/jcl/ACCTFILE.jcl} during dataset
     * (re-)load, as well as fixture reset in the golden-record harness
     * (AAP &sect;0.6.11).
     *
     * <h4>Not-found semantics</h4>
     * If no account exists with the supplied key, throws
     * {@link NoSuchElementException} &mdash; mirroring the COBOL
     * {@code DELETE} with {@code INVALID KEY} branch (FILE STATUS '23';
     * NOTFND is not a silent no-op).
     *
     * <h4>Implementation</h4>
     * <ol>
     *   <li>Validates {@code acctId} is in the {@code PIC 9(11)} range
     *       {@code [0, 99_999_999_999]}.</li>
     *   <li>Formats the key via {@link #formatKey(long)}.</li>
     *   <li>Acquires {@link #writeLock} for the duration of the delete so
     *       that the validate-encode-and-delete sequence is atomic with
     *       respect to other concurrent {@code save} or {@code delete}
     *       calls on this instance.</li>
     *   <li>Delegates to {@link FixedWidthWriter#deleteByKey(byte[], int)};
     *       if the returned boolean is {@code false} (no match), throws
     *       {@link NoSuchElementException} (NOTFND parity).</li>
     * </ol>
     *
     * @throws IllegalArgumentException if {@code acctId} is outside the
     *                                  {@code PIC 9(11)} range
     *                                  {@code [0, 99_999_999_999]}
     * @throws NoSuchElementException   if no record exists with the
     *                                  supplied {@code acctId}
     * @throws UncheckedIOException     if the underlying delete fails
     */
    @Override
    public void delete(long acctId) {
        validateAcctId(acctId);
        byte[] keyBytes = formatKey(acctId);
        synchronized (writeLock) {
            try {
                boolean removed = writer.deleteByKey(keyBytes, KEY_OFFSET);
                if (!removed) {
                    throw new NoSuchElementException(
                            "No ACCTDATA record found for acctId=" + acctId);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Error deleting ACCTDATA acctId=" + acctId
                                + " from " + dataFile, e);
            }
        }
        LOG.debug("delete acctId={} status=ok", acctId);
    }

    // ------------------------------------------------------------------
    // AutoCloseable — required by AccountRepository extending AutoCloseable
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
     * {@link AccountRepository#close()} contract.
     *
     * <p>Corresponds to the COBOL {@code CLOSE ACCTFILE-FILE} verb in the
     * {@code 9000-ACCTFILE-CLOSE} paragraph of CBACT01C
     * ({@code app/cbl/CBACT01C.cbl:L151-L167}) and the equivalent
     * {@code CLOSE} statements at the end of CBACT04C, CBTRN01C, and
     * CBTRN02C.
     */
    @Override
    public void close() {
        // No long-lived resources to release.
        // FixedWidthReader and FixedWidthWriter manage their own channel
        // lifecycle per operation; nothing to close at the repository
        // level. Method present to satisfy the AutoCloseable / port
        // contract and to allow future adapter implementations to add
        // resource cleanup without changing the call sites.
        LOG.debug("FileAccountRepository closed (no-op): dataFile={}", dataFile);
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /**
     * Validates an {@code ACCT-ID} value against the COBOL
     * {@code PIC 9(11)} domain {@code [0, 99_999_999_999]}.
     *
     * <p>This is the fail-fast diagnostic at the
     * {@link #findById(long)} / {@link #delete(long)} boundary mentioned
     * in {@link AccountRepository#findById(long)}'s parameter contract.
     * It mirrors the canonical-constructor invariant on
     * {@link AccountRecord} (AAP &sect;0.6.3, JEP 513 Flexible Constructor
     * Bodies): out-of-range values cannot describe a real account in the
     * COBOL domain.
     *
     * @param acctId the candidate {@code ACCT-ID} value
     * @throws IllegalArgumentException if {@code acctId} is outside
     *                                  {@code [0, 99_999_999_999]}
     */
    private static void validateAcctId(long acctId) {
        if (acctId < 0L || acctId > ACCT_ID_MAX) {
            throw new IllegalArgumentException(
                    "acctId out of PIC 9(11) range [0..99_999_999_999]: "
                            + acctId);
        }
    }

    /**
     * Encodes a numeric {@code ACCT-ID} as an {@value #KEY_LENGTH}-byte
     * ASCII zero-left-padded key matching the COBOL {@code PIC 9(11)}
     * field representation (e.g., the value {@code 12345L} renders as the
     * 11-byte ASCII buffer {@code "00000012345"}).
     *
     * <p>The encoding is unconditionally ASCII regardless of the
     * configured {@link #charset}, because the {@code ACCT-ID} field in
     * {@code app/data/ASCII/acctdata.txt} is a zoned-decimal value
     * ({@code USAGE DISPLAY} with no implicit-decimal scale on a
     * non-negative {@code PIC 9(11)}), which is unambiguously ASCII
     * digits 0&ndash;9 (bytes 0x30&ndash;0x39) in both ASCII and
     * IBM-1047 EBCDIC representations would diverge (EBCDIC digits are
     * 0xF0&ndash;0xF9). For source-byte fidelity against the shipped
     * ASCII fixtures, the key is encoded via
     * {@link StandardCharsets#US_ASCII US-ASCII} explicitly. Callers
     * working with EBCDIC-sourced files should pre-transcode their input
     * via {@link EbcdicTranscoder} before invoking this adapter, so the
     * persisted bytes are uniformly ASCII at the adapter boundary.
     *
     * @param acctId the non-negative account id (must fit in 11 digits;
     *               i.e., {@code [0, 99_999_999_999]})
     * @return an {@value #KEY_LENGTH}-byte ASCII buffer (e.g.,
     *         {@code "00000012345"})
     */
    private static byte[] formatKey(long acctId) {
        return String.format(Locale.ROOT, "%011d", acctId)
                .getBytes(StandardCharsets.US_ASCII);
    }
}
