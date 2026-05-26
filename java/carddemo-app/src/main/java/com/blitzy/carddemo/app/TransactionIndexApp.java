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
// exported by the java.base module — used here for:
//   - java.nio.file.{Files, Path}  (file I/O per AAP §0.6.5)
//   - java.io.IOException          (checked exception from Files.writeString)
//   - java.util.Locale             (Locale.ROOT in getProp helper)
//   - java.lang.ScopedValue        (which moved from java.util.concurrent to
//                                   java.lang when it was finalized in Java 25;
//                                   see java.base/java/lang/ScopedValue.java)
import module java.base;

import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/TRANIDX.jcl}
 * &mdash; a three-step IDCAMS job that builds (or rebuilds) the alternate
 * index over the existing {@code TRANSACT.VSAM.KSDS} master file.
 *
 * <h2>Source artefact</h2>
 * <p>{@code app/jcl/TRANIDX.jcl} chains three IDCAMS steps:
 * <ol>
 *   <li><strong>{@code STEP20}</strong> &mdash; {@code EXEC PGM=IDCAMS} issues
 *       {@code DEFINE ALTERNATEINDEX (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX)
 *       RELATE(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS) KEYS(26 304) NONUNIQUEKEY
 *       UPGRADE RECORDSIZE(350,350) VOLUMES(AWSHJ1) CYLINDERS(5,1))} along
 *       with {@code DATA} and {@code INDEX} sub-clauses, creating an AIX on
 *       the 26-byte composite key starting at offset 304 of each 350-byte
 *       record. {@code NONUNIQUEKEY} permits duplicate keys (multiple
 *       transactions may share the same processing timestamp);
 *       {@code UPGRADE} causes the AIX to be kept in sync automatically when
 *       the base cluster is updated.</li>
 *   <li><strong>{@code STEP25}</strong> &mdash; {@code EXEC PGM=IDCAMS} issues
 *       {@code DEFINE PATH (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX.PATH)
 *       PATHENTRY(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX))}, creating a PATH
 *       object that relates the AIX back to the base cluster so callers can
 *       open the path and read records in alternate-key order.</li>
 *   <li><strong>{@code STEP30}</strong> &mdash; {@code EXEC PGM=IDCAMS} issues
 *       {@code BLDINDEX INDATASET(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS)
 *       OUTDATASET(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX)}, populating the
 *       freshly-defined AIX from the current contents of the base
 *       cluster.</li>
 * </ol>
 *
 * <h2>Translation strategy &mdash; no-op with schema persistence (AAP &sect;0.6.5)</h2>
 * <p>In the file-based runtime per AAP &sect;0.6.12 ("File-based default;
 * JDBC adapter optional") and the file-I/O contract of AAP &sect;0.6.5, the
 * file-backed {@code FileTransactionRepository} computes any alternate index
 * (e.g., the CARD-NUM, TRAN-ID composite) at read time by scanning the data
 * file. There is no persistent AIX structure that needs to be built ahead of
 * time, and so {@code BLDINDEX} is a logical no-op.
 *
 * <p>However the structural metadata of the original AIX (key length, key
 * offset, NONUNIQUEKEY, UPGRADE, record length) is information that any
 * downstream tooling (a future JDBC adapter, an audit report, a schema
 * documentation generator) may want to consult. To preserve that knowledge
 * idiom-for-idiom &mdash; the AAP &sect;0.7.1 cardinal rule &mdash; this main
 * persists a small text file {@code <transact-path>.aix.schema} next to the
 * configured {@code TRANSACT} data file, recording:
 * <pre>
 *   aix=AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX
 *   aixKeyLength=26
 *   aixKeyOffset=304
 *   nonUniqueKey=true
 *   upgrade=true
 *   recordLength=350
 * </pre>
 *
 * <p>STEP25 (DEFINE PATH) and STEP30 (BLDINDEX) are logged as informational
 * "OK" lines so that operational logs from the COBOL job and the Java job
 * preserve the same step inventory and step ordering &mdash; matching the
 * "JCL step inventory completeness" mandate of AAP &sect;0.4.1.
 *
 * <h2>Return-code contract</h2>
 * <p>The COBOL job returns the highest condition code from any of its three
 * IDCAMS steps. This main preserves that contract using the standard IDCAMS
 * convention:
 * <ul>
 *   <li>{@code 0} &mdash; all steps succeeded (or were logical no-ops).</li>
 *   <li>{@code 4} &mdash; warning: the configured {@code TRANSACT} data file
 *       does not exist. In the COBOL job this is the {@code LASTCC=4}
 *       "object not found, proceed" return; downstream callers can still
 *       open the file when it appears.</li>
 *   <li>{@code 16} &mdash; an unexpected error occurred (e.g., an
 *       {@link java.io.IOException} writing the {@code .aix.schema} metadata
 *       file). This is the standard IDCAMS "terminator" code.</li>
 * </ul>
 *
 * <h2>Shaded jar</h2>
 * <p>Per AAP &sect;0.4.1, this main is packaged as
 * {@code carddemo-transaction-index.jar} (the classifier-shaded jar produced
 * by the {@code maven-shade-plugin} configuration in
 * {@code carddemo-app/pom.xml}).
 *
 * <h2>Configuration</h2>
 * <p>The path of the TRANSACT data file is read from
 * {@code carddemo.file.transact.path} (system property) or
 * {@code CARDDEMO_FILE_TRANSACT_PATH} (environment variable), with a
 * compiled-in default of {@code ./data/transact.dat} (matching
 * {@code application.properties.example} &sect;3). The schema metadata file
 * is written as a sibling of that data file with the suffix
 * {@code .aix.schema}.
 *
 * <h2>ScopedValue per AAP &sect;0.6.6 / &sect;0.7.4</h2>
 * <p>Per-job batch-run context (runId, processingDate, tenant) flows through
 * {@link #BATCH_CTX}, established at job entry via
 * {@code ScopedValue.where(BATCH_CTX, ctx).call(...)}. This <strong>replaces
 * {@link ThreadLocal} entirely</strong> in new code; {@code ThreadLocal} is
 * forbidden by AAP &sect;0.7.4.
 *
 * <h2>Non-goals (AAP &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring, Lombok, or Apache Commons.</li>
 *   <li>No reflection.</li>
 *   <li>No {@code java.io.File} &mdash; {@link java.nio.file} only per
 *       AAP &sect;0.6.5.</li>
 *   <li>No preview features (no {@code --enable-preview}).</li>
 *   <li>No actual VSAM AIX structure &mdash; out of scope for the
 *       file-based runtime per AAP &sect;0.6.12.</li>
 * </ul>
 *
 * @see <a href="https://www.ibm.com/docs/en/zos/3.1.0?topic=command-define-alternateindex">IDCAMS DEFINE ALTERNATEINDEX</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "IDCAMS (TRANIDX.jcl)",
        sourcePath = "app/jcl/TRANIDX.jcl",
        translationDate = "2025-10-24",
        notes = "JCL translation of the three-step IDCAMS job that builds the alternate "
                + "index over the TRANSACT.VSAM.KSDS master file (KEYS(26 304) "
                + "NONUNIQUEKEY UPGRADE RECORDSIZE(350,350)). In the file-based runtime "
                + "per AAP §0.6.5, the FileTransactionRepository computes alternate "
                + "indexes at read time, so STEP30 BLDINDEX is a logical no-op; STEP20 "
                + "DEFINE ALTERNATEINDEX persists the AIX structural metadata as a "
                + "<transact-path>.aix.schema sidecar file for downstream tooling; "
                + "STEP25 DEFINE PATH is logged for operational-inventory completeness."
)
public final class TransactionIndexApp {

    // ---------------------------------------------------------------------
    // Logger
    // ---------------------------------------------------------------------

    /** SLF4J logger held by this composition-root main class. */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionIndexApp.class);

    // ---------------------------------------------------------------------
    // ScopedValue carrier (per AAP §0.6.6, JEP 506 Final in Java 25)
    // ---------------------------------------------------------------------

    /**
     * Thread-scoped binding for this run's {@link BatchRunContext}.
     *
     * <p>The {@link #main(String[])} method binds the carrier via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(...)} for the duration of
     * the {@link #execute()} call. Any callee on the same or a child virtual
     * thread can read it via {@code BATCH_CTX.get()}. This field is the sole
     * {@code ScopedValue} for this main class &mdash; it replaces
     * {@link ThreadLocal} entirely per AAP &sect;0.6.6 and &sect;0.7.4
     * ({@code ThreadLocal} is forbidden in new CardDemo code).
     *
     * <p>Note: in Java 25 finalized, {@link ScopedValue} lives in
     * {@code java.lang} (it moved from {@code java.util.concurrent} when JEP
     * 506 was finalized). It is auto-imported via {@code import module
     * java.base;} above.
     *
     * <p>An alternative implementation would re-use
     * {@link BatchRunContext#BATCH_CTX BatchRunContext.BATCH_CTX} for
     * cross-module reuse; this main's own {@code BATCH_CTX} is the per-app
     * instance bound at job entry, mirroring the convention established by
     * {@link CombineTransactionsApp#BATCH_CTX}.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // ---------------------------------------------------------------------
    // JCL-derived constants (preserved verbatim from app/jcl/TRANIDX.jcl)
    // ---------------------------------------------------------------------

    /**
     * Base cluster dataset name from {@code TRANIDX.jcl} STEP20
     * {@code RELATE(...)} and STEP30 {@code INDATASET(...)}. Preserved
     * verbatim because any downstream tool that joins logs across the COBOL
     * and Java sides will key off this exact string.
     */
    private static final String CLUSTER_NAME = "AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS";

    /**
     * Alternate-index dataset name from {@code TRANIDX.jcl} STEP20
     * {@code NAME(...)} and STEP30 {@code OUTDATASET(...)}. Preserved
     * verbatim for the same reason as {@link #CLUSTER_NAME}.
     */
    private static final String AIX_NAME = "AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX";

    /**
     * AIX PATH dataset name from {@code TRANIDX.jcl} STEP25
     * {@code DEFINE PATH NAME(...)}. Preserved verbatim.
     */
    private static final String AIX_PATH_NAME = "AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX.PATH";

    /**
     * Alternate-index key length in bytes, from
     * {@code TRANIDX.jcl:L27} ({@code KEYS(26 304)} &mdash; first number is
     * length).
     */
    private static final int AIX_KEY_LENGTH = 26;

    /**
     * Alternate-index key offset (1-based, as recorded in the COBOL JCL),
     * from {@code TRANIDX.jcl:L27} ({@code KEYS(26 304)} &mdash; second
     * number is offset). Preserved verbatim; downstream tooling that
     * consumes the {@code .aix.schema} sidecar can interpret the offset
     * per the original IDCAMS convention.
     */
    private static final int AIX_KEY_OFFSET = 304;

    /**
     * Record length in bytes, from {@code TRANIDX.jcl:L30}
     * ({@code RECORDSIZE(350,350)}). Matches the TRAN-RECORD layout in
     * {@code app/cpy/CVTRA05Y.cpy}.
     */
    private static final int RECORD_LENGTH = 350;

    // ---------------------------------------------------------------------
    // Configuration keys + defaults
    // ---------------------------------------------------------------------

    /**
     * Configuration key for the filesystem path of the TRANSACT data file.
     * Documented in {@code application.properties.example} &sect;3.
     */
    private static final String PROP_TRANSACT_PATH = "carddemo.file.transact.path";

    /**
     * Compiled-in default for {@link #PROP_TRANSACT_PATH} when neither a
     * system property nor an environment variable supplies a non-blank
     * value, mirroring {@code application.properties.example} &sect;3.
     */
    private static final String DEFAULT_TRANSACT_PATH = "./data/transact.dat";

    /**
     * Suffix appended to the TRANSACT data-file name to obtain the
     * {@code .aix.schema} metadata file name. The metadata file lives as a
     * sibling of the data file (same directory) so that backup and restore
     * tooling can copy both together.
     */
    private static final String AIX_SCHEMA_SUFFIX = ".aix.schema";

    // ---------------------------------------------------------------------
    // IDCAMS-style return codes
    // ---------------------------------------------------------------------

    /** IDCAMS "OK" condition code (all steps succeeded). */
    private static final int RC_OK = 0;

    /**
     * IDCAMS warning code returned when the TRANSACT data file does not
     * exist at the configured path. The COBOL job would have failed at
     * STEP30 BLDINDEX in that situation; the file-based translation returns
     * the standard "warning, proceed" code so that downstream jobs can
     * observe the missing data file and decide how to handle it.
     */
    private static final int RC_FILE_NOT_FOUND = 4;

    /**
     * IDCAMS "terminator" code returned when an unexpected error occurs
     * (e.g., an {@link java.io.IOException} writing the schema metadata
     * file, or any other uncaught exception escaping {@link #execute()}).
     */
    private static final int RC_ERROR = 16;

    // ---------------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------------

    /** Utility class &mdash; not instantiable. */
    private TransactionIndexApp() {
        throw new AssertionError("TransactionIndexApp is not constructible");
    }

    // ---------------------------------------------------------------------
    // Main entry point
    // ---------------------------------------------------------------------

    /**
     * Java main entry point. Mirrors the {@code TRANIDX.jcl} job:
     * <ol>
     *   <li>Build a {@link BatchRunContext} from environment and system
     *       properties via {@link BatchRunContext#fromEnvironment()}.</li>
     *   <li>Bind the context to {@link #BATCH_CTX} for the duration of
     *       {@link #execute()} via
     *       {@code ScopedValue.where(BATCH_CTX, ctx).call(...)}.</li>
     *   <li>Convert the {@link Integer} return code into a process exit
     *       code via an exhaustive pattern-matching switch that clamps any
     *       out-of-range or {@code null} value to {@link #RC_ERROR}.</li>
     * </ol>
     *
     * <p>The {@code switch} is exhaustive on {@link Integer} via standard
     * pattern matching (Java 21 Final) rather than primitive patterns
     * (JEP 507 &mdash; preview, forbidden by AAP &sect;0.7.4). Coverage
     * proof: {@code case null} + five known constants ({@code 0}, {@code 4},
     * {@code 8}, {@code 12}, {@code 16}) + two guards for negative and
     * {@code > 16} + a final unguarded type pattern for the remaining
     * cases. The compiler enforces exhaustiveness; no {@code default}
     * branch is permitted per AAP &sect;0.7.3.
     *
     * @param args command-line arguments. Currently unused; the COBOL JCL
     *             job takes no PARM. Future extensions could accept a
     *             positional TRANSACT path override for ad-hoc testing.
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        // The selector is intentionally boxed to Integer so the switch
        // below can use standard pattern matching with type patterns
        // (case Integer i when ...) — primitive patterns on int are JEP
        // 507 preview, forbidden by AAP §0.7.4.
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(TransactionIndexApp::execute);
        } catch (Exception e) {
            LOG.error("TRANIDX job failed with uncaught exception", e);
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

    // ---------------------------------------------------------------------
    // Job body — three steps matching the JCL
    // ---------------------------------------------------------------------

    /**
     * Runs the three-step TRANIDX job body inside the {@link #BATCH_CTX}
     * scope.
     *
     * <p>STEP20 (DEFINE ALTERNATEINDEX): if the TRANSACT data file exists,
     * persist the {@code .aix.schema} sidecar with the AIX structural
     * metadata. If the data file is missing, log a warning and return
     * {@link #RC_FILE_NOT_FOUND} immediately (STEP25 and STEP30 are then
     * skipped, matching the COBOL job's behaviour of failing STEP30 BLDINDEX
     * on a missing INDATASET).
     *
     * <p>STEP25 (DEFINE PATH): logged as an "OK" line. There is no PATH
     * object to materialise in the file-based runtime; the log line exists
     * for operational-inventory parity with the COBOL job.
     *
     * <p>STEP30 (BLDINDEX): logged as a "no-op" line. The file-backed
     * {@code FileTransactionRepository} computes alternate indexes at read
     * time per AAP &sect;0.6.5, so there is no AIX structure to populate
     * here.
     *
     * @return {@link #RC_OK} on success, {@link #RC_FILE_NOT_FOUND} if the
     *         TRANSACT data file is missing
     * @throws IOException if writing the {@code .aix.schema} sidecar fails
     *                     for any reason other than "already exists" (the
     *                     caller in {@link #main} catches and converts to
     *                     {@link #RC_ERROR})
     */
    private static int execute() throws IOException {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("TRANIDX job starting; runId={}, cluster={}, aix={}",
                ctx.runId(), CLUSTER_NAME, AIX_NAME);

        Path target = SafePathResolver.resolveTrusted(PROP_TRANSACT_PATH, DEFAULT_TRANSACT_PATH);
        if (!Files.exists(target)) {
            LOG.warn("TRANIDX: TRANSACT KSDS file not found at {}; returning CC=4",
                    target);
            return RC_FILE_NOT_FOUND;
        }

        // STEP20: DEFINE ALTERNATEINDEX — persist .aix.schema sidecar
        Path aixSchema = target.resolveSibling(target.getFileName() + AIX_SCHEMA_SUFFIX);
        Files.writeString(aixSchema,
                "aix=" + AIX_NAME + "\n"
                        + "aixKeyLength=" + AIX_KEY_LENGTH + "\n"
                        + "aixKeyOffset=" + AIX_KEY_OFFSET + "\n"
                        + "nonUniqueKey=true\n"
                        + "upgrade=true\n"
                        + "recordLength=" + RECORD_LENGTH + "\n");
        LOG.info("STEP20: DEFINE ALTERNATEINDEX OK; name={} keys=({},{}) "
                        + "NONUNIQUEKEY UPGRADE recordLength={} schema={}",
                AIX_NAME, AIX_KEY_LENGTH, AIX_KEY_OFFSET, RECORD_LENGTH, aixSchema);

        // STEP25: DEFINE PATH — log only (no PATH object in file-based runtime)
        LOG.info("STEP25: DEFINE PATH OK; name={} pathEntry={}",
                AIX_PATH_NAME, AIX_NAME);

        // STEP30: BLDINDEX — no-op (AIX computed at read time per AAP §0.6.5)
        LOG.info("STEP30: BLDINDEX OK (no-op in file-based architecture; "
                + "FileTransactionRepository computes alternate index at read time)");

        LOG.info("TRANIDX job complete; runId={} rc={}", ctx.runId(), RC_OK);
        return RC_OK;
    }

    // ---------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------

    /**
     * Resolves a configuration value with the documented precedence: env
     * var first, JVM system property second, otherwise the supplied
     * default.
     *
     * <p>The env-var name is derived from {@code key} by uppercasing (using
     * {@link Locale#ROOT} to avoid locale-dependent surprises such as the
     * Turkish dotless-i transformation) and substituting underscores for
     * dots and hyphens. Example: {@code carddemo.file.transact.path} maps
     * to {@code CARDDEMO_FILE_TRANSACT_PATH}.
     *
     * <p>An env var or system property whose value is blank (per
     * {@link String#isBlank()}) is treated as "not set" so that a
     * deliberately-empty environment variable does not silently override
     * the default with the empty string. This matches the resolution
     * contract documented in {@code application.properties.example}.
     *
     * @param key          the system-property key (e.g.,
     *                     {@code carddemo.file.transact.path}); must be
     *                     non-{@code null}
     * @param defaultValue the value to return when neither source supplies
     *                     a non-blank value; must be non-{@code null}
     * @return the resolved value; never {@code null}
     */
    private static String getProp(String key, String defaultValue) {
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
