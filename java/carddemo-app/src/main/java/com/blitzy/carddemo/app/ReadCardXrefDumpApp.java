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

// JEP 511 (finalized in Java 25): single declaration imports the entire
// java.base module — including java.lang.ScopedValue (JEP 506 Final),
// java.nio.file.{Path, Files}, java.util.Locale, java.lang.System, and
// the implicit java.lang.* types used by the pattern-matching switch
// (Integer for the case-pattern labels, Exception for the catch clause).
// This single import replaces what would otherwise be several individual
// imports per AAP §0.4.2 and §0.6.7.
import module java.base;

import com.blitzy.carddemo.adapter.file.FileCardXrefRepository;
import com.blitzy.carddemo.application.account.CbAct03C;
import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.CardXrefRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/READXREF.jcl}
 * &mdash; the {@code //STEP05 EXEC PGM=CBACT03C} step that sequentially reads
 * and dumps the CARDXREF VSAM KSDS dataset via the COBOL program
 * {@code CBACT03C} (translated to {@link CbAct03C}).
 *
 * <h2>Source artefact</h2>
 * <p>The original JCL ({@code app/jcl/READXREF.jcl}) executes:
 * <pre>{@code
 * //STEP05   EXEC PGM=CBACT03C
 * //STEPLIB  DD DISP=SHR,DSN=AWS.M2.CARDDEMO.LOADLIB
 * //XREFFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS
 * //SYSOUT   DD SYSOUT=*
 * //SYSPRINT DD SYSOUT=*
 * }</pre>
 * The Java translation wires {@link FileCardXrefRepository} into
 * {@link CbAct03C} via the {@link CardXrefRepository} port and invokes
 * {@link CbAct03C#run()}, which streams every 50-byte CARD-XREF-RECORD (per
 * copybook {@code app/cpy/CVACT03Y.cpy}) to stdout via the COBOL
 * {@code DISPLAY} verb (translated to SLF4J INFO logging inside
 * {@link CbAct03C}).
 *
 * <h2>Hexagonal architecture (AAP &sect;0.3.6)</h2>
 * <p>The composition root in {@code carddemo-app} wires the concrete
 * {@link FileCardXrefRepository} adapter into the {@link CbAct03C} use case
 * via plain constructor injection &mdash; there is no Spring container, no
 * Guice, and no service locator (AAP &sect;0.1.1 architectural override). The
 * use case depends only on the {@link CardXrefRepository} port; this driver
 * is the single place where the concrete file-backed adapter is
 * instantiated. The repository variable in {@link #execute()} is declared as
 * the port type ({@code CardXrefRepository}) rather than the concrete
 * {@link FileCardXrefRepository} to enforce the abstraction-only dependency
 * direction at compile time.
 *
 * <h2>PAN masking (AAP &sect;0.7.2)</h2>
 * <p>The CBACT03C COBOL program emits cross-reference records that contain
 * 16-character primary account numbers ({@code XREF-CARD-NUM PIC X(16)}) to
 * the SYSOUT DD; the Java translation masks all but the last 4 digits when
 * logging through SLF4J. PAN masking is enforced inside
 * {@code CardXrefRecord#toString()} (which uses the
 * {@code ************LLLL} mask) and inside {@link FileCardXrefRepository}'s
 * private {@code maskPan(String)} helper. Card numbers persisted to file via
 * any adapter save path are NOT altered &mdash; byte-for-byte file fidelity
 * per AAP &sect;0.1.3 requires the persisted PAN to remain the verbatim
 * 16-character value.
 *
 * <h2>DOUBLE-DISPLAY anomaly preservation (AAP &sect;0.7.1)</h2>
 * <p>The COBOL source for CBACT03C displays each successfully-read record
 * <strong>twice</strong>: once from inside the {@code 1000-XREFFILE-GET-NEXT}
 * paragraph (line 96 of {@code app/cbl/CBACT03C.cbl}) and once from the
 * main loop body (line 78 of the same file). The Java translation in
 * {@link CbAct03C} preserves this anomaly verbatim per the Refactor
 * Discipline Guidelines (AAP &sect;0.7.1) &mdash; <em>"Preserve existing
 * functionality and behavior exactly as-is, including edge cases ..."</em>.
 * This composition root does not alter that emission cadence; the
 * DOUBLE-DISPLAY is observable in the SLF4J log output (with the PAN masked
 * in each emission) when this jar is run against any CARDXREF input.
 *
 * <h2>Runtime configuration (AAP &sect;0.7.2, 12-factor)</h2>
 * <ul>
 *   <li>{@link #PROP_CARDXREF_PATH}
 *       ({@code carddemo.file.cardxref.path}) &rarr; the XREFFILE DD input
 *       path. Defaults to {@link #DEFAULT_CARDXREF_PATH}
 *       ({@code ./data/cardxref.dat}).</li>
 * </ul>
 * Configuration is resolved by {@link #getProp(String, String)} with the
 * documented precedence:
 * <ol>
 *   <li>Environment variable (e.g.,
 *       {@code CARDDEMO_FILE_CARDXREF_PATH});</li>
 *   <li>JVM system property (e.g.,
 *       {@code -Dcarddemo.file.cardxref.path=...});</li>
 *   <li>Compiled-in default.</li>
 * </ol>
 * Additionally, {@link BatchRunContext#fromEnvironment()} consumes the
 * {@code CARDDEMO_RUN_ID}, {@code CARDDEMO_PROCESSING_DATE}, and
 * {@code CARDDEMO_TENANT} variables for batch-run-context propagation.
 *
 * <h2>Process-exit semantics</h2>
 * <ul>
 *   <li>{@link #RC_OK} ({@code 0}) on a clean dump pass &mdash; mirrors
 *       the COBOL {@code APPL-AOK} (value {@code 0}) terminal state from
 *       {@code app/cbl/CBACT03C.cbl} line 62.</li>
 *   <li>{@link #RC_NO_INPUT} ({@code 4}) when the configured XREFFILE
 *       input is absent &mdash; mirrors a JCL job that would have
 *       received a {@code JCL ERROR} or {@code FILE NOT FOUND} status,
 *       expressed via a non-zero step return code.</li>
 *   <li>{@link #RC_ERROR} ({@code 16}) on any abend or uncaught failure.
 *       {@link CbAct03C#run()} surfaces I/O failures via its {@code int}
 *       return value ({@link CbAct03C#APPL_ERROR} = 12 falls through this
 *       app's switch as a pass-through value); any uncaught
 *       {@link Exception} propagating out of {@link ScopedValue#call} is
 *       logged and mapped to RC_ERROR by {@link #main(String[])}.</li>
 * </ul>
 *
 * <h2>Batch-run context propagation (AAP &sect;0.6.6)</h2>
 * {@link BatchRunContext} is bound via {@link ScopedValue} (JEP 506 Final
 * in Java 25) for the duration of the job. {@link ThreadLocal} is
 * FORBIDDEN in new code per AAP &sect;0.7.4 and is replaced entirely by
 * {@code ScopedValue}; the scope binding has no per-thread heap cost and
 * propagates to child virtual threads. The context is constructed once at
 * job entry via {@link BatchRunContext#fromEnvironment()} and consulted by
 * {@link #execute()} via {@code BATCH_CTX.get()} for logging.
 *
 * <h2>No virtual-thread fan-out</h2>
 * Per AAP &sect;0.6.6 (<em>"Any reordering changes observable output and is
 * FORBIDDEN"</em>) and the DOUBLE-DISPLAY anomaly described above, this
 * driver does NOT fan out CARDXREF iteration across virtual threads. The
 * COBOL program's deterministic emission order (interleaved record-by-record
 * with the in-paragraph DISPLAY and the main-loop DISPLAY) must be
 * preserved byte-for-byte; virtual-thread fan-out would scramble that
 * interleaving and break byte-for-byte parity with the COBOL baseline.
 *
 * <h2>Mandated Java 25 idioms (per AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>JEP 511 Module Import Declarations</b> &mdash; the single
 *       {@code import module java.base;} statement at the top of this
 *       file replaces what would otherwise be several individual
 *       {@code java.lang.*}/{@code java.nio.file.*}/{@code java.util.*}
 *       imports.</li>
 *   <li><b>JEP 506 ScopedValue (Final)</b> &mdash; replaces
 *       {@code ThreadLocal} for batch-run-context propagation.</li>
 *   <li><b>Pattern-matching switch</b> &mdash; exhaustive on
 *       {@link Integer} with guarded patterns and a {@code case null};
 *       NO {@code default} branch per AAP &sect;0.7.3 (exhaustiveness is
 *       the safety guarantee).</li>
 *   <li><b>{@code final} class with private constructor</b> &mdash; this
 *       composition root is intentionally not instantiable.</li>
 *   <li><b>Try-with-resources</b> on the {@link CardXrefRepository} port
 *       (which extends {@link AutoCloseable}) &mdash; deterministically
 *       releases the underlying file channel even on abend, mirroring
 *       the COBOL {@code 9000-XREFFILE-CLOSE} paragraph in
 *       {@code app/cbl/CBACT03C.cbl} lines 136-152.</li>
 * </ul>
 *
 * @see CbAct03C
 * @see CardXrefRepository
 * @see FileCardXrefRepository
 * @see BatchRunContext
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBACT03C",
        sourcePath = "app/cbl/CBACT03C.cbl",
        translationDate = "2025-10-24",
        notes = "Composition root for JCL job app/jcl/READXREF.jcl — "
                + "IDCAMS PRINT-style card cross-reference dump utility "
                + "(CBACT03C engine). Wires FileCardXrefRepository into "
                + "CbAct03C via the CardXrefRepository port; runs "
                + "sequentially. PAN is masked in log output (last 4 "
                + "digits only) per AAP §0.7.2 but preserved verbatim in "
                + "file storage. DOUBLE-DISPLAY anomaly from CBACT03C is "
                + "preserved inside CbAct03C. AbendException is NOT "
                + "imported here (CbAct03C.run() surfaces failures via int "
                + "APPL-RESULT return value; uncaught exceptions are "
                + "mapped to RC_ERROR by main's generic Exception "
                + "handler)."
)
public final class ReadCardXrefDumpApp {

    /**
     * SLF4J logger that replaces the COBOL {@code DISPLAY} verb for the
     * job-lifecycle events emitted by this main class (start banner,
     * file-path resolution, completion banner, and uncaught-error
     * reporting). The concrete logging backend (logback-classic 1.5.12
     * per AAP &sect;0.5.1) is provided at runtime by the carddemo-app
     * composition-root jar.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ReadCardXrefDumpApp.class);

    /**
     * Thread-scoped binding for this run's {@link BatchRunContext} per AAP
     * &sect;0.6.6 (JEP 506 ScopedValue, finalized in Java 25). Bound at job
     * entry by {@link #main(String[])} via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(...)} and read inside
     * {@link #execute()} via {@code BATCH_CTX.get()}.
     *
     * <p>Exposed as {@code public static final} (per the file schema's
     * {@code members_exposed} contract) so collaborators executing within
     * the bound scope (including any future virtual-thread fan-out per AAP
     * &sect;0.6.6) can consult the same {@link ScopedValue} instance. The
     * {@code ScopedValue} object itself has no mutable state; only the
     * binding inside a {@code where(...).call(...)} scope is dynamic.
     *
     * <p>This is the per-app local {@code ScopedValue} declared per
     * composition-root convention. The sister-module {@link BatchRunContext}
     * also declares a public {@code BATCH_CTX} for batch-driver collaborators;
     * each main class binds its own instance for job-isolated propagation
     * within its single shaded jar &mdash; the per-jar convention preserves
     * the "one shaded jar per executable program" mandate of AAP &sect;0.4.1.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    /**
     * Dotted JVM-system-property key for the XREFFILE DD path override.
     * Documented in {@code java/application.properties.example}. The
     * equivalent environment-variable form is
     * {@code CARDDEMO_FILE_CARDXREF_PATH}, computed by
     * {@link #getProp(String, String)} via upper-case + dot/dash to
     * underscore conversion using {@link Locale#ROOT}.
     */
    static final String PROP_CARDXREF_PATH = "carddemo.file.cardxref.path";

    /**
     * Default XREFFILE DD path when no configuration override is set.
     * Mirrors the AAP &sect;0.5.4 12-factor configuration convention of
     * shipping a working default for development/test environments. The
     * relative path is resolved against the JVM working directory at
     * runtime so deployments can supply an absolute path via the
     * environment / system-property override above without modifying this
     * shipped default.
     */
    static final String DEFAULT_CARDXREF_PATH = "./data/cardxref.dat";

    /**
     * Job-step return code {@code 0} &mdash; clean dump pass (the Java
     * equivalent of the COBOL {@code APPL-AOK} terminal state at
     * {@code app/cbl/CBACT03C.cbl} line 62). Returned from
     * {@link #execute()} when {@link CbAct03C#run()} returns
     * {@link CbAct03C#APPL_AOK} (value {@code 0}).
     */
    static final int RC_OK = 0;

    /**
     * Job-step return code {@code 4} &mdash; XREFFILE input dataset absent
     * (mirrors a JCL {@code JCL ERROR} / {@code FILE NOT FOUND} step
     * return code). Returned by {@link #execute()} when the pre-check via
     * {@link Files#exists(Path, java.nio.file.LinkOption...)} reports the
     * configured path is missing &mdash; a fast-fail short-circuit that
     * avoids running the use case against a non-existent file.
     */
    static final int RC_NO_INPUT = 4;

    /**
     * Job-step return code {@code 16} &mdash; COBOL {@code CEE3ABD} abend
     * (ABCODE=999 from {@code app/cbl/CBACT03C.cbl} line 157) or any
     * uncaught Java failure. The COBOL program's {@code 9999-ABEND-PROGRAM}
     * paragraph is translated by {@link CbAct03C} as a return of
     * {@link CbAct03C#APPL_ERROR} (value {@code 12}); this main class
     * passes that value through the pattern-matching switch as-is (per
     * standard JCL return-code semantics where 12 is itself a well-known
     * step return code) and maps any out-of-band value (negative or
     * &gt;16) to {@code 16} via the pattern-matching switch in
     * {@link #main(String[])}.
     */
    static final int RC_ERROR = 16;

    /**
     * Prevents instantiation: this is a composition-root main class only.
     * Throws {@link AssertionError} to make a reflective bypass attempt
     * visibly fail rather than silently produce a useless instance.
     */
    private ReadCardXrefDumpApp() {
        throw new AssertionError("ReadCardXrefDumpApp is not constructible");
    }

    /**
     * Java main entry point bound by the {@code shade-read-card-xref-dump}
     * execution in {@code carddemo-app/pom.xml}. Mirrors the JCL step
     * {@code //STEP05 EXEC PGM=CBACT03C} in {@code app/jcl/READXREF.jcl}.
     *
     * <p>The flow is:
     * <ol>
     *   <li>Build a {@link BatchRunContext} from environment / system
     *       properties via {@link BatchRunContext#fromEnvironment()}.</li>
     *   <li>Bind the context via {@link ScopedValue} and delegate to
     *       {@link #execute()}.</li>
     *   <li>On any uncaught exception, log the failure and fall through
     *       to {@link #RC_ERROR}.</li>
     *   <li>Map the resulting return code to a process exit code via an
     *       exhaustive pattern-matching switch (no {@code default}
     *       branch per AAP &sect;0.7.3) and invoke
     *       {@link System#exit(int)}.</li>
     * </ol>
     *
     * <p>The switch handles {@code null} defensively (although the prior
     * try/catch guarantees {@code rc} is non-{@code null} on every flow,
     * Java's pattern-matching switch on {@link Integer} requires
     * exhaustive null-handling for type safety). Common JCL return codes
     * 0, 4, 8, 12, and 16 are passed through unchanged; out-of-band values
     * (negative or greater than 16) are clamped to {@link #RC_ERROR} per
     * the AAP &sect;0.7.1 mandate to "preserve current behavior" while
     * keeping observable exit codes well-defined.
     *
     * @param args command-line arguments (unused; all configuration flows
     *             through environment variables and JVM system properties
     *             per the 12-factor convention)
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(ReadCardXrefDumpApp::execute);
        } catch (Exception e) {
            LOG.error("READXREF (CBACT03C) job failed with uncaught exception", e);
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

    /**
     * Executes the READXREF job body. Translates the JCL step in
     * {@code app/jcl/READXREF.jcl} into a sequence of Java actions:
     *
     * <ol>
     *   <li>Log the job-start banner with {@link BatchRunContext} fields
     *       (runId, processingDate, tenant) consumed from the bound
     *       {@link #BATCH_CTX} {@link ScopedValue}.</li>
     *   <li>Resolve the XREFFILE DD path from configuration via
     *       {@link #getProp(String, String)}.</li>
     *   <li>If the input file is absent, return {@link #RC_NO_INPUT}
     *       without instantiating the adapter (mirrors a JCL step that
     *       short-circuits when its input dataset is missing).</li>
     *   <li>Instantiate {@link FileCardXrefRepository} as the concrete
     *       adapter, upcast to the {@link CardXrefRepository} port
     *       (hexagonal-architecture isolation per AAP &sect;0.3.6), and
     *       wire it into a fresh {@link CbAct03C} use-case instance.</li>
     *   <li>Invoke {@link CbAct03C#run()} (translates the COBOL
     *       PROCEDURE DIVISION at {@code app/cbl/CBACT03C.cbl}
     *       lines 70-87). The return value is the COBOL
     *       {@code APPL-RESULT}: {@code 0} on success or {@code 12} on
     *       any I/O failure that would have triggered the
     *       {@code 9999-ABEND-PROGRAM} paragraph in the original.</li>
     *   <li>Close the repository via try-with-resources, then log the
     *       job-complete banner and return the use case's
     *       {@code APPL-RESULT}.</li>
     * </ol>
     *
     * <p>The {@link CardXrefRepository} variable type (rather than the
     * concrete {@link FileCardXrefRepository}) enforces the
     * hexagonal-architecture rule that {@link CbAct03C} depends only on
     * the abstraction; substituting a different adapter (e.g., a future
     * JDBC implementation from {@code carddemo-adapter-db}) requires no
     * change to this driver beyond the constructor call.
     *
     * <p>Package-private (not {@code private}) so that adjacent unit tests
     * in the same package can drive {@link #execute()} directly under a
     * pre-bound {@link ScopedValue} scope without invoking
     * {@link System#exit(int)}.
     *
     * @return the job-step return code per AAP &sect;0.4.1:
     *         {@link #RC_OK} on a clean dump pass,
     *         {@link #RC_NO_INPUT} if the XREFFILE input dataset is
     *         missing, or the use case's {@code APPL-RESULT} value
     *         (typically {@link CbAct03C#APPL_AOK} on success or
     *         {@link CbAct03C#APPL_ERROR} on COBOL-side abend) otherwise.
     *         The caller in {@link #main(String[])} maps non-standard
     *         values to {@link #RC_ERROR} via the pattern-matching switch.
     */
    static int execute() {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("READXREF (CBACT03C) job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        Path xrefFilePath = SafePathResolver.resolveTrusted(PROP_CARDXREF_PATH, DEFAULT_CARDXREF_PATH);
        LOG.info("XREFFILE DD -> path={}", xrefFilePath);

        if (!Files.exists(xrefFilePath)) {
            LOG.warn("READXREF: XREFFILE input not found at {}; returning rc={}",
                    xrefFilePath, RC_NO_INPUT);
            return RC_NO_INPUT;
        }

        // Upcast to the port type at the variable declaration so CbAct03C is
        // wired against the abstraction, not the concrete adapter — the
        // hexagonal-architecture isolation rule from AAP §0.3.6. The
        // try-with-resources guarantees CardXrefRepository.close() (which
        // overrides AutoCloseable.close() without checked-exception
        // declaration in FileCardXrefRepository) is invoked
        // deterministically — mirroring the 9000-XREFFILE-CLOSE paragraph
        // in app/cbl/CBACT03C.cbl lines 136-152.
        try (CardXrefRepository repo = new FileCardXrefRepository(xrefFilePath)) {
            LOG.info("READXREF: wiring CbAct03C; cardxref={}", xrefFilePath);
            CbAct03C useCase = new CbAct03C(repo);
            int applResult = useCase.run();
            LOG.info("READXREF (CBACT03C) job complete; applResult={}", applResult);
            return applResult;
        }
    }

    /**
     * 12-factor configuration lookup with environment-variable-first
     * precedence (per AAP &sect;0.5.4).
     *
     * <p>The dotted JVM-system-property key is normalised to the
     * environment-variable convention by upper-casing and replacing
     * {@code '.'} and {@code '-'} with {@code '_'} (e.g.,
     * {@code carddemo.file.cardxref.path} &rarr;
     * {@code CARDDEMO_FILE_CARDXREF_PATH}). The conversion uses
     * {@link Locale#ROOT} so it is locale-deterministic and not subject to
     * Turkish-locale {@code i}/{@code I} surprises.
     *
     * <p>Resolution precedence:
     * <ol>
     *   <li>Environment variable &mdash; preferred for container
     *       deployments where secrets / paths are injected via the
     *       process environment.</li>
     *   <li>JVM system property (e.g.,
     *       {@code -Dcarddemo.file.cardxref.path=...}) &mdash; the
     *       developer-machine override.</li>
     *   <li>Compiled-in default &mdash; the fallback shipped with the
     *       jar; documented in
     *       {@code java/application.properties.example}.</li>
     * </ol>
     *
     * <p>Blank values (zero-length or whitespace-only) from environment
     * variable or system property are treated as &quot;unset&quot; and
     * fall through to the next precedence level. This avoids the footgun
     * of an accidentally empty variable masking the real default.
     *
     * <p>Package-private (not {@code private}) so that adjacent unit tests
     * in the same package can verify the precedence rules directly.
     *
     * @param key          the dotted property key (e.g.,
     *                     {@code carddemo.file.cardxref.path}); must be
     *                     non-{@code null}
     * @param defaultValue the compiled-in default returned when no
     *                     override is found; must be non-{@code null}
     * @return the resolved configuration value &mdash; never {@code null},
     *         never blank (unless {@code defaultValue} itself is
     *         intentionally so)
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
