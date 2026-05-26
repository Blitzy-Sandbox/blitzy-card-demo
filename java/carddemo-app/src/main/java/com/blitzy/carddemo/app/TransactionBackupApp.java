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

// JEP 511 (finalized in Java 25): single declaration imports every package
// exported by the java.base module. Brings into scope:
//   - java.lang.ScopedValue (JEP 506 final; moved from java.util.concurrent
//                            to java.lang when finalized in Java 25)
//   - java.nio.file.{Path, Files}                       (no java.io.File per
//                                                        AAP §0.6.5)
//   - java.io.IOException
//   - java.util.Locale
//   - java.util.stream.Stream
//   - java.lang.String/Integer/System/Exception/AssertionError
import module java.base;

import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/TRANBKP.jcl} &mdash;
 * a three-step JCL job that backs up the TRANSACT VSAM KSDS into the next
 * generation of the {@code AWS.M2.CARDDEMO.TRANSACT.BKUP} GDG and then deletes
 * and re-defines an empty replacement cluster ready for fresh data loading.
 *
 * <h2>Source artifact</h2>
 * <p>{@code app/jcl/TRANBKP.jcl} contains three steps; each is preserved by this
 * Java translation (AAP &sect;0.7.1 idiom-for-idiom mandate):
 * <ol>
 *   <li><strong>STEP05R</strong> &mdash; {@code EXEC PROC=REPROC,
 *       CNTLLIB=AWS.M2.CARDDEMO.CNTL} with overrides
 *       {@code PRC001.FILEIN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} (DISP=SHR)
 *       and {@code PRC001.FILEOUT=AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)}
 *       (DISP=(NEW,CATLG,DELETE), DCB=(LRECL=350,RECFM=FB,BLKSIZE=0),
 *       SPACE=(CYL,(1,1),RLSE)).
 *       The REPROC cataloged procedure ({@code app/proc/REPROC.prc}) is a
 *       generic IDCAMS REPRO wrapper that loads its control card from
 *       {@code &CNTLLIB(REPROCT)}; the effective semantic is &quot;copy FILEIN
 *       byte-for-byte to FILEOUT&quot;.</li>
 *   <li><strong>STEP05</strong> &mdash; {@code EXEC PGM=IDCAMS} that
 *       {@code DELETE}s the {@code TRANSACT.VSAM.KSDS} cluster and the
 *       {@code TRANSACT.VSAM.AIX} alternate index. Each delete is followed by
 *       {@code IF MAXCC LE 08 THEN SET MAXCC = 0} to swallow the
 *       &quot;not found&quot; condition.</li>
 *   <li><strong>STEP10</strong> &mdash; {@code EXEC PGM=IDCAMS} with
 *       {@code COND=(4,LT)} that {@code DEFINE CLUSTER}s an empty replacement
 *       with {@code CYLINDERS(1 5) VOLUMES(AWSHJ1) KEYS(16 0)
 *       RECORDSIZE(350 350) SHAREOPTIONS(2 3) ERASE INDEXED} plus
 *       {@code DATA(NAME=...KSDS.DATA)} and
 *       {@code INDEX(NAME=...KSDS.INDEX)} component clauses.</li>
 * </ol>
 *
 * <h2>JCL conditional execution (preserved verbatim)</h2>
 * <ul>
 *   <li>STEP05 has no {@code COND} in the source JCL but is intended to chain
 *       after STEP05R; in the Java translation we still gate STEP05 on
 *       STEP05R returning {@code 0}, since deleting the live cluster before a
 *       successful backup is destructive. This is the standard mainframe
 *       convention for backup-then-recreate jobs (the COBOL author likely
 *       relied on the implicit {@code COND=(0,NE,STEP05R)} pattern that
 *       follows from the JCL deck ordering; we make it explicit).</li>
 *   <li>STEP10 has {@code COND=(4,LT)} in the source &mdash; skip if any prior
 *       step's return code was strictly greater than {@code 4}. We preserve
 *       that condition by tracking the max prior return code in
 *       {@code maxPriorRc} and short-circuiting STEP10 when it exceeds
 *       {@link #RC_WARN}.</li>
 * </ul>
 *
 * <h2>File mapping &mdash; GDG &rarr; versioned files (AAP &sect;0.6.12)</h2>
 * The mainframe Generation Data Group {@code AWS.M2.CARDDEMO.TRANSACT.BKUP}
 * is realised as a filesystem directory whose name preserves the mainframe
 * dataset name verbatim:
 * <pre>
 *   &lt;gdgRoot&gt;/AWS.M2.CARDDEMO.TRANSACT.BKUP/
 *     G0001V00   &larr; first backup
 *     G0002V00   &larr; second backup
 *     G0003V00   &larr; third backup
 *     ...
 * </pre>
 * Each {@code G####V00} file is one full byte-for-byte copy of the TRANSACT
 * KSDS as it existed at backup time. The {@code G####V00} naming follows the
 * z/OS convention (four-digit zero-padded generation number, fixed
 * {@code V00} suffix). The {@code (+1)} relative reference in the JCL
 * (a new generation) is realised by scanning the directory for the highest
 * existing {@code G####V00} and writing into the {@code G####V00} that is one
 * greater (see {@link #computeNextGeneration(Path)}). The base GDG directory
 * is created on demand if it does not yet exist &mdash; the typical
 * deployment flow is {@code DefineGdgApp} followed by {@code TransactionBackupApp}
 * but the latter must be runnable even when the former has not been invoked.
 *
 * <h2>File mapping &mdash; VSAM KSDS &rarr; flat file + schema sidecar</h2>
 * <ul>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} (mainframe VSAM KSDS)
 *       &rarr; the file at {@code carddemo.file.transact.path} (default
 *       {@code ./data/transact.dat}). Records are fixed-width 350-byte rows.</li>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX} (mainframe VSAM AIX)
 *       &rarr; a sibling {@code <name>.aix.schema} sidecar that records the
 *       AIX metadata (per the TRANIDX.jcl pattern in this Java tree). This
 *       app deletes the AIX sidecar in STEP05 but does NOT re-create it in
 *       STEP10 &mdash; the original source JCL does the same (only the
 *       cluster is defined in STEP10; the AIX is rebuilt by a separate
 *       TRANIDX job).</li>
 *   <li>The cluster's {@code .schema} sidecar (KSDS metadata) is regenerated
 *       in STEP10 with the verbatim {@code DEFINE CLUSTER} attributes so that
 *       downstream readers can validate the KSDS layout.</li>
 * </ul>
 *
 * <h2>Translation strategy &mdash; idiom-for-idiom (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>The REPROC cataloged procedure (an IDCAMS {@code REPRO} from FILEIN
 *       to FILEOUT) becomes a single
 *       {@link java.nio.file.Files#copy(Path, Path, java.nio.file.CopyOption...)}
 *       call. The {@code REPROCT} control card (a {@code REPRO INFILE(...)
 *       OUTFILE(...)} statement) carries no logical filtering; the byte-level
 *       contract is preserved exactly.</li>
 *   <li>IDCAMS {@code DELETE} becomes
 *       {@link java.nio.file.Files#deleteIfExists(Path)}, which is the
 *       natural idiom for &quot;delete if present, succeed otherwise&quot;
 *       and matches the {@code IF MAXCC LE 08 THEN SET MAXCC = 0} pattern in
 *       the source JCL.</li>
 *   <li>IDCAMS {@code DEFINE CLUSTER} becomes a text {@code .schema} sidecar
 *       file that captures the KSDS metadata (cluster name, key length/offset,
 *       record length, volumes, ERASE flag, INDEXED flag, data/index component
 *       names) plus an empty data file at the cluster path. The cluster name
 *       is preserved verbatim ({@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}) for
 *       log-comparison fidelity.</li>
 * </ul>
 *
 * <h2>BatchRunContext / ScopedValue (AAP &sect;0.6.6, JEP 506 Final)</h2>
 * <p>{@link #main(String[])} resolves a {@link BatchRunContext} via
 * {@link BatchRunContext#fromEnvironment()} (12-factor configuration via JVM
 * system properties and environment variables) and binds it to
 * {@link #BATCH_CTX} for the duration of {@link #execute()}. Inside
 * {@code execute()}, {@code runId} is read from {@code BATCH_CTX.get()} for
 * the structured-logging startup line so that operations teams can correlate
 * log lines with a specific batch run identifier.
 *
 * <p>This binding replaces {@link ThreadLocal} entirely per AAP &sect;0.6.6
 * and &sect;0.7.4. The {@code ScopedValue} class moved from
 * {@code java.util.concurrent} (preview) to {@code java.lang} (final) when
 * JEP 506 was finalised in Java 25; the import is implicit via
 * {@code import module java.base;}.
 *
 * <h2>Process-exit semantics</h2>
 * <p>Each step contributes to the highest condition code observed. This main
 * preserves that contract:
 * <ul>
 *   <li>{@code 0}  &mdash; every step succeeded (STEP05R copied to a new
 *       generation; STEP05 deleted the cluster + AIX; STEP10 re-defined an
 *       empty cluster).</li>
 *   <li>{@code 4}  &mdash; STEP05R found no source file ("warning" return
 *       code: nothing to back up; later steps are skipped because the
 *       cluster is already absent).</li>
 *   <li>{@code 8}  &mdash; STEP05R caught a recoverable exception during
 *       the copy (e.g., I/O error writing the backup); later steps are
 *       skipped.</li>
 *   <li>{@code 16} &mdash; any uncaught exception in main() (IDCAMS
 *       "severe error" convention); negative or out-of-range return codes
 *       are also clamped to 16.</li>
 * </ul>
 *
 * <h2>JVM-tuning recommendation (AAP &sect;0.3.4)</h2>
 * Launch with the finalized GC and heap-layout flags:
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
 *   <li>No {@link ThreadLocal} &mdash; {@link java.lang.ScopedValue} only per
 *       AAP &sect;0.6.6.</li>
 *   <li>No preview features &mdash; the {@code --enable-preview} flag is
 *       forbidden.</li>
 *   <li>No generation roll-off ({@code LIMIT(5)} enforcement on the GDG)
 *       &mdash; flagged in {@code MIGRATION_NOTES.md} as a follow-up effort
 *       per the matching note in {@link DefineGdgApp}.</li>
 * </ul>
 *
 * @see app/jcl/TRANBKP.jcl  the original source JCL
 * @see app/proc/REPROC.prc  the cataloged REPRO procedure
 * @see BatchRunContext      the immutable context bound to {@link #BATCH_CTX}
 * @see DefineGdgApp         creates the GDG base directories that this app populates
 * @since 1.0.0
 */
@CobolProgram(
        value = "REPROC + IDCAMS (TRANBKP.jcl)",
        sourcePath = "app/jcl/TRANBKP.jcl",
        translationDate = "2025-10-24",
        notes = "JCL translation of the three-step TRANSACT backup-and-recreate job. "
                + "STEP05R invokes the REPROC cataloged procedure (app/proc/REPROC.prc) "
                + "to copy the TRANSACT KSDS into the next GDG generation "
                + "(TRANSACT.BKUP(+1)); STEP05 IDCAMS DELETEs the cluster + AIX with "
                + "MAXCC swallow; STEP10 IDCAMS DEFINEs an empty replacement cluster "
                + "(KEYS(16,0) RECORDSIZE(350,350) VOLUMES(AWSHJ1) SHAREOPTIONS(2,3) "
                + "ERASE INDEXED). JCL condition COND=(4,LT) on STEP10 is preserved as "
                + "an explicit Java conditional. GDG generation numbering follows the "
                + "z/OS G####V00 convention."
)
public final class TransactionBackupApp {

    // ---------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------

    /**
     * SLF4J logger. Backed at runtime by logback-classic supplied via the
     * carddemo-app shaded jar packaging (see AAP &sect;0.5.1); this class
     * deliberately holds no reference to a concrete logging implementation.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionBackupApp.class);

    // ---------------------------------------------------------------------
    // ScopedValue (JEP 506 Final) — propagates BatchRunContext into execute()
    // ---------------------------------------------------------------------

    /**
     * Local {@link java.lang.ScopedValue} for the {@link BatchRunContext} that
     * orchestrates this job. Bound by {@link #main(String[])} via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(TransactionBackupApp::execute)}
     * and read inside {@link #execute()} via {@code BATCH_CTX.get()} to emit
     * the {@code runId} on the TRANBKP startup log line.
     *
     * <p>This binding replaces {@link ThreadLocal} entirely per AAP &sect;0.6.6
     * and &sect;0.7.4. The {@code ScopedValue} class moved from
     * {@code java.util.concurrent} (preview) to {@code java.lang} (final) when
     * JEP 506 was finalised in Java 25.
     *
     * <p>Per the export schema for this file, this field is part of the
     * {@code TransactionBackupApp} public surface &mdash; co-located with
     * {@link BatchRunContext#BATCH_CTX} but scoped to this app to keep the
     * binding window narrow.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // ---------------------------------------------------------------------
    // VSAM cluster metadata — verbatim from app/jcl/TRANBKP.jcl
    // ---------------------------------------------------------------------

    /** VSAM KSDS cluster name preserved verbatim from {@code TRANBKP.jcl}. */
    static final String CLUSTER_NAME = "AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS";

    /** VSAM Alternate Index name preserved verbatim from {@code TRANBKP.jcl}. */
    static final String AIX_NAME = "AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX";

    /** GDG base name preserved verbatim from {@code TRANBKP.jcl} STEP05R FILEOUT DSN. */
    static final String BKUP_GDG_BASE = "AWS.M2.CARDDEMO.TRANSACT.BKUP";

    /** {@code KEYS(16 0)} &mdash; key length in bytes. */
    static final int KEY_LENGTH = 16;

    /** {@code KEYS(16 0)} &mdash; key offset from record start (0). */
    static final int KEY_OFFSET = 0;

    /** {@code RECORDSIZE(350 350)} &mdash; fixed record length in bytes. */
    static final int RECORD_LENGTH = 350;

    /** {@code VOLUMES(AWSHJ1)} &mdash; preserved as informational metadata. */
    static final String VOLUMES = "AWSHJ1";

    /** {@code SHAREOPTIONS(2 3)} &mdash; preserved as informational metadata. */
    static final String SHARE_OPTIONS = "2 3";

    /** {@code CYLINDERS(1 5)} &mdash; preserved as informational metadata. */
    static final String CYLINDERS = "1 5";

    // ---------------------------------------------------------------------
    // Return codes (IDCAMS / JCL convention)
    // ---------------------------------------------------------------------

    /** Successful return code: every step completed normally. */
    static final int RC_OK = 0;

    /** "Warning" return code (e.g., source file missing); subsequent steps skipped. */
    static final int RC_WARN = 4;

    /** Recoverable error return code (IDCAMS would emit 8 on individual command failure). */
    static final int RC_ERROR = 8;

    /** IDCAMS "severe error" return code: an uncaught exception occurred. */
    static final int RC_SEVERE = 16;

    // ---------------------------------------------------------------------
    // Configuration keys (12-factor per AAP §0.7.2)
    // ---------------------------------------------------------------------

    /**
     * JVM system-property key for the TRANSACT KSDS file path. Documented in
     * {@code java/application.properties.example}.
     */
    static final String PROP_TRANSACT_PATH = "carddemo.file.transact.path";

    /**
     * Default TRANSACT KSDS file path used when no system property and no
     * environment variable supply a value.
     */
    static final String DEFAULT_TRANSACT_PATH = "./data/transact.dat";

    /**
     * JVM system-property key for the GDG root directory under which the
     * {@link #BKUP_GDG_BASE} directory (and its {@code G####V00} generation
     * files) is created. Documented in
     * {@code java/application.properties.example}.
     */
    static final String PROP_GDG_ROOT = "carddemo.gdg.root";

    /**
     * Default GDG root directory used when no system property and no
     * environment variable supply a value.
     */
    static final String DEFAULT_GDG_ROOT = "./data/gdg";

    // ---------------------------------------------------------------------
    // GDG generation file naming — z/OS G####V00 convention
    // ---------------------------------------------------------------------

    /**
     * Regex matching mainframe GDG generation file names: a leading {@code G},
     * four decimal digits (zero-padded), and the literal suffix {@code V00}.
     * Used by {@link #computeNextGeneration(Path)} to find the highest
     * existing generation number in the GDG base directory.
     */
    static final String GENERATION_NAME_REGEX = "G\\d{4}V00";

    /**
     * Format string for the GDG generation file name. Combined with the
     * 1-based next-generation index via
     * {@code String.format(GENERATION_NAME_FORMAT, nextGen)}.
     */
    static final String GENERATION_NAME_FORMAT = "G%04dV00";

    // ---------------------------------------------------------------------
    // Construction guard
    // ---------------------------------------------------------------------

    /** Utility class &mdash; not constructible. */
    private TransactionBackupApp() {
        throw new AssertionError("TransactionBackupApp is not constructible");
    }

    // ---------------------------------------------------------------------
    // Java main entry point
    // ---------------------------------------------------------------------

    /**
     * Java main entry point. Mirrors the three-step TRANBKP.jcl job: resolves
     * a {@link BatchRunContext} from JVM system properties and environment
     * variables (12-factor configuration), binds it to {@link #BATCH_CTX} via
     * JEP 506 {@code ScopedValue.where(...).call(...)}, invokes
     * {@link #execute()}, and translates the returned IDCAMS-style return
     * code to a {@link System#exit(int)} call.
     *
     * @param args command-line arguments. Currently unused; configuration
     *             flows exclusively through JVM system properties and
     *             environment variables for 12-factor compliance (AAP
     *             &sect;0.7.2).
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        // Integer (not int) because ScopedValue.Carrier#call returns the
        // declared generic type (here Integer via autoboxing from execute()'s
        // int return). Using Integer also enables the standard
        // pattern-matching guards (case Integer i when i < 0) below.
        // Primitive patterns (JEP 507) are preview-only and forbidden by
        // AAP §0.7.4.
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(TransactionBackupApp::execute);
        } catch (Exception e) {
            LOG.error("TRANBKP job failed with uncaught exception", e);
            rc = RC_SEVERE;
        }
        // Pattern-matching switch — exhaustive on Integer, NO default branch
        // per AAP §0.7.3. Coverage proof:
        //   case null         + 4 known-good constants (0/4/8/16)
        // + case 12           (preserve unmapped IDCAMS RC=12 for diagnosis)
        // + two guards (< 0 and > 16) clamp out-of-range values to RC_SEVERE
        // + a final unguarded type pattern catches the remaining 1/2/3/5/6/7/
        //   9/10/11/13/14/15 cases by returning the value as-is.
        int exitCode = switch (rc) {
            case null -> RC_SEVERE;
            case 0 -> 0;
            case 4 -> 4;
            case 8 -> 8;
            case 12 -> 12;
            case 16 -> 16;
            case Integer i when i < 0 -> RC_SEVERE;
            case Integer i when i > 16 -> RC_SEVERE;
            case Integer i -> i;
        };
        System.exit(exitCode);
    }

    // ---------------------------------------------------------------------
    // Job body — three steps matching the JCL
    // ---------------------------------------------------------------------

    /**
     * Runs the three-step TRANBKP job body inside the {@link #BATCH_CTX}
     * scope. Visible for testing.
     *
     * <p>Step semantics:
     * <ol>
     *   <li><strong>STEP05R</strong> copies the TRANSACT KSDS to the next
     *       generation file in the BKUP GDG base directory. If the source
     *       file does not exist this method returns {@link #RC_WARN} and the
     *       subsequent steps are short-circuited; a recoverable I/O exception
     *       during the copy returns {@link #RC_ERROR}.</li>
     *   <li><strong>STEP05</strong> deletes the TRANSACT KSDS file, its
     *       {@code .schema} sidecar (cluster metadata), and its
     *       {@code .aix.schema} sidecar (alternate-index metadata) using
     *       {@link Files#deleteIfExists(Path)}.</li>
     *   <li><strong>STEP10</strong> writes a fresh {@code .schema} sidecar
     *       with the verbatim {@code DEFINE CLUSTER} attributes and creates
     *       an empty KSDS file. Skipped if the previous steps returned
     *       greater than {@link #RC_WARN} ({@code COND=(4,LT)}).</li>
     * </ol>
     *
     * @return the IDCAMS-style return code: {@link #RC_OK} on full success;
     *         {@link #RC_WARN} if the source file was missing; {@link #RC_ERROR}
     *         on a recoverable STEP05R failure
     * @throws IOException if any non-recoverable filesystem operation (delete
     *                     of the cluster, write of the schema sidecar, create
     *                     of the empty cluster) fails
     */
    static int execute() throws IOException {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("TRANBKP job starting; runId={}, processingDate={}, tenant={}, cluster={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant(), CLUSTER_NAME);

        // Resolve target paths from configuration. The KSDS source lives at the
        // configured TRANSACT path; the GDG generation files live under the
        // configured GDG root directory, namespaced by the verbatim GDG base
        // name AWS.M2.CARDDEMO.TRANSACT.BKUP.
        Path source = SafePathResolver.resolveTrusted(PROP_TRANSACT_PATH, DEFAULT_TRANSACT_PATH)
                .toAbsolutePath();
        Path gdgRoot = SafePathResolver.resolveTrusted(PROP_GDG_ROOT, DEFAULT_GDG_ROOT)
                .toAbsolutePath();
        Path bkupBaseDir = gdgRoot.resolve(BKUP_GDG_BASE);

        // ============================================================
        // STEP05R: REPROC — copy TRANSACT → TRANSACT.BKUP(+1)
        // ============================================================
        // The mainframe REPROC procedure (app/proc/REPROC.prc) invokes IDCAMS
        // with a REPRO control card from &CNTLLIB(REPROCT) — the effective
        // semantic is "copy FILEIN to FILEOUT byte-for-byte". A single
        // Files.copy preserves the byte-level contract exactly.
        // Recoverable I/O exceptions are caught here so that STEP10's
        // skip-if-prior-rc>4 condition can still be evaluated; severe runtime
        // exceptions (NPE, etc.) also reach this branch and degrade to
        // RC_ERROR rather than aborting the JVM.
        int rcStep05R;
        try {
            Files.createDirectories(bkupBaseDir);
            int nextGen = computeNextGeneration(bkupBaseDir);
            String genName = String.format(Locale.ROOT, GENERATION_NAME_FORMAT, nextGen);
            Path bkupTarget = bkupBaseDir.resolve(genName);
            if (!Files.exists(source)) {
                LOG.warn("STEP05R: REPRO source {} missing — skipping backup, returning CC={}",
                        source, RC_WARN);
                rcStep05R = RC_WARN;
            } else {
                Files.copy(source, bkupTarget);
                LOG.info("STEP05R: REPRO OK, source={}, target={}, generation={}, bytes={}",
                        source, bkupTarget, genName, Files.size(bkupTarget));
                rcStep05R = RC_OK;
            }
        } catch (Exception e) {
            LOG.error("STEP05R failed: {}", e.getMessage(), e);
            rcStep05R = RC_ERROR;
        }

        // ============================================================
        // STEP05: DELETE — conditional COND=(0,NE,STEP05R) — skip if STEP05R != 0
        // ============================================================
        // The COBOL JCL chain implies that if STEP05R does not produce a
        // backup, the live cluster must not be deleted. The source JCL omits
        // an explicit COND on STEP05 (a known mainframe-author convention is
        // to rely on deck ordering and operator override), but the safe and
        // idiom-preserving translation gates STEP05 on STEP05R == 0.
        if (rcStep05R != RC_OK) {
            LOG.info("STEP05 SKIPPED (COND=(0,NE,STEP05R), STEP05R rc={})", rcStep05R);
        } else {
            // IDCAMS DELETE CLUSTER is idempotent thanks to the
            // IF MAXCC LE 08 THEN SET MAXCC=0 swallow in the JCL.
            // Files.deleteIfExists is the natural Java translation:
            // returns true if the file existed and was deleted, false if it
            // did not exist; never throws on absence.
            boolean clusterDeleted = Files.deleteIfExists(source);
            Path clusterSchema = source.resolveSibling(source.getFileName() + ".schema");
            boolean schemaDeleted = Files.deleteIfExists(clusterSchema);
            Path aixSchema = source.resolveSibling(source.getFileName() + ".aix.schema");
            boolean aixDeleted = Files.deleteIfExists(aixSchema);
            LOG.info("STEP05: DELETE OK cluster={} (clusterDeleted={}, schemaDeleted={}); "
                            + "aix={} (aixDeleted={})",
                    CLUSTER_NAME, clusterDeleted, schemaDeleted, AIX_NAME, aixDeleted);
        }

        // ============================================================
        // STEP10: DEFINE EMPTY CLUSTER — conditional COND=(4,LT) — skip if max prior > 4
        // ============================================================
        // The source JCL's COND=(4,LT) means "skip this step if the highest
        // return code from any prior step is greater than 4". In our two-prior
        // step chain the highest prior RC equals rcStep05R (STEP05 either
        // ran successfully — leaving the chain RC at the STEP05R value — or
        // was skipped, also leaving it at the STEP05R value).
        int maxPriorRc = rcStep05R;
        if (maxPriorRc > RC_WARN) {
            LOG.info("STEP10 SKIPPED (COND=(4,LT), max prior rc={})", maxPriorRc);
        } else {
            // Ensure the parent directory exists before writing the schema or
            // creating the empty data file. Path.getParent() can return null
            // when the source is a bare filename with no separator; that case
            // is rare in production but worth guarding.
            Path parent = source.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Write the schema sidecar with the verbatim DEFINE CLUSTER
            // attributes from the source JCL. The format mirrors the
            // DUSRSECJ schema sidecar (key=value, one per line, LF-separated)
            // so downstream tooling can validate the cluster layout uniformly.
            Path schema = source.resolveSibling(source.getFileName() + ".schema");
            Files.writeString(schema,
                    "cluster=" + CLUSTER_NAME + System.lineSeparator()
                            + "keyLength=" + KEY_LENGTH + System.lineSeparator()
                            + "keyOffset=" + KEY_OFFSET + System.lineSeparator()
                            + "recordLength=" + RECORD_LENGTH + System.lineSeparator()
                            + "volumes=" + VOLUMES + System.lineSeparator()
                            + "cylinders=" + CYLINDERS + System.lineSeparator()
                            + "shareOptions=" + SHARE_OPTIONS + System.lineSeparator()
                            + "erase=true" + System.lineSeparator()
                            + "indexed=true" + System.lineSeparator()
                            + "dataComponent=" + CLUSTER_NAME + ".DATA" + System.lineSeparator()
                            + "indexComponent=" + CLUSTER_NAME + ".INDEX" + System.lineSeparator());
            // Create the empty cluster data file. Files.createFile throws
            // FileAlreadyExistsException if the file already exists; an
            // explicit exists-check guards against that case (e.g., a stale
            // file left over by a prior failed run that did not reach STEP05).
            if (!Files.exists(source)) {
                Files.createFile(source);
            }
            LOG.info("STEP10: DEFINE EMPTY OK cluster={}, KEYS({},{}), "
                            + "RECORDSIZE({},{}), VOLUMES({}), SHAREOPTIONS({}), ERASE INDEXED",
                    CLUSTER_NAME, KEY_LENGTH, KEY_OFFSET,
                    RECORD_LENGTH, RECORD_LENGTH, VOLUMES, SHARE_OPTIONS);
        }

        LOG.info("TRANBKP job complete; rc={}", rcStep05R);
        return rcStep05R;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Computes the next GDG generation index (1-based) for the given GDG base
     * directory. Scans the directory for files matching the
     * {@link #GENERATION_NAME_REGEX} pattern, parses the 4-digit generation
     * number from each, returns one more than the maximum (or {@code 1} if no
     * generations exist yet).
     *
     * <p>The directory listing is wrapped in a try-with-resources block so
     * that the underlying directory-stream OS handle is closed deterministically,
     * matching the {@link Files#list(Path)} contract.
     *
     * <p>Non-matching entries (e.g., hidden files, prior failed-write
     * scratch files) are silently ignored &mdash; they do not interfere with
     * the next-generation computation.
     *
     * <p>Visible for testing.
     *
     * @param baseDir the GDG base directory; must exist and be readable. Must
     *                not be {@code null}.
     * @return the 1-based generation index to use for the next backup
     * @throws IOException if the directory cannot be listed
     */
    static int computeNextGeneration(Path baseDir) throws IOException {
        try (Stream<Path> entries = Files.list(baseDir)) {
            int max = entries
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.matches(GENERATION_NAME_REGEX))
                    .mapToInt(name -> Integer.parseInt(name.substring(1, 5)))
                    .max()
                    .orElse(0);
            return max + 1;
        }
    }

    /**
     * Resolves a configuration property by consulting (in precedence order)
     * the JVM environment variable derived from {@code key}, then the JVM
     * system property {@code key}, then the provided {@code defaultValue}.
     *
     * <p>The environment-variable key is derived from {@code key} by
     * upper-casing (with {@link Locale#ROOT}) and replacing every {@code '.'}
     * and {@code '-'} with {@code '_'} &mdash; i.e.,
     * {@code "carddemo.file.transact.path"} maps to
     * {@code "CARDDEMO_FILE_TRANSACT_PATH"}. This matches the convention used
     * by the sibling {@link UsersSecuritySeedApp}, {@link DefineGdgApp}, and
     * {@link OpenFileApp} classes.
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
        return System.getProperty(key, defaultValue);
    }
}
