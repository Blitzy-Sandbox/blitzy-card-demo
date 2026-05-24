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
// exported by the java.base module. This brings into scope:
//   - java.nio.file.{Path, Files}           (filesystem GDG-directory creation)
//   - java.io.IOException                   (checked exception from Files.*)
//   - java.util.{List, Locale}              (List.of(...) GDG roster, Locale.ROOT
//                                            for env-key normalization)
//   - java.lang.ScopedValue                 (JEP 506 Final — moved from
//                                            java.util.concurrent (preview) to
//                                            java.lang (final) when JEP 506 was
//                                            finalized in Java 25; see
//                                            java.base/java/lang/ScopedValue.java)
// per AAP §0.7.3.
import module java.base;

import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/DEFGDGB.jcl}
 * &mdash; an IDCAMS job whose sole step ({@code STEP05 EXEC PGM=IDCAMS})
 * defines six Generation Data Group (GDG) bases on z/OS, each with
 * {@code LIMIT(5)} and {@code SCRATCH}. In the file-based runtime
 * established by AAP &sect;0.6.12 (&quot;File-based default; JDBC adapter
 * optional&quot;), each GDG base translates to an ordinary filesystem
 * directory whose name preserves the z/OS dataset name verbatim; later
 * jobs ({@link PostTransactionsApp}, {@link CombineTransactionsApp},
 * {@link TransactionBackupApp}, the statement and report generators)
 * write {@code G0001V00}-style generation files into these directories.
 *
 * <h2>Source artefact</h2>
 * <p>The originating {@code app/jcl/DEFGDGB.jcl} step body defines the
 * following six GDG bases, each followed by the idempotent IDCAMS
 * {@code IF LASTCC=12 THEN SET MAXCC=0} idiom that translates "already
 * cataloged" (LASTCC=12) into success:
 * <ol>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.BKUP}     LIMIT(5) SCRATCH</li>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.DALY}     LIMIT(5) SCRATCH</li>
 *   <li>{@code AWS.M2.CARDDEMO.TRANREPT}          LIMIT(5) SCRATCH</li>
 *   <li>{@code AWS.M2.CARDDEMO.TCATBALF.BKUP}     LIMIT(5) SCRATCH</li>
 *   <li>{@code AWS.M2.CARDDEMO.SYSTRAN}           LIMIT(5) SCRATCH</li>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.COMBINED} LIMIT(5) SCRATCH</li>
 * </ol>
 * <p>(Note: {@code app/jcl/REPTFILE.jcl} additionally defines
 * {@code AWS.M2.CARDDEMO.TRANREPT} with {@code LIMIT(10)} and no
 * {@code SCRATCH}; both JCL sources resolve to the same filesystem GDG
 * base in this translation. The {@link ReportFileApp} variant supersedes
 * the retention metadata at runtime if it runs after this app.)
 *
 * <h2>Translation strategy &mdash; idiom-for-idiom (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>Each {@code DEFINE GENERATIONDATAGROUP NAME(&lt;DSN&gt;)} clause
 *       becomes a {@link java.nio.file.Files#createDirectories(Path,
 *       java.nio.file.attribute.FileAttribute...)} call on a directory
 *       whose name is the GDG dataset name. The dataset name (uppercase,
 *       dots and all) is preserved verbatim per AAP &sect;0.6.12 z/OS
 *       naming conventions so downstream JCL-translated apps reference
 *       these directories by the exact name the mainframe jobs use.</li>
 *   <li>The {@code LIMIT(5)} and {@code SCRATCH} attributes are recorded
 *       in a sidecar {@code .retention} text file under each base
 *       directory. On the mainframe, the catalog manages GDG generation
 *       roll-off; in the file-based runtime, generation roll-off (if
 *       needed) is the responsibility of the writer of the GDG &mdash;
 *       a follow-up effort flagged in {@code MIGRATION_NOTES.md}. The
 *       initial milestone preserves the COBOL behaviour of &quot;directory
 *       exists, ready to receive generations&quot; and documents the
 *       attributes in metadata.</li>
 *   <li>The IDCAMS idiom {@code IF LASTCC=12 THEN SET MAXCC=0} (i.e.
 *       &quot;treat 'already exists' as success&quot;) is the natural
 *       semantic of {@link java.nio.file.Files#createDirectories(Path,
 *       java.nio.file.attribute.FileAttribute...)} which is idempotent
 *       and does not throw
 *       {@link java.nio.file.FileAlreadyExistsException} on a pre-existing
 *       directory. We additionally branch on
 *       {@link Files#exists(Path, java.nio.file.LinkOption...)} so the log
 *       output explicitly reports an idempotent re-run, matching the
 *       IDCAMS SYSPRINT &quot;already cataloged&quot; trace.</li>
 *   <li>The six bases are defined as an immutable
 *       {@code List<GdgBase>} of a private {@code record GdgBase(String
 *       name, int limit, boolean scratch)} per AAP &sect;0.3.2 (records
 *       for closed value tuples).</li>
 * </ul>
 *
 * <h2>Runtime configuration</h2>
 * <p>The base path under which the GDG directories are created is read
 * from the system property {@code carddemo.gdg.root} or the environment
 * variable {@code CARDDEMO_GDG_ROOT} (12-factor configuration per AAP
 * &sect;0.7.2). If neither is set, the JVM working directory's
 * {@code ./data/gdg/} subdirectory is used. This default matches the
 * convention used by the sibling {@link DailyRejectsApp} and
 * {@link ReportFileApp} so the file layout is consistent across every
 * GDG-defining app.
 *
 * <h2>BatchRunContext / ScopedValue (AAP &sect;0.6.6, JEP 506 Final)</h2>
 * <p>{@link #main(String[])} resolves a {@link BatchRunContext} via
 * {@link BatchRunContext#fromEnvironment()} (12-factor configuration: JVM
 * system properties and environment variables) and binds it to
 * {@link #BATCH_CTX} for the duration of {@link #execute()}. Inside
 * {@code execute()}, the {@code runId} and {@code processingDate} are
 * read from {@code BATCH_CTX.get()} for the structured-logging startup
 * line. <strong>No {@link ThreadLocal} is used anywhere</strong>, in
 * keeping with AAP &sect;0.6.6 and &sect;0.7.4.
 *
 * <p>The {@code ScopedValue} class moved from {@code java.util.concurrent}
 * (preview) to {@code java.lang} (final) when JEP 506 was finalized in
 * Java 25; it is available transitively via {@code import module
 * java.base;} above.
 *
 * <h2>Process-exit semantics</h2>
 * <p>The return codes mirror the COBOL/IDCAMS convention:
 * <ul>
 *   <li>{@link #RC_OK} ({@code 0}) on success (every GDG base directory
 *       was created or confirmed and every retention metadata file was
 *       written)</li>
 *   <li>{@link #RC_ERROR} ({@code 16}) for an uncaught failure &mdash;
 *       this includes any {@link java.io.IOException} during directory
 *       creation or the retention-file write, as well as any other
 *       unchecked exception that escapes {@code execute()}</li>
 * </ul>
 * The exit-code computation uses a pattern-matching switch (Java 21
 * Final) with <strong>no {@code default} branch</strong> per AAP
 * &sect;0.7.3. Exhaustiveness over {@link Integer} is established by the
 * {@code null} case, the five explicit IDCAMS return-code constants
 * ({@code 0}/{@code 4}/{@code 8}/{@code 12}/{@code 16}), two guarded type
 * patterns (negative and {@code > 16}, both clamped to
 * {@link #RC_ERROR}), and a final unguarded type pattern that catches
 * every remaining value.
 *
 * <h2>Shaded packaging</h2>
 * <p>Per AAP &sect;0.4.1 this main is packaged as the {@code carddemo-app}
 * shaded jar (single executable per JCL {@code EXEC PGM=} step),
 * launchable via:
 * <pre>
 *   java -XX:+UseCompactObjectHeaders \
 *        -XX:+UseShenandoahGC \
 *        -XX:ShenandoahGCMode=generational \
 *        -cp carddemo-app/target/carddemo-app-1.0.0-SNAPSHOT-shaded.jar \
 *        com.blitzy.carddemo.app.DefineGdgApp
 * </pre>
 *
 * <h2>Non-goals (AAP &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring, no Lombok, no Apache Commons.</li>
 *   <li>No reflection or dynamic proxies.</li>
 *   <li>No {@link java.io.File} &mdash; {@link java.nio.file} only per
 *       AAP &sect;0.6.5.</li>
 *   <li>No {@link ThreadLocal} &mdash; {@link java.lang.ScopedValue}
 *       only per AAP &sect;0.6.6.</li>
 *   <li>No preview features &mdash; finalized JEPs only per AAP
 *       &sect;0.7.4.</li>
 *   <li>No generation roll-off enforcement ({@code LIMIT(5)}) &mdash;
 *       the retention attributes are recorded in {@code .retention} for
 *       the writer to consult; a follow-up effort is flagged in
 *       {@code MIGRATION_NOTES.md}.</li>
 *   <li>No new GDG bases &mdash; the exact six bases from
 *       {@code DEFGDGB.jcl} are preserved verbatim.</li>
 * </ul>
 *
 * @see app/jcl/DEFGDGB.jcl    the originating JCL job
 * @see BatchRunContext        the immutable per-job context bound to
 *                             {@link #BATCH_CTX}
 * @see DailyRejectsApp        the single-base GDG-define sibling
 * @see ReportFileApp          the TRANREPT GDG-define sibling
 * @since 1.0.0
 */
@CobolProgram(
        value = "IDCAMS (DEFGDGB.jcl)",
        sourcePath = "app/jcl/DEFGDGB.jcl",
        translationDate = "2025-10-24",
        notes = "JCL translation of the single-step IDCAMS DEFINE GENERATIONDATAGROUP "
                + "job that creates six GDG bases for the CardDemo batch tree: "
                + "TRANSACT.BKUP, TRANSACT.DALY, TRANREPT, TCATBALF.BKUP, SYSTRAN, and "
                + "TRANSACT.COMBINED, each with LIMIT(5) SCRATCH. In the file-based "
                + "runtime per AAP §0.6.12 each GDG base is realised as a filesystem "
                + "directory; LIMIT(5) SCRATCH attributes are persisted to a sidecar "
                + ".retention metadata file under each base directory for the writer of "
                + "the +1 generation to consult. Generation roll-off is not enforced "
                + "here; it is the responsibility of the GDG writer (PostTransactionsApp, "
                + "CombineTransactionsApp, TransactionBackupApp, statement/report "
                + "generators) and is flagged as a follow-up effort in MIGRATION_NOTES.md. "
                + "The IDCAMS \"IF LASTCC=12 THEN SET MAXCC=0\" idempotent pattern is "
                + "realised by Files.createDirectories which does not throw on "
                + "pre-existing directories."
)
public final class DefineGdgApp {

    // -----------------------------------------------------------------------
    // Logger
    // -----------------------------------------------------------------------

    /**
     * SLF4J logger. Backed at runtime by logback-classic supplied via the
     * {@code carddemo-app} shaded jar packaging (see AAP &sect;0.5.1); this
     * class deliberately holds no reference to a concrete logging
     * implementation. No card PAN is logged anywhere in this app.
     */
    private static final Logger LOG = LoggerFactory.getLogger(DefineGdgApp.class);

    // -----------------------------------------------------------------------
    // ScopedValue (JEP 506 Final) — propagates BatchRunContext into execute()
    // -----------------------------------------------------------------------

    /**
     * Local {@link java.lang.ScopedValue} for the {@link BatchRunContext}
     * that orchestrates this job. Bound by {@link #main(String[])} via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(DefineGdgApp::execute)}
     * and read inside {@link #execute()} via {@code BATCH_CTX.get()} to emit
     * the {@code runId} and {@code processingDate} on the DEFGDGB startup
     * log line.
     *
     * <p>This binding replaces {@link ThreadLocal} entirely per AAP
     * &sect;0.6.6 and &sect;0.7.4. The {@code ScopedValue} class moved
     * from {@code java.util.concurrent} (preview) to {@code java.lang}
     * (final) when JEP 506 was finalized in Java 25.
     *
     * <p>Per the export schema for this file, this field is part of the
     * {@code DefineGdgApp} public surface &mdash; co-located with
     * {@link BatchRunContext#BATCH_CTX} but scoped to this app to keep
     * the binding window narrow.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // -----------------------------------------------------------------------
    // Inner record — closed value tuple per AAP §0.3.2
    // -----------------------------------------------------------------------

    /**
     * Immutable tuple describing one GDG base as written in the JCL:
     * the dataset name (e.g., {@code AWS.M2.CARDDEMO.TRANSACT.BKUP}),
     * the {@code LIMIT(n)} retention count (the maximum number of
     * generations the catalog will keep), and the {@code SCRATCH}
     * attribute (whether dropped generations should be physically
     * deleted on rollover).
     *
     * <p>Records are mandated by AAP &sect;0.3.2 for closed value tuples
     * such as this; the canonical constructor is sufficient (no
     * validation needed because the values are all compile-time
     * constants supplied by {@link #GDG_BASES} below).
     *
     * @param name    z/OS GDG dataset name, preserved verbatim per AAP
     *                &sect;0.6.12
     * @param limit   {@code LIMIT(n)} attribute from the JCL DEFINE
     *                clause; non-negative
     * @param scratch {@code SCRATCH} attribute from the JCL DEFINE
     *                clause; {@code true} when the JCL says SCRATCH,
     *                {@code false} when the JCL says NOSCRATCH or omits
     *                the keyword
     */
    private record GdgBase(String name, int limit, boolean scratch) {
    }

    // -----------------------------------------------------------------------
    // Constants — the six GDG bases from app/jcl/DEFGDGB.jcl
    // -----------------------------------------------------------------------

    /**
     * The six GDG bases defined by {@code app/jcl/DEFGDGB.jcl}, in
     * declaration order. Each entry mirrors the
     * {@code DEFINE GENERATIONDATAGROUP NAME(&lt;DSN&gt;) LIMIT(5)
     * SCRATCH} clause from the JCL.
     *
     * <p>Order is preserved exactly so any future audit-log diff or
     * structured-log replay sees identical sequencing between the
     * COBOL/IDCAMS run and the Java run. The dataset names are
     * preserved verbatim (uppercase, dots and all) per AAP &sect;0.6.12
     * z/OS naming conventions.
     *
     * <p>This collection is {@link List#of List.of(...)}-constructed so
     * it is immutable; attempting to add or remove a base would throw
     * {@link UnsupportedOperationException}. To intentionally change the
     * GDG roster, the only valid edit is to modify this list and the
     * corresponding {@code DEFGDGB.jcl} together &mdash; per AAP
     * &sect;0.7.1 the JCL is the reference implementation.
     */
    private static final List<GdgBase> GDG_BASES = List.of(
            new GdgBase("AWS.M2.CARDDEMO.TRANSACT.BKUP",     5, true),
            new GdgBase("AWS.M2.CARDDEMO.TRANSACT.DALY",     5, true),
            new GdgBase("AWS.M2.CARDDEMO.TRANREPT",          5, true),
            new GdgBase("AWS.M2.CARDDEMO.TCATBALF.BKUP",     5, true),
            new GdgBase("AWS.M2.CARDDEMO.SYSTRAN",           5, true),
            new GdgBase("AWS.M2.CARDDEMO.TRANSACT.COMBINED", 5, true)
    );

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
     * Default filesystem location for the GDG root, used when neither
     * the system property {@link #PROP_GDG_ROOT} nor the environment
     * variable {@code CARDDEMO_GDG_ROOT} is set. Resolves relative to
     * the JVM working directory. Matches the sibling
     * {@link DailyRejectsApp#DEFAULT_GDG_ROOT}.
     */
    static final String DEFAULT_GDG_ROOT = "./data/gdg";

    /**
     * The name of the sidecar metadata file written into each GDG base
     * directory after creation. Captures the {@code LIMIT} and
     * {@code SCRATCH} attributes from the IDCAMS DEFINE clause so the
     * writer of the {@code +1} generation can implement roll-off (a
     * follow-up effort).
     */
    static final String RETENTION_FILE_NAME = ".retention";

    // -----------------------------------------------------------------------
    // Constants — IDCAMS return codes
    // -----------------------------------------------------------------------

    /**
     * Successful IDCAMS return code: every DEFINE clause either created
     * the GDG base or hit the {@code LASTCC=12} &quot;already cataloged&quot;
     * idempotent guard, and every retention metadata file was written.
     */
    static final int RC_OK = 0;

    /**
     * Error return code for any uncaught failure. Matches the AAP
     * &sect;0.6.12 / pattern-matching switch convention used across the
     * carddemo-app composition root: any unexpected exception, negative
     * return code, or return code greater than 16 is clamped to this
     * value.
     */
    static final int RC_ERROR = 16;

    // -----------------------------------------------------------------------
    // Private constructor — this class is not constructible
    // -----------------------------------------------------------------------

    /**
     * Utility class with only static members &mdash; not constructible.
     * Throws an {@link AssertionError} on reflective invocation attempts.
     */
    private DefineGdgApp() {
        throw new AssertionError("DefineGdgApp is not constructible");
    }

    // -----------------------------------------------------------------------
    // main — entry point bound by the maven-shade-plugin manifest
    // -----------------------------------------------------------------------

    /**
     * Java main entry point. Mirrors the {@code STEP05 EXEC PGM=IDCAMS}
     * step of {@code DEFGDGB.jcl}: creates the six GDG base directories
     * under the configured GDG root and writes a {@code .retention}
     * metadata file into each one.
     *
     * <p>Lifecycle:
     * <ol>
     *   <li>Resolve a {@link BatchRunContext} from JVM system properties
     *       and environment variables via
     *       {@link BatchRunContext#fromEnvironment()}.</li>
     *   <li>Bind the context to {@link #BATCH_CTX} via
     *       {@code ScopedValue.where(BATCH_CTX, ctx).call(DefineGdgApp::execute)}.</li>
     *   <li>Compute the IDCAMS-style return code via an exhaustive
     *       pattern-matching switch on the boxed {@link Integer}
     *       result &mdash; no {@code default} branch per AAP
     *       &sect;0.7.3.</li>
     *   <li>Exit the JVM with {@link System#exit(int)} carrying that
     *       return code.</li>
     * </ol>
     *
     * <p>The switch is exhaustive over {@link Integer} via: the
     * {@code null} case (clamped to {@link #RC_ERROR}); five explicit
     * IDCAMS constants ({@code 0}, {@code 4}, {@code 8}, {@code 12},
     * {@code 16}); two guarded type patterns (negative and {@code > 16},
     * both clamped to {@link #RC_ERROR}); and a final unguarded type
     * pattern that matches every remaining value (the IDCAMS &quot;soft
     * error&quot; codes {@code 1}..{@code 3}, {@code 5}..{@code 7},
     * {@code 9}..{@code 11}, {@code 13}..{@code 15}). The Java compiler
     * enforces exhaustiveness; no {@code default} branch is needed or
     * permitted.
     *
     * @param args command-line arguments. Currently unused; configuration
     *             flows exclusively through JVM system properties and
     *             environment variables for 12-factor compliance (AAP
     *             &sect;0.7.2).
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        // The result is intentionally boxed to Integer so the switch below
        // is exhaustive via standard pattern matching (Java 21 Final) rather
        // than primitive patterns (JEP 507 — preview, forbidden by AAP §0.7.4).
        // ScopedValue.Carrier#call returns R (here Integer via autoboxing from
        // the int returned by execute()), so this assignment is direct.
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(DefineGdgApp::execute);
        } catch (Exception e) {
            LOG.error("DEFGDGB job failed with uncaught exception", e);
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
     * Runs the six-base DEFGDGB job body inside the {@link #BATCH_CTX}
     * scope. Idempotent: if a GDG base directory already exists, the
     * operation logs an idempotent re-run (matching the IDCAMS
     * {@code IF LASTCC=12 THEN SET MAXCC=0} idiom) and still rewrites
     * the retention metadata file so it always reflects the currently
     * configured {@code LIMIT}/{@code SCRATCH} values.
     *
     * <p>Visible for testing (package-private) so unit tests can invoke
     * the body directly inside their own
     * {@code ScopedValue.where(...).call(...)} scope without going
     * through {@link System#exit(int)}.
     *
     * @return {@link #RC_OK} on success
     * @throws IOException if any filesystem operation (root directory
     *                     create, per-base directory create, or
     *                     retention-file write) fails
     */
    static int execute() throws IOException {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("DEFGDGB job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        Path gdgRoot = Path.of(getProp(PROP_GDG_ROOT, DEFAULT_GDG_ROOT));
        LOG.info("DEFGDGB: GDG filesystem root resolved to {}", gdgRoot);
        // Ensure the root exists. createDirectories is idempotent and creates
        // intermediate directories as needed, mirroring IDCAMS' implicit
        // catalog-namespace materialization.
        Files.createDirectories(gdgRoot);

        for (GdgBase base : GDG_BASES) {
            Path baseDir = gdgRoot.resolve(base.name());
            if (Files.exists(baseDir)) {
                // Idempotent: translate "IF LASTCC=12 THEN SET MAXCC=0"
                LOG.info("DEFGDGB: GDG base already exists (idempotent re-run); "
                                + "name={}, dir={}",
                        base.name(), baseDir);
            } else {
                Files.createDirectories(baseDir);
                LOG.info("DEFGDGB: DEFINE GDG OK; name={}, limit={}, scratch={}, dir={}",
                        base.name(), base.limit(), base.scratch(), baseDir);
            }
            // Always (re)write the retention metadata so the sidecar reflects
            // the current configuration even when the directory pre-existed
            // under a prior configuration.
            Path retentionFile = baseDir.resolve(RETENTION_FILE_NAME);
            String retentionContent = "limit=" + base.limit()
                    + "\nscratch=" + base.scratch()
                    + "\n";
            Files.writeString(retentionFile, retentionContent);
            LOG.info("DEFGDGB: retention metadata written; file={}, limit={}, scratch={}",
                    retentionFile, base.limit(), base.scratch());
        }

        LOG.info("DEFGDGB job complete; basesProcessed={}, rc={}",
                GDG_BASES.size(), RC_OK);
        return RC_OK;
    }

    // -----------------------------------------------------------------------
    // getProp — 12-factor configuration lookup (env first, then sysprop)
    // -----------------------------------------------------------------------

    /**
     * Resolves a configuration value with the documented precedence:
     * <ol>
     *   <li>Environment variable derived from {@code key} by uppercasing
     *       and replacing dots and hyphens with underscores
     *       (e.g., {@code carddemo.gdg.root} &rarr;
     *       {@code CARDDEMO_GDG_ROOT}). The {@link Locale#ROOT}
     *       uppercase locale is used to avoid locale-sensitive
     *       surprises (e.g., the Turkish dotted-i).</li>
     *   <li>The JVM system property identified by {@code key}.</li>
     *   <li>The supplied {@code defaultValue}.</li>
     * </ol>
     * Blank (whitespace-only) values are treated as absent. This
     * precedence matches the 12-factor configuration convention used
     * across the carddemo-app composition root (see
     * {@link DailyRejectsApp#getProp(String, String)} and
     * {@link PrintTcatBalApp}).
     *
     * <p>Visible for testing (package-private) so unit tests can verify
     * the precedence rules without subclassing or reflection.
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
