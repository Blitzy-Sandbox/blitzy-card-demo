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

// JEP 511 (Module Import Declarations, Final in Java 25): a single declaration
// imports every package exported by the java.base module. Brings into scope:
//   - java.lang.ScopedValue        (JEP 506 Final; moved from java.util.concurrent
//                                   to java.lang when it was finalised in Java 25)
//   - java.nio.file.{Path, Files,
//                    StandardCopyOption}      (no java.io.File per AAP §0.6.5)
//   - java.io.IOException                     (execute() throws clause)
//   - java.util.Locale                        (env-var key normalization in getProp)
//   - java.lang.{String, Integer, System,
//                Exception, AssertionError}
// Per AAP §0.4.2 / §0.7.3 this single module-import declaration is mandated
// where many java.* packages are touched.
import module java.base;

import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/TRANFILE.jcl}
 * &mdash; a seven-step IDCAMS + SDSF job that re-creates, loads, and indexes
 * the {@code TRANSACT.VSAM.KSDS} cluster (Transaction master file) together
 * with its alternate index {@code TRANSACT.VSAM.AIX}.
 *
 * <h2>Source artefact</h2>
 * <p>The originating JCL job {@code app/jcl/TRANFILE.jcl} chains nine steps,
 * each preserved by this Java translation (AAP &sect;0.7.1 idiom-for-idiom
 * mandate). Unlike {@code XREFFILE.jcl}, this job sandwiches the IDCAMS steps
 * between two {@code EXEC PGM=SDSF} steps that drive CICS CEMT commands to
 * close and re-open the {@code TRANSACT} and {@code CXACAIX} CICS file
 * definitions.
 * <ol>
 *   <li><strong>CLCIFIL</strong> &mdash; {@code EXEC PGM=SDSF} that submits
 *       two CEMT commands to {@code CICSAWSA}:
 *       {@code 'CEMT SET FIL(TRANSACT ) CLO'} and
 *       {@code 'CEMT SET FIL(CXACAIX ) CLO'}. These close the CICS file
 *       definitions so that the IDCAMS steps can mutate the underlying VSAM
 *       cluster without contention. In the file-based runtime there is no
 *       CICS region; this Java translation captures the close intent in a
 *       structured-log line per AAP &sect;0.6.12 and proceeds.</li>
 *   <li><strong>STEP05</strong> &mdash; {@code EXEC PGM=IDCAMS} that runs two
 *       {@code DELETE} commands each followed by
 *       {@code IF MAXCC LE 08 THEN SET MAXCC = 0}: first the base cluster
 *       ({@code DELETE AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS CLUSTER}) and then
 *       the alternate index
 *       ({@code DELETE AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX ALTERNATEINDEX}).
 *       The {@code SET MAXCC = 0} idiom makes both deletes idempotent: a
 *       missing target is not an error.</li>
 *   <li><strong>STEP10</strong> &mdash; {@code EXEC PGM=IDCAMS} that runs
 *       {@code DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS)
 *       CYLINDERS(1 5) VOLUMES(AWSHJ1) KEYS(16 0) RECORDSIZE(350 350)
 *       SHAREOPTIONS(2 3) ERASE INDEXED)} with sibling {@code DATA} and
 *       {@code INDEX} components named
 *       {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS.DATA} and
 *       {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS.INDEX}.</li>
 *   <li><strong>STEP15</strong> &mdash; {@code EXEC PGM=IDCAMS} that runs
 *       {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} copying the flat
 *       sequential file {@code AWS.M2.CARDDEMO.DALYTRAN.PS.INIT} into the
 *       freshly defined KSDS cluster. The {@code .PS.INIT} suffix indicates
 *       this is the <em>initial</em> daily-transaction sequential file
 *       (i.e., the pristine baseline before any subsequent posting cycle).</li>
 *   <li><strong>STEP20</strong> &mdash; {@code EXEC PGM=IDCAMS} that runs
 *       {@code DEFINE ALTERNATEINDEX (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX)
 *       RELATE(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS) KEYS(26 304)
 *       NONUNIQUEKEY UPGRADE RECORDSIZE(350,350) VOLUMES(AWSHJ1)
 *       CYLINDERS(5,1))} with sibling {@code DATA} and {@code INDEX}
 *       components for the AIX. The AIX key spec {@code KEYS(26 304)}
 *       indexes the 26-byte field that begins at byte offset 304 of each
 *       350-byte TRANSACT record (the {@code TRAN-CARD-NUM} (16) +
 *       {@code TRAN-PROC-TS} prefix sub-key window per
 *       {@code app/cpy/CVTRA05Y.cpy}), supporting fast lookups of all
 *       transactions for a given card or timestamp prefix.</li>
 *   <li><strong>STEP25</strong> &mdash; {@code EXEC PGM=IDCAMS} that runs
 *       {@code DEFINE PATH (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX.PATH)
 *       PATHENTRY(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX))}. The path is the
 *       named handle through which CICS / batch programs open the
 *       AIX-driven view of the base cluster.</li>
 *   <li><strong>STEP30</strong> &mdash; {@code EXEC PGM=IDCAMS} that runs
 *       {@code BLDINDEX INDATASET(...KSDS) OUTDATASET(...AIX)}: on the
 *       mainframe this scans the loaded KSDS, extracts the AIX key from each
 *       record, and writes the AIX entries. In the file-based runtime the
 *       AIX entries are computed lazily at read time per AAP &sect;0.6.5,
 *       so this step degenerates to a structured-log no-op that preserves
 *       the JCL step ordering for parity with the COBOL baseline.</li>
 *   <li><strong>OPCIFIL</strong> &mdash; {@code EXEC PGM=SDSF} that submits
 *       the inverse CEMT pair: {@code 'CEMT SET FIL(TRANSACT ) OPE'} and
 *       {@code 'CEMT SET FIL(CXACAIX ) OPE'} to re-open the CICS file
 *       definitions now that the IDCAMS work is complete. Translated to a
 *       structured-log line per AAP &sect;0.6.12.</li>
 * </ol>
 *
 * <h2>Record layout (per {@code app/cpy/CVTRA05Y.cpy:&sect;TRAN-RECORD})</h2>
 * Each input record (see {@code app/data/ASCII/dailytran.txt}) is exactly
 * 350 bytes; the KSDS primary key is the 16-byte {@code TRAN-ID} occupying
 * bytes 0..15 and the AIX key is a 26-byte window at byte offset 304. The
 * KSDS primary key length (16) and offset (0) below match the JCL
 * {@code KEYS(16 0)} clause exactly; the AIX key length (26) and offset
 * (304) match {@code KEYS(26 304)}; the record length (350) matches
 * {@code RECORDSIZE(350 350)}. Any change to these constants would break
 * the downstream TRANSACT readers (file-based or DB-backed) that consume
 * the loaded file.
 *
 * <h2>File mapping</h2>
 * <ul>
 *   <li>{@code AWS.M2.CARDDEMO.DALYTRAN.PS.INIT} (mainframe sequential
 *       staging file, &quot;initial&quot; daily-transaction baseline)
 *       &rarr; the source flat file at
 *       {@code carddemo.file.dailytran.path} (default
 *       {@code ./data/dailytran.dat}). For test and demo runs without a
 *       prior daily-tran load step, set this property to point at the
 *       350-byte ASCII fixture preserved unchanged under {@code app/} per
 *       AAP &sect;0.2.2:
 *       {@code -Dcarddemo.file.dailytran.path=app/data/ASCII/dailytran.txt}.</li>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} (mainframe VSAM KSDS)
 *       &rarr; the file at {@code carddemo.file.transact.path} (default
 *       {@code ./data/transact.dat}) plus a sibling {@code <name>.schema}
 *       text file carrying the KSDS metadata (cluster name, key spec,
 *       record size, share options, ERASE flag, data &amp; index component
 *       names).</li>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX} (mainframe VSAM AIX)
 *       &rarr; a sibling {@code <name>.aix.schema} text file carrying the
 *       AIX metadata (AIX name, RELATE base cluster, {@code KEYS(26 304)},
 *       {@code NONUNIQUEKEY}, {@code UPGRADE}, {@code RECORDSIZE(350,350)},
 *       {@code VOLUMES(AWSHJ1)}, {@code CYLINDERS(5,1)}, DATA &amp; INDEX
 *       component names). The AIX is realised lazily at read time per AAP
 *       &sect;0.6.5; no AIX-data file is written in this step.</li>
 * </ul>
 *
 * <h2>Translation strategy &mdash; idiom-for-idiom (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>SDSF {@code F CICSAWSA,'CEMT SET FIL(...) CLO/OPE'} commands become
 *       structured-log lines per AAP &sect;0.6.12 (file-based runtime has no
 *       CICS region; the close/open intent is preserved in the log for
 *       trace-comparison parity with the COBOL baseline).</li>
 *   <li>IDCAMS {@code DELETE ... CLUSTER} and
 *       {@code DELETE ... ALTERNATEINDEX} + the {@code SET MAXCC = 0} idiom
 *       become idempotent {@link java.nio.file.Files#deleteIfExists(Path)}
 *       calls: a missing target file simply returns {@code false} without
 *       throwing, reproducing the mainframe &quot;swallow the not-found
 *       condition&quot; behaviour.</li>
 *   <li>IDCAMS {@code DEFINE CLUSTER ...} becomes a text {@code .schema}
 *       sidecar file that records the KSDS metadata verbatim (cluster name,
 *       {@code KEYS(16 0)}, {@code RECORDSIZE(350 350)},
 *       {@code SHAREOPTIONS(2 3)}, {@code ERASE}, {@code INDEXED},
 *       {@code DATA} and {@code INDEX} component names, {@code VOLUMES(AWSHJ1)},
 *       {@code CYLINDERS(1 5)}) so downstream readers can validate the
 *       layout. The cluster name is preserved verbatim
 *       ({@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}) for log-comparison
 *       fidelity.</li>
 *   <li>IDCAMS {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} becomes a
 *       {@link java.nio.file.Files#copy(Path, Path,
 *       java.nio.file.CopyOption...)} call from the staging flat file to
 *       the KSDS target with
 *       {@link java.nio.file.StandardCopyOption#REPLACE_EXISTING}. Records
 *       are loaded in their existing file order, matching IDCAMS REPRO's
 *       byte-for-byte copy behaviour (the input is already in
 *       transaction-id-ascending order, so REPRO performs no re-ordering
 *       on the mainframe either).</li>
 *   <li>IDCAMS {@code DEFINE ALTERNATEINDEX ...} becomes a text
 *       {@code .aix.schema} sidecar file that records the AIX metadata
 *       verbatim (AIX name, RELATE clause, key spec, NONUNIQUEKEY, UPGRADE,
 *       record size, volumes, cylinders, DATA/INDEX component names).
 *       Writing the sidecar codifies the AIX contract for downstream
 *       readers that may resolve transactions by the AIX-key window via
 *       the AIX.</li>
 *   <li>IDCAMS {@code DEFINE PATH ...} becomes a structured-log line that
 *       captures the path name and {@code PATHENTRY} target; no file is
 *       written because path-based open semantics do not have a direct
 *       analogue in the file-based runtime.</li>
 *   <li>IDCAMS {@code BLDINDEX ...} becomes a structured-log no-op. The
 *       AIX entries are computed on demand by the read-side adapter (AAP
 *       &sect;0.6.5: file-based adapter reading fixed-width records via
 *       {@code java.nio.file}); a pre-built physical AIX file is not
 *       needed to preserve external behaviour.</li>
 * </ul>
 *
 * <h2>Process-exit semantics</h2>
 * <p>Each IDCAMS-style step contributes to the highest condition code
 * (MAXCC) the JCL would observe. This main preserves that contract:
 * <ul>
 *   <li>{@link #RC_OK} (0) &mdash; every step succeeded.</li>
 *   <li>{@link #RC_NO_INPUT} (4) &mdash; the staging source file is missing.
 *       IDCAMS REPRO would emit condition code 4 (&quot;input dataset not
 *       found&quot;); this Java translation surfaces the equivalent return
 *       code so downstream batch orchestration can branch on it.</li>
 *   <li>{@link #RC_ERROR} (16) &mdash; any uncaught exception (IDCAMS
 *       &quot;severe error&quot; convention). Any value &lt; 0 or &gt; 16
 *       is also clamped to 16, matching the behaviour of {@code COND}
 *       expression evaluation in z/OS JCL.</li>
 * </ul>
 *
 * <h2>BatchRunContext / ScopedValue (AAP &sect;0.6.6, JEP 506 Final)</h2>
 * <p>{@link #main(String[])} resolves a {@link BatchRunContext} via
 * {@link BatchRunContext#fromEnvironment()} (12-factor configuration via
 * JVM system properties and environment variables) and binds it to
 * {@link #BATCH_CTX} for the duration of {@link #execute()}. Inside
 * {@code execute()}, {@code runId}, {@code processingDate}, and
 * {@code tenant} are read from {@code BATCH_CTX.get()} for the
 * structured-logging startup line so that operations teams can correlate
 * log lines with a specific batch run identifier. <strong>No
 * {@link ThreadLocal} is used anywhere</strong>, in keeping with
 * AAP &sect;0.7.4.
 *
 * <h2>JVM-tuning recommendation (AAP &sect;0.3.4)</h2>
 * Launch with the finalised GC and heap-layout flags:
 * <pre>
 *   java -XX:+UseCompactObjectHeaders          (JEP 519, Final in 25)
 *        -XX:+UseShenandoahGC
 *        -XX:ShenandoahGCMode=generational     (JEP 521, Final in 25)
 *        -jar carddemo-app/target/carddemo-app-&lt;version&gt;-shaded.jar
 * </pre>
 *
 * <h2>Non-goals (AAP &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring, no Lombok, no Apache Commons.</li>
 *   <li>No reflection or dynamic proxies.</li>
 *   <li>No {@code java.io.File} &mdash; {@link java.nio.file} only per
 *       AAP &sect;0.6.5.</li>
 *   <li>No {@link ThreadLocal} &mdash; {@link java.lang.ScopedValue} only
 *       per AAP &sect;0.6.6.</li>
 *   <li>No preview features &mdash; the {@code --enable-preview} flag is
 *       forbidden.</li>
 *   <li>No actual VSAM / IDCAMS / SDSF / CICS interaction &mdash; the
 *       file-based runtime writes a fixed-width file plus sidecar schema
 *       descriptors per AAP &sect;0.6.12.</li>
 * </ul>
 *
 * @see "app/jcl/TRANFILE.jcl   the originating JCL job (seven-step DELETE/DEFINE/REPRO/AIX, sandwiched between SDSF CEMT close/open)"
 * @see "app/cpy/CVTRA05Y.cpy   the TRAN-RECORD copybook defining the 350-byte layout"
 * @see BatchRunContext        the immutable context bound to {@link #BATCH_CTX}
 * @see DefineCardXrefApp      the sibling XREFFILE bootstrap (also KSDS + AIX, no SDSF)
 * @since 1.0.0
 */
@CobolProgram(
        value = "IDCAMS+SDSF (TRANFILE.jcl)",
        sourcePath = "app/jcl/TRANFILE.jcl",
        translationDate = "2025-10-24",
        notes = "JCL translation of the nine-step TRANFILE KSDS+AIX bootstrap (CLCIFIL SDSF "
                + "CEMT close TRANSACT+CXACAIX + STEP05 IDCAMS DELETE CLUSTER and ALTERNATEINDEX "
                + "+ STEP10 IDCAMS DEFINE CLUSTER KEYS(16 0) RECORDSIZE(350 350) VOLUMES(AWSHJ1) "
                + "CYLINDERS(1 5) SHAREOPTIONS(2 3) ERASE INDEXED + STEP15 IDCAMS REPRO from "
                + "AWS.M2.CARDDEMO.DALYTRAN.PS.INIT + STEP20 IDCAMS DEFINE ALTERNATEINDEX "
                + "KEYS(26 304) NONUNIQUEKEY UPGRADE RECORDSIZE(350,350) VOLUMES(AWSHJ1) "
                + "CYLINDERS(5,1) + STEP25 IDCAMS DEFINE PATH + STEP30 IDCAMS BLDINDEX + "
                + "OPCIFIL SDSF CEMT open TRANSACT+CXACAIX). In the file-based runtime the "
                + "KSDS becomes a 350-byte fixed-width file plus sidecar .schema and "
                + ".aix.schema descriptors; the staging PS file defaults to ./data/dailytran.dat "
                + "(populated by an upstream load step) and may be overridden to the preserved "
                + "fixture at app/data/ASCII/dailytran.txt (AAP §0.2.2). SDSF CEMT commands "
                + "translate to log-only lines per AAP §0.6.12. AIX entries are computed "
                + "lazily at read time per AAP §0.6.5, so BLDINDEX is a no-op."
)
public final class DefineTransactionFileApp {

    // ---------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------

    /**
     * SLF4J logger. Backed at runtime by logback-classic supplied via the
     * {@code carddemo-app} shaded jar packaging (AAP &sect;0.5.1); this
     * class deliberately holds no reference to a concrete logging
     * implementation. No PAN, no password, no card data is ever logged
     * from this app &mdash; the only payload logged is metadata (counts,
     * byte sizes, cluster names).
     */
    private static final Logger LOG = LoggerFactory.getLogger(DefineTransactionFileApp.class);

    // ---------------------------------------------------------------------
    // ScopedValue (JEP 506 Final) — propagates BatchRunContext into execute()
    // ---------------------------------------------------------------------

    /**
     * Local {@link java.lang.ScopedValue} for the {@link BatchRunContext}
     * that orchestrates this job. Bound by {@link #main(String[])} via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(DefineTransactionFileApp::execute)}
     * and read inside {@link #execute()} via {@code BATCH_CTX.get()} to
     * emit the {@code runId}, {@code processingDate}, and {@code tenant}
     * on the TRANFILE startup log line.
     *
     * <p>This binding <strong>replaces {@link ThreadLocal} entirely</strong>
     * per AAP &sect;0.6.6 and &sect;0.7.4. The {@code ScopedValue} class
     * moved from {@code java.util.concurrent} (preview) to {@code java.lang}
     * (final) when JEP 506 was finalised in Java 25, so it is available
     * here via the JEP 511 {@code import module java.base} declaration
     * above.
     *
     * <p>Per the export schema for this file, this field is part of the
     * {@code DefineTransactionFileApp} public surface &mdash; co-located
     * with {@link BatchRunContext#BATCH_CTX} but scoped to this app to
     * keep the binding window narrow (one {@code ScopedValue} per
     * composition root, matching the sibling {@link DefineCardXrefApp}
     * convention).
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // ---------------------------------------------------------------------
    // KSDS cluster metadata — verbatim from app/jcl/TRANFILE.jcl STEP10
    // ---------------------------------------------------------------------

    /** VSAM KSDS cluster name preserved verbatim from {@code TRANFILE.jcl} STEP10. */
    static final String CLUSTER_NAME = "AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS";

    /** Sequential staging file name preserved verbatim from {@code TRANFILE.jcl} STEP15. */
    static final String PS_NAME = "AWS.M2.CARDDEMO.DALYTRAN.PS.INIT";

    /**
     * {@code KEYS(16 0)} &mdash; KSDS primary-key length in bytes (16).
     * <p>Matches the {@code TRAN-ID} field from {@code app/cpy/CVTRA05Y.cpy}:
     * a 16-byte transaction identifier used as the KSDS primary key.
     */
    static final int KEY_LENGTH = 16;

    /** {@code KEYS(16 0)} &mdash; KSDS primary-key offset from record start (0). */
    static final int KEY_OFFSET = 0;

    /** {@code RECORDSIZE(350 350)} &mdash; fixed record length in bytes (350). */
    static final int RECORD_LENGTH = 350;

    /** {@code SHAREOPTIONS(2 3)} &mdash; cross-region/cross-system share options. */
    static final String SHARE_OPTIONS = "2 3";

    /** {@code CYLINDERS(1 5)} &mdash; primary 1 cyl, secondary 5 cyl space allocation. */
    static final String CYLINDERS = "1 5";

    /** {@code VOLUMES(AWSHJ1)} &mdash; AWS Mainframe Modernization volume name. */
    static final String VOLUMES = "AWSHJ1";

    /** {@code ERASE} flag indicating cleared-on-delete is requested for this cluster. */
    static final boolean ERASE = true;

    /** {@code INDEXED} flag indicating this is a KSDS (key-sequenced) cluster. */
    static final boolean INDEXED = true;

    // ---------------------------------------------------------------------
    // AIX (Alternate Index) metadata — verbatim from TRANFILE.jcl STEP20/STEP25
    // ---------------------------------------------------------------------

    /** VSAM AIX name preserved verbatim from {@code TRANFILE.jcl} STEP20. */
    static final String AIX_NAME = "AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX";

    /** VSAM AIX PATH name preserved verbatim from {@code TRANFILE.jcl} STEP25. */
    static final String AIX_PATH_NAME = "AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX.PATH";

    /**
     * {@code KEYS(26 304)} &mdash; AIX key length in bytes (26). Indexes a
     * 26-byte window beginning at byte offset 304 of each 350-byte
     * TRANSACT record, supporting fast AIX-keyed lookups (e.g., all
     * transactions for a given card-num + processed-timestamp prefix
     * pair per {@code app/cpy/CVTRA05Y.cpy}).
     */
    static final int AIX_KEY_LENGTH = 26;

    /**
     * {@code KEYS(26 304)} &mdash; AIX key offset from record start (304).
     * <p>The 26-byte window from byte 304 inclusive to byte 329 inclusive
     * matches the AIX-key region defined in the JCL exactly. Any change
     * here would break the AIX-keyed read-side adapter.
     */
    static final int AIX_KEY_OFFSET = 304;

    /**
     * {@code NONUNIQUEKEY} &mdash; the AIX key is not unique because
     * multiple TRANSACT records may share the same AIX-key window (e.g.,
     * multiple transactions on the same card on the same processed
     * timestamp window). The corresponding string token in the JCL is
     * bare ({@code NONUNIQUEKEY}); the boolean here is a Java-friendly
     * representation written into the schema sidecar.
     */
    static final boolean AIX_NONUNIQUE_KEY = true;

    /**
     * {@code UPGRADE} &mdash; the AIX is automatically kept in sync with
     * the base cluster on every base-cluster update. Preserved into the
     * schema sidecar so the read-side adapter knows the AIX-key-derivation
     * contract is consistent with each base-cluster mutation.
     */
    static final boolean AIX_UPGRADE = true;

    /**
     * {@code CYLINDERS(5,1)} &mdash; AIX space allocation: primary 5 cyl,
     * secondary 1 cyl. Note that this is INTENTIONALLY the inverse of the
     * base-cluster {@code CYLINDERS(1 5)} allocation: the AIX is expected
     * to be larger up front (since NONUNIQUEKEY entries fan out for each
     * key value) and grow less aggressively over time.
     */
    static final String AIX_CYLINDERS = "5 1";

    // ---------------------------------------------------------------------
    // SDSF CEMT metadata — verbatim from TRANFILE.jcl CLCIFIL/OPCIFIL
    // ---------------------------------------------------------------------

    /**
     * CICS region name targeted by the {@code F CICSAWSA,'CEMT SET ...'}
     * modify commands in the {@code CLCIFIL} and {@code OPCIFIL} SDSF
     * steps. Preserved verbatim from the JCL for trace-comparison parity;
     * in the file-based runtime there is no live CICS region, so the
     * value is captured in log lines only (AAP &sect;0.6.12).
     */
    static final String CICS_REGION = "CICSAWSA";

    /**
     * First CICS file definition closed/opened by the SDSF CEMT steps.
     * Mapped to the {@code TRANSACT} VSAM KSDS cluster managed by this
     * job. The trailing space in the JCL spelling
     * ({@code 'CEMT SET FIL(TRANSACT ) CLO'}) is intentional in CICS CEMT
     * but is normalised here for log clarity.
     */
    static final String CICS_FILE_TRANSACT = "TRANSACT";

    /**
     * Second CICS file definition closed/opened by the SDSF CEMT steps.
     * Mapped to the AIX-driven view of the TRANSACT KSDS via the AIX
     * defined in STEP20. Note the spelling {@code CXACAIX} comes from
     * the JCL verbatim and refers to the CICS-side file resource that
     * front-ends the alternate-index path; preserved literally for
     * log-comparison fidelity.
     */
    static final String CICS_FILE_TRANSACT_AIX = "CXACAIX";

    // ---------------------------------------------------------------------
    // Return codes (IDCAMS convention)
    // ---------------------------------------------------------------------

    /** Successful return code: every step completed. */
    static final int RC_OK = 0;

    /**
     * Soft warning: the staging input file is missing. IDCAMS REPRO would
     * emit condition code {@code 4} (&quot;input dataset not found&quot;);
     * this Java translation surfaces the equivalent return code so
     * downstream batch orchestration can branch on it.
     */
    static final int RC_NO_INPUT = 4;

    /** IDCAMS &quot;severe error&quot; return code: an uncaught exception occurred. */
    static final int RC_ERROR = 16;

    // ---------------------------------------------------------------------
    // Configuration keys (12-factor per AAP §0.7.2)
    // ---------------------------------------------------------------------

    /**
     * JVM system-property / env-var key for the KSDS target path.
     * Documented in {@code java/application.properties.example} as
     * {@code carddemo.file.transact.path}.
     */
    static final String PROP_TRANSACT_PATH = "carddemo.file.transact.path";

    /**
     * Default KSDS target path used when no system property and no
     * environment variable supply a value. Matches the default in
     * {@code java/application.properties.example}.
     */
    static final String DEFAULT_TRANSACT_PATH = "./data/transact.dat";

    /**
     * JVM system-property / env-var key for the staging-source sequential
     * file path. Mainframe equivalent: {@code //TRANSACT DD DSN=...DALYTRAN.PS.INIT}.
     *
     * <p>The property key is intentionally
     * {@code carddemo.file.dailytran.path} (not a dedicated
     * {@code transact.source}) because the DALYTRAN file is conceptually
     * shared across the daily-batch job suite: the upstream daily-tran
     * load step writes it, and this TRANFILE bootstrap REPROs it into the
     * TRANSACT KSDS.
     */
    static final String PROP_DALYTRAN_PATH = "carddemo.file.dailytran.path";

    /**
     * Default staging-source path used when no system property and no
     * environment variable supply a value. Matches the default in
     * {@code java/application.properties.example} for
     * {@code carddemo.file.dailytran.path}.
     *
     * <p>For test or demo runs without a prior daily-tran load step,
     * override this property to point at the preserved fixture under
     * {@code app/}:
     * {@code -Dcarddemo.file.dailytran.path=app/data/ASCII/dailytran.txt}.
     */
    static final String DEFAULT_DALYTRAN_PATH = "./data/dailytran.dat";

    // ---------------------------------------------------------------------
    // Construction guard
    // ---------------------------------------------------------------------

    /** Utility class &mdash; not constructible. */
    private DefineTransactionFileApp() {
        throw new AssertionError("DefineTransactionFileApp is not constructible");
    }

    // ---------------------------------------------------------------------
    // Java main entry point
    // ---------------------------------------------------------------------

    /**
     * Java main entry point. Mirrors the nine-step {@code TRANFILE.jcl}
     * job: resolves a {@link BatchRunContext} from JVM system properties
     * and environment variables (12-factor configuration), binds it to
     * {@link #BATCH_CTX} via JEP 506
     * {@code ScopedValue.where(...).call(...)}, invokes {@link #execute()},
     * and translates the returned IDCAMS-style return code to a
     * {@link System#exit(int)} call.
     *
     * <p>The pattern-matching switch in this method explicitly enumerates
     * {@code 0/4/8/12/16} and uses two guarded {@code Integer i when ...}
     * cases for the negative and {@code > 16} clamps; the final
     * {@code case Integer i} (no guard) catches the remaining
     * {@code 1, 2, 3, 5, 6, 7, 9, 10, 11, 13, 14, 15} values. No
     * {@code default} branch is permitted per AAP &sect;0.7.3 &mdash; the
     * Java compiler enforces exhaustiveness over {@code Integer} via
     * these cases.
     *
     * @param args command-line arguments. Currently unused; configuration
     *             flows exclusively through JVM system properties and
     *             environment variables for 12-factor compliance
     *             (AAP &sect;0.7.2).
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        // The selector is intentionally boxed to Integer so the switch
        // below is exhaustive via standard pattern matching (Java 21
        // Final) rather than primitive patterns (JEP 507 — preview,
        // forbidden by AAP §0.7.4). ScopedValue.Carrier#call returns R
        // (here Integer via autoboxing from the int returned by
        // execute()), so this assignment is direct.
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(DefineTransactionFileApp::execute);
        } catch (Exception e) {
            LOG.error("TRANFILE job failed with uncaught exception", e);
            rc = RC_ERROR;
        }
        // Pattern-matching switch — exhaustive on Integer, NO default
        // branch per AAP §0.7.3. Coverage proof: case null + 5 known-good
        // constants (0/4/8/12/16) + two guards for negative and >16 + a
        // final unguarded type pattern for the remaining
        // 1/2/3/5/6/7/9/10/11/13/14/15 cases.
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

    // ---------------------------------------------------------------------
    // Job body — nine steps matching the JCL
    // ---------------------------------------------------------------------

    /**
     * Runs the nine-step TRANFILE job body inside the {@link #BATCH_CTX}
     * scope. Visible for testing.
     *
     * <p>Step sequence (preserving JCL step order, per AAP &sect;0.7.1):
     * <ol>
     *   <li><strong>CLCIFIL</strong>: log-only CEMT CLOSE for
     *       {@code FIL(TRANSACT)} and {@code FIL(CXACAIX)} on
     *       {@code CICSAWSA} (AAP &sect;0.6.12).</li>
     *   <li><strong>STEP05</strong>: {@code Files.deleteIfExists(target)}
     *       on the KSDS data file, the {@code .schema} sidecar, AND the
     *       {@code .aix.schema} sidecar (the IDCAMS double
     *       {@code DELETE ... CLUSTER} / {@code DELETE ... ALTERNATEINDEX}
     *       + {@code SET MAXCC = 0} idiom made idempotent).</li>
     *   <li><strong>STEP10</strong>: write the {@code .schema} sidecar
     *       with the verbatim DEFINE CLUSTER attributes (cluster name,
     *       key spec, record size, share options, ERASE, INDEXED,
     *       DATA/INDEX component names, volumes, cylinders).</li>
     *   <li><strong>STEP15</strong>: copy the staging source flat file to
     *       the KSDS target via
     *       {@link java.nio.file.Files#copy(Path, Path,
     *       java.nio.file.CopyOption...)} with
     *       {@link java.nio.file.StandardCopyOption#REPLACE_EXISTING}.</li>
     *   <li><strong>STEP20</strong>: write the {@code .aix.schema}
     *       sidecar with the verbatim DEFINE ALTERNATEINDEX attributes
     *       (AIX name, RELATE clause, KEYS(26 304), NONUNIQUEKEY, UPGRADE,
     *       RECORDSIZE, volumes, cylinders, DATA/INDEX component
     *       names).</li>
     *   <li><strong>STEP25</strong>: emit a structured-log line that
     *       captures the AIX path name and {@code PATHENTRY} target. No
     *       file is written because path-based open semantics do not
     *       have a direct analogue in the file-based runtime.</li>
     *   <li><strong>STEP30</strong>: emit a structured-log line for
     *       {@code BLDINDEX}. The actual AIX-key extraction is performed
     *       lazily at read time by the file-based adapter
     *       (AAP &sect;0.6.5), so this step is a no-op that preserves
     *       the JCL step ordering for parity.</li>
     *   <li><strong>OPCIFIL</strong>: log-only CEMT OPEN for
     *       {@code FIL(TRANSACT)} and {@code FIL(CXACAIX)} on
     *       {@code CICSAWSA} (AAP &sect;0.6.12).</li>
     * </ol>
     *
     * @return {@link #RC_OK} on success, or {@link #RC_NO_INPUT} when the
     *         staging source file is missing
     * @throws IOException if any filesystem operation (delete, write,
     *                     copy) fails for a reason other than the
     *                     missing-input soft condition
     */
    static int execute() throws IOException {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("TRANFILE job starting; runId={}, processingDate={}, tenant={}, cluster={}, aix={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant(), CLUSTER_NAME, AIX_NAME);

        // Resolve target and source paths from configuration. The KSDS
        // target lives at the configured path; the staging source is the
        // upstream DALYTRAN.PS.INIT file (defaults to ./data/dailytran.dat;
        // override to app/data/ASCII/dailytran.txt for test/demo runs).
        // Both paths are normalised to absolute form so log lines and
        // error messages reference unambiguous locations regardless of
        // the JVM cwd.
        Path target = SafePathResolver.resolveTrusted(PROP_TRANSACT_PATH, DEFAULT_TRANSACT_PATH).toAbsolutePath();
        Path source = SafePathResolver.resolveTrusted(PROP_DALYTRAN_PATH, DEFAULT_DALYTRAN_PATH).toAbsolutePath();

        Path parentDir = target.getParent() != null
                ? target.getParent()
                : Path.of(".").toAbsolutePath();
        Path schema = parentDir.resolve(target.getFileName() + ".schema");
        Path aixSchema = parentDir.resolve(target.getFileName() + ".aix.schema");

        // Ensure the parent directory exists before any file I/O. This
        // is the Java analog of the z/OS catalog allocation that DEFINE
        // CLUSTER performs implicitly on the mainframe.
        Files.createDirectories(parentDir);

        // ========================================================
        // CLCIFIL — SDSF CEMT SET FIL(TRANSACT) CLO + FIL(CXACAIX) CLO
        // ========================================================
        // Log-only translation per AAP §0.6.12: the file-based runtime
        // has no live CICS region; capturing the close intent in the log
        // preserves trace-comparison parity with the COBOL baseline.
        LOG.info("CLCIFIL: CEMT close (log-only per AAP §0.6.12) region={} files={},{}",
                CICS_REGION, CICS_FILE_TRANSACT, CICS_FILE_TRANSACT_AIX);

        // ========================================================
        // STEP05 — IDCAMS DELETE CLUSTER + DELETE ALTERNATEINDEX
        //          + IF MAXCC LE 08 THEN SET MAXCC = 0 (idempotent)
        // ========================================================
        // Files.deleteIfExists returns false when the target is absent,
        // matching the SET MAXCC = 0 swallow-not-found behaviour. No
        // throw, no warn — just info-level "either path is fine".
        boolean ksdsDeleted = Files.deleteIfExists(target);
        boolean schemaDeleted = Files.deleteIfExists(schema);
        boolean aixSchemaDeleted = Files.deleteIfExists(aixSchema);
        if (ksdsDeleted) {
            LOG.info("STEP05: deleted prior KSDS file {} (mainframe cluster {})",
                    target, CLUSTER_NAME);
        } else {
            LOG.info("STEP05: no prior KSDS file at {} (idempotent; mainframe cluster {})",
                    target, CLUSTER_NAME);
        }
        if (schemaDeleted) {
            LOG.info("STEP05: deleted prior schema sidecar {}", schema);
        }
        if (aixSchemaDeleted) {
            LOG.info("STEP05: deleted prior AIX schema sidecar {} (mainframe AIX {})",
                    aixSchema, AIX_NAME);
        } else {
            LOG.info("STEP05: no prior AIX schema sidecar at {} (idempotent; mainframe AIX {})",
                    aixSchema, AIX_NAME);
        }

        // ========================================================
        // STEP10 — IDCAMS DEFINE CLUSTER (verbatim attributes)
        // ========================================================
        // The schema sidecar records the IDCAMS DEFINE CLUSTER attributes
        // verbatim so that any downstream reader (and any future schema
        // validator) can confirm the layout matches the COBOL contract.
        // Writing the sidecar BEFORE the REPRO copy guarantees that a
        // reader observing the data file always finds the sibling schema.
        Files.writeString(schema,
                "cluster=" + CLUSTER_NAME + System.lineSeparator()
                        + "keyLength=" + KEY_LENGTH + System.lineSeparator()
                        + "keyOffset=" + KEY_OFFSET + System.lineSeparator()
                        + "recordLength=" + RECORD_LENGTH + System.lineSeparator()
                        + "shareOptions=" + SHARE_OPTIONS + System.lineSeparator()
                        + "cylinders=" + CYLINDERS + System.lineSeparator()
                        + "volumes=" + VOLUMES + System.lineSeparator()
                        + "erase=" + ERASE + System.lineSeparator()
                        + "indexed=" + INDEXED + System.lineSeparator()
                        + "dataComponent=" + CLUSTER_NAME + ".DATA" + System.lineSeparator()
                        + "indexComponent=" + CLUSTER_NAME + ".INDEX" + System.lineSeparator()
                        + "stagingSource=" + PS_NAME + System.lineSeparator());
        LOG.info("STEP10: DEFINE OK cluster={}, key={},{} record={} SHAREOPTIONS({}) "
                        + "CYLINDERS({}) VOLUMES({}) ERASE={} INDEXED={}",
                CLUSTER_NAME, KEY_LENGTH, KEY_OFFSET, RECORD_LENGTH, SHARE_OPTIONS,
                CYLINDERS, VOLUMES, ERASE, INDEXED);

        // ========================================================
        // STEP15 — IDCAMS REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)
        // ========================================================
        // Copy the staging flat file byte-for-byte into the KSDS target.
        // The COBOL contract is that REPRO performs no record re-ordering
        // (the input is already in transaction-id-ascending order because
        // the upstream generator authored it that way), so a straight
        // Files.copy preserves the on-disk layout exactly.
        // StandardCopyOption.REPLACE_EXISTING guards against a residual
        // file that somehow survived the STEP05 delete (e.g., an external
        // symlink or a race with another process).
        if (!Files.exists(source)) {
            LOG.warn("STEP15: staging source not found at {} (mainframe dataset {}); rc={}",
                    source, PS_NAME, RC_NO_INPUT);
            return RC_NO_INPUT;
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        long copiedBytes = Files.size(target);
        long copiedRecords = copiedBytes / RECORD_LENGTH;
        LOG.info("STEP15: REPRO OK source={} target={} bytes={} records~={}",
                source, target, copiedBytes, copiedRecords);

        // ========================================================
        // STEP20 — IDCAMS DEFINE ALTERNATEINDEX (verbatim attributes)
        // ========================================================
        // The AIX schema sidecar records the IDCAMS DEFINE ALTERNATEINDEX
        // attributes verbatim. Downstream readers that want to navigate
        // the AIX (AIX-key-window → all matching transactions) can
        // validate the key spec against their derivation logic. The AIX
        // entries themselves are NOT materialised here — they are
        // computed at read time per AAP §0.6.5 (file-based adapter
        // reading fixed-width records via java.nio.file).
        Files.writeString(aixSchema,
                "aix=" + AIX_NAME + System.lineSeparator()
                        + "relate=" + CLUSTER_NAME + System.lineSeparator()
                        + "aixKeyLength=" + AIX_KEY_LENGTH + System.lineSeparator()
                        + "aixKeyOffset=" + AIX_KEY_OFFSET + System.lineSeparator()
                        + "recordLength=" + RECORD_LENGTH + System.lineSeparator()
                        + "nonUniqueKey=" + AIX_NONUNIQUE_KEY + System.lineSeparator()
                        + "upgrade=" + AIX_UPGRADE + System.lineSeparator()
                        + "volumes=" + VOLUMES + System.lineSeparator()
                        + "cylinders=" + AIX_CYLINDERS + System.lineSeparator()
                        + "dataComponent=" + AIX_NAME + ".DATA" + System.lineSeparator()
                        + "indexComponent=" + AIX_NAME + ".INDEX" + System.lineSeparator());
        LOG.info("STEP20: DEFINE ALTERNATEINDEX OK aix={} relate={} aixKey={},{} record={} "
                        + "NONUNIQUEKEY={} UPGRADE={} VOLUMES({}) CYLINDERS({})",
                AIX_NAME, CLUSTER_NAME, AIX_KEY_LENGTH, AIX_KEY_OFFSET, RECORD_LENGTH,
                AIX_NONUNIQUE_KEY, AIX_UPGRADE, VOLUMES, AIX_CYLINDERS);

        // ========================================================
        // STEP25 — IDCAMS DEFINE PATH
        // ========================================================
        // Log-only: the AIX path is the named handle through which CICS
        // / batch programs would open the AIX-driven view of the base
        // cluster on the mainframe. In the file-based runtime there is
        // no equivalent open semantic; readers that want AIX-keyed access
        // compute it directly from the loaded data file. We capture the
        // path metadata in the log so a future reviewer can confirm the
        // JCL step ran.
        LOG.info("STEP25: DEFINE PATH OK name={} pathEntry={}",
                AIX_PATH_NAME, AIX_NAME);

        // ========================================================
        // STEP30 — IDCAMS BLDINDEX (no-op; AIX computed at read time)
        // ========================================================
        // BLDINDEX on the mainframe would scan the loaded KSDS, extract
        // the AIX key (offset 304 length 26) from each record, sort by
        // AIX key, and write the AIX entries. In the file-based runtime
        // the AIX entries are computed lazily by the read-side adapter
        // (AAP §0.6.5), so this step is a no-op that preserves the JCL
        // step ordering. The log line below documents the no-op for
        // trace-comparison parity.
        LOG.info("STEP30: BLDINDEX OK (no-op; AIX computed at read time per AAP §0.6.5) "
                        + "inDataset={} outDataset={}",
                CLUSTER_NAME, AIX_NAME);

        // ========================================================
        // OPCIFIL — SDSF CEMT SET FIL(TRANSACT) OPE + FIL(CXACAIX) OPE
        // ========================================================
        // Log-only translation per AAP §0.6.12: re-opening the CICS file
        // definitions is a CICS-region operation with no analogue in the
        // file-based runtime; the open intent is captured in the log so
        // the JCL step ordering is preserved for trace-comparison parity.
        LOG.info("OPCIFIL: CEMT open (log-only per AAP §0.6.12) region={} files={},{}",
                CICS_REGION, CICS_FILE_TRANSACT, CICS_FILE_TRANSACT_AIX);

        LOG.info("TRANFILE job complete; rc={}", RC_OK);
        return RC_OK;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Resolves a configuration property by consulting (in precedence
     * order) the JVM environment variable derived from {@code key}, then
     * the JVM system property {@code key}, then the provided
     * {@code defaultValue}.
     *
     * <p>The environment-variable key is derived from {@code key} by
     * upper-casing (with {@link Locale#ROOT}) and replacing every
     * {@code '.'} and {@code '-'} with {@code '_'} &mdash; i.e., the
     * system property {@code "carddemo.file.transact.path"} maps to the
     * environment variable {@code "CARDDEMO_FILE_TRANSACT_PATH"}. This
     * matches the convention used by the sibling
     * {@link DefineCardXrefApp} and {@link DefineTcatBalApp} classes.
     *
     * <p>Visible for testing.
     *
     * @param key          the system-property key; must not be {@code null}
     * @param defaultValue the value to return when neither the environment
     *                     variable nor the system property is set; may be
     *                     {@code null}
     * @return the resolved value
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
