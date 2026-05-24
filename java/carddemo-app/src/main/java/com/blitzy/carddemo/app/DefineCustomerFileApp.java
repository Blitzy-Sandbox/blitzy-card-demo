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
//                Exception, AssertionError,
//                Math}
// Per AAP §0.4.2 / §0.7.3 this single module-import declaration is mandated
// where many java.* packages are touched.
import module java.base;

import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of <strong>two</strong> source JCL
 * files combined into one Java program per AAP &sect;0.4.1:
 * <ol>
 *   <li>{@code app/jcl/CUSTFILE.jcl} &mdash; the five-step
 *       IDCAMS&plus;SDSF customer-file bootstrap job
 *       (CEMT close &rarr; DELETE &rarr; DEFINE &rarr; REPRO &rarr; CEMT open)
 *       that re-creates and loads the production CUSTDATA VSAM KSDS cluster
 *       {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}, {@code KEYS(9 0)},
 *       {@code RECORDSIZE(500 500)}, {@code SHAREOPTIONS(2 3)}.</li>
 *   <li>{@code app/jcl/DEFCUST.jcl} &mdash; the alternate two-step
 *       IDCAMS-only customer-cluster definition job that contains a
 *       <strong>known dataset-name mismatch</strong>: it deletes
 *       {@code AWS.CCDA.CUSTDATA.CLUSTER} but defines
 *       {@code AWS.CUSTDATA.CLUSTER} (note the different prefix),
 *       {@code KEYS(10 0)} (one byte longer than CUSTFILE.jcl's
 *       {@code KEYS(9 0)}), {@code SHAREOPTIONS(1 4)} (different than
 *       CUSTFILE.jcl's {@code SHAREOPTIONS(2 3)}).</li>
 * </ol>
 *
 * <p>Per AAP &sect;0.4.1, this composition root is packaged as the shaded jar
 * {@code carddemo-define-customer-file.jar}.
 *
 * <h2>CRITICAL &mdash; KNOWN BUG PRESERVED FAITHFULLY</h2>
 * <p>The {@code DEFCUST.jcl} dataset-name mismatch
 * ({@code AWS.CCDA.CUSTDATA.CLUSTER} vs {@code AWS.CUSTDATA.CLUSTER}) and the
 * key-length discrepancy between CUSTFILE.jcl ({@code KEYS(9 0)}) and
 * DEFCUST.jcl ({@code KEYS(10 0)}) are <strong>preserved verbatim</strong> per
 * AAP &sect;0.7.1: <em>"do not enhance or optimize business logic beyond the
 * requirements of the migration. If a COBOL paragraph contains dead code or
 * obvious bugs, translate it faithfully and flag it in a
 * MIGRATION_NOTES.md."</em> The Java code performs the same dataset-name
 * mismatch and key/SHAREOPTIONS discrepancy at runtime and emits explicit
 * {@code WARN}-level log lines pointing the operator to
 * {@code java/MIGRATION_NOTES.md}. The author of any future "fix" must
 * coordinate with the migration backlog &mdash; do not silently repair the
 * mismatch in this refactor.
 *
 * <h2>Source artefacts (verbatim contents)</h2>
 *
 * <h3>CUSTFILE.jcl (5 steps)</h3>
 * <ol>
 *   <li><strong>CLCIFIL</strong> &mdash; {@code EXEC PGM=SDSF} running
 *       {@code /F CICSAWSA,'CEMT SET FIL(CUSTDAT ) CLO'} (close the CICS
 *       file). Translated to an informational log line (no CICS region to
 *       command from a Java JVM).</li>
 *   <li><strong>STEP05</strong> &mdash; {@code EXEC PGM=IDCAMS} running
 *       {@code DELETE AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS CLUSTER} followed by
 *       {@code IF MAXCC LE 08 THEN SET MAXCC = 0}; the conditional MAXCC
 *       reset makes the delete idempotent: a missing cluster is not an
 *       error.</li>
 *   <li><strong>STEP10</strong> &mdash; {@code EXEC PGM=IDCAMS} running
 *       {@code DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS)
 *       CYLINDERS(1 5) VOLUMES(AWSHJ1) KEYS(9 0) RECORDSIZE(500 500)
 *       SHAREOPTIONS(2 3) ERASE INDEXED)} with sibling {@code DATA} and
 *       {@code INDEX} components named
 *       {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS.DATA} and
 *       {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS.INDEX}.</li>
 *   <li><strong>STEP15</strong> &mdash; {@code EXEC PGM=IDCAMS} running
 *       {@code REPRO INFILE(CUSTDATA) OUTFILE(CUSTVSAM)} copying the flat
 *       sequential file {@code AWS.M2.CARDDEMO.CUSTDATA.PS} into the freshly
 *       defined KSDS cluster.</li>
 *   <li><strong>OPCIFIL</strong> &mdash; {@code EXEC PGM=SDSF} running
 *       {@code /F CICSAWSA,'CEMT SET FIL(CUSTDAT ) OPE'} (re-open the CICS
 *       file). Translated to an informational log line.</li>
 * </ol>
 *
 * <h3>DEFCUST.jcl (2 steps &mdash; WITH BUG)</h3>
 * <ol>
 *   <li><strong>STEP05 (1st)</strong> &mdash; {@code EXEC PGM=IDCAMS} running
 *       {@code DELETE AWS.CCDA.CUSTDATA.CLUSTER CLUSTER}. NOTE the
 *       {@code CCDA} prefix (which appears nowhere else in the COBOL source
 *       tree); this is the buggy DELETE name. There is no {@code SET MAXCC =
 *       0} so a missing cluster surfaces as MAXCC=8 on the mainframe; the
 *       Java translation matches by using
 *       {@link java.nio.file.Files#deleteIfExists(Path)} which returns
 *       {@code false} when the target is absent (no exception, no error
 *       return), preserving the COBOL job's downstream "DEFINE proceeds even
 *       if DELETE found nothing" behaviour.</li>
 *   <li><strong>STEP05 (2nd)</strong> &mdash; {@code EXEC PGM=IDCAMS} running
 *       {@code DEFINE CLUSTER (NAME(AWS.CUSTDATA.CLUSTER) CYLINDERS(1 5)
 *       KEYS(10 0) RECORDSIZE(500 500) SHAREOPTIONS(1 4) ERASE INDEXED)}
 *       with sibling {@code DATA} and {@code INDEX} components named
 *       {@code AWS.CUSTDATA.CLUSTER.DATA} and
 *       {@code AWS.CUSTDATA.CLUSTER.INDEX}. NOTE the cluster name does
 *       <strong>not match</strong> the DELETE name above. Both DEFCUST steps
 *       are labelled {@code STEP05} in the source JCL (likely a copy-paste
 *       oversight); the Java translation logs them as {@code STEP05a} and
 *       {@code STEP05b} to disambiguate.</li>
 * </ol>
 *
 * <h2>Record layout (per {@code app/cpy/CUSTREC.cpy} and
 * {@code app/cpy/CVCUS01Y.cpy})</h2>
 * <p>The flat customer file at {@code app/data/ASCII/custdata.txt} contains
 * fixed-length 500-byte records consumed in key-ascending order. The CUSTFILE
 * key length (9) matches the first nine bytes of the customer-ID field; the
 * DEFCUST key length (10) extends the key by one byte into the trailing first
 * name &mdash; the precise behavioural artefact this refactor preserves
 * faithfully per AAP &sect;0.7.1.
 *
 * <h2>Translation strategy &mdash; idiom-for-idiom (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>{@code EXEC PGM=SDSF / CEMT SET FIL(CUSTDAT) CLO/OPE} becomes a
 *       structured log message at INFO level (the CICS region is not in
 *       scope per AAP &sect;0.2.2 &mdash; "replacement orchestration is not
 *       part of this refactor").</li>
 *   <li>IDCAMS {@code DELETE ... CLUSTER} (with or without the
 *       {@code SET MAXCC = 0} conditional) becomes an idempotent
 *       {@link java.nio.file.Files#deleteIfExists(Path)} call: a missing
 *       target file simply returns {@code false} without throwing,
 *       reproducing the mainframe "swallow the not-found condition"
 *       behaviour.</li>
 *   <li>IDCAMS {@code DEFINE CLUSTER ...} becomes a text {@code .schema}
 *       sidecar file recording every DEFINE attribute verbatim, plus an
 *       empty data file (the equivalent of a freshly defined empty
 *       cluster). The two DEFCUST sidecars additionally record the buggy
 *       DELETE name with the
 *       {@code "(MISMATCH PRESERVED PER AAP §0.7.1)"} annotation so the
 *       discrepancy is visible to anyone inspecting the file system.</li>
 *   <li>IDCAMS {@code REPRO INFILE(CUSTDATA) OUTFILE(CUSTVSAM)} becomes a
 *       {@link java.nio.file.Files#copy(Path, Path,
 *       java.nio.file.CopyOption...)} call from the staging flat file to the
 *       KSDS target with {@link java.nio.file.StandardCopyOption#REPLACE_EXISTING}.
 *       Records are loaded in their existing file order, matching IDCAMS REPRO's
 *       byte-for-byte copy behaviour (the input is already in key-ascending
 *       order, so REPRO performs no re-ordering on the mainframe either).</li>
 * </ul>
 *
 * <h2>Process-exit semantics</h2>
 * <p>The job runs the CUSTFILE.jcl steps first and then the DEFCUST.jcl
 * steps; the final exit code is the maximum across both groups, matching the
 * COND-code behaviour of a single JCL that chained both jobs. The Java
 * translation reports IDCAMS-style return codes:
 * <ul>
 *   <li>{@link #RC_OK} (0) &mdash; every step succeeded.</li>
 *   <li>{@link #RC_NO_INPUT} (4) &mdash; the CUSTFILE staging source file is
 *       missing. IDCAMS REPRO would emit condition code 4 ("input dataset not
 *       found"); this Java translation surfaces the equivalent return code so
 *       downstream batch orchestration can branch on it. DEFCUST has no REPRO
 *       step so {@link #RC_NO_INPUT} from {@link #runDefcustSteps()} is not
 *       expected.</li>
 *   <li>{@link #RC_ERROR} (16) &mdash; any uncaught exception (IDCAMS "severe
 *       error" convention). Any value &lt; 0 or &gt; 16 is also clamped to 16,
 *       matching the behaviour of {@code COND} expression evaluation in z/OS
 *       JCL.</li>
 * </ul>
 *
 * <h2>BatchRunContext / ScopedValue (AAP &sect;0.6.6, JEP 506 Final)</h2>
 * <p>{@link #main(String[])} resolves a {@link BatchRunContext} via
 * {@link BatchRunContext#fromEnvironment()} (12-factor configuration via JVM
 * system properties and environment variables) and binds it to
 * {@link #BATCH_CTX} for the duration of {@link #execute()}. Inside
 * {@code execute()}, {@code runId}, {@code processingDate}, and {@code tenant}
 * are read from {@code BATCH_CTX.get()} for the structured-logging startup
 * line so that operations teams can correlate log lines with a specific batch
 * run identifier. <strong>No {@link ThreadLocal} is used anywhere</strong>,
 * in keeping with AAP &sect;0.7.4.
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
 *   <li>No actual VSAM / IDCAMS interaction &mdash; the file-based runtime
 *       writes a fixed-width file plus a sidecar schema descriptor per
 *       AAP &sect;0.6.12.</li>
 *   <li>No "fix" for the DEFCUST.jcl dataset-name mismatch &mdash; preserved
 *       per AAP &sect;0.7.1 and flagged in {@code java/MIGRATION_NOTES.md}.</li>
 * </ul>
 *
 * @see app/jcl/CUSTFILE.jcl  the production five-step customer-file bootstrap
 * @see app/jcl/DEFCUST.jcl   the alternate two-step define WITH preserved bug
 * @see app/cpy/CVCUS01Y.cpy  the CUSTOMER-RECORD 500-byte layout
 * @see DefineDiscountGroupApp sibling DEFINE/REPRO job sharing the same pattern
 * @see BatchRunContext       the immutable context bound to {@link #BATCH_CTX}
 * @since 1.0.0
 */
@CobolProgram(
        value = "IDCAMS+SDSF (CUSTFILE.jcl + DEFCUST.jcl)",
        sourcePath = "app/jcl/CUSTFILE.jcl,app/jcl/DEFCUST.jcl",
        translationDate = "2025-10-24",
        notes = "Dual-source JCL translation. CUSTFILE.jcl is the production "
                + "5-step bootstrap (CEMT close + IDCAMS DELETE + IDCAMS DEFINE "
                + "CLUSTER KEYS(9 0) RECORDSIZE(500 500) SHAREOPTIONS(2 3) "
                + "VOLUMES(AWSHJ1) CYLINDERS(1 5) ERASE INDEXED + IDCAMS REPRO "
                + "from AWS.M2.CARDDEMO.CUSTDATA.PS + CEMT open). DEFCUST.jcl is "
                + "the alternate 2-step define that contains a SUSPECTED BUG: "
                + "the DELETE targets AWS.CCDA.CUSTDATA.CLUSTER but the DEFINE "
                + "targets AWS.CUSTDATA.CLUSTER (different prefixes), and uses "
                + "KEYS(10 0) SHAREOPTIONS(1 4) (different than CUSTFILE.jcl). "
                + "Both discrepancies are preserved faithfully per AAP §0.7.1 "
                + "and flagged with explicit WARN log lines pointing operators "
                + "to java/MIGRATION_NOTES.md. In the file-based runtime each "
                + "KSDS becomes a fixed-width data file plus a sibling .schema "
                + "descriptor; the staging PS for CUSTFILE is sourced from "
                + "app/data/ASCII/custdata.txt (preserved per AAP §0.2.2)."
)
public final class DefineCustomerFileApp {

    // ---------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------

    /**
     * SLF4J logger. Backed at runtime by logback-classic supplied via the
     * {@code carddemo-app} shaded jar packaging (AAP &sect;0.5.1); this class
     * deliberately holds no reference to a concrete logging implementation.
     * No PAN, no password, no card data is ever logged from this app &mdash;
     * the only payload logged is metadata (counts, byte sizes, cluster names,
     * and the preserved DEFCUST.jcl bug indicator).
     */
    private static final Logger LOG = LoggerFactory.getLogger(DefineCustomerFileApp.class);

    // ---------------------------------------------------------------------
    // ScopedValue (JEP 506 Final) — propagates BatchRunContext into execute()
    // ---------------------------------------------------------------------

    /**
     * Local {@link java.lang.ScopedValue} for the {@link BatchRunContext} that
     * orchestrates this job. Bound by {@link #main(String[])} via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(DefineCustomerFileApp::execute)}
     * and read inside {@link #execute()} via {@code BATCH_CTX.get()} to emit
     * the {@code runId}, {@code processingDate}, and {@code tenant} on the
     * CUSTFILE+DEFCUST startup log line.
     *
     * <p>This binding <strong>replaces {@link ThreadLocal} entirely</strong>
     * per AAP &sect;0.6.6 and &sect;0.7.4. The {@code ScopedValue} class moved
     * from {@code java.util.concurrent} (preview) to {@code java.lang} (final)
     * when JEP 506 was finalised in Java 25, so it is available here via the
     * JEP 511 {@code import module java.base} declaration above.
     *
     * <p>Per the export schema for this file, this field is part of the
     * {@code DefineCustomerFileApp} public surface &mdash; co-located with
     * {@link BatchRunContext#BATCH_CTX} but scoped to this app to keep the
     * binding window narrow (one {@code ScopedValue} per composition root,
     * matching the sibling {@link DefineDiscountGroupApp} and
     * {@link DefineTcatBalApp} convention).
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // ---------------------------------------------------------------------
    // CUSTFILE.jcl KSDS cluster metadata — verbatim from app/jcl/CUSTFILE.jcl
    // ---------------------------------------------------------------------

    /**
     * VSAM KSDS cluster name preserved verbatim from {@code CUSTFILE.jcl}
     * STEP10 ({@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}). This is the
     * <strong>production</strong> customer-data cluster consumed by every
     * downstream batch and online program that reads customer records.
     */
    static final String CUSTFILE_CLUSTER_NAME = "AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS";

    /** Sequential staging file name preserved verbatim from {@code CUSTFILE.jcl} STEP15. */
    static final String CUSTFILE_PS_NAME = "AWS.M2.CARDDEMO.CUSTDATA.PS";

    /**
     * CICS file identifier preserved verbatim from the CEMT commands in
     * {@code CUSTFILE.jcl} CLCIFIL and OPCIFIL ({@code CUSTDAT}). Used only
     * in informational log lines.
     */
    static final String CUSTFILE_CICS_FILE_ID = "CUSTDAT";

    /** {@code KEYS(9 0)} &mdash; key length in bytes (9). */
    static final int CUSTFILE_KEY_LENGTH = 9;

    /** {@code KEYS(9 0)} &mdash; key offset from record start (0). */
    static final int CUSTFILE_KEY_OFFSET = 0;

    /** {@code RECORDSIZE(500 500)} &mdash; fixed record length in bytes (500). */
    static final int CUSTFILE_RECORD_LENGTH = 500;

    /** {@code SHAREOPTIONS(2 3)} &mdash; cross-region/cross-system share options. */
    static final String CUSTFILE_SHARE_OPTIONS = "2 3";

    /** {@code CYLINDERS(1 5)} &mdash; primary 1 cyl, secondary 5 cyl space allocation. */
    static final String CUSTFILE_CYLINDERS = "1 5";

    /** {@code VOLUMES(AWSHJ1)} &mdash; AWS Mainframe Modernization volume name. */
    static final String CUSTFILE_VOLUMES = "AWSHJ1";

    /** {@code ERASE} flag indicating cleared-on-delete is requested for this cluster. */
    static final boolean CUSTFILE_ERASE = true;

    /** {@code INDEXED} flag indicating this is a KSDS (key-sequenced) cluster. */
    static final boolean CUSTFILE_INDEXED = true;

    // ---------------------------------------------------------------------
    // DEFCUST.jcl KSDS cluster metadata — INTENTIONALLY MISMATCHED
    // (preserved bug per AAP §0.7.1; do NOT "fix" — flag in MIGRATION_NOTES.md)
    // ---------------------------------------------------------------------

    /**
     * <strong>BUGGY DELETE NAME</strong> &mdash; the dataset name that
     * {@code DEFCUST.jcl} STEP05 (1st) targets for {@code DELETE ... CLUSTER}:
     * {@code AWS.CCDA.CUSTDATA.CLUSTER}. NOTE the {@code CCDA} prefix appears
     * <strong>nowhere else</strong> in the COBOL source tree; this is almost
     * certainly a copy-paste error from an earlier draft of the dataset
     * naming convention. Per AAP &sect;0.7.1 ("translate faithfully; do not
     * fix"), the Java translation preserves this name verbatim and
     * emits a {@code WARN} log line at runtime pointing operators to
     * {@code java/MIGRATION_NOTES.md} for the rationale.
     */
    static final String DEFCUST_DELETE_NAME = "AWS.CCDA.CUSTDATA.CLUSTER";

    /**
     * <strong>BUGGY DEFINE NAME</strong> &mdash; the dataset name that
     * {@code DEFCUST.jcl} STEP05 (2nd) targets for {@code DEFINE CLUSTER}:
     * {@code AWS.CUSTDATA.CLUSTER}. Differs from both
     * {@link #DEFCUST_DELETE_NAME} (which has the spurious {@code CCDA}
     * prefix) and {@link #CUSTFILE_CLUSTER_NAME} (which has the standard
     * {@code M2.CARDDEMO} prefix). Three different cluster names across two
     * JCL files describing "the customer file" is the artefact this refactor
     * preserves verbatim per AAP &sect;0.7.1.
     */
    static final String DEFCUST_DEFINE_NAME = "AWS.CUSTDATA.CLUSTER";

    /**
     * {@code KEYS(10 0)} &mdash; key length in bytes (10).
     * <strong>One byte longer</strong> than {@link #CUSTFILE_KEY_LENGTH} (9);
     * preserved verbatim per AAP &sect;0.7.1.
     */
    static final int DEFCUST_KEY_LENGTH = 10;

    /** {@code KEYS(10 0)} &mdash; key offset from record start (0). */
    static final int DEFCUST_KEY_OFFSET = 0;

    /**
     * {@code RECORDSIZE(500 500)} &mdash; fixed record length in bytes (500).
     * Matches {@link #CUSTFILE_RECORD_LENGTH} (the only shared dimension
     * between the two JCL files).
     */
    static final int DEFCUST_RECORD_LENGTH = 500;

    /**
     * {@code SHAREOPTIONS(1 4)} &mdash; cross-region/cross-system share
     * options. <strong>Different from {@link #CUSTFILE_SHARE_OPTIONS}</strong>
     * ({@code "2 3"}); preserved verbatim per AAP &sect;0.7.1.
     */
    static final String DEFCUST_SHARE_OPTIONS = "1 4";

    /** {@code CYLINDERS(1 5)} &mdash; primary 1 cyl, secondary 5 cyl space allocation. */
    static final String DEFCUST_CYLINDERS = "1 5";

    /** {@code ERASE} flag preserved from DEFCUST.jcl STEP05 (2nd). */
    static final boolean DEFCUST_ERASE = true;

    /** {@code INDEXED} flag preserved from DEFCUST.jcl STEP05 (2nd). */
    static final boolean DEFCUST_INDEXED = true;

    // ---------------------------------------------------------------------
    // Return codes (IDCAMS convention)
    // ---------------------------------------------------------------------

    /** Successful return code: every step completed. */
    static final int RC_OK = 0;

    /**
     * Soft warning: the CUSTFILE staging input file is missing. IDCAMS REPRO
     * would emit condition code {@code 4} ("input dataset not found"); this
     * Java translation surfaces the equivalent return code so downstream
     * batch orchestration can branch on it. DEFCUST.jcl has no REPRO step,
     * so this code is only ever produced by {@link #runCustfileSteps()}.
     */
    static final int RC_NO_INPUT = 4;

    /** IDCAMS "severe error" return code: an uncaught exception occurred. */
    static final int RC_ERROR = 16;

    // ---------------------------------------------------------------------
    // Configuration keys (12-factor per AAP §0.7.2)
    // ---------------------------------------------------------------------

    /**
     * JVM system-property / env-var key for the CUSTFILE KSDS target path.
     * Documented in {@code java/application.properties.example} as
     * {@code carddemo.file.custdata.path}.
     */
    static final String PROP_CUSTDATA_PATH = "carddemo.file.custdata.path";

    /**
     * Default CUSTFILE KSDS target path used when no system property and no
     * environment variable supply a value. Matches the default in
     * {@code java/application.properties.example}.
     */
    static final String DEFAULT_CUSTDATA_PATH = "./data/custdata.dat";

    /**
     * JVM system-property / env-var key for the CUSTFILE staging-source flat
     * file path. Default is the preserved ASCII fixture under {@code app/}
     * (AAP &sect;0.2.2 mandates {@code app/} is immutable; this app only
     * reads from it).
     */
    static final String PROP_CUSTDATA_SOURCE = "carddemo.file.custdata.source";

    /**
     * Default CUSTFILE staging-source path: the fixed-width 500-byte ASCII
     * fixture in the preserved COBOL source tree.
     */
    static final String DEFAULT_CUSTDATA_SOURCE = "app/data/ASCII/custdata.txt";

    /**
     * JVM system-property / env-var key for the DEFCUST DELETE target path
     * &mdash; the file-side analog of the (buggy)
     * {@link #DEFCUST_DELETE_NAME} cluster. Default placeholder file is
     * {@code ./data/defcust-CCDA-custdata-cluster.dat} (the {@code CCDA}
     * fragment in the file name encodes the buggy mainframe prefix so log
     * lines and {@code ls} output make the discrepancy obvious).
     */
    static final String PROP_DEFCUST_DELETE_PATH = "carddemo.file.defcust.delete.path";

    /** Default file path representing the DEFCUST DELETE-target (buggy) cluster. */
    static final String DEFAULT_DEFCUST_DELETE_PATH = "./data/defcust-CCDA-custdata-cluster.dat";

    /**
     * JVM system-property / env-var key for the DEFCUST DEFINE target path
     * &mdash; the file-side analog of the (still-different)
     * {@link #DEFCUST_DEFINE_NAME} cluster. Default placeholder file is
     * {@code ./data/defcust-AWS-custdata-cluster.dat}.
     */
    static final String PROP_DEFCUST_DEFINE_PATH = "carddemo.file.defcust.define.path";

    /** Default file path representing the DEFCUST DEFINE-target cluster. */
    static final String DEFAULT_DEFCUST_DEFINE_PATH = "./data/defcust-AWS-custdata-cluster.dat";

    // ---------------------------------------------------------------------
    // Construction guard
    // ---------------------------------------------------------------------

    /** Utility class &mdash; not constructible. */
    private DefineCustomerFileApp() {
        throw new AssertionError("DefineCustomerFileApp is not constructible");
    }

    // ---------------------------------------------------------------------
    // Java main entry point
    // ---------------------------------------------------------------------

    /**
     * Java main entry point. Mirrors the chained CUSTFILE.jcl + DEFCUST.jcl
     * job pipeline: resolves a {@link BatchRunContext} from JVM system
     * properties and environment variables (12-factor configuration), binds
     * it to {@link #BATCH_CTX} via JEP 506
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
     * Java compiler enforces exhaustiveness over {@code Integer} via these
     * cases.
     *
     * @param args command-line arguments. Currently unused; configuration
     *             flows exclusively through JVM system properties and
     *             environment variables for 12-factor compliance
     *             (AAP &sect;0.7.2).
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        // The selector is intentionally boxed to Integer so the switch below
        // is exhaustive via standard pattern matching (Java 21 Final) rather
        // than primitive patterns (JEP 507 — preview, forbidden by AAP §0.7.4).
        // ScopedValue.Carrier#call returns R (here Integer via autoboxing
        // from the int returned by execute()), so this assignment is direct.
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(DefineCustomerFileApp::execute);
        } catch (Exception e) {
            LOG.error("CUSTFILE+DEFCUST job failed with uncaught exception", e);
            rc = RC_ERROR;
        }
        // Pattern-matching switch — exhaustive on Integer, NO default branch
        // per AAP §0.7.3. Coverage proof: case null + 5 known-good constants
        // (0/4/8/12/16) + two guards for negative and >16 + a final unguarded
        // type pattern for the remaining 1/2/3/5/6/7/9/10/11/13/14/15 cases.
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
    // Job body — orchestrates CUSTFILE.jcl then DEFCUST.jcl
    // ---------------------------------------------------------------------

    /**
     * Runs the chained CUSTFILE.jcl + DEFCUST.jcl pipeline inside the
     * {@link #BATCH_CTX} scope. Visible for testing.
     *
     * <p>Execution order (preserving the originating-JCL semantics, per AAP
     * &sect;0.7.1):
     * <ol>
     *   <li>{@link #runCustfileSteps()} &mdash; runs all 5 CUSTFILE.jcl
     *       steps (CEMT close, DELETE, DEFINE, REPRO, CEMT open). Returns
     *       {@link #RC_OK} on success or {@link #RC_NO_INPUT} when the
     *       staging input is missing.</li>
     *   <li>{@link #runDefcustSteps()} &mdash; runs the 2 DEFCUST.jcl steps
     *       (DELETE the (buggy) CCDA-prefixed cluster, DEFINE the
     *       different-prefixed cluster). Returns {@link #RC_OK} unless an
     *       I/O exception propagates from a filesystem operation.</li>
     * </ol>
     *
     * <p>The final return code is the maximum of the two step-group return
     * codes, matching the COND-code arithmetic that a single chained JCL
     * would perform via {@code COND=(0,LT,...)} expressions.
     *
     * @return the final IDCAMS-style return code; one of {@link #RC_OK},
     *         {@link #RC_NO_INPUT}, or {@link #RC_ERROR} (the last via
     *         exception clamping in {@link #main(String[])})
     * @throws IOException if any filesystem operation (delete, write, copy)
     *                     fails for a reason other than the missing-input
     *                     soft condition handled internally
     */
    static int execute() throws IOException {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("CUSTFILE+DEFCUST job starting; runId={}, processingDate={}, tenant={}, "
                        + "custfileCluster={}, defcustDeleteCluster={}, defcustDefineCluster={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant(),
                CUSTFILE_CLUSTER_NAME, DEFCUST_DELETE_NAME, DEFCUST_DEFINE_NAME);

        // Step group 1: CUSTFILE.jcl (5 steps)
        int custfileRc = runCustfileSteps();

        // Step group 2: DEFCUST.jcl (2 steps WITH PRESERVED BUG)
        int defcustRc = runDefcustSteps();

        // COND-code: highest return-code wins
        int rc = Math.max(custfileRc, defcustRc);

        LOG.info("CUSTFILE+DEFCUST job complete; custfileRc={}, defcustRc={}, finalRc={}",
                custfileRc, defcustRc, rc);
        return rc;
    }


    // ---------------------------------------------------------------------
    // CUSTFILE.jcl step group — 5 steps preserved in JCL order
    // ---------------------------------------------------------------------

    /**
     * Runs the five CUSTFILE.jcl steps in their original JCL order:
     * <ol>
     *   <li>CLCIFIL: log-only CEMT SET FIL(CUSTDAT) CLO.</li>
     *   <li>STEP05: DELETE AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS CLUSTER
     *       (idempotent: SET MAXCC = 0 on missing target).</li>
     *   <li>STEP10: DEFINE CLUSTER ... KEYS(9 0) RECORDSIZE(500 500)
     *       SHAREOPTIONS(2 3) VOLUMES(AWSHJ1) CYLINDERS(1 5) ERASE INDEXED.</li>
     *   <li>STEP15: REPRO INFILE(CUSTDATA) OUTFILE(CUSTVSAM) byte-for-byte.</li>
     *   <li>OPCIFIL: log-only CEMT SET FIL(CUSTDAT) OPE.</li>
     * </ol>
     *
     * <p>Visible for testing.
     *
     * @return {@link #RC_OK} on success, or {@link #RC_NO_INPUT} when the
     *         staging source file is missing (matches IDCAMS REPRO condition
     *         code 4)
     * @throws IOException if any filesystem operation fails for a reason
     *                     other than the missing-input soft condition
     */
    static int runCustfileSteps() throws IOException {
        // Resolve target and source paths from configuration. The KSDS target
        // lives at the configured path; the staging source is by default the
        // preserved ASCII fixture under app/ (AAP §0.2.2 immutable). Both
        // paths are normalised to absolute form so log lines and error
        // messages reference unambiguous locations regardless of the JVM cwd.
        Path target = Path.of(getProp(PROP_CUSTDATA_PATH, DEFAULT_CUSTDATA_PATH)).toAbsolutePath();
        Path source = Path.of(getProp(PROP_CUSTDATA_SOURCE, DEFAULT_CUSTDATA_SOURCE)).toAbsolutePath();

        Path parentDir = target.getParent() != null
                ? target.getParent()
                : Path.of(".").toAbsolutePath();
        Path schema = parentDir.resolve(target.getFileName() + ".schema");

        // Ensure the parent directory exists before any file I/O. This is the
        // Java analog of the z/OS catalog allocation that DEFINE CLUSTER
        // performs implicitly on the mainframe.
        Files.createDirectories(parentDir);

        // ========================================================
        // CLCIFIL — SDSF / CEMT SET FIL(CUSTDAT) CLO (log-only)
        // ========================================================
        // No CICS region to command from a JVM; log the equivalent
        // operator-visible event so external monitoring sees the same lifecycle
        // milestones as a mainframe execution.
        LOG.info("CUSTFILE.CLCIFIL: CEMT SET FIL({}) CLO — no-op in file-based runtime",
                CUSTFILE_CICS_FILE_ID);

        // ========================================================
        // STEP05 — IDCAMS DELETE + IF MAXCC LE 08 THEN SET MAXCC = 0
        // ========================================================
        // Files.deleteIfExists returns false when the target is absent,
        // matching the SET MAXCC = 0 swallow-not-found behaviour. No throw,
        // no warn — just info-level "either path is fine".
        boolean ksdsDeleted = Files.deleteIfExists(target);
        boolean schemaDeleted = Files.deleteIfExists(schema);
        if (ksdsDeleted) {
            LOG.info("CUSTFILE.STEP05: deleted prior KSDS file {} (mainframe cluster {})",
                    target, CUSTFILE_CLUSTER_NAME);
        } else {
            LOG.info("CUSTFILE.STEP05: no prior KSDS file at {} (idempotent; mainframe cluster {})",
                    target, CUSTFILE_CLUSTER_NAME);
        }
        if (schemaDeleted) {
            LOG.info("CUSTFILE.STEP05: deleted prior schema sidecar {}", schema);
        }

        // ========================================================
        // STEP10 — IDCAMS DEFINE CLUSTER (verbatim attributes)
        // ========================================================
        // The schema sidecar records the IDCAMS DEFINE CLUSTER attributes
        // verbatim so that downstream readers (and any future schema
        // validator) can confirm the layout matches the COBOL contract.
        // Writing the sidecar BEFORE the REPRO copy guarantees that a reader
        // observing the data file always finds the sibling schema.
        Files.writeString(schema,
                "cluster=" + CUSTFILE_CLUSTER_NAME + System.lineSeparator()
                        + "keyLength=" + CUSTFILE_KEY_LENGTH + System.lineSeparator()
                        + "keyOffset=" + CUSTFILE_KEY_OFFSET + System.lineSeparator()
                        + "recordLength=" + CUSTFILE_RECORD_LENGTH + System.lineSeparator()
                        + "shareOptions=" + CUSTFILE_SHARE_OPTIONS + System.lineSeparator()
                        + "cylinders=" + CUSTFILE_CYLINDERS + System.lineSeparator()
                        + "volumes=" + CUSTFILE_VOLUMES + System.lineSeparator()
                        + "erase=" + CUSTFILE_ERASE + System.lineSeparator()
                        + "indexed=" + CUSTFILE_INDEXED + System.lineSeparator()
                        + "dataComponent=" + CUSTFILE_CLUSTER_NAME + ".DATA" + System.lineSeparator()
                        + "indexComponent=" + CUSTFILE_CLUSTER_NAME + ".INDEX" + System.lineSeparator()
                        + "stagingSource=" + CUSTFILE_PS_NAME + System.lineSeparator());
        LOG.info("CUSTFILE.STEP10: DEFINE OK cluster={}, key={},{} record={} SHAREOPTIONS({}) "
                        + "CYLINDERS({}) VOLUMES({}) ERASE={} INDEXED={}",
                CUSTFILE_CLUSTER_NAME, CUSTFILE_KEY_LENGTH, CUSTFILE_KEY_OFFSET,
                CUSTFILE_RECORD_LENGTH, CUSTFILE_SHARE_OPTIONS, CUSTFILE_CYLINDERS,
                CUSTFILE_VOLUMES, CUSTFILE_ERASE, CUSTFILE_INDEXED);

        // ========================================================
        // STEP15 — IDCAMS REPRO INFILE(CUSTDATA) OUTFILE(CUSTVSAM)
        // ========================================================
        // Copy the staging flat file byte-for-byte into the KSDS target. The
        // COBOL contract is that REPRO performs no record re-ordering (the
        // input is already in key-ascending order because the upstream
        // generator authored it that way), so a straight Files.copy preserves
        // the on-disk layout exactly. StandardCopyOption.REPLACE_EXISTING
        // guards against a residual file that somehow survived the STEP05
        // delete (e.g. an external symlink or a race with another process).
        if (!Files.exists(source)) {
            LOG.warn("CUSTFILE.STEP15: staging source not found at {} (mainframe dataset {}); rc={}",
                    source, CUSTFILE_PS_NAME, RC_NO_INPUT);
            // Still log the OPCIFIL "close" semantics for symmetry; the
            // operator-visible job pipeline appears complete from a logging
            // perspective even on the soft-warning path.
            LOG.info("CUSTFILE.OPCIFIL: CEMT SET FIL({}) OPE — no-op in file-based runtime",
                    CUSTFILE_CICS_FILE_ID);
            return RC_NO_INPUT;
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        long copiedBytes = Files.size(target);
        long copiedRecords = copiedBytes / CUSTFILE_RECORD_LENGTH;
        LOG.info("CUSTFILE.STEP15: REPRO OK source={} target={} bytes={} records~={}",
                source, target, copiedBytes, copiedRecords);

        // ========================================================
        // OPCIFIL — SDSF / CEMT SET FIL(CUSTDAT) OPE (log-only)
        // ========================================================
        LOG.info("CUSTFILE.OPCIFIL: CEMT SET FIL({}) OPE — no-op in file-based runtime",
                CUSTFILE_CICS_FILE_ID);

        return RC_OK;
    }

    // ---------------------------------------------------------------------
    // DEFCUST.jcl step group — 2 steps WITH PRESERVED BUG (per AAP §0.7.1)
    // ---------------------------------------------------------------------

    /**
     * Runs the two DEFCUST.jcl steps preserving <strong>the known
     * dataset-name mismatch bug</strong> verbatim per AAP &sect;0.7.1.
     *
     * <p>Step sequence (preserving JCL step order, with the two
     * confusingly-named {@code STEP05} steps relabelled
     * {@code STEP05a}/{@code STEP05b} in the Java log lines for clarity):
     * <ol>
     *   <li><strong>STEP05a (DELETE)</strong>: target dataset name
     *       <em>{@link #DEFCUST_DELETE_NAME}</em>
     *       ({@code AWS.CCDA.CUSTDATA.CLUSTER} &mdash; the buggy CCDA-prefixed
     *       name). Java translation: idempotent
     *       {@link java.nio.file.Files#deleteIfExists(Path)} on the
     *       corresponding placeholder file. An explicit {@code WARN} log line
     *       records the bug.</li>
     *   <li><strong>STEP05b (DEFINE)</strong>: target dataset name
     *       <em>{@link #DEFCUST_DEFINE_NAME}</em>
     *       ({@code AWS.CUSTDATA.CLUSTER} &mdash; the still-different
     *       AWS-prefixed name), with {@code KEYS(10 0)} and
     *       {@code SHAREOPTIONS(1 4)}. Java translation: writes a
     *       {@code .schema} sidecar capturing both names plus the
     *       attributes, then touches an empty data file to represent the
     *       freshly-defined cluster. A second {@code WARN} log line records
     *       the bug.</li>
     * </ol>
     *
     * <p>Visible for testing.
     *
     * @return {@link #RC_OK} on success (the DEFCUST.jcl source has no REPRO
     *         step, so there is no soft-warning path)
     * @throws IOException if any filesystem operation (delete, write, create)
     *                     fails
     */
    static int runDefcustSteps() throws IOException {
        Path deletePath = Path.of(getProp(PROP_DEFCUST_DELETE_PATH, DEFAULT_DEFCUST_DELETE_PATH))
                .toAbsolutePath();
        Path definePath = Path.of(getProp(PROP_DEFCUST_DEFINE_PATH, DEFAULT_DEFCUST_DEFINE_PATH))
                .toAbsolutePath();

        Path defineParent = definePath.getParent() != null
                ? definePath.getParent()
                : Path.of(".").toAbsolutePath();
        Path deleteParent = deletePath.getParent() != null
                ? deletePath.getParent()
                : Path.of(".").toAbsolutePath();
        Path defineSchema = defineParent.resolve(definePath.getFileName() + ".schema");

        // Ensure both parent directories exist for the create/write below.
        Files.createDirectories(deleteParent);
        Files.createDirectories(defineParent);

        // ========================================================
        // STEP05a — DELETE AWS.CCDA.CUSTDATA.CLUSTER (BUGGY NAME)
        // ========================================================
        // The originating JCL specifies a dataset prefix (CCDA) that does
        // NOT match anything else in the COBOL source tree — almost
        // certainly a copy-paste error. Per AAP §0.7.1 we preserve the name
        // verbatim and emit a WARN log line pointing operators to
        // java/MIGRATION_NOTES.md. The Java translation deletes a
        // placeholder file whose path encodes the buggy prefix so the
        // discrepancy is also visible in the file system listing.
        LOG.warn("DEFCUST.STEP05a: DELETE references '{}' — KNOWN BUG; the originating JCL "
                        + "DELETE name does NOT match the subsequent DEFINE name ('{}'). "
                        + "Preserved verbatim per AAP §0.7.1; see java/MIGRATION_NOTES.md.",
                DEFCUST_DELETE_NAME, DEFCUST_DEFINE_NAME);
        boolean deleteHappened = Files.deleteIfExists(deletePath);
        if (deleteHappened) {
            LOG.info("DEFCUST.STEP05a: deleted prior placeholder file {} (mainframe cluster {})",
                    deletePath, DEFCUST_DELETE_NAME);
        } else {
            LOG.info("DEFCUST.STEP05a: no prior placeholder file at {} (idempotent; "
                            + "mainframe cluster {})",
                    deletePath, DEFCUST_DELETE_NAME);
        }

        // ========================================================
        // STEP05b — DEFINE AWS.CUSTDATA.CLUSTER (STILL-DIFFERENT NAME)
        // ========================================================
        // The DEFINE name differs from BOTH the DELETE name above AND the
        // CUSTFILE.jcl cluster name (three different names for "the
        // customer file" across two JCL files). KEYS(10 0) is one byte
        // longer than CUSTFILE.jcl's KEYS(9 0); SHAREOPTIONS(1 4) differs
        // from CUSTFILE.jcl's SHAREOPTIONS(2 3). All discrepancies are
        // preserved verbatim per AAP §0.7.1.
        LOG.warn("DEFCUST.STEP05b: DEFINE references '{}' — DIFFERENT FROM DELETE NAME ABOVE "
                        + "('{}') AND from CUSTFILE.jcl cluster ('{}'); KEYS({} {}) "
                        + "SHAREOPTIONS({}); preserved verbatim per AAP §0.7.1; "
                        + "see java/MIGRATION_NOTES.md.",
                DEFCUST_DEFINE_NAME, DEFCUST_DELETE_NAME, CUSTFILE_CLUSTER_NAME,
                DEFCUST_KEY_LENGTH, DEFCUST_KEY_OFFSET, DEFCUST_SHARE_OPTIONS);

        // Remove any stale data file from a prior run before re-creating
        // (the DEFINE step is implicitly preceded by the DELETE STEP05a,
        // but the buggy DELETE name does NOT match this DEFINE name, so
        // the data file at definePath is NOT automatically cleared by
        // STEP05a — we mirror the mainframe behaviour where the residual
        // DEFINE-named cluster would survive the buggy DELETE).
        Files.deleteIfExists(defineSchema);
        Files.deleteIfExists(definePath);

        // Write the schema sidecar with BOTH the buggy DELETE name (so the
        // mismatch is visible) and the DEFINE name (the actual target).
        Files.writeString(defineSchema,
                "cluster=" + DEFCUST_DEFINE_NAME + System.lineSeparator()
                        + "deleteCluster=" + DEFCUST_DELETE_NAME
                        + " (MISMATCH PRESERVED PER AAP §0.7.1)" + System.lineSeparator()
                        + "keyLength=" + DEFCUST_KEY_LENGTH + System.lineSeparator()
                        + "keyOffset=" + DEFCUST_KEY_OFFSET + System.lineSeparator()
                        + "recordLength=" + DEFCUST_RECORD_LENGTH + System.lineSeparator()
                        + "shareOptions=" + DEFCUST_SHARE_OPTIONS + System.lineSeparator()
                        + "cylinders=" + DEFCUST_CYLINDERS + System.lineSeparator()
                        + "erase=" + DEFCUST_ERASE + System.lineSeparator()
                        + "indexed=" + DEFCUST_INDEXED + System.lineSeparator()
                        + "dataComponent=" + DEFCUST_DEFINE_NAME + ".DATA" + System.lineSeparator()
                        + "indexComponent=" + DEFCUST_DEFINE_NAME + ".INDEX" + System.lineSeparator()
                        + "notes=DEFCUST.jcl dataset-name mismatch bug preserved per AAP §0.7.1"
                        + System.lineSeparator());

        // Touch the data file (DEFINE on the mainframe creates an empty
        // cluster; the Java analog is an empty placeholder file).
        Files.createFile(definePath);

        LOG.info("DEFCUST.STEP05b: DEFINE OK cluster={}, key={},{} record={} SHAREOPTIONS({}) "
                        + "CYLINDERS({}) ERASE={} INDEXED={}; placeholder file={}, schema={}",
                DEFCUST_DEFINE_NAME, DEFCUST_KEY_LENGTH, DEFCUST_KEY_OFFSET,
                DEFCUST_RECORD_LENGTH, DEFCUST_SHARE_OPTIONS, DEFCUST_CYLINDERS,
                DEFCUST_ERASE, DEFCUST_INDEXED, definePath, defineSchema);

        LOG.info("DEFCUST: dataset-name mismatch bug faithfully preserved; see "
                + "java/MIGRATION_NOTES.md for rationale.");
        return RC_OK;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Resolves a configuration property by consulting (in precedence order)
     * the JVM environment variable derived from {@code key}, then the JVM
     * system property {@code key}, then the provided {@code defaultValue}.
     *
     * <p>The environment-variable key is derived from {@code key} by
     * upper-casing (with {@link Locale#ROOT}) and replacing every {@code '.'}
     * and {@code '-'} with {@code '_'} &mdash; i.e., the system property
     * {@code "carddemo.file.custdata.path"} maps to the environment variable
     * {@code "CARDDEMO_FILE_CUSTDATA_PATH"}. This matches the convention used
     * by the sibling {@link DefineDiscountGroupApp} and
     * {@link DefineTcatBalApp} classes.
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


