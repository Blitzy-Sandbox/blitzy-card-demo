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

import com.blitzy.carddemo.domain.port.TransactionCategoryBalanceRepository;
import com.blitzy.carddemo.domain.record.TranCatBalRecord;
import com.blitzy.carddemo.domain.util.Decimals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plain-Java batch driver that translates the {@code app/jcl/PRTCATBL.jcl}
 * three-step job into a single {@link #execute()} method while preserving
 * byte-for-byte fidelity with the original DFSORT / IDCAMS flow.
 *
 * <h2>Source lineage ({@code app/jcl/PRTCATBL.jcl})</h2>
 *
 * <p>The originating JCL job has three sequential steps:
 *
 * <h3>DELDEF &mdash; IEFBR14 delete previous report</h3>
 * {@snippet lang = "jcl":
 * //DELDEF   EXEC PGM=IEFBR14
 * //THEFILE  DD DISP=(MOD,DELETE),
 * //         UNIT=SYSDA,
 * //         SPACE=(TRK,(1,1),RLSE),
 * //         DSN=AWS.M2.CARDDEMO.TCATBALF.REPT
 * }
 * Removes the previous {@code TCATBALF.REPT} file so STEP10R can create a
 * fresh output. The Java translation performs the equivalent
 * {@link Files#deleteIfExists(Path)} on {@code outputPath} at the head of
 * {@link #writeReport(List)}.
 *
 * <h3>STEP05R &mdash; PROC=REPROC: IDCAMS REPRO unload</h3>
 * {@snippet lang = "jcl":
 * //STEP05R EXEC PROC=REPROC,
 * // CNTLLIB=AWS.M2.CARDDEMO.CNTL
 * //PRC001.FILEIN  DD DISP=SHR,
 * //        DSN=AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS
 * //PRC001.FILEOUT DD DISP=(NEW,CATLG,DELETE),
 * //        UNIT=SYSDA,
 * //        DCB=(LRECL=50,RECFM=FB,BLKSIZE=0),
 * //        SPACE=(CYL,(1,1),RLSE),
 * //        DSN=AWS.M2.CARDDEMO.TCATBALF.BKUP(+1)
 * }
 * Unloads the live {@code TCATBALF.VSAM.KSDS} dataset to a flat-file
 * backup. In the Java translation the
 * {@link TransactionCategoryBalanceRepository#streamSequential()} port call
 * collapses STEP05R and STEP10R into a single sequential read &mdash; the
 * repository's port contract already returns records in ascending
 * composite-key order matching the KSDS index. No intermediate backup
 * file is materialised because the data never leaves the JVM heap between
 * read and report-write.
 *
 * <h3>STEP10R &mdash; PGM=SORT: DFSORT sort + format + write</h3>
 * {@snippet lang = "jcl":
 * //STEP10R  EXEC PGM=SORT
 * //SORTIN   DD DISP=SHR,
 * //         DSN=AWS.M2.CARDDEMO.TCATBALF.BKUP(+1)
 * //SYMNAMES DD *
 * TRANCAT-ACCT-ID,1,11,ZD
 * TRANCAT-TYPE-CD,12,2,CH
 * TRANCAT-CD,14,4,ZD
 * TRAN-CAT-BAL,18,11,ZD
 * //SYSIN    DD *
 *  SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)
 *  OUTREC FIELDS=(TRANCAT-ACCT-ID,X,
 *      TRANCAT-TYPE-CD,X,
 *      TRANCAT-CD,X,
 *      TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),9X)
 * //SORTOUT  DD DISP=(NEW,CATLG,DELETE),
 * //         UNIT=SYSDA,
 * //         DCB=(LRECL=40,RECFM=FB,BLKSIZE=0),
 * //         SPACE=(CYL,(1,1),RLSE),
 * //         DSN=AWS.M2.CARDDEMO.TCATBALF.REPT
 * }
 * STEP10R sorts the backup file in ascending composite-key order
 * ({@code TRANCAT-ACCT-ID}, then {@code TRANCAT-TYPE-CD}, then
 * {@code TRANCAT-CD}) and reformats each input record into a fixed
 * 40-byte ASCII report line via the DFSORT {@code OUTREC FIELDS=(...)}
 * specification. The Java translation in {@link #execute()} mirrors this
 * with a {@link Comparator}-driven {@link Stream#sorted(Comparator)} pass
 * (single-threaded, stable TimSort) followed by a per-record
 * {@link #formatLine(TranCatBalRecord)} call.
 *
 * <h2>Output record layout (40 bytes per AAP &sect;0.6.5)</h2>
 *
 * <p>Each output line is exactly {@link #OUTPUT_RECORD_LENGTH} bytes laid
 * out per the DFSORT {@code OUTREC FIELDS=(...)} spec, plus the JCL's
 * {@code SORTOUT DCB=(LRECL=40,RECFM=FB,BLKSIZE=0)}:
 * <pre>
 *   positions 1-11:  TRANCAT-ACCT-ID  (PIC 9(11), ZD, zero-padded LEFT)
 *   position  12:    space            (DFSORT X separator)
 *   positions 13-14: TRANCAT-TYPE-CD  (PIC X(02), CH, space-padded RIGHT)
 *   position  15:    space            (DFSORT X separator)
 *   positions 16-19: TRANCAT-CD       (PIC 9(04), ZD, zero-padded LEFT)
 *   position  20:    space            (DFSORT X separator)
 *   positions 21-32: TRAN-CAT-BAL     (PIC S9(09)V99 via EDIT=(TTTTTTTTT.TT) = 12 chars)
 *   positions 33-40: 8 trailing spaces (LRECL=40 - 32 = 8 padding bytes)
 * </pre>
 *
 * <h3>LRECL reconciliation (40 vs nominal 41)</h3>
 * <p>The DFSORT {@code OUTREC} clause declares {@code 9X} of trailing
 * padding (9 spaces), which combined with the four data fields (11 + 1 +
 * 2 + 1 + 4 + 1 + 12 = 32) yields a nominal record length of 41 bytes.
 * However, the {@code SORTOUT DCB} declares {@code LRECL=40}. The DCB is
 * the authoritative source for the output record size, so this
 * translation honours {@code LRECL=40} by writing exactly 8 trailing
 * spaces (32 + 8 = 40 bytes). DFSORT applies the same truncation when
 * the OUTREC pad specification overruns the LRECL; the nominal {@code 9X}
 * is therefore best read as "up to 9X of padding, truncated to LRECL." A
 * note on this resolution is recorded in {@code java/MIGRATION_NOTES.md}
 * pending verification against a captured golden-record fixture.
 *
 * <h2>EDIT-mask formatting (TRAN-CAT-BAL)</h2>
 *
 * <p>The {@code TRAN-CAT-BAL} field is rendered via
 * {@link Decimals#formatEditMaskUnsigned(BigDecimal, int, int)} with
 * {@code (integerDigits=9, decimalDigits=2)} to produce exactly 12 ASCII
 * characters: 9 zero-padded integer digits, a literal {@code '.'}, and 2
 * zero-padded fractional digits. This matches DFSORT's
 * {@code EDIT=(TTTTTTTTT.TT)} pattern semantics where {@code T} digit
 * positions are unsigned (no sign character in the output). Negative
 * balances &mdash; not expected for TCATBAL per AAP &sect;0.6.1 &mdash;
 * are emitted as their absolute value, the DFSORT default for unsigned
 * EDIT patterns.
 *
 * <p>All monetary formatting is routed through {@link Decimals}
 * (AAP &sect;0.6.1) to maintain the byte-for-byte parity invariant.
 * {@link String#format(String, Object...)} with {@code "%.2f"} or
 * {@link java.text.DecimalFormat} are <strong>forbidden</strong> because
 * they use {@code double} internally, violating AAP &sect;0.6.7 and
 * &sect;0.7.4.
 *
 * <h2>Sort semantics (DFSORT SORT FIELDS preservation)</h2>
 *
 * <p>The DFSORT {@code SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,
 * TRANCAT-CD,A)} clause specifies ascending lexicographic order by
 * composite key. The Java {@link #compositeKeyComparator()} preserves
 * this exactly:
 * <ul>
 *   <li>Primary: {@code Comparator.comparingLong(...)} on
 *       {@code TRANCAT-ACCT-ID} (long). The COBOL field is
 *       {@code PIC 9(11)}, unsigned numeric, so byte-lexicographic order
 *       over zero-padded ASCII digits and numeric order coincide.</li>
 *   <li>Secondary: {@code thenComparing(...)} on
 *       {@code TRANCAT-TYPE-CD} (String). The COBOL field is
 *       {@code PIC X(02)}, character, so String natural ordering matches
 *       DFSORT's {@code CH} collating sequence over ASCII bytes.</li>
 *   <li>Tertiary: {@code thenComparingInt(...)} on {@code TRANCAT-CD}
 *       (int). The COBOL field is {@code PIC 9(04)}, unsigned numeric, so
 *       byte-lexicographic and numeric order coincide.</li>
 * </ul>
 *
 * <p>The {@link Stream#sorted(Comparator)} call performs the sort on a
 * SINGLE thread using {@link java.util.List#sort(Comparator)}'s stable
 * TimSort under the hood. <strong>Virtual-thread fan-out is NOT used in
 * this driver.</strong> Per AAP &sect;0.1.3 ("virtual threads are NOT a
 * license to reorder records, change sort orders, or break sequencing")
 * the output ordering is observable and reordering would change the
 * byte-for-byte report content. The sort is idempotent on a
 * {@code streamSequential()} input that is already in composite-key
 * order, so the redundant pass is cheap and preserves parity with the
 * JCL flow even if the underlying adapter were ever to relax the
 * ordering invariant.
 *
 * <h2>I/O strategy ({@code java.nio.file})</h2>
 *
 * <p>Per AAP &sect;0.6.5 all file I/O uses {@code java.nio.file}; the
 * {@code java.io.File} type is <strong>forbidden</strong>. The output is
 * opened with {@link StandardOpenOption#CREATE_NEW} and
 * {@link StandardOpenOption#WRITE} to ensure the file is created fresh
 * each run (matching the JCL {@code DISP=(NEW,CATLG,DELETE)}). Any
 * existing file at {@code outputPath} is removed via
 * {@link Files#deleteIfExists(Path)} prior to the open, mirroring the
 * DELDEF {@code DISP=(MOD,DELETE)} idempotency.
 *
 * <p>The output bytes are produced via {@link StandardCharsets#US_ASCII}
 * because the source fixtures are ASCII-transcoded and the JCL
 * {@code TCATBALF.REPT} dataset is a flat text file with no EBCDIC
 * dependency. Production EBCDIC inputs are pre-transcoded by the
 * {@code EbcdicTranscoder} adapter in {@code carddemo-adapter-file}
 * before they reach this batch driver via the repository port.
 *
 * <h2>Architectural rule (AAP &sect;0.3.1)</h2>
 *
 * <p>This class:
 * <ul>
 *   <li>Lives in {@code carddemo-batch} (orchestration ring).</li>
 *   <li>Depends on {@code carddemo-domain} (innermost ring): the
 *       {@link TransactionCategoryBalanceRepository} port, the
 *       {@link TranCatBalRecord} domain record, and the {@link Decimals}
 *       utility.</li>
 *   <li>Does <strong>not</strong> depend on any adapter module
 *       ({@code carddemo-adapter-file}, {@code carddemo-adapter-db}) &mdash;
 *       the concrete repository implementation is wired by the
 *       composition root ({@code carddemo-app}) and passed in via the
 *       constructor.</li>
 *   <li>Does <strong>not</strong> use Spring, Spring Batch, or any
 *       framework container per AAP &sect;0.6.12 architectural override.</li>
 *   <li>Does <strong>not</strong> carry a {@code @CobolProgram}
 *       annotation: this driver represents a JCL job (PRTCATBL.jcl), not
 *       a COBOL {@code PROGRAM-ID}.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class is immutable after construction (both fields are
 * {@code final} and the repository is a constructor-injected port).
 * {@link #execute()} is safe to call from any single thread but is not
 * intended for concurrent invocation: the output file is opened with
 * {@code CREATE_NEW}, so a second concurrent call would race on file
 * creation and one of the calls would fail with an
 * {@link UncheckedIOException} wrapping a
 * {@link java.nio.file.FileAlreadyExistsException}. The repository's
 * thread-safety contract is the repository's responsibility per the
 * port's class-level Javadoc.
 *
 * <h2>Forbidden in this driver (AAP &sect;0.6.7, &sect;0.7.4)</h2>
 * <ul>
 *   <li>Spring / Spring Batch / framework container &mdash;
 *       AAP &sect;0.6.12 explicit override.</li>
 *   <li>{@code ThreadLocal} &mdash; {@code ScopedValue} replaces it; this
 *       driver does not propagate context, so neither is needed here.</li>
 *   <li>{@code double} / {@code float} for monetary values &mdash; only
 *       {@link BigDecimal} routed through {@link Decimals}.</li>
 *   <li>{@code String.format("%.2f", ...)} / {@link java.text.DecimalFormat}
 *       on monetary values &mdash; they use {@code double} internally.</li>
 *   <li>{@code java.util.Date} / {@code Calendar} &mdash; only
 *       {@link Instant} / {@link Duration} from {@code java.time}.</li>
 *   <li>{@code java.io.File} &mdash; only {@code java.nio.file}.</li>
 *   <li>{@link System#out} / {@link System#err} &mdash; only SLF4J.</li>
 *   <li>Reflection, dynamic proxies, Lombok.</li>
 *   <li>{@code Executors.newVirtualThreadPerTaskExecutor()} or any
 *       parallel fan-out &mdash; sequential output ordering is observable
 *       per AAP &sect;0.1.3 and reordering is forbidden.</li>
 *   <li>Preview features (no {@code --enable-preview}).</li>
 *   <li>{@code @CobolProgram} annotation &mdash; this is a JCL driver,
 *       not a COBOL program translation.</li>
 * </ul>
 *
 * @see TransactionCategoryBalanceRepository
 * @see TranCatBalRecord
 * @see Decimals#formatEditMaskUnsigned(BigDecimal, int, int)
 * @since 1.0.0
 */
public final class PrintTcatBalBatch {

    // -----------------------------------------------------------------------
    // SLF4J logger — observability per AAP §0.5.1 and AAP §0.6.6
    // -----------------------------------------------------------------------

    /**
     * Class-level SLF4J logger. Emits structured INFO-level start / end
     * markers including the output path, line count, and elapsed
     * {@link Duration}. The concrete logging backend (Logback) is supplied
     * by the composition root ({@code carddemo-app}); this module depends
     * only on the SLF4J facade.
     */
    private static final Logger LOG = LoggerFactory.getLogger(PrintTcatBalBatch.class);

    // -----------------------------------------------------------------------
    // Public layout constants — DFSORT OUTREC dimensions
    // -----------------------------------------------------------------------

    /**
     * Fixed output record length in bytes per the {@code PRTCATBL.jcl}
     * {@code SORTOUT DCB=(LRECL=40,RECFM=FB,BLKSIZE=0)} declaration
     * {@code [app/jcl/PRTCATBL.jcl:L61]}. Every line produced by
     * {@link #formatLine(TranCatBalRecord)} is exactly 40 bytes long;
     * {@link #writeReport(List)} asserts this invariant before each write
     * and raises {@link IllegalStateException} if violated.
     */
    public static final int OUTPUT_RECORD_LENGTH = 40;

    // -----------------------------------------------------------------------
    // Private layout constants — derived from the DFSORT SYMNAMES + OUTREC
    // -----------------------------------------------------------------------

    /**
     * Line separator for FB-format report output: a single LF
     * ({@code 0x0A}) byte. Written after every fixed-width line so the
     * resulting file is greppable on a POSIX shell. The line terminator is
     * NOT part of the {@link #OUTPUT_RECORD_LENGTH} budget &mdash; total
     * bytes per line = {@link #OUTPUT_RECORD_LENGTH} + 1 separator byte =
     * 41 bytes on disk. This matches the production behaviour of the JCL
     * {@code RECFM=FB} dataset when transferred to a flat-file system; the
     * RECFM byte sequence on z/OS has no inter-record separator but the
     * downstream consumer expects one on the host OS filesystem.
     */
    private static final byte LINE_SEPARATOR = (byte) 0x0A;

    /**
     * Number of decimal digits after the decimal point in the
     * {@code TRAN-CAT-BAL} EDIT mask {@code EDIT=(TTTTTTTTT.TT)}: 2 digits
     * (the {@code V99} suffix on {@code PIC S9(09)V99}). Equal to
     * {@link TranCatBalRecord#TRAN_CAT_BAL_SCALE}.
     */
    private static final int TRAN_CAT_BAL_DECIMAL_DIGITS = 2;

    /**
     * Number of decimal digits before the decimal point in the
     * {@code TRAN-CAT-BAL} EDIT mask {@code EDIT=(TTTTTTTTT.TT)}: 9 digits
     * (the {@code S9(09)} prefix on {@code PIC S9(09)V99}).
     */
    private static final int TRAN_CAT_BAL_INTEGER_DIGITS = 9;

    /**
     * Total width (in characters) of the formatted {@code TRAN-CAT-BAL}
     * field, including the embedded decimal point.
     * {@code EDIT=(TTTTTTTTT.TT)} = 9 integer digits + {@code '.'} + 2
     * decimal digits = 12 characters. Equal to
     * {@code TRAN_CAT_BAL_INTEGER_DIGITS + 1 + TRAN_CAT_BAL_DECIMAL_DIGITS}.
     */
    private static final int TRAN_CAT_BAL_WIDTH = 12;

    /**
     * Width of the {@code TRANCAT-ACCT-ID} field (DFSORT SYMNAMES
     * {@code TRANCAT-ACCT-ID,1,11,ZD} &rarr; 11 bytes).
     */
    private static final int ACCT_ID_WIDTH = 11;

    /**
     * Width of the {@code TRANCAT-TYPE-CD} field (DFSORT SYMNAMES
     * {@code TRANCAT-TYPE-CD,12,2,CH} &rarr; 2 bytes).
     */
    private static final int TYPE_CD_WIDTH = 2;

    /**
     * Width of the {@code TRANCAT-CD} field (DFSORT SYMNAMES
     * {@code TRANCAT-CD,14,4,ZD} &rarr; 4 bytes).
     */
    private static final int CAT_CD_WIDTH = 4;

    // -----------------------------------------------------------------------
    // Private final fields — constructor-injected collaborators
    // -----------------------------------------------------------------------

    /**
     * The port abstraction over the {@code TCATBALF} VSAM KSDS dataset.
     * Injected via the constructor per hexagonal-architecture
     * dependency-inversion (AAP &sect;0.3.2 Repository pattern).
     * {@link TransactionCategoryBalanceRepository#streamSequential()} is
     * the only port method used by this driver; the concrete adapter
     * (file or JDBC) is selected by the composition root.
     */
    private final TransactionCategoryBalanceRepository tranCatBalRepository;

    /**
     * Filesystem location of the {@code TCATBALF.REPT} output file. Set
     * by the composition root from {@code application.properties} (key
     * {@code carddemo.file.printcatbl.path}); supplied via the constructor
     * so this driver is fully testable without environment dependencies.
     */
    private final Path outputPath;

    // -----------------------------------------------------------------------
    // Constructor — constructor injection per AAP §0.6.12 (no framework)
    // -----------------------------------------------------------------------

    /**
     * Constructs a new {@code PrintTcatBalBatch} bound to the supplied
     * repository port and output file path. Both arguments are required;
     * neither is allowed to be {@code null}.
     *
     * <p>This is plain constructor injection per AAP &sect;0.1.1 and
     * &sect;0.6.12 ("hexagonal Java 25 with plain factories and
     * constructor injection (no Spring container)"). No framework
     * container is involved; the composition root in {@code carddemo-app}
     * is responsible for wiring the concrete repository implementation
     * and the resolved output path before invoking {@link #execute()}.
     *
     * @param tranCatBalRepository the {@code TCATBALF} port; must be
     *                             non-{@code null}. The repository's
     *                             {@link TransactionCategoryBalanceRepository#streamSequential()}
     *                             method must return records in
     *                             ascending composite-key order per the
     *                             port's class-level Javadoc invariant.
     * @param outputPath           the destination path of the
     *                             {@code TCATBALF.REPT} output file; must
     *                             be non-{@code null}. Parent directories
     *                             are created on demand by
     *                             {@link #writeReport(List)} via
     *                             {@link Files#createDirectories(Path,
     *                             java.nio.file.attribute.FileAttribute...)}.
     * @throws NullPointerException if either argument is {@code null}
     */
    public PrintTcatBalBatch(
            TransactionCategoryBalanceRepository tranCatBalRepository,
            Path outputPath) {
        this.tranCatBalRepository = Objects.requireNonNull(
                tranCatBalRepository, "tranCatBalRepository");
        this.outputPath = Objects.requireNonNull(outputPath, "outputPath");
    }

    // -----------------------------------------------------------------------
    // Public API — execute() runs the PRTCATBL job body
    // -----------------------------------------------------------------------

    /**
     * Executes the PRTCATBL job body: reads every record from the
     * {@code TCATBALF} repository via
     * {@link TransactionCategoryBalanceRepository#streamSequential()},
     * sorts the stream by the composite key
     * {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD)} ascending
     * (idempotent on an already-sorted port output), formats each record
     * into a fixed 40-byte report line via the
     * {@code EDIT=(TTTTTTTTT.TT)} mask equivalent, and writes the result
     * to {@link #outputPath}.
     *
     * <p>If a file already exists at {@code outputPath} it is removed
     * first (DELDEF idempotency); the parent directory is created on
     * demand. The output file is then opened with
     * {@link StandardOpenOption#CREATE_NEW} + {@link StandardOpenOption#WRITE}
     * so a concurrent call against the same path would fail rather than
     * silently overwrite.
     *
     * <p>Sequential, single-threaded execution. Virtual-thread fan-out is
     * <strong>not</strong> used here per AAP &sect;0.1.3: the output
     * order is observable and reordering would invalidate the
     * byte-for-byte parity contract. The sort is performed by
     * {@link Stream#sorted(Comparator)} (stable TimSort under the hood)
     * on the same thread that invokes {@code execute()}.
     *
     * <p>The repository stream is wrapped in try-with-resources so the
     * underlying file channel / cursor is released even if an exception
     * is thrown during sort or write. This honours the
     * {@link Stream#close()} contract documented on
     * {@link TransactionCategoryBalanceRepository#streamSequential()}.
     *
     * <p>Logs:
     * <ul>
     *   <li>INFO "PRTCATBL start: outputPath={}" with the resolved
     *       output path at entry.</li>
     *   <li>INFO "PRTCATBL end: linesWritten={}, elapsed={}" with the
     *       count of report lines written and the
     *       {@link Duration#between(java.time.temporal.Temporal,
     *       java.time.temporal.Temporal)} elapsed
     *       {@link Instant#now()}-based timing at exit.</li>
     * </ul>
     *
     * @return the number of report lines written to {@link #outputPath};
     *         equal to the total number of records returned by the
     *         repository's {@link TransactionCategoryBalanceRepository#streamSequential()}
     *         call (since every record is written verbatim with no
     *         filtering)
     * @throws UncheckedIOException     if any filesystem operation
     *                                  (delete, create-directories,
     *                                  open, write) on
     *                                  {@link #outputPath} fails
     * @throws IllegalStateException    if any formatted line fails the
     *                                  {@link #OUTPUT_RECORD_LENGTH}
     *                                  invariant
     * @throws IllegalArgumentException if a record's
     *                                  {@link TranCatBalRecord#tranCatBal()}
     *                                  integer-part magnitude exceeds 9
     *                                  digits (which would overflow the
     *                                  {@code EDIT=(TTTTTTTTT.TT)} mask)
     */
    public int execute() {
        LOG.info("PRTCATBL start: outputPath={}", outputPath);
        Instant start = Instant.now();

        // STEP05R + STEP10R collapsed: read all records via the port (already
        // in ascending composite-key order per the port's class-level
        // Javadoc), apply the DFSORT SORT idempotently, materialize as an
        // immutable List for a deterministic write order, then write the
        // fixed-width report. The try-with-resources ensures the underlying
        // file channel / cursor is released even on exceptional exit.
        int linesWritten;
        try (Stream<TranCatBalRecord> stream = tranCatBalRepository.streamSequential()) {
            List<TranCatBalRecord> sorted = stream
                    .sorted(compositeKeyComparator())
                    .toList();
            linesWritten = writeReport(sorted);
        }

        Duration elapsed = Duration.between(start, Instant.now());
        LOG.info("PRTCATBL end: linesWritten={}, elapsed={}", linesWritten, elapsed);
        return linesWritten;
    }

    // -----------------------------------------------------------------------
    // Sort — DFSORT SORT FIELDS=(...,A,...,A,...,A) preservation
    // -----------------------------------------------------------------------

    /**
     * Returns a {@link Comparator} that orders {@link TranCatBalRecord}
     * values by the composite key
     * {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD)} ascending.
     * This is the exact translation of the JCL
     * {@code SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)}
     * clause {@code [app/jcl/PRTCATBL.jcl:L52]}.
     *
     * <p>Field-by-field comparator construction:
     * <ul>
     *   <li>{@link Comparator#comparingLong(java.util.function.ToLongFunction)}
     *       on {@link #accountId(TranCatBalRecord)} &mdash; primary key.
     *       The COBOL source field is unsigned numeric
     *       {@code PIC 9(11)}, so the {@code long} natural ordering
     *       matches DFSORT's {@code ZD} ascending sort byte-equivalently
     *       (zero-padded ASCII digits sort identically to numeric value).</li>
     *   <li>{@link Comparator#thenComparing(java.util.function.Function)}
     *       on {@link #typeCode(TranCatBalRecord)} (returns {@link String})
     *       &mdash; secondary key. The COBOL source field is
     *       {@code PIC X(02)}, character, so {@link String}'s natural
     *       (Unicode code-point) ordering matches DFSORT's {@code CH}
     *       ascending sort byte-equivalently because the values are 7-bit
     *       ASCII.</li>
     *   <li>{@link Comparator#thenComparingInt(java.util.function.ToIntFunction)}
     *       on {@link #categoryCode(TranCatBalRecord)} &mdash; tertiary
     *       key. The COBOL source field is unsigned numeric
     *       {@code PIC 9(04)}, so the {@code int} natural ordering
     *       matches DFSORT's {@code ZD} ascending sort.</li>
     * </ul>
     *
     * <p>The returned comparator is stateless and thread-safe.
     *
     * @return a {@link Comparator} that orders {@code TranCatBalRecord}
     *         values by ascending composite key
     */
    private static Comparator<TranCatBalRecord> compositeKeyComparator() {
        return Comparator
                .comparingLong(PrintTcatBalBatch::accountId)
                .thenComparing(PrintTcatBalBatch::typeCode)
                .thenComparingInt(PrintTcatBalBatch::categoryCode);
    }

    /**
     * Extracts the {@code TRANCAT-ACCT-ID} component of the composite key
     * from a record &mdash; the primary sort key.
     *
     * @param r the record (must not be {@code null}; the caller's
     *          comparator never invokes this with {@code null} input)
     * @return the unsigned 11-digit account ID
     */
    private static long accountId(TranCatBalRecord r) {
        return r.tranCatKey().trancatAcctId();
    }

    /**
     * Extracts the {@code TRANCAT-TYPE-CD} component of the composite key
     * from a record &mdash; the secondary sort key.
     *
     * @param r the record (must not be {@code null}; the caller's
     *          comparator never invokes this with {@code null} input)
     * @return the 2-character transaction-type code; never {@code null}
     *         per the {@code TranCatBalRecord.TranCatKey} canonical
     *         constructor invariant
     */
    private static String typeCode(TranCatBalRecord r) {
        return r.tranCatKey().trancatTypeCd();
    }

    /**
     * Extracts the {@code TRANCAT-CD} component of the composite key from
     * a record &mdash; the tertiary sort key.
     *
     * @param r the record (must not be {@code null}; the caller's
     *          comparator never invokes this with {@code null} input)
     * @return the unsigned 4-digit category code
     */
    private static int categoryCode(TranCatBalRecord r) {
        return r.tranCatKey().trancatCd();
    }

    // -----------------------------------------------------------------------
    // Write report — DELDEF + STEP10R SORTOUT equivalent
    // -----------------------------------------------------------------------

    /**
     * Writes the supplied sorted list of records to {@link #outputPath}
     * as a fixed-width FB report. Each record produces exactly one line
     * of {@link #OUTPUT_RECORD_LENGTH} bytes followed by one
     * {@link #LINE_SEPARATOR} byte.
     *
     * <p>Sequence of operations (matching DELDEF + STEP10R SORTOUT):
     * <ol>
     *   <li>{@link Files#deleteIfExists(Path)} on {@code outputPath} to
     *       satisfy DELDEF's {@code DISP=(MOD,DELETE)} idempotency.</li>
     *   <li>{@link Files#createDirectories(Path,
     *       java.nio.file.attribute.FileAttribute...)} on the parent
     *       directory (creates ancestors on demand). No-op if the parent
     *       already exists.</li>
     *   <li>{@link Files#newOutputStream(Path,
     *       java.nio.file.OpenOption...)} with
     *       {@link StandardOpenOption#CREATE_NEW} +
     *       {@link StandardOpenOption#WRITE} to honour the JCL
     *       {@code DISP=(NEW,CATLG,DELETE)}; a concurrent call against
     *       the same path would fail rather than silently overwrite.</li>
     *   <li>For each record: format the line via
     *       {@link #formatLine(TranCatBalRecord)}, assert the length
     *       invariant ({@link #OUTPUT_RECORD_LENGTH} bytes), write the
     *       line, then write the {@link #LINE_SEPARATOR}.</li>
     *   <li>Close the {@link java.io.OutputStream} via the
     *       try-with-resources statement.</li>
     * </ol>
     *
     * <p>Any {@link IOException} from the file operations is re-thrown as
     * an {@link UncheckedIOException} so the public {@link #execute()}
     * method does not need to declare a checked exception &mdash; the
     * caller is expected to map this to a non-zero return code in the
     * composition root.
     *
     * @param sorted the records to write, in the order they should appear
     *               in the output file. Must not be {@code null}.
     *               Empty list is allowed and produces a zero-byte file
     *               (matching the DFSORT behaviour for an empty SORTIN).
     * @return the number of lines written (equal to {@code sorted.size()})
     * @throws UncheckedIOException  if {@link Files#deleteIfExists(Path)},
     *                               {@link Files#createDirectories(Path,
     *                               java.nio.file.attribute.FileAttribute...)},
     *                               {@link Files#newOutputStream(Path,
     *                               java.nio.file.OpenOption...)}, or any
     *                               write fails
     * @throws IllegalStateException if a formatted line is not exactly
     *                               {@link #OUTPUT_RECORD_LENGTH} bytes
     *                               long &mdash; defensive guard against
     *                               a {@link #formatLine(TranCatBalRecord)}
     *                               bug; should never trigger in practice
     */
    private int writeReport(List<TranCatBalRecord> sorted) {
        try {
            Files.deleteIfExists(outputPath);
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (var out = Files.newOutputStream(
                    outputPath,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                for (TranCatBalRecord r : sorted) {
                    byte[] line = formatLine(r);
                    if (line.length != OUTPUT_RECORD_LENGTH) {
                        throw new IllegalStateException(
                                "Formatted line length=" + line.length
                                        + ", expected " + OUTPUT_RECORD_LENGTH
                                        + " for record " + r);
                    }
                    out.write(line);
                    out.write(LINE_SEPARATOR);
                }
            }
            return sorted.size();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to write PRTCATBL output " + outputPath, e);
        }
    }

    // -----------------------------------------------------------------------
    // Format line — DFSORT OUTREC FIELDS=(...) equivalent
    // -----------------------------------------------------------------------

    /**
     * Formats a single {@link TranCatBalRecord} into the fixed
     * {@link #OUTPUT_RECORD_LENGTH}-byte ASCII line layout specified by
     * the DFSORT {@code OUTREC FIELDS=(...)} clause in
     * {@code PRTCATBL.jcl} {@code [app/jcl/PRTCATBL.jcl:L53-L56]}.
     *
     * <p>Layout (1-based byte positions, matching the DFSORT spec):
     * <ul>
     *   <li>1-11: {@code TRANCAT-ACCT-ID} via
     *       {@code String.format("%011d", ...)} &mdash; zero-padded LEFT
     *       to 11 ASCII digit bytes ({@code "00000000001"}).</li>
     *   <li>12: single ASCII space byte (DFSORT {@code X} separator).</li>
     *   <li>13-14: {@code TRANCAT-TYPE-CD} via
     *       {@link #padRight(String, int)} &mdash; space-padded RIGHT to
     *       2 ASCII bytes; truncated from the left if the source string
     *       is longer than 2 characters (defence-in-depth; the
     *       {@code TranCatBalRecord.TranCatKey} canonical constructor
     *       rejects values longer than 2 characters).</li>
     *   <li>15: single ASCII space byte (DFSORT {@code X} separator).</li>
     *   <li>16-19: {@code TRANCAT-CD} via
     *       {@code String.format("%04d", ...)} &mdash; zero-padded LEFT
     *       to 4 ASCII digit bytes ({@code "0001"}).</li>
     *   <li>20: single ASCII space byte (DFSORT {@code X} separator).</li>
     *   <li>21-32: {@code TRAN-CAT-BAL} via
     *       {@link #formatBalance(BigDecimal)} which routes through
     *       {@link Decimals#formatEditMaskUnsigned(BigDecimal, int, int)}
     *       to produce exactly 12 ASCII characters per the
     *       {@code EDIT=(TTTTTTTTT.TT)} mask
     *       ({@code "000000194.00"}).</li>
     *   <li>33-40: 8 trailing ASCII space bytes (LRECL=40 padding; see
     *       the class Javadoc for the rationale on the
     *       {@code 9X} vs {@code 8X} reconciliation).</li>
     * </ul>
     *
     * <p>The resulting {@link String} is converted to a {@code byte[]}
     * via {@link StandardCharsets#US_ASCII}. The output bytes are
     * deliberately ASCII; any EBCDIC transcoding for production
     * deployment is performed by the {@code EbcdicTranscoder} adapter in
     * {@code carddemo-adapter-file} before bytes ever reach this driver
     * via the repository port.
     *
     * <p>All monetary formatting is routed through {@link Decimals}
     * (AAP &sect;0.6.1) and never through
     * {@link String#format(String, Object...)} with a floating-point
     * specifier such as {@code "%.2f"} (which would use {@code double}
     * internally, violating AAP &sect;0.6.7 / &sect;0.7.4). Numeric
     * integer formatting via {@code String.format("%011d", ...)} and
     * {@code "%04d"} is permitted because the source values are
     * {@code long} / {@code int} primitives, not {@link BigDecimal}.
     *
     * @param r the source record; must not be {@code null} (the caller
     *          guarantees non-null records from {@code Stream.toList()})
     * @return a {@code byte[OUTPUT_RECORD_LENGTH]} ASCII representation
     *         of the record
     * @throws IllegalStateException if the assembled line exceeds
     *                               {@link #OUTPUT_RECORD_LENGTH} bytes
     *                               &mdash; defensive guard against a
     *                               record whose field widths overflow
     *                               the EDIT mask. Should never trigger
     *                               in practice because the record
     *                               canonical constructor enforces field
     *                               widths.
     */
    private static byte[] formatLine(TranCatBalRecord r) {
        StringBuilder sb = new StringBuilder(OUTPUT_RECORD_LENGTH);

        // Positions 1-11: TRANCAT-ACCT-ID (PIC 9(11)) — zero-padded LEFT
        // via %011d on the long primitive returned by tranCatKey().trancatAcctId().
        sb.append(String.format("%011d", accountId(r)));
        // Position 12: DFSORT 'X' separator (1 ASCII space).
        sb.append(' ');

        // Positions 13-14: TRANCAT-TYPE-CD (PIC X(02)) — space-padded RIGHT.
        // padRight handles the (rare) edge cases where the record carries a
        // shorter or longer string than 2 chars.
        sb.append(padRight(typeCode(r), TYPE_CD_WIDTH));
        // Position 15: DFSORT 'X' separator.
        sb.append(' ');

        // Positions 16-19: TRANCAT-CD (PIC 9(04)) — zero-padded LEFT
        // via %04d on the int primitive returned by tranCatKey().trancatCd().
        sb.append(String.format("%04d", categoryCode(r)));
        // Position 20: DFSORT 'X' separator.
        sb.append(' ');

        // Positions 21-32: TRAN-CAT-BAL (PIC S9(09)V99) via DFSORT
        // EDIT=(TTTTTTTTT.TT) — 12 ASCII characters. Routed through
        // Decimals.formatEditMaskUnsigned to guarantee byte-for-byte
        // parity with the COBOL/DFSORT baseline.
        sb.append(formatBalance(r.tranCatBal()));

        // Positions 33-40: 8 trailing spaces to reach LRECL=40.
        // Loop-fill rather than fixed-width concatenation so the
        // OUTPUT_RECORD_LENGTH constant is the single source of truth.
        while (sb.length() < OUTPUT_RECORD_LENGTH) {
            sb.append(' ');
        }
        // Defensive guard — should never trigger because every field
        // contributes a fixed width.
        if (sb.length() > OUTPUT_RECORD_LENGTH) {
            throw new IllegalStateException(
                    "Line exceeded " + OUTPUT_RECORD_LENGTH
                            + " chars: '" + sb + "' (length=" + sb.length() + ")");
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    // -----------------------------------------------------------------------
    // Helpers — fixed-width string padding and BigDecimal formatting
    // -----------------------------------------------------------------------

    /**
     * Right-pads the supplied {@link String} to exactly {@code width}
     * characters with ASCII space ({@code 0x20}), or truncates from the
     * tail if the string is longer than {@code width}. Models the COBOL
     * {@code MOVE x TO PIC X(width)} semantic.
     *
     * <p>This helper exists because the {@code TranCatBalRecord.TranCatKey}
     * canonical constructor allows {@code TRANCAT-TYPE-CD} values shorter
     * than 2 characters and rejects values longer than 2 characters, but
     * the DFSORT output is always exactly 2 characters wide. The truncate
     * branch is therefore unreachable through the normal record
     * construction path; it is retained as defence-in-depth so a
     * mis-constructed record cannot corrupt the fixed-width report
     * layout.
     *
     * <p>A {@code null} input is treated as the empty string and
     * produces a {@code width}-long sequence of spaces. This matches
     * COBOL's behaviour for an uninitialized {@code WORKING-STORAGE} item
     * (which is implicitly all spaces or low-values depending on the
     * compiler option). In practice the {@link TranCatBalRecord.TranCatKey}
     * canonical constructor rejects {@code null} {@code trancatTypeCd}, so
     * this branch is also unreachable through the normal path.
     *
     * @param s     the source string; may be {@code null}
     * @param width the target width in characters; must be {@code >= 0}
     * @return a {@link String} of exactly {@code width} characters
     */
    private static String padRight(String s, int width) {
        if (s == null) {
            return " ".repeat(width);
        }
        if (s.length() >= width) {
            return s.substring(0, width);
        }
        return s + " ".repeat(width - s.length());
    }

    /**
     * Formats a {@link BigDecimal} {@code TRAN-CAT-BAL} value via the
     * DFSORT {@code EDIT=(TTTTTTTTT.TT)} mask equivalent &mdash; 9
     * zero-padded integer digits, a literal {@code '.'}, and 2
     * zero-padded fractional digits, with no leading sign character
     * (12-character total width).
     *
     * <p>All monetary formatting is routed through
     * {@link Decimals#formatEditMaskUnsigned(BigDecimal, int, int)}
     * per AAP &sect;0.6.1 (Decimal Arithmetic Fidelity). This guarantees:
     * <ul>
     *   <li>No {@code double} / {@code float} arithmetic (AAP &sect;0.6.7
     *       forbidden).</li>
     *   <li>Banker's-rounding rescale to scale 2 via the
     *       {@link Decimals#ROUNDED_MODE} default (effectively a no-op
     *       because {@link TranCatBalRecord} normalises the scale to 2
     *       in its canonical constructor; the rescale is defence-in-
     *       depth).</li>
     *   <li>Byte-for-byte parity with the COBOL/DFSORT baseline by
     *       construction &mdash; the formatting logic is centralised in
     *       {@link Decimals}, so any future change applies uniformly to
     *       every translated EDIT-mask call site.</li>
     * </ul>
     *
     * <p>Negative balances (not expected for {@code TCATBAL} per AAP
     * &sect;0.6.1) are emitted as their absolute value, matching
     * DFSORT's default unsigned-EDIT behaviour (the {@code T} digit
     * positions have no sign character; the {@code SIGNS} subparameter
     * is absent from the JCL). This is documented on the
     * {@link Decimals#formatEditMaskUnsigned(BigDecimal, int, int)}
     * Javadoc and reflects the JCL specification verbatim.
     *
     * @param balance the {@code TRAN-CAT-BAL} value; must not be
     *                {@code null}. The supplied value's scale is
     *                irrelevant &mdash;
     *                {@link Decimals#formatEditMaskUnsigned(BigDecimal,
     *                int, int)} normalises it via
     *                {@link BigDecimal#setScale(int,
     *                java.math.RoundingMode)} prior to formatting.
     * @return a 12-character {@link String} representation of the
     *         balance per the {@code EDIT=(TTTTTTTTT.TT)} mask
     * @throws NullPointerException     if {@code balance} is {@code null}
     * @throws IllegalArgumentException if the integer part of the
     *                                  balance exceeds 9 digits (which
     *                                  would overflow the EDIT mask).
     *                                  This corresponds to a
     *                                  {@code PIC S9(09)V99} overflow on
     *                                  the COBOL side and would also
     *                                  cause a DFSORT abend; it is
     *                                  surfaced as an
     *                                  {@link IllegalArgumentException}
     *                                  here so the composition root can
     *                                  fail with a clear diagnostic.
     */
    private static String formatBalance(BigDecimal balance) {
        Objects.requireNonNull(balance, "balance");
        return Decimals.formatEditMaskUnsigned(
                balance, TRAN_CAT_BAL_INTEGER_DIGITS, TRAN_CAT_BAL_DECIMAL_DIGITS);
    }
}
