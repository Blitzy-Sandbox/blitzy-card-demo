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
package com.blitzy.carddemo.batch;

import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.record.TranRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plain-Java batch driver that translates the {@code app/jcl/COMBTRAN.jcl}
 * two-step job into a single {@link #execute()} method while preserving
 * byte-for-byte fidelity with the original DFSORT / IDCAMS flow.
 *
 * <h2>Source lineage ({@code app/jcl/COMBTRAN.jcl})</h2>
 *
 * <p>The originating JCL job has two sequential steps:
 *
 * <h3>STEP05R &mdash; DFSORT concatenate-and-sort</h3>
 * {@snippet lang = "jcl":
 * //STEP05R  EXEC PGM=SORT
 * //SORTIN   DD DISP=SHR,
 * //         DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(0)
 * //         DD DISP=SHR,
 * //         DSN=AWS.M2.CARDDEMO.SYSTRAN(0)
 * //SYMNAMES DD *
 * TRAN-ID,1,16,CH
 * //SYSIN    DD *
 *  SORT FIELDS=(TRAN-ID,A)
 * //SORTOUT  DD DISP=(NEW,CATLG,DELETE),
 * //         UNIT=SYSDA,
 * //         DCB=(*.SORTIN),
 * //         SPACE=(CYL,(1,1),RLSE),
 * //         DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)
 * }
 *
 * <p>STEP05R concatenates two input datasets in JCL DD order &mdash;
 * {@code TRANSACT.BKUP(0)} (the most recent backup of the live transaction
 * file) FIRST, followed by {@code SYSTRAN(0)} (system-generated
 * transactions from the most recent posting cycle). The concatenated
 * stream is sorted in ascending {@code TRAN-ID} order using DFSORT's
 * {@code CH} (alphanumeric / character) collating sequence over bytes 1-16
 * of each record. The sorted output is written to a new GDG generation
 * {@code TRANSACT.COMBINED(+1)} with the DCB inherited from {@code SORTIN}
 * (350-byte fixed-width records).
 *
 * <h3>STEP10 &mdash; IDCAMS REPRO into the live KSDS</h3>
 * {@snippet lang = "jcl":
 * //STEP10 EXEC PGM=IDCAMS
 * //SYSPRINT DD   SYSOUT=*
 * //TRANSACT DD DISP=SHR,
 * //         DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)
 * //TRANVSAM DD DISP=SHR,
 * //         DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS
 * //SYSIN    DD   *
 *    REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)
 * }
 *
 * <p>STEP10 reads the combined sequential file produced by STEP05R and
 * loads it into the live {@code TRANSACT.VSAM.KSDS} via IDCAMS
 * {@code REPRO}. Because the input is already sorted ascending by the KSDS
 * primary key ({@code TRAN-ID}), the REPRO appends each record to the
 * KSDS in key order without re-sorting.
 *
 * <h2>Java translation</h2>
 *
 * <p>The two JCL steps are coalesced into a single {@link #execute()}
 * method:
 * <ol>
 *   <li>Read {@link #backupInputPath} as a fixed-width 350-byte record
 *       stream, appending raw {@code byte[350]} buffers to an in-memory
 *       {@link ArrayList} in arrival order.</li>
 *   <li>Read {@link #systranInputPath} as a fixed-width 350-byte record
 *       stream, appending to the same buffer AFTER the backup records.
 *       This preserves the JCL DD concatenation order (backup FIRST, then
 *       systran) as the stable-sort tiebreaker for equal {@code TRAN-ID}
 *       keys.</li>
 *   <li>Sort the combined list in ascending byte-wise unsigned order on
 *       the first {@value #TRAN_ID_LENGTH} bytes of each record (the
 *       {@code TRAN-ID} field). {@link List#sort(Comparator)} uses a
 *       stable mergesort (TimSort), matching DFSORT's stable-sort
 *       semantics for equal keys per {@code SORT FIELDS=(TRAN-ID,A)}
 *       without {@code EQUALS=NO}.</li>
 *   <li>Write the sorted stream to {@link #combinedOutputPath} byte-for-
 *       byte, with the parent directory created if it does not yet
 *       exist. Any pre-existing file at the output path is deleted first
 *       to honour the JCL {@code DISP=(NEW,CATLG,DELETE)} semantics for
 *       {@code SORTOUT}.</li>
 *   <li>Refresh the live {@link TransactionRepository} by parsing every
 *       combined record via {@link TranRecord#parse(byte[])} and
 *       invoking {@link TransactionRepository#save(TranRecord)} once per
 *       record. This translates IDCAMS REPRO from the sequential combined
 *       file into the live KSDS.</li>
 * </ol>
 *
 * <p>Coalescing the two steps into one Java method is acceptable because:
 * (a) the original JCL contains no {@code COND=(0,NE)} predicate between
 * STEP05R and STEP10 &mdash; the two steps always run sequentially with
 * no conditional skip, (b) the intermediate {@code TRANSACT.COMBINED(+1)}
 * file is still written to disk so any downstream JCL or audit consumer
 * sees the same byte-for-byte intermediate dataset that DFSORT would
 * produce, and (c) the observable end state (sorted combined file plus
 * refreshed live KSDS) is identical to the two-step JCL flow.
 *
 * <h2>Sort-key fidelity (AAP &sect;0.7.1)</h2>
 *
 * <p>The DFSORT statement is {@code SORT FIELDS=(TRAN-ID,A)} where
 * {@code TRAN-ID} is defined in the {@code SYMNAMES} DD as
 * {@code TRAN-ID,1,16,CH}: a 16-byte alphanumeric ({@code CH}) field
 * starting at byte position 1 of each record. Translated to Java
 * (where buffers are zero-indexed):
 * <ul>
 *   <li>{@link #TRAN_ID_OFFSET} = {@code 0} (COBOL position 1 = Java
 *       offset 0).</li>
 *   <li>{@link #TRAN_ID_LENGTH} = {@code 16} (the {@code PIC X(16)} of
 *       {@code TRAN-RECORD.TRAN-ID} per
 *       {@code app/cpy/CVTRA05Y.cpy}).</li>
 * </ul>
 *
 * <p>The {@link #byTranIdAscending()} comparator compares the first
 * {@value #TRAN_ID_LENGTH} bytes of each record byte-by-byte using
 * <strong>unsigned</strong> comparison ({@code byte & 0xFF}). This
 * byte-wise unsigned comparison matches DFSORT's {@code CH} collating
 * sequence on the input encoding: for ASCII data (the migration's
 * default per AAP &sect;0.6.5) the order is identical to ASCII lexical
 * order; for EBCDIC data (when the file-based adapter loads
 * EBCDIC-encoded fixtures with {@code IBM-1047}), the byte-level order
 * is preserved verbatim &mdash; downstream consumers see byte-for-byte
 * identical output to what DFSORT would produce on the same input.
 *
 * <p>Per AAP &sect;0.1.3, reordering records or changing sort orders
 * is FORBIDDEN: "virtual threads are NOT a license to reorder records,
 * change sort orders, or break sequencing." This driver therefore
 * performs the sort on a SINGLE thread &mdash; {@link List#sort(Comparator)}
 * is a stable serial mergesort. Using {@link java.util.Arrays#parallelSort
 * Arrays.parallelSort} or any virtual-thread fan-out would risk breaking
 * the stable-sort tiebreaker for equal {@code TRAN-ID} keys and is
 * NOT used here.
 *
 * <h2>Stable-sort tiebreaker (JCL DD order preservation)</h2>
 *
 * <p>The JCL concatenation places {@code TRANSACT.BKUP(0)} BEFORE
 * {@code SYSTRAN(0)} on the {@code SORTIN} DD. DFSORT's default sort
 * is stable (the {@code EQUALS=NO} override is NOT present in the
 * source SORT card), so for records with identical {@code TRAN-ID}
 * keys, the backup record sorts BEFORE the systran record in the
 * output. The Java translation preserves this exactly: records are
 * appended to the same buffer in JCL DD order (backup first via
 * {@link #readFixedWidth(Path, List)} on {@link #backupInputPath},
 * then systran via the same call on {@link #systranInputPath}), and
 * {@link List#sort(Comparator)} is documented to be stable. The
 * resulting byte-for-byte output is therefore identical to DFSORT's.
 *
 * <h2>File I/O (AAP &sect;0.6.5)</h2>
 *
 * <p>All file I/O uses {@link java.nio.file} APIs exclusively. The
 * legacy {@link java.io.File} API is FORBIDDEN per AAP &sect;0.6.5
 * &mdash; this class imports {@link java.io.IOException} and
 * {@link java.io.UncheckedIOException} from {@code java.io} but NOT
 * {@code java.io.File}. Channels are obtained via
 * {@link Files#newByteChannel(Path, java.nio.file.OpenOption...)} and
 * always used inside try-with-resources to guarantee release of the
 * underlying file handle.
 *
 * <p>Inputs are opened with {@link StandardOpenOption#READ} and read
 * exactly {@value #TRAN_RECORD_LENGTH} bytes at a time. The reader
 * verifies that the file size is an integral multiple of the record
 * length and rejects short reads &mdash; mirroring the COBOL FD
 * {@code RECORD CONTAINS 350 CHARACTERS / BLOCK CONTAINS 0 RECORDS}
 * contract. A {@link UncheckedIOException} is raised when the file
 * is missing (matching z/OS {@code DISP=SHR} on a non-existent
 * dataset, which abends the step) or when any I/O error occurs
 * during the read.
 *
 * <p>The output is written with
 * {@link StandardOpenOption#CREATE_NEW} + {@link StandardOpenOption#WRITE}
 * AFTER any pre-existing file is removed via
 * {@link Files#deleteIfExists(Path)} &mdash; mirroring the JCL
 * {@code DISP=(NEW,CATLG,DELETE)} for {@code SORTOUT} that conditionally
 * defines a brand-new dataset, catalogs it on normal completion, and
 * deletes it on step abend.
 *
 * <h2>Repository refresh (STEP10 IDCAMS REPRO equivalent)</h2>
 *
 * <p>{@link #refreshTransactRepository(List)} iterates the sorted
 * combined records, parses each {@code byte[350]} via
 * {@link TranRecord#parse(byte[])} (the round-trip-invariant factory
 * defined in {@code app/cpy/CVTRA05Y.cpy}'s Java translation), and
 * invokes {@link TransactionRepository#save(TranRecord)} once per
 * record. The composition root in {@code carddemo-app} selects the
 * concrete {@code TransactionRepository} adapter (file-based by
 * default per AAP &sect;0.6.5, optionally JDBC); both adapters honour
 * the contract that {@code save} produces the same observable state
 * as IDCAMS REPRO would on the live KSDS &mdash; namely, every key
 * present in the combined input is present in the repository at end
 * of run, with the same byte-for-byte 350-byte record image.
 *
 * <h2>Wall-clock instrumentation</h2>
 *
 * <p>{@link Instant#now()} captures start and end timestamps and
 * {@link Duration#between(java.time.temporal.Temporal, java.time.temporal.Temporal)
 * Duration.between} computes the elapsed time, logged at INFO level on
 * completion. Per AAP &sect;0.6.4, {@link java.util.Date} and
 * {@link java.util.Calendar} are FORBIDDEN &mdash; {@link java.time}
 * is used exclusively for any date/time value.
 *
 * <h2>NO &#64;CobolProgram annotation</h2>
 *
 * <p>This driver represents a JCL job &mdash; not a COBOL
 * {@code PROGRAM-ID}. Per AAP &sect;0.2.1 the {@code @CobolProgram}
 * traceability annotation is reserved for translated COBOL program
 * classes (one per {@code PROGRAM-ID}). {@code COMBTRAN.jcl} has no
 * underlying COBOL program &mdash; it is a pure DFSORT/IDCAMS flow
 * &mdash; so the annotation does NOT apply here.
 *
 * <h2>Immutability and thread safety</h2>
 *
 * <p>All instance fields are {@code final}; the class itself is
 * {@code final} to prevent subclassing. {@link #execute()} is NOT
 * marked {@code synchronized} because (a) the in-memory record list
 * is a method-local {@link ArrayList} that never escapes the method
 * scope, and (b) concurrent {@code execute()} invocations on the
 * same instance against the same input paths would each open their
 * own channels and produce their own sorted output &mdash; the only
 * shared mutable state is the {@link TransactionRepository}, whose
 * thread-safety guarantees are an implementation concern of the
 * adapter (see the port's class-level Javadoc). In practice
 * {@link #execute()} is called serially from the composition root
 * (one JCL step at a time), so concurrent invocations do not arise.
 *
 * <h2>Authority</h2>
 * <ul>
 *   <li>AAP &sect;0.4.1 &mdash; {@code CombineTransactionsApp} listing
 *       and the COMBTRAN JCL transformation row</li>
 *   <li>AAP &sect;0.6.5 &mdash; File I/O exactness: {@code java.nio.file}
 *       only; {@code java.io.File} FORBIDDEN; byte-for-byte fidelity
 *       round-trip invariant</li>
 *   <li>AAP &sect;0.7.1 &mdash; Preserve-As-Is: "All file naming
 *       conventions, sort orders, and batch sequencing"</li>
 *   <li>AAP &sect;0.1.3 &mdash; "virtual threads are NOT a license to
 *       reorder records, change sort orders, or break sequencing"</li>
 *   <li>{@code app/jcl/COMBTRAN.jcl} &mdash; original JCL with
 *       {@code SORT FIELDS=(TRAN-ID,A)} and {@code REPRO INFILE(TRANSACT)
 *       OUTFILE(TRANVSAM)}</li>
 *   <li>{@code app/cpy/CVTRA05Y.cpy} &mdash; 350-byte
 *       {@code TRAN-RECORD} layout with {@code 05 TRAN-ID PIC X(16)} at
 *       offset 0</li>
 * </ul>
 *
 * @since 1.0.0
 */
public final class CombineTransactionsBatch {

    // -----------------------------------------------------------------------
    // Static constants
    // -----------------------------------------------------------------------

    /**
     * SLF4J logger for this driver. The concrete logging backend
     * (logback-classic) is supplied at runtime by the composition root
     * in {@code carddemo-app}; this module declares only the facade
     * (slf4j-api) per AAP &sect;0.5.1.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CombineTransactionsBatch.class);

    /**
     * Fixed length of a {@code TRAN-RECORD} in bytes per
     * {@code app/cpy/CVTRA05Y.cpy} &mdash; 350 bytes (AAP &sect;0.4.1
     * and AAP &sect;0.6.9 Copybook-to-Record table). Matches
     * {@link TranRecord#RECORD_LENGTH} exactly; declared as a local
     * {@code public static final} per the file's export schema so
     * upstream callers (tests, composition root) can reference it
     * directly via {@code CombineTransactionsBatch.TRAN_RECORD_LENGTH}.
     */
    public static final int TRAN_RECORD_LENGTH = 350;

    /**
     * Byte offset of the {@code TRAN-ID} field within a
     * {@code TRAN-RECORD}. COBOL position 1 corresponds to Java offset
     * 0 (zero-based indexing). Used by {@link #byTranIdAscending()}
     * to locate the 16-byte sort key.
     */
    private static final int TRAN_ID_OFFSET = 0;

    /**
     * Byte length of the {@code TRAN-ID} field per DFSORT's
     * {@code SYMNAMES TRAN-ID,1,16,CH} symbol definition, which is the
     * canonical 16-character alphanumeric primary key declared at
     * {@code 05 TRAN-ID PIC X(16)} in {@code app/cpy/CVTRA05Y.cpy}.
     */
    private static final int TRAN_ID_LENGTH = 16;

    // -----------------------------------------------------------------------
    // Instance fields (all final)
    // -----------------------------------------------------------------------

    /**
     * Live transaction repository, refreshed at the end of
     * {@link #execute()} via the IDCAMS REPRO equivalent
     * ({@link #refreshTransactRepository(List)}). The composition root
     * in {@code carddemo-app} wires the concrete adapter (file-based by
     * default per AAP &sect;0.6.5, optionally JDBC).
     */
    private final TransactionRepository transactionRepository;

    /**
     * Filesystem path to the backup-of-transactions input dataset
     * &mdash; the JCL {@code TRANSACT.BKUP(0)} GDG reference. Resolved
     * by the composition root from configuration (typically via
     * {@code DailyRejectsBatch}-style GDG resolution for the
     * {@code TRANSACT.BKUP} dataset). Read FIRST during
     * {@link #execute()} so that backup records sort before systran
     * records when {@code TRAN-ID} keys collide (the stable-sort
     * tiebreaker for the JCL DD concatenation order).
     */
    private final Path backupInputPath;

    /**
     * Filesystem path to the system-generated transactions input
     * dataset &mdash; the JCL {@code SYSTRAN(0)} GDG reference.
     * Resolved by the composition root from configuration. Read SECOND
     * during {@link #execute()} after the backup records, so that
     * systran records sort after backup records on key collision (the
     * stable-sort tiebreaker for the JCL DD concatenation order).
     */
    private final Path systranInputPath;

    /**
     * Filesystem path to the combined output dataset &mdash; the JCL
     * {@code TRANSACT.COMBINED(+1)} GDG reference. Resolved by the
     * composition root from a GDG resolver (analogous to
     * {@link DailyRejectsBatch#resolveNextGeneration()}). Any pre-existing
     * file at this path is deleted before writing (mirroring the JCL
     * {@code DISP=(NEW,CATLG,DELETE)} semantics for {@code SORTOUT}).
     */
    private final Path combinedOutputPath;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Constructs a batch driver that translates the {@code COMBTRAN.jcl}
     * two-step flow.
     *
     * <p>Arguments are validated eagerly: all four parameters must be
     * non-{@code null} (per AAP &sect;0.6.5 "byte-for-byte file
     * fidelity is non-negotiable" &mdash; a null path or repository is
     * a configuration error that must surface immediately at
     * construction, not during execution). The constructor does NOT
     * verify that the input files exist or that the repository is
     * reachable &mdash; those checks are deferred to
     * {@link #execute()} so the composition root can construct the
     * driver before the input GDG generations are provisioned by a
     * sibling job step.
     *
     * @param transactionRepository live {@code TRANSACT} repository
     *                              that will receive the sorted
     *                              combined records via
     *                              {@link TransactionRepository#save(TranRecord)};
     *                              must be non-{@code null}
     * @param backupInputPath       path to the backup input
     *                              ({@code TRANSACT.BKUP(0)}
     *                              equivalent); must be
     *                              non-{@code null}
     * @param systranInputPath      path to the systran input
     *                              ({@code SYSTRAN(0)} equivalent);
     *                              must be non-{@code null}
     * @param combinedOutputPath    path to the combined output
     *                              ({@code TRANSACT.COMBINED(+1)}
     *                              equivalent); must be
     *                              non-{@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CombineTransactionsBatch(
            TransactionRepository transactionRepository,
            Path backupInputPath,
            Path systranInputPath,
            Path combinedOutputPath) {
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository, "transactionRepository");
        this.backupInputPath = Objects.requireNonNull(
                backupInputPath, "backupInputPath");
        this.systranInputPath = Objects.requireNonNull(
                systranInputPath, "systranInputPath");
        this.combinedOutputPath = Objects.requireNonNull(
                combinedOutputPath, "combinedOutputPath");
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Executes the {@code COMBTRAN} job translation: concatenates the
     * backup and systran inputs in JCL DD order, sorts ascending by
     * {@code TRAN-ID}, writes the combined output, and refreshes the
     * live {@link TransactionRepository}.
     *
     * <p>Steps (matching the original JCL two-step flow):
     * <ol>
     *   <li>Read {@link #backupInputPath} as a fixed-width 350-byte
     *       record stream; append each {@code byte[350]} to a fresh
     *       in-memory buffer in arrival order.</li>
     *   <li>Read {@link #systranInputPath} as a fixed-width 350-byte
     *       record stream; append each {@code byte[350]} to the same
     *       buffer AFTER the backup records. JCL DD concatenation
     *       order is therefore preserved as the stable-sort
     *       tiebreaker for equal {@code TRAN-ID} keys.</li>
     *   <li>Sort the combined buffer in ascending byte-wise unsigned
     *       order on the first 16 bytes ({@code TRAN-ID}) using
     *       {@link List#sort(Comparator)} (stable TimSort) &mdash;
     *       matching DFSORT {@code SORT FIELDS=(TRAN-ID,A)}.</li>
     *   <li>Write the sorted buffer to {@link #combinedOutputPath}
     *       byte-for-byte (any pre-existing file is deleted first).
     *       Parent directories are created as needed.</li>
     *   <li>Refresh the live {@link TransactionRepository}: parse each
     *       sorted 350-byte record via
     *       {@link TranRecord#parse(byte[])} and invoke
     *       {@link TransactionRepository#save(TranRecord)} once per
     *       record &mdash; the IDCAMS REPRO equivalent.</li>
     * </ol>
     *
     * <p>The method returns the total number of records combined
     * (backup + systran). Wall-clock elapsed time is logged on
     * completion at INFO level.
     *
     * @return total number of 350-byte records combined and persisted
     * @throws UncheckedIOException     if any input file is missing
     *                                  ({@code DISP=SHR} on a
     *                                  non-existent dataset abends on
     *                                  z/OS &mdash; this translation
     *                                  surfaces the same hard failure)
     *                                  or any I/O error occurs during
     *                                  read or write
     * @throws IllegalStateException    if any input file size is not an
     *                                  integral multiple of
     *                                  {@value #TRAN_RECORD_LENGTH}
     *                                  bytes (short or long record;
     *                                  mirrors the COBOL
     *                                  {@code FD RECORD CONTAINS 350}
     *                                  validation)
     * @throws IllegalArgumentException if any 350-byte record fails the
     *                                  {@link TranRecord#parse(byte[])}
     *                                  contract during the repository
     *                                  refresh (malformed numeric
     *                                  field, invalid timestamp,
     *                                  malformed zoned decimal, etc.)
     */
    public int execute() {
        LOG.info("COMBTRAN start: backup={}, systran={}, combined={}",
                backupInputPath, systranInputPath, combinedOutputPath);
        Instant start = Instant.now();

        // ----- STEP05R prelude: concatenate the two inputs in JCL DD order -----
        // Capacity 0; the ArrayList grows on demand. Records are appended
        // strictly in arrival order so List.sort's stability preserves the
        // JCL DD concatenation tiebreaker (backup-first, systran-second).
        List<byte[]> allRecords = new ArrayList<>();
        int backupCount = readFixedWidth(backupInputPath, allRecords);
        int systranCount = readFixedWidth(systranInputPath, allRecords);
        int total = allRecords.size();
        LOG.info("COMBTRAN read: backup={}, systran={}, total={}",
                backupCount, systranCount, total);

        // ----- STEP05R: stable sort by TRAN-ID ascending -----
        // List.sort is documented to be a stable mergesort (TimSort) in the
        // Java SE specification — matching DFSORT's stable-sort semantics
        // for equal keys (the SORT card has no EQUALS=NO override).
        // Parallel sort variants (Arrays.parallelSort, virtual-thread fan-
        // out) are NOT used here: per AAP §0.1.3 they would risk breaking
        // the stable-sort tiebreaker for equal TRAN-IDs, which is part of
        // the observable byte-for-byte output contract.
        allRecords.sort(byTranIdAscending());

        // ----- STEP05R epilogue: write SORTOUT byte-for-byte -----
        writeFixedWidth(combinedOutputPath, allRecords);
        LOG.info("COMBTRAN wrote {} records to {}",
                allRecords.size(), combinedOutputPath);

        // ----- STEP10: IDCAMS REPRO equivalent — refresh the live TRANSACT KSDS -----
        refreshTransactRepository(allRecords);

        Duration elapsed = Duration.between(start, Instant.now());
        LOG.info("COMBTRAN end: total={}, elapsed={}", total, elapsed);
        return total;
    }

    // -----------------------------------------------------------------------
    // TRAN-ID comparator — byte-wise unsigned ascending on the first 16 bytes
    // -----------------------------------------------------------------------

    /**
     * Returns a {@link Comparator} that orders 350-byte {@code TRAN-RECORD}
     * buffers in ascending byte-wise unsigned order on the first
     * {@value #TRAN_ID_LENGTH} bytes (the {@code TRAN-ID} field).
     *
     * <p>The comparator is hand-written (rather than expressed via
     * {@code Comparator.comparing(...)} on a derived key) to (a) avoid
     * allocating an intermediate {@link String} or {@code byte[]} per
     * compare invocation &mdash; the comparator is invoked
     * {@code O(N log N)} times across the sort &mdash; and (b) make the
     * byte-wise UNSIGNED comparison explicit. Java's {@code byte} is
     * signed, so a naive direct comparison of two negative bytes (e.g.,
     * EBCDIC high-bit-set characters) would produce reversed order;
     * masking with {@code & 0xFF} promotes each byte into the
     * {@code [0, 255]} integer range and produces the same order
     * DFSORT's {@code CH} collating sequence does on the underlying
     * input encoding (ASCII or EBCDIC).
     *
     * <h3>Encoding-neutrality note</h3>
     * <p>For ASCII data (the migration's default per AAP &sect;0.6.5),
     * byte-wise unsigned order coincides exactly with ASCII lexical
     * order, so this comparator produces an output that is
     * byte-for-byte identical to DFSORT's. For EBCDIC data (when the
     * file-based adapter loads EBCDIC fixtures decoded via
     * {@code IBM-1047}), byte-wise unsigned order on the EBCDIC bytes
     * differs from ASCII lexical order &mdash; but the comparator
     * preserves the input encoding's byte-level lexicographic order
     * verbatim, which is exactly what DFSORT does on the equivalent
     * EBCDIC input. Either way, the output is byte-for-byte preserved
     * per AAP &sect;0.1.3.
     *
     * @return a stateless {@link Comparator} that returns negative,
     *         zero, or positive integers per the standard contract
     */
    private static Comparator<byte[]> byTranIdAscending() {
        return (a, b) -> {
            // Compare exactly TRAN_ID_LENGTH bytes starting at
            // TRAN_ID_OFFSET, byte-wise unsigned. Loop is intentionally
            // explicit (rather than Arrays.compareUnsigned over a slice)
            // to avoid intermediate byte[] allocation and to make the
            // comparison window crystal-clear to readers auditing the
            // sort-fidelity contract.
            for (int i = 0; i < TRAN_ID_LENGTH; i++) {
                int ai = a[TRAN_ID_OFFSET + i] & 0xFF;
                int bi = b[TRAN_ID_OFFSET + i] & 0xFF;
                if (ai != bi) {
                    return Integer.compare(ai, bi);
                }
            }
            return 0;
        };
    }

    // -----------------------------------------------------------------------
    // Fixed-width reader — STEP05R SORTIN equivalent (per DD)
    // -----------------------------------------------------------------------

    /**
     * Reads {@code inputPath} as a fixed-width 350-byte record stream
     * and appends each record to {@code sink} in file order.
     *
     * <p>The reader enforces the 350-byte record contract strictly:
     * <ul>
     *   <li>The file MUST exist &mdash; a missing file raises
     *       {@link UncheckedIOException}, matching the z/OS hard
     *       failure for {@code DISP=SHR} on a non-existent dataset.</li>
     *   <li>The file size MUST be an integral multiple of
     *       {@value #TRAN_RECORD_LENGTH} &mdash; otherwise
     *       {@link IllegalStateException} is raised. This mirrors the
     *       COBOL {@code FD RECORD CONTAINS 350 CHARACTERS} contract;
     *       a short or long record indicates corruption upstream.</li>
     *   <li>Each {@link SeekableByteChannel#read(ByteBuffer) read}
     *       MUST return exactly {@value #TRAN_RECORD_LENGTH} bytes or
     *       end-of-stream (-1); a partial read is treated as
     *       corruption.</li>
     * </ul>
     *
     * <p>Each successfully read record is materialised as a fresh
     * {@code byte[TRAN_RECORD_LENGTH]} and appended to {@code sink} in
     * arrival order. Records are NOT parsed into {@link TranRecord} at
     * this stage &mdash; the sort key is the raw byte slice, so
     * deferring the {@link TranRecord#parse(byte[]) parse} until the
     * repository refresh saves N parse-and-re-encode round trips
     * across the sort.
     *
     * @param inputPath the file to read; must be a regular file
     *                  containing 0 or more 350-byte records
     * @param sink      the list to append parsed records to (passed in
     *                  so the caller controls the buffer capacity and
     *                  the JCL DD concatenation order)
     * @return the number of records read from this file (added to
     *         {@code sink})
     * @throws UncheckedIOException  if {@code inputPath} does not exist
     *                               or any I/O error occurs while
     *                               reading
     * @throws IllegalStateException if the file size is not an integral
     *                               multiple of
     *                               {@value #TRAN_RECORD_LENGTH}, or
     *                               any individual read returns fewer
     *                               bytes than expected (partial
     *                               record)
     */
    private int readFixedWidth(Path inputPath, List<byte[]> sink) {
        if (!Files.exists(inputPath)) {
            // JCL DISP=SHR on a non-existent file is a hard failure on
            // z/OS — the job step abends. The Java translation raises
            // an UncheckedIOException carrying the same diagnostic so
            // the composition root can surface a non-zero exit status.
            throw new UncheckedIOException(
                    new IOException("Input file does not exist: " + inputPath));
        }
        int count = 0;
        try (SeekableByteChannel ch = Files.newByteChannel(inputPath, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size % TRAN_RECORD_LENGTH != 0L) {
                throw new IllegalStateException(
                        "Input " + inputPath + " size=" + size
                                + " is not a multiple of TRAN_RECORD_LENGTH="
                                + TRAN_RECORD_LENGTH);
            }
            // A single reusable ByteBuffer is allocated once outside the
            // loop; the buffer is cleared and refilled for each record.
            ByteBuffer buf = ByteBuffer.allocate(TRAN_RECORD_LENGTH);
            while (true) {
                buf.clear();
                int read = ch.read(buf);
                if (read == -1) {
                    // Clean end-of-stream.
                    break;
                }
                if (read != TRAN_RECORD_LENGTH) {
                    throw new IllegalStateException(
                            "Short read from " + inputPath + ": got " + read
                                    + " bytes, expected " + TRAN_RECORD_LENGTH);
                }
                // Materialise a fresh byte[] so subsequent reads into the
                // shared ByteBuffer do not overwrite this record's bytes.
                byte[] record = new byte[TRAN_RECORD_LENGTH];
                buf.flip();
                buf.get(record);
                sink.add(record);
                count++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + inputPath, e);
        }
        return count;
    }

    // -----------------------------------------------------------------------
    // Fixed-width writer — STEP05R SORTOUT equivalent
    // -----------------------------------------------------------------------

    /**
     * Writes {@code records} to {@code outputPath} as a fixed-width
     * 350-byte record stream in list order.
     *
     * <p>Pre-write housekeeping mirrors the JCL
     * {@code DISP=(NEW,CATLG,DELETE)} for {@code SORTOUT}:
     * <ul>
     *   <li>{@link Files#deleteIfExists(Path)} removes any pre-existing
     *       file at the output path (analogous to "NEW" allocating a
     *       fresh dataset; on re-runs, the prior generation file is
     *       displaced).</li>
     *   <li>Parent directories are created via
     *       {@link Files#createDirectories(Path)} if they do not yet
     *       exist (analogous to the SMS allocation of the dataset's
     *       containing storage class).</li>
     *   <li>The output channel is opened with
     *       {@link StandardOpenOption#CREATE_NEW} so that any race with
     *       a concurrent writer fails immediately rather than
     *       silently overwriting.</li>
     * </ul>
     *
     * <p>Each record is validated to be exactly
     * {@value #TRAN_RECORD_LENGTH} bytes before being written, as a
     * defensive double-check against {@link #readFixedWidth(Path, List)}
     * having appended a malformed buffer. The
     * {@link SeekableByteChannel#write(ByteBuffer)} call is wrapped in
     * a {@code while (buf.hasRemaining())} loop because non-blocking
     * channels MAY return short writes; on a regular blocking file
     * channel the loop typically iterates exactly once.
     *
     * @param outputPath the file to write; any pre-existing file is
     *                   removed first
     * @param records    the sorted list of records to write, in list
     *                   order
     * @throws UncheckedIOException  if the file cannot be deleted,
     *                               created, or written
     * @throws IllegalStateException if any record's length is not
     *                               {@value #TRAN_RECORD_LENGTH} bytes
     */
    private void writeFixedWidth(Path outputPath, List<byte[]> records) {
        try {
            Files.deleteIfExists(outputPath);
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (SeekableByteChannel ch = Files.newByteChannel(
                    outputPath,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                for (byte[] record : records) {
                    if (record.length != TRAN_RECORD_LENGTH) {
                        throw new IllegalStateException(
                                "Record length mismatch: got " + record.length
                                        + ", expected " + TRAN_RECORD_LENGTH);
                    }
                    ByteBuffer buf = ByteBuffer.wrap(record);
                    while (buf.hasRemaining()) {
                        ch.write(buf);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + outputPath, e);
        }
    }

    // -----------------------------------------------------------------------
    // Repository refresh — STEP10 IDCAMS REPRO equivalent
    // -----------------------------------------------------------------------

    /**
     * Loads the sorted combined records into the live
     * {@link TransactionRepository} by invoking
     * {@link TransactionRepository#save(TranRecord)} once per record &mdash;
     * the Java translation of the STEP10 JCL command
     * {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)}.
     *
     * <p>Each {@code byte[350]} buffer is parsed via the round-trip-
     * invariant {@link TranRecord#parse(byte[])} factory before being
     * handed to the port; this validates the record format
     * (fixed-width field constraints, zoned-decimal sign overpunch on
     * {@code TRAN-AMT}, timestamp pattern on {@code TRAN-ORIG-TS} and
     * {@code TRAN-PROC-TS}) and produces the typed domain record that
     * the repository contract expects.
     *
     * <p>The repository's {@code save} semantics are
     * implementation-defined (see the port's class-level Javadoc):
     * the file-based KSDS adapter performs an insert-or-REWRITE
     * upsert (matching IDCAMS REPRO's behaviour when the target KSDS
     * already contains a record with the same key), while the
     * sequential-output adapter silently appends. The composition
     * root selects the correct adapter at startup; this driver does
     * not concern itself with the choice.
     *
     * <p>A parse failure raises {@link IllegalArgumentException}
     * propagated from {@link TranRecord#parse(byte[])} &mdash; this
     * is intentional and mirrors the COBOL data-validation contract
     * (a malformed record in the input stream is a hard failure, not
     * a silently-skipped row).
     *
     * @param combinedRecords the sorted list of 350-byte combined
     *                        records produced by
     *                        {@link #execute()}; never modified by
     *                        this method
     * @throws IllegalArgumentException if any record fails the
     *                                  {@link TranRecord#parse(byte[])}
     *                                  contract
     * @throws RuntimeException         if the underlying repository
     *                                  adapter raises an I/O failure
     *                                  while persisting a record (see
     *                                  {@link TransactionRepository#save(TranRecord)}
     *                                  for the error-semantics contract)
     */
    private void refreshTransactRepository(List<byte[]> combinedRecords) {
        // STEP10 IDCAMS REPRO: load combined sequential file into
        // TRANSACT.VSAM.KSDS. Implemented as a per-record save through
        // the port. The TransactionRepository's class-level Javadoc
        // documents the upsert (file-based KSDS adapter) vs append
        // (sequential adapter) decision; this driver is encoding-
        // neutral and works correctly with both.
        int saved = 0;
        for (byte[] bytes : combinedRecords) {
            TranRecord rec = TranRecord.parse(bytes);
            transactionRepository.save(rec);
            saved++;
        }
        LOG.info("COMBTRAN repository refresh: saved={}", saved);
    }
}
