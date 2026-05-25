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
import com.blitzy.carddemo.domain.port.DiscountGroupRepository;
import com.blitzy.carddemo.domain.record.DisGroupRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed adapter implementation of {@link DiscountGroupRepository} that
 * reads and writes 50-byte fixed-width {@code DIS-GROUP-RECORD} entries
 * keyed by a 16-byte composite key
 * ({@code DIS-ACCT-GROUP-ID PIC X(10)} +
 * {@code DIS-TRAN-TYPE-CD PIC X(02)} +
 * {@code DIS-TRAN-CAT-CD PIC 9(04)}) from the {@code DISCGRP} VSAM KSDS
 * dataset.
 *
 * <h2>Authority and source lineage</h2>
 * <ul>
 *   <li><b>AAP &sect;0.1.1, &sect;0.3.1</b> &mdash; file-based batch
 *       processing by default (no PostgreSQL); this adapter is the
 *       default outer-ring repository implementation for
 *       {@code DISCGRP} per the hexagonal architecture diagram at
 *       AAP &sect;0.3.6.</li>
 *   <li><b>AAP &sect;0.6.5</b> &mdash; file I/O exactness. This
 *       adapter uses only {@link java.nio.file} primitives delegated
 *       through {@link FixedWidthReader} and {@link FixedWidthWriter};
 *       the {@code java.io.File} class is FORBIDDEN.</li>
 *   <li><b>AAP &sect;0.4.1</b> (transformation map) &mdash;
 *       {@code FileDiscountGroupRepository} translates the COBOL
 *       VSAM KSDS access patterns demonstrated in:
 *       <ul>
 *         <li>{@code app/cpy/CVTRA02Y.cpy} &mdash; 50-byte
 *             {@code DIS-GROUP-RECORD} layout with 16-byte
 *             composite {@code DIS-GROUP-KEY} primary key at
 *             offset 0
 *             ({@code app/cpy/CVTRA02Y.cpy:L4-L10}).</li>
 *         <li>{@code app/cbl/CBACT04C.cbl} paragraph
 *             {@code 1200-GET-INTEREST-RATE}
 *             ({@code app/cbl/CBACT04C.cbl:L415-L420}) &mdash; random
 *             read by 16-byte composite key. The COBOL idiom
 *             {@code READ DISCGRP-FILE INTO DIS-GROUP-RECORD
 *             INVALID KEY ...} maps to
 *             {@link #findByKey(String, String, int)}; the
 *             {@code INVALID KEY} branch (FILE STATUS {@code '23'})
 *             surfaces as {@link Optional#empty()}.</li>
 *         <li>{@code app/cbl/CBACT04C.cbl} paragraph
 *             {@code 1200-A-GET-DEFAULT-INT-RATE}
 *             ({@code app/cbl/CBACT04C.cbl:L443-L460}) &mdash; the
 *             COBOL fallback path overwrites the
 *             {@code FD-DIS-ACCT-GROUP-ID} component with the literal
 *             {@code 'DEFAULT'} and re-issues a second random
 *             {@code READ}. The fallback policy is implemented in
 *             {@code com.blitzy.carddemo.application.account.CbAct04C}
 *             at the application layer, NOT in this port adapter
 *             (see Phase 12 Key Insight #2 of the agent prompt and
 *             AAP &sect;0.6.10 single-responsibility for ports).</li>
 *         <li>{@code app/jcl/DISCGRP.jcl} &mdash; IDCAMS
 *             {@code DELETE CLUSTER}, {@code DEFINE CLUSTER}, and
 *             {@code REPRO} initial seed-load job; loads the rate
 *             table from {@code app/data/ASCII/discgrp.txt}. Maps to
 *             {@link #delete(String, String, int)} (per-record
 *             delete semantics) and {@link #save(DisGroupRecord)}
 *             (REPRO inserts).</li>
 *       </ul></li>
 * </ul>
 *
 * <h2>Byte-for-byte fidelity</h2>
 * <p>This adapter delegates all field-level serialization to
 * {@link DisGroupRecord#parse(byte[])} and
 * {@link DisGroupRecord#encode()}, preserving the trailing 28-byte
 * FILLER verbatim. Re-encoding a parsed record MUST produce a
 * byte-identical buffer (AAP &sect;0.6.5 round-trip invariant). The
 * {@link #save(DisGroupRecord)} method asserts the encoded length
 * equals {@link #RECORD_LENGTH} as a defense-in-depth check.
 *
 * <h2>Composite key encoding</h2>
 * The {@link #buildKey(String, String, int)} helper assembles the
 * 16-byte composite key via a {@link ByteBuffer} of exactly
 * {@link #KEY_LENGTH} bytes:
 * <pre>{@code
 *   DIS-ACCT-GROUP-ID  10 bytes  right-space-padded (PIC X(10))
 *   DIS-TRAN-TYPE-CD    2 bytes  right-space-padded (PIC X(02))
 *   DIS-TRAN-CAT-CD     4 bytes  zero-left-padded   (PIC 9(04))
 * }</pre>
 * Per AAP &sect;0.6.5 the {@code String.getBytes(Charset)} call uses
 * the configured {@link Charset} (default {@code IBM-1047} EBCDIC at
 * the composition root; overridable per dataset via
 * {@code carddemo.file.discgrp.charset} in
 * {@code application.properties}). The same {@code charset} is also
 * forwarded to the underlying {@link FixedWidthReader} and
 * {@link FixedWidthWriter} so that key bytes round-trip consistently
 * with file bytes.
 *
 * <h2>DEFAULT fallback policy &mdash; application-layer, NOT here</h2>
 * The COBOL {@code 1200-A-GET-DEFAULT-INT-RATE} fallback to the
 * literal {@code 'DEFAULT'} account-group is intentionally NOT
 * replicated in this adapter. The port faithfully returns
 * {@link Optional#empty()} for the first miss, and
 * {@code com.blitzy.carddemo.application.account.CbAct04C} (the
 * translated CBACT04C application class) decides whether to retry
 * with {@code accountGroupId = "DEFAULT"} or to ABEND. Adapter
 * implementations MUST NOT silently retry the lookup with
 * {@code "DEFAULT"} on a miss; doing so would mask the missed key
 * from observability and the application class would have no
 * opportunity to apply the COBOL-faithful logging
 * ({@code DISPLAY 'DISCLOSURE GROUP RECORD MISSING'} and
 * {@code DISPLAY 'TRY WITH DEFAULT GROUP CODE'} at
 * {@code app/cbl/CBACT04C.cbl:L418-L419}). This is Phase 12 Key
 * Insight #2 of the agent prompt.
 *
 * <h2>Decimal-rate semantics (DIS-INT-RATE)</h2>
 * The {@code DIS-INT-RATE PIC S9(04)V99} field carried inside each
 * {@link DisGroupRecord} is the monthly periodic interest rate as a
 * percentage with two implicit decimal places (scale 2). The COBOL
 * interest computation in {@code 1300-COMPUTE-INTEREST} is
 * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) /
 * 1200} at {@code app/cbl/CBACT04C.cbl:L464-L465}. The statement
 * carries no {@code ROUNDED} clause, so per AAP &sect;0.6.1 the Java
 * translation applies {@link java.math.RoundingMode#DOWN} (truncation)
 * &mdash; NOT banker's rounding. The rounding policy is enforced by
 * the application-layer use case ({@code CbAct04C}); this adapter
 * simply surfaces the {@link DisGroupRecord} whose
 * {@link DisGroupRecord#disIntRate()} is already normalised to
 * scale 2 by the {@link DisGroupRecord} canonical constructor.
 *
 * <h2>Concurrency &mdash; class-owned write lock</h2>
 * Write operations ({@link #save(DisGroupRecord)} and
 * {@link #delete(String, String, int)}) are serialised through an
 * instance-private {@link #writeLock}. The lock is held only across
 * the smallest possible critical section (a single port-method
 * invocation); it is NOT re-entrant and is NOT exposed via a public
 * accessor. The underlying {@link FixedWidthWriter} also has its own
 * internal write lock; the two-level locking is intentional for
 * defense in depth and matches the established sibling
 * {@code File*Repository} pattern (e.g.,
 * {@code FileTransactionCategoryBalanceRepository},
 * {@code FileTransactionRepository}).
 *
 * <h2>Read-mostly access pattern</h2>
 * The {@code DISCGRP} dataset is small (a single-digit number of rows
 * in the reference fixture {@code app/data/ASCII/discgrp.txt}) and is
 * accessed READ-only at runtime by translated application use cases.
 * No translated COBOL paragraph issues a runtime {@code WRITE} or
 * {@code REWRITE} against {@code DISCGRP} &mdash; the only mutation
 * paths are the IDCAMS {@code REPRO} seed-load and {@code DELETE
 * CLUSTER} / re-{@code DEFINE} cycle declared in
 * {@code app/jcl/DISCGRP.jcl}. {@link #save(DisGroupRecord)} and
 * {@link #delete(String, String, int)} therefore exist exclusively
 * to support those JCL-step translations and are not invoked during
 * normal interest-calculation runs.
 *
 * <h2>Resource ownership</h2>
 * The underlying {@link FixedWidthReader} and {@link FixedWidthWriter}
 * open a fresh {@link java.nio.channels.SeekableByteChannel} per
 * operation and close it deterministically (via try-with-resources
 * in the foundational primitives, or via the {@code Stream.onClose}
 * hook for {@link #streamSequential()}). The {@link #close()} method
 * is consequently a no-op DEBUG-log statement. Callers of
 * {@link #streamSequential()} MUST close the returned {@link Stream}
 * via try-with-resources to release the underlying channel.
 *
 * <h2>Architectural constraints</h2>
 * <ul>
 *   <li>{@code java.nio.file} exclusively; {@code java.io.File},
 *       {@code RandomAccessFile}, {@code FileInputStream}, and
 *       {@code FileOutputStream} are FORBIDDEN per AAP &sect;0.6.5
 *       and the agent prompt Phase 10 validation checklist.</li>
 *   <li>No Spring; no Lombok; no Hibernate; constructor injection
 *       and plain factories only per AAP &sect;0.1.1.</li>
 *   <li>No {@code double} or {@code float}; this adapter never
 *       handles monetary values directly (the {@link BigDecimal} rate
 *       is owned by {@link DisGroupRecord}) but the prohibition
 *       stands per AAP &sect;0.6.1.</li>
 *   <li>No {@link ThreadLocal}; use {@code ScopedValue} at the
 *       calling layer per AAP &sect;0.6.6.</li>
 * </ul>
 *
 * @see DiscountGroupRepository
 * @see DisGroupRecord
 * @see DisGroupRecord.DisGroupKey
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @since 1.0.0
 */
@CobolProgram(
        value = "DISCGRP",
        sourcePath = "app/cpy/CVTRA02Y.cpy",
        notes = "File-backed adapter for the DISCGRP VSAM KSDS "
                + "(50-byte DIS-GROUP-RECORD; 16-byte composite key: "
                + "DIS-ACCT-GROUP-ID PIC X(10) + DIS-TRAN-TYPE-CD PIC X(02) "
                + "+ DIS-TRAN-CAT-CD PIC 9(04)). See CBACT04C "
                + "1200-GET-INTEREST-RATE at app/cbl/CBACT04C.cbl:L415-L420; "
                + "on FILE STATUS '23' the COBOL falls back to the 'DEFAULT' "
                + "group, which the application layer (CbAct04C) handles "
                + "via a second findByKey call. This port returns "
                + "Optional.empty() for not-found per AAP §0.6.10 "
                + "single-responsibility for ports."
)
public final class FileDiscountGroupRepository implements DiscountGroupRepository {

    /**
     * SLF4J logger; static so all instances share the same logger and
     * so the binding cost is paid once at class-load time. DEBUG lines
     * document successful save (key written) and delete (key removed)
     * outcomes; no INFO/WARN logging on the hot read paths to keep
     * production log volume bounded under high-fanout virtual-thread
     * dispatch per AAP &sect;0.6.6.
     */
    private static final Logger LOG = LoggerFactory.getLogger(FileDiscountGroupRepository.class);

    // ------------------------------------------------------------------
    // Layout constants — derived directly from app/cpy/CVTRA02Y.cpy.
    // Held as private constants per the agent prompt Phase 4
    // specification. The DisGroupRecord class also exposes its own
    // public layout constants (DIS_ACCT_GROUP_ID_LENGTH, etc.); both
    // sets agree by construction so the adapter remains decoupled
    // from the record's accidental rename of those constants.
    // ------------------------------------------------------------------

    /** Total fixed-record length in bytes (50). */
    private static final int RECORD_LENGTH = 50;

    /** Byte offset of the composite key within each record (always 0). */
    private static final int KEY_OFFSET = 0;

    /** Composite key length in bytes (10 + 2 + 4 = 16). */
    private static final int KEY_LENGTH = 16;

    /** Length in bytes of {@code DIS-ACCT-GROUP-ID PIC X(10)}. */
    private static final int ACCT_GROUP_ID_LENGTH = 10;

    /** Length in bytes of {@code DIS-TRAN-TYPE-CD PIC X(02)}. */
    private static final int TRAN_TYPE_CD_LENGTH = 2;

    /** Length in digits of {@code DIS-TRAN-CAT-CD PIC 9(04)}. */
    private static final int TRAN_CAT_CD_LENGTH = 4;

    /**
     * Maximum numeric value representable in
     * {@code DIS-TRAN-CAT-CD PIC 9(04)}; equals
     * {@code 10^TRAN_CAT_CD_LENGTH - 1 = 9999}.
     */
    private static final int TRAN_CAT_CD_MAX = 9_999;

    // ------------------------------------------------------------------
    // Configuration fields — final, set by constructor.
    // ------------------------------------------------------------------

    /**
     * Path to the data file backing this repository. Held as a
     * {@link Path} per AAP &sect;0.6.5 mandate to avoid
     * {@code java.io.File}. Used in error-message construction and
     * forwarded to the underlying {@link FixedWidthReader} /
     * {@link FixedWidthWriter}.
     */
    private final Path dataFile;

    /**
     * Configured {@link Charset} for byte-level encoding/decoding.
     * Forwarded to {@link FixedWidthReader} and {@link FixedWidthWriter}
     * and used directly by {@link #buildKey(String, String, int)} /
     * {@link #padRightSpaces(String, int)} to encode composite key
     * bytes. Default value at the composition root is
     * {@code IBM-1047} (EBCDIC) per AAP &sect;0.6.5; overridable via
     * {@code carddemo.file.discgrp.charset} in
     * {@code application.properties}.
     */
    private final Charset charset;

    /**
     * The foundational {@link FixedWidthReader} used for both
     * random-key lookup via {@link FixedWidthReader#findByKey(byte[], int)}
     * (translating COBOL {@code READ DISCGRP-FILE KEY IS ...} at
     * {@code app/cbl/CBACT04C.cbl:L416-L420}) and sequential scans
     * via {@link FixedWidthReader#streamSequential()} (translating a
     * hypothetical sequential COBOL {@code READ DISCGRP-FILE} loop
     * used by the seed-load verification step in
     * {@code app/jcl/DISCGRP.jcl}).
     */
    private final FixedWidthReader reader;

    /**
     * The foundational {@link FixedWidthWriter} used for upsert via
     * {@link FixedWidthWriter#upsert(byte[], int, byte[])}
     * (translating the IDCAMS {@code REPRO} seed-load in
     * {@code app/jcl/DISCGRP.jcl}) and per-record delete via
     * {@link FixedWidthWriter#deleteByKey(byte[], int)} (translating
     * the {@code DELETE CLUSTER} prologue in
     * {@code app/jcl/DISCGRP.jcl}).
     */
    private final FixedWidthWriter writer;

    /**
     * Repository-level write lock. Serializes the encode-and-upsert
     * sequence in {@link #save(DisGroupRecord)} and the
     * key-construction-and-delete sequence in
     * {@link #delete(String, String, int)}. Held only across the
     * smallest possible critical section (a single port-method
     * invocation); not re-entrant; not exposed via a public accessor
     * (which would tempt callers to perform cross-method critical
     * sections that this class is not designed to support).
     *
     * <p>Per AAP &sect;0.6.6 this lock is the only in-process
     * serialisation primitive used here; no {@link ThreadLocal} and
     * no {@code ScopedValue} is needed because the lock guards a
     * class instance (not a per-thread variable).
     */
    private final Object writeLock = new Object();

    // ------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------

    /**
     * Convenience constructor delegating to the canonical
     * {@link #FileDiscountGroupRepository(Path, Charset)} constructor
     * with {@code IBM-1047} (EBCDIC) as the default codepage per AAP
     * &sect;0.6.5. Preserves source-code compatibility with the
     * established codebase convention used by
     * {@code InterestCalculationApp} and other composition roots that
     * construct repositories with a single {@link Path} argument and
     * rely on the mandated EBCDIC default.
     *
     * <p>For an ASCII-encoded input file (e.g., the ASCII fixtures
     * shipped under {@code app/data/ASCII/}), use the canonical
     * two-arg constructor and pass
     * {@link java.nio.charset.StandardCharsets#US_ASCII} explicitly
     * &mdash; or set the {@code carddemo.file.discgrp.charset}
     * property at the composition root.
     *
     * @param dataFile absolute filesystem path of the DISCGRP file
     *                 (e.g., {@code ./data/discgrp.dat}); must not be
     *                 {@code null}. The file need NOT exist at
     *                 construction time; read methods handle missing
     *                 files as NOTFND parity, and write methods
     *                 create the file on first call if absent.
     * @throws NullPointerException if {@code dataFile} is {@code null}
     */
    public FileDiscountGroupRepository(Path dataFile) {
        this(dataFile, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs a new file-backed DISCGRP adapter. The data file
     * need NOT exist at construction time; read methods handle
     * missing files as NOTFND parity ({@link Optional#empty()} or
     * empty streams), and write methods create the file on first
     * call if absent.
     *
     * @param dataFile absolute filesystem path of the DISCGRP file
     *                 (e.g., {@code ./data/discgrp.dat}); must not be
     *                 {@code null}
     * @param charset  the character set forwarded to the underlying
     *                 {@link FixedWidthReader} and
     *                 {@link FixedWidthWriter} and used to encode
     *                 the composite key bytes; defaults to
     *                 {@code IBM-1047} per AAP &sect;0.6.5;
     *                 configurable via
     *                 {@code carddemo.file.discgrp.charset} in
     *                 {@code application.properties}. The schema
     *                 requires the key bytes to be encoded via
     *                 {@code String.getBytes(charset)} so that key
     *                 bytes round-trip consistently with file bytes
     *                 (the composition root is responsible for
     *                 supplying a charset that matches the file
     *                 contents).
     * @throws NullPointerException if any argument is {@code null}
     */
    public FileDiscountGroupRepository(Path dataFile, Charset charset) {
        this.dataFile = Objects.requireNonNull(dataFile, "dataFile");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(dataFile, RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(dataFile, RECORD_LENGTH, charset);
        LOG.debug("FileDiscountGroupRepository configured: dataFile={}, charset={}, recordLength={}",
                dataFile, charset, RECORD_LENGTH);
    }

    // ------------------------------------------------------------------
    // Configuration accessors — public for symmetry with sibling
    // File*Repository implementations and for diagnostic tooling.
    // ------------------------------------------------------------------

    /**
     * Returns the configured data file path.
     *
     * @return the path to the DISCGRP file; never {@code null}
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
    // DiscountGroupRepository port methods
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Translates the COBOL random read in
     * {@code app/cbl/CBACT04C.cbl} paragraph
     * {@code 1200-GET-INTEREST-RATE} at
     * {@code app/cbl/CBACT04C.cbl:L416-L420}:
     * <pre>{@code
     *   READ DISCGRP-FILE INTO DIS-GROUP-RECORD
     *        INVALID KEY
     *           DISPLAY 'DISCLOSURE GROUP RECORD MISSING'
     *           DISPLAY 'TRY WITH DEFAULT GROUP CODE'
     *   END-READ.
     * }</pre>
     *
     * <h3>Implementation</h3>
     * <ol>
     *   <li>Builds the 16-byte composite key via
     *       {@link #buildKey(String, String, int)} which validates
     *       the composite-key components against their respective
     *       COBOL PIC bounds and assembles the byte image via a
     *       {@link ByteBuffer} of exactly {@link #KEY_LENGTH} bytes.</li>
     *   <li>Delegates to {@link FixedWidthReader#findByKey(byte[], int)}
     *       which scans the file sequentially comparing the key field
     *       at {@link #KEY_OFFSET} of each record.</li>
     *   <li>Maps the returned byte buffer via
     *       {@link DisGroupRecord#parse(byte[])}.</li>
     * </ol>
     *
     * <p>Returns {@link Optional#empty()} on NOTFND (file status
     * {@code '23'}). The {@code IOException} surface from the reader
     * is wrapped in {@link UncheckedIOException} per the port
     * contract.
     *
     * @throws NullPointerException     if {@code accountGroupId} or
     *                                  {@code tranTypeCd} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code accountGroupId}
     *                                  exceeds 10 characters,
     *                                  {@code tranTypeCd} exceeds 2
     *                                  characters, or {@code tranCatCd}
     *                                  is outside {@code [0, 9999]}
     * @throws UncheckedIOException     if the underlying file read
     *                                  fails
     */
    @Override
    public Optional<DisGroupRecord> findByKey(String accountGroupId,
                                              String tranTypeCd,
                                              int tranCatCd) {
        byte[] keyBytes = buildKey(accountGroupId, tranTypeCd, tranCatCd);
        try {
            return reader.findByKey(keyBytes, KEY_OFFSET).map(DisGroupRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error reading DISCGRP for accountGroupId='" + accountGroupId
                            + "' tranTypeCd='" + tranTypeCd
                            + "' tranCatCd=" + tranCatCd + " from " + dataFile, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Translates a hypothetical sequential COBOL {@code READ
     * DISCGRP-FILE} loop over the DISCGRP dataset. No translated
     * COBOL application program actually performs a sequential scan
     * of {@code DISCGRP}; this method is provided for
     * application-startup preload, IDCAMS-style seed-load
     * verification, and end-to-end fixture comparison by the
     * golden-record harness per AAP &sect;0.6.11.
     *
     * <p>The returned {@link Stream} is lazy and backed by the
     * underlying {@link java.nio.channels.SeekableByteChannel};
     * callers MUST close the stream &mdash; typically via
     * try-with-resources &mdash; so that the channel is released
     * promptly. Records are streamed in ascending composite-key
     * order; reordering is forbidden per AAP &sect;0.1.3.
     *
     * <pre>{@code
     *   try (Stream<DisGroupRecord> groups = repository.streamSequential()) {
     *       groups.forEach(this::process);
     *   }
     * }</pre>
     *
     * @return a lazy {@link Stream} of every {@link DisGroupRecord}
     *         in ascending composite-key order; never {@code null}
     *         (empty stream when the dataset has no records)
     * @throws UncheckedIOException if the underlying file open fails
     */
    @Override
    public Stream<DisGroupRecord> streamSequential() {
        try {
            return reader.streamSequential().map(DisGroupRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error opening DISCGRP for sequential read: " + dataFile, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Upsert semantics &mdash; inserts the supplied record if no
     * record with the same composite key exists, or replaces the
     * existing record byte-for-byte if one does. Translates the
     * IDCAMS {@code REPRO} seed-load path declared in
     * {@code app/jcl/DISCGRP.jcl}; no translated COBOL application
     * program issues a runtime {@code WRITE} or {@code REWRITE}
     * against {@code DISCGRP} at runtime (it is a static reference
     * dataset per AAP &sect;0.6.10).
     *
     * <h3>Implementation</h3>
     * <ol>
     *   <li>Verifies the supplied {@link DisGroupRecord} is
     *       non-null.</li>
     *   <li>Encodes the record to its canonical 50-byte byte image
     *       via {@link DisGroupRecord#encode()}. The compact
     *       canonical constructor of {@link DisGroupRecord} has
     *       already enforced field-level invariants (composite-key
     *       components in range, rate scale exactly 2, FILLER length
     *       exactly 28).</li>
     *   <li>Asserts the encoded length equals {@link #RECORD_LENGTH}
     *       as a defense-in-depth check protecting the dataset from
     *       corruption if a future refactor accidentally breaks the
     *       record-fidelity invariant.</li>
     *   <li>Builds the 16-byte composite key via
     *       {@link #buildKey(String, String, int)} using the key
     *       components carried on the record's nested
     *       {@link DisGroupRecord.DisGroupKey}
     *       ({@code disGroupKey().disAcctGroupId()},
     *       {@code disGroupKey().disTranTypeCd()},
     *       {@code disGroupKey().disTranCatCd()}).</li>
     *   <li>Under the repository-level {@link #writeLock},
     *       delegates to {@link FixedWidthWriter#upsert(byte[], int, byte[])}
     *       to perform the atomic insert-or-replace.</li>
     * </ol>
     *
     * @throws NullPointerException  if {@code record} is {@code null}
     * @throws IllegalStateException if {@link DisGroupRecord#encode()}
     *                               returns a buffer whose length
     *                               differs from {@link #RECORD_LENGTH}
     *                               (defensive invariant check)
     * @throws UncheckedIOException  on underlying file I/O failure
     */
    @Override
    public void save(DisGroupRecord record) {
        Objects.requireNonNull(record, "record");
        // Encode the record to its 50-byte canonical byte image.
        // DisGroupRecord.encode() is guaranteed to return exactly
        // RECORD_LENGTH bytes per the AAP §0.6.5 byte-fidelity
        // invariant; the defensive length check below surfaces any
        // future regression in DisGroupRecord immediately rather than
        // silently corrupting the file.
        byte[] encoded = record.encode();
        if (encoded.length != RECORD_LENGTH) {
            throw new IllegalStateException(
                    "DisGroupRecord.encode() returned " + encoded.length
                            + " bytes; expected " + RECORD_LENGTH
                            + " (record-fidelity invariant violated)");
        }
        // The nested DisGroupKey exposes disAcctGroupId(),
        // disTranTypeCd(), disTranCatCd() — see CVTRA02Y.cpy:L5-L8.
        DisGroupRecord.DisGroupKey k = record.disGroupKey();
        byte[] keyBytes = buildKey(k.disAcctGroupId(), k.disTranTypeCd(), k.disTranCatCd());
        // Serialise the upsert under the repository-level writeLock
        // so that concurrent callers see a strict single-writer
        // ordering (matching the COBOL FILE-OPEN-I-O single-process
        // WRITE/REWRITE semantic). FixedWidthWriter also has its own
        // internal writeLock; the two-level locking is intentional
        // for defense in depth and matches the established sibling
        // File*Repository pattern (e.g.,
        // FileTransactionCategoryBalanceRepository).
        synchronized (writeLock) {
            try {
                writer.upsert(keyBytes, KEY_OFFSET, encoded);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Error upserting DISCGRP key='"
                                + new String(keyBytes, charset)
                                + "' to " + dataFile, e);
            }
        }
        if (LOG.isDebugEnabled()) {
            // disIntRate is scale-preserved BigDecimal (e.g., "1.20"
            // never "1.2") per AAP §0.1.3 — safe to print without
            // further formatting.
            LOG.debug("save key='{}' rate={} file={} status=ok",
                    new String(keyBytes, charset), record.disIntRate(), dataFile);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Removes the {@link DisGroupRecord} keyed by the composite
     * {@code (accountGroupId, tranTypeCd, tranCatCd)} triple. The
     * port contract specifies that this operation exists exclusively
     * to support the IDCAMS {@code DELETE CLUSTER} / re-{@code
     * DEFINE} cycle declared in {@code app/jcl/DISCGRP.jcl}; no
     * translated COBOL application program issues a runtime
     * {@code DELETE} against {@code DISCGRP}. The
     * {@code DefineDiscountGroupApp} composition root uses this
     * method to express the reset-and-reload idiom symmetrically
     * with sibling apps.
     *
     * <h3>Implementation</h3>
     * <ol>
     *   <li>Builds the 16-byte composite key via
     *       {@link #buildKey(String, String, int)} (which also
     *       performs PIC-bound validation on the components).</li>
     *   <li>Under the repository-level {@link #writeLock}, delegates
     *       to {@link FixedWidthWriter#deleteByKey(byte[], int)}
     *       which returns {@code false} on NOTFND (no matching
     *       record).</li>
     *   <li>Translates the NOTFND outcome into a
     *       {@link NoSuchElementException}, mirroring the COBOL
     *       {@code INVALID KEY} clause on a hypothetical
     *       {@code DELETE DISCGRP-FILE} statement (file status
     *       {@code '23'}).</li>
     * </ol>
     *
     * @throws NullPointerException     if {@code accountGroupId} or
     *                                  {@code tranTypeCd} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code accountGroupId}
     *                                  exceeds 10 characters,
     *                                  {@code tranTypeCd} exceeds 2
     *                                  characters, or {@code tranCatCd}
     *                                  is outside {@code [0, 9999]}
     * @throws NoSuchElementException   if no record with the supplied
     *                                  composite key exists in the
     *                                  dataset
     * @throws UncheckedIOException     if the underlying file delete
     *                                  fails
     */
    @Override
    public void delete(String accountGroupId, String tranTypeCd, int tranCatCd) {
        byte[] keyBytes = buildKey(accountGroupId, tranTypeCd, tranCatCd);
        synchronized (writeLock) {
            try {
                boolean removed = writer.deleteByKey(keyBytes, KEY_OFFSET);
                if (!removed) {
                    throw new NoSuchElementException(
                            "No DISCGRP record found for accountGroupId='"
                                    + accountGroupId + "' tranTypeCd='"
                                    + tranTypeCd + "' tranCatCd=" + tranCatCd);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Error deleting DISCGRP accountGroupId='"
                                + accountGroupId + "' tranTypeCd='"
                                + tranTypeCd + "' tranCatCd=" + tranCatCd
                                + " from " + dataFile, e);
            }
        }
        LOG.debug("delete accountGroupId='{}' tranTypeCd='{}' tranCatCd={} file={} status=ok",
                accountGroupId, tranTypeCd, tranCatCd, dataFile);
    }

    // ------------------------------------------------------------------
    // AutoCloseable — required by DiscountGroupRepository extending
    // AutoCloseable
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
     * multiple times without observable side effects, consistent
     * with the {@link DiscountGroupRepository#close()} contract.
     */
    @Override
    public void close() {
        LOG.debug("FileDiscountGroupRepository closed (no-op): dataFile={}", dataFile);
    }

    // ------------------------------------------------------------------
    // Private helpers — composite-key construction
    // ------------------------------------------------------------------

    /**
     * Builds the 16-byte composite key bytes from its three
     * components per the COBOL {@code DIS-GROUP-KEY} layout
     * ({@code app/cpy/CVTRA02Y.cpy:L5-L8}).
     *
     * <p>Layout:
     * <ul>
     *   <li>Bytes 0-9: {@code accountGroupId}
     *       ({@code DIS-ACCT-GROUP-ID PIC X(10)}, space-padded
     *       right)</li>
     *   <li>Bytes 10-11: {@code tranTypeCd}
     *       ({@code DIS-TRAN-TYPE-CD PIC X(02)}, space-padded
     *       right)</li>
     *   <li>Bytes 12-15: {@code tranCatCd}
     *       ({@code DIS-TRAN-CAT-CD PIC 9(04)}, zero-padded
     *       left)</li>
     * </ul>
     *
     * <p>Encodes the result via {@link String#getBytes(Charset)} so
     * that the resulting key bytes round-trip consistently with the
     * file bytes written by {@link FixedWidthWriter} (which
     * forwards the same {@code charset}).
     *
     * @param accountGroupId the 10-character account-group identifier
     *                       ({@code DIS-ACCT-GROUP-ID PIC X(10)}); may
     *                       be a 7-character literal such as
     *                       {@code "DEFAULT"} — it will be right-
     *                       space-padded to 10 bytes here
     * @param tranTypeCd     the 2-character transaction-type code
     *                       ({@code DIS-TRAN-TYPE-CD PIC X(02)})
     * @param tranCatCd      the 4-digit unsigned category code
     *                       ({@code DIS-TRAN-CAT-CD PIC 9(04)})
     * @return a freshly allocated {@code byte[KEY_LENGTH]} containing
     *         the composite key in COBOL field order
     * @throws NullPointerException     if {@code accountGroupId} or
     *                                  {@code tranTypeCd} is
     *                                  {@code null}
     * @throws IllegalArgumentException if any component is outside
     *                                  its COBOL PIC bound
     */
    private byte[] buildKey(String accountGroupId, String tranTypeCd, int tranCatCd) {
        Objects.requireNonNull(accountGroupId, "accountGroupId");
        Objects.requireNonNull(tranTypeCd, "tranTypeCd");
        if (accountGroupId.length() > ACCT_GROUP_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "accountGroupId max length " + ACCT_GROUP_ID_LENGTH
                            + " per PIC X(" + ACCT_GROUP_ID_LENGTH + "); got "
                            + accountGroupId.length());
        }
        if (tranTypeCd.length() > TRAN_TYPE_CD_LENGTH) {
            throw new IllegalArgumentException(
                    "tranTypeCd max length " + TRAN_TYPE_CD_LENGTH
                            + " per PIC X(" + TRAN_TYPE_CD_LENGTH + "); got "
                            + tranTypeCd.length());
        }
        if (tranCatCd < 0 || tranCatCd > TRAN_CAT_CD_MAX) {
            throw new IllegalArgumentException(
                    "tranCatCd out of PIC 9(" + TRAN_CAT_CD_LENGTH + ") range "
                            + "[0, " + TRAN_CAT_CD_MAX + "]: " + tranCatCd);
        }

        // Assemble the 16-byte composite key via ByteBuffer per the
        // binding external_imports schema requirement and the agent
        // prompt Phase 5 specification. The three components are
        // appended in COBOL field order so the resulting key matches
        // byte-for-byte the in-record key bytes produced by
        // DisGroupRecord.encode().
        ByteBuffer buffer = ByteBuffer.allocate(KEY_LENGTH);
        buffer.put(padRightSpaces(accountGroupId, ACCT_GROUP_ID_LENGTH));
        buffer.put(padRightSpaces(tranTypeCd, TRAN_TYPE_CD_LENGTH));
        // %04d on a non-negative int in the validated range [0, 9999]
        // produces exactly TRAN_CAT_CD_LENGTH (4) ASCII digits; the
        // String.getBytes(charset) call then transcodes those digits
        // through the configured charset. For ASCII and EBCDIC the
        // digit code points are well-defined; the composition root is
        // responsible for selecting a charset that matches the file
        // contents.
        buffer.put(String.format("%04d", tranCatCd).getBytes(charset));
        return buffer.array();
    }

    /**
     * Right-space-pads (or truncates to) {@code value} to exactly
     * {@code length} bytes encoded via {@link #charset}. Models the
     * COBOL {@code PIC X(length)} convention where shorter values
     * are padded with spaces on the RIGHT.
     *
     * <p>The {@code length} validation in
     * {@link #buildKey(String, String, int)} already rejects values
     * longer than the field width; this helper applies
     * defense-in-depth truncation via {@link Math#min(int, int)}
     * rather than overflowing the destination buffer.
     *
     * @param value  the value to encode and pad; must not be
     *               {@code null} (caller-validated)
     * @param length the target field length in bytes; must be
     *               strictly positive
     * @return a freshly allocated {@code byte[length]} containing the
     *         charset-encoded value followed by ASCII space
     *         ({@code 0x20}) padding
     */
    private byte[] padRightSpaces(String value, int length) {
        byte[] result = new byte[length];
        byte[] valueBytes = value.getBytes(charset);
        int copy = Math.min(valueBytes.length, length);
        System.arraycopy(valueBytes, 0, result, 0, copy);
        for (int i = copy; i < length; i++) {
            result[i] = (byte) ' ';
        }
        return result;
    }
}
