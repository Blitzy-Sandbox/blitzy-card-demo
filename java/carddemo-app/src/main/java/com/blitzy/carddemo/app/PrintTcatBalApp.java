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
// StandardOpenOption}, java.nio.charset.StandardCharsets, java.io.{IOException,
// OutputStream}, java.util.{List,ArrayList,Arrays,Locale}, java.util.stream.Stream,
// and java.lang.ScopedValue (which moved from java.util.concurrent to java.lang
// when it was finalized in Java 25; see java.base/java/lang/ScopedValue.java).
import module java.base;

import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java translation of {@code app/jcl/PRTCATBL.jcl} — the three-step DFSORT job
 * that unloads the {@code TCATBALF} VSAM KSDS, sorts the unloaded records by
 * {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD)}, and emits a
 * fixed-width 40-byte formatted report with the {@code TRAN-CAT-BAL} balance
 * rendered through the DFSORT {@code EDIT=(TTTTTTTTT.TT)} edit mask.
 *
 * <h2>Source artefacts</h2>
 * <p>The originating JCL job ({@code app/jcl/PRTCATBL.jcl}) chains three steps:
 * <ol>
 *   <li><strong>{@code DELDEF}</strong> &mdash; {@code EXEC PGM=IEFBR14}
 *       with {@code DISP=(MOD,DELETE)}, which on z/OS deletes the prior
 *       {@code AWS.M2.CARDDEMO.TCATBALF.REPT} dataset if present. The
 *       {@code MOD} disposition makes the delete idempotent (no failure if the
 *       dataset does not exist).</li>
 *   <li><strong>{@code STEP05R}</strong> &mdash; {@code EXEC PROC=REPROC}
 *       (see {@code app/proc/REPROC.prc}) which runs IDCAMS {@code REPRO} to
 *       unload {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS} into a new GDG
 *       generation under {@code AWS.M2.CARDDEMO.TCATBALF.BKUP(+1)}. The unload
 *       is a byte-for-byte copy: {@code LRECL=50, RECFM=FB}.</li>
 *   <li><strong>{@code STEP10R}</strong> &mdash; {@code EXEC PGM=SORT} that
 *       reads the new GDG generation, applies the in-line SYMNAMES schema, the
 *       {@code SORT FIELDS=(...)} key, and the {@code OUTREC FIELDS=(...)}
 *       projection (with the {@code EDIT=(TTTTTTTTT.TT)} edit mask), and
 *       writes the formatted output to {@code AWS.M2.CARDDEMO.TCATBALF.REPT}
 *       as {@code LRECL=40, RECFM=FB}.</li>
 * </ol>
 *
 * <h2>SYMNAMES schema preserved from the JCL</h2>
 * <p>The in-line SYMNAMES block declares the source-record field layout used
 * by the SORT and OUTREC clauses. All offsets are preserved verbatim (the JCL
 * uses 1-indexed positions; this class converts to 0-indexed offsets at the
 * constants below):
 * <pre>
 *   TRANCAT-ACCT-ID, 1,11,ZD   — unsigned zoned-decimal account id  (PIC 9(11))
 *   TRANCAT-TYPE-CD,12, 2,CH   — 2-byte character type code         (PIC X(02))
 *   TRANCAT-CD,     14, 4,ZD   — unsigned zoned-decimal category id (PIC 9(04))
 *   TRAN-CAT-BAL,   18,11,ZD   — signed zoned-decimal balance       (PIC S9(9)V99)
 * </pre>
 * Positions 29–50 are the 22-byte FILLER tail of the 50-byte input record;
 * those bytes are never read or copied by this job.
 *
 * <h2>OUTREC field layout (40 bytes)</h2>
 * <p>The SORT OUTREC specification literally lists
 * {@code TRANCAT-ACCT-ID, X, TRANCAT-TYPE-CD, X, TRANCAT-CD, X, TRAN-CAT-BAL,
 * EDIT=(TTTTTTTTT.TT), 9X}, which arithmetically computes to
 * {@code 11 + 1 + 2 + 1 + 4 + 1 + 12 + 9 = 41} bytes. The JCL {@code SORTOUT}
 * DCB declares {@code LRECL=40}, however, so DFSORT truncates the trailing
 * filler to 8 spaces rather than 9. This Java translation honours the
 * authoritative {@code LRECL=40} disposition; the 9X &rarr; 8 trailing-space
 * delta is documented in {@code java/MIGRATION_NOTES.md} as a faithful-
 * translation decision per AAP &sect;0.7.1 ("translate faithfully and flag in
 * MIGRATION_NOTES.md; do not fix in this refactor").
 *
 * <h2>EDIT=(TTTTTTTTT.TT) semantics</h2>
 * <p>DFSORT's {@code T} placeholder reproduces a single digit position with
 * leading-zero suppression: a leading zero is rendered as a blank, the first
 * non-zero digit and every subsequent digit is rendered literally, and the
 * units position (the right-most pre-decimal-point digit) is <em>always</em>
 * rendered (so a value of {@code 0} still produces {@code "0.00"} rather than
 * an empty pre-decimal column). The width of the mask is 12 characters: 9
 * pre-decimal {@code T}s, a literal {@code "."}, and 2 post-decimal {@code T}s.
 * Negative values are rendered with a leading minus sign that displaces the
 * rightmost suppression blank (matching DFSORT's default {@code SIGNS} option
 * of {@code SIGNS=(,-,,,)}).
 *
 * <h2>ASCII zoned-decimal sign overpunch</h2>
 * <p>The reference fixtures in {@code app/data/ASCII/tcatbal.txt} are EBCDIC
 * zoned-decimal records transcoded to ASCII via IBM-1047. After transcoding,
 * the trailing byte of a {@code PIC S9(n)V99} field carries the sign as an
 * "overpunch" character per the IBM-1047 mapping:
 * <pre>
 *   '{'         — positive zero  (EBCDIC X'C0' -&gt; ASCII 0x7B)
 *   'A'..'I'    — positive 1..9  (EBCDIC X'C1'..X'C9' -&gt; ASCII 0x41..0x49)
 *   '}'         — negative zero  (EBCDIC X'D0' -&gt; ASCII 0x7D)
 *   'J'..'R'    — negative 1..9  (EBCDIC X'D1'..X'D9' -&gt; ASCII 0x4A..0x52)
 *   '0'..'9'    — unsigned digit (no overpunch; assumed positive)
 * </pre>
 * {@link #formatTtttttttttDotTt(byte[], int, int)} decodes both the digit and
 * the sign from these characters; the byte-level approach satisfies the AAP
 * &sect;0.6.1 mandate that no {@code double} or {@code float} ever participates
 * in monetary handling.
 *
 * <h2>Sort key — byte-level lexicographic comparison</h2>
 * <p>The three sort keys ({@code TRANCAT-ACCT-ID}, {@code TRANCAT-TYPE-CD},
 * {@code TRANCAT-CD}) are all unsigned fields containing plain ASCII characters
 * (digits {@code '0'..'9'} or 2-byte character codes), so byte-level
 * lexicographic comparison is identical to the numeric/character comparison
 * DFSORT applies. {@link #compareBytes(byte[], int, byte[], int, int)} is
 * a position-wise unsigned-byte comparator; ties on a key advance to the next.
 * The Java {@link java.util.List#sort(java.util.Comparator)} implementation
 * (TimSort) is stable, preserving the relative order of records that share a
 * full key — matching DFSORT's default sort stability for this job (no
 * {@code OPTION EQUALS} required because PRTCATBL has no per-record-tie tail).
 *
 * <h2>BatchRunContext / ScopedValue (AAP &sect;0.6.6, JEP 506 Final)</h2>
 * <p>{@link #main(String[])} resolves a {@link BatchRunContext} via
 * {@link BatchRunContext#fromEnvironment()} (12-factor configuration: JVM
 * system properties and environment variables) and binds it to {@link #BATCH_CTX}
 * for the duration of {@link #execute()}. Inside {@code execute()}, the runId
 * is read from {@code BATCH_CTX.get()} for the structured-logging startup line.
 * <strong>No {@code ThreadLocal} is used anywhere</strong>, in keeping with
 * AAP &sect;0.7.4.
 *
 * <h2>Process-exit semantics</h2>
 * <p>The return codes mirror the COBOL/IDCAMS convention: {@code 0} for
 * success, {@code 4} for a missing input file (the soft warning path that
 * STEP05R would have emitted), and {@code 16} for an uncaught failure
 * (clamped from any larger or negative value via a pattern-matching switch —
 * no {@code default} branch per AAP &sect;0.7.3). The pattern switch is
 * intentionally exhaustive on {@code Integer} via {@code Integer i when ...}
 * guards.
 *
 * <h2>Shaded packaging</h2>
 * <p>Per AAP &sect;0.4.1 this main is packaged as a single shaded jar
 * (carddemo-app artifact) so the COBOL JCL step {@code //STEP10R EXEC PGM=SORT}
 * is replaceable on the modern stack by:
 * <pre>
 *   java -XX:+UseCompactObjectHeaders \
 *        -XX:+UseShenandoahGC \
 *        -XX:ShenandoahGCMode=generational \
 *        -jar carddemo-app/target/carddemo-app-1.0.0-SNAPSHOT-shaded.jar
 * </pre>
 *
 * <h2>Non-goals (AAP &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring, no Lombok, no Apache Commons.</li>
 *   <li>No reflection.</li>
 *   <li>No {@link java.io.File} &mdash; {@link java.nio.file} only per
 *       AAP &sect;0.6.5.</li>
 *   <li>No {@code double} or {@code float} for the balance &mdash; the
 *       sign-and-digit extraction is byte-level per AAP &sect;0.6.1.</li>
 *   <li>No {@code ThreadLocal} &mdash; {@link java.lang.ScopedValue} only per
 *       AAP &sect;0.6.6.</li>
 *   <li>No preview features &mdash; finalized JEPs only per AAP &sect;0.7.4.</li>
 *   <li>No actual VSAM / IDCAMS interaction &mdash; the file-based runtime
 *       reads the input as plain bytes per AAP &sect;0.6.12.</li>
 * </ul>
 *
 * @see "app/jcl/PRTCATBL.jcl  the originating JCL job"
 * @see "app/proc/REPROC.prc  the cataloged IDCAMS REPRO procedure invoked by STEP05R"
 * @see BatchRunContext  the immutable context bound to {@link #BATCH_CTX}
 * @since 1.0.0
 */
@CobolProgram(
        value = "REPROC + DFSORT (PRTCATBL.jcl)",
        sourcePath = "app/jcl/PRTCATBL.jcl",
        translationDate = "2025-10-24",
        notes = "Translation of the three-step PRTCATBL JCL job: DELDEF (IEFBR14 delete) + "
                + "STEP05R (REPROC IDCAMS REPRO unload of TCATBALF KSDS to BKUP(+1) GDG) + "
                + "STEP10R (DFSORT sort + OUTREC EDIT=(TTTTTTTTT.TT) format). No COBOL "
                + "program is invoked; the @CobolProgram annotation cites the JCL "
                + "orchestration as the source of truth per AAP §0.7.1. Faithful "
                + "translation decisions documented in java/MIGRATION_NOTES.md: (1) the "
                + "OUTREC 9X trailing-filler vs LRECL=40 off-by-one is resolved as 8 "
                + "trailing spaces, honouring the authoritative DCB; (2) ASCII zoned-"
                + "decimal sign overpunch ({, }, A-I, J-R) is decoded byte-level."
)
public final class PrintTcatBalApp {

    // ---------------------------------------------------------------------
    // Logger
    // ---------------------------------------------------------------------

    /**
     * SLF4J logger. Backed at runtime by logback-classic supplied via the
     * carddemo-app shaded jar packaging (see AAP §0.5.1); this class
     * deliberately holds no reference to a concrete logging implementation.
     */
    private static final Logger LOG = LoggerFactory.getLogger(PrintTcatBalApp.class);

    // ---------------------------------------------------------------------
    // ScopedValue (JEP 506 Final) — propagates BatchRunContext into execute()
    // ---------------------------------------------------------------------

    /**
     * Local {@link java.lang.ScopedValue} for the {@link BatchRunContext} that
     * orchestrates this job. Bound by {@link #main(String[])} via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(PrintTcatBalApp::execute)}
     * and read inside {@link #execute()} via {@code BATCH_CTX.get()} to emit
     * the {@code runId} on the PRTCATBL startup log line.
     *
     * <p>This binding replaces {@link ThreadLocal} entirely per AAP §0.6.6
     * and §0.7.4. The {@code ScopedValue} class moved from
     * {@code java.util.concurrent} (preview) to {@code java.lang} (final) when
     * JEP 506 was finalised in Java 25.
     *
     * <p>Per the export schema for this file, this field is part of the
     * {@code PrintTcatBalApp} public surface — co-located with
     * {@link BatchRunContext#BATCH_CTX} but scoped to this app to keep the
     * binding window narrow.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // ---------------------------------------------------------------------
    // Record layout — SYMNAMES from PRTCATBL.jcl, 0-indexed
    // ---------------------------------------------------------------------

    /** Input record length: {@code LRECL=50} per the {@code STEP05R PRC001.FILEOUT} DD. */
    static final int INPUT_RECORD_LENGTH = 50;

    /** 0-indexed offset of {@code TRANCAT-ACCT-ID} (PIC 9(11)). JCL position 1, length 11, format ZD. */
    static final int OFFSET_ACCT_ID = 0;

    /** Length of {@code TRANCAT-ACCT-ID}: 11 bytes. */
    static final int LENGTH_ACCT_ID = 11;

    /** 0-indexed offset of {@code TRANCAT-TYPE-CD} (PIC X(02)). JCL position 12, length 2, format CH. */
    static final int OFFSET_TYPE_CD = 11;

    /** Length of {@code TRANCAT-TYPE-CD}: 2 bytes. */
    static final int LENGTH_TYPE_CD = 2;

    /** 0-indexed offset of {@code TRANCAT-CD} (PIC 9(04)). JCL position 14, length 4, format ZD. */
    static final int OFFSET_CAT_CD = 13;

    /** Length of {@code TRANCAT-CD}: 4 bytes. */
    static final int LENGTH_CAT_CD = 4;

    /** 0-indexed offset of {@code TRAN-CAT-BAL} (PIC S9(9)V99). JCL position 18, length 11, format ZD. */
    static final int OFFSET_TRAN_CAT_BAL = 17;

    /** Length of {@code TRAN-CAT-BAL}: 11 bytes. */
    static final int LENGTH_TRAN_CAT_BAL = 11;

    /** Output record length: {@code LRECL=40} per the {@code STEP10R SORTOUT} DD. */
    static final int OUTPUT_RECORD_LENGTH = 40;

    /**
     * Width of the {@code EDIT=(TTTTTTTTT.TT)} mask: 9 pre-decimal digits +
     * 1 decimal point + 2 post-decimal digits = 12 characters.
     */
    static final int EDIT_MASK_WIDTH = 12;

    /** Number of pre-decimal digit positions inside the edit mask: 9. */
    static final int EDIT_PRE_DECIMAL_DIGITS = 9;

    /** Number of post-decimal digit positions inside the edit mask: 2. */
    static final int EDIT_POST_DECIMAL_DIGITS = 2;

    // ---------------------------------------------------------------------
    // Return codes — IDCAMS / DFSORT convention
    // ---------------------------------------------------------------------

    /** Successful completion. Both STEP05R and STEP10R succeed. */
    static final int RC_OK = 0;

    /**
     * Soft warning: the TCATBALF input is missing. {@code STEP05R PRC001.FILEIN}
     * resolves to {@code DSN=NULLFILE} on the mainframe when the dataset is
     * absent, yielding IDCAMS condition code 4 ("dataset not found" — non-
     * fatal). This Java translation surfaces the equivalent return code so
     * downstream batch orchestration can branch on it.
     */
    static final int RC_NO_INPUT = 4;

    /**
     * Uncaught exception in the job body — typically an {@link java.io.IOException}
     * from filesystem operations or an unhandled runtime exception. Matches the
     * DFSORT "S0C4 / OC7" abend severity mapped to RC=16.
     */
    static final int RC_ERROR = 16;

    // ---------------------------------------------------------------------
    // Configuration keys — 12-factor (AAP §0.7.2)
    // ---------------------------------------------------------------------

    /** System-property / env-var key for the {@code TCATBALF} input file path. */
    static final String PROP_TCATBALF_PATH = "carddemo.file.tcatbalf.path";

    /** Default {@code TCATBALF} input path (relative to the JVM cwd). */
    static final String DEFAULT_TCATBALF_PATH = "./data/tcatbalf.dat";

    /** System-property / env-var key for the output report path. */
    static final String PROP_REPORT_PATH = "carddemo.file.printcatbl.path";

    /** Default output report path (relative to the JVM cwd). */
    static final String DEFAULT_REPORT_PATH = "./output/tcatbalf.rept";

    /** System-property / env-var key for the GDG (Generation Data Group) root directory. */
    static final String PROP_GDG_ROOT = "carddemo.gdg.root";

    /** Default GDG root directory (relative to the JVM cwd). */
    static final String DEFAULT_GDG_ROOT = "./data/gdg";

    /**
     * GDG base directory name for the TCATBALF backup unloads. Preserved
     * byte-for-byte from the JCL {@code DSN=AWS.M2.CARDDEMO.TCATBALF.BKUP(+1)}
     * dataset name (the {@code (+1)} GDG offset is replaced at runtime by the
     * generation file produced by {@link #computeNextGeneration(Path)}).
     */
    static final String GDG_BKUP_BASE = "AWS.M2.CARDDEMO.TCATBALF.BKUP";

    /**
     * Regular expression for a GDG generation filename, matching the
     * z/OS convention {@code GNNNNV00} preserved on the local filesystem per
     * AAP §0.1.2 ("z/OS GDG → versioned files on a normal filesystem; same
     * naming conventions preserved").
     */
    static final java.util.regex.Pattern GDG_GEN_PATTERN =
            java.util.regex.Pattern.compile("G(\\d{4})V00");

    // ---------------------------------------------------------------------
    // Constructor (utility class — not constructible)
    // ---------------------------------------------------------------------

    /** Utility class — not constructible. */
    private PrintTcatBalApp() {
        throw new AssertionError("PrintTcatBalApp is not constructible");
    }

    // ---------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------

    /**
     * Java main entry point. Resolves the {@link BatchRunContext} from JVM
     * system properties / environment variables, binds it to
     * {@link #BATCH_CTX} via JEP 506 {@code ScopedValue.where(...).call(...)},
     * runs the three-step PRTCATBL job body in {@link #execute()}, and exits
     * with the IDCAMS-style return code clamped to the supported set
     * {@code {0, 4, 8, 12, 16}}.
     *
     * <p>Process-exit values:
     * <ul>
     *   <li>{@link #RC_OK} (0) — both STEP05R and STEP10R succeeded.</li>
     *   <li>{@link #RC_NO_INPUT} (4) — the configured TCATBALF input file is
     *       absent. The Java translation surfaces this as a warn-and-return
     *       so downstream orchestration can detect the soft condition.</li>
     *   <li>{@link #RC_ERROR} (16) — uncaught exception. Any negative
     *       value or any value &gt; 16 is also clamped to 16, matching the
     *       behaviour of {@code COND} expression evaluation in z/OS JCL.</li>
     * </ul>
     *
     * <p>The pattern-matching switch in this method explicitly enumerates
     * 0/4/8/12/16 and uses two guarded {@code Integer i when ...} cases for
     * the negative and &gt;16 clamps; the final {@code case Integer i} (no
     * guard) catches the remaining 1, 2, 3, 5, 6, 7, 9, 10, 11, 13, 14, 15
     * values. No {@code default} branch is permitted per AAP §0.7.3 — the
     * Java compiler enforces exhaustiveness over {@code Integer} via these
     * cases.
     *
     * @param args command-line arguments. Currently unused; configuration
     *             flows exclusively through JVM system properties and
     *             environment variables for 12-factor compliance (AAP §0.7.2).
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        // The selector is intentionally boxed to Integer so the switch below
        // is exhaustive via standard pattern matching (Java 21 Final) rather
        // than primitive patterns (JEP 507 — preview, forbidden by AAP §0.7.4).
        // ScopedValue.Carrier#call returns R (here Integer via autoboxing
        // from the int returned by execute()), so this assignment is direct.
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(PrintTcatBalApp::execute);
        } catch (Exception e) {
            LOG.error("PRTCATBL job failed with uncaught exception", e);
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

    // ---------------------------------------------------------------------
    // Job body — three steps matching the JCL
    // ---------------------------------------------------------------------

    /**
     * Runs the three-step PRTCATBL job body inside the
     * {@link #BATCH_CTX} scope. Returns the IDCAMS-style return code:
     * <ul>
     *   <li>{@link #RC_OK} on success</li>
     *   <li>{@link #RC_NO_INPUT} if the configured {@code TCATBALF} input is
     *       missing</li>
     * </ul>
     *
     * @return the return code; {@link #RC_OK} or {@link #RC_NO_INPUT}
     * @throws IOException if any filesystem operation (read, write, delete,
     *                     directory create, copy, list) fails for a reason
     *                     other than the missing-input soft condition
     */
    static int execute() throws IOException {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("PRTCATBL job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        Path tcatbalfPath = SafePathResolver.resolveTrusted(PROP_TCATBALF_PATH, DEFAULT_TCATBALF_PATH);
        Path reportPath = SafePathResolver.resolveTrusted(PROP_REPORT_PATH, DEFAULT_REPORT_PATH);
        Path gdgRoot = SafePathResolver.resolveTrusted(PROP_GDG_ROOT, DEFAULT_GDG_ROOT);

        // ========================================================
        // Step DELDEF — IEFBR14 with DISP=(MOD,DELETE) on the prior REPT
        // ========================================================
        if (Files.exists(reportPath)) {
            Files.delete(reportPath);
            LOG.info("DELDEF: deleted prior TCATBALF.REPT at {}", reportPath);
        } else {
            LOG.info("DELDEF: no prior TCATBALF.REPT at {} (idempotent)", reportPath);
        }
        Path reportParent = reportPath.getParent();
        if (reportParent != null) {
            Files.createDirectories(reportParent);
        }

        // ========================================================
        // Step STEP05R — REPROC: unload TCATBALF KSDS to BKUP(+1)
        // ========================================================
        if (!Files.exists(tcatbalfPath)) {
            LOG.warn("STEP05R: TCATBALF input not found at {}; returning rc={}",
                    tcatbalfPath, RC_NO_INPUT);
            return RC_NO_INPUT;
        }
        Path bkupBaseDir = gdgRoot.resolve(GDG_BKUP_BASE);
        Files.createDirectories(bkupBaseDir);
        int nextGen = computeNextGeneration(bkupBaseDir);
        Path bkupNew = bkupBaseDir.resolve(String.format(Locale.ROOT, "G%04dV00", nextGen));
        Files.copy(tcatbalfPath, bkupNew);
        LOG.info("STEP05R: REPROC OK; source={}, target={} (GDG +{}), bytes={}",
                tcatbalfPath, bkupNew, nextGen, Files.size(bkupNew));

        // ========================================================
        // Step STEP10R — DFSORT: read, sort by (acct,type,cat), OUTREC format, write
        // ========================================================
        List<byte[]> records = readFixedWidthRecords(bkupNew, INPUT_RECORD_LENGTH);
        LOG.info("STEP10R: read {} record(s) from {}", records.size(), bkupNew);

        // SORT FIELDS=(TRANCAT-ACCT-ID,A, TRANCAT-TYPE-CD,A, TRANCAT-CD,A) — stable sort
        records.sort((a, b) -> {
            int cmp = compareBytes(a, OFFSET_ACCT_ID, b, OFFSET_ACCT_ID, LENGTH_ACCT_ID);
            if (cmp != 0) {
                return cmp;
            }
            cmp = compareBytes(a, OFFSET_TYPE_CD, b, OFFSET_TYPE_CD, LENGTH_TYPE_CD);
            if (cmp != 0) {
                return cmp;
            }
            return compareBytes(a, OFFSET_CAT_CD, b, OFFSET_CAT_CD, LENGTH_CAT_CD);
        });

        // OUTREC FIELDS=(...) — assemble 40-byte formatted record for each input record
        try (OutputStream out = Files.newOutputStream(reportPath, StandardOpenOption.CREATE_NEW)) {
            for (byte[] rec : records) {
                out.write(formatOutputRecord(rec));
            }
        }
        LOG.info("STEP10R: SORT+OUTREC OK; wrote {} formatted record(s) ({} bytes) to {}",
                records.size(), Files.size(reportPath), reportPath);

        LOG.info("PRTCATBL job complete; rc={}", RC_OK);
        return RC_OK;
    }

    // ---------------------------------------------------------------------
    // Helper methods — file I/O, formatting, comparator, GDG, config
    // ---------------------------------------------------------------------

    /**
     * Reads a fixed-width-record file into a list of immutable byte-array
     * records. The input file size MUST be a positive multiple of
     * {@code recordLength}; any trailing partial record indicates upstream
     * corruption (typically a mid-write crash) and triggers an
     * {@link IOException}.
     *
     * <p>Loading the whole file into memory is appropriate for TCATBALF
     * (which is bounded by the active account-type-category cardinality on
     * the order of thousands of records; the fixture in
     * {@code app/data/ASCII/tcatbal.txt} is 2,550 bytes / 51 records). For
     * larger inputs this strategy would be replaced by a streaming reader
     * with an external-merge sort, but that is out of scope for the
     * faithful-translation milestone (see {@code java/MIGRATION_NOTES.md}).
     *
     * @param file         the file to read; must be readable
     * @param recordLength the fixed record length in bytes; must be {@code > 0}
     * @return a new {@code ArrayList} of byte-array records, each of length
     *         {@code recordLength}; never {@code null}, possibly empty
     * @throws IOException if the file is unreadable or its size is not a
     *                     multiple of {@code recordLength}
     */
    static List<byte[]> readFixedWidthRecords(Path file, int recordLength) throws IOException {
        if (recordLength <= 0) {
            throw new IllegalArgumentException(
                    "recordLength must be positive; got " + recordLength);
        }
        byte[] all = Files.readAllBytes(file);
        if (all.length % recordLength != 0) {
            throw new IOException("File " + file + " has length " + all.length
                    + " which is not a multiple of recordLength=" + recordLength);
        }
        int n = all.length / recordLength;
        List<byte[]> records = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byte[] rec = new byte[recordLength];
            System.arraycopy(all, i * recordLength, rec, 0, recordLength);
            records.add(rec);
        }
        return records;
    }

    /**
     * Formats one 50-byte input record into a 40-byte output record matching
     * the JCL {@code OUTREC FIELDS=(...)} specification:
     * <pre>
     *   [ TRANCAT-ACCT-ID ][ ][ TRANCAT-TYPE-CD ][ ][ TRANCAT-CD ][ ][ EDIT(...) ][ trailing spaces ]
     *   |&lt;----- 11 -----&gt;||||&lt;------- 2 -----&gt;||||&lt;--- 4 ---&gt;|||&lt;--- 12 ---&gt;||&lt;------ 8 ------&gt;|
     * </pre>
     * Total: {@code 11 + 1 + 2 + 1 + 4 + 1 + 12 + 8 = 40} bytes, matching the
     * JCL {@code SORTOUT DCB=(LRECL=40,RECFM=FB)}. The trailing-space count of
     * 8 (rather than the literal {@code 9X} in the OUTREC clause) honours the
     * authoritative DCB; see the class Javadoc for the rationale.
     *
     * <p>The first three fields are copied verbatim from the source bytes —
     * they are ASCII characters in the reference fixture and require no
     * translation. The fourth field ({@code TRAN-CAT-BAL}) is rendered via
     * {@link #formatTtttttttttDotTt(byte[], int, int)} so the
     * {@code EDIT=(TTTTTTTTT.TT)} mask, leading-zero suppression, and ASCII
     * zoned-decimal sign overpunch are all applied consistently.
     *
     * @param rec the 50-byte input record; must be non-{@code null} and at
     *            least {@link #INPUT_RECORD_LENGTH} bytes long
     * @return a fresh 40-byte output record
     * @throws IndexOutOfBoundsException if {@code rec} is shorter than
     *                                   {@link #INPUT_RECORD_LENGTH}
     */
    static byte[] formatOutputRecord(byte[] rec) {
        if (rec.length < INPUT_RECORD_LENGTH) {
            throw new IndexOutOfBoundsException("rec.length=" + rec.length
                    + " is shorter than INPUT_RECORD_LENGTH=" + INPUT_RECORD_LENGTH);
        }
        byte[] out = new byte[OUTPUT_RECORD_LENGTH];
        Arrays.fill(out, (byte) ' ');

        int pos = 0;
        // TRANCAT-ACCT-ID (11 bytes)
        System.arraycopy(rec, OFFSET_ACCT_ID, out, pos, LENGTH_ACCT_ID);
        pos += LENGTH_ACCT_ID;
        // X (1 space) — separator
        out[pos++] = ' ';
        // TRANCAT-TYPE-CD (2 bytes)
        System.arraycopy(rec, OFFSET_TYPE_CD, out, pos, LENGTH_TYPE_CD);
        pos += LENGTH_TYPE_CD;
        // X (1 space) — separator
        out[pos++] = ' ';
        // TRANCAT-CD (4 bytes)
        System.arraycopy(rec, OFFSET_CAT_CD, out, pos, LENGTH_CAT_CD);
        pos += LENGTH_CAT_CD;
        // X (1 space) — separator
        out[pos++] = ' ';
        // TRAN-CAT-BAL rendered through EDIT=(TTTTTTTTT.TT) (12 bytes)
        String balanceStr =
                formatTtttttttttDotTt(rec, OFFSET_TRAN_CAT_BAL, LENGTH_TRAN_CAT_BAL);
        byte[] balBytes = balanceStr.getBytes(StandardCharsets.US_ASCII);
        // Defensive: clamp to EDIT_MASK_WIDTH in case of formatter bug. The
        // formatter is contractually obligated to return exactly EDIT_MASK_WIDTH
        // characters; the clamp is a belt-and-braces guard.
        int copyLen = Math.min(balBytes.length, EDIT_MASK_WIDTH);
        System.arraycopy(balBytes, 0, out, pos, copyLen);
        pos += EDIT_MASK_WIDTH;
        // Remaining bytes (out[pos..OUTPUT_RECORD_LENGTH)) are the 8 trailing
        // spaces — already populated by Arrays.fill above. pos is intentionally
        // referenced (via assignment) to make the field offsets explicit; the
        // last assignment is a stylistic cue rather than a functional one.
        assert pos + (OUTPUT_RECORD_LENGTH - pos) == OUTPUT_RECORD_LENGTH;
        return out;
    }

    /**
     * Renders an {@code LENGTH_TRAN_CAT_BAL}-byte signed zoned-decimal value
     * (PIC S9(9)V99) through the DFSORT {@code EDIT=(TTTTTTTTT.TT)} mask. The
     * output is always exactly {@link #EDIT_MASK_WIDTH} characters wide.
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Decode 11 digits from the source bytes plus the sign:
     *       <ul>
     *         <li>Bytes 0..9 are plain ASCII digits {@code '0'..'9'} in the
     *             reference fixture. Any other byte is treated defensively as
     *             a packed-decimal pair {@code (high, low)} so legacy inputs
     *             survive without corruption.</li>
     *         <li>Byte 10 is the sign-bearing byte — see the IBM-1047
     *             zoned-decimal overpunch table in the class Javadoc. The
     *             digit and the sign are extracted in one switch.</li>
     *       </ul></li>
     *   <li>Split the 11 digits into {@code pre[0..8]} (9 pre-decimal) and
     *       {@code post[0..1]} (2 post-decimal).</li>
     *   <li>Apply leading-zero suppression: leading zeros up to and including
     *       index 7 become spaces; index 8 (the units position) is always
     *       rendered as a digit so a zero balance reads as
     *       {@code "        0.00"}.</li>
     *   <li>If the value is negative, replace the rightmost suppression-blank
     *       position with a {@code '-'} character (DFSORT default sign
     *       behaviour: {@code SIGNS=(,-,,,)}).</li>
     * </ol>
     *
     * @param rec    the source record buffer; must be non-{@code null}
     * @param offset 0-indexed start of the zoned-decimal field within
     *               {@code rec}; must satisfy
     *               {@code offset + length <= rec.length}
     * @param length the field length; must equal {@link #LENGTH_TRAN_CAT_BAL}
     *               (other widths are not required by PRTCATBL and are
     *               rejected to surface integration mistakes early)
     * @return a string of length {@link #EDIT_MASK_WIDTH} (always 12)
     * @throws IllegalArgumentException if {@code length} is not the supported
     *                                  width
     * @throws IndexOutOfBoundsException if {@code offset + length} exceeds
     *                                   {@code rec.length}
     */
    static String formatTtttttttttDotTt(byte[] rec, int offset, int length) {
        if (length != LENGTH_TRAN_CAT_BAL) {
            throw new IllegalArgumentException(
                    "Only PIC S9(9)V99 (length=" + LENGTH_TRAN_CAT_BAL
                            + ") is supported; got length=" + length);
        }
        if (offset + length > rec.length) {
            throw new IndexOutOfBoundsException("offset+length=" + (offset + length)
                    + " exceeds rec.length=" + rec.length);
        }

        // Decode 11 digits + sign from the source bytes.
        char[] digits = new char[LENGTH_TRAN_CAT_BAL];
        boolean negative = false;
        for (int i = 0; i < length; i++) {
            int b = rec[offset + i] & 0xFF;
            if (i == length - 1) {
                // Final byte: digit + sign overpunch (IBM-1047 zoned-decimal).
                negative = decodeOverpunchSign(b);
                digits[i] = decodeOverpunchDigit(b);
            } else {
                // Non-final bytes: plain ASCII digit, or packed-decimal high
                // nybble (defensive — never observed in the reference fixture).
                if (b >= '0' && b <= '9') {
                    digits[i] = (char) b;
                } else {
                    int high = (b >> 4) & 0x0F;
                    digits[i] = (char) ('0' + Math.min(high, 9));
                }
            }
        }

        // Split into pre-decimal (9 digits) and post-decimal (2 digits).
        // Apply leading-zero suppression: positions 0..7 become spaces while
        // every preceding digit is '0'; position 8 (units) is always rendered.
        char[] formatted = new char[EDIT_MASK_WIDTH];
        boolean nonZeroSeen = false;
        for (int i = 0; i < EDIT_PRE_DECIMAL_DIGITS; i++) {
            char c = digits[i];
            if (c != '0') {
                nonZeroSeen = true;
            }
            if (!nonZeroSeen && i < EDIT_PRE_DECIMAL_DIGITS - 1) {
                formatted[i] = ' ';
            } else {
                formatted[i] = c;
            }
        }
        formatted[EDIT_PRE_DECIMAL_DIGITS] = '.';
        for (int j = 0; j < EDIT_POST_DECIMAL_DIGITS; j++) {
            formatted[EDIT_PRE_DECIMAL_DIGITS + 1 + j] =
                    digits[EDIT_PRE_DECIMAL_DIGITS + j];
        }

        // Negative sign: replace the rightmost suppression-blank with '-'.
        // Scan right-to-left across the pre-decimal columns (skipping the
        // decimal point and the two post-decimal positions, hence the
        // length - 4 starting index).
        if (negative) {
            for (int i = formatted.length - (EDIT_POST_DECIMAL_DIGITS + 2); i >= 0; i--) {
                if (formatted[i] == ' ') {
                    formatted[i] = '-';
                    break;
                }
            }
        }
        return new String(formatted);
    }

    /**
     * Decodes the sign from a single zoned-decimal byte using the IBM-1047
     * overpunch mapping. Documented in the class Javadoc; condensed here:
     * <ul>
     *   <li>{@code '0'..'9'}  &mdash; positive (no overpunch)</li>
     *   <li>{@code '{'}, {@code 'A'..'I'} &mdash; positive</li>
     *   <li>{@code '}'}, {@code 'J'..'R'} &mdash; negative</li>
     *   <li>Anything else &mdash; treat as positive (defensive default).</li>
     * </ul>
     *
     * <p>For defence-in-depth, this method also recognises the raw EBCDIC
     * packed-decimal sign-nybble convention {@code (0x_D == negative)}: if
     * the low nybble of the byte is {@code 0x0D} or {@code 0x0B}, the value
     * is treated as negative. This case is never expected to fire on the
     * IBM-1047-transcoded ASCII fixture but is preserved so the parser stays
     * robust against unforeseen upstream encoding choices.
     *
     * @param b an unsigned byte ({@code 0..255}); typically the final byte
     *          of a zoned-decimal field
     * @return {@code true} iff the value carries a negative sign
     */
    static boolean decodeOverpunchSign(int b) {
        char c = (char) b;
        if (c == '}' || (c >= 'J' && c <= 'R')) {
            return true;
        }
        if (c == '{' || (c >= 'A' && c <= 'I') || (c >= '0' && c <= '9')) {
            return false;
        }
        // Defensive fallback: packed-decimal sign nybble convention.
        int low = b & 0x0F;
        return low == 0x0D || low == 0x0B;
    }

    /**
     * Decodes the digit from a single zoned-decimal byte using the IBM-1047
     * overpunch mapping; see {@link #decodeOverpunchSign(int)} for the
     * companion sign decoder.
     * <ul>
     *   <li>{@code '0'..'9'}     &mdash; digit {@code 0..9}</li>
     *   <li>{@code '{'}, {@code '}'} &mdash; digit {@code 0}</li>
     *   <li>{@code 'A'..'I'}     &mdash; digit {@code 1..9} (positive overpunch)</li>
     *   <li>{@code 'J'..'R'}     &mdash; digit {@code 1..9} (negative overpunch)</li>
     *   <li>Anything else &mdash; the byte's high nybble (defensive
     *       packed-decimal fallback), clamped to {@code 0..9}.</li>
     * </ul>
     *
     * @param b an unsigned byte ({@code 0..255})
     * @return the digit character in {@code '0'..'9'}
     */
    static char decodeOverpunchDigit(int b) {
        char c = (char) b;
        if (c >= '0' && c <= '9') {
            return c;
        }
        if (c == '{' || c == '}') {
            return '0';
        }
        if (c >= 'A' && c <= 'I') {
            return (char) ('0' + (c - 'A' + 1));
        }
        if (c >= 'J' && c <= 'R') {
            return (char) ('0' + (c - 'J' + 1));
        }
        // Defensive fallback: packed-decimal high nybble.
        int high = (b >> 4) & 0x0F;
        return (char) ('0' + Math.min(high, 9));
    }

    /**
     * Position-wise unsigned-byte comparator. Returns a negative value, zero,
     * or a positive value as the slice {@code a[aOff..aOff+length)} is
     * lexicographically less than, equal to, or greater than the slice
     * {@code b[bOff..bOff+length)}.
     *
     * <p>This is the byte-level analogue of DFSORT's {@code CH} (character)
     * and {@code ZD} (zoned-decimal) sort formats over fields whose contents
     * are plain ASCII characters or unsigned ASCII digits — which is the case
     * for the three sort keys in PRTCATBL ({@code TRANCAT-ACCT-ID},
     * {@code TRANCAT-TYPE-CD}, and {@code TRANCAT-CD} are all unsigned).
     *
     * @param a      the left buffer; must satisfy
     *               {@code aOff + length <= a.length}
     * @param aOff   0-indexed start position in {@code a}
     * @param b      the right buffer; must satisfy
     *               {@code bOff + length <= b.length}
     * @param bOff   0-indexed start position in {@code b}
     * @param length the slice length, in bytes
     * @return {@code -1}, {@code 0}, or {@code +1} per the relative ordering
     *         of the two slices
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
     * Computes the next GDG generation number for a base directory.
     * Generations are filesystem files named {@code GNNNNV00} (per AAP §0.1.2
     * "z/OS GDG → versioned files on a normal filesystem; same naming
     * conventions preserved"). The next generation is one greater than the
     * highest existing {@code NNNN} value, or {@code 1} if the directory is
     * empty.
     *
     * <p>The {@code Files.list} stream is drained in a try-with-resources to
     * release the underlying directory stream promptly. Non-matching filenames
     * (e.g., stray dot files) are silently ignored — the COBOL/z/OS GDG
     * catalog manager applies the same filter implicitly.
     *
     * @param baseDir the GDG base directory; must exist
     * @return the next generation number (at least {@code 1})
     * @throws IOException if the directory listing fails
     */
    static int computeNextGeneration(Path baseDir) throws IOException {
        try (Stream<Path> entries = Files.list(baseDir)) {
            int max = entries
                    .map(p -> p.getFileName().toString())
                    .filter(n -> GDG_GEN_PATTERN.matcher(n).matches())
                    .mapToInt(n -> Integer.parseInt(n.substring(1, 5)))
                    .max()
                    .orElse(0);
            return max + 1;
        }
    }

    /**
     * Resolves a configuration value with the precedence:
     * <ol>
     *   <li>JVM system property {@code key} (e.g., set via
     *       {@code -Dcarddemo.file.tcatbalf.path=...})</li>
     *   <li>Environment variable {@code KEY} (the property name uppercased
     *       and with {@code '.'} and {@code '-'} replaced by {@code '_'} —
     *       e.g., {@code CARDDEMO_FILE_TCATBALF_PATH})</li>
     *   <li>The supplied {@code defaultValue}</li>
     * </ol>
     * Blank (whitespace-only) values are treated as absent. This precedence
     * matches the 12-factor configuration convention used across the
     * carddemo-app composition root (see {@code DefineGdgApp}).
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

