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

// JEP 511 (finalized in Java 25): brings every type exported by the java.base
// module into scope with a single declaration. Specifically used here for:
//   - java.util.List          (the CICS_FILE_IDS constant)
//   - java.util.Locale        (case-insensitive uppercasing in getProp())
//   - java.nio.file.Path      (file path resolution per AAP §0.6.5)
//   - java.nio.file.Files     (Files.exists; replaces java.io.File)
//   - java.lang.ScopedValue   (the BATCH_CTX field; ScopedValue moved from its
//                              preview-status location java.util.concurrent to
//                              java.lang when JEP 506 was finalized)
//   - java.lang.Integer       (the Integer rc pattern-matching switch selector)
import module java.base;

import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/OPENFIL.jcl}
 * &mdash; the {@code SDSF}-based batch job that, on the mainframe, issues
 * {@code CEMT SET FIL(<name>) OPE} commands to the {@code CICSAWSA} region to
 * open five CICS-managed VSAM files for online transaction processing
 * (typically run after a batch maintenance job has finished updating them
 * &mdash; see {@link CloseFileApp} for the symmetric close-side companion).
 *
 * <h2>Source artifact</h2>
 * <p>{@code app/jcl/OPENFIL.jcl} step {@code OPCIFIL EXEC PGM=SDSF}, inline
 * {@code ISFIN}, contains the verbatim command list (one
 * {@code /F CICSAWSA,'CEMT SET FIL(<name>) OPE'} per CICS file):
 * <ol>
 *   <li>{@code TRANSACT} &mdash; transaction master KSDS</li>
 *   <li>{@code CCXREF}   &mdash; card cross-reference KSDS</li>
 *   <li>{@code ACCTDAT}  &mdash; account master KSDS</li>
 *   <li>{@code CXACAIX}  &mdash; alternate-index PATH over the card
 *       cross-reference KSDS (shares the underlying {@code CCXREF} file in
 *       the file-based architecture)</li>
 *   <li>{@code USRSEC}   &mdash; user security KSDS</li>
 * </ol>
 * The five identifiers are preserved verbatim and in declaration order in
 * {@link #CICS_FILE_IDS}.
 *
 * <h2>Translation strategy &mdash; idiom-for-idiom (AAP &sect;0.7.1)</h2>
 * <p>The original JCL is a CICS-region administration utility: it tells the
 * online CICS region that previously-closed VSAM files are now available for
 * application use. Per AAP &sect;0.6.12 (Acknowledged Architectural Override),
 * CICS region administration is <strong>out of scope</strong> for the file-
 * based Java runtime &mdash; there is no CICS region to communicate with.
 * File handles in the file-based architecture are acquired on demand by
 * individual {@code java.nio.file} channels and released via
 * try-with-resources in the corresponding file adapters; the orchestration-
 * level &quot;open&quot; therefore has no Java equivalent.
 *
 * <p>Per AAP &sect;0.4.1 (the JCL-to-Java mapping table marks this as
 * &quot;{@code IEFBR14 no-op translated for completeness}&quot;), the Java
 * translation is a verifying no-op:
 * <ol>
 *   <li>For each of the five CICS file IDs, resolve the configured filesystem
 *       path via {@link #resolvePathForCicsFileId(String)}.</li>
 *   <li>If the path exists on disk, emit an INFO log line acknowledging the
 *       open intent.</li>
 *   <li>If the path is missing, emit a WARN log line and bump the return
 *       code to {@code 4} (mainframe-style warning); downstream readers will
 *       fail loudly when they actually try to open the file, which is the
 *       same observable outcome as on the mainframe when a missing dataset
 *       causes a {@code DD} error.</li>
 * </ol>
 *
 * <h2>Execution shape</h2>
 * <p>Shaded as {@code carddemo-open-file.jar} by the {@code maven-shade-plugin}
 * configured in {@code java/carddemo-app/pom.xml}; run via:
 * {@snippet lang = "shell":
 * java -XX:+UseCompactObjectHeaders \
 *      -XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational \
 *      -jar carddemo-open-file.jar
 * }
 * Batch-run context (run identifier, processing date, tenant) is supplied via
 * JVM system properties or environment variables consumed by
 * {@link BatchRunContext#fromEnvironment()} and is propagated to the
 * {@link #execute()} method through the JEP&nbsp;506 {@link ScopedValue}
 * binding {@link #BATCH_CTX}.
 *
 * <h2>Mandates honored (AAP)</h2>
 * <ul>
 *   <li>&sect;0.6.5 every file I/O surface uses {@link java.nio.file};
 *       {@code java.io.File} is forbidden.</li>
 *   <li>&sect;0.6.6 batch-run context flows through {@link ScopedValue};
 *       {@link ThreadLocal} is forbidden in new code.</li>
 *   <li>&sect;0.6.7 pattern-matching {@code switch} expression for the return
 *       code &rarr; exit code mapping; no {@code default} branch &mdash; the
 *       compiler enforces exhaustiveness via the unconditional
 *       {@code case Integer i} pattern.</li>
 *   <li>&sect;0.4.2 {@code import module java.base;} (JEP&nbsp;511) at the top
 *       of the file.</li>
 *   <li>&sect;0.7.1 traceability {@link CobolProgram} annotation citing the
 *       original JCL identity, source path, and translation date.</li>
 *   <li>&sect;0.6.12 / &sect;0.7.4 no Spring, no preview features, no
 *       {@code java.io.File}, no {@code java.util.Date}/{@code Calendar},
 *       no {@code ThreadLocal}.</li>
 * </ul>
 *
 * <h2>Logging surface</h2>
 * <p>This main never logs card PANs or any other PCI-sensitive value: the
 * only values that appear in its log lines are CICS file IDs (5 fixed
 * strings), filesystem paths (configured by the operator), and run metadata
 * (run identifier, processing date, tenant). This satisfies the AAP
 * &sect;0.7.2 security mandate.
 *
 * @see CloseFileApp the symmetric &quot;close&quot; no-op companion utility
 * @see BatchRunContext the per-run context carried by {@link #BATCH_CTX}
 * @since 1.0.0
 */
@CobolProgram(
        value = "SDSF (OPENFIL.jcl)",
        sourcePath = "app/jcl/OPENFIL.jcl",
        translationDate = "2025-10-24",
        notes = "Translated for completeness per AAP §0.4.1. The original SDSF/CEMT "
                + "CICS file-open mechanism is replaced by verifying no-op path-"
                + "existence checks: the file-based Java runtime has no CICS region, "
                + "and file handles are acquired on demand by the file adapters. The "
                + "CICS region name CICSAWSA is intentionally NOT translated (no Java "
                + "equivalent). CXACAIX (the alternate-index PATH over the CARDXREF "
                + "KSDS) shares the underlying CCXREF file path in this translation. "
                + "Missing files do not abort the job (mirroring SDSF's best-effort "
                + "semantics) but DO bump the return code to 4 (warning) so the "
                + "operator notices."
)
public final class OpenFileApp {

    /**
     * SLF4J logger emitting structured (JSON via Logback at the binding) log
     * lines for job startup, per-file open acknowledgement, missing-file
     * warnings, and job completion. No card PAN ever appears in these log
     * statements (the only values logged are CICS file IDs, filesystem paths,
     * and run metadata), satisfying the AAP &sect;0.7.2 security mandate.
     */
    private static final Logger LOG = LoggerFactory.getLogger(OpenFileApp.class);

    /**
     * {@link ScopedValue} carrying the immutable per-run
     * {@link BatchRunContext} (run identifier, processing date, tenant) into
     * {@link #execute()}. Established at {@link #main(String[])} entry via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(OpenFileApp::execute)}
     * and read inside {@link #execute()} via {@code BATCH_CTX.get()}.
     *
     * <p>Per AAP &sect;0.6.6 (Batch Throughput Strategy) and &sect;0.7.4
     * (forbidden features), this field replaces any use of
     * {@link ThreadLocal} in new code. {@code ScopedValue} (JEP&nbsp;506,
     * finalized in Java&nbsp;25) is dramatically lighter than
     * {@code ThreadLocal} when the carrier count is large (e.g. millions of
     * virtual threads) and eliminates the forgotten-cleanup hazard inherent
     * to {@code ThreadLocal}.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    /**
     * The five CICS-managed file identifiers opened by
     * {@code app/jcl/OPENFIL.jcl}, preserved verbatim and in declaration
     * order. Iterated in {@link #execute()} to verify each file's
     * configured filesystem path and emit an INFO or WARN log line
     * accordingly.
     *
     * <p>The list is immutable ({@link List#of}). Order matters: the
     * mainframe issues the {@code CEMT SET FIL(...) OPE} commands in this
     * exact sequence, and preserving the order keeps any future log-line
     * diff or audit trail deterministic.
     */
    private static final List<String> CICS_FILE_IDS =
            List.of("TRANSACT", "CCXREF", "ACCTDAT", "CXACAIX", "USRSEC");

    /**
     * Mainframe-convention return code emitted when at least one of the
     * five CICS files is missing on disk. {@code 4} is the standard
     * &quot;warning&quot; severity in z/OS JCL; downstream batch readers
     * will still fail at their actual open call, but the warning gives the
     * operator a clear early signal.
     */
    private static final int RC_WARNING_MISSING_FILE = 4;

    /**
     * Maximum mainframe-convention return code. Values outside the
     * <code>[0, 16]</code> band are normalised to {@code 16} (severe error)
     * by the pattern-matching switch in {@link #main(String[])}.
     */
    private static final int RC_MAX = 16;

    /**
     * Composition-root utility class; instances are never meaningful.
     *
     * @throws AssertionError always &mdash; this constructor exists only to
     *                        prevent reflective instantiation
     */
    private OpenFileApp() {
        throw new AssertionError("OpenFileApp is not constructible");
    }

    /**
     * Java main entry point mirroring the {@code OPCIFIL EXEC PGM=SDSF} step
     * of {@code app/jcl/OPENFIL.jcl}.
     *
     * <p>Flow:
     * <ol>
     *   <li>Build a {@link BatchRunContext} from the runtime environment
     *       (JVM system properties &rarr; environment variables &rarr;
     *       per-component defaults &mdash; see
     *       {@link BatchRunContext#fromEnvironment()}).</li>
     *   <li>Bind the context to {@link #BATCH_CTX} and invoke
     *       {@link #execute()} inside the {@link ScopedValue} dynamic scope.
     *       The return value (an {@link Integer} from the auto-boxing
     *       conversion of {@code execute()}'s {@code int} return) carries
     *       the mainframe-style return code.</li>
     *   <li>Any exception thrown by {@code execute()} is logged at ERROR and
     *       converted to return code {@code 16} (severe error).</li>
     *   <li>The return code is mapped to a process-exit code via a
     *       pattern-matching {@code switch} expression with exhaustive
     *       coverage (no {@code default} branch).</li>
     * </ol>
     *
     * <p>Possible outcomes:
     * <ul>
     *   <li>Exit code {@code 0} &mdash; all five files exist on disk.</li>
     *   <li>Exit code {@code 4} &mdash; at least one file is missing
     *       (mainframe-style warning).</li>
     *   <li>Exit code {@code 16} &mdash; an uncaught exception occurred
     *       (mainframe-style severe error).</li>
     * </ul>
     *
     * @param args command-line arguments (currently unused; the original JCL
     *             has no parameters)
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();

        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(OpenFileApp::execute);
        } catch (Exception e) {
            LOG.error("OPENFIL job failed with uncaught exception", e);
            rc = RC_MAX;
        }

        // Pattern-matching switch (JEP 441, final since Java 21) over the
        // boxed Integer rc. The compiler verifies exhaustiveness via the
        // unconditional `case Integer i` pattern; no `default` branch is
        // required or permitted per AAP §0.6.7. The five explicit constants
        // mirror the standard mainframe return-code ladder (0=OK, 4=warning,
        // 8=error, 12=severe, 16=catastrophic); any other value is bucketed
        // into the closest safe code (out-of-band values become 16; in-band
        // non-standard values are passed through).
        int exitCode = switch (rc) {
            case 0 -> 0;
            case 4 -> 4;
            case 8 -> 8;
            case 12 -> 12;
            case 16 -> 16;
            case Integer i when i < 0 -> RC_MAX;
            case Integer i when i > RC_MAX -> RC_MAX;
            case Integer i -> i;
        };

        System.exit(exitCode);
    }

    /**
     * The translated logic: verify each of the five CICS-managed file
     * identifiers from {@code OPENFIL.jcl} has its configured filesystem
     * path present on disk and return the highest observed mainframe-style
     * return code.
     *
     * <p>Reads the bound {@link BatchRunContext} via {@link #BATCH_CTX} for
     * the job-start log line so that the run identifier and processing date
     * appear in structured-logging output for trace correlation.
     *
     * @return the highest mainframe-convention return code observed across
     *         the five files; {@code 0} if all exist, {@code 4} if any are
     *         missing (since SDSF reports CEMT errors but does not fail
     *         the job)
     */
    static int execute() {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("OPENFIL job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        int rc = 0;
        for (String cicsFileId : CICS_FILE_IDS) {
            // Translate "CEMT SET FIL(<id>) OPE" → verify the corresponding
            // filesystem path exists. On the mainframe SDSF would have issued
            // /F CICSAWSA,'CEMT SET FIL(<id>) OPE'; in the file-based
            // architecture there is no CICS region, so the per-file work
            // is purely a logging+existence-check acknowledgement.
            Path path = resolvePathForCicsFileId(cicsFileId);
            if (Files.exists(path)) {
                LOG.info("OPEN OK: cicsFileId={}, path={}", cicsFileId, path);
            } else {
                LOG.warn("OPEN MISSING: cicsFileId={}, path={} "
                                + "(file not found; treated as CC={} warning)",
                        cicsFileId, path, RC_WARNING_MISSING_FILE);
                rc = Math.max(rc, RC_WARNING_MISSING_FILE);
            }
        }

        LOG.info("OPENFIL job complete; rc={}", rc);
        return rc;
    }

    /**
     * Translates a CICS file identifier to its filesystem path via
     * configuration keys read from JVM system properties and environment
     * variables (12-factor precedence per AAP &sect;0.7.2: environment
     * variable &gt; system property &gt; compiled-in default).
     *
     * <p>The mapping mirrors the dataset names documented in
     * {@code java/application.properties.example} &sect;2 and the COBOL
     * dataset table in the root {@code README.md}:
     * <ul>
     *   <li>{@code TRANSACT} &rarr; {@code carddemo.file.transact.path}</li>
     *   <li>{@code CCXREF}   &rarr; {@code carddemo.file.cardxref.path}</li>
     *   <li>{@code ACCTDAT}  &rarr; {@code carddemo.file.acctdata.path}</li>
     *   <li>{@code CXACAIX}  &rarr; {@code carddemo.file.cardxref.path}
     *       (alternate-index PATH over the same KSDS)</li>
     *   <li>{@code USRSEC}   &rarr; {@code carddemo.file.usrsec.path}</li>
     * </ul>
     *
     * <p>The {@code switch} expression uses pattern matching with no
     * {@code default} branch per AAP &sect;0.6.7. The final
     * {@code case String s} pattern is an unconditional type pattern that
     * makes the switch exhaustive at compile time; if an unexpected file ID
     * is ever passed (which can only happen via reflection or test data
     * mutation, since {@link #CICS_FILE_IDS} is the only call site), the
     * switch throws {@link IllegalArgumentException} rather than silently
     * misrouting to a wrong path.
     *
     * <p>Visible for testing.
     *
     * @param fileId one of {@link #CICS_FILE_IDS}; must be non-{@code null}
     * @return the resolved filesystem path; never {@code null}
     * @throws IllegalArgumentException if {@code fileId} is not one of the
     *                                  five expected CICS identifiers
     */
    static Path resolvePathForCicsFileId(String fileId) {
        return switch (fileId) {
            case "TRANSACT" -> Path.of(
                    getProp("carddemo.file.transact.path", "./data/transact.dat"));
            case "CCXREF"   -> Path.of(
                    getProp("carddemo.file.cardxref.path", "./data/cardxref.dat"));
            case "ACCTDAT"  -> Path.of(
                    getProp("carddemo.file.acctdata.path", "./data/acctdata.dat"));
            // The CXACAIX alternate-index PATH is a view over the same
            // underlying CARDXREF KSDS, so it points at the same file in
            // the file-based translation. The two CEMT SET FIL commands on
            // the mainframe still address logically distinct CICS file
            // names (so they remain separate iterations in execute()), but
            // physically they share the same data file.
            case "CXACAIX"  -> Path.of(
                    getProp("carddemo.file.cardxref.path", "./data/cardxref.dat"));
            case "USRSEC"   -> Path.of(
                    getProp("carddemo.file.usrsec.path", "./data/usrsec.dat"));
            // No `default` per AAP §0.6.7. The unconditional type pattern
            // `case String s` makes the switch exhaustive at compile time
            // and routes any unexpected ID to a loud failure rather than a
            // silent misroute.
            case String s -> throw new IllegalArgumentException(
                    "Unknown CICS file id: " + s);
        };
    }

    /**
     * Reads a configuration value using the 12-factor precedence order
     * mandated by AAP &sect;0.7.2: environment variable first, then JVM
     * system property, then the supplied compiled-in default. Environment
     * variable keys are derived from property keys by upper-casing the
     * whole name and replacing dots and hyphens with underscores
     * (e.g., {@code carddemo.file.transact.path} &rarr;
     * {@code CARDDEMO_FILE_TRANSACT_PATH}).
     *
     * <p>Visible for testing.
     *
     * @param key          the property key (dotted lowercase); must be
     *                     non-{@code null}
     * @param defaultValue the compiled-in default value; must be
     *                     non-{@code null}
     * @return the resolved value; never {@code null}
     */
    static String getProp(String key, String defaultValue) {
        String envKey = key.toUpperCase(Locale.ROOT)
                .replace('.', '_')
                .replace('-', '_');
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return System.getProperty(key, defaultValue);
    }
}
