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
 * Composition root for the Java translation of {@code app/jcl/CLOSEFIL.jcl}
 * &mdash; the {@code SDSF}-based batch job that, on the mainframe, issues
 * {@code CEMT SET FIL(<name>) CLO} commands to the {@code CICSAWSA} region to
 * close five CICS-managed VSAM files (used in conjunction with
 * {@link OpenFileApp} to take files offline for batch maintenance work such as
 * {@link DefineCardFileApp} or {@link DefineTransactionFileApp} runs).
 *
 * <h2>Source artifact</h2>
 * <p>{@code app/jcl/CLOSEFIL.jcl} step {@code CLCIFIL EXEC PGM=SDSF}, inline
 * {@code ISFIN}, contains the verbatim command list (one
 * {@code /F CICSAWSA,'CEMT SET FIL(<name>) CLO'} per CICS file):
 * <ol>
 *   <li>{@code TRANSACT}</li>
 *   <li>{@code CCXREF}</li>
 *   <li>{@code ACCTDAT}</li>
 *   <li>{@code CXACAIX}</li>
 *   <li>{@code USRSEC}</li>
 * </ol>
 * The five identifiers are preserved verbatim and in declaration order in
 * {@link #CICS_FILE_IDS}.
 *
 * <h2>Translation strategy &mdash; idiom-for-idiom (AAP &sect;0.7.1)</h2>
 * <p>The original JCL is a CICS-region administration utility: it tells the
 * online CICS region that VSAM files should be closed so that subsequent batch
 * jobs (define/reload) can take exclusive access. Per AAP &sect;0.6.12
 * (Acknowledged Architectural Override), CICS region administration is
 * <strong>out of scope</strong> for the file-based Java runtime &mdash; there
 * is no CICS region to communicate with. File handles in the file-based
 * architecture are acquired on demand by individual {@code java.nio.file}
 * channels and released via try-with-resources in the corresponding file
 * adapters; "closing" at the orchestration level is non-failable.
 *
 * <p>Per AAP &sect;0.4.1 (the JCL-to-Java mapping table marks this as
 * "{@code IEFBR14 no-op translated for completeness}"), the Java translation
 * is a logging-only no-op: it emits an INFO log line per file ID acknowledging
 * the close intent and always exits with return code {@code 0}.
 *
 * <h2>Execution shape</h2>
 * <p>Shaded as {@code carddemo-close-file.jar} by the {@code maven-shade-plugin}
 * configured in {@code java/carddemo-app/pom.xml}; run via:
 * {@snippet lang = "shell":
 * java -XX:+UseCompactObjectHeaders \
 *      -XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational \
 *      -jar carddemo-close-file.jar
 * }
 * Batch-run context (run identifier, processing date, tenant) is supplied via
 * JVM system properties or environment variables consumed by
 * {@link BatchRunContext#fromEnvironment()} and is propagated to the
 * {@link #execute()} method through the JEP&nbsp;506 {@link ScopedValue}
 * binding {@link #BATCH_CTX}.
 *
 * <h2>Mandates honored (AAP)</h2>
 * <ul>
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
 *       {@code java.io.File}, no {@code java.util.Date}/{@code Calendar}.</li>
 * </ul>
 *
 * @see OpenFileApp the symmetric "open" no-op companion utility
 * @see BatchRunContext the per-run context carried by {@link #BATCH_CTX}
 * @since 1.0.0
 */
@CobolProgram(
        value = "SDSF (CLOSEFIL.jcl)",
        sourcePath = "app/jcl/CLOSEFIL.jcl",
        translationDate = "2025-10-24",
        notes = "Translated for completeness per AAP §0.4.1. The original SDSF/CEMT "
                + "CICS file-close mechanism is replaced by a logging-only no-op: the "
                + "file-based Java runtime has no CICS region, and file handles are "
                + "released via try-with-resources in the file adapters. The CICS "
                + "region name CICSAWSA is intentionally NOT translated (no Java "
                + "equivalent). Closing always succeeds (rc=0) since there is no "
                + "external resource to release."
)
public final class CloseFileApp {

    /**
     * SLF4J logger emitting structured (JSON via Logback at the binding) log
     * lines for job startup, per-file close acknowledgement, and job
     * completion. No card PAN ever appears in these log statements (the only
     * values logged are CICS file IDs and run metadata), satisfying the AAP
     * &sect;0.7.2 security mandate.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CloseFileApp.class);

    /**
     * {@link ScopedValue} carrying the immutable per-run
     * {@link BatchRunContext} (run identifier, processing date, tenant) into
     * {@link #execute()}. Established at {@link #main(String[])} entry via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(CloseFileApp::execute)}
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
     * The five CICS-managed file identifiers closed by
     * {@code app/jcl/CLOSEFIL.jcl}, preserved verbatim and in declaration
     * order. Iterated in {@link #execute()} to emit one INFO log line per
     * file acknowledging the close intent.
     *
     * <p>The list is immutable ({@link List#of}). Order matters: the
     * mainframe issues the {@code CEMT SET FIL(...) CLO} commands in this
     * exact sequence, and preserving the order keeps any future log-line
     * diff or audit trail deterministic.
     */
    private static final List<String> CICS_FILE_IDS =
            List.of("TRANSACT", "CCXREF", "ACCTDAT", "CXACAIX", "USRSEC");

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
    private CloseFileApp() {
        throw new AssertionError("CloseFileApp is not constructible");
    }

    /**
     * Java main entry point mirroring the {@code CLCIFIL EXEC PGM=SDSF} step
     * of {@code app/jcl/CLOSEFIL.jcl}.
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
     * <p>In the file-based architecture {@code execute()} cannot fail, so the
     * exit code is always {@code 0}. The full mapping pipeline is retained
     * for symmetry with other JCL-step translations whose
     * {@code execute()} methods <em>can</em> return non-zero codes.
     *
     * @param args command-line arguments (currently unused; the original JCL
     *             has no parameters)
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();

        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(CloseFileApp::execute);
        } catch (Exception e) {
            LOG.error("CLOSEFIL job failed with uncaught exception", e);
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
     * The actual translated logic: log an acknowledgement for each of the
     * five CICS-managed file identifiers from {@code CLOSEFIL.jcl} and
     * return {@code 0} unconditionally.
     *
     * <p>Reads the bound {@link BatchRunContext} via {@link #BATCH_CTX} for
     * the job-start log line so that the run identifier and processing date
     * appear in structured-logging output for trace correlation.
     *
     * @return the mainframe-convention return code; always {@code 0} in the
     *         file-based architecture since there is no external resource
     *         (CICS region) to release
     */
    private static int execute() {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("CLOSEFIL job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        // Closing is non-failable in the file-based translation; just log
        // each intent. The original SDSF/CEMT mechanism would have issued
        // /F CICSAWSA,'CEMT SET FIL(<id>) CLO' per file; in the file-based
        // architecture there is no CICS region to receive the modify
        // command, so the per-file work is purely a logging acknowledgement.
        for (String cicsFileId : CICS_FILE_IDS) {
            LOG.info("CLOSE OK: cicsFileId={} (no-op in file-based architecture)",
                    cicsFileId);
        }

        LOG.info("CLOSEFIL job complete; rc=0");
        return 0;
    }
}
