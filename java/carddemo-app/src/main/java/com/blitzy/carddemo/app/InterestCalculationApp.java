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

// JEP 511 (finalized in Java 25): a single declaration imports every
// package exported by the java.base module. Per AAP §0.6.7 ("Module Import
// Declarations: import module java.base; at the top of files using many
// java.* packages"), this is the mandated idiom. Pulls in (among others):
//   - java.io.IOException
//   - java.nio.file.{Path, Files}
//   - java.nio.charset.{Charset, StandardCharsets}
//   - java.time.LocalDate
//   - java.time.format.{DateTimeFormatter, ResolverStyle}
//   - java.util.Locale
//   - java.util.stream.Stream
//   - java.lang.ScopedValue (moved from java.util.concurrent when JEP 506
//     finalized in Java 25; the java.util.concurrent.ScopedValue alias is
//     also re-exported by java.base for backwards compatibility)
import module java.base;

// Internal imports — strictly the carddemo-* dependencies declared in the
// file schema's depends_on_files list. All five file-backed adapter
// implementations from carddemo-adapter-file plus the CbAct04C use case
// from carddemo-application.
import com.blitzy.carddemo.adapter.file.FileAccountRepository;
import com.blitzy.carddemo.adapter.file.FileCardXrefRepository;
import com.blitzy.carddemo.adapter.file.FileDiscountGroupRepository;
import com.blitzy.carddemo.adapter.file.FileTransactionCategoryBalanceRepository;
import com.blitzy.carddemo.adapter.file.FileTransactionRepository;

// Use case, batch-run context, and Javadoc-style provenance annotation.
import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.application.account.CbAct04C;
import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

// SLF4J facade (slf4j-api 2.0.16 per parent POM dependency-management).
// Used for structured job-lifecycle logging per AAP §0.6.12. The concrete
// logback-classic backend is supplied at runtime by the shaded jar's
// runtime classpath.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/INTCALC.jcl}
 * &mdash; the monthly interest calculation job that invokes the COBOL
 * {@code CBACT04C} interest accrual engine (Java translation
 * {@link CbAct04C}).
 *
 * <h2>Source artefact</h2>
 * <p>The original JCL ({@code app/jcl/INTCALC.jcl}) chains a single
 * {@code STEP15 EXEC PGM=CBACT04C,PARM='2022071800'} step with six DD
 * allocations:
 * <ul>
 *   <li>{@code TCATBALF} &larr; {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS}
 *       (DISP=SHR &mdash; per-category balance sequential read; drives the
 *       main interest-calculation loop in {@code 1000-TCATBALF-GET-NEXT})</li>
 *   <li>{@code XREFFILE} &larr; {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}
 *       (DISP=SHR &mdash; card-account cross-reference random read by
 *       alternate index {@code FD-XREF-ACCT-ID})</li>
 *   <li>{@code XREFFIL1} &larr; {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH}
 *       (DISP=SHR &mdash; the AIX path companion DD). Per AAP &sect;0.6.5
 *       this is a <strong>no-op DD</strong> in the file-based architecture:
 *       the alternate index is computed at read time inside
 *       {@link FileCardXrefRepository}, so no separate Java-side path is
 *       required.</li>
 *   <li>{@code ACCTFILE} &larr; {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}
 *       (DISP=SHR &mdash; account master random read + REWRITE in
 *       {@code 1050-UPDATE-ACCOUNT})</li>
 *   <li>{@code DISCGRP} &larr; {@code AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS}
 *       (DISP=SHR &mdash; discount group interest-rate random read with
 *       {@code DEFAULT}-group fallback per AAP &sect;0.6.10)</li>
 *   <li>{@code TRANSACT} &larr; {@code AWS.M2.CARDDEMO.SYSTRAN(+1)}
 *       (NEW GDG generation, {@code DCB=(RECFM=F,LRECL=350,BLKSIZE=0)},
 *       {@code SPACE=(CYL,(1,1),RLSE)} &mdash; sequential write of
 *       system-generated interest accrual transactions, one 350-byte
 *       record per non-zero-rate {@code TCATBAL} entry processed)</li>
 * </ul>
 *
 * <h2>SYSTRAN GDG +1 handling</h2>
 * <p>The COBOL JCL {@code DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)} declares the
 * INTCALC {@code TRANSACT} DD as a <strong>NEW</strong> Generation Data
 * Group entry &mdash; each batch run produces a fresh {@code G####V00}
 * file under the SYSTRAN GDG base directory. The Java translation
 * mirrors this by enumerating existing {@code G\d{4}V00} generations
 * under {@link #SYSTRAN_GDG_BASE} and selecting the next sequential
 * suffix via {@link #computeNextGeneration(Path)} (the same pattern used
 * by {@code PostTransactionsApp} for {@code DALYREJS(+1)}). The resulting
 * path is forwarded to {@link FileTransactionRepository} as the system
 * transaction destination. Per AAP &sect;0.6.5 "z/OS GDG &rarr; versioned
 * files on a normal filesystem; same naming conventions preserved."
 *
 * <p>The SYSTRAN GDG dataset is <strong>distinct</strong> from the
 * regular TRANSACT VSAM KSDS used by other batch programs and online
 * transactions (which lives at {@code carddemo.file.transact.path}).
 * Writing INTCALC output to the regular TRANSACT file would corrupt the
 * shared dataset; the GDG +1 isolation is therefore critical.
 *
 * <h2>PARM date handling (AAP &sect;0.6.4)</h2>
 * <p>The COBOL {@code PROCEDURE DIVISION USING EXTERNAL-PARMS} accepts a
 * 10-character {@code PARM-DATE PIC X(10)} parameter in the
 * {@code YYYYMMDDHH} format (e.g., {@code "2022071800"} meaning
 * 2022-07-18 hour 00). The Java translation accepts this via either the
 * first command-line argument (highest precedence) or the
 * {@code carddemo.intcalc.parm} property (env var
 * {@code CARDDEMO_INTCALC_PARM}); the default {@link #DEFAULT_PARM_DATE}
 * matches the literal {@code PARM='2022071800'} in
 * {@code app/jcl/INTCALC.jcl}. The 8-character date prefix is parsed
 * with {@link DateTimeFormatter} in
 * {@link ResolverStyle#STRICT STRICT} mode so invalid calendar dates
 * (e.g., 2022-02-30) raise {@link java.time.format.DateTimeParseException}
 * rather than silently rolling over.
 *
 * <h2>DEFAULT fallback for missing discount-group records (AAP &sect;0.6.10)</h2>
 * <p>The application-layer {@link CbAct04C} class implements the
 * paragraph {@code 1200-A-GET-DEFAULT-INT-RATE} fallback:
 * <ol>
 *   <li>First look up by {@code (account-group-id, type-cd, cat-cd)}.</li>
 *   <li>If empty, retry with {@code account-group-id = "DEFAULT"}.</li>
 *   <li>If still empty, use a zero interest rate.</li>
 * </ol>
 * The {@link FileDiscountGroupRepository} adapter simply returns
 * {@link java.util.Optional#empty()} on not-found &mdash; the fallback
 * logic lives in the application layer, NOT the adapter.
 *
 * <h2>BatchRunContext / ScopedValue (AAP &sect;0.6.6, JEP 506 Final)</h2>
 * <p>{@link #main(String[])} resolves a {@link BatchRunContext} via
 * {@link BatchRunContext#fromEnvironment()} (12-factor configuration: JVM
 * system properties and environment variables) and binds it to
 * {@link #BATCH_CTX} for the duration of {@link #execute()}. Inside
 * {@code execute()}, the {@code runId} and {@code processingDate} are
 * read from {@code BATCH_CTX.get()} for the structured-logging startup
 * line. <strong>No {@link ThreadLocal} is used anywhere</strong>, in
 * keeping with AAP &sect;0.7.4.
 *
 * <p>The {@code ScopedValue} class moved from {@code java.util.concurrent}
 * (preview) to {@code java.lang} (final) when JEP 506 was finalized in
 * Java 25; the {@code java.util.concurrent.ScopedValue} alias is also
 * re-exported by {@code java.base} so existing code that imported from
 * the preview location continues to compile.
 *
 * <h2>Process-exit semantics</h2>
 * <p>Return codes mirror the COBOL {@code RETURN-CODE} convention:
 * <ul>
 *   <li>{@link #RC_OK} ({@code 0}) on a clean interest-accrual pass</li>
 *   <li>{@link #RC_NO_INPUT} ({@code 4}) when the TCATBALF input file is absent</li>
 *   <li>{@link #RC_ERROR} ({@code 16}) on any abend or uncaught failure</li>
 * </ul>
 * The exit-code computation in {@link #main(String[])} uses an
 * exhaustive pattern-matching switch over {@link Integer} (Java 21
 * Final) with <strong>no {@code default} branch</strong> per AAP
 * &sect;0.7.3 mandate.
 *
 * <h2>Shaded jar packaging (AAP &sect;0.7.2)</h2>
 * <p>This class is packaged as {@code carddemo-interest-calculation.jar}
 * by {@code maven-shade-plugin} (configured in {@code carddemo-app/pom.xml}).
 * Recommended launch:
 * <pre>{@code
 * java -XX:+UseCompactObjectHeaders \
 *      -XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational \
 *      -jar carddemo-interest-calculation.jar [PARM]
 * }</pre>
 *
 * @see CbAct04C
 * @see BatchRunContext
 * @see app/jcl/INTCALC.jcl
 * @see app/cbl/CBACT04C.cbl
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBACT04C",
        sourcePath = "app/jcl/INTCALC.jcl",
        translationDate = "2025-10-24",
        notes = "Composition root for the monthly interest calculation job (CBACT04C engine). "
                + "Wires 5 file-backed repository adapters (TCATBalance, CardXref, "
                + "DiscountGroup, Account, Transaction) plus the PARM='YYYYMMDDHH' date. "
                + "DEFAULT-group fallback lives in the application layer per AAP §0.6.10. "
                + "TRANSACT DD writes to AWS.M2.CARDDEMO.SYSTRAN(+1) GDG generation."
)
public final class InterestCalculationApp {

    // -----------------------------------------------------------------------
    // Logger and ScopedValue binding
    // -----------------------------------------------------------------------

    private static final Logger LOG = LoggerFactory.getLogger(InterestCalculationApp.class);

    /**
     * Thread-scoped binding for the per-run {@link BatchRunContext}, per
     * JEP 506 (Final in Java 25). Bound at the top of
     * {@link #main(String[])} via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(...)} and consulted
     * inside {@link #execute()} via {@code BATCH_CTX.get()}. Replaces
     * {@link ThreadLocal} entirely per AAP &sect;0.6.6.
     *
     * <p>Distinct from {@link BatchRunContext#BATCH_CTX}: this app-local
     * binding is what {@code execute()} reads; the shared module-level
     * binding in {@link BatchRunContext} is provided as the canonical
     * place to obtain the context from any callee that does not have a
     * direct reference to this class.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // -----------------------------------------------------------------------
    // Configuration keys — input DDs (12-factor per AAP §0.7.2)
    // -----------------------------------------------------------------------

    /** {@code application.properties} key for the TCATBALF DD path. */
    static final String PROP_TCATBALF_PATH = "carddemo.file.tcatbalf.path";

    /** Default TCATBALF path when no system property or env override is set. */
    static final String DEFAULT_TCATBALF_PATH = "./data/tcatbalf.dat";

    /** {@code application.properties} key for the XREFFILE DD path. */
    static final String PROP_CARDXREF_PATH = "carddemo.file.cardxref.path";

    /** Default XREFFILE path when no system property or env override is set. */
    static final String DEFAULT_CARDXREF_PATH = "./data/cardxref.dat";

    /** {@code application.properties} key for the DISCGRP DD path. */
    static final String PROP_DISCGRP_PATH = "carddemo.file.discgrp.path";

    /** Default DISCGRP path when no system property or env override is set. */
    static final String DEFAULT_DISCGRP_PATH = "./data/discgrp.dat";

    /** {@code application.properties} key for the ACCTFILE DD path. */
    static final String PROP_ACCTDATA_PATH = "carddemo.file.acctdata.path";

    /** Default ACCTFILE path when no system property or env override is set. */
    static final String DEFAULT_ACCTDATA_PATH = "./data/acctdata.dat";

    // -----------------------------------------------------------------------
    // Configuration keys — SYSTRAN GDG (TRANSACT DD output)
    // -----------------------------------------------------------------------

    /**
     * {@code application.properties} key for the GDG umbrella root
     * directory. The SYSTRAN GDG base directory is created underneath
     * this root. Documented in {@code application.properties.example}
     * &sect;12.4.
     */
    static final String PROP_GDG_ROOT = "carddemo.gdg.root";

    /**
     * Default filesystem location for the GDG umbrella root. Resolves
     * relative to the JVM working directory.
     */
    static final String DEFAULT_GDG_ROOT = "./data/gdg";

    /**
     * SYSTRAN GDG base directory name &mdash; mirrors the COBOL JCL
     * {@code DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)} dataset name. Operators
     * familiar with the source mainframe will recognise this directory
     * on the filesystem.
     */
    static final String SYSTRAN_GDG_BASE = "AWS.M2.CARDDEMO.SYSTRAN";

    /**
     * {@code application.properties} key for an explicit-override SYSTRAN
     * path (test convenience). When set, the configured path is used
     * verbatim and the GDG +1 enumeration is bypassed. Documented in
     * {@code application.properties.example} &sect;12.4 (optional key).
     */
    static final String PROP_SYSTRAN_PATH = "carddemo.file.systran.path";

    /**
     * Regular expression matching the canonical GDG generation file name
     * pattern: literal {@code G}, four decimal digits, literal
     * {@code V00}. Identifies pre-existing generations under the SYSTRAN
     * GDG base directory so {@link #computeNextGeneration(Path)} can
     * select the next sequential suffix.
     */
    static final String GDG_GENERATION_REGEX = "G\\d{4}V00";

    /**
     * Format string used by {@link String#format} to build a new GDG
     * generation file name from a numeric suffix. {@code G%04dV00}
     * produces names such as {@code G0001V00}, {@code G0002V00}, etc.,
     * preserving the z/OS GDG convention per AAP &sect;0.6.5.
     */
    static final String GENERATION_NAME_FORMAT = "G%04dV00";

    // -----------------------------------------------------------------------
    // Configuration keys — PARM (interest accrual date)
    // -----------------------------------------------------------------------

    /**
     * {@code application.properties} key for the COBOL
     * {@code PARM='YYYYMMDDHH'} value passed to CBACT04C's
     * {@code EXTERNAL-PARMS.PARM-DATE} linkage section.
     */
    static final String PROP_PARM_DATE = "carddemo.intcalc.parm";

    /**
     * Default PARM date when neither the system property nor the
     * command-line argument is supplied. Mirrors the literal
     * {@code PARM='2022071800'} at
     * {@code app/jcl/INTCALC.jcl:L22} (STEP15 EXEC PGM=CBACT04C).
     * Matches the {@code application.properties.example} default.
     */
    static final String DEFAULT_PARM_DATE = "2022071800";

    /**
     * Width of the PARM date's date component in characters (the
     * {@code YYYYMMDD} prefix of the 10-character
     * {@code PARM='YYYYMMDDHH'} value). The trailing two characters
     * (hour) are currently unused by the Java translation per the
     * {@code CbAct04C.run(LocalDate)} contract.
     */
    static final int PARM_DATE_WIDTH = 8;

    /**
     * Strict-resolver {@link DateTimeFormatter} for parsing the 8-char
     * date prefix of the PARM-DATE value. {@link ResolverStyle#STRICT}
     * ensures invalid calendar dates (e.g., 2022-02-30) raise
     * {@link java.time.format.DateTimeParseException} rather than
     * silently rolling over, per AAP &sect;0.6.4.
     */
    static final DateTimeFormatter PARM_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd").withResolverStyle(ResolverStyle.STRICT);

    // -----------------------------------------------------------------------
    // Return code constants
    // -----------------------------------------------------------------------

    /** COBOL {@code RETURN-CODE} = 0; clean execution. */
    static final int RC_OK = 0;

    /**
     * COBOL convention return code 4 &mdash; soft warning indicating
     * the TCATBALF input file was not present (no work to do).
     */
    static final int RC_NO_INPUT = 4;

    /**
     * COBOL convention return code 16 &mdash; abend / uncaught failure
     * (mirrors the COBOL {@code MOVE 16 TO APPL-RESULT} in
     * {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBACT04C.cbl:L628-L632}).
     */
    static final int RC_ERROR = 16;

    // -----------------------------------------------------------------------
    // Command-line override capture
    // -----------------------------------------------------------------------

    /**
     * Holds the first command-line arg if supplied. Reset to {@code null}
     * on every {@link #main(String[])} entry so a fresh invocation never
     * inherits an override from a prior call. Consumed by
     * {@link #resolveParmDate()}.
     *
     * <p>Declared {@code volatile} so a value written by the
     * {@code main} thread is reliably visible inside the
     * {@code ScopedValue} body running on a virtual carrier thread when
     * future fan-out is introduced. The current implementation is
     * single-threaded and would be correct without {@code volatile}; the
     * keyword preserves correctness under future evolution.
     */
    private static volatile String parmDateOverride;

    // -----------------------------------------------------------------------
    // Private constructor — utility class
    // -----------------------------------------------------------------------

    private InterestCalculationApp() {
        throw new AssertionError("InterestCalculationApp is not constructible");
    }

    // -----------------------------------------------------------------------
    // Entry points
    // -----------------------------------------------------------------------

    /**
     * Java {@code main} entry point. Captures any
     * {@code YYYYMMDDHH}-shaped first argument as the
     * {@link #parmDateOverride}, builds the {@link BatchRunContext} from
     * the environment, binds it to {@link #BATCH_CTX} via JEP 506
     * {@link ScopedValue} (replacing {@link ThreadLocal} per AAP
     * &sect;0.6.6), invokes {@link #execute()}, and translates the
     * resulting return code into a process exit code via an exhaustive
     * pattern-matching switch (Java 21 Final; no {@code default}
     * branch per AAP &sect;0.7.3).
     *
     * @param args command-line arguments; if present, {@code args[0]} is
     *             taken as the {@code YYYYMMDDHH} PARM-DATE override
     */
    public static void main(String[] args) {
        // Capture optional command-line override.
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            parmDateOverride = args[0];
        } else {
            parmDateOverride = null;
        }

        BatchRunContext ctx = BatchRunContext.fromEnvironment();

        Integer rc;
        try {
            // ScopedValue.where(...).call(...) propagates the binding
            // through any callee on the same thread or a virtual child;
            // a checked exception in the body propagates out as-is.
            rc = ScopedValue.where(BATCH_CTX, ctx).call(InterestCalculationApp::execute);
        } catch (Exception e) {
            LOG.error("INTCALC job failed with uncaught exception", e);
            rc = RC_ERROR;
        }

        // Exhaustive pattern-matching switch over Integer (Java 21 Final).
        // NO default branch per AAP §0.7.3 ("rely on exhaustiveness
        // checking; no default branches that mask missing cases").
        // Coverage:
        //   case null      -> RC_ERROR (defensive; .call() returns null only on
        //                     a Throwable that escapes our catch — should
        //                     never happen but the type system permits it)
        //   case 0/4/8/12/16 -> identity (standard COBOL RC values)
        //   case i < 0    -> RC_ERROR (translate to clean error code)
        //   case i > 16   -> RC_ERROR (clamp; no >16 RC in this job)
        //   case other    -> identity (Integer wildcard sink)
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
     * Runs the interest accrual body inside the {@link #BATCH_CTX} scope.
     * Resolves the four input DD paths plus the SYSTRAN GDG +1 output
     * generation, opens the five file-backed repositories under a single
     * try-with-resources, constructs {@link CbAct04C} with constructor
     * injection, parses the PARM date in {@link ResolverStyle#STRICT}
     * mode, invokes {@link CbAct04C#run(LocalDate)}, and returns the
     * mapped RC.
     *
     * <p>Pre-flight: TCATBALF is the only mandatory input (CBACT04C
     * iterates over it). The other four (XREFFILE, ACCTFILE, DISCGRP,
     * SYSTRAN) are read/append datasets that the COBOL job creates if
     * absent. Returning {@link #RC_NO_INPUT} on missing TCATBALF matches
     * the COBOL "empty run" return-code convention.
     *
     * <p>Visibility: {@code package-private} for direct testing from the
     * carddemo-tests module without going through {@link #main(String[])}.
     *
     * @return one of {@link #RC_OK}, {@link #RC_NO_INPUT},
     *         {@link #RC_ERROR} per the COBOL convention
     */
    static int execute() {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("INTCALC job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        String parmDate = resolveParmDate();
        LOG.info("INTCALC: PARM='{}' (interest accrual date YYYYMMDDHH)", parmDate);

        // Path resolution flows through SafePathResolver so every JCL DD
        // path is (a) read with documented 12-factor env/sysprop/default
        // precedence and (b) normalised against any `..` segments per
        // AAP §0.7.2 (CWE-22 mitigation). resolveTrusted preserves the
        // trusted-deployment model: the operator who configures these
        // paths is the same one who controls the data directory layout.
        Path tcatBalfPath = SafePathResolver.resolveTrusted(PROP_TCATBALF_PATH, DEFAULT_TCATBALF_PATH);
        Path cardXrefPath = SafePathResolver.resolveTrusted(PROP_CARDXREF_PATH, DEFAULT_CARDXREF_PATH);
        Path discGrpPath = SafePathResolver.resolveTrusted(PROP_DISCGRP_PATH, DEFAULT_DISCGRP_PATH);
        Path acctDataPath = SafePathResolver.resolveTrusted(PROP_ACCTDATA_PATH, DEFAULT_ACCTDATA_PATH);

        // Resolve the SYSTRAN GDG +1 generation. The COBOL JCL declares
        // DSN=AWS.M2.CARDDEMO.SYSTRAN(+1) — a new generation per run.
        // A successful resolution may create the GDG base directory if
        // it does not yet exist (IDCAMS DEFINE GDGBASE equivalent).
        Path systranPath;
        try {
            systranPath = resolveSystranPath();
        } catch (IOException e) {
            LOG.error("INTCALC: failed to compute SYSTRAN GDG +1 generation", e);
            return RC_ERROR;
        }

        LOG.info("JCL DDs resolved:");
        LOG.info("  TCATBALF = {}", tcatBalfPath);
        LOG.info("  XREFFILE = {}", cardXrefPath);
        LOG.info("  XREFFIL1 = (no-op DD; AIX path computed at read time)");
        LOG.info("  ACCTFILE = {}", acctDataPath);
        LOG.info("  DISCGRP  = {}", discGrpPath);
        LOG.info("  TRANSACT = {} (SYSTRAN GDG +1, LRECL=350 RECFM=F)", systranPath);

        // Pre-flight: TCATBALF is the only required input.
        if (!Files.exists(tcatBalfPath)) {
            LOG.warn("INTCALC: TCATBALF input not found at {}; returning rc={}",
                    tcatBalfPath, RC_NO_INPUT);
            return RC_NO_INPUT;
        }

        // Open all five repositories under a single try-with-resources so
        // file handles are released on every exit path (success, error,
        // exception). Plain Java constructor injection (no Spring); per
        // AAP §0.7.4 the COBOL system has no DI container and none is
        // introduced.
        try (FileTransactionCategoryBalanceRepository tcatRepo =
                     new FileTransactionCategoryBalanceRepository(tcatBalfPath);
             FileCardXrefRepository xrefRepo =
                     new FileCardXrefRepository(cardXrefPath);
             FileDiscountGroupRepository discRepo =
                     new FileDiscountGroupRepository(discGrpPath);
             FileAccountRepository acctRepo =
                     new FileAccountRepository(acctDataPath);
             FileTransactionRepository tranRepo =
                     new FileTransactionRepository(systranPath)) {

            // CbAct04C constructor argument order per the dependency
            // file's signature: (TransactionCategoryBalanceRepository,
            //                    AccountRepository, CardXrefRepository,
            //                    DiscountGroupRepository,
            //                    TransactionRepository).
            CbAct04C cbAct04C = new CbAct04C(
                    tcatRepo,       // TransactionCategoryBalanceRepository
                    acctRepo,       // AccountRepository
                    xrefRepo,       // CardXrefRepository
                    discRepo,       // DiscountGroupRepository
                    tranRepo);      // TransactionRepository

            // CbAct04C#run(LocalDate) consumes a date-only value. The
            // COBOL PARM is a 10-character YYYYMMDDHH string; the Java
            // contract uses the 8-char date portion parsed via
            // java.time. The hour suffix (trailing 2 chars of the
            // 10-char PARM) is captured in the PARM-level log line for
            // traceability but is otherwise unused by the Java
            // translation per the run(LocalDate) signature.
            // ResolverStyle.STRICT (per AAP §0.6.4) catches malformed
            // PARM-DATE values that COBOL would silently corrupt.
            LocalDate processingDate = LocalDate.parse(
                    parmDate.substring(0, PARM_DATE_WIDTH),
                    PARM_DATE_FORMATTER);

            int applRc = cbAct04C.run(processingDate);

            int rc = (applRc == CbAct04C.APPL_AOK) ? RC_OK : RC_ERROR;
            LOG.info("INTCALC job complete; CBACT04C APPL_RC={}, rc={}, "
                            + "system transactions written to {}",
                    applRc, rc, systranPath);
            return rc;
        } catch (AbendException ae) {
            // CbAct04C internally catches AbendException and translates
            // to APPL_ERROR (12), so this branch is defensive — covers
            // any future evolution where the use case escalates.
            LOG.error("INTCALC: CBACT04C abended with code={}: {}",
                    ae.abendCode(), ae.getMessage(), ae);
            return RC_ERROR;
        } catch (java.time.format.DateTimeParseException dtpe) {
            LOG.error("INTCALC: invalid PARM date '{}': {}",
                    resolveParmDate(), dtpe.getMessage(), dtpe);
            return RC_ERROR;
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Resolves the SYSTRAN GDG +1 output path. If
     * {@link #PROP_SYSTRAN_PATH} is explicitly set, that path is used
     * verbatim (after normalisation) and its parent directory is
     * created on demand. Otherwise the COBOL GDG +1 semantics are
     * emulated: the GDG umbrella directory is located via
     * {@link #PROP_GDG_ROOT}, the {@link #SYSTRAN_GDG_BASE} GDG base
     * directory is created if missing, and the next sequential
     * {@code G####V00} generation suffix is computed by
     * {@link #computeNextGeneration(Path)}.
     *
     * @return the resolved SYSTRAN output path; never {@code null}
     * @throws IOException if {@link Files#createDirectories(Path,
     *                     java.nio.file.attribute.FileAttribute...)} or
     *                     {@link Files#list(Path)} fails
     */
    static Path resolveSystranPath() throws IOException {
        // Explicit-override escape hatch (test convenience). The
        // resolveTrusted variant requires a non-null default, so we
        // probe the raw property first via getProperty(...) with an
        // empty default and switch on absence.
        String explicit = SafePathResolver.getProperty(PROP_SYSTRAN_PATH, "");
        if (!explicit.isBlank()) {
            Path explicitPath = Path.of(explicit).normalize();
            Path parent = explicitPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return explicitPath;
        }

        // GDG +1 computation. The base directory mirrors the COBOL DSN
        // (AWS.M2.CARDDEMO.SYSTRAN) literally so operators familiar
        // with the source can locate it by name on the filesystem.
        Path gdgRoot = SafePathResolver.resolveTrusted(PROP_GDG_ROOT, DEFAULT_GDG_ROOT);
        Path systranBaseDir = gdgRoot.resolve(SYSTRAN_GDG_BASE);
        Files.createDirectories(systranBaseDir);
        int nextGen = computeNextGeneration(systranBaseDir);
        // String.format with Locale.ROOT avoids the Turkish-locale
        // dotted-I trap for hex digits (not applicable for %d but a
        // consistent habit). The %04d format produces e.g. "G0001V00".
        return systranBaseDir.resolve(
                String.format(Locale.ROOT, GENERATION_NAME_FORMAT, nextGen));
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
     *                     including the
     *                     {@link java.nio.file.NotDirectoryException}
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
     * Resolves the {@code YYYYMMDDHH} PARM date with documented
     * precedence:
     * <ol>
     *   <li>command-line arg (captured into {@link #parmDateOverride}
     *       by {@link #main(String[])})</li>
     *   <li>env-variable or system-property
     *       {@link #PROP_PARM_DATE}</li>
     *   <li>{@link #DEFAULT_PARM_DATE}</li>
     * </ol>
     *
     * @return the resolved PARM date string; never {@code null}
     */
    static String resolveParmDate() {
        String override = parmDateOverride;
        if (override != null && !override.isBlank()) {
            return override;
        }
        return getProp(PROP_PARM_DATE, DEFAULT_PARM_DATE);
    }

    /**
     * 12-factor configuration lookup: environment variable first, then
     * JVM system property, then default. The environment-variable key
     * is derived from the property key by uppercasing (with
     * {@link Locale#ROOT} to avoid the Turkish-locale dotted-I trap) and
     * substituting underscores for dots and hyphens.
     *
     * @param key          the system-property / property-file key
     * @param defaultValue the value returned when neither env nor
     *                     system-property is set
     * @return the resolved value; never {@code null} (when
     *         {@code defaultValue} is non-{@code null})
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
