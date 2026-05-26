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
// java.nio.file.{Path, Files}, java.util.Locale, java.lang.System, and the
// implicit java.lang.* types used by the pattern-matching switch.
// This replaces what would otherwise be several individual imports per
// AAP §0.4.2 and §0.6.7.
import module java.base;

import com.blitzy.carddemo.adapter.file.FileCustomerRepository;
import com.blitzy.carddemo.application.customer.CbCus01C;
import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.CustomerRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/READCUST.jcl}
 * &mdash; the {@code //STEP05 EXEC PGM=CBCUS01C} step that sequentially reads
 * and dumps the CUSTDATA VSAM KSDS dataset via the COBOL program
 * {@code CBCUS01C} (translated to {@link CbCus01C}).
 *
 * <h2>Source artefact</h2>
 * <p>The original JCL ({@code app/jcl/READCUST.jcl}) executes:
 * <pre>{@code
 * //STEP05   EXEC PGM=CBCUS01C
 * //STEPLIB  DD DISP=SHR,DSN=AWS.M2.CARDDEMO.LOADLIB
 * //CUSTFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS
 * //SYSOUT   DD SYSOUT=*
 * //SYSPRINT DD SYSOUT=*
 * }</pre>
 * The Java translation wires {@link FileCustomerRepository} into
 * {@link CbCus01C} via the {@link CustomerRepository} port and invokes
 * {@code run()}, which streams every 500-byte CUSTOMER-RECORD (per copybook
 * {@code app/cpy/CVCUS01Y.cpy}) to stdout via the COBOL {@code DISPLAY} verb
 * (translated to SLF4J INFO logging inside {@link CbCus01C}).
 *
 * <h2>Hexagonal architecture (AAP &sect;0.3.6)</h2>
 * <p>The composition root in {@code carddemo-app} wires the concrete
 * {@link FileCustomerRepository} adapter into the {@link CbCus01C} use case
 * via plain constructor injection &mdash; there is no Spring container, no
 * Guice, and no service locator (AAP &sect;0.1.1). The use case depends
 * only on the {@link CustomerRepository} port; this driver is the single
 * place where the concrete file-backed adapter is instantiated. The
 * resource lifecycle of the adapter is managed by try-with-resources,
 * mirroring the COBOL {@code OPEN}/{@code CLOSE} discipline of paragraphs
 * {@code 0000-CUSTFILE-OPEN} and {@code 9000-CUSTFILE-CLOSE} in
 * {@code app/cbl/CBCUS01C.cbl}.
 *
 * <h2>Runtime configuration (AAP &sect;0.7.2, 12-factor)</h2>
 * <ul>
 *   <li>{@link #PROP_CUSTDATA_PATH} ({@code carddemo.file.custdata.path}) &rarr;
 *       the CUSTFILE DD input path. Defaults to
 *       {@link #DEFAULT_CUSTDATA_PATH} ({@code ./data/custdata.dat}).</li>
 * </ul>
 * Configuration is resolved by {@link #getProp(String, String)} with the
 * documented precedence:
 * <ol>
 *   <li>Environment variable (e.g., {@code CARDDEMO_FILE_CUSTDATA_PATH});</li>
 *   <li>JVM system property (e.g.,
 *       {@code -Dcarddemo.file.custdata.path=...});</li>
 *   <li>Compiled-in default.</li>
 * </ol>
 * Additionally, {@link BatchRunContext#fromEnvironment()} consumes the
 * {@code CARDDEMO_RUN_ID}, {@code CARDDEMO_PROCESSING_DATE}, and
 * {@code CARDDEMO_TENANT} variables for batch-run-context propagation.
 *
 * <h2>Process-exit semantics</h2>
 * <ul>
 *   <li>{@link #RC_OK} ({@code 0}) on a clean dump pass &mdash; mirrors the
 *       COBOL {@code APPL-AOK} (value {@code 0}) terminal state.</li>
 *   <li>{@link #RC_NO_INPUT} ({@code 4}) when the configured CUSTFILE
 *       input is absent &mdash; mirrors a JCL job that would have
 *       received a {@code JCL ERROR} or {@code FILE NOT FOUND} status,
 *       expressed via a non-zero step return code.</li>
 *   <li>{@link #RC_ERROR} ({@code 16}) on any abend or uncaught failure.
 *       The COBOL {@code Z-ABEND-PROGRAM} paragraph (which invokes
 *       {@code CALL 'CEE3ABD'} with ABCODE=999 at
 *       {@code app/cbl/CBCUS01C.cbl:L154-L158}) is translated by
 *       {@link CbCus01C} as an unchecked {@link IllegalStateException};
 *       this main class catches it via the generic
 *       {@link Exception} handler in {@link #main(String[])}.</li>
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
 * <h2>Mandated Java 25 idioms (per AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>JEP 511 Module Import Declarations</b> &mdash; the single
 *       {@code import module java.base;} statement at the top of this file
 *       replaces what would otherwise be several individual
 *       {@code java.lang.*}/{@code java.nio.file.*}/{@code java.util.*}
 *       imports.</li>
 *   <li><b>JEP 506 ScopedValue (Final)</b> &mdash; replaces
 *       {@code ThreadLocal} for batch-run-context propagation.</li>
 *   <li><b>Pattern-matching switch</b> &mdash; exhaustive on {@code Integer}
 *       with guarded patterns and a {@code case null}; NO {@code default}
 *       branch per AAP &sect;0.7.3 (exhaustiveness is the safety
 *       guarantee).</li>
 *   <li><b>{@code final} class with private constructor</b> &mdash; this
 *       composition root is intentionally not instantiable.</li>
 *   <li><b>Try-with-resources</b> on the {@link CustomerRepository} port
 *       (which extends {@link AutoCloseable}) &mdash; deterministically
 *       releases the underlying file channel even on abend.</li>
 * </ul>
 *
 * @see <a href="file:../../../../../../../../../../../app/jcl/READCUST.jcl">app/jcl/READCUST.jcl (source JCL)</a>
 * @see <a href="file:../../../../../../../../../../../app/cbl/CBCUS01C.cbl">app/cbl/CBCUS01C.cbl (source COBOL program)</a>
 * @see CbCus01C
 * @see CustomerRepository
 * @see FileCustomerRepository
 * @see BatchRunContext
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBCUS01C",
        sourcePath = "app/cbl/CBCUS01C.cbl",
        translationDate = "2025-10-24",
        notes = "Composition root for JCL job app/jcl/READCUST.jcl — "
                + "IDCAMS PRINT-style customer dump utility (CBCUS01C engine). "
                + "Wires FileCustomerRepository into CbCus01C via the "
                + "CustomerRepository port; runs sequentially. Translates COBOL "
                + "DISPLAY output to SLF4J INFO logs. AbendException is NOT "
                + "imported here (CbCus01C.run() surfaces CEE3ABD as an "
                + "unchecked IllegalStateException caught by main's generic "
                + "Exception handler)."
)
public final class ReadCustomerDumpApp {

    /**
     * SLF4J logger that replaces the COBOL {@code DISPLAY} verb for the
     * job-lifecycle events emitted by this main class (start banner, file
     * path resolution, completion banner, and uncaught-error reporting).
     */
    private static final Logger LOG = LoggerFactory.getLogger(ReadCustomerDumpApp.class);

    /**
     * Thread-scoped binding for this run's {@link BatchRunContext} per
     * AAP &sect;0.6.6 (JEP 506 ScopedValue, finalized in Java 25). Bound at
     * job entry by {@link #main(String[])} via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(...)} and read inside
     * {@link #execute()} via {@code BATCH_CTX.get()}.
     *
     * <p>Exposed as {@code public static final} so collaborators executing
     * within the bound scope (including future virtual-thread fan-out per
     * AAP &sect;0.6.6) can consult the same {@link ScopedValue} instance.
     * The {@code ScopedValue} object itself has no mutable state; only the
     * binding inside a {@code where(...).call(...)} scope is dynamic.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    /**
     * Dotted JVM-system-property key for the CUSTFILE DD path override.
     * Documented in {@code java/application.properties.example}.
     */
    static final String PROP_CUSTDATA_PATH = "carddemo.file.custdata.path";

    /**
     * Default CUSTFILE DD path when no configuration override is set.
     * Mirrors the AAP &sect;0.5.4 12-factor configuration convention of
     * shipping a working default for development/test environments.
     */
    static final String DEFAULT_CUSTDATA_PATH = "./data/custdata.dat";

    /**
     * Job-step return code {@code 0} &mdash; clean dump pass (the Java
     * equivalent of the COBOL {@code APPL-AOK} terminal state).
     */
    static final int RC_OK = 0;

    /**
     * Job-step return code {@code 4} &mdash; CUSTFILE input dataset absent
     * (mirrors a JCL {@code JCL ERROR} / {@code FILE NOT FOUND} step
     * return code).
     */
    static final int RC_NO_INPUT = 4;

    /**
     * Job-step return code {@code 16} &mdash; COBOL {@code CEE3ABD} abend
     * (ABCODE=999) or any uncaught Java failure. {@link CbCus01C} surfaces
     * the abend as an {@link IllegalStateException}, which propagates to
     * {@link #main(String[])} where it is mapped to this code.
     */
    static final int RC_ERROR = 16;

    /**
     * Prevents instantiation: this is a composition-root main class only.
     * Throws {@link AssertionError} to make a reflective bypass attempt
     * visibly fail rather than silently produce a useless instance.
     */
    private ReadCustomerDumpApp() {
        throw new AssertionError("ReadCustomerDumpApp is not constructible");
    }

    /**
     * Java main entry point bound by the {@code shade-read-customer-dump}
     * execution in {@code carddemo-app/pom.xml}. Mirrors the JCL step
     * {@code //STEP05 EXEC PGM=CBCUS01C} in {@code app/jcl/READCUST.jcl}.
     *
     * <p>The flow is:
     * <ol>
     *   <li>Build a {@link BatchRunContext} from environment / system
     *       properties via {@link BatchRunContext#fromEnvironment()}.</li>
     *   <li>Bind the context via {@link ScopedValue} and delegate to
     *       {@link #execute()}.</li>
     *   <li>On any uncaught exception (including
     *       {@link IllegalStateException} from
     *       {@link CbCus01C#run()}'s abend path), log the failure and
     *       fall through to {@link #RC_ERROR}.</li>
     *   <li>Map the resulting return code to a process exit code via an
     *       exhaustive pattern-matching switch (no {@code default} branch
     *       per AAP &sect;0.7.3) and invoke {@link System#exit(int)}.</li>
     * </ol>
     *
     * <p>The switch handles {@code null} defensively (although the prior
     * try/catch guarantees {@code rc} is non-{@code null} on every flow,
     * Java's pattern-matching switch on {@link Integer} requires
     * exhaustive null-handling for type safety).
     *
     * @param args command-line arguments (unused; all configuration flows
     *             through environment variables and JVM system properties
     *             per the 12-factor convention)
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(ReadCustomerDumpApp::execute);
        } catch (Exception e) {
            LOG.error("READCUST (CBCUS01C) job failed with uncaught exception", e);
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
     * Executes the READCUST job body. Translates the JCL step in
     * {@code app/jcl/READCUST.jcl} into a sequence of Java actions:
     *
     * <ol>
     *   <li>Log the job-start banner with {@link BatchRunContext} fields
     *       (runId, processingDate, tenant) consumed from the bound
     *       {@link #BATCH_CTX} {@link ScopedValue}.</li>
     *   <li>Resolve the CUSTFILE DD path from configuration via
     *       {@link #getProp(String, String)}.</li>
     *   <li>If the input file is absent, return {@link #RC_NO_INPUT}
     *       without instantiating the adapter (mirrors a JCL step that
     *       short-circuits when its input dataset is missing).</li>
     *   <li>Instantiate {@link FileCustomerRepository} as the concrete
     *       adapter, upcast to the {@link CustomerRepository} port
     *       (hexagonal-architecture isolation per AAP &sect;0.3.6), and
     *       wire it into a fresh {@link CbCus01C} use-case instance.</li>
     *   <li>Invoke {@link CbCus01C#run()} (translates COBOL PROCEDURE
     *       DIVISION). The {@code void} return aligns with the COBOL
     *       {@code GOBACK} verb &mdash; success is signalled by normal
     *       return, failure by an unchecked exception (which propagates
     *       to {@link #main(String[])}).</li>
     *   <li>Close the repository via try-with-resources, then log the
     *       job-complete banner and return {@link #RC_OK}.</li>
     * </ol>
     *
     * <p>The {@link CustomerRepository} variable type (rather than the
     * concrete {@link FileCustomerRepository}) enforces the
     * hexagonal-architecture rule that {@link CbCus01C} depends only on
     * the abstraction; substituting a different adapter (e.g., a future
     * JDBC implementation from {@code carddemo-adapter-db}) requires no
     * change to this driver beyond the constructor call.
     *
     * @return the job-step return code per AAP &sect;0.4.1:
     *         {@link #RC_OK} on success, {@link #RC_NO_INPUT} if the
     *         CUSTFILE input dataset is missing
     */
    static int execute() {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("READCUST (CBCUS01C) job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        Path custFilePath = SafePathResolver.resolveTrusted(PROP_CUSTDATA_PATH, DEFAULT_CUSTDATA_PATH);
        LOG.info("CUSTFILE DD -> path={}", custFilePath);

        if (!Files.exists(custFilePath)) {
            LOG.warn("READCUST: CUSTFILE input not found at {}; returning rc={}",
                    custFilePath, RC_NO_INPUT);
            return RC_NO_INPUT;
        }

        // Upcast to the port type at the variable declaration so CbCus01C is
        // wired against the abstraction, not the concrete adapter — the
        // hexagonal-architecture isolation rule from AAP §0.3.6. The
        // try-with-resources guarantees CustomerRepository.close() (which
        // overrides AutoCloseable.close() without checked-exception
        // declaration) is invoked deterministically — mirroring the
        // 9000-CUSTFILE-CLOSE paragraph in app/cbl/CBCUS01C.cbl.
        try (CustomerRepository repo = new FileCustomerRepository(custFilePath)) {
            LOG.info("READCUST: wiring CbCus01C; custdata={}", custFilePath);
            CbCus01C useCase = new CbCus01C(repo);
            useCase.run();
            LOG.info("READCUST (CBCUS01C) job complete; rc={}", RC_OK);
            return RC_OK;
        }
    }

    /**
     * 12-factor configuration lookup with environment-variable-first
     * precedence (per AAP &sect;0.5.4).
     *
     * <p>The dotted JVM-system-property key is normalised to the
     * environment-variable convention by upper-casing and replacing
     * {@code '.'} and {@code '-'} with {@code '_'} (e.g.,
     * {@code carddemo.file.custdata.path} &rarr;
     * {@code CARDDEMO_FILE_CUSTDATA_PATH}). The conversion uses
     * {@link Locale#ROOT} so it is locale-deterministic and not subject to
     * Turkish-locale {@code i}/{@code I} surprises.
     *
     * <p>Resolution precedence:
     * <ol>
     *   <li>Environment variable &mdash; preferred for container
     *       deployments where secrets / paths are injected via the
     *       process environment.</li>
     *   <li>JVM system property (e.g.,
     *       {@code -Dcarddemo.file.custdata.path=...}) &mdash; the
     *       developer-machine override.</li>
     *   <li>Compiled-in default &mdash; the fallback shipped with the
     *       jar; documented in
     *       {@code java/application.properties.example}.</li>
     * </ol>
     *
     * <p>Blank values (zero-length or whitespace-only) from environment
     * variable or system property are treated as &quot;unset&quot; and
     * fall through to the next precedence level. This avoids the
     * footgun of an accidentally empty variable masking the real default.
     *
     * @param key          the dotted property key (e.g.,
     *                     {@code carddemo.file.custdata.path}); must be
     *                     non-{@code null}
     * @param defaultValue the compiled-in default returned when no
     *                     override is found; must be non-{@code null}
     * @return the resolved configuration value &mdash; never
     *         {@code null}, never blank (unless {@code defaultValue}
     *         itself is intentionally so)
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
