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

import com.blitzy.carddemo.application.statement.CbStm03A;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plain-Java batch driver that translates the {@code app/jcl/CREASTMT.JCL}
 * five-step statement-generation job into a single {@link #execute()}
 * method while preserving byte-for-byte fidelity with the underlying
 * COBOL implementation.
 *
 * <h2>Source lineage ({@code app/jcl/CREASTMT.JCL})</h2>
 *
 * <p>The originating JCL job has five sequential steps:
 *
 * <h3>DELDEF01 &mdash; IDCAMS delete-then-define TRXFL</h3>
 * {@snippet lang = "jcl":
 * //DELDEF01 EXEC PGM=IDCAMS
 * //SYSIN    DD  *
 *   DELETE    AWS.M2.CARDDEMO.TRXFL.SEQ
 *   DELETE    AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS CLUSTER
 *   SET       MAXCC = 0
 *   DEFINE    CLUSTER (NAME(AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS)
 *                     KEYS(32 0) RECORDSIZE(350 350)
 *                     INDEXED CYL(1 5))
 * }
 * Deletes any previous {@code TRXFL.SEQ} sequential file and
 * {@code TRXFL.VSAM.KSDS} cluster, then defines a fresh 350-byte
 * fixed-width KSDS cluster keyed by a 32-byte composite of (card-num + tran-id)
 * starting at offset 0 of each record. The Java translation delegates
 * this lifecycle to the file adapter wired by the composition root in
 * {@code carddemo-app} (which uses {@link java.nio.file.Files#deleteIfExists(Path)}
 * + open-with-{@code CREATE_NEW} semantics on the underlying TRXFL backing
 * file before the {@link CbStm03A} use case reads it).
 *
 * <h3>STEP010 &mdash; PGM=SORT: DFSORT key inversion + record reformatting</h3>
 * {@snippet lang = "jcl":
 * //STEP010  EXEC PGM=SORT
 * //SORTIN   DD  DISP=SHR,DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS
 * //SORTOUT  DD  DSN=AWS.M2.CARDDEMO.TRXFL.SEQ,LRECL=350,RECFM=FB
 * //SYSIN    DD  *
 *   SORT FIELDS=(263,16,CH,A,1,16,CH,A)
 *   OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)
 * }
 * Reads the live {@code TRANSACT.VSAM.KSDS} (350-byte records keyed by
 * tran-id at bytes 1-16) and sorts in ascending order by composite key
 * <em>card-num then tran-id</em> &mdash; bytes 263-278 (card-num, 16 bytes)
 * as primary, bytes 1-16 (tran-id) as secondary, both using the
 * DFSORT {@code CH} (character / alphanumeric) collating sequence. The
 * {@code OUTREC FIELDS=(...)} clause then reformats each record to put
 * card-num at positions 1-16, tran-id (and original positions 17-262)
 * at positions 17-278, and the trailing portion of the original record
 * (positions 279-328) at positions 279-328. The reformatted records are
 * written to {@code TRXFL.SEQ} (350-byte fixed-width).
 *
 * <p>This sort order is critical: {@link CbStm03A} buffers transactions
 * grouped by card-num via a {@link java.util.LinkedHashMap}, and the
 * insertion order of that map IS the iteration order. Reordering the
 * input changes observable text and HTML output and is FORBIDDEN per
 * AAP &sect;0.1.3 ("virtual threads are NOT a license to reorder records,
 * change sort orders, or break sequencing").
 *
 * <h3>STEP020 &mdash; IDCAMS REPRO load into the TRXFL KSDS</h3>
 * {@snippet lang = "jcl":
 * //STEP020  EXEC PGM=IDCAMS,COND=(0,NE)
 * //INFILE   DD  DISP=SHR,DSN=AWS.M2.CARDDEMO.TRXFL.SEQ
 * //OUTFILE  DD  DISP=SHR,DSN=AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS
 * //SYSIN    DD  *
 *   REPRO INFILE(INFILE) OUTFILE(OUTFILE)
 * }
 * Conditionally (only if STEP010 returned 0) loads the sorted sequential
 * file produced by STEP010 into the empty KSDS cluster defined by
 * DELDEF01. The Java translation collapses STEP010+STEP020 into the
 * {@code TransactionRepository} port wiring at the composition root:
 * the file adapter exposes the sorted card-num-then-tran-id stream
 * directly to {@link CbStm03A} via the {@code TRNXFILE} port, and no
 * intermediate KSDS materialisation is required because the data never
 * leaves the JVM heap between read and statement-write.
 *
 * <h3>STEP030 &mdash; IEFBR14 delete previous outputs</h3>
 * {@snippet lang = "jcl":
 * //STEP030  EXEC PGM=IEFBR14,COND=(0,NE)
 * //HTMLFILE DD DISP=(MOD,DELETE,DELETE),...,DSN=AWS.M2.CARDDEMO.STATEMNT.HTML
 * //STMTFILE DD DISP=(MOD,DELETE,DELETE),...,DSN=AWS.M2.CARDDEMO.STATEMNT.PS
 * }
 * {@code IEFBR14} is the classic z/OS no-op program; the only effect of
 * this step is the {@code DISP=(MOD,DELETE,DELETE)} disposition, which
 * deletes any existing {@code STATEMNT.HTML} and {@code STATEMNT.PS}
 * outputs prior to {@code STEP040} opening them with {@code DISP=NEW}.
 * The Java translation defers this idempotency concern to
 * {@link CbStm03A} itself, which opens both output files via
 * {@link java.nio.file.StandardOpenOption} flags during
 * {@code openStatementOutputs()} and is responsible for any
 * pre-existing-file cleanup before write.
 *
 * <h3>STEP040 &mdash; PGM=CBSTM03A: statement generation</h3>
 * {@snippet lang = "jcl":
 * //STEP040  EXEC PGM=CBSTM03A,COND=(0,NE)
 * //TRNXFILE DD  DISP=SHR,DSN=AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS
 * //XREFFILE DD  DISP=SHR,DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS
 * //ACCTFILE DD  DISP=SHR,DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
 * //CUSTFILE DD  DISP=SHR,DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS
 * //STMTFILE DD DISP=(NEW,CATLG,DELETE),...,DSN=AWS.M2.CARDDEMO.STATEMNT.PS
 * //HTMLFILE DD DISP=(NEW,CATLG,DELETE),...,DSN=AWS.M2.CARDDEMO.STATEMNT.HTML
 * }
 * Runs the {@code CBSTM03A} statement-generation program with four
 * input DD cards ({@code TRNXFILE}, {@code XREFFILE}, {@code ACCTFILE},
 * {@code CUSTFILE}) and two output DD cards ({@code STMTFILE} for
 * 80-character plain-text statements and {@code HTMLFILE} for
 * 100-character HTML statements). The {@code CBSTM03A} program
 * internally {@code CALL 'CBSTM03B'} to perform the four COBOL file
 * primitives (OPEN, READ, READ-K, CLOSE) against the four input
 * datasets. This step is the heart of the job; in the Java translation,
 * {@link #execute()} delegates the entire STEP040 body to
 * {@link CbStm03A#run()}.
 *
 * <h2>Java translation</h2>
 *
 * <p>The five JCL steps collapse into a thin {@link #execute()} method
 * because the COBOL-level complexity is owned by {@link CbStm03A} and
 * its constructor-injected {@code CbStm03B} file-services collaborator:
 * <ul>
 *   <li>DELDEF01 + STEP010 + STEP020 (TRXFL define / sort / REPRO) are
 *       handled by the file adapter wired by the composition root in
 *       {@code carddemo-app}. The adapter's
 *       {@code TransactionRepository#streamSequential()} port contract
 *       guarantees the same card-num-then-tran-id sort order that
 *       DFSORT produces.</li>
 *   <li>STEP030 (IEFBR14 delete previous outputs) is handled by
 *       {@link CbStm03A} during {@code openStatementOutputs()} before
 *       opening {@code STMTFILE}/{@code HTMLFILE} with
 *       {@link java.nio.file.StandardOpenOption}-controlled
 *       create-or-truncate semantics.</li>
 *   <li>STEP040 (PGM=CBSTM03A) is delegated to
 *       {@link CbStm03A#run()}; this driver simply invokes the use case
 *       and returns its exit code.</li>
 * </ul>
 *
 * <h2>Architectural deviations (flagged in {@code MIGRATION_NOTES.md})</h2>
 *
 * <p>Two deviations from idiom-for-idiom translation are owned by
 * {@link CbStm03A} (not this driver) but are noted here for traceability:
 * <ol>
 *   <li><strong>TIOT/PSA/TCB inspection</strong> &mdash; CBSTM03A.CBL
 *       contains paragraphs that walk the z/OS Task I/O Table, Prefixed
 *       Save Area, and Task Control Block to enumerate DD names. Java
 *       has no equivalent of these mainframe control blocks; the
 *       translation provides a no-op stub that logs a {@code DEVIATION}
 *       warning. See {@link CbStm03A}'s class-level Javadoc and
 *       {@code java/MIGRATION_NOTES.md} for details.</li>
 *   <li><strong>ALTER &hellip; GO TO state machine</strong> &mdash; the
 *       COBOL {@code 0000-START} paragraph uses {@code ALTER} to mutate
 *       the target of {@code GO TO 8100-FILE-OPEN} among five
 *       paragraphs. The Java translation realizes this as a
 *       {@code DispatchState} enum + state-driven loop in
 *       {@link CbStm03A}; this preserves the COBOL sequencing exactly
 *       while adhering to structured-programming idioms. The state
 *       ordering matches the original COBOL exactly &mdash; reordering
 *       changes observable behavior and is FORBIDDEN.</li>
 * </ol>
 *
 * <h2>Outputs</h2>
 * <ul>
 *   <li>{@link #statementTextOutputPath()} &mdash; the {@code STMTFILE}
 *       80-character plain-text statement file
 *       ({@code AWS.M2.CARDDEMO.STATEMNT.PS} on z/OS).</li>
 *   <li>{@link #statementHtmlOutputPath()} &mdash; the {@code HTMLFILE}
 *       100-character HTML statement file
 *       ({@code AWS.M2.CARDDEMO.STATEMNT.HTML} on z/OS).</li>
 * </ul>
 *
 * <p>Both paths are constructor parameters supplied by the composition
 * root from {@code application.properties} keys
 * ({@code carddemo.file.stmt.text.path} and
 * {@code carddemo.file.stmt.html.path}). The driver itself does
 * NOT perform any direct file I/O; it forwards the paths to
 * {@link CbStm03A} via the constructor-injected use case reference and
 * exposes them via the accessor methods purely for composition-root
 * verification and structured-log emission.
 *
 * <h2>Conditional execution ({@code COND=(0,NE)})</h2>
 *
 * <p>Each step from STEP020 onward carries a JCL {@code COND=(0,NE)}
 * predicate meaning "execute this step only if every prior step returned
 * 0." In the Java translation the equivalent of {@code COND=(0,NE)} is
 * implicit in the sequential call chain: each step is a method call,
 * and if any throws an unchecked exception, subsequent steps are not
 * invoked (Java propagates the exception up the stack). The
 * {@link CbStm03A#run()} return code (0 = OK, 12 = error, 16 = EOF) is
 * returned verbatim by {@link #execute()} so the JCL-step main class in
 * {@code carddemo-app} can convert it to a {@link System#exit(int)} call
 * if required.
 *
 * <h2>Virtual-thread fan-out</h2>
 *
 * <p>AAP &sect;0.6.6 permits virtual-thread fan-out for per-card
 * statement generation because each card's statement is independent.
 * However, the output ordering MUST be preserved (statements must be
 * written in card-num-then-tran-id order per the STEP010 DFSORT sort).
 * This permission applies <strong>inside</strong> {@link CbStm03A} (if
 * the use-case author chooses to implement fan-out internally,
 * collecting per-card results, sorting them by card-id, then writing
 * serially). This driver does NOT introduce fan-out itself: it makes a
 * single, synchronous call to {@link CbStm03A#run()} on the calling
 * thread. The current {@link CbStm03A} implementation uses
 * {@link java.util.LinkedHashMap} insertion order rather than
 * fan-out-and-resort, which preserves the COBOL load-order invariant
 * with zero concurrency.
 *
 * <h2>Architectural rule (AAP &sect;0.3.1, &sect;0.3.6, &sect;0.6.12)</h2>
 *
 * <p>This class:
 * <ul>
 *   <li>Lives in {@code carddemo-batch} (orchestration ring).</li>
 *   <li>Depends on {@code carddemo-application} (use-case ring) via
 *       the {@link CbStm03A} reference, and transitively on
 *       {@code carddemo-domain} (innermost ring) via the
 *       {@link CobolProgram} annotation.</li>
 *   <li>Does <strong>not</strong> depend on any adapter module
 *       ({@code carddemo-adapter-file}, {@code carddemo-adapter-db})
 *       &mdash; the concrete file-services collaborator is already
 *       wired into the {@link CbStm03A} instance by the composition
 *       root.</li>
 *   <li>Does <strong>not</strong> use Spring, Spring Batch, or any
 *       framework container per AAP &sect;0.6.12 architectural
 *       override.</li>
 *   <li>Carries a {@link CobolProgram} annotation citing
 *       {@code CBSTM03A} because this driver is closely tied to one
 *       specific COBOL program (unlike most JCL drivers which span
 *       several IDCAMS / DFSORT steps).</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class is immutable after construction (all three fields are
 * {@code final} and the {@link CbStm03A} reference is itself
 * constructor-injected by the composition root). The thread-safety
 * contract of {@link #execute()} is therefore the same as the
 * {@link CbStm03A#run()} contract: per the {@link CbStm03A} class-level
 * Javadoc, the use case manages mutable working-storage internally and
 * is not designed for concurrent invocation. Use a fresh
 * {@code CreateStatementsBatch} instance per concurrent batch invocation.
 *
 * <h2>Forbidden in this driver (AAP &sect;0.6.7, &sect;0.7.4)</h2>
 * <ul>
 *   <li>Spring / Spring Batch / framework container &mdash;
 *       AAP &sect;0.6.12 explicit override.</li>
 *   <li>{@code ThreadLocal} &mdash; {@code ScopedValue} replaces it;
 *       this driver does not propagate context, so neither is needed
 *       here.</li>
 *   <li>{@code double} / {@code float} for monetary values &mdash; this
 *       driver performs no arithmetic; all monetary aggregation
 *       happens inside {@link CbStm03A} via the {@code Decimals}
 *       utility.</li>
 *   <li>{@code java.util.Date} / {@code Calendar} &mdash; only
 *       {@link Instant} / {@link Duration} from {@code java.time}.</li>
 *   <li>{@code java.io.File} &mdash; only {@link Path} from
 *       {@code java.nio.file}.</li>
 *   <li>{@link System#out} / {@link System#err} &mdash; only SLF4J.</li>
 *   <li>Reflection, dynamic proxies, Lombok.</li>
 *   <li>{@code Executors.newVirtualThreadPerTaskExecutor()} or any
 *       parallel fan-out &mdash; output order is observable per AAP
 *       &sect;0.1.3 and any fan-out is internal to {@link CbStm03A}.</li>
 *   <li>Preview features (no {@code --enable-preview}).</li>
 * </ul>
 *
 * @see CbStm03A
 * @see CobolProgram
 * @see <a href="https://cf-workers-proxy-9e9.pages.dev/aws-mainframe-modernization/aws-card-demo">Original
 *      CardDemo COBOL source ({@code app/jcl/CREASTMT.JCL},
 *      {@code app/cbl/CBSTM03A.CBL}, {@code app/cbl/CBSTM03B.CBL})</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBSTM03A",
        sourcePath = "app/cbl/CBSTM03A.CBL",
        translationDate = "2025-01-15",
        notes = "Plain-Java batch driver for the CREASTMT.JCL five-step "
              + "statement-generation job. Delegates the statement-generation "
              + "logic (STEP040 PGM=CBSTM03A) to the CbStm03A use case; the "
              + "preceding TRXFL define / sort / REPRO steps (DELDEF01 / "
              + "STEP010 / STEP020) are handled by the file adapter wired by "
              + "the composition root in carddemo-app. See java/MIGRATION_NOTES.md "
              + "for the TIOT/PSA/TCB and ALTER/GO TO deviations (owned by "
              + "CbStm03A, not by this driver). Output ordering by "
              + "card-num-then-tran-id MUST be preserved (DFSORT STEP010 "
              + "SORT FIELDS=(263,16,CH,A,1,16,CH,A))."
)
public final class CreateStatementsBatch {

    // -----------------------------------------------------------------------
    // SLF4J logger — observability per AAP §0.5.1 and AAP §0.6.6
    // -----------------------------------------------------------------------

    /**
     * Class-level SLF4J logger. Emits structured INFO-level start / end
     * markers including the resolved output paths, the {@link CbStm03A}
     * exit code, and the elapsed {@link Duration}. The concrete logging
     * backend (Logback) is supplied by the composition root
     * ({@code carddemo-app}); this module depends only on the SLF4J
     * facade per the {@code carddemo-batch} module's POM.
     *
     * <p>Why not {@link System#out}? Per AAP &sect;0.6.7 and the
     * {@code carddemo-batch/pom.xml} forbidden list, direct console
     * writes bypass the structured-logging pipeline (JSON layout, log
     * aggregation, severity-based filtering) that production deployments
     * rely on. SLF4J + Logback is the only sanctioned observability
     * surface.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CreateStatementsBatch.class);

    // -----------------------------------------------------------------------
    // Private final fields — constructor-injected collaborators and paths
    // -----------------------------------------------------------------------

    /**
     * The constructor-injected {@link CbStm03A} use case translating
     * {@code app/cbl/CBSTM03A.CBL}. Injected by the composition root in
     * {@code carddemo-app} per hexagonal-architecture dependency
     * inversion (AAP &sect;0.6.12 &mdash; plain factories and
     * constructor injection, no Spring container).
     *
     * <p>The use case holds its {@code CbStm03B} file-services
     * collaborator internally (constructor-injected at the
     * composition-root level), so this driver does NOT need to be
     * aware of the file adapter or the four input DD-equivalent paths
     * ({@code TRNXFILE}, {@code XREFFILE}, {@code ACCTFILE},
     * {@code CUSTFILE}). The driver-level abstraction is at the
     * {@link CbStm03A#run()} use-case entry point.
     *
     * <p>Final because the wiring is immutable post-construction;
     * mutating this reference at runtime would break the
     * constructor-injection invariant and is FORBIDDEN.
     */
    private final CbStm03A cbStm03A;

    /**
     * Filesystem location of the {@code STMTFILE} 80-character plain-text
     * statement output ({@code AWS.M2.CARDDEMO.STATEMNT.PS} on z/OS).
     * Set by the composition root from {@code application.properties}
     * (key {@code carddemo.file.stmt.text.path}); supplied via the
     * constructor so this driver is fully testable without environment
     * dependencies.
     *
     * <p>This path is NOT used by the driver for direct I/O. The
     * {@link CbStm03A} use case owns the actual file open / write /
     * close lifecycle. This field is retained for:
     * <ul>
     *   <li>Structured log emission in {@link #execute()} (the start /
     *       end log lines include both configured paths for operational
     *       traceability).</li>
     *   <li>Composition-root verification via the
     *       {@link #statementTextOutputPath()} accessor (so the
     *       composition root can assert the wiring is consistent with
     *       the {@link CbStm03A} instance's configured output path).</li>
     * </ul>
     *
     * <p>Use {@link Path} (java.nio.file) rather than
     * {@link java.io.File} per AAP &sect;0.6.5 (file I/O exactness).
     */
    private final Path statementTextOutputPath;

    /**
     * Filesystem location of the {@code HTMLFILE} 100-character HTML
     * statement output ({@code AWS.M2.CARDDEMO.STATEMNT.HTML} on z/OS).
     * Set by the composition root from {@code application.properties}
     * (key {@code carddemo.file.stmt.html.path}); supplied via the
     * constructor so this driver is fully testable without environment
     * dependencies.
     *
     * <p>As with {@link #statementTextOutputPath}, this path is NOT used
     * by the driver for direct I/O. It is retained for structured log
     * emission and composition-root verification via the
     * {@link #statementHtmlOutputPath()} accessor.
     */
    private final Path statementHtmlOutputPath;

    // -----------------------------------------------------------------------
    // Constructor — constructor injection per AAP §0.6.12 (no framework)
    // -----------------------------------------------------------------------

    /**
     * Constructs a new {@code CreateStatementsBatch} bound to the
     * supplied {@link CbStm03A} use case and the two output file paths.
     * All three arguments are required; none are allowed to be
     * {@code null}.
     *
     * <p>This is plain constructor injection per AAP &sect;0.1.1 and
     * &sect;0.6.12 ("hexagonal Java 25 with plain factories and
     * constructor injection (no Spring container)"). No framework
     * container is involved; the composition root in
     * {@code carddemo-app} is responsible for:
     * <ol>
     *   <li>Building the {@link com.blitzy.carddemo.application.statement.CbStm03B
     *       CbStm03B} file-services collaborator with its four input
     *       paths ({@code TRNXFILE}, {@code XREFFILE}, {@code ACCTFILE},
     *       {@code CUSTFILE}).</li>
     *   <li>Building the {@link CbStm03A} use case with the
     *       {@code CbStm03B} reference plus the two output paths
     *       ({@code statementTextOutputPath},
     *       {@code statementHtmlOutputPath}).</li>
     *   <li>Building this {@code CreateStatementsBatch} driver with the
     *       same {@link CbStm03A} reference and the same two output
     *       paths (so the driver's log lines accurately reflect the
     *       paths the use case will write to).</li>
     * </ol>
     *
     * <p>Why pass the output paths into both the use case AND the batch
     * driver? Because the batch driver owns the structured log line
     * "CREASTMT start: textOut=..., htmlOut=..." and benefits from
     * having the paths locally available; passing them in as
     * constructor parameters keeps the driver fully testable in
     * isolation without reaching into the {@link CbStm03A} instance via
     * a getter. The composition root is responsible for ensuring the
     * paths supplied here match those supplied to the {@link CbStm03A}
     * constructor &mdash; a mismatch would not cause incorrect output
     * (the use case writes wherever its own paths point), but would
     * make the driver's log lines misleading. The
     * {@link #statementTextOutputPath()} and
     * {@link #statementHtmlOutputPath()} accessors expose the driver's
     * view so the composition root can assert consistency.
     *
     * <p>Fail-fast null-validation: {@link Objects#requireNonNull(Object,
     * String)} is used for each parameter so a null arrives at the
     * composition-root level rather than at run-time deep inside the
     * use case. The descriptive parameter-name string is preserved in
     * the {@link NullPointerException} message.
     *
     * @param cbStm03A                the {@link CbStm03A} use case
     *                                translating {@code CBSTM03A.CBL};
     *                                must be non-{@code null}. The use
     *                                case must already have its
     *                                {@code CbStm03B} file-services
     *                                collaborator wired internally.
     * @param statementTextOutputPath path to the {@code STMTFILE}
     *                                80-character plain-text statement
     *                                output ({@code STATEMNT.PS}); must
     *                                be non-{@code null}. The path
     *                                value is forwarded to
     *                                {@link CbStm03A} via the use
     *                                case's own constructor at
     *                                composition time; this driver
     *                                stores it only for log emission
     *                                and verification accessor.
     * @param statementHtmlOutputPath path to the {@code HTMLFILE}
     *                                100-character HTML statement
     *                                output ({@code STATEMNT.HTML});
     *                                must be non-{@code null}. Same
     *                                semantics as
     *                                {@code statementTextOutputPath}.
     * @throws NullPointerException if any argument is {@code null}
     */
    public CreateStatementsBatch(
            CbStm03A cbStm03A,
            Path statementTextOutputPath,
            Path statementHtmlOutputPath) {
        this.cbStm03A = Objects.requireNonNull(cbStm03A, "cbStm03A");
        this.statementTextOutputPath = Objects.requireNonNull(
                statementTextOutputPath, "statementTextOutputPath");
        this.statementHtmlOutputPath = Objects.requireNonNull(
                statementHtmlOutputPath, "statementHtmlOutputPath");
    }

    // -----------------------------------------------------------------------
    // Public API — execute() runs the CREASTMT job body
    // -----------------------------------------------------------------------

    /**
     * Executes the CREASTMT job equivalent. Delegates the statement
     * generation logic (STEP040 of the JCL, the {@code PGM=CBSTM03A}
     * step) to {@link CbStm03A#run()}; the preceding TRXFL sort / REPRO
     * steps (DELDEF01 / STEP010 / STEP020) and the IEFBR14 cleanup step
     * (STEP030) are handled at composition time by the file adapter
     * wiring (the {@code TransactionRepository} port surfaces the
     * pre-sorted card-num-then-tran-id stream to {@link CbStm03A}, and
     * the use case itself takes responsibility for any pre-existing
     * output file cleanup before opening {@code STMTFILE}/{@code HTMLFILE}).
     *
     * <h2>Return-code semantics</h2>
     *
     * <p>The returned {@code int} is the verbatim result of
     * {@link CbStm03A#run()}. Per the {@link CbStm03A} class-level
     * Javadoc:
     * <ul>
     *   <li>{@code 0}  &mdash; normal completion ({@code APPL_AOK})</li>
     *   <li>{@code 12} &mdash; error / abend ({@code APPL_ERROR};
     *       any uncaught exception in the use case is converted to
     *       this code; the use case logs the stack trace and does NOT
     *       call {@link System#exit(int)})</li>
     *   <li>{@code 16} &mdash; end-of-file reached cleanly
     *       ({@code APPL_EOF}; reserved for callers that distinguish
     *       "clean EOF" from "normal completion")</li>
     * </ul>
     *
     * <p>The JCL-step main class in {@code carddemo-app} is responsible
     * for converting this return code into a {@link System#exit(int)}
     * call if the operating system / orchestrator requires a process
     * exit code. This batch driver intentionally does NOT call
     * {@code System.exit} because doing so would prevent in-process
     * testability and prevent batch composition (e.g., a future driver
     * that chains {@code CreateStatementsBatch} with downstream cleanup
     * steps).
     *
     * <h2>Conditional execution ({@code COND=(0,NE)})</h2>
     *
     * <p>The JCL {@code COND=(0,NE)} predicate on STEP040 means
     * "execute only if every prior step returned 0." This driver does
     * not re-implement that predicate because the preceding steps
     * (TRXFL define / sort / REPRO / cleanup) are owned by the file
     * adapter and the composition root. If the adapter wiring fails,
     * the composition root will catch the exception before this
     * {@code execute()} is invoked.
     *
     * <h2>Timing and logging</h2>
     *
     * <p>The method captures wall-clock elapsed time around the
     * {@link CbStm03A#run()} call via {@link Instant#now()} bracketing
     * and {@link Duration#between(java.time.temporal.Temporal,
     * java.time.temporal.Temporal)}. Two INFO-level log lines are
     * emitted:
     * <ul>
     *   <li>{@code "CREASTMT start: textOut={}, htmlOut={}"} &mdash;
     *       includes both resolved output paths so an operator can
     *       correlate the run with the files on disk.</li>
     *   <li>{@code "CREASTMT end: exitCode={}, elapsed={}"} &mdash;
     *       includes the {@link CbStm03A} exit code and the elapsed
     *       duration formatted by the
     *       {@link Duration#toString()} ISO-8601 representation
     *       (e.g., {@code PT2.345S}).</li>
     * </ul>
     *
     * <p>Exception handling: if {@link CbStm03A#run()} throws an
     * unchecked exception (e.g., the use case decides a particular
     * I/O failure is unrecoverable), the exception propagates out of
     * {@code execute()} verbatim. The "end" log line is NOT emitted on
     * the exception path because the exit code is undefined. The
     * composition root in {@code carddemo-app} is responsible for the
     * top-level catch.
     *
     * @return the {@link CbStm03A#run()} return code (0 = OK,
     *         12 = error, 16 = EOF)
     */
    public int execute() {
        LOG.info("CREASTMT start: textOut={}, htmlOut={}",
                statementTextOutputPath, statementHtmlOutputPath);
        Instant start = Instant.now();
        int exitCode = cbStm03A.run();
        Duration elapsed = Duration.between(start, Instant.now());
        LOG.info("CREASTMT end: exitCode={}, elapsed={}", exitCode, elapsed);
        return exitCode;
    }

    // -----------------------------------------------------------------------
    // Public accessors — output-path verification for the composition root
    // -----------------------------------------------------------------------

    /**
     * Returns the configured text statement output path ({@code STMTFILE}
     * equivalent &mdash; {@code AWS.M2.CARDDEMO.STATEMNT.PS} on z/OS).
     *
     * <p>This accessor is intended for the composition root in
     * {@code carddemo-app} to assert wiring consistency between this
     * driver and the {@link CbStm03A} instance it composes. The actual
     * file write is performed by {@link CbStm03A} using its own
     * configured path (passed to its constructor at composition time);
     * this driver's path is used only for log emission.
     *
     * @return the configured text statement output path; never
     *         {@code null} (the constructor rejects {@code null}
     *         arguments via {@link Objects#requireNonNull(Object,
     *         String)})
     */
    public Path statementTextOutputPath() {
        return statementTextOutputPath;
    }

    /**
     * Returns the configured HTML statement output path ({@code HTMLFILE}
     * equivalent &mdash; {@code AWS.M2.CARDDEMO.STATEMNT.HTML} on z/OS).
     *
     * <p>This accessor is intended for the composition root in
     * {@code carddemo-app} to assert wiring consistency between this
     * driver and the {@link CbStm03A} instance it composes. The actual
     * file write is performed by {@link CbStm03A} using its own
     * configured path (passed to its constructor at composition time);
     * this driver's path is used only for log emission.
     *
     * @return the configured HTML statement output path; never
     *         {@code null} (the constructor rejects {@code null}
     *         arguments via {@link Objects#requireNonNull(Object,
     *         String)})
     */
    public Path statementHtmlOutputPath() {
        return statementHtmlOutputPath;
    }
}
