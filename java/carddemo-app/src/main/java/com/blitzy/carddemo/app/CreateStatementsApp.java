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
package com.blitzy.carddemo.app;

// JEP 511 (finalized in Java 25): a single declaration imports every package
// exported by the java.base module — used here for java.nio.file.{Path,Files,
// StandardOpenOption,StandardCopyOption}, java.io.{IOException,OutputStream},
// java.util.{ArrayList,List,Locale}, and java.lang.ScopedValue (which moved
// from java.util.concurrent to java.lang when JEP 506 was finalized in
// Java 25; see java.base/java/lang/ScopedValue.java).
import module java.base;

import com.blitzy.carddemo.application.statement.CbStm03A;
import com.blitzy.carddemo.application.statement.CbStm03B;
import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/CREASTMT.JCL}
 * &mdash; the statement-generation job. Produces customer statements in
 * both plain-text ({@code STATEMNT.PS}) and HTML ({@code STATEMNT.HTML})
 * formats by invoking the COBOL {@code CBSTM03A} statement engine (plus
 * its {@code CBSTM03B} file-services subroutine).
 *
 * <h2>Source artefact</h2>
 * <p>The original JCL ({@code app/jcl/CREASTMT.JCL}) chains five steps;
 * the Java translation preserves the sequencing exactly. Per AAP
 * &sect;0.6.6 these steps are <strong>not</strong> parallelised &mdash;
 * each subsequent step depends on the output of the previous step
 * (the in-memory sort is followed by a REPRO into the keyed file
 * which is read by CBSTM03A).
 *
 * <h3>DELDEF01 &mdash; IDCAMS DELETE+DEFINE TRXFL.VSAM.KSDS</h3>
 * <p>Deletes any prior {@code AWS.M2.CARDDEMO.TRXFL.SEQ} and
 * {@code AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS}, then defines a new KSDS
 * cluster with:
 * <ul>
 *   <li>{@link #TRXFL_KEY_LENGTH KEYS(32 0)} &mdash; 32-byte primary key
 *       starting at offset 0 (= 16-byte card-num + 16-byte tran-id)</li>
 *   <li>{@link #TRXFL_RECORD_LENGTH RECORDSIZE(350 350)} &mdash; fixed
 *       350-byte records</li>
 *   <li>{@link #TRXFL_CISIZE CISZ(4096)} &mdash; 4096-byte control
 *       intervals</li>
 * </ul>
 * In the file-based runtime (AAP &sect;0.6.12) the KSDS spec is recorded
 * as a {@code .schema} sidecar text file next to the data file; this
 * preserves the dataset metadata for the {@code CBSTM03B} file-services
 * subroutine to consume.
 *
 * <h3>STEP010 &mdash; PGM=SORT</h3>
 * <p>Sorts the existing TRANSACT KSDS by a two-key compound:
 * <pre>
 *   SORT FIELDS=(263,16,CH,A,1,16,CH,A)
 * </pre>
 * <ul>
 *   <li>Primary key: 16 bytes at positions 263-278 = {@code TRAN-CARD-NUM}
 *       (char ascending)</li>
 *   <li>Secondary key: 16 bytes at positions 1-16 = {@code TRAN-ID}
 *       (char ascending)</li>
 * </ul>
 * then applies the OUTREC rearrangement:
 * <pre>
 *   OUTREC FIELDS=(1:263,16, 17:1,262, 279:279,50)
 * </pre>
 * <ul>
 *   <li>Output bytes 1-16 &larr; input bytes 263-278 (the 16-byte card
 *       number, hoisted to the leading positions)</li>
 *   <li>Output bytes 17-278 &larr; input bytes 1-262 (the first 262 bytes
 *       of the original record, displaced right by 16)</li>
 *   <li>Output bytes 279-328 &larr; input bytes 279-328 (the trailing
 *       50 bytes, unchanged)</li>
 * </ul>
 * The output is a sequential file with the 16-byte card number as the
 * leading key, suitable for loading into the {@code TRXFL.VSAM.KSDS}
 * defined in DELDEF01.
 *
 * <h3>STEP020 &mdash; PGM=IDCAMS REPRO (COND=(0,NE))</h3>
 * <p>{@code REPRO INFILE(TRXFL.SEQ) OUTFILE(TRXFL.VSAM.KSDS)} copies the
 * sorted sequential file into the keyed file produced by DELDEF01. The
 * {@code COND=(0,NE)} clause bypasses this step if any prior step had a
 * non-zero return code; the Java translation short-circuits by returning
 * early on any failure in DELDEF01 or STEP010.
 *
 * <h3>STEP030 &mdash; PGM=IEFBR14 (COND=(0,NE))</h3>
 * <p>{@code IEFBR14} is a z/OS no-op program; its purpose here is to
 * trigger the {@code DISP=(MOD,DELETE,DELETE)} clauses on the
 * {@code HTMLFILE} and {@code STMTFILE} DDs, which delete any prior
 * {@code STATEMNT.HTML} and {@code STATEMNT.PS} datasets. The Java
 * translation deletes the files directly (idempotent &mdash; no error if
 * the file does not exist).
 *
 * <h3>STEP040 &mdash; PGM=CBSTM03A (COND=(0,NE))</h3>
 * <p>Invokes the {@link CbStm03A} statement-generation engine with these
 * DDs:
 * <ul>
 *   <li>{@code TRNXFILE} &larr; the {@code TRXFL.VSAM.KSDS} produced by
 *       STEP020 (read sequentially by {@code CBSTM03B})</li>
 *   <li>{@code XREFFILE} &larr; {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}</li>
 *   <li>{@code ACCTFILE} &larr; {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}</li>
 *   <li>{@code CUSTFILE} &larr; {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}</li>
 *   <li>{@code STMTFILE} &rarr; {@code AWS.M2.CARDDEMO.STATEMNT.PS}
 *       (LRECL=80, RECFM=FB) &mdash; <strong>DD MALFORMED, see below</strong></li>
 *   <li>{@code HTMLFILE} &rarr; {@code AWS.M2.CARDDEMO.STATEMNT.HTML}
 *       (LRECL=100, RECFM=FB)</li>
 * </ul>
 *
 * <h2>FAITHFUL TRANSLATION NOTE &mdash; malformed STMTFILE DD</h2>
 * <p>Per AAP &sect;0.7.1 (&quot;If a COBOL paragraph contains dead code
 * or obvious bugs, translate it faithfully and flag it in a
 * MIGRATION_NOTES.md; do not 'fix' it in this refactor&quot;):
 *
 * <p>The {@code STMTFILE} DD at line 90 of {@code app/jcl/CREASTMT.JCL}
 * is <strong>malformed</strong> with garbled trailing tokens
 * {@code "00,RECFM=FB), ATA.VSAM.KSDS"} after the {@code SPACE=} keyword:
 * <pre>
 *   //STMTFILE DD DISP=(NEW,CATLG,DELETE),
 *   //         UNIT=SYSDA,
 *   //         DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB),
 *   //         SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS
 *   //         DSN=AWS.M2.CARDDEMO.STATEMNT.PS
 * </pre>
 * The garbled fragment appears to be a copy/paste artefact or corrupted
 * edit (mainframe JCL parsers traditionally tolerate continuation-line
 * trailing junk). This Java translation:
 * <ol>
 *   <li>Translates only the <em>valid intent</em> of the DD: write
 *       statement records to {@code STATEMNT.PS} using LRECL=80
 *       RECFM=FB &mdash; the well-formed DCB attributes extracted from
 *       the preceding line and the canonical DSN= from the following
 *       line.</li>
 *   <li>Logs a {@code WARN}-level message at job startup citing the
 *       malformed line so that the bug is visible in every job log.</li>
 *   <li>Documents the preservation in {@code java/MIGRATION_NOTES.md}
 *       (separate task by another agent &mdash; <em>this</em> class
 *       does NOT modify or &quot;fix&quot; the COBOL/JCL source).</li>
 * </ol>
 * Silently &quot;fixing&quot; the malformed line would be a behaviour
 * change outside the scope of this migration and is FORBIDDEN per AAP
 * &sect;0.7.1.
 *
 * <h2>Runtime configuration (AAP &sect;0.7.2 &mdash; 12-factor)</h2>
 * <p>Every path is resolved through {@link #getProp(String, String)};
 * environment variables take precedence over system properties, and a
 * blank value at either layer falls back to the next layer. Keys:
 * <table border="1">
 *   <caption>Configuration keys for {@code CreateStatementsApp}</caption>
 *   <tr><th>System-property key</th>
 *       <th>Env-var key</th>
 *       <th>Default</th>
 *       <th>Purpose</th></tr>
 *   <tr><td>{@code carddemo.file.transact.path}</td>
 *       <td>{@code CARDDEMO_FILE_TRANSACT_PATH}</td>
 *       <td>{@code ./data/transact.dat}</td>
 *       <td>STEP010 SORT input (the un-sorted TRANSACT KSDS)</td></tr>
 *   <tr><td>{@code carddemo.file.cardxref.path}</td>
 *       <td>{@code CARDDEMO_FILE_CARDXREF_PATH}</td>
 *       <td>{@code ./data/cardxref.dat}</td>
 *       <td>STEP040 XREFFILE DD input</td></tr>
 *   <tr><td>{@code carddemo.file.acctdata.path}</td>
 *       <td>{@code CARDDEMO_FILE_ACCTDATA_PATH}</td>
 *       <td>{@code ./data/acctdata.dat}</td>
 *       <td>STEP040 ACCTFILE DD input</td></tr>
 *   <tr><td>{@code carddemo.file.custdata.path}</td>
 *       <td>{@code CARDDEMO_FILE_CUSTDATA_PATH}</td>
 *       <td>{@code ./data/custdata.dat}</td>
 *       <td>STEP040 CUSTFILE DD input</td></tr>
 *   <tr><td>{@code carddemo.file.stmt.text.path}</td>
 *       <td>{@code CARDDEMO_FILE_STMT_TEXT_PATH}</td>
 *       <td>{@code ./output/statements.txt}</td>
 *       <td>STEP040 STMTFILE DD output (line 90 malformed; valid intent
 *           only)</td></tr>
 *   <tr><td>{@code carddemo.file.stmt.html.path}</td>
 *       <td>{@code CARDDEMO_FILE_STMT_HTML_PATH}</td>
 *       <td>{@code ./output/statements.html}</td>
 *       <td>STEP040 HTMLFILE DD output</td></tr>
 *   <tr><td>{@code carddemo.work.path}</td>
 *       <td>{@code CARDDEMO_WORK_PATH}</td>
 *       <td>{@code ./work}</td>
 *       <td>Scratch directory for the intermediate TRXFL.SEQ and
 *           TRXFL.VSAM.KSDS files produced by DELDEF01/STEP010/STEP020</td></tr>
 * </table>
 *
 * <h2>Process-exit semantics</h2>
 * <ul>
 *   <li>{@link #RC_OK} ({@code 0}) on clean statement generation</li>
 *   <li>{@link #RC_NO_INPUT} ({@code 12}) when {@code TRANSACT} is missing
 *       (matches the COBOL {@code 9999-ABEND-PROGRAM} behaviour for
 *       missing input files)</li>
 *   <li>{@link #RC_ERROR} ({@code 16}) on any abend or uncaught failure
 *       (clamps any out-of-range return code to this value)</li>
 *   <li>Any value 0..16 returned by {@link CbStm03A#run() CbStm03A.run()}
 *       passes through unchanged (CbStm03A returns {@code APPL_AOK=0},
 *       {@code APPL_ERROR=12}, or {@code APPL_EOF=16})</li>
 * </ul>
 *
 * <h2>Runtime invocation</h2>
 * <p>Packaged as {@code carddemo-create-statements.jar} via
 * {@code maven-shade-plugin} (see {@code carddemo-app/pom.xml}
 * &mdash; shade execution 04 of 28). Invoke as:
 * <pre>
 * java -XX:+UseCompactObjectHeaders \
 *      -XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational \
 *      -jar carddemo-create-statements.jar
 * </pre>
 *
 * <h2>Forbidden idioms (AAP &sect;0.6.7, &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring / Spring Boot / Spring Batch &mdash; constructor
 *       injection at the composition root only.</li>
 *   <li>No {@link java.io.File} &mdash; {@link java.nio.file.Path} +
 *       {@link java.nio.file.Files} only (AAP &sect;0.6.5). The
 *       {@link java.io.OutputStream} abstract type returned by
 *       {@link Files#newOutputStream Files.newOutputStream} is allowed.</li>
 *   <li>No {@link ThreadLocal} &mdash; {@link ScopedValue} (JEP 506
 *       Final) only.</li>
 *   <li>No {@code default} branch on the exit-code switch &mdash;
 *       exhaustiveness is enforced by guarded type patterns.</li>
 *   <li>No virtual-thread fan-out &mdash; the sort is single-threaded for
 *       deterministic byte-for-byte parity with DFSORT, and STEP040
 *       statement output ordering is sequential and observable.</li>
 *   <li>No preview features (JEP 502 stable values, 505 structured
 *       concurrency, 507 primitive patterns); no {@code --enable-preview}
 *       flag.</li>
 * </ul>
 *
 * @see CbStm03A
 * @see CbStm03B
 * @see BatchRunContext
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBSTM03A",
        sourcePath = "app/cbl/CBSTM03A.CBL",
        translationDate = "2025-10-24",
        notes = "Composition root for the CREASTMT (app/jcl/CREASTMT.JCL) "
                + "statement-generation job. Translates DELDEF01 (IDCAMS DELETE+DEFINE "
                + "TRXFL.VSAM.KSDS), STEP010 (SORT TRANSACT by card-num+tran-id with "
                + "OUTREC rearrangement), STEP020 (IDCAMS REPRO TRXFL.SEQ -> "
                + "TRXFL.VSAM.KSDS), STEP030 (IEFBR14 delete prior STATEMNT.HTML/PS), "
                + "and STEP040 (invoke CBSTM03A with CBSTM03B file-services). "
                + "FAITHFUL TRANSLATION (AAP §0.7.1): the STMTFILE DD at line 90 of "
                + "CREASTMT.JCL is malformed with garbled trailing tokens "
                + "'00,RECFM=FB), ATA.VSAM.KSDS' after SPACE= — preserved as-is with "
                + "a WARN log at startup; see Javadoc and MIGRATION_NOTES.md. "
                + "Legacy z/OS TIOT/TCB/PSA inspection and ALTER/GO TO control flow "
                + "in CBSTM03A is also translated faithfully — see "
                + "MIGRATION_NOTES.md for the DEVIATION flag."
)
public final class CreateStatementsApp {

    // -----------------------------------------------------------------------
    // Logging + scoped context
    // -----------------------------------------------------------------------

    /**
     * SLF4J logger for this main class. The runtime backend (logback-classic)
     * is bound at the composition-root scope via {@code carddemo-app}'s POM
     * dependencies; no logger name override is needed.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CreateStatementsApp.class);

    /**
     * Thread-scoped binding for this run's {@link BatchRunContext}, bound
     * by {@link #main(String[])} via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(...)} for the duration
     * of {@link #execute()}. Any callee on the same or a child virtual
     * thread can read it via {@code BATCH_CTX.get()}.
     *
     * <p>This field is the sole {@code ScopedValue} for this main class
     * &mdash; it replaces {@link ThreadLocal} entirely per AAP
     * &sect;0.6.6 and &sect;0.7.4.
     *
     * <p>Note: in finalized Java 25 {@link ScopedValue} lives in
     * {@code java.lang} (it moved from {@code java.util.concurrent} when
     * JEP 506 was finalized); no explicit import is needed because
     * {@code java.lang.*} is auto-imported and
     * {@code import module java.base} also makes it available.
     *
     * <p>The {@code carddemo-batch} module exposes the canonical
     * {@link BatchRunContext#BATCH_CTX BATCH_CTX} for cross-module reuse;
     * this main's own {@code BATCH_CTX} is the per-app instance bound at
     * job entry, kept separate so each main has its own self-contained
     * lifecycle and the schema's {@code members_exposed} contract is
     * honoured.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // -----------------------------------------------------------------------
    // DELDEF01 — TRXFL.VSAM.KSDS cluster specification (preserved verbatim
    // from app/jcl/CREASTMT.JCL lines 29-39)
    // -----------------------------------------------------------------------

    /**
     * KSDS primary-key length in bytes. From {@code KEYS(32 0)} at
     * CREASTMT.JCL line 30: 32-byte key = 16-byte card-num + 16-byte
     * tran-id (the compound key produced by STEP010's OUTREC
     * rearrangement).
     */
    private static final int TRXFL_KEY_LENGTH = 32;

    /**
     * KSDS primary-key offset within the record. From {@code KEYS(32 0)}
     * at CREASTMT.JCL line 30: key starts at byte 0.
     */
    private static final int TRXFL_KEY_OFFSET = 0;

    /**
     * KSDS fixed record length in bytes. From {@code RECORDSIZE(350 350)}
     * at CREASTMT.JCL line 32: 350-byte fixed records matching
     * {@code app/cpy/CVTRA05Y.cpy} {@code TRAN-RECORD}.
     */
    private static final int TRXFL_RECORD_LENGTH = 350;

    /**
     * KSDS control-interval size in bytes. From {@code CISZ(4096)} at
     * CREASTMT.JCL line 38: 4096-byte control intervals (a standard z/OS
     * VSAM default tuned for moderate-fanout indexes).
     */
    private static final int TRXFL_CISIZE = 4096;

    /**
     * KSDS cluster name preserved verbatim from CREASTMT.JCL line 29
     * ({@code DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS) ...)}).
     * Recorded in the {@code .schema} sidecar so downstream tooling can
     * link the on-disk file back to its mainframe DSN.
     */
    private static final String TRXFL_CLUSTER_NAME = "AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS";

    // -----------------------------------------------------------------------
    // STEP010 — SORT specification (preserved verbatim from CREASTMT.JCL
    // lines 53-54)
    // -----------------------------------------------------------------------

    /**
     * Primary sort-key offset in bytes (0-indexed). From
     * {@code SORT FIELDS=(263,16,CH,A,...)} at CREASTMT.JCL line 53: 1-indexed
     * position 263 maps to 0-indexed offset {@code 262}. Identifies the
     * 16-byte {@code TRAN-CARD-NUM} field at the front of the sort key.
     */
    private static final int SORT_PRIMARY_OFFSET = 262;

    /**
     * Primary sort-key length in bytes. From
     * {@code SORT FIELDS=(263,16,CH,A,...)} at CREASTMT.JCL line 53:
     * 16-byte field width.
     */
    private static final int SORT_PRIMARY_LENGTH = 16;

    /**
     * Secondary sort-key offset in bytes (0-indexed). From
     * {@code SORT FIELDS=(...,1,16,CH,A)} at CREASTMT.JCL line 53:
     * 1-indexed position 1 maps to 0-indexed offset {@code 0}. Identifies
     * the 16-byte {@code TRAN-ID} field used to break ties on equal card
     * numbers.
     */
    private static final int SORT_SECONDARY_OFFSET = 0;

    /**
     * Secondary sort-key length in bytes. From
     * {@code SORT FIELDS=(...,1,16,CH,A)} at CREASTMT.JCL line 53:
     * 16-byte field width.
     */
    private static final int SORT_SECONDARY_LENGTH = 16;

    /**
     * Fixed input record length in bytes. From the SORTIN DCB
     * specification (LRECL=350 on TRANSACT KSDS) and matching
     * {@code app/cpy/CVTRA05Y.cpy} {@code TRAN-RECORD}.
     */
    private static final int INPUT_RECORD_LENGTH = 350;

    /**
     * Fixed output record length in bytes after OUTREC rearrangement.
     * From {@code OUTREC FIELDS=(1:263,16, 17:1,262, 279:279,50)} at
     * CREASTMT.JCL line 54: total output width = 16 + 262 + 50 = 328
     * bytes. (Note: although the OUTREC truncates input bytes 263-278
     * to the front, the trailing 50-byte segment at positions 279-328
     * is preserved unchanged; this is the layout consumed by the
     * subsequent TRXFL.VSAM.KSDS REPRO and by CBSTM03B's
     * {@code FD-TRNXFILE} reader.)
     */
    private static final int OUTREC_RECORD_LENGTH = 328;

    /**
     * OUTREC segment 1 input offset (0-indexed). From {@code 1:263,16}
     * &mdash; output position 1 receives 16 bytes starting at input
     * position 263 (= 0-indexed offset 262).
     */
    private static final int OUTREC_SEG1_INPUT_OFFSET = 262;

    /**
     * OUTREC segment 1 output offset (0-indexed). From {@code 1:263,16}
     * &mdash; output position 1 = 0-indexed offset 0.
     */
    private static final int OUTREC_SEG1_OUTPUT_OFFSET = 0;

    /** OUTREC segment 1 length. From {@code 1:263,16} &mdash; 16 bytes. */
    private static final int OUTREC_SEG1_LENGTH = 16;

    /**
     * OUTREC segment 2 input offset (0-indexed). From {@code 17:1,262}
     * &mdash; output position 17 receives 262 bytes starting at input
     * position 1 (= 0-indexed offset 0).
     */
    private static final int OUTREC_SEG2_INPUT_OFFSET = 0;

    /**
     * OUTREC segment 2 output offset (0-indexed). From {@code 17:1,262}
     * &mdash; output position 17 = 0-indexed offset 16.
     */
    private static final int OUTREC_SEG2_OUTPUT_OFFSET = 16;

    /** OUTREC segment 2 length. From {@code 17:1,262} &mdash; 262 bytes. */
    private static final int OUTREC_SEG2_LENGTH = 262;

    /**
     * OUTREC segment 3 input offset (0-indexed). From {@code 279:279,50}
     * &mdash; output position 279 receives 50 bytes starting at input
     * position 279 (= 0-indexed offset 278).
     */
    private static final int OUTREC_SEG3_INPUT_OFFSET = 278;

    /**
     * OUTREC segment 3 output offset (0-indexed). From {@code 279:279,50}
     * &mdash; output position 279 = 0-indexed offset 278.
     */
    private static final int OUTREC_SEG3_OUTPUT_OFFSET = 278;

    /** OUTREC segment 3 length. From {@code 279:279,50} &mdash; 50 bytes. */
    private static final int OUTREC_SEG3_LENGTH = 50;

    // -----------------------------------------------------------------------
    // Configuration keys (12-factor per AAP §0.7.2; env vars override
    // system properties, blanks fall through to the documented defaults)
    // -----------------------------------------------------------------------

    /** System-property / env-var key for the TRANSACT KSDS input path. */
    static final String PROP_TRANSACT_PATH = "carddemo.file.transact.path";
    /** Default TRANSACT path if neither sys-prop nor env-var supplies a value. */
    static final String DEFAULT_TRANSACT_PATH = "./data/transact.dat";

    /** System-property / env-var key for the CARDXREF KSDS path (STEP040 XREFFILE). */
    static final String PROP_CARDXREF_PATH = "carddemo.file.cardxref.path";
    /** Default CARDXREF path. */
    static final String DEFAULT_CARDXREF_PATH = "./data/cardxref.dat";

    /** System-property / env-var key for the CUSTDATA KSDS path (STEP040 CUSTFILE). */
    static final String PROP_CUSTDATA_PATH = "carddemo.file.custdata.path";
    /** Default CUSTDATA path. */
    static final String DEFAULT_CUSTDATA_PATH = "./data/custdata.dat";

    /** System-property / env-var key for the ACCTDATA KSDS path (STEP040 ACCTFILE). */
    static final String PROP_ACCTDATA_PATH = "carddemo.file.acctdata.path";
    /** Default ACCTDATA path. */
    static final String DEFAULT_ACCTDATA_PATH = "./data/acctdata.dat";

    /** System-property / env-var key for the STATEMNT.PS output path (STMTFILE). */
    static final String PROP_STMT_TEXT_PATH = "carddemo.file.stmt.text.path";
    /** Default STATEMNT.PS path. */
    static final String DEFAULT_STMT_TEXT_PATH = "./output/statements.txt";

    /** System-property / env-var key for the STATEMNT.HTML output path (HTMLFILE). */
    static final String PROP_STMT_HTML_PATH = "carddemo.file.stmt.html.path";
    /** Default STATEMNT.HTML path. */
    static final String DEFAULT_STMT_HTML_PATH = "./output/statements.html";

    /** System-property / env-var key for the scratch work directory. */
    static final String PROP_WORK_PATH = "carddemo.work.path";
    /** Default scratch directory. */
    static final String DEFAULT_WORK_PATH = "./work";

    /**
     * Working file name for the SORT output (STEP010) and REPRO source
     * (STEP020). Maps to z/OS DSN {@code AWS.M2.CARDDEMO.TRXFL.SEQ}.
     */
    private static final String WORK_TRXFL_SEQ_FILENAME = "trxfl.seq";

    /**
     * Working file name for the SORT output post-REPRO (STEP020 target).
     * Maps to z/OS DSN {@code AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS}. This is
     * the file consumed by CBSTM03A's TRNXFILE DD in STEP040.
     */
    private static final String WORK_TRXFL_KSDS_FILENAME = "trxfl.dat";

    /**
     * Sidecar suffix for KSDS schema files. The {@code .schema} file
     * records the cluster name, key length, key offset, record length,
     * and control-interval size of a KSDS &mdash; the metadata that
     * IDCAMS DEFINE CLUSTER captures and that downstream tooling
     * (CBSTM03B in particular) may need to validate.
     */
    private static final String SCHEMA_SUFFIX = ".schema";

    // -----------------------------------------------------------------------
    // Return codes (preserved from COBOL conventions and CBSTM03A)
    // -----------------------------------------------------------------------

    /**
     * Application return code: clean completion. Matches CbStm03A.APPL_AOK
     * and IDCAMS {@code MAXCC=0}.
     */
    static final int RC_OK = 0;

    /**
     * Application return code: missing required input. The COBOL
     * {@code 9999-ABEND-PROGRAM} flow on a missing TRANSACT file
     * produces a non-zero abend code that operations treats as
     * &quot;input not staged yet&quot; rather than a logic error;
     * mapped here to RC=12.
     */
    static final int RC_NO_INPUT = 12;

    /**
     * Application return code: catastrophic error &mdash; uncaught
     * exception, invalid return value, or any out-of-range RC.
     * Matches the upper IDCAMS COND threshold.
     */
    static final int RC_ERROR = 16;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /** Utility class &mdash; not instantiable. */
    private CreateStatementsApp() {
        throw new AssertionError("CreateStatementsApp is not constructible");
    }

    // -----------------------------------------------------------------------
    // Main entry point
    // -----------------------------------------------------------------------

    /**
     * Java main entry point bound by the {@code shade-create-statements}
     * execution in {@code carddemo-app/pom.xml}. Mirrors the JCL job
     * lifecycle:
     * <ol>
     *   <li>Build a {@link BatchRunContext} from environment / system
     *       properties via {@link BatchRunContext#fromEnvironment()}.</li>
     *   <li>Bind the context to {@link #BATCH_CTX} for the duration of
     *       {@link #execute()} using
     *       {@code ScopedValue.where(BATCH_CTX, ctx).call(...)}.</li>
     *   <li>Convert the {@link Integer} return code into a process exit
     *       code via an exhaustive pattern-matching switch (Java 21
     *       Final). Coverage proof: {@code case null} + five known
     *       constants ({@code 0}, {@code 4}, {@code 8}, {@code 12},
     *       {@code 16}) + two guards for negative and {@code > 16} + a
     *       final unguarded type pattern for the remaining cases. The
     *       compiler enforces exhaustiveness; <strong>no {@code default}
     *       branch is permitted</strong> per AAP &sect;0.7.3.</li>
     * </ol>
     *
     * <p>The selector is intentionally boxed to {@link Integer} so the
     * switch can use standard pattern matching with type patterns
     * ({@code case Integer i when ...}) &mdash; primitive patterns on
     * {@code int} are JEP 507 preview, forbidden by AAP &sect;0.7.4.
     *
     * @param args ignored; the COBOL JCL job takes no PARM
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(CreateStatementsApp::execute);
        } catch (Exception e) {
            LOG.error("CREASTMT job failed with uncaught exception", e);
            rc = RC_ERROR;
        }
        int exitCode = switch (rc) {
            case null -> RC_ERROR;
            case 0 -> 0;
            case 4 -> 4;
            case 8 -> 8;
            case 12 -> 12;
            case 16 -> 16;
            case Integer i when i < 0 -> RC_ERROR;
            case Integer i when i > 16 -> RC_ERROR;
            case Integer i -> i;
        };
        System.exit(exitCode);
    }

    // -----------------------------------------------------------------------
    // Job body — five steps matching the JCL
    // -----------------------------------------------------------------------

    /**
     * Runs the five-step CREASTMT job body inside the {@link #BATCH_CTX}
     * scope. Each step matches the corresponding JCL EXEC stanza:
     * <ol>
     *   <li>DELDEF01 &mdash; {@link #defineTrxflKsds(Path, Path, Path)
     *       defineTrxflKsds()}</li>
     *   <li>STEP010 &mdash; {@link #sortTransact(Path, Path)
     *       sortTransact()}</li>
     *   <li>STEP020 &mdash; {@link #reproIntoKsds(Path, Path)
     *       reproIntoKsds()}</li>
     *   <li>STEP030 &mdash; {@link #deletePriorStatementOutputs(Path,
     *       Path) deletePriorStatementOutputs()}</li>
     *   <li>STEP040 &mdash; {@link #invokeCbStm03A(Path, Path, Path,
     *       Path, Path, Path) invokeCbStm03A()}</li>
     * </ol>
     *
     * <p>The {@code COND=(0,NE)} clauses on STEP020/STEP030/STEP040 are
     * realised by early {@code return}s on any prior-step failure: once
     * we return from {@code execute()} the {@link #main(String[]) main}
     * method short-circuits and never invokes the subsequent step.
     *
     * @return one of {@link #RC_OK}, {@link #RC_NO_INPUT}, or
     *         {@link #RC_ERROR}; or any value returned by
     *         {@link CbStm03A#run() CbStm03A.run()} ({@code APPL_AOK=0},
     *         {@code APPL_ERROR=12}, {@code APPL_EOF=16})
     * @throws IOException if any file I/O operation fails &mdash; the
     *                     caller in {@link #main} logs the exception and
     *                     converts it to a {@link #RC_ERROR} exit code
     */
    static int execute() throws IOException {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("CREASTMT job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        // === FAITHFUL TRANSLATION NOTE per AAP §0.7.1 ===
        // Emit a WARN log at startup citing the malformed STMTFILE DD at
        // line 90 of CREASTMT.JCL. This is the mandated runtime visibility
        // of the preserved bug; the Javadoc on this class documents the
        // verbatim malformed line. We do NOT silently "fix" the JCL.
        LOG.warn("FAITHFUL TRANSLATION NOTE (AAP §0.7.1): STMTFILE DD at line 90 of "
                + "app/jcl/CREASTMT.JCL is malformed with garbled trailing tokens "
                + "'00,RECFM=FB), ATA.VSAM.KSDS' after the SPACE= keyword. "
                + "Translated using the valid intent only (LRECL=80 RECFM=FB -> "
                + "STATEMNT.PS). See class Javadoc and java/MIGRATION_NOTES.md.");

        // ---- Path resolution (12-factor, env > sysprop > default) -------
        Path transactPath = SafePathResolver.resolveTrusted(PROP_TRANSACT_PATH, DEFAULT_TRANSACT_PATH);
        Path cardxrefPath = SafePathResolver.resolveTrusted(PROP_CARDXREF_PATH, DEFAULT_CARDXREF_PATH);
        Path custdataPath = SafePathResolver.resolveTrusted(PROP_CUSTDATA_PATH, DEFAULT_CUSTDATA_PATH);
        Path acctdataPath = SafePathResolver.resolveTrusted(PROP_ACCTDATA_PATH, DEFAULT_ACCTDATA_PATH);
        Path stmtTextPath = SafePathResolver.resolveTrusted(PROP_STMT_TEXT_PATH, DEFAULT_STMT_TEXT_PATH);
        Path stmtHtmlPath = SafePathResolver.resolveTrusted(PROP_STMT_HTML_PATH, DEFAULT_STMT_HTML_PATH);
        Path workDir = SafePathResolver.resolveTrusted(PROP_WORK_PATH, DEFAULT_WORK_PATH);

        Files.createDirectories(workDir);
        Path trxflSeq = workDir.resolve(WORK_TRXFL_SEQ_FILENAME);
        Path trxflKsds = workDir.resolve(WORK_TRXFL_KSDS_FILENAME);
        Path trxflSchema = trxflKsds.resolveSibling(trxflKsds.getFileName() + SCHEMA_SUFFIX);

        LOG.info("CREASTMT: paths resolved; transact={}, cardxref={}, custdata={}, "
                        + "acctdata={}, stmt.text={}, stmt.html={}, work.dir={}",
                transactPath, cardxrefPath, custdataPath, acctdataPath,
                stmtTextPath, stmtHtmlPath, workDir);

        // ============ DELDEF01: IDCAMS DELETE+DEFINE TRXFL.VSAM.KSDS ====
        defineTrxflKsds(trxflSeq, trxflKsds, trxflSchema);

        // ============ STEP010: PGM=SORT =================================
        if (!Files.exists(transactPath)) {
            LOG.error("STEP010: TRANSACT input not found at {}; returning rc={}",
                    transactPath, RC_NO_INPUT);
            return RC_NO_INPUT;
        }
        sortTransact(transactPath, trxflSeq);

        // ============ STEP020: IDCAMS REPRO (COND=(0,NE)) ===============
        // STEP020 has COND=(0,NE) which bypasses the step if ANY prior step
        // had RC != 0. Java translation: we reach this line only on the
        // success path of DELDEF01 and STEP010, so the COND check is
        // satisfied implicitly by the control flow.
        reproIntoKsds(trxflSeq, trxflKsds);

        // ============ STEP030: IEFBR14 cleanup (COND=(0,NE)) ============
        // IEFBR14 is a z/OS no-op program; its DD allocations with
        // DISP=(MOD,DELETE,DELETE) cause the system to ensure-and-delete
        // the prior STATEMNT.HTML and STATEMNT.PS datasets. The Java
        // translation deletes them directly (idempotent — no error if
        // the file does not exist).
        deletePriorStatementOutputs(stmtTextPath, stmtHtmlPath);

        // ============ STEP040: PGM=CBSTM03A (COND=(0,NE)) ===============
        // Invoke the statement-generation engine with the 4 input paths
        // (TRNXFILE comes from the just-built TRXFL.VSAM.KSDS) and 2
        // output paths.
        int cbstm03aRc = invokeCbStm03A(trxflKsds, cardxrefPath, custdataPath,
                acctdataPath, stmtTextPath, stmtHtmlPath);

        LOG.info("CREASTMT job complete; rc={}, statements written to text={} html={}",
                cbstm03aRc, stmtTextPath, stmtHtmlPath);
        return cbstm03aRc;
    }

    // -----------------------------------------------------------------------
    // DELDEF01 — IDCAMS DELETE+DEFINE TRXFL.VSAM.KSDS
    // -----------------------------------------------------------------------

    /**
     * Translates the DELDEF01 IDCAMS DELETE+DEFINE step
     * (CREASTMT.JCL lines 22-39). Removes any stale TRXFL.SEQ and
     * TRXFL.VSAM.KSDS from a prior run, then writes a fresh
     * {@code .schema} sidecar capturing the cluster spec
     * ({@link #TRXFL_KEY_LENGTH KEYS(32 0)},
     * {@link #TRXFL_RECORD_LENGTH RECORDSIZE(350)},
     * {@link #TRXFL_CISIZE CISZ(4096)}).
     *
     * <p>The IDCAMS DELETE statements in the JCL are followed by
     * {@code SET MAXCC = 0} which clears any &quot;file not found&quot;
     * error from the DELETE; the Java translation accomplishes the same
     * by using {@link Files#deleteIfExists(Path)} which is idempotent.
     *
     * @param trxflSeq    path to the prior TRXFL.SEQ working file
     *                    (deleted if present)
     * @param trxflKsds   path to the prior TRXFL.VSAM.KSDS working file
     *                    (deleted if present)
     * @param trxflSchema path to the schema sidecar to (re)create
     * @throws IOException if deleting or writing fails
     */
    private static void defineTrxflKsds(Path trxflSeq, Path trxflKsds, Path trxflSchema)
            throws IOException {
        Files.deleteIfExists(trxflSeq);
        Files.deleteIfExists(trxflKsds);
        Files.deleteIfExists(trxflSchema);

        // Write the schema sidecar describing the cluster the JCL would
        // have produced. Downstream tooling (notably the CBSTM03B
        // file-services layer) may use this to validate or self-describe
        // the keyed dataset.
        String schemaText = ""
                + "cluster=" + TRXFL_CLUSTER_NAME + "\n"
                + "keyLength=" + TRXFL_KEY_LENGTH + "\n"
                + "keyOffset=" + TRXFL_KEY_OFFSET + "\n"
                + "recordLength=" + TRXFL_RECORD_LENGTH + "\n"
                + "ciSize=" + TRXFL_CISIZE + "\n";
        Files.writeString(trxflSchema, schemaText,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        LOG.info("DELDEF01: DEFINE OK cluster={}, key=({},{}), record={}, CISZ({})",
                TRXFL_CLUSTER_NAME, TRXFL_KEY_LENGTH, TRXFL_KEY_OFFSET,
                TRXFL_RECORD_LENGTH, TRXFL_CISIZE);
    }

    // -----------------------------------------------------------------------
    // STEP010 — PGM=SORT
    // -----------------------------------------------------------------------

    /**
     * Translates the STEP010 SORT step (CREASTMT.JCL lines 44-55). Reads
     * the un-sorted TRANSACT KSDS as fixed-width 350-byte records, sorts
     * by the compound key (primary: card-num bytes 263-278; secondary:
     * tran-id bytes 1-16), applies the OUTREC rearrangement, and writes
     * the result to TRXFL.SEQ.
     *
     * <p>{@link java.util.List#sort(java.util.Comparator)} is guaranteed
     * stable per its contract; this matches DFSORT's default
     * {@code EQUALS} option (preserve input order for equal keys). Per
     * AAP &sect;0.6.6 the sort is single-threaded for deterministic
     * byte-for-byte parity with DFSORT &mdash; virtual-thread fan-out
     * would reorder equal-key records and is FORBIDDEN.
     *
     * <p>If TRANSACT's length is not a multiple of {@link #INPUT_RECORD_LENGTH}
     * a WARN is logged but the trailing partial record is silently
     * dropped, matching DFSORT's behaviour of truncating
     * misaligned input.
     *
     * @param transactPath path to the TRANSACT KSDS (must exist)
     * @param trxflSeq     path to the SORT output file (created with
     *                     {@link StandardOpenOption#CREATE_NEW
     *                     CREATE_NEW} so a stale file from DELDEF01 is
     *                     caught rather than silently overwritten)
     * @throws IOException if reading TRANSACT or writing TRXFL.SEQ fails
     */
    private static void sortTransact(Path transactPath, Path trxflSeq) throws IOException {
        byte[] inputBytes = Files.readAllBytes(transactPath);
        if (inputBytes.length % INPUT_RECORD_LENGTH != 0) {
            LOG.warn("STEP010: TRANSACT length {} is not a multiple of LRECL={}; "
                            + "trailing {} bytes ignored",
                    inputBytes.length, INPUT_RECORD_LENGTH,
                    inputBytes.length % INPUT_RECORD_LENGTH);
        }
        int recCount = inputBytes.length / INPUT_RECORD_LENGTH;
        List<byte[]> records = new ArrayList<>(recCount);
        for (int i = 0; i < recCount; i++) {
            byte[] rec = new byte[INPUT_RECORD_LENGTH];
            System.arraycopy(inputBytes, i * INPUT_RECORD_LENGTH, rec, 0, INPUT_RECORD_LENGTH);
            records.add(rec);
        }
        LOG.info("STEP010: read {} records ({} bytes) from TRANSACT={}",
                recCount, inputBytes.length, transactPath);

        // SORT FIELDS=(263,16,CH,A,1,16,CH,A): primary card-num (16 bytes
        // at offset 262); secondary tran-id (16 bytes at offset 0). CH =
        // unsigned-byte character order; ASCII fixtures coincide with
        // byte order.
        records.sort((a, b) -> {
            int cmp = compareBytes(a, SORT_PRIMARY_OFFSET, b, SORT_PRIMARY_OFFSET,
                    SORT_PRIMARY_LENGTH);
            if (cmp != 0) {
                return cmp;
            }
            return compareBytes(a, SORT_SECONDARY_OFFSET, b, SORT_SECONDARY_OFFSET,
                    SORT_SECONDARY_LENGTH);
        });

        // OUTREC FIELDS=(1:263,16, 17:1,262, 279:279,50): rearrange to put
        // the 16-byte card-num at the front, the first 262 bytes of the
        // original record next, and the trailing 50 bytes unchanged. Total
        // output width = 16 + 262 + 50 = 328 bytes.
        try (OutputStream out = Files.newOutputStream(trxflSeq, StandardOpenOption.CREATE_NEW)) {
            byte[] outRec = new byte[OUTREC_RECORD_LENGTH];
            for (byte[] in : records) {
                // 1:263,16  -> outRec[0..15]    <- in[262..277]
                System.arraycopy(in, OUTREC_SEG1_INPUT_OFFSET,
                        outRec, OUTREC_SEG1_OUTPUT_OFFSET, OUTREC_SEG1_LENGTH);
                // 17:1,262  -> outRec[16..277]  <- in[0..261]
                System.arraycopy(in, OUTREC_SEG2_INPUT_OFFSET,
                        outRec, OUTREC_SEG2_OUTPUT_OFFSET, OUTREC_SEG2_LENGTH);
                // 279:279,50 -> outRec[278..327] <- in[278..327]
                System.arraycopy(in, OUTREC_SEG3_INPUT_OFFSET,
                        outRec, OUTREC_SEG3_OUTPUT_OFFSET, OUTREC_SEG3_LENGTH);
                out.write(outRec);
            }
        }
        LOG.info("STEP010: SORT OK; wrote {} sorted records ({} bytes) to TRXFL.SEQ={}",
                records.size(), Files.size(trxflSeq), trxflSeq);
    }

    // -----------------------------------------------------------------------
    // STEP020 — PGM=IDCAMS REPRO (COND=(0,NE))
    // -----------------------------------------------------------------------

    /**
     * Translates the STEP020 IDCAMS REPRO step (CREASTMT.JCL lines 56-62).
     * Copies the sorted sequential file (TRXFL.SEQ) into the keyed file
     * (TRXFL.VSAM.KSDS), byte-for-byte. Because the file-based runtime
     * does not maintain a separate keyed-index structure (the schema
     * sidecar written in DELDEF01 is sufficient), the REPRO is a
     * straight byte copy.
     *
     * <p>The {@code COND=(0,NE)} clause in the JCL is honoured by early
     * {@code return}s in the calling {@link #execute()} method: this
     * helper is only invoked on the success path of DELDEF01 and
     * STEP010.
     *
     * @param trxflSeq  source file produced by STEP010
     * @param trxflKsds target file (will REPLACE_EXISTING per IDCAMS
     *                  REPRO semantics)
     * @throws IOException if copying fails
     */
    private static void reproIntoKsds(Path trxflSeq, Path trxflKsds) throws IOException {
        Files.copy(trxflSeq, trxflKsds, StandardCopyOption.REPLACE_EXISTING);
        LOG.info("STEP020: REPRO OK; source={}, target={}, bytes={}",
                trxflSeq, trxflKsds, Files.size(trxflKsds));
    }

    // -----------------------------------------------------------------------
    // STEP030 — PGM=IEFBR14 cleanup (COND=(0,NE))
    // -----------------------------------------------------------------------

    /**
     * Translates the STEP030 IEFBR14 cleanup step (CREASTMT.JCL lines
     * 63-75). The DD allocations with {@code DISP=(MOD,DELETE,DELETE)}
     * trigger ensure-and-delete semantics on the prior STATEMNT.HTML and
     * STATEMNT.PS datasets; the Java translation deletes them directly.
     *
     * <p>Uses {@link Files#deleteIfExists(Path)} for idempotency &mdash;
     * a missing prior file is not an error (matches z/OS behaviour where
     * the {@code MOD} disposition first allocates the dataset if it does
     * not exist).
     *
     * @param stmtTextPath path to the prior STATEMNT.PS (deleted if present)
     * @param stmtHtmlPath path to the prior STATEMNT.HTML (deleted if present)
     * @throws IOException if deletion fails (other than &quot;not found&quot;)
     */
    private static void deletePriorStatementOutputs(Path stmtTextPath, Path stmtHtmlPath)
            throws IOException {
        boolean stmtDeleted = Files.deleteIfExists(stmtTextPath);
        boolean htmlDeleted = Files.deleteIfExists(stmtHtmlPath);
        LOG.info("STEP030: cleanup OK; STATEMNT.PS={} (deleted={}), STATEMNT.HTML={} (deleted={})",
                stmtTextPath, stmtDeleted, stmtHtmlPath, htmlDeleted);
    }

    // -----------------------------------------------------------------------
    // STEP040 — PGM=CBSTM03A (COND=(0,NE))
    // -----------------------------------------------------------------------

    /**
     * Translates the STEP040 CBSTM03A invocation step (CREASTMT.JCL
     * lines 76-96). Constructs the {@link CbStm03B} file-services
     * helper with the four input file paths, then injects it (along
     * with the two output paths) into a single {@link CbStm03A}
     * statement-generation use case and invokes {@link CbStm03A#run()
     * run()}.
     *
     * <p>FAITHFUL TRANSLATION (AAP &sect;0.7.1): the STMTFILE DD at line
     * 90 of the JCL is malformed (see class Javadoc for the verbatim
     * citation). The Java translation honours the well-formed
     * {@code LRECL=80, RECFM=FB} attributes from the preceding line and
     * the canonical {@code DSN=AWS.M2.CARDDEMO.STATEMNT.PS} from the
     * following line. We do NOT modify the JCL and do NOT silently
     * &quot;fix&quot; the malformed tokens.
     *
     * <p>The parent directories of the two output paths are ensured to
     * exist before invocation; the output files themselves are opened
     * (truncate-on-write) by {@code CbStm03A}.
     *
     * <p>{@link CbStm03A#run()} catches all exceptions internally and
     * returns one of {@code APPL_AOK=0}, {@code APPL_ERROR=12}, or
     * {@code APPL_EOF=16}; no exception escapes the call and no
     * try/catch wrapping is required here.
     *
     * @param trxflKsds      TRNXFILE DD &mdash; the sorted KSDS produced
     *                       by STEP020
     * @param cardxrefPath   XREFFILE DD &mdash; CARDXREF.VSAM.KSDS
     * @param custdataPath   CUSTFILE DD &mdash; CUSTDATA.VSAM.KSDS
     * @param acctdataPath   ACCTFILE DD &mdash; ACCTDATA.VSAM.KSDS
     * @param stmtTextPath   STMTFILE DD &mdash; STATEMNT.PS output (the
     *                       valid intent of the malformed line 90)
     * @param stmtHtmlPath   HTMLFILE DD &mdash; STATEMNT.HTML output
     * @return the return code from {@link CbStm03A#run()}
     * @throws IOException if creating the output parent directories fails
     */
    private static int invokeCbStm03A(Path trxflKsds, Path cardxrefPath, Path custdataPath,
                                       Path acctdataPath, Path stmtTextPath, Path stmtHtmlPath)
            throws IOException {
        // Ensure output parent directories exist (do NOT create the files
        // themselves — CBSTM03A opens them with truncate-on-write
        // semantics in its openStatementOutputs() entry).
        ensureParent(stmtTextPath);
        ensureParent(stmtHtmlPath);

        LOG.info("STEP040: wiring CbStm03B + CbStm03A; trnx={}, xref={}, cust={}, acct={}, "
                        + "stmt.text={}, stmt.html={}",
                trxflKsds, cardxrefPath, custdataPath, acctdataPath,
                stmtTextPath, stmtHtmlPath);

        // CbStm03B's constructor order from the source:
        //   CbStm03B(Path trnxFilePath, Path xrefFilePath,
        //            Path custFilePath, Path acctFilePath)
        // — preserve the COBOL DD order TRNXFILE/XREFFILE/CUSTFILE/ACCTFILE.
        CbStm03B cbStm03B = new CbStm03B(trxflKsds, cardxrefPath, custdataPath, acctdataPath);

        // CbStm03A's constructor order from the source:
        //   CbStm03A(CbStm03B fileServices,
        //            Path statementTextOutputPath,
        //            Path statementHtmlOutputPath)
        // — STMTFILE first (the malformed-but-preserved DD), then HTMLFILE.
        CbStm03A cbStm03A = new CbStm03A(cbStm03B, stmtTextPath, stmtHtmlPath);

        int rc = cbStm03A.run();
        LOG.info("STEP040: CBSTM03A.run() returned rc={}", rc);
        return rc;
    }

    // -----------------------------------------------------------------------
    // Helpers — byte compare, parent-directory ensure, configuration lookup
    // -----------------------------------------------------------------------

    /**
     * Compares two byte regions, unsigned and lexicographically, of the
     * given length. Mirrors DFSORT's CH (character) comparator on
     * single-byte streams &mdash; bytes are compared as integers in
     * {@code [0, 255]}.
     *
     * @param a       left-hand byte array
     * @param aOff    offset into {@code a}
     * @param b       right-hand byte array
     * @param bOff    offset into {@code b}
     * @param length  number of bytes to compare
     * @return negative if {@code a} region &lt; {@code b} region, zero if
     *         equal, positive if {@code a} region &gt; {@code b} region
     */
    private static int compareBytes(byte[] a, int aOff, byte[] b, int bOff, int length) {
        for (int i = 0; i < length; i++) {
            int ai = a[aOff + i] & 0xFF;
            int bi = b[bOff + i] & 0xFF;
            if (ai != bi) {
                return Integer.compare(ai, bi);
            }
        }
        return 0;
    }

    /**
     * Creates the parent directory of the supplied path if it does not
     * already exist. No-op if the path has no parent (e.g., a bare
     * filename in the current working directory).
     *
     * @param path the target file path
     * @throws IOException if directory creation fails
     */
    private static void ensureParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /**
     * Resolves a configuration value with the documented precedence: env
     * var first, JVM system property second, otherwise the supplied
     * default.
     *
     * <p>The env-var name is derived from {@code key} by uppercasing
     * (using {@link Locale#ROOT} to avoid locale-dependent surprises
     * such as the Turkish dotless-i transformation) and substituting
     * underscores for dots and hyphens. Example:
     * {@code carddemo.file.transact.path} maps to
     * {@code CARDDEMO_FILE_TRANSACT_PATH}.
     *
     * <p>An env var or system property whose value is blank (per
     * {@link String#isBlank()}) is treated as &quot;not set&quot; so
     * that a deliberately-empty environment variable does not silently
     * override the default with an empty string. This matches the
     * resolution contract documented in
     * {@code application.properties.example}.
     *
     * @param key          the system-property key (e.g.,
     *                     {@code carddemo.file.transact.path}); must be
     *                     non-{@code null}
     * @param defaultValue the value to return when neither source
     *                     supplies a non-blank value; must be non-
     *                     {@code null}
     * @return the resolved value; never {@code null}
     */
    static String getProp(String key, String defaultValue) {
        String envKey = key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String fromProp = System.getProperty(key);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp;
        }
        return defaultValue;
    }
}
