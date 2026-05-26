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
// exported by the java.base module — used here for java.nio.file.{Path,Files,
// StandardOpenOption,StandardCopyOption}, java.io.{IOException,OutputStream},
// java.util.{ArrayList,List,Comparator,Locale}, java.util.stream.Stream, and
// java.lang.ScopedValue (which moved from java.util.concurrent to java.lang
// when it was finalized in Java 25; see java.base/java/lang/ScopedValue.java).
import module java.base;

import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/COMBTRAN.jcl}
 * &mdash; the two-step DFSORT + IDCAMS REPRO job that sorts and combines the
 * current transaction backup with the system-generated transactions, then
 * replaces the {@code TRANSACT} VSAM KSDS master file.
 *
 * <h2>Source artefact</h2>
 * <p>{@code app/jcl/COMBTRAN.jcl} chains two steps:
 * <ol>
 *   <li><strong>{@code STEP05R}</strong> &mdash; {@code EXEC PGM=SORT}
 *       concatenates {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} and
 *       {@code AWS.M2.CARDDEMO.SYSTRAN(0)} (both GDG {@code (0)} = current
 *       generation), sorts the combined stream ascending by {@code TRAN-ID}
 *       at bytes 1-16, and writes a NEW GDG generation
 *       {@code AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)} with
 *       {@code LRECL=350, RECFM=FB}.</li>
 *   <li><strong>{@code STEP10}</strong> &mdash; {@code EXEC PGM=IDCAMS}
 *       executes {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} which
 *       copies the freshly-sorted combined file into
 *       {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}, replacing the existing
 *       KSDS contents.</li>
 * </ol>
 *
 * <h2>SYMNAMES schema preserved from the JCL</h2>
 * <p>The in-line SYMNAMES block declares a single sort field:
 * <pre>
 *   TRAN-ID, 1, 16, CH   — 16-byte character key starting at position 1
 * </pre>
 * The JCL uses 1-indexed positions; this class converts to 0-indexed Java
 * offsets at the constants below.
 *
 * <h2>SORT semantics</h2>
 * <ul>
 *   <li><strong>Key:</strong> 16 bytes starting at position 1 (1-indexed
 *       inclusive) &rArr; 0-indexed offset 0, length 16.</li>
 *   <li><strong>Order:</strong> ascending character order (CH=A). Bytes are
 *       compared as unsigned (treating each byte as an integer in
 *       {@code [0, 255]}), matching DFSORT's default {@code FORMAT=CH}
 *       behaviour on a single-byte encoded stream.</li>
 *   <li><strong>Record length:</strong> 350 bytes fixed (LRECL=350 RECFM=FB),
 *       matching {@code app/cpy/CVTRA05Y.cpy} {@code TRAN-RECORD}.</li>
 *   <li><strong>Stability:</strong> {@link java.util.List#sort(Comparator)}
 *       is guaranteed stable per the {@link java.util.List#sort} contract,
 *       matching DFSORT's default {@code EQUALS} option behaviour
 *       (preserve input order for equal keys).</li>
 *   <li><strong>No reordering beyond the specified sort:</strong> the
 *       comparator is single-key on bytes 1-16 only; bytes 17-350 are not
 *       inspected and do not influence ordering. Per AAP &sect;0.6.6, sort
 *       order is preserved exactly; virtual threads are <strong>not</strong>
 *       used here because reordering would change observable output.</li>
 * </ul>
 *
 * <h2>GDG translation</h2>
 * <p>In the file-based runtime (AAP &sect;0.6.12) each z/OS GDG base is a
 * filesystem directory whose name is the GDG dataset name, and each
 * generation is a file named {@code G####V00}. The mapping is:
 * <ul>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} &rarr;
 *       {@code <gdg-root>/AWS.M2.CARDDEMO.TRANSACT.BKUP/G####V00} where
 *       {@code ####} is the highest existing number.</li>
 *   <li>{@code AWS.M2.CARDDEMO.SYSTRAN(0)} &rarr; same scheme.</li>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)} &rarr;
 *       {@code <gdg-root>/AWS.M2.CARDDEMO.TRANSACT.COMBINED/G####V00}
 *       where {@code ####} is one greater than the highest existing
 *       number (or {@code 0001} if the directory is empty).</li>
 * </ul>
 *
 * <h2>STEP10 IDCAMS REPRO translation</h2>
 * <p>The {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} statement is
 * translated to {@link java.nio.file.Files#copy(Path, Path, java.nio.file.CopyOption...)}
 * with {@link StandardCopyOption#REPLACE_EXISTING} so that an existing
 * {@code TRANSACT} master file is overwritten with the same byte stream as
 * the source combined file. The byte-for-byte copy preserves the
 * {@code LRECL=350 RECFM=FB} record structure exactly &mdash; required for
 * downstream programs to read the KSDS correctly per AAP &sect;0.6.5
 * (byte-for-byte file fidelity is non-negotiable).
 *
 * <h2>Runtime configuration</h2>
 * <p>Two settings drive the file locations, both resolved via the canonical
 * 12-factor precedence (env var over system property over default):
 * <ul>
 *   <li>{@code carddemo.gdg.root} / {@code CARDDEMO_GDG_ROOT} &mdash;
 *       directory under which the {@code AWS.M2.CARDDEMO.*} GDG-base
 *       sub-directories live. Default: {@code ./data/gdg}.</li>
 *   <li>{@code carddemo.file.transact.path} /
 *       {@code CARDDEMO_FILE_TRANSACT_PATH} &mdash; absolute or relative
 *       path of the TRANSACT KSDS master file written by STEP10. Default:
 *       {@code ./data/transact.dat} (matches
 *       {@code application.properties.example}).</li>
 * </ul>
 *
 * <h2>Process-exit semantics</h2>
 * <p>The COBOL job exits with the maximum of its two step return codes,
 * with conventional IDCAMS values: {@code 0} (success), {@code 4} (warning),
 * {@code 8} (operational error), {@code 12} (severe error), {@code 16}
 * (catastrophic error). This Java main mirrors that contract; any uncaught
 * exception from {@link #execute()} is logged at ERROR and surfaces as
 * {@code rc=16}. The pattern-matching {@code switch} on the boxed
 * {@link Integer} return code is exhaustive (case null + five known
 * constants + two guards + a fallthrough type pattern) per AAP &sect;0.7.3;
 * no {@code default} branch is permitted.
 *
 * <h2>Non-goals (AAP &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring, no Lombok, no Apache Commons.</li>
 *   <li>No reflection.</li>
 *   <li>No {@code java.io.File} &mdash; {@link java.nio.file} only per
 *       AAP &sect;0.6.5.</li>
 *   <li>No preview features (no {@code --enable-preview} flag is required
 *       or accepted by the build).</li>
 *   <li>No virtual threads &mdash; the SORT is deterministic and would
 *       break byte-for-byte parity if records were processed concurrently.</li>
 *   <li>No {@link ThreadLocal} &mdash; AAP &sect;0.6.6 mandates
 *       {@link ScopedValue} for cross-method context propagation.</li>
 * </ul>
 *
 * <h2>Shaded artefact</h2>
 * <p>This main class is packaged as {@code carddemo-app-<version>-shaded.jar}
 * via {@code maven-shade-plugin} per AAP &sect;0.7.2. Invoke as:
 * <pre>
 * java -XX:+UseCompactObjectHeaders \
 *      -XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational \
 *      -cp carddemo-app-1.0.0-SNAPSHOT-shaded.jar \
 *      com.blitzy.carddemo.app.CombineTransactionsApp
 * </pre>
 *
 * @see <a href="https://www.ibm.com/docs/en/zos/3.1.0?topic=services-generation-data-groups">z/OS Generation Data Groups</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "SORT + IDCAMS (COMBTRAN.jcl)",
        sourcePath = "app/jcl/COMBTRAN.jcl",
        translationDate = "2025-10-24",
        notes = "JCL translation of the two-step DFSORT + IDCAMS REPRO job that "
                + "concatenates TRANSACT.BKUP(0) + SYSTRAN(0), sorts ascending by "
                + "TRAN-ID at bytes 1-16, writes TRANSACT.COMBINED(+1), then REPROs "
                + "the combined file into TRANSACT.VSAM.KSDS replacing the existing "
                + "master. Per AAP §0.6.6 the sort is single-threaded for "
                + "deterministic byte-for-byte parity with DFSORT output."
)
public final class CombineTransactionsApp {

    // ---------------------------------------------------------------------
    // Logging + scoped context
    // ---------------------------------------------------------------------

    private static final Logger LOG = LoggerFactory.getLogger(CombineTransactionsApp.class);

    /**
     * Thread-scoped binding for this run's {@link BatchRunContext}.
     *
     * <p>Bound by {@link #main(String[])} via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(...)} for the duration
     * of {@link #execute()} so that any callee on the same or a child
     * virtual thread can read it via {@code BATCH_CTX.get()}. This field
     * is the sole {@code ScopedValue} for this main class &mdash; it
     * replaces {@link ThreadLocal} entirely per AAP &sect;0.6.6 and
     * &sect;0.7.4.
     *
     * <p>Note: in Java 25 finalized, {@link ScopedValue} lives in
     * {@code java.lang} (it moved from {@code java.util.concurrent} when
     * JEP 506 was finalized); no explicit import is needed because
     * {@code java.lang} is auto-imported and {@code import module
     * java.base} also makes it available.
     *
     * <p>The carddemo-batch module exposes the canonical
     * {@link BatchRunContext#BATCH_CTX BATCH_CTX} for cross-module reuse;
     * this main's own {@code BATCH_CTX} is the per-app instance bound at
     * job entry, kept separate so each main has its own self-contained
     * lifecycle and the schema's {@code members_exposed} contract is
     * honoured.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // ---------------------------------------------------------------------
    // SORT semantics constants (preserved from the JCL SYMNAMES block)
    // ---------------------------------------------------------------------

    /**
     * Sort key starting offset (0-indexed). The SYMNAMES declaration
     * {@code TRAN-ID,1,16,CH} uses 1-indexed positions; bytes 1-16 in
     * 1-indexed inclusive form become offset 0 in 0-indexed form.
     */
    private static final int SORT_KEY_OFFSET = 0;

    /**
     * Sort key length in bytes. From {@code TRAN-ID,1,16,CH} &mdash; 16
     * bytes covering the {@code TRAN-ID PIC X(16)} primary key from
     * {@code app/cpy/CVTRA05Y.cpy}.
     */
    private static final int SORT_KEY_LENGTH = 16;

    /**
     * Fixed record length in bytes. From the SORTIN/SORTOUT DCB
     * specification ({@code LRECL=350 RECFM=FB}) and matching
     * {@code app/cpy/CVTRA05Y.cpy} {@code TRAN-RECORD}.
     */
    private static final int RECORD_LENGTH = 350;

    // ---------------------------------------------------------------------
    // GDG-base dataset names (preserved from the JCL verbatim)
    // ---------------------------------------------------------------------

    /** GDG base for the prior-run transaction backup &mdash; SORTIN(1). */
    private static final String GDG_BASE_BKUP = "AWS.M2.CARDDEMO.TRANSACT.BKUP";

    /** GDG base for system-generated transactions (e.g., interest) &mdash; SORTIN(2). */
    private static final String GDG_BASE_SYSTRAN = "AWS.M2.CARDDEMO.SYSTRAN";

    /** GDG base for the sort output written by STEP05R. */
    private static final String GDG_BASE_COMBINED = "AWS.M2.CARDDEMO.TRANSACT.COMBINED";

    // ---------------------------------------------------------------------
    // Configuration keys (12-factor: env vars override system properties)
    // ---------------------------------------------------------------------

    /** System-property / env-var key for the GDG-root directory. */
    private static final String PROP_GDG_ROOT = "carddemo.gdg.root";

    /** Default GDG root if neither sys-prop nor env-var supplies a value. */
    private static final String DEFAULT_GDG_ROOT = "./data/gdg";

    /** System-property / env-var key for the TRANSACT master file path. */
    private static final String PROP_TRANSACT_PATH = "carddemo.file.transact.path";

    /** Default TRANSACT path if neither sys-prop nor env-var supplies a value. */
    private static final String DEFAULT_TRANSACT_PATH = "./data/transact.dat";

    // ---------------------------------------------------------------------
    // IDCAMS-style return codes (preserved from COBOL conventions)
    // ---------------------------------------------------------------------

    /** Success &mdash; both steps OK; IDCAMS {@code MAXCC=0}. */
    private static final int RC_OK = 0;

    /** Catastrophic error &mdash; uncaught exception or invalid return value. */
    private static final int RC_ERROR = 16;

    // ---------------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------------

    /** Utility class &mdash; not instantiable. */
    private CombineTransactionsApp() {
        // intentionally empty
    }

    // ---------------------------------------------------------------------
    // Main entry point
    // ---------------------------------------------------------------------

    /**
     * Java main entry point. Mirrors the JCL job:
     * <ol>
     *   <li>Build a {@link BatchRunContext} from environment / system
     *       properties via {@link BatchRunContext#fromEnvironment()}.</li>
     *   <li>Bind the context to {@link #BATCH_CTX} for the duration of
     *       {@link #execute()} using
     *       {@code ScopedValue.where(BATCH_CTX, ctx).call(...)}.</li>
     *   <li>Convert the {@link Integer} return code into a process exit
     *       code via an exhaustive pattern-matching switch that clamps
     *       any out-of-range value to {@link #RC_ERROR}.</li>
     * </ol>
     *
     * <p>The {@code switch} is exhaustive on {@link Integer} via standard
     * pattern matching (Java 21 Final) rather than primitive patterns
     * (JEP 507 &mdash; preview, forbidden by AAP &sect;0.7.4). Coverage
     * proof: {@code case null} + five known constants ({@code 0}, {@code 4},
     * {@code 8}, {@code 12}, {@code 16}) + two guards for negative and
     * {@code > 16} + a final unguarded type pattern for the remaining
     * cases. The compiler enforces exhaustiveness; no {@code default}
     * branch is permitted per AAP &sect;0.7.3.
     *
     * @param args ignored; the COBOL JCL job takes no PARM
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        // The selector is intentionally boxed to Integer so the switch
        // below can use standard pattern matching with type patterns
        // (case Integer i when ...) — primitive patterns on int are JEP
        // 507 preview, forbidden by AAP §0.7.4. ScopedValue.Carrier#call
        // returns R, here Integer via autoboxing from the int returned
        // by execute().
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(CombineTransactionsApp::execute);
        } catch (Exception e) {
            LOG.error("COMBTRAN job failed with uncaught exception", e);
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

    // ---------------------------------------------------------------------
    // Job body — two steps matching the JCL
    // ---------------------------------------------------------------------

    /**
     * Runs the two-step COMBTRAN job body inside the {@link #BATCH_CTX}
     * scope. Returns the IDCAMS-style return code: {@link #RC_OK} on
     * success.
     *
     * <p>STEP05R performs the SORT in-memory:
     * <ol>
     *   <li>Resolve the current generation of {@code TRANSACT.BKUP} and
     *       {@code SYSTRAN} (the highest-numbered {@code G####V00} file
     *       in each GDG base directory, or {@code null} if the directory
     *       is empty).</li>
     *   <li>Read all 350-byte fixed-width records from each input into a
     *       single {@code List<byte[]>}. Missing inputs contribute zero
     *       records and a WARN log line; this matches DFSORT's behaviour
     *       of producing an empty output if both inputs are empty.</li>
     *   <li>Sort the combined list by bytes 1-16 ascending using an
     *       unsigned-byte comparator (CH = character order; on the ASCII
     *       fixtures shipped under {@code app/data/ASCII/}, unsigned
     *       byte order coincides with character order).</li>
     *   <li>Write the sorted records into a NEW generation file
     *       {@code G####V00} under the COMBINED GDG base, where
     *       {@code ####} is the next available generation number. The
     *       file is created with
     *       {@link StandardOpenOption#CREATE_NEW CREATE_NEW} so that a
     *       collision is reported as an {@code IOException} rather than
     *       silently overwriting.</li>
     * </ol>
     *
     * <p>STEP10 performs the IDCAMS REPRO by copying the freshly-sorted
     * combined file into the TRANSACT KSDS path, replacing any existing
     * file. The byte stream is identical to STEP05R's output, preserving
     * byte-for-byte parity with DFSORT + IDCAMS on z/OS.
     *
     * @return {@link #RC_OK} on success
     * @throws IOException if any file I/O operation fails &mdash; the
     *                     caller in {@link #main} logs the exception and
     *                     converts it to a {@link #RC_ERROR} exit code
     */
    private static int execute() throws IOException {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("COMBTRAN job starting; runId={}", ctx.runId());

        Path gdgRoot = SafePathResolver.resolveTrusted(PROP_GDG_ROOT, DEFAULT_GDG_ROOT);
        Path transactPath = SafePathResolver.resolveTrusted(PROP_TRANSACT_PATH, DEFAULT_TRANSACT_PATH);

        Path bkupBaseDir = gdgRoot.resolve(GDG_BASE_BKUP);
        Path systranBaseDir = gdgRoot.resolve(GDG_BASE_SYSTRAN);
        Path combinedBaseDir = gdgRoot.resolve(GDG_BASE_COMBINED);

        // Ensure the COMBINED GDG-base directory exists before writing to
        // it (idempotent; matches IDCAMS DEFINE GENERATIONDATAGROUP +
        // LASTCC=12 swallow idiom executed by DefineGdgApp).
        Files.createDirectories(combinedBaseDir);

        // STEP05R: resolve (0) generation = CURRENT generation
        Path bkupCurrent = resolveCurrentGeneration(bkupBaseDir);
        Path systranCurrent = resolveCurrentGeneration(systranBaseDir);

        // STEP05R: read concatenated inputs into a single in-memory list
        List<byte[]> records = new ArrayList<>();
        if (bkupCurrent != null) {
            readFixedWidthRecords(bkupCurrent, RECORD_LENGTH, records);
            LOG.info("STEP05R: read {} bytes from TRANSACT.BKUP(0)={}",
                    Files.size(bkupCurrent), bkupCurrent);
        } else {
            LOG.warn("STEP05R: TRANSACT.BKUP(0) NOT FOUND under {} — proceeding with empty input",
                    bkupBaseDir);
        }
        if (systranCurrent != null) {
            readFixedWidthRecords(systranCurrent, RECORD_LENGTH, records);
            LOG.info("STEP05R: read {} bytes from SYSTRAN(0)={}",
                    Files.size(systranCurrent), systranCurrent);
        } else {
            LOG.warn("STEP05R: SYSTRAN(0) NOT FOUND under {} — proceeding with empty contribution",
                    systranBaseDir);
        }

        LOG.info("STEP05R: total records to sort = {}", records.size());

        // STEP05R: sort by bytes [SORT_KEY_OFFSET, SORT_KEY_OFFSET+SORT_KEY_LENGTH)
        // using unsigned byte comparison (CH = character ascending). On the
        // ASCII fixtures under app/data/ASCII/ this coincides with character
        // order; on EBCDIC source streams DFSORT also performs unsigned-byte
        // comparison so the algorithm is identical on both encodings.
        //
        // List.sort(Comparator) is guaranteed stable per the contract — equal
        // keys preserve input order, matching DFSORT's default EQUALS option.
        records.sort((a, b) -> {
            for (int i = 0; i < SORT_KEY_LENGTH; i++) {
                int ai = a[SORT_KEY_OFFSET + i] & 0xFF;
                int bi = b[SORT_KEY_OFFSET + i] & 0xFF;
                if (ai != bi) {
                    return Integer.compare(ai, bi);
                }
            }
            return 0;
        });

        // STEP05R: write the sorted output to a NEW TRANSACT.COMBINED(+1)
        int nextGen = computeNextGeneration(combinedBaseDir);
        Path combinedNew = combinedBaseDir.resolve(String.format(Locale.ROOT, "G%04dV00", nextGen));
        try (OutputStream out = Files.newOutputStream(combinedNew, StandardOpenOption.CREATE_NEW)) {
            for (byte[] rec : records) {
                out.write(rec);
            }
        }
        LOG.info(
                "STEP05R: SORT OK; wrote {} sorted records ({} bytes) to TRANSACT.COMBINED(+1)={}",
                records.size(), Files.size(combinedNew), combinedNew);

        // STEP10: IDCAMS REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)
        // — copy the combined file into the TRANSACT KSDS, replacing the
        // existing master byte-for-byte. The parent directory is created
        // if missing (12-factor: the user may have configured a path under
        // a not-yet-existing directory tree).
        Path parent = transactPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(combinedNew, transactPath, StandardCopyOption.REPLACE_EXISTING);
        LOG.info("STEP10: REPRO OK; source={}, target={}, bytes={}",
                combinedNew, transactPath, Files.size(transactPath));

        LOG.info("COMBTRAN job complete; rc={}; combined {} records", RC_OK, records.size());
        return RC_OK;
    }

    // ---------------------------------------------------------------------
    // Helpers — file reading, GDG resolution, configuration lookup
    // ---------------------------------------------------------------------

    /**
     * Reads a fixed-width file into the supplied sink list, one
     * {@code byte[]} per {@code recordLength}-byte record.
     *
     * <p>If the file size is not an exact multiple of {@code recordLength}
     * a WARN line is logged but the trailing partial record is dropped
     * silently &mdash; this matches DFSORT's behaviour of truncating an
     * input that does not align to {@code LRECL}. Whole records are
     * always copied byte-for-byte; no transcoding is performed so the
     * caller-supplied bytes pass through {@link #execute()} unmodified.
     *
     * @param file         the input file; must be non-{@code null}
     * @param recordLength the fixed record length in bytes; must be
     *                     positive
     * @param sink         the list to append the parsed records to; must
     *                     be non-{@code null}
     * @throws IOException if reading {@code file} fails
     */
    private static void readFixedWidthRecords(Path file, int recordLength, List<byte[]> sink)
            throws IOException {
        byte[] all = Files.readAllBytes(file);
        if (all.length % recordLength != 0) {
            LOG.warn("File {} length {} is not a multiple of record length {}; "
                            + "trailing {} bytes ignored",
                    file, all.length, recordLength, all.length % recordLength);
        }
        int count = all.length / recordLength;
        for (int i = 0; i < count; i++) {
            byte[] rec = new byte[recordLength];
            System.arraycopy(all, i * recordLength, rec, 0, recordLength);
            sink.add(rec);
        }
    }

    /**
     * Resolves the highest-numbered {@code G####V00} generation file in
     * the given GDG-base directory (i.e. the {@code (0)} relative
     * generation in JCL terms).
     *
     * <p>Returns {@code null} if the directory does not exist or contains
     * no {@code G####V00} entries; callers (notably {@link #execute()})
     * treat a {@code null} result as &quot;no records to contribute&quot;
     * which matches DFSORT's behaviour when a SORTIN DD points at an
     * empty dataset.
     *
     * @param baseDir the GDG-base directory; need not exist
     * @return the highest-numbered generation file, or {@code null}
     * @throws IOException if listing {@code baseDir} fails
     */
    private static Path resolveCurrentGeneration(Path baseDir) throws IOException {
        if (!Files.isDirectory(baseDir)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(baseDir)) {
            return entries
                    .filter(p -> p.getFileName().toString().matches("G\\d{4}V00"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        }
    }

    /**
     * Computes the next available {@code G####V00} generation number in
     * the given GDG-base directory (i.e. the {@code (+1)} relative
     * generation in JCL terms). The numbering starts at {@code 0001}
     * when the directory is empty, otherwise it is one greater than the
     * highest existing number.
     *
     * <p>Note: this method does not enforce the JCL
     * {@code LIMIT(5) SCRATCH} clause that
     * {@code app/jcl/DEFGDGB.jcl} attaches to each GDG base. Generation
     * roll-off is a follow-up effort documented in
     * {@code MIGRATION_NOTES.md}; the initial milestone preserves the
     * &quot;directory ready to receive next generation&quot; behaviour.
     *
     * @param baseDir the GDG-base directory; must exist
     * @return the next-generation number, always positive
     * @throws IOException if listing {@code baseDir} fails
     */
    private static int computeNextGeneration(Path baseDir) throws IOException {
        try (Stream<Path> entries = Files.list(baseDir)) {
            int max = entries
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("G\\d{4}V00"))
                    .mapToInt(n -> Integer.parseInt(n.substring(1, 5)))
                    .max()
                    .orElse(0);
            return max + 1;
        }
    }

    /**
     * Resolves a configuration value with the documented precedence: env
     * var first, JVM system property second, otherwise the supplied
     * default.
     *
     * <p>The env-var name is derived from {@code key} by uppercasing
     * (using {@link Locale#ROOT} to avoid locale-dependent surprises
     * such as the Turkish dotless-i transformation) and substituting
     * underscores for dots and hyphens. Example: {@code carddemo.gdg.root}
     * maps to {@code CARDDEMO_GDG_ROOT}.
     *
     * <p>An env var or system property whose value is blank (per
     * {@link String#isBlank()}) is treated as &quot;not set&quot; so
     * that a deliberately-empty environment variable does not silently
     * override the default with the empty string. This matches the
     * resolution contract documented in
     * {@code application.properties.example}.
     *
     * @param key          the system-property key (e.g.,
     *                     {@code carddemo.gdg.root}); must be non-
     *                     {@code null}
     * @param defaultValue the value to return when neither source
     *                     supplies a non-blank value; must be non-
     *                     {@code null}
     * @return the resolved value; never {@code null}
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
