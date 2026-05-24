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
//                            to java.lang when finalized)
//   - java.nio.file.{Path, Files, StandardCopyOption}  (no java.io.File per
//                                                       AAP §0.6.5)
//   - java.io.{ByteArrayOutputStream, IOException}     (byte assembly only)
//   - java.nio.charset.StandardCharsets                (US_ASCII transcoding)
//   - java.util.{List, Locale}
//   - java.lang.String/Integer/System/Exception/AssertionError
import module java.base;

import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/DUSRSECJ.jcl} &mdash;
 * a four-step JCL job that seeds the {@code USRSEC.VSAM.KSDS} cluster with
 * ten hard-coded user records (five admin + five user) via IEBGENER + IDCAMS.
 *
 * <h2>Source artifact</h2>
 * <p>{@code app/jcl/DUSRSECJ.jcl} contains four steps; each is preserved by
 * this Java translation (AAP &sect;0.7.1 idiom-for-idiom mandate):
 * <ol>
 *   <li><strong>PREDEL</strong> &mdash; {@code EXEC PGM=IEFBR14} with
 *       {@code DISP=(MOD,DELETE,DELETE)} on {@code AWS.M2.CARDDEMO.USRSEC.PS}.
 *       Idempotent pre-delete of the staging sequential file.</li>
 *   <li><strong>STEP01</strong> &mdash; {@code EXEC PGM=IEBGENER} copying the
 *       in-stream {@code SYSUT1} (ten 80-byte records) to
 *       {@code SYSUT2=AWS.M2.CARDDEMO.USRSEC.PS} with {@code LRECL=80
 *       RECFM=FB DSORG=PS}.</li>
 *   <li><strong>STEP02</strong> &mdash; {@code EXEC PGM=IDCAMS} that
 *       {@code DELETE}s and re-{@code DEFINE}s the cluster
 *       {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS} with
 *       {@code KEYS(8,0) RECORDSIZE(80,80) REUSE INDEXED TRACKS(45,15)
 *       FREESPACE(10,15) CISZ(8192)}, plus {@code DATA} and {@code INDEX}
 *       component clauses.</li>
 *   <li><strong>STEP03</strong> &mdash; {@code EXEC PGM=IDCAMS} that
 *       {@code REPRO}s the PS file into the freshly-defined KSDS cluster.</li>
 * </ol>
 *
 * <h2>Record layout (per {@code app/cpy/CSUSR01Y.cpy:&sect;SEC-USER-DATA})</h2>
 * Each of the ten records is exactly 80 bytes:
 * <pre>
 *   bytes  0..7   SEC-USR-ID     PIC X(08)
 *   bytes  8..27  SEC-USR-FNAME  PIC X(20)
 *   bytes 28..47  SEC-USR-LNAME  PIC X(20)
 *   bytes 48..55  SEC-USR-PWD    PIC X(08)
 *   byte  56      SEC-USR-TYPE   PIC X(01)  ('A' for admin, 'U' for user)
 *   bytes 57..79  SEC-USR-FILLER PIC X(23)  (trailing spaces)
 * </pre>
 *
 * <h2>Plaintext-password preservation (AAP &sect;0.1.3)</h2>
 * The original COBOL system stores passwords in plaintext as
 * {@code SEC-USR-PWD PIC X(08)}. Per the AAP &sect;0.1.3 preserve-as-is
 * mandate ("Plaintext password preservation: SEC-USER-DATA stores passwords
 * as plaintext (PIC X(08))"), this seed app preserves that behaviour
 * identically. Introducing BCrypt/Argon2 would be a behaviour change beyond
 * migration scope; the topic is flagged in {@code java/MIGRATION_NOTES.md} as
 * a follow-up effort. Per AAP &sect;0.7.2, passwords are not logged at any
 * level &mdash; only record counts and file sizes are emitted.
 *
 * <h2>File mapping</h2>
 * <ul>
 *   <li>{@code AWS.M2.CARDDEMO.USRSEC.PS} (mainframe PS staging file)
 *       &rarr; sibling {@code usrsec.ps} file in the directory of the
 *       configured KSDS target path.</li>
 *   <li>{@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS} (mainframe VSAM KSDS)
 *       &rarr; the file at {@code carddemo.file.usrsec.path} (default
 *       {@code ./data/usrsec.dat}) plus a sibling
 *       {@code <name>.schema} text file carrying the KSDS metadata.</li>
 * </ul>
 *
 * <h2>Translation strategy &mdash; idiom-for-idiom (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>IEFBR14 delete becomes an idempotent
 *       {@link java.nio.file.Files#deleteIfExists(Path)}-style check-and-delete.</li>
 *   <li>IEBGENER in-stream data becomes a compile-time
 *       {@code List<SeedUser>} that is assembled into a byte array using
 *       the exact 8-20-20-8-1-23 layout from {@code CSUSR01Y.cpy}.</li>
 *   <li>IDCAMS {@code DEFINE CLUSTER} becomes a text {@code .schema}
 *       sidecar file that records the KSDS metadata (key length/offset,
 *       record length, freespace, CISZ, REUSE) so downstream readers can
 *       validate the layout. The cluster name is preserved verbatim
 *       ({@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}) for log-comparison
 *       fidelity.</li>
 *   <li>IDCAMS {@code REPRO} becomes a
 *       {@link java.nio.file.Files#copy(Path, Path, java.nio.file.CopyOption...)}
 *       from the PS staging file to the KSDS target.</li>
 * </ul>
 *
 * <h2>Process-exit semantics</h2>
 * <p>Each IDCAMS-style step contributes to the highest condition code (MAXCC)
 * the JCL would observe. This main preserves that contract:
 * <ul>
 *   <li>{@code 0}  &mdash; every step succeeded.</li>
 *   <li>{@code 16} &mdash; any uncaught exception (IDCAMS "severe error"
 *       convention).</li>
 * </ul>
 *
 * <h2>BatchRunContext / ScopedValue (AAP &sect;0.6.6, JEP 506 Final)</h2>
 * <p>{@link #main(String[])} resolves a {@link BatchRunContext} via
 * {@link BatchRunContext#fromEnvironment()} (12-factor configuration via JVM
 * system properties and environment variables) and binds it to
 * {@link #BATCH_CTX} for the duration of {@link #execute()}. Inside
 * {@code execute()}, {@code runId} is read from {@code BATCH_CTX.get()} for
 * the structured-logging startup line so that operations teams can correlate
 * log lines with a specific batch run identifier.
 *
 * <h2>JVM-tuning recommendation (AAP &sect;0.3.4)</h2>
 * Launch with the finalized GC and heap-layout flags:
 * <pre>
 *   java -XX:+UseCompactObjectHeaders          (JEP 519, Final in 25)
 *        -XX:+UseShenandoahGC
 *        -XX:ShenandoahGCMode=generational     (JEP 521, Final in 25)
 *        -jar carddemo-app/target/carddemo-app-&lt;version&gt;-shaded.jar
 * </pre>
 *
 * <h2>Non-goals (AAP &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring, no Lombok, no Apache Commons.</li>
 *   <li>No reflection or dynamic proxies.</li>
 *   <li>No {@code java.io.File} &mdash; {@link java.nio.file} only per
 *       AAP &sect;0.6.5.</li>
 *   <li>No {@link ThreadLocal} &mdash; {@link java.lang.ScopedValue} only
 *       per AAP &sect;0.6.6.</li>
 *   <li>No preview features &mdash; the {@code --enable-preview} flag is
 *       forbidden.</li>
 *   <li>No password hashing &mdash; plaintext storage preserved per
 *       AAP &sect;0.1.3.</li>
 *   <li>No password logging &mdash; only record counts and file sizes
 *       per AAP &sect;0.7.2.</li>
 * </ul>
 *
 * @see app/jcl/DUSRSECJ.jcl  the original source JCL
 * @see app/cpy/CSUSR01Y.cpy  the SEC-USER-DATA copybook defining the layout
 * @see BatchRunContext       the immutable context bound to {@link #BATCH_CTX}
 * @since 1.0.0
 */
@CobolProgram(
        value = "IEBGENER+IDCAMS (DUSRSECJ.jcl)",
        sourcePath = "app/jcl/DUSRSECJ.jcl",
        translationDate = "2025-10-24",
        notes = "JCL translation of the four-step USRSEC seed job (PREDEL + IEBGENER + "
                + "IDCAMS DEFINE + IDCAMS REPRO). Inline SYSUT1 data (10 user records of "
                + "80 bytes each) is preserved verbatim from the original JCL; plaintext "
                + "passwords are preserved per AAP §0.1.3 preserve-as-is mandate. The "
                + "VSAM KSDS cluster name and DEFINE attributes (KEYS(8,0), "
                + "RECORDSIZE(80,80), REUSE, FREESPACE(10,15), CISZ(8192)) are preserved "
                + "in a sidecar .schema file for downstream readers."
)
public final class UsersSecuritySeedApp {

    // ---------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------

    /**
     * SLF4J logger. Backed at runtime by logback-classic supplied via the
     * carddemo-app shaded jar packaging (see AAP §0.5.1); this class
     * deliberately holds no reference to a concrete logging implementation.
     */
    private static final Logger LOG = LoggerFactory.getLogger(UsersSecuritySeedApp.class);

    // ---------------------------------------------------------------------
    // ScopedValue (JEP 506 Final) — propagates BatchRunContext into execute()
    // ---------------------------------------------------------------------

    /**
     * Local {@link java.lang.ScopedValue} for the {@link BatchRunContext} that
     * orchestrates this job. Bound by {@link #main(String[])} via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(UsersSecuritySeedApp::execute)}
     * and read inside {@link #execute()} via {@code BATCH_CTX.get()} to emit
     * the {@code runId} on the DUSRSECJ startup log line.
     *
     * <p>This binding replaces {@link ThreadLocal} entirely per AAP §0.6.6
     * and §0.7.4. The {@code ScopedValue} class moved from
     * {@code java.util.concurrent} (preview) to {@code java.lang} (final) when
     * JEP 506 was finalised in Java 25.
     *
     * <p>Per the export schema for this file, this field is part of the
     * {@code UsersSecuritySeedApp} public surface &mdash; co-located with
     * {@link BatchRunContext#BATCH_CTX} but scoped to this app to keep the
     * binding window narrow.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // ---------------------------------------------------------------------
    // KSDS cluster metadata — verbatim from app/jcl/DUSRSECJ.jcl STEP02
    // ---------------------------------------------------------------------

    /** VSAM KSDS cluster name preserved verbatim from {@code DUSRSECJ.jcl} STEP02. */
    static final String CLUSTER_NAME = "AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS";

    /** Sequential staging file name preserved verbatim from {@code DUSRSECJ.jcl}. */
    static final String PS_NAME = "AWS.M2.CARDDEMO.USRSEC.PS";

    /** {@code KEYS(8,0)} &mdash; key length in bytes. */
    static final int KEY_LENGTH = 8;

    /** {@code KEYS(8,0)} &mdash; key offset from record start (0). */
    static final int KEY_OFFSET = 0;

    /** {@code RECORDSIZE(80,80)} &mdash; fixed record length in bytes. */
    static final int RECORD_LENGTH = 80;

    /** {@code FREESPACE(10,15)} &mdash; CI/CA free-space percentages. */
    static final String FREESPACE = "10 15";

    /** {@code CISZ(8192)} &mdash; control-interval size in bytes. */
    static final int CISIZE = 8192;

    /** {@code REUSE} &mdash; flag indicating the cluster is reusable. */
    static final boolean REUSE = true;

    // ---------------------------------------------------------------------
    // Return codes (IDCAMS convention)
    // ---------------------------------------------------------------------

    /** Successful return code: every step completed. */
    static final int RC_OK = 0;

    /** IDCAMS "severe error" return code: an uncaught exception occurred. */
    static final int RC_ERROR = 16;

    // ---------------------------------------------------------------------
    // Configuration keys (12-factor per AAP §0.7.2)
    // ---------------------------------------------------------------------

    /**
     * JVM system-property key for the KSDS target path. Documented in
     * {@code java/application.properties.example}.
     */
    static final String PROP_USRSEC_PATH = "carddemo.file.usrsec.path";

    /**
     * Default KSDS target path used when no system property and no environment
     * variable supply a value.
     */
    static final String DEFAULT_USRSEC_PATH = "./data/usrsec.dat";

    // ---------------------------------------------------------------------
    // Inline SYSUT1 seed data — verbatim from app/jcl/DUSRSECJ.jcl STEP01
    // ---------------------------------------------------------------------

    /**
     * Immutable record carrying one seeded user. Fields map directly to the
     * 5 named subfields of {@code SEC-USER-DATA} in {@code CSUSR01Y.cpy}
     * (the sixth, {@code SEC-USR-FILLER}, is always blank and is emitted by
     * {@link #buildInlineRecords()} rather than carried in the record).
     */
    private record SeedUser(String userId, String firstName, String lastName,
                             String password, char userType) {
    }

    /**
     * The 10 inline SYSUT1 records from {@code DUSRSECJ.jcl}, in EXACT order.
     * Layout per {@code app/cpy/CSUSR01Y.cpy:&sect;SEC-USER-DATA}:
     * <pre>
     *   SEC-USR-ID     PIC X(08)
     * + SEC-USR-FNAME  PIC X(20)
     * + SEC-USR-LNAME  PIC X(20)
     * + SEC-USR-PWD    PIC X(08)
     * + SEC-USR-TYPE   PIC X(01)
     * + SEC-USR-FILLER PIC X(23)
     * = 80 bytes total
     * </pre>
     *
     * <p>Names and IDs are preserved verbatim from the original JCL (the
     * JCL literal {@code PASSWORDA}/{@code PASSWORDU} encodes the 8-byte
     * password {@code "PASSWORD"} followed by the 1-byte user type letter
     * {@code 'A'}/{@code 'U'} in the byte layout). The first five records
     * are administrators ({@code SEC-USR-TYPE = 'A'}); the last five are
     * regular users ({@code SEC-USR-TYPE = 'U'}).
     */
    static final List<SeedUser> SEED_USERS = List.of(
            new SeedUser("ADMIN001", "MARGARET",  "GOLD",       "PASSWORD", 'A'),
            new SeedUser("ADMIN002", "RUSSELL",   "RUSSELL",    "PASSWORD", 'A'),
            new SeedUser("ADMIN003", "RAYMOND",   "WHITMORE",   "PASSWORD", 'A'),
            new SeedUser("ADMIN004", "EMMANUEL",  "CASGRAIN",   "PASSWORD", 'A'),
            new SeedUser("ADMIN005", "GRANVILLE", "LACHAPELLE", "PASSWORD", 'A'),
            new SeedUser("USER0001", "LAWRENCE",  "THOMAS",     "PASSWORD", 'U'),
            new SeedUser("USER0002", "AJITH",     "KUMAR",      "PASSWORD", 'U'),
            new SeedUser("USER0003", "LAURITZ",   "ALME",       "PASSWORD", 'U'),
            new SeedUser("USER0004", "AVERARDO",  "MAZZI",      "PASSWORD", 'U'),
            new SeedUser("USER0005", "LEE",       "TING",       "PASSWORD", 'U')
    );

    // ---------------------------------------------------------------------
    // Construction guard
    // ---------------------------------------------------------------------

    /** Utility class &mdash; not constructible. */
    private UsersSecuritySeedApp() {
        throw new AssertionError("UsersSecuritySeedApp is not constructible");
    }

    // ---------------------------------------------------------------------
    // Java main entry point
    // ---------------------------------------------------------------------

    /**
     * Java main entry point. Mirrors the four-step DUSRSECJ.jcl job:
     * resolves a {@link BatchRunContext} from JVM system properties and
     * environment variables (12-factor configuration), binds it to
     * {@link #BATCH_CTX} via JEP 506 {@code ScopedValue.where(...).call(...)},
     * invokes {@link #execute()}, and translates the returned IDCAMS-style
     * return code to a {@link System#exit(int)} call.
     *
     * @param args command-line arguments. Currently unused; configuration
     *             flows exclusively through JVM system properties and
     *             environment variables for 12-factor compliance (AAP §0.7.2).
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        // Use Integer (not int) for rc so the switch below can use the
        // standard pattern-matching guards (case Integer i when i < 0).
        // Primitive patterns (JEP 507) are preview-only and forbidden by
        // AAP §0.7.4. ScopedValue.Carrier#call returns R (here Integer via
        // autoboxing from the int returned by execute()).
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(UsersSecuritySeedApp::execute);
        } catch (Exception e) {
            LOG.error("DUSRSECJ job failed with uncaught exception", e);
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
    // Job body — four steps matching the JCL
    // ---------------------------------------------------------------------

    /**
     * Runs the four-step DUSRSECJ job body inside the {@link #BATCH_CTX}
     * scope. Visible for testing.
     *
     * @return {@link #RC_OK} on success
     * @throws IOException if any filesystem operation (delete, write, copy)
     *                     fails
     */
    static int execute() throws IOException {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("DUSRSECJ job starting; runId={}, processingDate={}, tenant={}, cluster={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant(), CLUSTER_NAME);

        // Resolve target paths from configuration. The KSDS target lives at the
        // configured path; the PS staging file is a sibling in the same
        // directory (matches the COBOL dataset-naming convention where both
        // datasets share the same high-level qualifier prefix).
        Path target = Path.of(getProp(PROP_USRSEC_PATH, DEFAULT_USRSEC_PATH)).toAbsolutePath();
        Path parentDir = target.getParent() != null
                ? target.getParent()
                : Path.of(".").toAbsolutePath();
        Path psFile = parentDir.resolve("usrsec.ps");

        // Ensure the parent directory exists before any file I/O.
        Files.createDirectories(parentDir);

        // ========================================================
        // PREDEL — IEFBR14 with DISP=(MOD,DELETE) on USRSEC.PS
        // ========================================================
        // Idempotent: COBOL JCL allocates the dataset with DISP=MOD (creates
        // if absent) and then deletes; this means a missing PS file is OK.
        // Files.deleteIfExists captures both branches in a single call.
        boolean psDeleted = Files.deleteIfExists(psFile);
        if (psDeleted) {
            LOG.info("PREDEL: deleted prior staging file {} (mainframe dataset {})",
                    psFile, PS_NAME);
        } else {
            LOG.info("PREDEL: no prior staging file at {} (idempotent; mainframe dataset {})",
                    psFile, PS_NAME);
        }

        // ========================================================
        // STEP01 — IEBGENER: copy inline SYSUT1 → SYSUT2 (PS)
        // ========================================================
        // Build the 10×80-byte block from the SEED_USERS list and write it
        // atomically. The block size (800 bytes) is the only byte payload
        // emitted; no record-separator bytes are added because LRECL=80
        // RECFM=FB has no separators in the mainframe contract.
        byte[] psBytes = buildInlineRecords();
        Files.write(psFile, psBytes);
        LOG.info("STEP01: IEBGENER OK; wrote {} bytes ({} records of {} bytes) -> {}",
                psBytes.length, SEED_USERS.size(), RECORD_LENGTH, psFile);

        // ========================================================
        // STEP02 — IDCAMS DELETE + DEFINE CLUSTER
        // ========================================================
        // The COBOL job DELETEs the prior KSDS, sets MAXCC=0 to swallow the
        // "not found" condition, and DEFINEs a fresh cluster. The Java
        // translation:
        //   - deletes the KSDS file at the target path (idempotent)
        //   - deletes any stale schema sidecar
        //   - writes a new schema sidecar with the verbatim DEFINE attributes
        Files.deleteIfExists(target);
        Path schema = parentDir.resolve(target.getFileName() + ".schema");
        Files.deleteIfExists(schema);
        Files.writeString(schema,
                "cluster=" + CLUSTER_NAME + System.lineSeparator()
                        + "keyLength=" + KEY_LENGTH + System.lineSeparator()
                        + "keyOffset=" + KEY_OFFSET + System.lineSeparator()
                        + "recordLength=" + RECORD_LENGTH + System.lineSeparator()
                        + "freeSpace=" + FREESPACE + System.lineSeparator()
                        + "ciSize=" + CISIZE + System.lineSeparator()
                        + "reuse=" + REUSE + System.lineSeparator()
                        + "dataComponent=" + CLUSTER_NAME + ".DAT" + System.lineSeparator()
                        + "indexComponent=" + CLUSTER_NAME + ".IDX" + System.lineSeparator());
        LOG.info("STEP02: DEFINE OK cluster={}, key={},{} record={} FREESPACE({}) CISZ({}) REUSE={}",
                CLUSTER_NAME, KEY_LENGTH, KEY_OFFSET, RECORD_LENGTH, FREESPACE, CISIZE, REUSE);

        // ========================================================
        // STEP03 — IDCAMS REPRO INFILE(IN) OUTFILE(OUT)
        // ========================================================
        // Copy the staging PS file byte-for-byte into the KSDS target. Per the
        // COBOL contract the REPRO does no re-ordering (the input is already
        // in key-ascending order because the IEBGENER input was authored that
        // way), so a straight Files.copy preserves the on-disk layout exactly.
        // StandardCopyOption.REPLACE_EXISTING guards against a residual file
        // that somehow survived the STEP02 delete (e.g. an external symlink).
        Files.copy(psFile, target, StandardCopyOption.REPLACE_EXISTING);
        LOG.info("STEP03: REPRO OK source={} target={} bytes={}",
                psFile, target, Files.size(target));

        LOG.info("DUSRSECJ job complete; rc={}", RC_OK);
        return RC_OK;
    }

    // ---------------------------------------------------------------------
    // Byte assembly — the IEBGENER record block
    // ---------------------------------------------------------------------

    /**
     * Builds the 10&nbsp;&times;&nbsp;80-byte inline SYSUT1 record block.
     * <p>Per {@code app/cpy/CSUSR01Y.cpy:&sect;SEC-USER-DATA}:
     * <pre>
     *   SEC-USR-ID     PIC X(08)
     *   SEC-USR-FNAME  PIC X(20)
     *   SEC-USR-LNAME  PIC X(20)
     *   SEC-USR-PWD    PIC X(08)
     *   SEC-USR-TYPE   PIC X(01)
     *   SEC-USR-FILLER PIC X(23)
     *   = 80 bytes total
     * </pre>
     *
     * <p>Encoding is {@link StandardCharsets#US_ASCII}: every character in the
     * seed data is a 7-bit ASCII printable, and the {@code app/data/ASCII/*}
     * fixtures (per AAP &sect;0.2.1) are likewise US-ASCII. Production
     * deployments that need EBCDIC output configure a per-file codepage
     * override via {@code carddemo.file.usrsec.charset} (out of scope for
     * this seed app, which writes the post-transcoded staging file).
     *
     * <p>Visible for testing.
     *
     * @return a byte array of exactly {@code SEED_USERS.size() * RECORD_LENGTH}
     *         bytes (800 bytes for the 10 seed users)
     */
    static byte[] buildInlineRecords() {
        ByteArrayOutputStream baos =
                new ByteArrayOutputStream(SEED_USERS.size() * RECORD_LENGTH);
        for (SeedUser user : SEED_USERS) {
            // Bytes 0..7   — SEC-USR-ID
            baos.writeBytes(padRight(user.userId(),    8).getBytes(StandardCharsets.US_ASCII));
            // Bytes 8..27  — SEC-USR-FNAME
            baos.writeBytes(padRight(user.firstName(), 20).getBytes(StandardCharsets.US_ASCII));
            // Bytes 28..47 — SEC-USR-LNAME
            baos.writeBytes(padRight(user.lastName(),  20).getBytes(StandardCharsets.US_ASCII));
            // Bytes 48..55 — SEC-USR-PWD
            baos.writeBytes(padRight(user.password(),  8).getBytes(StandardCharsets.US_ASCII));
            // Byte 56      — SEC-USR-TYPE (single ASCII character)
            baos.writeBytes(new byte[] {(byte) user.userType()});
            // Bytes 57..79 — SEC-USR-FILLER (23 spaces)
            baos.writeBytes(padRight("", 23).getBytes(StandardCharsets.US_ASCII));
        }
        return baos.toByteArray();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Right-pads a value with US-ASCII space characters to exactly the given
     * length, mirroring the COBOL {@code PIC X(n)} convention of trailing
     * spaces. If the value is longer than {@code length}, it is truncated
     * (the COBOL receiving field would silently truncate to length n).
     *
     * <p>Visible for testing.
     *
     * @param value  the value to pad; must not be {@code null}
     * @param length the target length in characters; must be &gt;= 0
     * @return a string of exactly {@code length} characters
     */
    static String padRight(String value, int length) {
        if (value.length() >= length) {
            return value.substring(0, length);
        }
        return value + " ".repeat(length - value.length());
    }

    /**
     * Resolves a configuration property by consulting (in precedence order)
     * the JVM environment variable derived from {@code key}, then the JVM
     * system property {@code key}, then the provided {@code defaultValue}.
     *
     * <p>The environment-variable key is derived from {@code key} by
     * upper-casing (with {@link Locale#ROOT}) and replacing every
     * {@code '.'} and {@code '-'} with {@code '_'} &mdash; i.e.,
     * {@code "carddemo.file.usrsec.path"} maps to
     * {@code "CARDDEMO_FILE_USRSEC_PATH"}. This matches the convention used
     * by the sibling {@link OpenFileApp} and {@link DefineGdgApp} classes.
     *
     * <p>Visible for testing.
     *
     * @param key          the system-property key; must not be {@code null}
     * @param defaultValue the value to return when neither the environment
     *                     variable nor the system property is set; may be
     *                     {@code null}
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
