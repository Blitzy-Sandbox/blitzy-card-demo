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
// exported by the java.base module. Brings into scope:
//   - java.lang.ScopedValue (JEP 506 final; moved from java.util.concurrent
//                            to java.lang when finalized in Java 25)
//   - java.nio.file.{Path, Files, StandardOpenOption, StandardCopyOption}
//                                                     (per AAP §0.6.5 file I/O
//                                                      mandate)
//   - java.nio.charset.StandardCharsets
//   - java.time.LocalDate (per AAP §0.6.4 time-API mandate)
//   - java.io.{IOException, OutputStream, UncheckedIOException}
//   - java.util.{ArrayList, List, Locale}
//   - java.util.stream.{Stream, Collectors}
import module java.base;

import com.blitzy.carddemo.adapter.file.FileCardXrefRepository;
import com.blitzy.carddemo.adapter.file.FileTransactionCategoryRepository;
import com.blitzy.carddemo.adapter.file.FileTransactionRepository;
import com.blitzy.carddemo.adapter.file.FileTransactionTypeRepository;
import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.application.transaction.CbTrn03C;
import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.TransactionCategoryRepository;
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.port.TransactionTypeRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/TRANREPT.jcl}
 * &mdash; the paginated transaction-detail report job that drives the CBTRN03C
 * engine. The original JCL deck is a three-step chain:
 *
 * <h2>STEP05R (REPROC) &mdash; backup TRANSACT KSDS to BKUP(+1) GDG generation</h2>
 * <p>{@code EXEC PROC=REPROC, CNTLLIB=AWS.M2.CARDDEMO.CNTL} with overrides
 * {@code PRC001.FILEIN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} and
 * {@code PRC001.FILEOUT=AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)}
 * (DCB LRECL=350 RECFM=FB). The REPROC cataloged procedure
 * ({@code app/proc/REPROC.prc}) invokes IDCAMS REPRO via the
 * {@code &amp;CNTLLIB(REPROCT)} control card &mdash; effectively a byte-for-byte
 * copy from FILEIN to FILEOUT, faithfully translated as a single
 * {@link Files#copy(Path, Path, java.nio.file.CopyOption...)} call.
 *
 * <h2>STEP05R (SORT) &mdash; filter by date range and sort by TRAN-CARD-NUM</h2>
 * <p>{@code EXEC PGM=SORT} reading {@code SORTIN=AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)}
 * and writing {@code SORTOUT=AWS.M2.CARDDEMO.TRANSACT.DALY(+1)}. The SORT
 * control cards are preserved verbatim:
 * <pre>
 *   SYMNAMES:
 *     TRAN-CARD-NUM,263,16,ZD          (1-indexed position, 0-indexed = 262)
 *     TRAN-PROC-DT,305,10,CH           (1-indexed position, 0-indexed = 304)
 *     PARM-START-DATE,C'2022-01-01'    (HARD-CODED JCL PARM)
 *     PARM-END-DATE,C'2022-07-06'      (HARD-CODED JCL PARM)
 *   SYSIN:
 *     SORT FIELDS=(TRAN-CARD-NUM,A)
 *     INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,
 *                   TRAN-PROC-DT,LE,PARM-END-DATE)
 * </pre>
 * The Java translation applies the {@code INCLUDE} predicate via
 * {@link java.util.stream.Stream#filter(java.util.function.Predicate)} and
 * the ascending {@code SORT FIELDS} via
 * {@link java.util.List#sort(java.util.Comparator)} using a byte-by-byte
 * unsigned comparison &mdash; matching the COBOL/JCL zoned-decimal collation
 * which treats the 16-byte TRAN-CARD-NUM as a sequence of ASCII digits.
 *
 * <h2>JCL step-name duplication (preserved verbatim per AAP &sect;0.7.1)</h2>
 * <p>The source JCL labels BOTH the REPROC step and the SORT step
 * {@code STEP05R} &mdash; that is a known anomaly in the COBOL source repo and
 * is reproduced faithfully here in log messages
 * ({@code "STEP05R (REPROC)"} and {@code "STEP05R (SORT)"}). The duplicate
 * step name is also logged in {@code MIGRATION_NOTES.md}.
 *
 * <h2>STEP10R (CBTRN03C) &mdash; produce paginated transaction-detail report</h2>
 * <p>{@code EXEC PGM=CBTRN03C} with 5 input DDs + 1 output DD:
 * <ul>
 *   <li>{@code TRANFILE} &larr; {@code TRANSACT.DALY(+1)} (the filtered/sorted
 *       output of STEP05R SORT) &mdash; wired through
 *       {@link FileTransactionRepository}</li>
 *   <li>{@code CARDXREF} &larr; {@code CARDXREF.VSAM.KSDS} &mdash; wired
 *       through {@link FileCardXrefRepository}</li>
 *   <li>{@code TRANTYPE} &larr; {@code TRANTYPE.VSAM.KSDS} &mdash; wired
 *       through {@link FileTransactionTypeRepository}</li>
 *   <li>{@code TRANCATG} &larr; {@code TRANCATG.VSAM.KSDS} &mdash; wired
 *       through {@link FileTransactionCategoryRepository}</li>
 *   <li>{@code DATEPARM} &larr; small sequential file carrying the start/end
 *       date window (this composition root WRITES the DATEPARM file with the
 *       resolved start/end dates BEFORE invoking CBTRN03C, mirroring the JCL
 *       chain that pre-supplies the DD)</li>
 *   <li>{@code TRANREPT} &rarr; {@code TRANREPT(+1)} GDG generation
 *       (NEW, LRECL=133, RECFM=FB) &mdash; produced by
 *       {@link CbTrn03C.ReportSink}</li>
 * </ul>
 *
 * <h2>GDG translation (AAP &sect;0.6.12)</h2>
 * <p>The three GDG bases (BKUP, DALY, TRANREPT) are mapped to filesystem
 * directories whose names preserve the mainframe dataset name verbatim:
 * <pre>
 *   &lt;gdgRoot&gt;/AWS.M2.CARDDEMO.TRANSACT.BKUP/G####V00
 *   &lt;gdgRoot&gt;/AWS.M2.CARDDEMO.TRANSACT.DALY/G####V00
 *   &lt;gdgRoot&gt;/AWS.M2.CARDDEMO.TRANREPT/G####V00
 * </pre>
 * The {@code (+1)} relative reference is realised by
 * {@link #computeNextGeneration(Path)} which scans for the highest existing
 * {@code G####V00} file and returns one greater (or 1 if none).
 *
 * <h2>JCL PARM preservation (AAP &sect;0.7.1 "Preserve-As-Is")</h2>
 * <p>The two hard-coded date PARMs in {@code app/jcl/TRANREPT.jcl} lines 43-44
 * ({@code PARM-START-DATE=2022-01-01}, {@code PARM-END-DATE=2022-07-06}) are
 * preserved as Java constants {@link #DEFAULT_START_DATE} and
 * {@link #DEFAULT_END_DATE}. They may be overridden at runtime in this order
 * of precedence (highest first):
 * <ol>
 *   <li>{@code args[0]} and {@code args[1]} on the command line
 *       ({@code java -jar carddemo-transaction-report.jar 2022-07-01 2022-07-31})</li>
 *   <li>JVM system property
 *       {@code -Dcarddemo.tranrept.start-date=...} /
 *       {@code -Dcarddemo.tranrept.end-date=...}</li>
 *   <li>Environment variables {@code CARDDEMO_TRANREPT_START_DATE} /
 *       {@code CARDDEMO_TRANREPT_END_DATE}</li>
 *   <li>The verbatim JCL constants {@link #DEFAULT_START_DATE} /
 *       {@link #DEFAULT_END_DATE}</li>
 * </ol>
 *
 * <h2>Date type (AAP &sect;0.6.4)</h2>
 * <p>The start/end dates are validated using {@link LocalDate#parse(CharSequence)}
 * (ISO {@code yyyy-MM-dd} format). Legacy date types from {@code java.util.*}
 * are forbidden per AAP &sect;0.6.4 &mdash; only {@code java.time} is used.
 *
 * <h2>Process-exit semantics</h2>
 * <ul>
 *   <li>{@link #RC_OK} ({@code 0}) on a clean three-step run</li>
 *   <li>{@link #RC_WARN} ({@code 4}) when STEP05R SORT yields zero records
 *       passing the INCLUDE filter (advisory; still proceeds to STEP10R)</li>
 *   <li>{@link #RC_ERROR} ({@code 8}) on a recoverable I/O failure in any
 *       step</li>
 *   <li>{@code 12} when the TRANSACT input is absent (analogous to IDCAMS
 *       MAXCC=12 "dataset not found")</li>
 *   <li>{@link #RC_SEVERE} ({@code 16}) on any uncaught exception or
 *       {@link AbendException}</li>
 * </ul>
 *
 * <h2>JVM tuning baseline (AAP &sect;0.3.4)</h2>
 * The shaded jar {@code carddemo-transaction-report.jar} is documented to run
 * with {@code -XX:+UseCompactObjectHeaders} (JEP 519 final) and
 * {@code -XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational}
 * (JEP 521 final). No {@code --enable-preview} flag is permitted.
 *
 * @see CbTrn03C
 * @see TransactionBackupApp for the dataset-recreate sibling job (TRANBKP.jcl)
 * @see <a href="file:../../../../../../app/jcl/TRANREPT.jcl">app/jcl/TRANREPT.jcl</a>
 * @see <a href="file:../../../../../../app/cbl/CBTRN03C.cbl">app/cbl/CBTRN03C.cbl</a>
 * @see <a href="file:../../../../../../app/proc/REPROC.prc">app/proc/REPROC.prc</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBTRN03C",
        sourcePath = "app/jcl/TRANREPT.jcl",
        translationDate = "2025-10-24",
        notes = "Composition root for the three-step paginated transaction "
                + "report job (STEP05R REPROC, STEP05R SORT, STEP10R CBTRN03C). "
                + "Preserves the verbatim JCL PARMs 2022-01-01 / 2022-07-06. "
                + "JCL step-name DUPLICATION (STEP05R for both REPROC and SORT) "
                + "preserved as a faithful-translation quirk per AAP §0.7.1."
)
public final class TransactionReportApp {

    // ---------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------

    private static final Logger LOG = LoggerFactory.getLogger(TransactionReportApp.class);

    // ---------------------------------------------------------------------
    // ScopedValue binding for the BatchRunContext (AAP §0.6.6, JEP 506 Final)
    // ---------------------------------------------------------------------

    /**
     * Thread-scoped binding carrying the per-batch-run context (runId,
     * processingDate, tenant) for the duration of {@link #execute(String[])}.
     * Established in {@link #main(String[])} via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(...)} and read inside
     * {@link #execute(String[])} via {@code BATCH_CTX.get()}.
     *
     * <p>Each {@code carddemo-app} main class owns its own
     * {@link ScopedValue}; this is the established convention across all 28
     * sibling apps in this module. The legacy thread-bound-state mechanism
     * is forbidden in new code per AAP &sect;0.7.4.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // ---------------------------------------------------------------------
    // Hard-coded JCL PARM defaults (preserved verbatim from TRANREPT.jcl)
    // ---------------------------------------------------------------------

    /**
     * PARM-START-DATE,C'2022-01-01' &mdash; preserved verbatim from
     * {@code app/jcl/TRANREPT.jcl} line 43. Used as the start-date default
     * when no command-line arg, system property, or environment variable
     * supplies a value.
     */
    static final String DEFAULT_START_DATE = "2022-01-01";

    /**
     * PARM-END-DATE,C'2022-07-06' &mdash; preserved verbatim from
     * {@code app/jcl/TRANREPT.jcl} line 44. Used as the end-date default
     * when no command-line arg, system property, or environment variable
     * supplies a value.
     */
    static final String DEFAULT_END_DATE = "2022-07-06";

    // ---------------------------------------------------------------------
    // SYMNAMES offsets from JCL (1-indexed in source, 0-indexed in Java)
    // ---------------------------------------------------------------------

    /**
     * 0-indexed offset of {@code TRAN-CARD-NUM} within the 350-byte
     * transaction record. The JCL SYMNAMES declares
     * {@code TRAN-CARD-NUM,263,16,ZD} (1-indexed position 263, length 16,
     * zoned-decimal); subtract 1 for Java's 0-indexed array offset.
     */
    static final int OFFSET_TRAN_CARD_NUM = 262;

    /** Length of {@code TRAN-CARD-NUM} in bytes (matches PIC X(16)). */
    static final int LENGTH_TRAN_CARD_NUM = 16;

    /**
     * 0-indexed offset of {@code TRAN-PROC-DT} within the 350-byte
     * transaction record. The JCL SYMNAMES declares
     * {@code TRAN-PROC-DT,305,10,CH} (1-indexed position 305, length 10,
     * character); subtract 1 for Java's 0-indexed array offset.
     *
     * <p>Note: TRAN-PROC-DT is the 10-character prefix of the 26-character
     * TRAN-PROC-TS timestamp field at copybook offset 305 (per CVTRA05Y
     * COPY).
     */
    static final int OFFSET_TRAN_PROC_DT = 304;

    /** Length of {@code TRAN-PROC-DT} in bytes (ISO {@code YYYY-MM-DD}). */
    static final int LENGTH_TRAN_PROC_DT = 10;

    /** Fixed length of every transaction record &mdash; DCB LRECL=350 RECFM=FB. */
    static final int RECORD_LENGTH = 350;

    // ---------------------------------------------------------------------
    // GDG base names (verbatim from app/jcl/TRANREPT.jcl)
    // ---------------------------------------------------------------------

    /** GDG base for STEP05R (REPROC) output: {@code AWS.M2.CARDDEMO.TRANSACT.BKUP}. */
    static final String BKUP_GDG_BASE = "AWS.M2.CARDDEMO.TRANSACT.BKUP";

    /** GDG base for STEP05R (SORT) output: {@code AWS.M2.CARDDEMO.TRANSACT.DALY}. */
    static final String DALY_GDG_BASE = "AWS.M2.CARDDEMO.TRANSACT.DALY";

    /** GDG base for STEP10R (CBTRN03C) output: {@code AWS.M2.CARDDEMO.TRANREPT}. */
    static final String TRANREPT_GDG_BASE = "AWS.M2.CARDDEMO.TRANREPT";

    // ---------------------------------------------------------------------
    // GDG generation file naming &mdash; z/OS G####V00 convention
    // ---------------------------------------------------------------------

    /**
     * Regex matching mainframe GDG generation file names: a leading
     * {@code G}, four decimal digits (zero-padded), and the literal suffix
     * {@code V00}. Used by {@link #computeNextGeneration(Path)} to find the
     * highest existing generation number in a GDG base directory.
     */
    static final String GENERATION_NAME_REGEX = "G\\d{4}V00";

    /**
     * Format string for the GDG generation file name. Combined with the
     * 1-based next-generation index via
     * {@code String.format(Locale.ROOT, GENERATION_NAME_FORMAT, nextGen)}.
     */
    static final String GENERATION_NAME_FORMAT = "G%04dV00";

    // ---------------------------------------------------------------------
    // Configuration keys (12-factor per AAP §0.7.2)
    // ---------------------------------------------------------------------

    /** JVM system-property key for the TRANSACT KSDS input path. */
    static final String PROP_TRANSACT_PATH = "carddemo.file.transact.path";

    /** Default TRANSACT KSDS input path. */
    static final String DEFAULT_TRANSACT_PATH = "./data/transact.dat";

    /** JVM system-property key for the CARDXREF VSAM KSDS lookup path. */
    static final String PROP_CARDXREF_PATH = "carddemo.file.cardxref.path";

    /** Default CARDXREF VSAM KSDS lookup path. */
    static final String DEFAULT_CARDXREF_PATH = "./data/cardxref.dat";

    /** JVM system-property key for the TRANTYPE VSAM KSDS lookup path. */
    static final String PROP_TRANTYPE_PATH = "carddemo.file.trantype.path";

    /** Default TRANTYPE VSAM KSDS lookup path. */
    static final String DEFAULT_TRANTYPE_PATH = "./data/trantype.dat";

    /** JVM system-property key for the TRANCATG VSAM KSDS lookup path. */
    static final String PROP_TRANCATG_PATH = "carddemo.file.trancatg.path";

    /** Default TRANCATG VSAM KSDS lookup path. */
    static final String DEFAULT_TRANCATG_PATH = "./data/trancatg.dat";

    /**
     * JVM system-property key for the TRANREPT friendly output path
     * (the GDG generation file is always produced as the authoritative
     * output; this is an additional convenience copy for downstream
     * consumers that do not understand the GDG layout).
     */
    static final String PROP_TRANREPT_PATH = "carddemo.file.tranrept.path";

    /** Default TRANREPT friendly output path. */
    static final String DEFAULT_TRANREPT_PATH = "./output/tranrept.dat";

    /**
     * JVM system-property key for the DATEPARM sequential file path. The
     * file is WRITTEN by this composition root before {@link CbTrn03C} is
     * invoked, carrying the resolved start/end dates as a single 80-byte
     * record (10-byte start + 1-byte filler + 10-byte end + 59-byte
     * trailing padding).
     */
    static final String PROP_DATEPARM_PATH = "carddemo.file.dateparm.path";

    /** Default DATEPARM file path. */
    static final String DEFAULT_DATEPARM_PATH = "./work/dateparm.dat";

    /**
     * JVM system-property key for the GDG root directory under which the
     * {@code AWS.M2.CARDDEMO.TRANSACT.BKUP}, {@code .TRANSACT.DALY}, and
     * {@code .TRANREPT} GDG base directories are created.
     */
    static final String PROP_GDG_ROOT = "carddemo.gdg.root";

    /** Default GDG root directory. */
    static final String DEFAULT_GDG_ROOT = "./data/gdg";

    /**
     * JVM system-property key for the start-date override. Takes precedence
     * over {@link #DEFAULT_START_DATE} but is itself overridden by
     * {@code args[0]} on the command line.
     */
    static final String PROP_START_DATE = "carddemo.tranrept.start-date";

    /**
     * JVM system-property key for the end-date override. Takes precedence
     * over {@link #DEFAULT_END_DATE} but is itself overridden by
     * {@code args[1]} on the command line.
     */
    static final String PROP_END_DATE = "carddemo.tranrept.end-date";

    // ---------------------------------------------------------------------
    // Return codes (IDCAMS / JCL convention)
    // ---------------------------------------------------------------------

    /** Successful return code: every step completed normally. */
    static final int RC_OK = 0;

    /**
     * Warning return code: typically used when STEP05R SORT yields zero
     * records passing the INCLUDE filter. STEP10R still runs (CBTRN03C
     * tolerates an empty input), but the warning is surfaced to the
     * caller via the JVM exit code.
     */
    static final int RC_WARN = 4;

    /** Recoverable error return code: a single step failed with an I/O exception. */
    static final int RC_ERROR = 8;

    /** "Dataset not found" return code (IDCAMS MAXCC=12 analogue). */
    static final int RC_NO_INPUT = 12;

    /**
     * Severe error return code (IDCAMS MAXCC=16 analogue): an uncaught
     * exception or {@link AbendException} terminated the job.
     */
    static final int RC_SEVERE = 16;

    // ---------------------------------------------------------------------
    // DATEPARM file layout (mirror of FD-DATEPARM-REC at CBTRN03C.cbl L88)
    // ---------------------------------------------------------------------

    /**
     * Total length of the DATEPARM record &mdash; matches
     * {@code FD-DATEPARM-REC PIC X(80)} in CBTRN03C.cbl at line 88.
     */
    static final int DATEPARM_RECORD_LENGTH = 80;

    /** Length of the start-date field within the DATEPARM record. */
    static final int DATEPARM_START_DATE_LENGTH = 10;

    /** Length of the end-date field within the DATEPARM record. */
    static final int DATEPARM_END_DATE_LENGTH = 10;

    // ---------------------------------------------------------------------
    // Construction guard
    // ---------------------------------------------------------------------

    /** Utility class &mdash; not constructible. */
    private TransactionReportApp() {
        throw new AssertionError("TransactionReportApp is not constructible");
    }

    // ---------------------------------------------------------------------
    // Java main entry point
    // ---------------------------------------------------------------------

    /**
     * Java main entry point. Resolves a {@link BatchRunContext} from JVM
     * system properties and environment variables (12-factor configuration),
     * binds it to {@link #BATCH_CTX} via JEP 506
     * {@code ScopedValue.where(...).call(...)}, invokes
     * {@link #execute(String[])}, and translates the returned IDCAMS-style
     * return code to a {@link System#exit(int)} call.
     *
     * <p>Optional command-line arguments:
     * <ol start="0">
     *   <li>{@code args[0]} &mdash; start-date override ({@code YYYY-MM-DD})</li>
     *   <li>{@code args[1]} &mdash; end-date override ({@code YYYY-MM-DD})</li>
     * </ol>
     * When omitted, the start/end dates fall back to the property /
     * environment chain, then ultimately to the verbatim JCL constants
     * {@link #DEFAULT_START_DATE} ({@code "2022-01-01"}) and
     * {@link #DEFAULT_END_DATE} ({@code "2022-07-06"}).
     *
     * @param args optional command-line arguments; never {@code null} when
     *             invoked via {@code java -jar ...}
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        // Integer (not int) because ScopedValue.Carrier#call returns the
        // declared generic type (here Integer via autoboxing from
        // execute(args)'s int return). Using Integer also enables the
        // standard pattern-matching guards (case Integer i when i < 0).
        // Primitive patterns (JEP 507) are preview-only and forbidden by
        // AAP §0.7.4.
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(() -> execute(args));
        } catch (Exception e) {
            LOG.error("TRANREPT job failed with uncaught exception", e);
            rc = RC_SEVERE;
        }
        // Pattern-matching switch — exhaustive on Integer, NO default branch
        // per AAP §0.7.3. Coverage proof:
        //   case null         + 5 known constants (0/4/8/12/16)
        // + two guards (< 0 and > 16) clamp out-of-range values to RC_SEVERE
        // + a final unguarded type pattern catches the remaining 1/2/3/5/6/7/
        //   9/10/11/13/14/15 cases by returning the value as-is.
        int exitCode = switch (rc) {
            case null -> RC_SEVERE;
            case 0 -> 0;
            case 4 -> 4;
            case 8 -> 8;
            case 12 -> 12;
            case 16 -> 16;
            case Integer i when i < 0 -> RC_SEVERE;
            case Integer i when i > 16 -> RC_SEVERE;
            case Integer i -> i;
        };
        System.exit(exitCode);
    }

    // ---------------------------------------------------------------------
    // Job body — three-step JCL chain inside the BATCH_CTX scope
    // ---------------------------------------------------------------------

    /**
     * Runs the three-step TRANREPT job body inside the {@link #BATCH_CTX}
     * scope. Visible for testing.
     *
     * <p>The three steps are executed in strict sequence; each step's failure
     * short-circuits the subsequent steps with the appropriate
     * {@code RC_*} return code:
     * <ol>
     *   <li><strong>STEP05R (REPROC)</strong> &mdash; backup TRANSACT KSDS to
     *       the next generation under
     *       {@link #BKUP_GDG_BASE}. Returns {@link #RC_NO_INPUT} if the
     *       TRANSACT input is absent.</li>
     *   <li><strong>STEP05R (SORT)</strong> &mdash; read the BKUP file,
     *       apply the {@code INCLUDE COND} date-range filter, sort by
     *       TRAN-CARD-NUM ascending, and write the result to the next
     *       generation under {@link #DALY_GDG_BASE}. Returns
     *       {@link #RC_WARN} if zero records pass the filter.</li>
     *   <li><strong>STEP10R (CBTRN03C)</strong> &mdash; write the DATEPARM
     *       file with the resolved start/end dates, instantiate the
     *       four file-backed repositories, instantiate {@link CbTrn03C}
     *       with a single-record {@link CbTrn03C.DateParamsSource} and a
     *       133-byte-line {@link CbTrn03C.ReportSink} backed by the next
     *       generation under {@link #TRANREPT_GDG_BASE}, and invoke
     *       {@link CbTrn03C#run()}. A successful run additionally copies
     *       the GDG output to {@link #PROP_TRANREPT_PATH}.</li>
     * </ol>
     *
     * @param args optional command-line arguments (start-date / end-date
     *             override); never {@code null}
     * @return the IDCAMS-style return code: {@link #RC_OK} on full success
     * @throws Exception if a non-recoverable error occurs; the caller
     *                   ({@link #main(String[])}) catches and maps to
     *                   {@link #RC_SEVERE}
     */
    static int execute(String[] args) throws Exception {
        BatchRunContext ctx = BATCH_CTX.get();

        // Resolve PARMs: args > config > default. The defaults are the
        // verbatim JCL constants — see AAP §0.7.1 "Preserve-As-Is" mandate.
        String startDate = resolveDate(args, 0, PROP_START_DATE, DEFAULT_START_DATE);
        String endDate = resolveDate(args, 1, PROP_END_DATE, DEFAULT_END_DATE);

        // Validate the date strings using java.time (legacy date types are
        // forbidden per AAP §0.6.4). LocalDate.parse uses the strict ISO_LOCAL_DATE
        // formatter; an invalid value throws DateTimeParseException which
        // propagates up to main()'s catch(Exception) → RC_SEVERE.
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);

        LOG.info("TRANREPT job starting; runId={}, processingDate={}, tenant={}, "
                        + "startDate={}, endDate={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant(), start, end);

        // Resolve all input/output paths from 12-factor configuration.
        Path transactPath = SafePathResolver.resolveTrusted(PROP_TRANSACT_PATH, DEFAULT_TRANSACT_PATH);
        Path cardxrefPath = SafePathResolver.resolveTrusted(PROP_CARDXREF_PATH, DEFAULT_CARDXREF_PATH);
        Path trantypePath = SafePathResolver.resolveTrusted(PROP_TRANTYPE_PATH, DEFAULT_TRANTYPE_PATH);
        Path trancatgPath = SafePathResolver.resolveTrusted(PROP_TRANCATG_PATH, DEFAULT_TRANCATG_PATH);
        Path reportPath = SafePathResolver.resolveTrusted(PROP_TRANREPT_PATH, DEFAULT_TRANREPT_PATH);
        Path dateparmPath = SafePathResolver.resolveTrusted(PROP_DATEPARM_PATH, DEFAULT_DATEPARM_PATH);
        Path gdgRoot = SafePathResolver.resolveTrusted(PROP_GDG_ROOT, DEFAULT_GDG_ROOT);

        // ============================================================
        // STEP05R (REPROC): backup TRANSACT KSDS → BKUP(+1) GDG generation
        // ============================================================
        // The REPROC cataloged procedure (app/proc/REPROC.prc) invokes
        // IDCAMS REPRO via &CNTLLIB(REPROCT) — the effective semantic is
        // "copy FILEIN to FILEOUT byte-for-byte". A single Files.copy
        // preserves that contract exactly.
        if (!Files.exists(transactPath)) {
            LOG.error("STEP05R (REPROC): TRANSACT input not found: {}; returning CC={}",
                    transactPath, RC_NO_INPUT);
            return RC_NO_INPUT;
        }
        Path bkupBaseDir = gdgRoot.resolve(BKUP_GDG_BASE);
        Files.createDirectories(bkupBaseDir);
        int bkupGen = computeNextGeneration(bkupBaseDir);
        Path bkupNew = bkupBaseDir.resolve(
                String.format(Locale.ROOT, GENERATION_NAME_FORMAT, bkupGen));
        try {
            Files.copy(transactPath, bkupNew);
        } catch (IOException ioe) {
            LOG.error("STEP05R (REPROC) failed: {}", ioe.getMessage(), ioe);
            return RC_ERROR;
        }
        long bkupBytes = Files.size(bkupNew);
        LOG.info("STEP05R (REPROC): OK source={}, target={} (GDG +{}), bytes={}",
                transactPath, bkupNew, bkupGen, bkupBytes);

        // ============================================================
        // STEP05R (SORT): filter by date + sort by TRAN-CARD-NUM ascending
        // ============================================================
        // Read the BKUP file as a contiguous byte stream and slice into
        // RECORD_LENGTH (350-byte) records. Then apply the SYSIN control
        // cards:
        //   INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,
        //                 TRAN-PROC-DT,LE,PARM-END-DATE)
        //   SORT FIELDS=(TRAN-CARD-NUM,A)
        // The byte-by-byte unsigned comparator on TRAN-CARD-NUM matches the
        // COBOL/JCL zoned-decimal collation (which treats the field as a
        // sequence of ASCII digit bytes, sorting numerically because ASCII
        // '0'..'9' are themselves ordered).
        byte[] bkupBytesArr;
        try {
            bkupBytesArr = Files.readAllBytes(bkupNew);
        } catch (IOException ioe) {
            LOG.error("STEP05R (SORT) read failed: {}", ioe.getMessage(), ioe);
            return RC_ERROR;
        }
        if (bkupBytesArr.length % RECORD_LENGTH != 0) {
            LOG.warn("STEP05R (SORT): BKUP file size {} is not a multiple of "
                            + "RECORD_LENGTH {} — truncating to nearest record",
                    bkupBytesArr.length, RECORD_LENGTH);
        }
        int recCount = bkupBytesArr.length / RECORD_LENGTH;
        List<byte[]> records = new ArrayList<>(recCount);
        for (int i = 0; i < recCount; i++) {
            byte[] rec = new byte[RECORD_LENGTH];
            System.arraycopy(bkupBytesArr, i * RECORD_LENGTH, rec, 0, RECORD_LENGTH);
            records.add(rec);
        }
        LOG.info("STEP05R (SORT): read {} records from {}", records.size(), bkupNew);

        // INCLUDE filter — captured into final locals for the lambda.
        final String startStr = startDate;
        final String endStr = endDate;
        List<byte[]> filtered = records.stream()
                .filter(rec -> {
                    String procDt = new String(rec, OFFSET_TRAN_PROC_DT,
                            LENGTH_TRAN_PROC_DT, StandardCharsets.US_ASCII);
                    // COBOL/JCL SORT INCLUDE uses byte-level lexical
                    // comparison; for ISO date strings this is also a
                    // valid date comparison because YYYY-MM-DD is
                    // monotonically sortable.
                    return procDt.compareTo(startStr) >= 0
                            && procDt.compareTo(endStr) <= 0;
                })
                .collect(Collectors.toCollection(ArrayList::new));
        LOG.info("STEP05R (SORT): {} records pass INCLUDE filter (date range {}..{})",
                filtered.size(), startStr, endStr);

        // SORT FIELDS=(TRAN-CARD-NUM,A) — 16-byte ascending key.
        // The byte-by-byte unsigned comparator below matches the COBOL/JCL
        // collation: ASCII digit codes 0x30..0x39 are themselves ordered,
        // so the lexical ordering equals the numerical ordering when the
        // field holds zero-padded digits.
        filtered.sort((a, b) -> compareBytes(a, OFFSET_TRAN_CARD_NUM,
                b, OFFSET_TRAN_CARD_NUM, LENGTH_TRAN_CARD_NUM));

        // Write the sorted/filtered records to TRANSACT.DALY(+1).
        Path dalyBaseDir = gdgRoot.resolve(DALY_GDG_BASE);
        Files.createDirectories(dalyBaseDir);
        int dalyGen = computeNextGeneration(dalyBaseDir);
        Path dalyNew = dalyBaseDir.resolve(
                String.format(Locale.ROOT, GENERATION_NAME_FORMAT, dalyGen));
        try (OutputStream out = Files.newOutputStream(dalyNew, StandardOpenOption.CREATE_NEW)) {
            for (byte[] rec : filtered) {
                out.write(rec);
            }
        } catch (IOException ioe) {
            LOG.error("STEP05R (SORT) write failed: {}", ioe.getMessage(), ioe);
            return RC_ERROR;
        }
        long dalyBytes = Files.size(dalyNew);
        LOG.info("STEP05R (SORT): OK wrote {} sorted/filtered records ({} bytes) to {} (GDG +{})",
                filtered.size(), dalyBytes, dalyNew, dalyGen);

        // Track an advisory "empty filter result" warning. CBTRN03C is
        // robust to an empty TRANSACT input (the read loop simply
        // returns EOF immediately), so we proceed to STEP10R but surface
        // a non-zero RC in the final exit code so operators notice.
        boolean emptyFilter = filtered.isEmpty();

        // ============================================================
        // STEP10R (CBTRN03C): generate the paginated detail report
        // ============================================================
        // The TRANREPT output is a NEW GDG (+1) of TRANREPT_GDG_BASE,
        // LRECL=133 RECFM=FB.
        Path tranreptBaseDir = gdgRoot.resolve(TRANREPT_GDG_BASE);
        Files.createDirectories(tranreptBaseDir);
        int reportGen = computeNextGeneration(tranreptBaseDir);
        Path reportNew = tranreptBaseDir.resolve(
                String.format(Locale.ROOT, GENERATION_NAME_FORMAT, reportGen));

        // Write the DATEPARM file: an 80-byte record containing the
        // resolved start/end dates. Layout matches the CBTRN03C
        // DateParamsSource expectation:
        //   bytes 0..9   WS-START-DATE  PIC X(10)  ("YYYY-MM-DD")
        //   byte  10     FILLER         PIC X(01)
        //   bytes 11..20 WS-END-DATE    PIC X(10)  ("YYYY-MM-DD")
        //   bytes 21..79 (trailing padding to FD-DATEPARM-REC width 80)
        // The DATEPARM file is mainly informational — this composition
        // root supplies the actual values via a DateParamsSource lambda
        // — but the file is written for parity with the JCL chain and
        // for downstream tooling that inspects it.
        Path dateparmParent = dateparmPath.getParent();
        if (dateparmParent != null) {
            Files.createDirectories(dateparmParent);
        }
        writeDateparm(dateparmPath, startDate, endDate);
        LOG.info("STEP10R: wrote DATEPARM file at {} with startDate={}, endDate={}",
                dateparmPath, startDate, endDate);

        // Wire CBTRN03C use case via plain Java constructor injection
        // (no DI container per AAP §0.6.12). The two AutoCloseable adapters
        // (TransactionRepository + CardXrefRepository) are placed inside a
        // try-with-resources to guarantee file-handle release in the
        // exception path. The TransactionType/Category adapters are NOT
        // AutoCloseable so they are simply held as locals.
        int rc = runReport(dalyNew, cardxrefPath, trantypePath, trancatgPath,
                reportNew, startDate, endDate);
        if (rc != RC_OK) {
            return rc;
        }

        // Optional: copy the GDG generation to the friendly tranrept output
        // path for downstream readers that don't navigate the GDG layout.
        Path reportParent = reportPath.getParent();
        if (reportParent != null) {
            Files.createDirectories(reportParent);
        }
        Files.copy(reportNew, reportPath, StandardCopyOption.REPLACE_EXISTING);

        int finalRc = emptyFilter ? RC_WARN : RC_OK;
        LOG.info("TRANREPT job complete; rc={}, report at {} (also copied to {})",
                finalRc, reportNew, reportPath);
        return finalRc;
    }

    // ---------------------------------------------------------------------
    // Helper: run the CBTRN03C use case with the DateParamsSource +
    //         ReportSink lambdas wired around the resolved date window
    //         and the file-backed report output.
    // ---------------------------------------------------------------------

    /**
     * Instantiates and runs {@link CbTrn03C} with file-backed adapters and
     * the resolved date window. Returns the IDCAMS-style return code:
     * {@link #RC_OK} on success, {@link #RC_SEVERE} on
     * {@link AbendException}, {@link #RC_ERROR} on {@link IOException}.
     *
     * <p>The two AutoCloseable adapters
     * ({@link FileTransactionRepository}, {@link FileCardXrefRepository})
     * are placed inside a try-with-resources block so the underlying file
     * handles are released even when CBTRN03C abends. The
     * non-AutoCloseable adapters
     * ({@link FileTransactionTypeRepository},
     * {@link FileTransactionCategoryRepository}) are simple value-objects
     * over their respective lookup files.
     *
     * <p>The {@link CbTrn03C.ReportSink} writes each 133-byte fixed-width
     * line to the GDG output file. The trailing newline mirrors the
     * sibling {@code TransactionBackupApp} convention so that downstream
     * text inspection (and the golden-record harness in
     * {@code carddemo-tests}) can read the file as a line-oriented stream
     * without losing the 133-byte record boundary.
     *
     * @param dalyNew      the post-sort TRANSACT.DALY GDG generation file
     * @param cardxrefPath the CARDXREF VSAM KSDS lookup file
     * @param trantypePath the TRANTYPE VSAM KSDS lookup file
     * @param trancatgPath the TRANCATG VSAM KSDS lookup file
     * @param reportNew    the TRANREPT GDG generation output file
     * @param startDate    the resolved start date (YYYY-MM-DD); supplied to
     *                     the DateParamsSource lambda
     * @param endDate      the resolved end date (YYYY-MM-DD); supplied to
     *                     the DateParamsSource lambda
     * @return the IDCAMS-style return code for the STEP10R step
     */
    static int runReport(Path dalyNew, Path cardxrefPath, Path trantypePath,
                         Path trancatgPath, Path reportNew,
                         String startDate, String endDate) {
        // Final locals for the DateParamsSource lambda — Java requires
        // closed-over locals to be effectively final.
        final String resolvedStart = startDate;
        final String resolvedEnd = endDate;

        try (TransactionRepository transactRepo = new FileTransactionRepository(dalyNew);
             CardXrefRepository xrefRepo = new FileCardXrefRepository(cardxrefPath);
             OutputStream reportOut = Files.newOutputStream(reportNew,
                     StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE)) {

            TransactionTypeRepository typeRepo = new FileTransactionTypeRepository(trantypePath);
            TransactionCategoryRepository catRepo = new FileTransactionCategoryRepository(trancatgPath);

            LOG.info("STEP10R: wiring CbTrn03C; transact={}, cardxref={}, trantype={}, "
                            + "trancatg={}, tranrept={}",
                    dalyNew, cardxrefPath, trantypePath, trancatgPath, reportNew);

            // DateParamsSource: the COBOL DD points at a small dataset
            // holding the start/end dates; this composition root has
            // already resolved them from args/env/sysprop and written the
            // DATEPARM file above. The status is hard-coded to "00"
            // (success) because validation already happened upstream.
            CbTrn03C.DateParamsSource dateParams = () ->
                    new CbTrn03C.DateParams(resolvedStart, resolvedEnd, "00");

            // ReportSink: write each 133-byte fixed-width line followed
            // by a single LF separator. The LF is the convention used by
            // all sibling apps (see TransactionBackupApp) so downstream
            // text inspection can read the file as a line-oriented stream
            // without losing the 133-byte record boundary on z/OS-style
            // record-mode datasets.
            CbTrn03C.ReportSink sink = line -> {
                try {
                    reportOut.write(line);
                    reportOut.write('\n');
                    return "00";
                } catch (IOException ioe) {
                    throw new UncheckedIOException(
                            "Failed writing TRANREPT line", ioe);
                }
            };

            CbTrn03C useCase = new CbTrn03C(transactRepo, xrefRepo, typeRepo,
                    catRepo, dateParams, sink);
            useCase.run();
            return RC_OK;
        } catch (AbendException ae) {
            LOG.error("STEP10R: CBTRN03C abended with code={}: {}",
                    ae.abendCode(), ae.getMessage(), ae);
            return RC_SEVERE;
        } catch (IOException ioe) {
            LOG.error("STEP10R: I/O failure: {}", ioe.getMessage(), ioe);
            return RC_ERROR;
        }
    }

    // ---------------------------------------------------------------------
    // Helper: write the DATEPARM record
    // ---------------------------------------------------------------------

    /**
     * Writes the 80-byte DATEPARM record carrying the resolved start/end
     * dates to the given path. Layout matches the COBOL
     * {@code FD-DATEPARM-REC PIC X(80)} field in {@code CBTRN03C.cbl} at
     * line 88:
     * <pre>
     *   bytes  0..9   WS-START-DATE  PIC X(10)  ("YYYY-MM-DD")
     *   byte  10      FILLER         PIC X(01)  (single space)
     *   bytes 11..20  WS-END-DATE    PIC X(10)  ("YYYY-MM-DD")
     *   bytes 21..79  trailing padding (US-ASCII spaces, 59 bytes)
     * </pre>
     * The total record length is exactly {@link #DATEPARM_RECORD_LENGTH}
     * (80) bytes; no trailing newline is appended so the COBOL fixed-width
     * read semantics are preserved.
     *
     * <p>Visible for testing.
     *
     * @param dateparmPath the target file path; the parent directory must
     *                     already exist
     * @param startDate    the 10-character start date ("YYYY-MM-DD")
     * @param endDate      the 10-character end date ("YYYY-MM-DD")
     * @throws IOException if the file cannot be written
     */
    static void writeDateparm(Path dateparmPath, String startDate, String endDate)
            throws IOException {
        byte[] record = new byte[DATEPARM_RECORD_LENGTH];
        // Fill the entire buffer with ASCII spaces (0x20) — matches the
        // COBOL fixed-width convention for unused trailing bytes.
        java.util.Arrays.fill(record, (byte) 0x20);
        // Copy start-date bytes into [0..10).
        byte[] startBytes = startDate.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(startBytes, 0, record, 0,
                Math.min(DATEPARM_START_DATE_LENGTH, startBytes.length));
        // Copy end-date bytes into [11..21) (offset 10 is the FILLER space).
        byte[] endBytes = endDate.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(endBytes, 0, record,
                DATEPARM_START_DATE_LENGTH + 1,
                Math.min(DATEPARM_END_DATE_LENGTH, endBytes.length));
        try (OutputStream out = Files.newOutputStream(dateparmPath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            out.write(record);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Resolves a date value with the documented precedence: command-line
     * argument at {@code args[argIndex]} (highest), then JVM system
     * property {@code propKey}, then environment variable derived from
     * {@code propKey}, then the supplied {@code defaultValue} (lowest).
     *
     * <p>Visible for testing.
     *
     * @param args         the command-line arguments; may be {@code null} or
     *                     shorter than {@code argIndex + 1}
     * @param argIndex     the index into {@code args} to consult
     * @param propKey      the JVM system-property key
     * @param defaultValue the fallback when no override is supplied; must
     *                     not be {@code null}
     * @return the resolved date string (not validated here; the caller
     *         must validate with {@link LocalDate#parse(CharSequence)})
     */
    static String resolveDate(String[] args, int argIndex, String propKey,
                              String defaultValue) {
        if (args != null && args.length > argIndex
                && args[argIndex] != null && !args[argIndex].isBlank()) {
            return args[argIndex];
        }
        return getProp(propKey, defaultValue);
    }

    /**
     * Compares two byte sub-arrays for ascending order using unsigned byte
     * semantics. The COBOL/JCL SORT key collation is byte-level (each byte
     * is treated as a value in {@code [0..255]} without sign), so a
     * straightforward {@code (byte) - (byte)} subtraction would yield
     * wrong order on bytes &geq; {@code 0x80}; the {@code & 0xFF}
     * unsigning is critical for byte-for-byte parity with the mainframe.
     *
     * <p>Visible for testing.
     *
     * @param a      the first byte array
     * @param aOff   the start offset within {@code a}
     * @param b      the second byte array
     * @param bOff   the start offset within {@code b}
     * @param length the number of bytes to compare
     * @return a negative integer, zero, or a positive integer per
     *         {@link java.util.Comparator#compare(Object, Object)}
     */
    static int compareBytes(byte[] a, int aOff, byte[] b, int bOff, int length) {
        for (int i = 0; i < length; i++) {
            int ai = a[aOff + i] & 0xFF;
            int bi = b[bOff + i] & 0xFF;
            if (ai != bi) {
                return Integer.compare(ai, bi);
            }
        }
        return 0;
    }

    /**
     * Computes the next GDG generation index (1-based) for the given GDG
     * base directory. Scans for {@code G####V00} entries, parses the 4-digit
     * generation number from each, and returns one more than the maximum
     * (or {@code 1} if no generations exist yet).
     *
     * <p>Non-matching entries (hidden files, scratch files, sub-directories
     * not following the GDG convention) are silently ignored. The directory
     * stream is closed via try-with-resources.
     *
     * <p>Visible for testing.
     *
     * @param baseDir the GDG base directory; must exist and be readable
     * @return the 1-based generation index to use for the next write
     * @throws IOException if the directory cannot be listed
     */
    static int computeNextGeneration(Path baseDir) throws IOException {
        try (Stream<Path> entries = Files.list(baseDir)) {
            int max = entries
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.matches(GENERATION_NAME_REGEX))
                    .mapToInt(n -> Integer.parseInt(n.substring(1, 5)))
                    .max()
                    .orElse(0);
            return max + 1;
        }
    }

    /**
     * Resolves a configuration value with the documented precedence:
     * environment variable (highest), JVM system property, supplied
     * {@code defaultValue} (lowest). The environment-variable key is
     * derived from {@code key} by upper-casing (with {@link Locale#ROOT})
     * and replacing every {@code '.'} and {@code '-'} with {@code '_'} —
     * i.e., {@code "carddemo.tranrept.start-date"} maps to
     * {@code "CARDDEMO_TRANREPT_START_DATE"}. This matches the convention
     * used by all sibling apps in {@code carddemo-app}.
     *
     * <p>Visible for testing.
     *
     * @param key          the system-property key; must not be {@code null}
     * @param defaultValue the fallback when neither the environment
     *                     variable nor the system property is set; may
     *                     be {@code null}
     * @return the resolved value
     */
    static String getProp(String key, String defaultValue) {
        String envKey = key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return System.getProperty(key, defaultValue);
    }
}
