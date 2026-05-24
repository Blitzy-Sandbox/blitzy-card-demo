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
 * Composition root for the Java translation of {@code app/jcl/TRANCATG.jcl}
 * &mdash; a three-step IDCAMS job that re-creates and loads the
 * {@code TRANCATG.VSAM.KSDS} cluster (Transaction Category lookup file,
 * copybook {@code app/cpy/CVTRA04Y.cpy}).
 *
 * <h2>Source artefact</h2>
 * <p>The originating JCL job {@code app/jcl/TRANCATG.jcl} chains three steps,
 * each preserved by this Java translation (AAP &sect;0.7.1 idiom-for-idiom
 * mandate):
 * <ol>
 *   <li><strong>STEP05</strong> &mdash; {@code EXEC PGM=IDCAMS} that runs
 *       {@code DELETE AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS CLUSTER} followed by
 *       {@code SET MAXCC = 0}. The {@code SET MAXCC = 0} makes the delete
 *       idempotent: a missing cluster is not an error.</li>
 *   <li><strong>STEP10</strong> &mdash; {@code EXEC PGM=IDCAMS} that runs
 *       {@code DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS)
 *       CYLINDERS(1 5) VOLUMES(AWSHJ1) KEYS(6 0) RECORDSIZE(60 60)
 *       SHAREOPTIONS(2 3) ERASE INDEXED)} with sibling {@code DATA} and
 *       {@code INDEX} components named
 *       {@code AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS.DATA} and
 *       {@code AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS.INDEX}.</li>
 *   <li><strong>STEP15</strong> &mdash; {@code EXEC PGM=IDCAMS} that runs
 *       {@code REPRO INFILE(TRANCATG) OUTFILE(TCATVSAM)} copying the flat
 *       sequential file {@code AWS.M2.CARDDEMO.TRANCATG.PS} into the freshly
 *       defined KSDS cluster.</li>
 * </ol>
 *
 * <h2>Record layout (per {@code app/cpy/CVTRA04Y.cpy:&sect;TRAN-CAT-RECORD})</h2>
 * Each of the 18 input records (see {@code app/data/ASCII/trancatg.txt}) is
 * exactly 60 bytes:
 * <pre>
 *   bytes  0..5   TRAN-CAT-KEY                  6 bytes (composite key)
 *     bytes 0..1    TRAN-TYPE-CD     PIC X(02)  2 bytes  (e.g., "01")
 *     bytes 2..5    TRAN-CAT-CD      PIC 9(04)  4 bytes  (e.g., "0001")
 *   bytes  6..55  TRAN-CAT-TYPE-DESC PIC X(50) 50 bytes  (e.g., "Regular Sales Draft")
 *   bytes 56..59  FILLER             PIC X(04)  4 bytes  (trailing "0000" pad)
 * </pre>
 * <p>The key length (6) and offset (0) below match the JCL {@code KEYS(6 0)}
 * clause exactly; the record length (60) matches {@code RECORDSIZE(60 60)}.
 * Any change to these constants would break downstream readers that consume
 * the loaded file.
 *
 * <h2>File mapping</h2>
 * <ul>
 *   <li>{@code AWS.M2.CARDDEMO.TRANCATG.PS} (mainframe sequential staging file)
 *       &rarr; the source flat file at
 *       {@code carddemo.file.trancatg.source} (default
 *       {@code app/data/ASCII/trancatg.txt}, the 18&times;60-byte ASCII
 *       fixture preserved unchanged under {@code app/} per AAP &sect;0.2.2).</li>
 *   <li>{@code AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS} (mainframe VSAM KSDS) &rarr;
 *       the file at {@code carddemo.file.trancatg.path} (default
 *       {@code ./data/trancatg.dat}) plus a sibling {@code <name>.schema} text
 *       file carrying the KSDS metadata (cluster name, key spec, record size,
 *       share options, ERASE flag, data &amp; index component names).</li>
 * </ul>
 *
 * <h2>Translation strategy &mdash; idiom-for-idiom (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>IDCAMS {@code DELETE ... CLUSTER} + {@code SET MAXCC = 0} becomes an
 *       idempotent {@link java.nio.file.Files#deleteIfExists(Path)} call: a
 *       missing target file simply returns {@code false} without throwing,
 *       reproducing the mainframe "swallow the not-found condition"
 *       behaviour.</li>
 *   <li>IDCAMS {@code DEFINE CLUSTER ...} becomes a text {@code .schema}
 *       sidecar file that records the KSDS metadata verbatim (cluster name,
 *       {@code KEYS(6 0)}, {@code RECORDSIZE(60 60)}, {@code SHAREOPTIONS(2
 *       3)}, {@code ERASE}, {@code INDEXED}, {@code DATA} and {@code INDEX}
 *       component names, {@code VOLUMES(AWSHJ1)}, {@code CYLINDERS(1 5)}) so
 *       downstream readers can validate the layout. The cluster name is
 *       preserved verbatim ({@code AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS}) for
 *       log-comparison fidelity.</li>
 *   <li>IDCAMS {@code REPRO INFILE(TRANCATG) OUTFILE(TCATVSAM)} becomes a
 *       {@link java.nio.file.Files#copy(Path, Path,
 *       java.nio.file.CopyOption...)} call from the staging flat file to the
 *       KSDS target with {@link java.nio.file.StandardCopyOption#REPLACE_EXISTING}.
 *       Records are loaded in their existing file order, matching IDCAMS REPRO's
 *       byte-for-byte copy behaviour (the input is already in key-ascending
 *       order, so REPRO performs no re-ordering on the mainframe either).</li>
 * </ul>
 *
 * <h2>Process-exit semantics</h2>
 * <p>Each IDCAMS-style step contributes to the highest condition code (MAXCC)
 * the JCL would observe. This main preserves that contract:
 * <ul>
 *   <li>{@link #RC_OK} (0) &mdash; every step succeeded.</li>
 *   <li>{@link #RC_NO_INPUT} (4) &mdash; the staging source file is missing.
 *       IDCAMS REPRO would emit condition code 4 ("input dataset not found");
 *       this Java translation surfaces the equivalent return code so
 *       downstream batch orchestration can branch on it.</li>
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
 * </ul>
 *
 * @see app/jcl/TRANCATG.jcl  the originating JCL job (three-step DELETE/DEFINE/REPRO)
 * @see app/cpy/CVTRA04Y.cpy  the TRAN-CAT-RECORD copybook defining the 60-byte layout
 * @see DefineTransactionTypeApp  the sibling DEFINE/REPRO app pattern this class mirrors
 * @see BatchRunContext       the immutable context bound to {@link #BATCH_CTX}
 * @since 1.0.0
 */
@CobolProgram(
        value = "IDCAMS (TRANCATG.jcl)",
        sourcePath = "app/jcl/TRANCATG.jcl",
        translationDate = "2025-10-24",
        notes = "JCL translation of the three-step TRANCATG KSDS bootstrap (STEP05 IDCAMS "
                + "DELETE + STEP10 IDCAMS DEFINE CLUSTER KEYS(6 0) RECORDSIZE(60 60) "
                + "VOLUMES(AWSHJ1) CYLINDERS(1 5) SHAREOPTIONS(2 3) ERASE INDEXED + "
                + "STEP15 IDCAMS REPRO from AWS.M2.CARDDEMO.TRANCATG.PS). In the file-"
                + "based runtime the KSDS becomes a 60-byte fixed-width file plus a "
                + "sidecar .schema descriptor; the staging PS file is sourced from "
                + "app/data/ASCII/trancatg.txt (18 records of 60 bytes, preserved per "
                + "AAP §0.2.2). Composite key length 6 matches CVTRA04Y TRAN-CAT-KEY "
                + "(TRAN-TYPE-CD PIC X(02) + TRAN-CAT-CD PIC 9(04))."
)
public final class DefineTransactionCategoryApp {

    // ---------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------

    /**
     * SLF4J logger. Backed at runtime by logback-classic supplied via the
     * {@code carddemo-app} shaded jar packaging (AAP &sect;0.5.1); this class
     * deliberately holds no reference to a concrete logging implementation.
     * No PAN, no password, no card data is ever logged from this app &mdash;
     * the only payload logged is metadata (counts, byte sizes, cluster names).
     */
    private static final Logger LOG = LoggerFactory.getLogger(DefineTransactionCategoryApp.class);

    // ---------------------------------------------------------------------
    // ScopedValue (JEP 506 Final) — propagates BatchRunContext into execute()
    // ---------------------------------------------------------------------

    /**
     * Local {@link java.lang.ScopedValue} for the {@link BatchRunContext} that
     * orchestrates this job. Bound by {@link #main(String[])} via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(DefineTransactionCategoryApp::execute)}
     * and read inside {@link #execute()} via {@code BATCH_CTX.get()} to emit
     * the {@code runId}, {@code processingDate}, and {@code tenant} on the
     * TRANCATG startup log line.
     *
     * <p>This binding <strong>replaces {@link ThreadLocal} entirely</strong>
     * per AAP &sect;0.6.6 and &sect;0.7.4. The {@code ScopedValue} class moved
     * from {@code java.util.concurrent} (preview) to {@code java.lang} (final)
     * when JEP 506 was finalised in Java 25, so it is available here via the
     * JEP 511 {@code import module java.base} declaration above.
     *
     * <p>Co-located with {@link BatchRunContext#BATCH_CTX} but scoped to this
     * app to keep the binding window narrow (one {@code ScopedValue} per
     * composition root, matching the sibling {@link DefineTransactionTypeApp}
     * and {@link DefineTcatBalApp} convention).
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // ---------------------------------------------------------------------
    // KSDS cluster metadata — verbatim from app/jcl/TRANCATG.jcl STEP10
    // ---------------------------------------------------------------------

    /** VSAM KSDS cluster name preserved verbatim from {@code TRANCATG.jcl} STEP10. */
    static final String CLUSTER_NAME = "AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS";

    /** Sequential staging file name preserved verbatim from {@code TRANCATG.jcl} STEP15. */
    static final String PS_NAME = "AWS.M2.CARDDEMO.TRANCATG.PS";

    /**
     * {@code KEYS(6 0)} &mdash; key length in bytes (6).
     * <p>Matches the composite {@code TRAN-CAT-KEY} from
     * {@code app/cpy/CVTRA04Y.cpy}:
     * {@code TRAN-TYPE-CD PIC X(02)} (2 bytes) +
     * {@code TRAN-CAT-CD  PIC 9(04)} (4 bytes) = 6 bytes total.
     */
    static final int KEY_LENGTH = 6;

    /** {@code KEYS(6 0)} &mdash; key offset from record start (0). */
    static final int KEY_OFFSET = 0;

    /** {@code RECORDSIZE(60 60)} &mdash; fixed record length in bytes (60). */
    static final int RECORD_LENGTH = 60;

    /**
     * {@code SHAREOPTIONS(2 3)} &mdash; cross-region/cross-system share options.
     * <p>This is the standard CardDemo share-options value used by most
     * clusters (TRANCATG, TCATBALF, DISCGRP, etc.). It is preserved verbatim
     * from {@code TRANCATG.jcl:L42} per the AAP &sect;0.6.12 byte-fidelity
     * mandate; it is recorded in the {@code .schema} sidecar so downstream
     * validators can confirm exact JCL fidelity.
     */
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
    // Return codes (IDCAMS convention)
    // ---------------------------------------------------------------------

    /** Successful return code: every step completed. */
    static final int RC_OK = 0;

    /**
     * Soft warning: the staging input file is missing. IDCAMS REPRO would
     * emit condition code {@code 4} ("input dataset not found"); this Java
     * translation surfaces the equivalent return code so downstream batch
     * orchestration can branch on it.
     */
    static final int RC_NO_INPUT = 4;

    /** IDCAMS "severe error" return code: an uncaught exception occurred. */
    static final int RC_ERROR = 16;

    // ---------------------------------------------------------------------
    // Configuration keys (12-factor per AAP §0.7.2)
    // ---------------------------------------------------------------------

    /**
     * JVM system-property / env-var key for the KSDS target path. Documented
     * in {@code java/application.properties.example} as
     * {@code carddemo.file.trancatg.path}.
     */
    static final String PROP_TRANCATG_PATH = "carddemo.file.trancatg.path";

    /**
     * Default KSDS target path used when no system property and no
     * environment variable supply a value. Matches the default in
     * {@code java/application.properties.example}.
     */
    static final String DEFAULT_TRANCATG_PATH = "./data/trancatg.dat";

    /**
     * JVM system-property / env-var key for the staging-source flat file path.
     * Default is the preserved 18-record ASCII fixture under {@code app/}; in
     * production this would be re-pointed at the upstream-supplied
     * TRAN-CAT feed.
     */
    static final String PROP_TRANCATG_SOURCE = "carddemo.file.trancatg.source";

    /**
     * Default staging-source path: the 18&times;60-byte ASCII fixture in the
     * preserved COBOL source tree (AAP &sect;0.2.2 mandates {@code app/} is
     * immutable; this app only reads from it).
     */
    static final String DEFAULT_TRANCATG_SOURCE = "app/data/ASCII/trancatg.txt";

    // ---------------------------------------------------------------------
    // Construction guard
    // ---------------------------------------------------------------------

    /** Utility class &mdash; not constructible. */
    private DefineTransactionCategoryApp() {
        throw new AssertionError("DefineTransactionCategoryApp is not constructible");
    }

    // ---------------------------------------------------------------------
    // Java main entry point
    // ---------------------------------------------------------------------

    /**
     * Java main entry point. Mirrors the three-step {@code TRANCATG.jcl} job:
     * resolves a {@link BatchRunContext} from JVM system properties and
     * environment variables (12-factor configuration), binds it to
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
            rc = ScopedValue.where(BATCH_CTX, ctx).call(DefineTransactionCategoryApp::execute);
        } catch (Exception e) {
            LOG.error("TRANCATG job failed with uncaught exception", e);
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
    // Job body — three steps matching the JCL
    // ---------------------------------------------------------------------

    /**
     * Runs the three-step TRANCATG job body inside the {@link #BATCH_CTX}
     * scope. Visible for testing.
     *
     * <p>Step sequence (preserving JCL step order, per AAP &sect;0.7.1):
     * <ol>
     *   <li><strong>STEP05</strong>: {@code Files.deleteIfExists(target)} on
     *       both the KSDS data file and the sidecar schema file (the
     *       IDCAMS {@code DELETE ... CLUSTER} + {@code SET MAXCC = 0} idiom
     *       made idempotent).</li>
     *   <li><strong>STEP10</strong>: write the {@code .schema} sidecar with
     *       the verbatim DEFINE attributes (cluster name, key spec, record
     *       size, share options, ERASE, INDEXED, DATA/INDEX component
     *       names, volumes, cylinders).</li>
     *   <li><strong>STEP15</strong>: copy the staging source flat file to
     *       the KSDS target via
     *       {@link java.nio.file.Files#copy(Path, Path,
     *       java.nio.file.CopyOption...)} with
     *       {@link java.nio.file.StandardCopyOption#REPLACE_EXISTING}.</li>
     * </ol>
     *
     * @return {@link #RC_OK} on success, or {@link #RC_NO_INPUT} when the
     *         staging source file is missing
     * @throws IOException if any filesystem operation (delete, write, copy)
     *                     fails for a reason other than the missing-input
     *                     soft condition
     */
    static int execute() throws IOException {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("TRANCATG job starting; runId={}, processingDate={}, tenant={}, cluster={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant(), CLUSTER_NAME);

        // Resolve target and source paths from configuration. The KSDS target
        // lives at the configured path; the staging source is by default the
        // preserved ASCII fixture under app/ (AAP §0.2.2 immutable). Both
        // paths are normalised to absolute form so log lines and error
        // messages reference unambiguous locations regardless of the JVM cwd.
        Path target = Path.of(getProp(PROP_TRANCATG_PATH, DEFAULT_TRANCATG_PATH)).toAbsolutePath();
        Path source = Path.of(getProp(PROP_TRANCATG_SOURCE, DEFAULT_TRANCATG_SOURCE)).toAbsolutePath();

        Path parentDir = target.getParent() != null
                ? target.getParent()
                : Path.of(".").toAbsolutePath();
        Path schema = parentDir.resolve(target.getFileName() + ".schema");

        // Ensure the parent directory exists before any file I/O. This is the
        // Java analog of the z/OS catalog allocation that DEFINE CLUSTER
        // performs implicitly on the mainframe.
        Files.createDirectories(parentDir);

        // ========================================================
        // STEP05 — IDCAMS DELETE + SET MAXCC = 0 (idempotent)
        // ========================================================
        // Files.deleteIfExists returns false when the target is absent,
        // matching the SET MAXCC = 0 swallow-not-found behaviour. No throw,
        // no warn — just info-level "either path is fine".
        boolean ksdsDeleted = Files.deleteIfExists(target);
        boolean schemaDeleted = Files.deleteIfExists(schema);
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

        // ========================================================
        // STEP10 — IDCAMS DEFINE CLUSTER (verbatim attributes)
        // ========================================================
        // The schema sidecar records the IDCAMS DEFINE CLUSTER attributes
        // verbatim so that downstream readers (and any future schema
        // validator) can confirm the layout matches the COBOL contract.
        // Writing the sidecar BEFORE the REPRO copy guarantees that a reader
        // observing the data file always finds the sibling schema. The
        // SHAREOPTIONS(2 3) value is recorded verbatim per AAP §0.6.12.
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
        // STEP15 — IDCAMS REPRO INFILE(TRANCATG) OUTFILE(TCATVSAM)
        // ========================================================
        // Copy the staging flat file byte-for-byte into the KSDS target. The
        // COBOL contract is that REPRO performs no record re-ordering (the
        // input is already in key-ascending order because the upstream
        // generator authored it that way), so a straight Files.copy preserves
        // the on-disk layout exactly. StandardCopyOption.REPLACE_EXISTING
        // guards against a residual file that somehow survived the STEP05
        // delete (e.g. an external symlink or a race with another process).
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

        LOG.info("TRANCATG job complete; rc={}", RC_OK);
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
     * {@code "carddemo.file.trancatg.path"} maps to the environment variable
     * {@code "CARDDEMO_FILE_TRANCATG_PATH"}. This matches the convention used
     * by the sibling {@link DefineTransactionTypeApp} and
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
