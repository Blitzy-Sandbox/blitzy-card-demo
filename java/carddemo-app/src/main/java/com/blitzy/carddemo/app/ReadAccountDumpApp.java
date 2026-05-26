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

// JEP 511 (finalized in Java 25 per AAP §0.4.2 and §0.6.7): a single module
// import declaration brings every package exported by java.base into scope.
// This replaces what would otherwise be separate imports for
//   - java.lang.ScopedValue      (JEP 506 Final; moved from java.util.concurrent
//                                 to java.lang when finalized in Java 25)
//   - java.nio.file.Path         (AAP §0.6.5 mandated file-I/O abstraction)
//   - java.nio.file.Files        (used to existence-check the ACCTFILE input)
//   - java.util.Locale           (Locale.ROOT for deterministic env-var key
//                                 derivation in getProp())
//   - java.lang.System           (getenv / getProperty / exit; auto-imported via
//                                 java.lang but enumerated here for clarity)
//   - java.lang.Integer          (boxed-int pattern matching in main())
//   - java.lang.Exception        (catch clause for the ScopedValue.call()
//                                 declared throws Exception contract)
import module java.base;

// Internal imports — STRICTLY from this file's depends_on_files set
// (AAP §0.5.3 import-refactoring whitelist). Any import that is not one
// of these five would violate the dependency rule.
import com.blitzy.carddemo.adapter.file.FileAccountRepository;
import com.blitzy.carddemo.application.account.CbAct01C;
import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.AccountRepository;

// External imports — SLF4J 2.0.16 facade per AAP §0.5.1; the concrete
// logback-classic 1.5.12 backend is supplied at runtime by the
// carddemo-app composition root.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root (Java {@code main} class) for the translation of
 * {@code app/jcl/READACCT.jcl} &mdash; the JCL job that invokes the COBOL
 * program {@code CBACT01C} to sequentially read and DISPLAY-dump every
 * record in the {@code ACCTDATA} VSAM KSDS master file.
 *
 * <h2>Source artefact provenance</h2>
 * <p>The original JCL ({@code app/jcl/READACCT.jcl}) declares one
 * executable step:
 * <pre>{@code
 * //STEP05   EXEC PGM=CBACT01C
 * //STEPLIB  DD DISP=SHR,DSN=AWS.M2.CARDDEMO.LOADLIB
 * //ACCTFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
 * //SYSOUT   DD SYSOUT=*
 * //SYSPRINT DD SYSOUT=*
 * }</pre>
 *
 * <p>Each DD statement maps to a Java construct per the AAP &sect;0.4.2
 * transformation rules:
 * <table>
 *   <caption>JCL DD &rarr; Java mapping</caption>
 *   <tr><th>JCL DD</th><th>Java translation</th></tr>
 *   <tr><td>{@code STEP05 EXEC PGM=CBACT01C}</td>
 *       <td>{@link #main(String[])} invoking {@link CbAct01C#run()}</td></tr>
 *   <tr><td>{@code STEPLIB DD ... LOADLIB}</td>
 *       <td>No equivalent &mdash; the Java classpath replaces the
 *           STEPLIB concatenation</td></tr>
 *   <tr><td>{@code ACCTFILE DD ... ACCTDATA.VSAM.KSDS}</td>
 *       <td>{@link FileAccountRepository} constructed with the
 *           {@link java.nio.file.Path Path} resolved from
 *           {@code carddemo.file.acctdata.path} (12-factor configuration
 *           per AAP &sect;0.5.4)</td></tr>
 *   <tr><td>{@code SYSOUT DD SYSOUT=*}</td>
 *       <td>SLF4J standard-out appender configured by the carddemo-app
 *           runtime logback configuration</td></tr>
 *   <tr><td>{@code SYSPRINT DD SYSOUT=*}</td>
 *       <td>SLF4J standard-out appender (same)</td></tr>
 * </table>
 *
 * <h2>Architectural authority</h2>
 * <ul>
 *   <li>AAP &sect;0.1.1 &mdash; one shaded jar per executable program;
 *       this class is the {@code carddemo-read-account-dump.jar} main.</li>
 *   <li>AAP &sect;0.3.2 &mdash; hexagonal composition root: this class
 *       wires the file-backed adapter ({@link FileAccountRepository})
 *       into the use case ({@link CbAct01C}) via plain Java constructor
 *       injection. The use case depends only on the
 *       {@link AccountRepository} port (the abstraction); the concrete
 *       adapter is wired here at the outermost ring of the hexagon.</li>
 *   <li>AAP &sect;0.4.1 &mdash; "JCL EXEC PGM= step &rarr; main entry
 *       point in {@code carddemo-app}, packaged as a shaded jar".</li>
 *   <li>AAP &sect;0.6.5 &mdash; all file I/O uses
 *       {@link java.nio.file java.nio.file}; {@link java.io.File} is
 *       FORBIDDEN.</li>
 *   <li>AAP &sect;0.6.6 &mdash; batch-run context propagation uses
 *       {@link ScopedValue} (JEP 506 Final); {@link ThreadLocal} is
 *       FORBIDDEN.</li>
 *   <li>AAP &sect;0.6.7 &mdash; pattern-matching switch with
 *       compiler-enforced exhaustiveness, no {@code default} branch.</li>
 *   <li>AAP &sect;0.7.1 &mdash; "Document every translated program with
 *       a Javadoc header citing the original PROGRAM-ID, source file
 *       path, and the date of translation."</li>
 * </ul>
 *
 * <h2>Runtime configuration (12-factor per AAP &sect;0.5.4)</h2>
 * <p>Resolved via {@link #getProp(String, String)} with the documented
 * precedence: environment variable first, JVM system property second,
 * compiled-in default last.
 * <table>
 *   <caption>Configuration inputs</caption>
 *   <tr><th>Property key</th>
 *       <th>Environment variable</th>
 *       <th>Default</th></tr>
 *   <tr><td>{@code carddemo.file.acctdata.path}</td>
 *       <td>{@code CARDDEMO_FILE_ACCTDATA_PATH}</td>
 *       <td>{@code ./data/acctdata.dat}</td></tr>
 * </table>
 *
 * <p>The batch-run context ({@link BatchRunContext}) is sourced from
 * environment / system properties via {@link BatchRunContext#fromEnvironment()}:
 * {@code CARDDEMO_RUN_ID}, {@code CARDDEMO_PROCESSING_DATE},
 * {@code CARDDEMO_TENANT}.
 *
 * <h2>Process exit codes (translated COBOL APPL-RESULT semantics)</h2>
 * <p>The COBOL program uses an {@code APPL-RESULT} register with
 * {@code 88 APPL-AOK VALUE 0} and {@code 88 APPL-EOF VALUE 16} (per
 * {@code app/cbl/CBACT01C.cbl:L61-L63}) and {@code MOVE 12 TO APPL-RESULT}
 * on I/O failure (lines L99, L101, L139, L152, L157). The Java {@code run()}
 * surfaces these via {@link CbAct01C#APPL_AOK} (0) and
 * {@link CbAct01C#APPL_ERROR} (12). This {@code main} maps the return
 * code into a process exit code via the pattern-matching switch below;
 * unexpected values (negative, or &gt;16) are normalised to
 * {@link #RC_ERROR} so an operator's job-scheduling system sees a
 * recognisable failure value.
 *
 * <h2>Forbidden idioms (per AAP &sect;0.6.7, &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring / Spring Boot &mdash; plain Java constructor injection
 *       only; this class instantiates its collaborators directly with
 *       {@code new}.</li>
 *   <li>No {@link ThreadLocal} &mdash; cross-method and cross-virtual-
 *       thread context propagation uses {@link ScopedValue} via
 *       {@link #BATCH_CTX}.</li>
 *   <li>No {@link java.io.File} &mdash; only {@link java.nio.file.Path}
 *       and {@link java.nio.file.Files}.</li>
 *   <li>No {@code default} branch in the exit-code switch &mdash;
 *       exhaustiveness is enforced by the compiler via the total
 *       {@code case Integer i -&gt; i;} pattern.</li>
 *   <li>No {@code --enable-preview} JVM flag required &mdash; this class
 *       uses only finalized JEPs (511 module imports, 506 ScopedValue,
 *       and 441 pattern-matching switch which has been final since
 *       Java 21).</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>This class is a stateless utility (private no-op constructor). The
 * single {@link ScopedValue} static field is itself thread-safe by
 * construction. The {@link #main(String[])} method is intended to be
 * invoked once per JVM lifetime (a fresh JVM is launched per shaded jar
 * invocation, matching the COBOL one-job-step-per-execution model).
 *
 * @see CbAct01C
 * @see FileAccountRepository
 * @see AccountRepository
 * @see BatchRunContext
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBACT01C",
        sourcePath = "app/cbl/CBACT01C.cbl",
        translationDate = "2025-10-24",
        notes = "Composition root (Java main) for the JCL job app/jcl/READACCT.jcl "
                + "(STEP05 EXEC PGM=CBACT01C). Wires FileAccountRepository (from "
                + "carddemo-adapter-file) into CbAct01C (from carddemo-application) "
                + "via plain Java constructor injection per AAP §0.3.2 hexagonal "
                + "composition root; establishes a ScopedValue<BatchRunContext> "
                + "binding at job entry per AAP §0.6.6 (JEP 506 Final); maps the "
                + "use-case APPL-RESULT return code to a process exit code via an "
                + "exhaustive pattern-matching switch per AAP §0.6.7 (no default "
                + "branch). Shaded as carddemo-read-account-dump.jar by the "
                + "shade-read-account-dump execution in carddemo-app/pom.xml."
)
public final class ReadAccountDumpApp {

    // -------------------------------------------------------------------
    // Static fields
    // -------------------------------------------------------------------

    /**
     * SLF4J logger for job-lifecycle and error-reporting events. Bound to
     * {@code org.slf4j:slf4j-api:2.0.16} (per AAP &sect;0.5.1); the
     * concrete backend ({@code ch.qos.logback:logback-classic:1.5.12})
     * is supplied at runtime by the {@code carddemo-app} composition
     * root's classpath. All log messages use SLF4J's parameterized
     * {@code {}} placeholders so that argument {@code toString()} is
     * deferred until the log level is enabled (a documented SLF4J best
     * practice for reducing string-formatting overhead in disabled-level
     * call sites).
     */
    private static final Logger LOG = LoggerFactory.getLogger(ReadAccountDumpApp.class);

    /**
     * Thread-scoped binding for this job's {@link BatchRunContext}.
     * Established by {@link #main(String[])} via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(...)} and consumed
     * by {@link #execute()} (and any child virtual threads spawned by
     * collaborators that may inherit the scope) via {@code BATCH_CTX.get()}.
     *
     * <p>Per AAP &sect;0.6.6 (JEP 506 Final), {@link ScopedValue}
     * <strong>replaces {@link ThreadLocal} entirely in new CardDemo
     * code</strong>. {@code ThreadLocal} is FORBIDDEN per AAP &sect;0.7.4
     * and would forfeit the cross-virtual-thread propagation that
     * {@code ScopedValue} provides.
     *
     * <p>This field is intentionally local to {@link ReadAccountDumpApp};
     * it is distinct from {@link BatchRunContext#BATCH_CTX} (the
     * module-level scoped value used by {@code carddemo-batch} drivers).
     * Each composition-root main class owns its own
     * {@link ScopedValue} so that bindings established by sibling main
     * classes never collide. The exports schema (AAP &sect;0.4.1)
     * explicitly enumerates {@code BATCH_CTX} alongside
     * {@code main(String[])} as the two public members of this class.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // -------------------------------------------------------------------
    // Configuration keys and compiled-in defaults
    // -------------------------------------------------------------------

    /**
     * JVM system-property key for the ACCTFILE input path. The matching
     * environment variable is derived deterministically by
     * {@link #getProp(String, String)} as
     * {@code CARDDEMO_FILE_ACCTDATA_PATH} (uppercase, dots and dashes
     * replaced with underscores). Documented in
     * {@code java/application.properties.example}.
     */
    private static final String PROP_ACCTDATA_PATH = "carddemo.file.acctdata.path";

    /**
     * Compiled-in default for the ACCTFILE input path used when neither
     * {@link #PROP_ACCTDATA_PATH} (as a system property) nor its
     * environment-variable form is set or is non-blank. The relative
     * path {@code ./data/acctdata.dat} is consistent with the AAP
     * &sect;0.4.1 default location for ACCTDATA in the file-based
     * deployment.
     */
    private static final String DEFAULT_ACCTDATA_PATH = "./data/acctdata.dat";

    // -------------------------------------------------------------------
    // Process exit codes (mirror the JCL/operator conventions: 0 / 4 /
    // 8 / 12 / 16 with 16 reserved for catastrophic failure). The
    // mapping from CbAct01C.APPL_RESULT to these values is performed in
    // main() via the exhaustive pattern-matching switch.
    // -------------------------------------------------------------------

    /** Job completed cleanly with no errors. Matches COBOL {@code APPL-AOK = 0}. */
    private static final int RC_OK = 0;

    /**
     * Catastrophic failure (uncaught exception, malformed input, or any
     * return code outside the {@code [0, 16]} band). Sets a recognisable
     * non-zero exit code for the operator's job scheduler. Higher than
     * {@code COBOL APPL-EOF = 16} would be unusual; this value matches
     * the COBOL upper bound at which the COBOL {@code CEE3ABD ABCODE=999}
     * abend would have terminated the step.
     */
    private static final int RC_ERROR = 16;

    // -------------------------------------------------------------------
    // Construction is forbidden — this is a static composition utility.
    // -------------------------------------------------------------------

    /**
     * Private constructor: this class is a composition-root utility and
     * is not meaningfully instantiable. Calling it via reflection raises
     * an {@link AssertionError} (a hard-fast diagnostic for future
     * maintainers who might accidentally try to construct an instance).
     */
    private ReadAccountDumpApp() {
        throw new AssertionError("ReadAccountDumpApp is a static composition root; do not instantiate");
    }

    // -------------------------------------------------------------------
    // Main entry point — bound by the shade-read-account-dump execution
    // in carddemo-app/pom.xml. Runtime invocation:
    //   java -XX:+UseCompactObjectHeaders \
    //        -XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational \
    //        -jar carddemo-read-account-dump.jar
    // (per AAP §0.3.4 JVM tuning baseline; all flags reference finalized
    // JEPs 519 and 521).
    // -------------------------------------------------------------------

    /**
     * Java {@code main} entry point. Establishes the batch-run scope and
     * delegates to {@link #execute()}; on exception or out-of-band return
     * code, normalises the result into a process exit code via the
     * exhaustive pattern-matching switch and calls {@link System#exit(int)}.
     *
     * <p><strong>Args</strong> are accepted by the main signature for the
     * JVM contract but are not consumed by this job. The COBOL
     * {@code CBACT01C} program reads no command-line parameters; all
     * configuration arrives via JCL DD statements (translated to
     * environment variables / system properties per AAP &sect;0.5.4).
     *
     * <h3>Control flow</h3>
     * <ol>
     *   <li>Build a {@link BatchRunContext} from the environment via
     *       {@link BatchRunContext#fromEnvironment()}. This consults
     *       {@code CARDDEMO_RUN_ID}, {@code CARDDEMO_PROCESSING_DATE},
     *       and {@code CARDDEMO_TENANT}; missing values default to an
     *       auto-generated run id, {@link java.time.LocalDate#now()}, and
     *       {@link BatchRunContext#DEFAULT_TENANT} respectively.</li>
     *   <li>Bind the context to {@link #BATCH_CTX} via
     *       {@code ScopedValue.where(BATCH_CTX, ctx).call(...)} (JEP 506
     *       Final). The {@code call} variant is used (rather than
     *       {@code run}) because {@link #execute()} returns an
     *       application-result {@code int}.</li>
     *   <li>Catch any propagated {@link Exception} (including the
     *       checked exception declared by
     *       {@link ScopedValue.Carrier#call(java.util.concurrent.Callable)});
     *       log it and substitute {@link #RC_ERROR} as the return code.</li>
     *   <li>Map the (possibly {@code null}) {@link Integer} return code
     *       to a process exit code via an exhaustive
     *       pattern-matching switch per AAP &sect;0.6.7 (no
     *       {@code default} branch).</li>
     *   <li>Call {@link System#exit(int)} with the mapped exit code.</li>
     * </ol>
     *
     * <h3>Why {@code Integer} (boxed), not {@code int}?</h3>
     * <p>{@link ScopedValue.Carrier#call(java.util.concurrent.Callable)
     * call(Callable&lt;? extends R&gt;)} returns {@code R} &mdash; a
     * reference type, since {@link java.util.concurrent.Callable Callable}
     * is generic over a reference parameter. The {@code int} returned by
     * {@link #execute()} is auto-boxed to {@link Integer}. The {@code rc}
     * local is therefore declared {@link Integer} (not {@code int}) so
     * that the exhaustive pattern-matching switch below uses the
     * standard JEP 441 pattern-matching-for-switch idiom (final since
     * Java 21) and does <strong>not</strong> require the preview
     * primitive-pattern matching of JEP 507 (which is explicitly
     * forbidden by AAP &sect;0.7.4).
     *
     * <h3>Why no {@code default} branch?</h3>
     * <p>Per AAP &sect;0.6.7, pattern-matching switches in CardDemo Java
     * code never use a {@code default} branch &mdash; exhaustiveness is
     * enforced by the compiler through a total pattern
     * ({@code case Integer i -&gt; i;}). A {@code default} would silently
     * mask unhandled cases; the total pattern makes every value
     * explicitly reachable while still satisfying the compiler.
     *
     * @param args ignored; included for the {@code public static void
     *             main(String[])} JVM contract
     */
    public static void main(String[] args) {
        // Build the batch-run context from the environment (run id,
        // processing date, tenant) — this never reads/writes any DD-equivalent
        // file path; those are resolved later inside execute().
        BatchRunContext ctx = BatchRunContext.fromEnvironment();

        // Bind the context to the local ScopedValue and run the job body.
        // ScopedValue.Carrier.call(Callable) throws Exception (the
        // signature of Callable.call), so we catch the broad java.lang.Exception
        // and surface RC_ERROR. Per AAP §0.7.4, ThreadLocal is FORBIDDEN
        // and is replaced entirely by this ScopedValue idiom.
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(ReadAccountDumpApp::execute);
        } catch (Exception e) {
            LOG.error("READACCT (CBACT01C) job failed with uncaught exception", e);
            rc = RC_ERROR;
        }

        // Exhaustive pattern-matching switch per AAP §0.6.7. Each labeled
        // case mirrors the COBOL operator-level convention: 0 = OK,
        // 4 = soft warning, 8 = caution, 12 = error (default APPL-RESULT
        // for I/O failure paths in CBACT01C), 16 = severe (the
        // COBOL APPL-EOF terminal value). Any negative value or any
        // value > 16 is normalised to RC_ERROR. The total pattern at
        // the end matches every remaining int (1..3, 5..7, 9..11, 13..15)
        // and propagates it verbatim — these are operator-recognisable
        // intermediate codes that should NOT be normalised away because
        // a custom collaborator may want to use them for sub-step
        // reporting.
        //
        // The `case null` arm is defensive: the only ways to reach it
        // would be a hypothetical ScopedValue.call() that returned a
        // null Integer (which our execute() implementation never does)
        // or a compiler change to the boxing semantics. Including it
        // explicitly avoids any NullPointerException risk and matches
        // the documented AAP §0.6.7 exhaustiveness discipline.
        int exitCode = switch (rc) {
            case null -> RC_ERROR;
            case 0 -> 0;
            case 4 -> 4;
            case 8 -> 8;
            case 12 -> 12;
            case 16 -> 16;
            case Integer i when i.intValue() < 0 -> RC_ERROR;
            case Integer i when i.intValue() > 16 -> RC_ERROR;
            case Integer i -> i.intValue();
        };

        System.exit(exitCode);
    }

    // -------------------------------------------------------------------
    // Job body — runs inside the ScopedValue scope established by main().
    // -------------------------------------------------------------------

    /**
     * Job body: wires the file-backed adapter into the use case, drives
     * the sequential ACCTFILE scan via {@link CbAct01C#run()}, and
     * returns the use-case's application-result code.
     *
     * <p>This method is the {@link java.util.concurrent.Callable Callable}
     * passed to {@link ScopedValue.Carrier#call(java.util.concurrent.Callable)}
     * in {@link #main(String[])}; it MUST therefore have a signature
     * compatible with a {@code Callable<Integer>} when used as a
     * method reference. Its visibility is {@code private} because it is
     * not part of the public surface exposed by this composition root
     * (the exports schema enumerates only {@link #main(String[])} and
     * {@link #BATCH_CTX}); the {@code ReadAccountDumpApp::execute}
     * method reference site is internal to this class.
     *
     * <h3>Hexagonal wiring (AAP &sect;0.3.2)</h3>
     * <ol>
     *   <li>Resolve the ACCTFILE input path via
     *       {@link #getProp(String, String)} &mdash; the JCL
     *       {@code ACCTFILE DD} translates to the
     *       {@code carddemo.file.acctdata.path} 12-factor config key.</li>
     *   <li>If the input file is absent (which COBOL would have
     *       diagnosed via {@code OPEN INPUT} returning a non-{@code '00'}
     *       FILE STATUS at {@code app/cbl/CBACT01C.cbl:L135-L148}),
     *       short-circuit with a structured warning log and return
     *       {@link CbAct01C#APPL_ERROR} so that the
     *       {@code main} pattern-matching switch normalises to
     *       {@link #RC_ERROR}. We perform the existence check here
     *       (before constructing the adapter) so that the operator
     *       gets a clear "no input" diagnostic instead of a deep
     *       stack trace from the adapter's downstream read.</li>
     *   <li>Construct a {@link FileAccountRepository} via plain Java
     *       constructor injection &mdash; the single-argument
     *       constructor uses the AAP-mandated default codepage
     *       ({@code IBM-1047}); per-file codepage overrides are handled
     *       at the adapter layer per AAP &sect;0.6.5.</li>
     *   <li>Up-cast the concrete adapter to the {@link AccountRepository}
     *       port type for the use-case injection &mdash; this realises
     *       the hexagonal-architecture rule from AAP &sect;0.3.2 that
     *       the use case depends only on the abstraction. Even though
     *       the local variable is declared as the port, try-with-resources
     *       remains valid because {@link AccountRepository} extends
     *       {@link AutoCloseable}.</li>
     *   <li>Construct {@link CbAct01C} with the port-typed adapter and
     *       invoke {@link CbAct01C#run() run()}.</li>
     *   <li>Return the application-result code verbatim (the
     *       {@code main} pattern-matching switch maps it to a process
     *       exit code).</li>
     * </ol>
     *
     * <h3>Resource lifecycle</h3>
     * <p>The adapter is opened inside a try-with-resources block to
     * guarantee that the underlying file channels are released on every
     * exit path &mdash; matching the COBOL convention that the
     * {@code 9000-ACCTFILE-CLOSE} paragraph runs at
     * {@code app/cbl/CBACT01C.cbl:L151-L167} regardless of whether the
     * read loop terminated normally or with an abend (per the
     * unconditional {@code PERFORM 9000-ACCTFILE-CLOSE} at COBOL
     * line L83).
     *
     * @return the use-case's application-result code: {@code 0} on a
     *         clean scan ({@link CbAct01C#APPL_AOK}), {@code 12} on any
     *         I/O failure ({@link CbAct01C#APPL_ERROR}), or
     *         {@link CbAct01C#APPL_ERROR} if the input file is absent
     */
    private static int execute() {
        // Retrieve the bound context (never null inside the scope
        // established by main()'s ScopedValue.where(...).call(...)).
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("READACCT (CBACT01C) job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        // Resolve the ACCTFILE DD-equivalent path via 12-factor config
        // (AAP §0.5.4). Path.of(...) is the AAP §0.6.5 mandated factory;
        // java.io.File is FORBIDDEN. The path itself need not exist at
        // construction time of the adapter, but if it is missing we
        // prefer a structured diagnostic over a runtime IOException
        // from the first read.
        Path acctFilePath = SafePathResolver.resolveTrusted(PROP_ACCTDATA_PATH, DEFAULT_ACCTDATA_PATH);
        LOG.info("READACCT (CBACT01C) ACCTFILE DD -> path={}", acctFilePath);

        if (!Files.exists(acctFilePath)) {
            // Operator-friendly diagnostic for the missing-input case;
            // COBOL would have aborted at OPEN INPUT with FILE STATUS != '00'
            // and PERFORM 9999-ABEND-PROGRAM (app/cbl/CBACT01C.cbl:L143-L147).
            // We surface APPL_ERROR (12) here so the main switch maps it
            // to RC_ERROR — matching the observable COBOL outcome (a
            // failed job step).
            LOG.error("READACCT (CBACT01C) ACCTFILE input not found at {}; "
                    + "returning APPL_ERROR={}", acctFilePath, CbAct01C.APPL_ERROR);
            return CbAct01C.APPL_ERROR;
        }

        // Plain Java constructor injection (AAP §0.1.1: "plain factories
        // and constructor injection (no Spring container)"). The 1-arg
        // FileAccountRepository constructor uses the AAP §0.6.5 default
        // codepage (IBM-1047); the per-file codepage override
        // (carddemo.file.acctdata.charset) is the adapter layer's
        // concern and not surfaced here.
        //
        // The local is declared as the AccountRepository port type
        // (not the concrete FileAccountRepository) to enforce the
        // hexagonal-architecture rule from AAP §0.3.2: the use case
        // CbAct01C is constructed with — and depends only on — the
        // abstraction. This decouples the use case from the file-backed
        // adapter and allows future substitution with a JDBC adapter
        // from carddemo-adapter-db without any change to this
        // composition root beyond swapping the `new
        // FileAccountRepository(...)` call.
        try (AccountRepository repo = new FileAccountRepository(acctFilePath)) {
            LOG.info("READACCT (CBACT01C) wired CbAct01C with FileAccountRepository "
                    + "(port=AccountRepository); starting sequential dump");

            // One-to-one with the COBOL PROGRAM-ID (AAP §0.1.1). The
            // use-case's run() method translates the COBOL PROCEDURE
            // DIVISION (app/cbl/CBACT01C.cbl:L70-L87) including:
            //   - PERFORM 0000-ACCTFILE-OPEN  (acctFileOpen())
            //   - PERFORM UNTIL END-OF-FILE   (acctFileReadLoop(stream))
            //   - PERFORM 9000-ACCTFILE-CLOSE (acctFileClose(stream))
            //   - PERFORM 9999-ABEND-PROGRAM  (zAbendProgram())
            //   - PERFORM 9910-DISPLAY-IO-STATUS  (displayIoStatus(...))
            //
            // Errors are surfaced via the return code (APPL_ERROR = 12),
            // NOT via thrown exceptions — matching AAP §0.7.1 "identical
            // observable outcomes" for the COBOL job-step abend.
            CbAct01C useCase = new CbAct01C(repo);
            int applResult = useCase.run();

            LOG.info("READACCT (CBACT01C) job complete; applResult={}", applResult);
            return applResult;
        }
    }

    // -------------------------------------------------------------------
    // 12-factor configuration helper. Translates a dotted property key
    // (e.g., "carddemo.file.acctdata.path") into both:
    //   - a JVM system-property lookup (-Dcarddemo.file.acctdata.path=...)
    //   - an environment-variable lookup (CARDDEMO_FILE_ACCTDATA_PATH=...)
    // The environment-variable form is derived deterministically:
    //   uppercase, dots and dashes replaced with underscores. This
    //   matches the convention used by every composition-root main class
    //   in carddemo-app and is documented in
    //   java/application.properties.example.
    //
    // Precedence: environment variable first (highest), then system
    // property, then the supplied default. This matches the 12-factor
    // app conventions for production deployment.
    // -------------------------------------------------------------------

    /**
     * Resolves a configuration value via the documented 12-factor
     * precedence:
     * <ol>
     *   <li>Environment variable &mdash; e.g.,
     *       {@code CARDDEMO_FILE_ACCTDATA_PATH=/var/data/acctdata.dat}
     *       (derived from {@code key} by uppercasing with
     *       {@link Locale#ROOT} and replacing dots and dashes with
     *       underscores).</li>
     *   <li>JVM system property &mdash; e.g.,
     *       {@code -Dcarddemo.file.acctdata.path=/var/data/acctdata.dat}.</li>
     *   <li>The supplied compiled-in {@code defaultValue}.</li>
     * </ol>
     *
     * <p>Both the environment variable and the system property are
     * considered "unset" if their value is {@code null} or
     * {@link String#isBlank() blank}; in either case the next layer
     * (system property or default) is consulted.
     *
     * <p>{@link Locale#ROOT} is used to make the uppercase conversion
     * deterministic across all JVM locales &mdash; e.g., {@code "i"} in
     * a Turkish locale would otherwise uppercase to a dotted-capital
     * {@code "İ"} (U+0130) instead of the expected ASCII {@code "I"}.
     * This locale-independence is required so that the same property key
     * resolves to the same environment-variable key on every operator's
     * machine.
     *
     * @param key          the dotted property key (e.g.,
     *                     {@code "carddemo.file.acctdata.path"}); must be
     *                     non-{@code null}
     * @param defaultValue the compiled-in default returned when neither
     *                     the environment variable nor the system property
     *                     supplies a non-blank value; may be any string
     *                     (including {@code null} if the caller wants
     *                     "not set" to surface through the method)
     * @return the resolved value, with documented precedence; or
     *         {@code defaultValue} when no source supplies a non-blank
     *         value
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
