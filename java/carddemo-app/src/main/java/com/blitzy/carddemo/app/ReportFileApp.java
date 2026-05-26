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
// exported by the java.base module. This brings into scope:
//   * java.nio.file.Path, java.nio.file.Files — filesystem GDG base directory
//     creation and ".retention" file writing (AAP §0.6.5 mandate to use
//     java.nio.file, never java.io.File).
//   * java.lang.ScopedValue — JEP 506 thread-scoped binding of
//     BatchRunContext. Note: ScopedValue was moved from its preview-status
//     location (java.util.concurrent.ScopedValue) to java.lang when JEP 506
//     was finalized in Java 25.
//   * java.io.IOException — checked exception type propagated by execute().
//   * java.util.Locale — ROOT-locale environment-variable key normalization.
//   * Core java.lang types (String, Integer, etc.) — implicit auto-import.
import module java.base;

import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/REPTFILE.jcl}
 * &mdash; an IDCAMS job that defines the {@code AWS.M2.CARDDEMO.TRANREPT}
 * Generation Data Group (GDG) base with {@code LIMIT(10)} and no
 * {@code SCRATCH} keyword.
 *
 * <h2>Source artifact (verbatim from {@code app/jcl/REPTFILE.jcl})</h2>
 * <pre>{@code
 * //STEP05 EXEC PGM=IDCAMS
 * //SYSPRINT DD   SYSOUT=*
 * //SYSIN    DD   *
 *    DEFINE GENERATIONDATAGROUP -
 *    (NAME(AWS.M2.CARDDEMO.TRANREPT) -
 *     LIMIT(10) -
 *    )
 * /*
 * }</pre>
 *
 * <h2>Preserved JCL inconsistency &mdash; SAME GDG, DIFFERENT settings</h2>
 * <p><strong>NOTE:</strong> {@code REPTFILE.jcl} defines <strong>the same
 * GDG base</strong> ({@code AWS.M2.CARDDEMO.TRANREPT}) as
 * {@code app/jcl/DEFGDGB.jcl}, but with <strong>different attributes</strong>:
 * <ul>
 *   <li>{@code REPTFILE.jcl} &rarr; {@code LIMIT(10)} <em>without</em>
 *       {@code SCRATCH}.</li>
 *   <li>{@code DEFGDGB.jcl}  &rarr; {@code LIMIT(5) SCRATCH}.</li>
 * </ul>
 *
 * <p>This is a JCL inconsistency present in the original COBOL source
 * tree. Per AAP &sect;0.7.1 (<em>"If a COBOL paragraph contains dead code or
 * obvious bugs, translate it faithfully and flag it in a
 * MIGRATION_NOTES.md; do not 'fix' it in this refactor"</em>) the
 * inconsistency is preserved verbatim: this app uses {@code LIMIT(10)}
 * without {@code SCRATCH}, regardless of what {@code DefineGdgApp} may have
 * previously written. The conflict is logged in
 * {@code java/MIGRATION_NOTES.md}.
 *
 * <h2>Translation strategy &mdash; idiom-for-idiom (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>The {@code DEFINE GENERATIONDATAGROUP NAME(...)} clause becomes a
 *       {@link java.nio.file.Files#createDirectories(Path, java.nio.file.attribute.FileAttribute...)}
 *       call on a directory whose name is the GDG dataset name. The dataset
 *       name {@code AWS.M2.CARDDEMO.TRANREPT} is preserved verbatim
 *       (uppercase, dots and all) because downstream JCL-translated apps
 *       (notably {@code TransactionReportApp}, which is the producer of
 *       {@code TRANREPT(+1)} generations) reference this directory by that
 *       exact name.</li>
 *   <li>The {@code LIMIT(10)} attribute is recorded in a sidecar
 *       {@code .retention} file inside the GDG directory. Per AAP
 *       &sect;0.7.1 the value is preserved verbatim; <strong>generation
 *       roll-off is NOT enforced</strong> by this app (the IDCAMS catalog
 *       roll-off behavior is a follow-up effort, flagged in
 *       {@code MIGRATION_NOTES.md}).</li>
 *   <li>The absence of the {@code SCRATCH} keyword is recorded as
 *       {@code scratch=false} in the {@code .retention} sidecar so that a
 *       future roll-off implementation can consult the file and apply
 *       (or skip) the physical-delete semantics correctly.</li>
 *   <li>The job is idempotent: if the GDG directory already exists (e.g.
 *       a prior run of either {@code DefineGdgApp} or this app), the
 *       {@code .retention} file is overwritten with the
 *       {@code REPTFILE.jcl}-specific values. This faithfully reproduces
 *       the z/OS IDCAMS behavior in which a later {@code DEFINE} of the
 *       same GDG with different attributes wins the catalog entry. This
 *       observable behavior is part of the preserved-inconsistency
 *       contract (AAP &sect;0.7.1).</li>
 * </ul>
 *
 * <h2>{@code ScopedValue} batch-run context (JEP 506, AAP &sect;0.6.6)</h2>
 * <p>{@link #main(String[])} materializes a {@link BatchRunContext} via
 * {@link BatchRunContext#fromEnvironment()} and binds it through both
 * {@link #BATCH_CTX} (this app's own ScopedValue) and the shared
 * {@link BatchRunContext#BATCH_CTX} so that {@link #execute()} and any
 * virtual threads it spawns can read the context via either binding.
 * Per AAP &sect;0.6.6 and &sect;0.7.4, {@link ThreadLocal} is
 * <strong>forbidden</strong> in new CardDemo Java code.
 *
 * <h2>Runtime configuration</h2>
 * <p>The base path under which the GDG directory is created is resolved with
 * the 12-factor precedence (env var over system property over default):
 * <ul>
 *   <li>{@code CARDDEMO_GDG_ROOT} (env var) &mdash; highest precedence.</li>
 *   <li>{@code carddemo.gdg.root} (JVM system property) &mdash; second.</li>
 *   <li>{@code ./data/gdg} &mdash; compiled-in default.</li>
 * </ul>
 * <p>This matches the canonical convention used by {@code DefineGdgApp},
 * {@code CombineTransactionsApp}, and the other sibling
 * {@code carddemo-app} entry points.
 *
 * <h2>Process-exit semantics</h2>
 * <p>The COBOL job emits return code {@code 0} on success. This main mirrors
 * that contract:
 * <ul>
 *   <li>{@code 0} &mdash; success (directory created or already present,
 *       {@code .retention} written).</li>
 *   <li>{@code 16} &mdash; uncaught exception (any
 *       {@link java.io.IOException} or other failure during directory
 *       creation or {@code .retention} writing). {@code 16} is the
 *       conventional JCL terminal-failure severity ceiling.</li>
 *   <li>{@code 4}, {@code 8}, {@code 12} &mdash; reserved for future
 *       severity levels; pass through unchanged when returned by
 *       {@link #execute()}.</li>
 * </ul>
 * <p>Any out-of-range value returned by {@link #execute()} is clamped to
 * {@code 16}. Implementation note: the exit-code clamping switch uses a
 * plain {@code int} selector with a {@code default} branch &mdash;
 * <strong>not</strong> a pattern-matching switch on boxed {@code Integer}
 * &mdash; because JEP 507 (Primitive Patterns) is a preview feature and is
 * explicitly forbidden by AAP &sect;0.7.4.
 *
 * <h2>Non-goals (AAP &sect;0.2.2, &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring, Lombok, or Apache Commons.</li>
 *   <li>No reflection.</li>
 *   <li>No {@code java.io.File} &mdash; {@link java.nio.file} only per AAP
 *       &sect;0.6.5.</li>
 *   <li>No preview features (no {@code --enable-preview} required).</li>
 *   <li>No {@link ThreadLocal} &mdash; {@link java.lang.ScopedValue} only
 *       per AAP &sect;0.6.6.</li>
 *   <li>No generation roll-off enforcement &mdash; the {@code LIMIT(10)}
 *       attribute is recorded but not acted upon. A follow-up effort.</li>
 * </ul>
 *
 * <h2>Shaded artifact</h2>
 * <p>Per AAP &sect;0.4.1, this composition root is packaged as a single
 * shaded jar ({@code carddemo-report-file.jar}) by the
 * {@code maven-shade-plugin} configuration in
 * {@code java/carddemo-app/pom.xml}.
 *
 * @see com.blitzy.carddemo.batch.BatchRunContext
 * @see CobolProgram
 * @see DefineGdgApp
 * @see <a href="https://www.ibm.com/docs/en/zos/3.1.0?topic=services-generation-data-groups">z/OS Generation Data Groups</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "IDCAMS (REPTFILE.jcl)",
        sourcePath = "app/jcl/REPTFILE.jcl",
        translationDate = "2025-10-24",
        notes = "JCL translation of the IDCAMS DEFINE GENERATIONDATAGROUP step "
                + "that defines AWS.M2.CARDDEMO.TRANREPT with LIMIT(10) and NO "
                + "SCRATCH. PRESERVED INCONSISTENCY per AAP §0.7.1: the same "
                + "GDG base is also defined by DEFGDGB.jcl with LIMIT(5) "
                + "SCRATCH; this translation faithfully reproduces both jobs' "
                + "behavior — whichever runs last wins the .retention sidecar, "
                + "matching the z/OS IDCAMS catalog-overwrite semantics. The "
                + "inconsistency is documented in java/MIGRATION_NOTES.md. "
                + "Generation roll-off is NOT enforced; LIMIT is recorded for a "
                + "follow-up effort. Exit code 0 on success, 16 on uncaught "
                + "exception.")
public final class ReportFileApp {

    // ------------------------------------------------------------------
    // Logger
    // ------------------------------------------------------------------

    /**
     * SLF4J logger for this main. All lifecycle messages (job start,
     * idempotent re-run detection, {@code DEFINE GDG} completion, exit
     * status) are written here at {@code INFO}. Uncaught exceptions are
     * logged at {@code ERROR} prior to the {@code System.exit(16)} call
     * in {@link #main(String[])}.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ReportFileApp.class);

    // ------------------------------------------------------------------
    // ScopedValue for batch-run context (AAP §0.6.6 / JEP 506 Final)
    // ------------------------------------------------------------------

    /**
     * Thread-scoped binding holding the active {@link BatchRunContext}
     * for the current {@code ReportFileApp} run. Established at
     * {@link #main(String[])} entry via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(...)} and read by
     * {@link #execute()} via {@link #BATCH_CTX BATCH_CTX.get()}.
     *
     * <p>Per AAP &sect;0.6.6 and &sect;0.7.4, this {@link ScopedValue}
     * <strong>replaces {@link ThreadLocal} entirely</strong>; the binding
     * is automatically cleared when the
     * {@code ScopedValue.where(...).call(...)} call returns.
     *
     * <p>Although the {@link BatchRunContext} class itself exposes a
     * sibling {@link BatchRunContext#BATCH_CTX} field used by the shared
     * {@code carddemo-batch} drivers, this app declares its own
     * {@code ScopedValue} so that the lifecycle of the
     * {@code ReportFileApp} binding is scoped strictly to the
     * {@code ReportFileApp} call chain &mdash; per AAP &sect;0.4.1's
     * one-class-per-JCL-step pattern. Both bindings are populated with
     * the same {@link BatchRunContext} value within {@link #main(String[])}
     * so callees that read either field observe identical context.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // ------------------------------------------------------------------
    // Compile-time constants from the source JCL
    // ------------------------------------------------------------------

    /**
     * The GDG base dataset name from {@code app/jcl/REPTFILE.jcl} line
     * {@code NAME(AWS.M2.CARDDEMO.TRANREPT)}. Preserved verbatim
     * (uppercase, dots) per AAP &sect;0.7.1 because downstream
     * JCL-translated apps reference this directory by that exact name.
     */
    static final String GDG_BASE_NAME = "AWS.M2.CARDDEMO.TRANREPT";

    /**
     * The GDG generation limit from {@code app/jcl/REPTFILE.jcl} line
     * {@code LIMIT(10)}. Recorded in the {@code .retention} sidecar but
     * <strong>not enforced</strong> (generation roll-off is a follow-up
     * effort).
     *
     * <p>This value <strong>intentionally differs</strong> from the
     * {@code LIMIT(5)} value used in {@code DEFGDGB.jcl} for the same GDG
     * base. See class Javadoc for the documented inconsistency.
     */
    static final int GDG_LIMIT = 10;

    /**
     * Whether the GDG should physically delete (scratch) rolled-off
     * generations. {@code REPTFILE.jcl} does <strong>not</strong> specify
     * the {@code SCRATCH} keyword, so this value is {@code false}.
     *
     * <p>This value <strong>intentionally differs</strong> from the
     * {@code SCRATCH} setting used in {@code DEFGDGB.jcl} for the same
     * GDG base. See class Javadoc for the documented inconsistency.
     */
    static final boolean GDG_SCRATCH = false;

    /**
     * Filename of the sidecar file that records the GDG attributes
     * ({@code LIMIT}, {@code SCRATCH}) for a future roll-off
     * implementation. The dot-prefix marks it as a hidden file on
     * POSIX filesystems so it does not appear in casual {@code ls}
     * listings of generation files (which are named
     * {@code G####V00}).
     */
    static final String RETENTION_FILENAME = ".retention";

    // ------------------------------------------------------------------
    // Configuration keys (12-factor)
    // ------------------------------------------------------------------

    /**
     * Configuration key for the directory under which the GDG-base
     * sub-directory is created. Resolved with the 12-factor precedence
     * (env var {@code CARDDEMO_GDG_ROOT} over system property
     * {@code carddemo.gdg.root} over the default
     * {@link #DEFAULT_GDG_ROOT}). Matches the canonical convention used
     * by every other GDG-aware sibling in {@code carddemo-app}.
     */
    static final String PROP_GDG_ROOT = "carddemo.gdg.root";

    /**
     * Default GDG root if neither env var nor system property supplies a
     * value. Relative paths are resolved against the JVM working
     * directory at the time of {@link #execute()}.
     */
    static final String DEFAULT_GDG_ROOT = "./data/gdg";

    // ------------------------------------------------------------------
    // Construction policy
    // ------------------------------------------------------------------

    /**
     * Private constructor to prevent instantiation. {@code ReportFileApp}
     * is a process entry point invoked exclusively via
     * {@link #main(String[])}; it carries no per-instance state.
     */
    private ReportFileApp() {
        // utility class — instantiation is meaningless
    }

    // ------------------------------------------------------------------
    // Process entry point
    // ------------------------------------------------------------------

    /**
     * Process entry point for the shaded {@code carddemo-report-file.jar}.
     * Materializes a {@link BatchRunContext} from JVM properties and
     * environment variables, binds it through both {@link #BATCH_CTX}
     * and {@link BatchRunContext#BATCH_CTX}, and dispatches the GDG
     * directory creation via {@link #execute()}.
     *
     * <p>Exit codes (JCL-conventional severity scale):
     * <ul>
     *   <li>{@code 0} &mdash; success (directory created or already
     *       present, {@code .retention} sidecar written).</li>
     *   <li>{@code 4}, {@code 8}, {@code 12} &mdash; reserved for future
     *       severity levels; pass through unchanged when returned by
     *       {@link #execute()}.</li>
     *   <li>{@code 16} &mdash; uncaught exception, or any {@code rc} value
     *       returned by {@link #execute()} that is outside
     *       {@code [0, 16]}. Per JCL convention {@code 16} is the
     *       conventional terminal-failure severity.</li>
     * </ul>
     *
     * <p>Args are accepted but currently ignored; the future evolution is
     * intentionally left open (e.g.&nbsp;a {@code --dry-run} flag could
     * report what would be created without modifying the filesystem).
     *
     * @param args command-line arguments; currently ignored
     */
    public static void main(String[] args) {
        // 1. Materialize the batch-run context from JVM properties + env vars.
        //    fromEnvironment() never returns null and never throws on missing
        //    configuration; it generates a UUID-based runId and defaults
        //    processingDate to LocalDate.now() per the documented contract in
        //    BatchRunContext.
        BatchRunContext ctx = BatchRunContext.fromEnvironment();

        // 2. Bind the context through both ScopedValues (this app's own
        //    BATCH_CTX and the shared BatchRunContext.BATCH_CTX) so any
        //    callee — whether it reads our binding or the shared one —
        //    observes identical state. Nesting the where(...) carriers is
        //    the AAP §0.6.6 / JEP 506 idiom; both bindings have the same
        //    lifetime as the .call(...) invocation.
        int rc;
        try {
            rc = ScopedValue
                    .where(BATCH_CTX, ctx)
                    .where(BatchRunContext.BATCH_CTX, ctx)
                    .call(ReportFileApp::execute);
        } catch (Exception e) {
            // Catch-all: any unchecked exception thrown by execute(), or
            // any checked exception declared by ScopedValue.call(...)
            // (notably IOException from Files.createDirectories /
            // Files.writeString) lands here.
            LOG.error("REPTFILE job failed with uncaught exception", e);
            rc = 16;
        }

        // 3. Clamp the return code to the conventional JCL severity range
        //    [0, 16]. Implemented as a plain `int` switch with a default
        //    branch — NOT a pattern-matching switch on Integer — because
        //    JEP 507 (Primitive Patterns) is a preview feature and is
        //    explicitly FORBIDDEN by AAP §0.7.4. The semantics of this
        //    switch are equivalent to:
        //        (rc < 0 || rc > 16) ? 16 : rc
        //    but the switch form makes the conventional COBOL/JCL return
        //    codes (0, 4, 8, 12, 16) explicit at the call site.
        int exitCode = switch (rc) {
            case 0, 4, 8, 12, 16 -> rc;
            default -> {
                if (rc > 0 && rc < 16) {
                    // Non-standard but in-range: pass through unchanged so
                    // a future caller that returns, e.g., rc=2 is not
                    // silently clobbered.
                    yield rc;
                }
                // Negative or > 16: clamp to the JCL terminal-failure
                // severity ceiling.
                yield 16;
            }
        };
        System.exit(exitCode);
    }

    // ------------------------------------------------------------------
    // Scoped-value callee
    // ------------------------------------------------------------------

    /**
     * Creates the {@code AWS.M2.CARDDEMO.TRANREPT} GDG-base directory
     * under the resolved GDG root and writes the {@code .retention}
     * sidecar file with the {@code LIMIT(10)} / no-SCRATCH attributes
     * from {@code REPTFILE.jcl}.
     *
     * <p>Idempotency contract:
     * <ul>
     *   <li>If the directory does not exist, it is created (along with
     *       all missing parent directories) and the creation is logged
     *       at {@code INFO}.</li>
     *   <li>If the directory already exists (e.g. a prior run of either
     *       this app or {@code DefineGdgApp}), this is logged at
     *       {@code INFO} and treated as success &mdash; the Java analog
     *       of the IDCAMS {@code IF LASTCC=12 THEN SET MAXCC=0}
     *       "already exists is OK" idiom.</li>
     *   <li>In <strong>both</strong> cases the {@code .retention} sidecar
     *       is overwritten with {@code REPTFILE.jcl}'s
     *       {@code LIMIT(10)} / no-SCRATCH values. This faithfully
     *       reproduces the z/OS IDCAMS behavior in which a later
     *       {@code DEFINE} of the same GDG with different attributes
     *       wins the catalog entry &mdash; whichever of {@code DEFGDGB}
     *       and {@code REPTFILE} runs last sets the active attributes.
     *       Per AAP &sect;0.7.1 this inconsistency is preserved
     *       deliberately and is documented in
     *       {@code java/MIGRATION_NOTES.md}.</li>
     * </ul>
     *
     * <p>Returns {@code 0} on success. Any {@link IOException} is
     * propagated to the caller (which catches it in
     * {@link #main(String[])} and maps it to exit code {@code 16}).
     *
     * @return {@code 0} on success
     * @throws IOException if the GDG directory cannot be created or if
     *                     the {@code .retention} sidecar cannot be
     *                     written
     */
    private static int execute() throws IOException {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info(
                "REPTFILE job starting; runId={}, processingDate={}, tenant={}, gdgBase={}, limit={}, scratch={}",
                ctx.runId(),
                ctx.processingDate(),
                ctx.tenant(),
                GDG_BASE_NAME,
                GDG_LIMIT,
                GDG_SCRATCH);

        // Resolve the GDG root with 12-factor precedence (env var first,
        // system property second, compiled-in default last).
        Path gdgRoot = SafePathResolver.resolveTrusted(PROP_GDG_ROOT, DEFAULT_GDG_ROOT);
        Path baseDir = gdgRoot.resolve(GDG_BASE_NAME);

        if (Files.exists(baseDir)) {
            // Idempotent re-run: directory already present. Per AAP §0.7.1
            // the .retention sidecar is OVERWRITTEN below with REPTFILE.jcl's
            // attributes (LIMIT(10), no SCRATCH) to faithfully reproduce the
            // z/OS IDCAMS catalog-overwrite semantics. This is the documented
            // "REPTFILE may run after DEFGDGB and clobber its LIMIT(5) SCRATCH"
            // path; see java/MIGRATION_NOTES.md.
            LOG.info(
                    "GDG base already exists (idempotent re-run): name={}, dir={}",
                    GDG_BASE_NAME,
                    baseDir);
        } else {
            // First-time create: ensure all parent directories exist as well.
            Files.createDirectories(baseDir);
            LOG.info(
                    "DEFINE GDG OK: name={}, limit={}, scratch={}, dir={}",
                    GDG_BASE_NAME,
                    GDG_LIMIT,
                    GDG_SCRATCH,
                    baseDir);
        }

        // Write the .retention sidecar with the LIMIT/SCRATCH attributes from
        // REPTFILE.jcl. Format: two key=value lines, LF-terminated, UTF-8 — a
        // text format any future roll-off implementation (or operator) can
        // read with java.util.Properties or a simple parser.
        Path retentionFile = baseDir.resolve(RETENTION_FILENAME);
        String retentionBody = "limit=" + GDG_LIMIT + "\nscratch=" + GDG_SCRATCH + "\n";
        Files.writeString(retentionFile, retentionBody);
        LOG.info(
                "Wrote retention sidecar: file={}, limit={}, scratch={}",
                retentionFile,
                GDG_LIMIT,
                GDG_SCRATCH);

        LOG.info("REPTFILE job complete; rc=0");
        return 0;
    }

    // ------------------------------------------------------------------
    // 12-factor configuration helpers
    // ------------------------------------------------------------------

    /**
     * Resolves a configuration value with the canonical 12-factor
     * precedence used across the {@code carddemo-app} module:
     * <ol>
     *   <li>Environment variable, looked up by uppercasing {@code key}
     *       (using {@link Locale#ROOT} to avoid locale-dependent
     *       surprises such as the Turkish dotless-i transformation) and
     *       substituting underscores for dots and hyphens. Example:
     *       {@code carddemo.gdg.root} maps to {@code CARDDEMO_GDG_ROOT}.</li>
     *   <li>JVM system property looked up by the supplied {@code key}.</li>
     *   <li>The supplied {@code defaultValue}.</li>
     * </ol>
     *
     * <p>Blank values (per {@link String#isBlank()}) at the env-var or
     * system-property layer are treated as if the value were absent, so
     * that {@code FOO=""} on a CI runner does not silently clobber the
     * default.
     *
     * @param key          dotted-lowercase property key (e.g.
     *                     {@code carddemo.gdg.root}); must not be
     *                     {@code null}
     * @param defaultValue compiled-in default if neither env var nor
     *                     system property supplies a non-blank value;
     *                     may be {@code null} only if the caller is
     *                     prepared to handle a {@code null} return
     * @return the resolved value, or {@code defaultValue} if neither
     *         source provides a non-blank value
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
