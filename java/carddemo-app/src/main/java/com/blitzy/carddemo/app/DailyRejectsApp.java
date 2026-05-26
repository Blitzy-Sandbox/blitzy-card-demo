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
// exported by the java.base module — used here for java.nio.file.{Path, Files},
// java.io.IOException, java.util.Locale, and java.lang.ScopedValue (which
// moved from java.util.concurrent to java.lang when JEP 506 was finalized in
// Java 25; see java.base/java/lang/ScopedValue.java).
import module java.base;

import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/DALYREJS.jcl}
 * &mdash; an IDCAMS job whose sole step ({@code STEP05 EXEC PGM=IDCAMS})
 * defines the {@code AWS.M2.CARDDEMO.DALYREJS} Generation Data Group (GDG)
 * base with {@code LIMIT(5)} and {@code SCRATCH}. In the file-based runtime
 * established by AAP &sect;0.6.12 (&quot;File-based default; JDBC adapter
 * optional&quot;), the GDG base translates to an ordinary filesystem directory
 * with the dataset name preserved verbatim; later jobs (e.g.,
 * {@link PostTransactionsApp} via the CBTRN02C posting engine) write the
 * {@code +1} generation (a {@code G0001V00}-style file) into this directory.
 *
 * <h2>Source artefact</h2>
 * <p>The originating {@code app/jcl/DALYREJS.jcl} step body:
 * <pre>
 *   //STEP05 EXEC PGM=IDCAMS
 *   //SYSPRINT DD   SYSOUT=*
 *   //SYSIN    DD   *
 *      DEFINE GENERATIONDATAGROUP -
 *      (NAME(AWS.M2.CARDDEMO.DALYREJS) -
 *       LIMIT(5) -
 *       SCRATCH -
 *      )
 *   /*
 * </pre>
 *
 * <h2>Translation strategy &mdash; idiom-for-idiom (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>The {@code DEFINE GENERATIONDATAGROUP NAME(AWS.M2.CARDDEMO.DALYREJS)}
 *       clause becomes a {@link java.nio.file.Files#createDirectories(Path,
 *       java.nio.file.attribute.FileAttribute...)} call on a directory whose
 *       name is the dataset name {@code AWS.M2.CARDDEMO.DALYREJS}. The dataset
 *       name (uppercase, dots and all) is preserved verbatim per AAP
 *       &sect;0.6.12 z/OS naming conventions so downstream JCL-translated apps
 *       reference this directory by that exact name.</li>
 *   <li>The {@code LIMIT(5)} and {@code SCRATCH} attributes are recorded in a
 *       sidecar {@code .retention} text file under the directory. On the
 *       mainframe, the catalog manages GDG generation roll-off; in the
 *       file-based runtime, generation roll-off (if needed) is the
 *       responsibility of the writer of the GDG &mdash; a follow-up effort
 *       flagged in {@code MIGRATION_NOTES.md}. The initial milestone preserves
 *       the COBOL behaviour of &quot;directory exists, ready to receive
 *       generations&quot; and documents the attributes in metadata.</li>
 *   <li>The IDCAMS idiom {@code IF LASTCC=12 THEN SET MAXCC=0} (i.e.
 *       &quot;treat 'already exists' as success&quot;) is the natural semantic
 *       of {@link java.nio.file.Files#createDirectories(Path,
 *       java.nio.file.attribute.FileAttribute...)} which is idempotent and
 *       does not throw {@link java.nio.file.FileAlreadyExistsException} on a
 *       pre-existing directory. We additionally branch on
 *       {@link Files#exists(Path, java.nio.file.LinkOption...)} so the log
 *       output explicitly reports an idempotent re-run, matching the IDCAMS
 *       SYSPRINT &quot;already cataloged&quot; trace.</li>
 * </ul>
 *
 * <h2>Runtime configuration</h2>
 * <p>The base path under which the GDG directory is created is read from the
 * system property {@code carddemo.gdg.root} or the environment variable
 * {@code CARDDEMO_GDG_ROOT} (12-factor configuration per AAP &sect;0.7.2).
 * If neither is set, the JVM working directory's {@code ./data/gdg/}
 * subdirectory is used. This default matches the convention documented in
 * {@code application.properties.example}.
 *
 * <h2>BatchRunContext / ScopedValue (AAP &sect;0.6.6, JEP 506 Final)</h2>
 * <p>{@link #main(String[])} resolves a {@link BatchRunContext} via
 * {@link BatchRunContext#fromEnvironment()} (12-factor configuration: JVM
 * system properties and environment variables) and binds it to
 * {@link #BATCH_CTX} for the duration of {@link #execute()}. Inside
 * {@code execute()}, the {@code runId} and {@code processingDate} are read
 * from {@code BATCH_CTX.get()} for the structured-logging startup line.
 * <strong>No {@link ThreadLocal} is used anywhere</strong>, in keeping with
 * AAP &sect;0.7.4.
 *
 * <p>The {@code ScopedValue} class moved from {@code java.util.concurrent}
 * (preview) to {@code java.lang} (final) when JEP 506 was finalized in Java
 * 25; it is available transitively via {@code import module java.base;}
 * above.
 *
 * <h2>Process-exit semantics</h2>
 * <p>The return codes mirror the COBOL/IDCAMS convention:
 * <ul>
 *   <li>{@link #RC_OK} ({@code 0}) on success (the directory was created or
 *       confirmed and the retention metadata was written)</li>
 *   <li>{@link #RC_ERROR} ({@code 16}) for an uncaught failure &mdash; this
 *       includes any {@link java.io.IOException} during directory creation or
 *       the retention-file write, as well as any other unchecked exception
 *       that escapes {@code execute()}</li>
 * </ul>
 * The exit-code computation uses a pattern-matching switch (Java 21 Final)
 * with <strong>no {@code default} branch</strong> per AAP &sect;0.7.3.
 * Exhaustiveness over {@link Integer} is established by the {@code null}
 * case, the five explicit IDCAMS return-code constants
 * ({@code 0}/{@code 4}/{@code 8}/{@code 12}/{@code 16}), two guarded type
 * patterns (negative and {@code > 16}, both clamped to {@link #RC_ERROR}),
 * and a final unguarded type pattern that catches every remaining value.
 *
 * <h2>Shaded packaging</h2>
 * <p>Per AAP &sect;0.4.1 this main is packaged as the {@code carddemo-app}
 * shaded jar (single executable per JCL {@code EXEC PGM=} step), launchable
 * via:
 * <pre>
 *   java -XX:+UseCompactObjectHeaders \
 *        -XX:+UseShenandoahGC \
 *        -XX:ShenandoahGCMode=generational \
 *        -cp carddemo-app/target/carddemo-app-1.0.0-SNAPSHOT-shaded.jar \
 *        com.blitzy.carddemo.app.DailyRejectsApp
 * </pre>
 *
 * <h2>Non-goals (AAP &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring, no Lombok, no Apache Commons.</li>
 *   <li>No reflection or dynamic proxies.</li>
 *   <li>No {@link java.io.File} &mdash; {@link java.nio.file} only per AAP
 *       &sect;0.6.5.</li>
 *   <li>No {@link ThreadLocal} &mdash; {@link java.lang.ScopedValue} only per
 *       AAP &sect;0.6.6.</li>
 *   <li>No preview features &mdash; finalized JEPs only per AAP
 *       &sect;0.7.4.</li>
 *   <li>No generation roll-off enforcement ({@code LIMIT(5)}) &mdash; the
 *       retention attributes are recorded in {@code .retention} for the
 *       writer to consult; a follow-up effort is flagged in
 *       {@code MIGRATION_NOTES.md}.</li>
 * </ul>
 *
 * @see app/jcl/DALYREJS.jcl  the originating JCL job
 * @see BatchRunContext        the immutable per-job context bound to
 *                             {@link #BATCH_CTX}
 * @see PostTransactionsApp    the downstream job that writes the
 *                             {@code +1} generation into this GDG base
 * @since 1.0.0
 */
@CobolProgram(
        value = "IDCAMS (DALYREJS.jcl)",
        sourcePath = "app/jcl/DALYREJS.jcl",
        translationDate = "2025-10-24",
        notes = "JCL translation of the single-step IDCAMS DEFINE GENERATIONDATAGROUP "
                + "for AWS.M2.CARDDEMO.DALYREJS with LIMIT(5) SCRATCH. In the file-based "
                + "runtime per AAP §0.6.12 the GDG base is realised as a filesystem "
                + "directory; LIMIT(5) SCRATCH attributes are persisted to a sidecar "
                + ".retention metadata file for the writer of the +1 generation to consult. "
                + "Generation roll-off is not enforced here; it is the responsibility of the "
                + "GDG writer (PostTransactionsApp) and is flagged as a follow-up effort in "
                + "MIGRATION_NOTES.md. The IDCAMS \"IF LASTCC=12 THEN SET MAXCC=0\" idempotent "
                + "pattern is realised by Files.createDirectories which does not throw on "
                + "pre-existing directories."
)
public final class DailyRejectsApp {

    // -----------------------------------------------------------------------
    // Logger
    // -----------------------------------------------------------------------

    /**
     * SLF4J logger. Backed at runtime by logback-classic supplied via the
     * carddemo-app shaded jar packaging (see AAP &sect;0.5.1); this class
     * deliberately holds no reference to a concrete logging implementation.
     */
    private static final Logger LOG = LoggerFactory.getLogger(DailyRejectsApp.class);

    // -----------------------------------------------------------------------
    // ScopedValue (JEP 506 Final) — propagates BatchRunContext into execute()
    // -----------------------------------------------------------------------

    /**
     * Local {@link java.lang.ScopedValue} for the {@link BatchRunContext} that
     * orchestrates this job. Bound by {@link #main(String[])} via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(DailyRejectsApp::execute)}
     * and read inside {@link #execute()} via {@code BATCH_CTX.get()} to emit
     * the {@code runId} and {@code processingDate} on the DALYREJS startup
     * log line.
     *
     * <p>This binding replaces {@link ThreadLocal} entirely per AAP
     * &sect;0.6.6 and &sect;0.7.4. The {@code ScopedValue} class moved from
     * {@code java.util.concurrent} (preview) to {@code java.lang} (final)
     * when JEP 506 was finalized in Java 25.
     *
     * <p>Per the export schema for this file, this field is part of the
     * {@code DailyRejectsApp} public surface &mdash; co-located with
     * {@link BatchRunContext#BATCH_CTX} but scoped to this app to keep the
     * binding window narrow.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // -----------------------------------------------------------------------
    // Constants — GDG base attributes from app/jcl/DALYREJS.jcl
    // -----------------------------------------------------------------------

    /**
     * The dataset name from the JCL clause
     * {@code NAME(AWS.M2.CARDDEMO.DALYREJS)}. Preserved verbatim
     * (uppercase, dots and all) per AAP &sect;0.6.12 z/OS naming conventions
     * so that downstream batch jobs locate this GDG base by the exact name
     * the mainframe job uses.
     */
    private static final String GDG_BASE_NAME = "AWS.M2.CARDDEMO.DALYREJS";

    /**
     * The {@code LIMIT(5)} attribute from the IDCAMS DEFINE clause. Recorded
     * in the {@code .retention} sidecar metadata file under the GDG base
     * directory for the writer of the {@code +1} generation to consult.
     * Generation roll-off is not enforced by this class; see the migration
     * notes.
     */
    private static final int GDG_LIMIT = 5;

    // -----------------------------------------------------------------------
    // Constants — configuration keys (12-factor per AAP §0.7.2)
    // -----------------------------------------------------------------------

    /**
     * JVM system-property key for the GDG root directory. Documented in
     * {@code application.properties.example}.
     *
     * <p>Resolved by {@link #getProp(String, String)} which checks the
     * environment-variable equivalent first ({@code CARDDEMO_GDG_ROOT}),
     * then this system property, and finally falls back to
     * {@link #DEFAULT_GDG_ROOT}.
     */
    static final String PROP_GDG_ROOT = "carddemo.gdg.root";

    /**
     * Default filesystem location for the GDG root, used when neither the
     * system property {@link #PROP_GDG_ROOT} nor the environment variable
     * {@code CARDDEMO_GDG_ROOT} is set. Resolves relative to the JVM working
     * directory.
     */
    static final String DEFAULT_GDG_ROOT = "./data/gdg";

    /**
     * The name of the sidecar metadata file written into the GDG base
     * directory after creation. Captures the {@code LIMIT} and
     * {@code SCRATCH} attributes from the IDCAMS DEFINE clause so the writer
     * of the {@code +1} generation can implement roll-off (a follow-up
     * effort).
     */
    static final String RETENTION_FILE_NAME = ".retention";

    // -----------------------------------------------------------------------
    // Constants — IDCAMS return codes
    // -----------------------------------------------------------------------

    /**
     * Successful IDCAMS return code: the DEFINE clause either created the GDG
     * base or hit the {@code LASTCC=12} &quot;already cataloged&quot;
     * idempotent guard.
     */
    static final int RC_OK = 0;

    /**
     * Error return code for any uncaught failure. Matches the AAP
     * &sect;0.6.12 / pattern-matching switch convention used across the
     * carddemo-app composition root: any unexpected exception, negative
     * return code, or return code greater than 16 is clamped to this value.
     */
    static final int RC_ERROR = 16;

    // -----------------------------------------------------------------------
    // Private constructor — this class is not constructible
    // -----------------------------------------------------------------------

    /**
     * Utility class with only static members &mdash; not constructible.
     * Throws an {@link AssertionError} on reflective invocation attempts.
     */
    private DailyRejectsApp() {
        throw new AssertionError("DailyRejectsApp is not constructible");
    }

    // -----------------------------------------------------------------------
    // main — entry point bound by the maven-shade-plugin manifest
    // -----------------------------------------------------------------------

    /**
     * Java main entry point. Mirrors the {@code STEP05 EXEC PGM=IDCAMS} step
     * of {@code DALYREJS.jcl}: creates the
     * {@code AWS.M2.CARDDEMO.DALYREJS} GDG base directory under the
     * configured GDG root and writes the retention metadata sidecar.
     *
     * <p>Lifecycle:
     * <ol>
     *   <li>Resolve a {@link BatchRunContext} from JVM system properties and
     *       environment variables via {@link BatchRunContext#fromEnvironment()}.</li>
     *   <li>Bind the context to {@link #BATCH_CTX} via
     *       {@code ScopedValue.where(BATCH_CTX, ctx).call(DailyRejectsApp::execute)}.</li>
     *   <li>Compute the IDCAMS-style return code via an exhaustive
     *       pattern-matching switch on the boxed {@link Integer} result
     *       &mdash; no {@code default} branch per AAP &sect;0.7.3.</li>
     *   <li>Exit the JVM with {@link System#exit(int)} carrying that return
     *       code.</li>
     * </ol>
     *
     * <p>The switch is exhaustive over {@link Integer} via: the {@code null}
     * case (clamped to {@link #RC_ERROR}); five explicit IDCAMS constants
     * ({@code 0}, {@code 4}, {@code 8}, {@code 12}, {@code 16}); two guarded
     * type patterns (negative and {@code > 16}, both clamped to
     * {@link #RC_ERROR}); and a final unguarded type pattern that matches
     * every remaining value (the IDCAMS &quot;soft error&quot; codes
     * {@code 1}..{@code 3}, {@code 5}..{@code 7}, {@code 9}..{@code 11},
     * {@code 13}..{@code 15}). The Java compiler enforces exhaustiveness; no
     * {@code default} branch is needed or permitted.
     *
     * @param args command-line arguments. Currently unused; configuration
     *             flows exclusively through JVM system properties and
     *             environment variables for 12-factor compliance (AAP
     *             &sect;0.7.2).
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        // The selector is intentionally boxed to Integer so the switch below
        // is exhaustive via standard pattern matching (Java 21 Final) rather
        // than primitive patterns (JEP 507 — preview, forbidden by AAP §0.7.4).
        // ScopedValue.Carrier#call returns R (here Integer via autoboxing from
        // the int returned by execute()), so this assignment is direct.
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(DailyRejectsApp::execute);
        } catch (Exception e) {
            LOG.error("DALYREJS job failed with uncaught exception", e);
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

    // -----------------------------------------------------------------------
    // execute — job body invoked inside the ScopedValue scope
    // -----------------------------------------------------------------------

    /**
     * Runs the single-step DALYREJS job body inside the {@link #BATCH_CTX}
     * scope. Idempotent: if the GDG base directory already exists, the
     * operation logs an idempotent re-run (matching the IDCAMS
     * {@code IF LASTCC=12 THEN SET MAXCC=0} idiom) and still rewrites the
     * retention metadata file so it always reflects the current
     * {@link #GDG_LIMIT} configuration.
     *
     * <p>Visible for testing (package-private) so unit tests can invoke the
     * body directly inside their own {@code ScopedValue.where(...).run(...)}
     * scope without going through {@link System#exit(int)}.
     *
     * @return {@link #RC_OK} on success
     * @throws IOException if any filesystem operation (directory create or
     *                     retention-file write) fails
     */
    static int execute() throws IOException {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("DALYREJS job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        Path gdgRoot = SafePathResolver.resolveTrusted(PROP_GDG_ROOT, DEFAULT_GDG_ROOT);
        Path baseDir = gdgRoot.resolve(GDG_BASE_NAME);

        if (Files.exists(baseDir)) {
            // Idempotent: translate "IF LASTCC=12 THEN SET MAXCC=0"
            LOG.info("DALYREJS: GDG base already exists (idempotent re-run); name={}, dir={}",
                    GDG_BASE_NAME, baseDir);
        } else {
            Files.createDirectories(baseDir);
            LOG.info("DALYREJS: DEFINE GDG OK; name={}, limit={}, scratch=true, dir={}",
                    GDG_BASE_NAME, GDG_LIMIT, baseDir);
        }

        // Always (re)write the retention metadata so the file reflects the
        // current configuration even when the directory pre-existed under a
        // prior configuration.
        Path retentionFile = baseDir.resolve(RETENTION_FILE_NAME);
        Files.writeString(retentionFile, "limit=" + GDG_LIMIT + "\nscratch=true\n");
        LOG.info("DALYREJS: retention metadata written; file={}, limit={}, scratch=true",
                retentionFile, GDG_LIMIT);

        LOG.info("DALYREJS job complete; rc={}", RC_OK);
        return RC_OK;
    }

    // -----------------------------------------------------------------------
    // getProp — 12-factor configuration lookup (env first, then sysprop)
    // -----------------------------------------------------------------------

    /**
     * Resolves a configuration value with the documented precedence:
     * <ol>
     *   <li>Environment variable derived from {@code key} by uppercasing and
     *       replacing dots and hyphens with underscores
     *       (e.g., {@code carddemo.gdg.root} &rarr; {@code CARDDEMO_GDG_ROOT}).
     *       The {@link Locale#ROOT} uppercase locale is used to avoid
     *       locale-sensitive surprises (e.g., the Turkish dotted-i).</li>
     *   <li>The JVM system property identified by {@code key}.</li>
     *   <li>The supplied {@code defaultValue}.</li>
     * </ol>
     * Blank (whitespace-only) values are treated as absent. This precedence
     * matches the 12-factor configuration convention used across the
     * carddemo-app composition root (see {@code PrintTcatBalApp} and
     * {@code DefineGdgApp}).
     *
     * <p>Visible for testing (package-private) so unit tests can verify the
     * precedence rules without subclassing or reflection.
     *
     * @param key          the property name; must be non-{@code null}
     * @param defaultValue the fallback value; must be non-{@code null}
     * @return the resolved configuration value; never {@code null}
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
