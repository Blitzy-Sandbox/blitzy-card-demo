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
// exported by the java.base module. Per AAP §0.6.7 ("Module Import
// Declarations: import module java.base; at the top of files using many
// java.* packages"), this is the mandated idiom. Pulls in:
//   - java.io.IOException, java.io.UncheckedIOException
//   - java.nio.file.{Path, Files}
//   - java.nio.charset.{Charset, StandardCharsets}
//   - java.util.Locale
//   - java.util.stream.Stream
//   - java.lang.ScopedValue (moved from java.util.concurrent when JEP 506
//     finalized in Java 25; the java.util.concurrent.ScopedValue alias is
//     also re-exported by java.base for backwards compatibility)
import module java.base;

// Internal imports — strictly limited to depends_on_files per AAP §0.5.1
// (file schema's internal_imports). All five file-backed adapter
// implementations from carddemo-adapter-file.
import com.blitzy.carddemo.adapter.file.FileAccountRepository;
import com.blitzy.carddemo.adapter.file.FileCardXrefRepository;
import com.blitzy.carddemo.adapter.file.FileDailyTransactionRepository;
import com.blitzy.carddemo.adapter.file.FileTransactionCategoryBalanceRepository;
import com.blitzy.carddemo.adapter.file.FileTransactionRepository;

// The CbTrn02C use case (Java translation of app/cbl/CBTRN02C.cbl), the
// batch-run context, and the Javadoc-style provenance annotation.
import com.blitzy.carddemo.application.transaction.CbTrn02C;
import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

// All five port interfaces from carddemo-domain. Per AAP §0.3 hexagonal
// architecture, the composition root holds adapter instances behind their
// port interface declarations so the use case is unaware of the file-vs-DB
// adapter choice. Each port also extends AutoCloseable, enabling the
// try-with-resources block in execute() to manage all five lifecycles
// uniformly.
import com.blitzy.carddemo.domain.port.AccountRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.DailyTransactionRepository;
import com.blitzy.carddemo.domain.port.TransactionCategoryBalanceRepository;
import com.blitzy.carddemo.domain.port.TransactionRepository;

// SLF4J facade (slf4j-api 2.0.16 per parent POM dependency-management).
// Used for structured job-lifecycle logging per AAP §0.6.12. The concrete
// logback-classic backend is supplied at runtime by the shaded jar's
// runtime classpath.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/POSTTRAN.jcl}
 * &mdash; the daily transaction posting job that invokes the COBOL
 * {@code CBTRN02C} posting engine (the most critical batch program in
 * CardDemo per AAP &sect;0.6.11).
 *
 * <h2>Source artefacts</h2>
 * <p>Original JCL ({@code app/jcl/POSTTRAN.jcl}) chains a single
 * {@code STEP15 EXEC PGM=CBTRN02C} step with six DD allocations:
 * <ul>
 *   <li>{@code TRANFILE} &larr; {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}
 *       (DISP=SHR &mdash; read/write)</li>
 *   <li>{@code DALYTRAN} &larr; {@code AWS.M2.CARDDEMO.DALYTRAN.PS}
 *       (DISP=SHR &mdash; sequential read input)</li>
 *   <li>{@code XREFFILE} &larr; {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}
 *       (DISP=SHR &mdash; random read)</li>
 *   <li>{@code DALYREJS} &larr; {@code AWS.M2.CARDDEMO.DALYREJS(+1)}
 *       (NEW GDG generation, {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)}
 *       &mdash; sequential append-only write)</li>
 *   <li>{@code ACCTFILE} &larr; {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}
 *       (DISP=SHR &mdash; random read + REWRITE)</li>
 *   <li>{@code TCATBALF} &larr; {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS}
 *       (DISP=SHR &mdash; random read + WRITE/REWRITE)</li>
 * </ul>
 *
 * <h2>Translation strategy &mdash; idiom-for-idiom (AAP &sect;0.7.1)</h2>
 * <p>The {@code STEP15 EXEC PGM=CBTRN02C} step is realised as plain Java
 * factory wiring of five file-backed repository adapters into the
 * {@link CbTrn02C} use case &mdash; <strong>no Spring container is
 * involved</strong> (per AAP &sect;0.7.4: the COBOL system has no DI
 * container, so none is introduced). The {@code DALYREJS} reject append
 * flow is wired through the
 * {@link DailyTransactionRepository#appendReject(
 * com.blitzy.carddemo.domain.record.DalyTranRecord, int, String)}
 * 430-byte record format (350-byte DALYTRAN body + 4-byte PIC 9(04)
 * reason + 76-byte PIC X(76) description) per AAP &sect;0.6.9 and the
 * JCL {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} clause.
 *
 * <h2>DALYREJS Generation Data Group (+1) handling</h2>
 * <p>The COBOL JCL {@code DSN=AWS.M2.CARDDEMO.DALYREJS(+1)} declares the
 * reject output as a new GDG generation &mdash; each batch run produces
 * a fresh {@code G####V00} file under the GDG base directory. The Java
 * translation mirrors this by enumerating existing {@code G\d{4}V00}
 * generations under the configured GDG base and selecting the next
 * sequential suffix via {@link #computeNextGeneration(Path)}. The
 * resulting path is forwarded to {@link FileDailyTransactionRepository}
 * as the reject-file destination. Per AAP &sect;0.6.5 "z/OS GDG &rarr;
 * versioned files on a normal filesystem; same naming conventions
 * preserved."
 *
 * <h2>Runtime configuration (AAP &sect;0.7.2 12-factor)</h2>
 * <p>Each file path is resolved via the same-package
 * {@link SafePathResolver} helper which applies env-variable &rarr;
 * system-property &rarr; default precedence and CWE-22 path-traversal
 * normalisation:
 * <ul>
 *   <li>{@code carddemo.file.dailytran.path} &rarr; DALYTRAN</li>
 *   <li>{@code carddemo.file.transact.path} &rarr; TRANFILE</li>
 *   <li>{@code carddemo.file.cardxref.path} &rarr; XREFFILE</li>
 *   <li>{@code carddemo.file.acctdata.path} &rarr; ACCTFILE</li>
 *   <li>{@code carddemo.file.tcatbalf.path} &rarr; TCATBALF</li>
 *   <li>{@code carddemo.gdg.root} &rarr; GDG umbrella directory; the
 *       {@code AWS.M2.CARDDEMO.DALYREJS} GDG base is resolved
 *       underneath this root</li>
 *   <li>{@code carddemo.file.dalyrejs.path} &rarr; explicit override of
 *       the computed GDG generation file (testing escape hatch)</li>
 *   <li>{@code carddemo.file.charset} &rarr; record-byte charset
 *       (default {@code IBM-1047} EBCDIC per AAP &sect;0.6.5; tests use
 *       {@code US-ASCII} for the pre-transcoded ASCII fixtures)</li>
 * </ul>
 *
 * <h2>BatchRunContext / ScopedValue (AAP &sect;0.6.6, JEP 506 Final)</h2>
 * <p>The {@link BatchRunContext} is resolved via
 * {@link BatchRunContext#fromEnvironment()} and bound to {@link #BATCH_CTX}
 * for the duration of {@link #execute()}. <strong>No {@link ThreadLocal}
 * is used anywhere</strong>, per AAP &sect;0.7.4 strict mandate. The
 * scope binding propagates to any virtual threads spawned by
 * {@link CbTrn02C} per JEP 506 Final semantics.
 *
 * <h2>Process-exit semantics</h2>
 * <p>Return codes (matching COBOL {@code MOVE n TO RETURN-CODE} idiom):
 * <ul>
 *   <li>{@link #RC_OK} ({@code 0}) on a clean posting run with zero
 *       rejects</li>
 *   <li>{@link #RC_WITH_REJECTS} ({@code 4}) when the COBOL engine
 *       reports at least one reject record via
 *       {@link CbTrn02C#returnCode()} &mdash; matches the COBOL
 *       {@code MOVE 4 TO RETURN-CODE} idiom on the EOJ branch when
 *       {@code WS-REJECT-COUNT &gt; 0}</li>
 *   <li>{@link #RC_NO_INPUT} ({@code 4}) when the DALYTRAN input file
 *       is absent (empty input is a valid COBOL terminating state
 *       returning rc=4 by convention)</li>
 *   <li>{@link #RC_ERROR} ({@code 16}) on any uncaught failure &mdash;
 *       includes unchecked exceptions from the repositories, the use
 *       case, or a 9999-ABEND-PROGRAM emission from {@code CbTrn02C}.
 *       Per AAP §0.5.1 strict whitelist, this composition root does
 *       NOT import the abend exception type directly; the generic
 *       {@code catch (Exception e)} block in {@link #main(String[])}
 *       handles it via subtype polymorphism.</li>
 * </ul>
 * The exit-code computation uses an exhaustive pattern-matching switch
 * over {@link Integer} (Java 21 Final) with <strong>no {@code default}
 * branch</strong> per AAP &sect;0.7.3 mandate.
 *
 * <h2>Architectural compliance</h2>
 * <ul>
 *   <li>{@code java.nio.file} exclusively; {@code java.io.File} forbidden
 *       (AAP &sect;0.6.5)</li>
 *   <li>{@code BigDecimal} via the {@code Decimals} utility for any
 *       monetary value (this composition root does not perform monetary
 *       arithmetic directly)</li>
 *   <li>No Spring, no Hibernate, no Lombok, no Apache Commons</li>
 *   <li>No {@code double} or {@code float}; no {@link ThreadLocal}; no
 *       preview features; no reflection</li>
 *   <li>Shaded as {@code carddemo-post-transactions.jar} via
 *       {@code maven-shade-plugin} in {@code carddemo-app/pom.xml}</li>
 * </ul>
 *
 * @see CbTrn02C
 * @see FileDailyTransactionRepository
 * @see BatchRunContext
 * @see SafePathResolver
 * @since 1.0.0
 */
@CobolProgram(
        value = "POSTTRAN",
        sourcePath = "app/jcl/POSTTRAN.jcl",
        translationDate = "2025-10-24",
        notes = "Composition root for the daily transaction posting JCL job. "
                + "The annotation value names the JCL job (POSTTRAN), per "
                + "AAP §0.6.8 program-by-program mapping convention for "
                + "carddemo-app composition roots which are 1:1 with JCL "
                + "EXEC steps, not with the underlying COBOL PROGRAM-ID. "
                + "The underlying use case is CBTRN02C (translated into "
                + "com.blitzy.carddemo.application.transaction.CbTrn02C). "
                + "Wires 5 file-backed repository adapters "
                + "(DailyTransaction, Transaction, CardXref, Account, "
                + "TransactionCategoryBalance) into the CbTrn02C use case. "
                + "Translates JCL DSN=AWS.M2.CARDDEMO.DALYREJS(+1) GDG semantics "
                + "to next-sequential G####V00 generation file. "
                + "RC_OK=0, RC_WITH_REJECTS=4, RC_NO_INPUT=4, RC_ERROR=16."
)
public final class PostTransactionsApp {

    /**
     * SLF4J logger. Used for structured job-lifecycle logging per AAP
     * &sect;0.6.12: job start (runId, processingDate, tenant), the six
     * resolved JCL DDs (TRANFILE, DALYTRAN, XREFFILE, DALYREJS, ACCTFILE,
     * TCATBALF), job completion (return code and reject file location),
     * and uncaught-exception stack traces.
     */
    private static final Logger LOG = LoggerFactory.getLogger(PostTransactionsApp.class);

    /**
     * Alias for the canonical {@link BatchRunContext#BATCH_CTX} so the
     * binding is the SAME {@link ScopedValue} identity used by every
     * other class in the system (composition roots, batch drivers,
     * use cases). This guarantees that a callee on the same or a child
     * virtual thread reads the exact value
     * {@link #main(String[])} bound &mdash; a separate
     * {@code ScopedValue.newInstance()} would create a distinct
     * identity even with the same field name (the M6 regression flagged
     * by the Checkpoint-5 review).
     *
     * <p>Per JEP 506 (Final in Java 25): bound exactly once in
     * {@link #main(String[])} via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(PostTransactionsApp::execute)};
     * retrieved inside {@link #execute()} via {@code BATCH_CTX.get()}.
     * Replaces {@link ThreadLocal} entirely per AAP &sect;0.6.6.</p>
     *
     * <p>This field is preserved as a local re-export to keep the
     * {@code main(String[])} body readable; assigning the canonical
     * instance to a class-level constant rather than introducing a
     * separate one means a single {@code grep BATCH_CTX} on
     * {@code carddemo-app/} still locates every entry point without
     * the AAP §0.6.6 ScopedValue-identity regression.</p>
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = BatchRunContext.BATCH_CTX;

    // -----------------------------------------------------------------------
    // Configuration keys (12-factor per AAP §0.7.2)
    // -----------------------------------------------------------------------

    /** {@code application.properties} key for the DALYTRAN input path. */
    static final String PROP_DAILYTRAN_PATH = "carddemo.file.dailytran.path";
    /** Default DALYTRAN input path if neither env-var nor sysprop is set. */
    static final String DEFAULT_DAILYTRAN_PATH = "./data/dailytran.dat";

    /** Explicit override key for the DALYREJS output path (testing escape hatch). */
    static final String PROP_DALYREJS_PATH = "carddemo.file.dalyrejs.path";

    /** {@code application.properties} key for the TRANSACT (TRANFILE) data path. */
    static final String PROP_TRANSACT_PATH = "carddemo.file.transact.path";
    /** Default TRANSACT path. */
    static final String DEFAULT_TRANSACT_PATH = "./data/transact.dat";

    /** {@code application.properties} key for the CARDXREF (XREFFILE) path. */
    static final String PROP_CARDXREF_PATH = "carddemo.file.cardxref.path";
    /** Default CARDXREF path. */
    static final String DEFAULT_CARDXREF_PATH = "./data/cardxref.dat";

    /** {@code application.properties} key for the ACCTDATA (ACCTFILE) path. */
    static final String PROP_ACCTDATA_PATH = "carddemo.file.acctdata.path";
    /** Default ACCTDATA path. */
    static final String DEFAULT_ACCTDATA_PATH = "./data/acctdata.dat";

    /** {@code application.properties} key for the TCATBALF path. */
    static final String PROP_TCATBALF_PATH = "carddemo.file.tcatbalf.path";
    /** Default TCATBALF path. */
    static final String DEFAULT_TCATBALF_PATH = "./data/tcatbalf.dat";

    /** {@code application.properties} key for the GDG umbrella directory. */
    static final String PROP_GDG_ROOT = "carddemo.gdg.root";
    /** Default GDG umbrella directory. */
    static final String DEFAULT_GDG_ROOT = "./data/gdg";

    /**
     * COBOL GDG base name for DALYREJS as declared in
     * {@code app/jcl/POSTTRAN.jcl} ({@code DSN=AWS.M2.CARDDEMO.DALYREJS(+1)}).
     * Preserved verbatim to keep the filesystem naming convention
     * recognisable for operators familiar with the COBOL source.
     */
    static final String DALYREJS_GDG_BASE = "AWS.M2.CARDDEMO.DALYREJS";

    /**
     * Regex matching a single GDG generation file name &mdash; four
     * decimal digits between {@code G} and {@code V00} (zero-padded).
     * The {@code G\d{4}V00} pattern matches z/OS GDG conventions and is
     * the only sequence {@link #computeNextGeneration(Path)} accepts when
     * scanning {@link Files#list(Path)} results.
     */
    static final String GDG_GENERATION_REGEX = "G\\d{4}V00";

    /**
     * {@code application.properties} key for the per-job character set
     * used by the file adapters when transcoding fixed-width record
     * bytes. The default is {@code IBM-1047} (EBCDIC) per AAP
     * &sect;0.6.5; the golden-record harness in {@code carddemo-tests}
     * overrides this to {@code US-ASCII} to use the pre-transcoded
     * fixtures under {@code app/data/ASCII/}.
     */
    static final String PROP_CHARSET = "carddemo.file.charset";

    /**
     * Default character set name: IBM-1047 EBCDIC per AAP &sect;0.6.5
     * ("the default codepage for EBCDIC-to-ASCII transcoding is
     * Charset.forName(\"IBM-1047\")"). String-typed so the lookup in
     * {@link #resolveCharset()} can fast-path well-known ASCII-family
     * names via {@link StandardCharsets} before falling back to
     * {@link Charset#forName(String)}.
     */
    static final String DEFAULT_CHARSET_NAME = "IBM-1047";

    // -----------------------------------------------------------------------
    // Return codes (preserve COBOL MOVE n TO RETURN-CODE values)
    // -----------------------------------------------------------------------

    /** Clean run, zero rejects. */
    static final int RC_OK = 0;
    /** At least one reject record was emitted; {@code WS-REJECT-COUNT > 0}. */
    static final int RC_WITH_REJECTS = 4;
    /** DALYTRAN input absent; an empty input is a valid COBOL terminating state. */
    static final int RC_NO_INPUT = 4;
    /** Uncaught failure; anything outside the [0,16] band normalises to here. */
    static final int RC_ERROR = 16;

    /**
     * Private constructor &mdash; the class is a static composition root
     * and is intentionally not instantiable. The {@link AssertionError}
     * guard catches reflection-based instantiation attempts.
     */
    private PostTransactionsApp() {
        throw new AssertionError("PostTransactionsApp is not constructible");
    }

    /**
     * Java main entry point bound by the {@code shade-post-transactions}
     * execution in {@code carddemo-app/pom.xml}. Translates the
     * {@code STEP15 EXEC PGM=CBTRN02C} step of
     * {@code app/jcl/POSTTRAN.jcl}.
     *
     * <p>The body:
     * <ol>
     *   <li>Builds a {@link BatchRunContext} from env-vars + sysprops via
     *       {@link BatchRunContext#fromEnvironment()}.</li>
     *   <li>Binds it to {@link #BATCH_CTX} via
     *       {@code ScopedValue.where(...).call(...)} and invokes
     *       {@link #execute()}.</li>
     *   <li>Maps the returned return code to a JVM exit code via an
     *       exhaustive pattern-matching switch (no {@code default}
     *       branch per AAP &sect;0.7.3).</li>
     *   <li>Calls {@link System#exit(int)} with the normalised exit
     *       code.</li>
     * </ol>
     *
     * @param args unused; configuration flows through environment
     *             variables / JVM system properties for 12-factor
     *             compliance per AAP &sect;0.7.2
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        Integer rc;
        try {
            // ScopedValue.where(...).call(...) returns the value produced
            // by execute() (autoboxed from int to Integer). Any exception
            // (including AbendException from CbTrn02C, which is a
            // RuntimeException subtype and thus assignable to Exception)
            // propagates to the catch block below.
            rc = ScopedValue.where(BATCH_CTX, ctx).call(PostTransactionsApp::execute);
        } catch (Exception e) {
            LOG.error("POSTTRAN job failed with uncaught exception", e);
            rc = RC_ERROR;
        }
        // Exhaustive pattern-matching switch over Integer (Java 21 Final).
        // NO default branch per AAP §0.7.3 ("rely on exhaustiveness
        // checking; no default branches that mask missing cases").
        //
        // Per the Checkpoint-5 review (M5) only the COBOL-recognised
        // return-code set {0, 4, 8, 12, 16} passes through unchanged.
        // Any other Integer value — including unexpected positives
        // such as 1, 5, 13, or 17 — is normalised to RC_ERROR (16)
        // after logging the original value. This matches the COBOL
        // convention that any non-zero, non-4 return code is treated
        // as an error in the calling JCL (and is what an operator
        // reading the system console expects).
        //
        // Coverage:
        //   case null        -> RC_ERROR (defensive; .call() returns
        //                       null only on a Throwable that escapes
        //                       our catch — should never happen but
        //                       the type system permits it)
        //   case 0/4/8/12/16 -> identity (standard COBOL RC values)
        //   case Integer i   -> RC_ERROR (sink; logs the unexpected
        //                       value for diagnosis)
        int exitCode = switch (rc) {
            case null -> RC_ERROR;
            case 0 -> 0;
            case 4 -> 4;
            case 8 -> 8;
            case 12 -> 12;
            case 16 -> 16;
            case Integer i -> {
                LOG.warn("POSTTRAN: normalising unexpected return code {} to RC_ERROR ({})",
                        i, RC_ERROR);
                yield RC_ERROR;
            }
        };
        System.exit(exitCode);
    }

    /**
     * Runs the posting job body inside the {@link #BATCH_CTX} scope.
     * Resolves the six JCL DD paths, the GDG +1 reject generation, and
     * the record charset; opens the five file-backed repositories under
     * a single try-with-resources; constructs {@link CbTrn02C} with
     * constructor injection of the five port references; invokes
     * {@link CbTrn02C#run()}; and returns the
     * {@link CbTrn02C#returnCode()} the COBOL program would have set.
     *
     * @return {@link #RC_OK} on a clean run, {@link #RC_WITH_REJECTS}
     *         when {@code CbTrn02C} reports rejects, {@link #RC_NO_INPUT}
     *         when the DALYTRAN input file is absent
     * @throws RuntimeException if any unrecoverable I/O or abend
     *                          condition propagates from the repositories
     *                          or the use case (caught by
     *                          {@link #main(String[])})
     */
    static int execute() {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("POSTTRAN job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        // Path resolution flows through SafePathResolver so every JCL DD
        // path is (a) read with documented 12-factor env/sysprop/default
        // precedence and (b) normalised against any `..` segments per
        // AAP §0.7.2 (CWE-22 mitigation). resolveTrusted preserves the
        // trusted-deployment model: the operator who configures these
        // paths is the same one who controls the data directory layout.
        Path dailyTranPath = SafePathResolver.resolveTrusted(PROP_DAILYTRAN_PATH, DEFAULT_DAILYTRAN_PATH);
        Path transactPath = SafePathResolver.resolveTrusted(PROP_TRANSACT_PATH, DEFAULT_TRANSACT_PATH);
        Path cardXrefPath = SafePathResolver.resolveTrusted(PROP_CARDXREF_PATH, DEFAULT_CARDXREF_PATH);
        Path acctDataPath = SafePathResolver.resolveTrusted(PROP_ACCTDATA_PATH, DEFAULT_ACCTDATA_PATH);
        Path tcatBalfPath = SafePathResolver.resolveTrusted(PROP_TCATBALF_PATH, DEFAULT_TCATBALF_PATH);

        // Resolve the per-job character set. Default IBM-1047 (EBCDIC)
        // per AAP §0.6.5; golden-record tests override via
        // -Dcarddemo.file.charset=US-ASCII for the pre-transcoded ASCII
        // fixtures.
        Charset charset;
        try {
            charset = resolveCharset();
        } catch (RuntimeException e) {
            LOG.error("POSTTRAN: failed to resolve charset for record I/O", e);
            return RC_ERROR;
        }

        // Resolve the DALYREJS GDG +1 generation. The COBOL JCL declares
        // DSN=AWS.M2.CARDDEMO.DALYREJS(+1) — a new generation per run.
        // A successful resolution may create the GDG base directory if
        // it does not yet exist (IDCAMS DEFINE GDGBASE equivalent).
        Path dalyRejsPath;
        try {
            dalyRejsPath = resolveDalyRejsPath();
        } catch (IOException e) {
            LOG.error("POSTTRAN: failed to compute DALYREJS GDG +1 generation", e);
            return RC_ERROR;
        }

        LOG.info("JCL DDs resolved:");
        LOG.info("  TRANFILE = {}", transactPath);
        LOG.info("  DALYTRAN = {}", dailyTranPath);
        LOG.info("  XREFFILE = {}", cardXrefPath);
        LOG.info("  DALYREJS = {} (GDG +1, LRECL=430 RECFM=F)", dalyRejsPath);
        LOG.info("  ACCTFILE = {}", acctDataPath);
        LOG.info("  TCATBALF = {}", tcatBalfPath);
        LOG.info("  charset  = {}", charset);

        // Pre-flight: DALYTRAN is the only required input. The other four
        // are read+update / append datasets that the COBOL job creates if
        // absent (CBTRN02C internally opens them with INPUT/I-O/OUTPUT
        // semantics as needed). Returning RC_NO_INPUT (4) here matches
        // the COBOL "empty run" return-code convention.
        if (!Files.exists(dailyTranPath)) {
            LOG.warn("POSTTRAN: DALYTRAN input not found at {}; returning rc={}",
                    dailyTranPath, RC_NO_INPUT);
            return RC_NO_INPUT;
        }

        // Wire the 5 file-backed repository adapters under a single
        // try-with-resources block. Each port interface (and therefore
        // each adapter) extends AutoCloseable, so all five lifecycles
        // are managed uniformly. Locals are declared as the PORT type
        // (not the concrete adapter type) per AAP §0.3 hexagonal
        // architecture — the use case (CbTrn02C) is unaware of the
        // file-vs-DB adapter choice.
        try (DailyTransactionRepository dailyTranRepo =
                     new FileDailyTransactionRepository(dailyTranPath, dalyRejsPath, charset);
             TransactionRepository tranRepo =
                     new FileTransactionRepository(transactPath, charset);
             CardXrefRepository xrefRepo =
                     new FileCardXrefRepository(cardXrefPath, charset);
             AccountRepository acctRepo =
                     new FileAccountRepository(acctDataPath, charset);
             TransactionCategoryBalanceRepository tcatRepo =
                     new FileTransactionCategoryBalanceRepository(tcatBalfPath, charset)) {

            LOG.info("POSTTRAN: wiring CbTrn02C use case; dailytran={}, dalyrejs={}, "
                            + "transact={}, cardxref={}, acctdata={}, tcatbalf={}",
                    dailyTranPath, dalyRejsPath, transactPath, cardXrefPath,
                    acctDataPath, tcatBalfPath);

            // Constructor parameter order per CbTrn02C signature and AAP
            // §0.5.3 dependency ordering:
            //   (DailyTransaction, CardXref, Account, Transaction, TcatBal)
            // The DALYREJS reject path is already encapsulated by the
            // FileDailyTransactionRepository constructor above, so the
            // use case sees only the five port interfaces.
            CbTrn02C cbTrn02C = new CbTrn02C(dailyTranRepo, xrefRepo, acctRepo,
                    tranRepo, tcatRepo);
            cbTrn02C.run();

            int rc = cbTrn02C.returnCode();
            LOG.info("POSTTRAN job complete; rc={}, rejects written to {}", rc, dalyRejsPath);
            return rc;
        }
    }

    /**
     * Resolves the DALYREJS output path. If
     * {@code carddemo.file.dalyrejs.path} is explicitly set, that path is
     * used verbatim (after normalisation) and its parent directory is
     * created on demand. Otherwise the COBOL GDG +1 semantics are
     * emulated: the GDG umbrella directory is located via
     * {@code carddemo.gdg.root}, the {@code AWS.M2.CARDDEMO.DALYREJS} GDG
     * base directory is created if missing, and the next sequential
     * {@code G####V00} generation suffix is computed by
     * {@link #computeNextGeneration(Path)}.
     *
     * @return the resolved DALYREJS output path; never {@code null}
     * @throws IOException if {@link Files#createDirectories(Path,
     *                     java.nio.file.attribute.FileAttribute...)} or
     *                     {@link Files#list(Path)} fails
     */
    static Path resolveDalyRejsPath() throws IOException {
        // Explicit-override escape hatch (test convenience). When set,
        // the override is routed through SafePathResolver.resolveOutput
        // which enforces CWE-22 containment under carddemo.output.root.
        // This prevents an operator-supplied PROP_DALYREJS_PATH from
        // writing outside the documented output root via traversal
        // (e.g., "../../etc/passwd"). Per AAP §0.7.2 and the security
        // rationale in application.properties.example §2.1.
        String explicit = SafePathResolver.getProperty(PROP_DALYREJS_PATH, "");
        if (!explicit.isBlank()) {
            // Re-resolve through resolveOutput so CWE-22 containment is
            // enforced. resolveOutput reads the property internally;
            // passing the same key round-trips the explicit value with
            // containment applied under carddemo.output.root.
            Path explicitPath = SafePathResolver.resolveOutput(
                    PROP_DALYREJS_PATH, explicit);
            Path parent = explicitPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return explicitPath;
        }
        // GDG +1 computation. The base directory mirrors the COBOL DSN
        // (AWS.M2.CARDDEMO.DALYREJS) literally so operators familiar
        // with the source can locate it by name on the filesystem.
        Path gdgRoot = SafePathResolver.resolveTrusted(PROP_GDG_ROOT, DEFAULT_GDG_ROOT);
        Path dalyrejsBaseDir = gdgRoot.resolve(DALYREJS_GDG_BASE);
        Files.createDirectories(dalyrejsBaseDir);
        int nextGen = computeNextGeneration(dalyrejsBaseDir);
        // String.format with Locale.ROOT avoids the Turkish-locale
        // dotted-I trap for hex digits (not applicable for %d but a
        // consistent habit). The %04d format produces e.g. "G0001V00".
        return dalyrejsBaseDir.resolve(String.format(Locale.ROOT, "G%04dV00", nextGen));
    }

    /**
     * Computes the next GDG generation suffix &mdash; the smallest
     * positive integer N such that {@code G####V00} where {@code #### =
     * N} does not yet exist under {@code baseDir}. Implementation:
     * enumerate {@link Files#list(Path)} entries matching the
     * {@link #GDG_GENERATION_REGEX}, extract their 4-digit numeric
     * suffix, take the maximum (or {@code 0} for an empty base
     * directory), and return {@code max + 1}.
     *
     * <p>The {@link Stream} returned by {@link Files#list(Path)} is
     * consumed entirely inside a try-with-resources block so the
     * underlying directory handle is released promptly.
     *
     * @param baseDir the GDG base directory; must exist and be a
     *                directory
     * @return the next generation suffix (a positive integer)
     * @throws IOException if {@link Files#list(Path)} fails &mdash;
     *                     including the {@link java.nio.file.NotDirectoryException}
     *                     case when {@code baseDir} is not a directory
     */
    static int computeNextGeneration(Path baseDir) throws IOException {
        try (Stream<Path> entries = Files.list(baseDir)) {
            int max = entries
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.matches(GDG_GENERATION_REGEX))
                    .mapToInt(n -> Integer.parseInt(n.substring(1, 5)))
                    .max()
                    .orElse(0);
            return max + 1;
        }
    }

    /**
     * Resolves the character set used by every file-backed repository
     * adapter for fixed-width record byte transcoding. The configured
     * name is looked up via {@link SafePathResolver#getProperty(String,
     * String)}; well-known ASCII-family names short-circuit through
     * {@link StandardCharsets} constants (saves a JDK lookup and
     * documents the expected values); otherwise the name is resolved via
     * {@link Charset#forName(String)} which may throw an
     * {@link java.nio.charset.UnsupportedCharsetException} or
     * {@link java.nio.charset.IllegalCharsetNameException} that
     * propagates to the {@link #execute()} caller and is mapped to
     * {@link #RC_ERROR}.
     *
     * <p>Default: {@value #DEFAULT_CHARSET_NAME} (IBM-1047 EBCDIC) per
     * AAP &sect;0.6.5 ("the default codepage for EBCDIC-to-ASCII
     * transcoding is Charset.forName(\"IBM-1047\")"). Tests using the
     * pre-transcoded ASCII fixtures under {@code app/data/ASCII/}
     * override to {@code US-ASCII} via the {@link #PROP_CHARSET} key.
     *
     * @return the resolved {@link Charset}; never {@code null}
     */
    static Charset resolveCharset() {
        String name = SafePathResolver.getProperty(PROP_CHARSET, DEFAULT_CHARSET_NAME);
        // Locale.ROOT case-folding avoids the Turkish dotted-I trap and
        // keeps the switch labels matchable across all JVM locales.
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "US-ASCII", "ASCII" -> StandardCharsets.US_ASCII;
            case "UTF-8", "UTF8" -> StandardCharsets.UTF_8;
            case "ISO-8859-1", "LATIN-1", "LATIN1" -> StandardCharsets.ISO_8859_1;
            case "UTF-16" -> StandardCharsets.UTF_16;
            case "UTF-16BE" -> StandardCharsets.UTF_16BE;
            case "UTF-16LE" -> StandardCharsets.UTF_16LE;
            default -> Charset.forName(name);
        };
    }
}
