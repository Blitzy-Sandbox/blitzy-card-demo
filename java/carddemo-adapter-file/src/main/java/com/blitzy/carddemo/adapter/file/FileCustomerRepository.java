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
import com.blitzy.carddemo.domain.port.CustomerRepository;
import com.blitzy.carddemo.domain.record.CustomerRecord;

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
 * File-backed implementation of {@link CustomerRepository} that reads and
 * writes the {@code CUSTDATA} VSAM KSDS dataset as a fixed-width binary file
 * via {@link java.nio.file}.
 *
 * <h2>Source artefact</h2>
 * Implements the access path for the {@code CUSTOMER-RECORD} 01-level group
 * defined in {@code app/cpy/CVCUS01Y.cpy} (500-byte fixed-width record,
 * 9-digit zoned-decimal {@code CUST-ID PIC 9(09)} primary key at offset 0).
 * The COBOL programs that consume this dataset are:
 * <ul>
 *   <li><b>CBCUS01C</b> ({@code app/cbl/CBCUS01C.cbl}) &mdash; batch
 *       sequential dump of the customer master via paragraph
 *       {@code 1000-CUSTFILE-GET-NEXT} driven by
 *       {@code ORGANIZATION IS INDEXED ACCESS MODE IS SEQUENTIAL
 *       RECORD KEY IS FD-CUST-ID}
 *       ({@code app/cbl/CBCUS01C.cbl:L29-L33}).</li>
 *   <li><b>COACTVWC</b> ({@code app/cbl/COACTVWC.cbl}) &mdash; online
 *       account-view transaction. Performs a random keyed read via
 *       {@code EXEC CICS READ DATASET(LIT-CUSTFILENAME)
 *       INTO(CUSTOMER-RECORD)} ({@code app/cbl/COACTVWC.cbl:L827-L831}).</li>
 *   <li><b>COACTUPC</b> ({@code app/cbl/COACTUPC.cbl}) &mdash; online
 *       account-update transaction. Reads the record (paragraph
 *       {@code 9700-CHECK-CHANGE-IN-REC} at
 *       {@code app/cbl/COACTUPC.cbl:L4109-L4193}) and later issues
 *       {@code EXEC CICS REWRITE FILE(LIT-CUSTFILENAME)
 *       FROM(CUST-UPDATE-RECORD)} ({@code app/cbl/COACTUPC.cbl:L4086}).
 *       This is the second leg of the only {@code SYNCPOINT ROLLBACK}
 *       transaction in the COBOL source ({@code app/cbl/COACTUPC.cbl:L4100});
 *       transactional atomicity is reconstructed at the application layer
 *       in {@code CoActUpC} via try/finally and compensating writes (per
 *       AAP &sect;0.1.2 and the IMPLEMENTATION DECISION logged in
 *       {@code MIGRATION_NOTES.md}). This adapter deliberately does
 *       <em>not</em> model transactional boundaries.</li>
 * </ul>
 *
 * <h2>Record layout</h2>
 * Each record is exactly {@value #RECORD_LENGTH} bytes; the 9-byte
 * {@code CUST-ID} field is the KSDS primary key at offset 0. See
 * {@link CustomerRecord} for the complete byte-by-byte layout, including the
 * 168-byte trailing {@code FILLER} preserved verbatim per AAP &sect;0.6.5
 * byte-for-byte fidelity.
 *
 * <h2>Adapter responsibilities</h2>
 * <ul>
 *   <li>Translate {@link CustomerRecord} domain objects to and from the
 *       500-byte fixed-width byte image via
 *       {@link CustomerRecord#parse(byte[])} and
 *       {@link CustomerRecord#encode()}.</li>
 *   <li>Look up by 9-digit customer ID via {@link FixedWidthReader#findByKey}
 *       (linear scan over the file).</li>
 *   <li>Stream all records in physical (KSDS key) order via
 *       {@link FixedWidthReader#streamSequential()}.</li>
 *   <li>Persist customer updates via {@link FixedWidthWriter#upsert} (the
 *       hexagonal-port {@link CustomerRepository#save(CustomerRecord)}
 *       method is upsert-by-key semantics, REWRITE-if-exists else
 *       INSERT-in-key-order).</li>
 *   <li>Delete records via {@link FixedWidthWriter#deleteByKey}; throws
 *       {@link NoSuchElementException} when the key is absent (a stricter
 *       contract than the COBOL {@code DELETE} verb's silent
 *       {@code FILE STATUS = '23'}).</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 * Save and delete operations are serialised on an instance-private
 * {@link #writeLock} monitor for the entire encode + upsert/delete sequence,
 * ensuring that concurrent application-layer callers (including virtual
 * threads fanned out per AAP &sect;0.6.6) cannot interleave write
 * operations and corrupt the underlying file. The underlying
 * {@link FixedWidthReader} and {@link FixedWidthWriter} maintain their own
 * file-channel-level synchronisation; this adapter's outer lock guarantees
 * that the logical save/delete sequence appears atomic to callers. The lock
 * is NOT re-entrant and is NOT exposed; cross-method critical sections must
 * be coordinated by the caller via the
 * {@code SYNCPOINT}-equivalent try/finally pattern in {@code CoActUpC}
 * (see AAP &sect;0.6.8).
 *
 * <h2>168-byte FILLER preservation</h2>
 * Per AAP &sect;0.6.5 byte-for-byte fidelity: every {@code save()} call
 * persists the record's 168-byte trailing FILLER verbatim through
 * {@link CustomerRecord#encode()}. The byte-for-byte round-trip invariant
 * {@code CustomerRecord.parse(record.encode()).equals(record)} holds for
 * every well-formed record, and that invariant extends through this
 * adapter's I/O paths.
 *
 * <h2>DEFCUST.jcl note (AAP &sect;0.4.1)</h2>
 * The companion job {@code app/jcl/DEFCUST.jcl} contains a delete/define
 * dataset-name mismatch that is flagged as a suspected JCL defect in
 * {@code MIGRATION_NOTES.md} per AAP &sect;0.7.1 (faithful translation).
 * That defect is preserved by the JCL translation in
 * {@code DefineCustomerFileApp.java} and does <em>not</em> affect this
 * adapter's port contract.
 *
 * <h2>PII handling</h2>
 * Per AAP &sect;0.7.2, {@link CustomerRecord#toString()} masks the SSN,
 * government-issued ID, date of birth, EFT account ID, and address
 * components so an accidental {@code log.info("{}", customer)} cannot leak
 * Personally Identifiable Information. This adapter logs only the
 * {@code custId} (a non-confidential 9-digit primary key) at
 * {@link Logger#debug DEBUG} level &mdash; never the full record contents.
 *
 * <h2>Architectural constraints</h2>
 * Per AAP &sect;0.6.5 and &sect;0.7.4 this adapter uses only
 * {@link java.nio.file}; {@link java.io.File}, {@code RandomAccessFile},
 * Spring framework, Lombok, {@code ThreadLocal}, {@code double}/{@code float}
 * for monetary values, and JVM {@code --enable-preview} are explicitly
 * FORBIDDEN.
 *
 * @see CustomerRepository
 * @see CustomerRecord
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @see EbcdicTranscoder
 * @since 1.0.0
 */
@CobolProgram(
        value = "CUSTDATA",
        sourcePath = "app/cpy/CVCUS01Y.cpy",
        translationDate = "2025-10-24",
        notes = "VSAM KSDS, 500-byte records, 9-byte CUST-ID key. "
                + "See CBCUS01C (sequential), COACTVWC (view), COACTUPC "
                + "9700-CHECK-CHANGE-IN-REC (read + REWRITE within SYNCPOINT). "
                + "DEFCUST.jcl has dataset-name mismatch flagged in "
                + "MIGRATION_NOTES.md."
)
public final class FileCustomerRepository implements CustomerRepository {

    /**
     * SLF4J logger. DEBUG-level emissions document save/delete completions
     * with custId only; no PII (SSN, DOB, address, govt-id, EFT account)
     * ever appears in log lines per AAP &sect;0.7.2.
     */
    private static final Logger LOG = LoggerFactory.getLogger(FileCustomerRepository.class);

    // ========================================================================
    // Layout constants — mirror CustomerRecord (app/cpy/CVCUS01Y.cpy).
    // Held locally as private static finals (per the agent_prompt Phase 4
    // specification) so this adapter is self-documenting about the byte
    // contract it implements. The values intentionally match
    // CustomerRecord.RECORD_LENGTH / CUST_ID_OFFSET / CUST_ID_LENGTH; a
    // package-private static initializer guard could be added if drift were
    // ever observed, but the constants in CustomerRecord are themselves
    // derived from the same copybook, so consistency is structural rather
    // than enforced at runtime.
    // ========================================================================

    /** Fixed-width record length in bytes per {@code app/cpy/CVCUS01Y.cpy}. */
    private static final int RECORD_LENGTH = 500;

    /** Offset of the {@code CUST-ID PIC 9(09)} primary key within each record. */
    private static final int KEY_OFFSET = 0;

    /** Length of the {@code CUST-ID PIC 9(09)} primary key in bytes (9 ASCII digits). */
    private static final int KEY_LENGTH = 9;

    /**
     * Inclusive upper bound for a {@code CUST-ID PIC 9(09)} value. The 9-digit
     * unsigned zoned-decimal field can represent values in the range
     * {@code 0..999_999_999L}; supplying any value outside this range to
     * {@link #findById(long)} or {@link #delete(long)} would require more
     * than 9 ASCII digits to format and therefore cannot be a valid key.
     */
    private static final long MAX_CUST_ID = 999_999_999L;

    /**
     * Configuration key used by {@link EbcdicTranscoder} to look up the
     * per-file codepage override for the CUSTDATA dataset. The default
     * value (when no override is set) is
     * {@link EbcdicTranscoder#DEFAULT_CHARSET_NAME IBM-1047} per AAP &sect;0.6.5.
     */
    public static final String DATASET_KEY = "custdata";

    /**
     * Path to the CUSTDATA dataset. Held as {@link Path} (per AAP &sect;0.6.5
     * mandate to avoid {@link java.io.File}); supplied by the composition
     * root via {@code carddemo.file.custdata.path} configuration.
     */
    private final Path dataFile;

    /**
     * Charset used to encode the CUST-ID key bytes that the underlying
     * {@link FixedWidthReader} / {@link FixedWidthWriter} compare against
     * the on-disk record bytes at {@link #KEY_OFFSET}. Defaults to
     * {@code IBM-1047} when constructed via the convenience
     * {@link #FileCustomerRepository(Path)} overload; explicitly set when
     * constructed via the primary {@link #FileCustomerRepository(Path, Charset)}
     * constructor. For the ASCII fixtures in {@code app/data/ASCII/} the
     * composition root supplies {@code US-ASCII} via
     * {@code carddemo.file.custdata.charset}.
     */
    private final Charset charset;

    /**
     * Underlying byte-level reader used by {@link #findById(long)} and
     * {@link #streamSequential()}. Configured with this adapter's
     * {@link #dataFile}, {@link #RECORD_LENGTH}, and {@link #charset}.
     */
    private final FixedWidthReader reader;

    /**
     * Underlying byte-level writer used by {@link #save(CustomerRecord)} and
     * {@link #delete(long)}. Configured with this adapter's {@link #dataFile},
     * {@link #RECORD_LENGTH}, and {@link #charset}.
     */
    private final FixedWidthWriter writer;

    /**
     * Instance-private mutex serialising the encode + upsert sequence in
     * {@link #save(CustomerRecord)} and the deleteByKey sequence in
     * {@link #delete(long)} so that concurrent virtual-thread callers cannot
     * interleave logical write operations. The underlying
     * {@link FixedWidthWriter} maintains its own channel-level lock; this
     * outer lock guarantees logical atomicity of the encode + write step
     * (the writer's lock alone would not prevent two callers from racing
     * between {@link CustomerRecord#encode()} and
     * {@link FixedWidthWriter#upsert(byte[], int, byte[])}).
     *
     * <p>Held only across the smallest possible critical section of each
     * mutating method, and is NOT re-entrant. Initialised eagerly at
     * construction so every instance has a unique monitor (ruling out the
     * cross-instance aliasing that would occur with a class-level static).
     */
    private final Object writeLock = new Object();

    /**
     * Convenience constructor: defaults to the IBM-1047 EBCDIC codepage per
     * AAP &sect;0.6.5 (the production z/OS codepage). The composition root
     * uses this overload when no explicit codepage override is configured
     * via {@code carddemo.file.custdata.charset}.
     *
     * @param dataFile the absolute or relative path to the CUSTDATA file;
     *                 must be non-{@code null}
     * @throws NullPointerException if {@code dataFile} is {@code null}
     */
    public FileCustomerRepository(Path dataFile) {
        this(dataFile, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Primary constructor: explicit charset selection.
     *
     * @param dataFile the absolute or relative path to the CUSTDATA file;
     *                 must be non-{@code null}
     * @param charset  the codepage for key-byte encoding; must be
     *                 non-{@code null}. For the ASCII fixtures under
     *                 {@code app/data/ASCII/}, supply
     *                 {@link java.nio.charset.StandardCharsets#US_ASCII}.
     *                 For production EBCDIC files, supply
     *                 {@code Charset.forName("IBM-1047")}.
     * @throws NullPointerException if any argument is {@code null}
     */
    public FileCustomerRepository(Path dataFile, Charset charset) {
        this.dataFile = Objects.requireNonNull(dataFile, "dataFile");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(dataFile, RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(dataFile, RECORD_LENGTH, charset);
        LOG.debug("FileCustomerRepository configured: dataFile={}, charset={}, recordLength={}",
                dataFile, charset, RECORD_LENGTH);
    }

    /**
     * The configured data file path.
     *
     * @return the CUSTDATA file path; never {@code null}
     */
    public Path file() {
        return dataFile;
    }

    /**
     * The configured charset.
     *
     * @return the charset; never {@code null}
     */
    public Charset charset() {
        return charset;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Encodes {@code custId} as a 9-byte ASCII zoned-decimal key and
     * delegates to {@link FixedWidthReader#findByKey(byte[], int)}. Maps
     * COBOL/CICS response codes to the {@link Optional} return value:
     * a {@code NORMAL} response yields a non-empty {@link Optional} and a
     * {@code NOTFND} response (or missing file) yields
     * {@link Optional#empty()}.
     *
     * @throws IllegalArgumentException if {@code custId} is outside the
     *                                  inclusive {@code 0..999_999_999L}
     *                                  range required by
     *                                  {@code CUST-ID PIC 9(09)}
     * @throws UncheckedIOException     wrapping any {@link IOException} from
     *                                  the underlying file channel (e.g., a
     *                                  truncated record at EOF)
     */
    @Override
    public Optional<CustomerRecord> findById(long custId) {
        validateCustId(custId);
        byte[] keyBytes = formatKey(custId);
        try {
            return reader.findByKey(keyBytes, KEY_OFFSET).map(CustomerRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error reading CUSTDATA for custId=" + custId
                            + " from " + dataFile, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Opens a lazy {@link Stream} over the CUSTDATA dataset in ascending
     * physical (KSDS key) order. The caller MUST close the returned stream
     * via try-with-resources to release the underlying file channel
     * (failing to do so leaks an OS file handle).
     *
     * <p>Per AAP &sect;0.6.6 the stream is intentionally sequential
     * (non-parallel); virtual-thread fan-out by downstream callers is
     * permitted only when reordering would not change observable output.
     *
     * @throws UncheckedIOException wrapping any {@link IOException} from
     *                              the underlying channel open
     */
    @Override
    public Stream<CustomerRecord> streamSequential() {
        try {
            return reader.streamSequential().map(CustomerRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error opening CUSTDATA for sequential read: " + dataFile, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Encodes the customer record to its 500-byte fixed-width buffer,
     * validates the encoded length, then upserts (REWRITE-if-exists else
     * INSERT-in-key-order) under {@link #writeLock}. The 168-byte trailing
     * {@code FILLER} of the {@code CUSTOMER-RECORD} is preserved verbatim
     * by {@link CustomerRecord#encode()}.
     *
     * <p>Corresponds to {@code EXEC CICS REWRITE FILE(LIT-CUSTFILENAME)
     * FROM(CUST-UPDATE-RECORD)} in COACTUPC
     * ({@code app/cbl/COACTUPC.cbl:L4086}) and to {@code WRITE} or IDCAMS
     * {@code REPRO} loads from {@code app/jcl/CUSTFILE.jcl}.
     *
     * @throws NullPointerException  if {@code customer} is {@code null}
     * @throws IllegalStateException if {@code customer.encode()} returns a
     *                               buffer of unexpected length (an invariant
     *                               violation of the byte-fidelity contract)
     * @throws UncheckedIOException  wrapping any {@link IOException} from the
     *                               underlying writer
     */
    @Override
    public void save(CustomerRecord customer) {
        Objects.requireNonNull(customer, "customer");
        byte[] encoded = customer.encode();
        if (encoded.length != RECORD_LENGTH) {
            throw new IllegalStateException(
                    "CustomerRecord.encode() returned " + encoded.length
                            + " bytes; expected " + RECORD_LENGTH);
        }
        byte[] keyBytes = formatKey(customer.custId());
        synchronized (writeLock) {
            try {
                writer.upsert(keyBytes, KEY_OFFSET, encoded);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Error upserting CUSTDATA custId=" + customer.custId()
                                + " to " + dataFile, e);
            }
        }
        LOG.debug("save custId={} status=ok dataFile={}", customer.custId(), dataFile);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Encodes {@code custId} as a 9-byte ASCII zoned-decimal key and
     * delegates to {@link FixedWidthWriter#deleteByKey(byte[], int)} under
     * {@link #writeLock}. If the key is absent, throws
     * {@link NoSuchElementException} per the
     * {@link CustomerRepository#delete(long)} contract (a deliberately
     * stricter contract than the COBOL {@code DELETE} verb's silent
     * {@code FILE STATUS = '23'} so JCL-time defects surface earlier).
     *
     * @throws IllegalArgumentException if {@code custId} is outside the
     *                                  inclusive {@code 0..999_999_999L}
     *                                  range required by
     *                                  {@code CUST-ID PIC 9(09)}
     * @throws NoSuchElementException   if no record with the given
     *                                  {@code custId} exists
     * @throws UncheckedIOException     wrapping any {@link IOException} from
     *                                  the underlying writer
     */
    @Override
    public void delete(long custId) {
        validateCustId(custId);
        byte[] keyBytes = formatKey(custId);
        synchronized (writeLock) {
            try {
                boolean removed = writer.deleteByKey(keyBytes, KEY_OFFSET);
                if (!removed) {
                    throw new NoSuchElementException(
                            "No CUSTDATA record found for custId=" + custId);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Error deleting CUSTDATA custId=" + custId
                                + " from " + dataFile, e);
            }
        }
        LOG.debug("delete custId={} status=ok dataFile={}", custId, dataFile);
    }

    /**
     * {@inheritDoc}
     *
     * <p>This adapter holds no long-lived resources: each operation opens
     * its own {@link java.nio.channels.SeekableByteChannel} via the
     * underlying {@link FixedWidthReader} / {@link FixedWidthWriter} and
     * closes it deterministically through try-with-resources or stream
     * {@code onClose} hooks. {@code close()} is therefore a no-op but is
     * provided to satisfy the {@link AutoCloseable} contract inherited by
     * {@link CustomerRepository} so this adapter remains ergonomic in
     * try-with-resources blocks (mirroring the COBOL
     * {@code 9000-CUSTFILE-CLOSE} call site in
     * {@code app/cbl/CBCUS01C.cbl}).
     */
    @Override
    public void close() {
        LOG.debug("FileCustomerRepository closed (no-op): dataFile={}", dataFile);
    }

    // ------------------------------------------------------------------
    //  Private helpers
    // ------------------------------------------------------------------

    /**
     * Validates that {@code custId} is within the
     * {@code CUST-ID PIC 9(09)} value space (inclusive
     * {@code 0..999_999_999L}). Any value outside this range cannot be
     * represented as a valid 9-digit ASCII zoned-decimal key and is
     * therefore rejected upfront with a clear diagnostic.
     *
     * @param custId the candidate key value
     * @throws IllegalArgumentException if {@code custId < 0} or
     *                                  {@code custId > 999_999_999}
     */
    private static void validateCustId(long custId) {
        if (custId < 0L || custId > MAX_CUST_ID) {
            throw new IllegalArgumentException(
                    "custId out of PIC 9(09) range [0..999_999_999]: " + custId);
        }
    }

    /**
     * Formats {@code custId} as a 9-byte zoned-decimal key in the
     * adapter's configured {@link #charset}. The {@code %09d} format
     * specifier produces 9 zero-left-padded ASCII digits; the
     * {@link String#getBytes(Charset) getBytes(charset)} call then
     * transcodes those digits into the configured codepage so the resulting
     * key matches the bytes at {@link #KEY_OFFSET} in records of the same
     * codepage.
     *
     * <p>This intentionally honours the adapter's configured charset (the
     * composition root supplies {@code US-ASCII} for the
     * {@code app/data/ASCII/} fixtures and {@code IBM-1047} for production
     * EBCDIC files) so the key encoding is consistent with the record
     * encoding at the byte level. The output buffer is always
     * {@value #KEY_LENGTH} bytes for both ASCII and EBCDIC because both
     * codepages encode each Western Arabic digit in a single byte.
     *
     * @param custId the non-negative 9-digit customer ID
     * @return a {@value #KEY_LENGTH}-byte key buffer
     */
    private byte[] formatKey(long custId) {
        return String.format("%09d", custId).getBytes(charset);
    }
}
