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

// JEP 511 (finalized in Java 25): single declaration imports every
// package exported by the java.base module. This brings
// java.nio.file.Files, java.nio.file.Path, java.nio.file.Paths, and
// java.lang.String into scope without further import statements,
// matching the canonical pattern established by the carddemo-application
// classes (AAP §0.7.3).
import module java.base;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java translation of the {@code DEFGDGB.jcl} job that defines six
 * Generation Data Group (GDG) bases on the mainframe via IDCAMS. In
 * the file-based Java runtime (AAP &sect;0.6.12 "File-based default;
 * JDBC adapter optional") GDGs are realised as ordinary versioned
 * files in a filesystem directory; this app creates those directories
 * with the same names as the COBOL GDG bases so downstream batch jobs
 * can write {@code G0001V00}-style generations into them.
 *
 * <h2>Source artifact</h2>
 * <p>{@code app/jcl/DEFGDGB.jcl} step {@code STEP05} (PGM=IDCAMS)
 * defines the following GDG bases, each with {@code LIMIT(5)} and
 * {@code SCRATCH}:
 * <ol>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.BKUP}</li>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.DALY}</li>
 *   <li>{@code AWS.M2.CARDDEMO.TRANREPT}</li>
 *   <li>{@code AWS.M2.CARDDEMO.TCATBALF.BKUP}</li>
 *   <li>{@code AWS.M2.CARDDEMO.SYSTRAN}</li>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.COMBINED}</li>
 * </ol>
 *
 * <h2>Translation strategy &mdash; idiom-for-idiom (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>Each {@code DEFINE GENERATIONDATAGROUP NAME(...)} clause
 *       becomes a {@link java.nio.file.Files#createDirectories(Path, java.nio.file.attribute.FileAttribute...)}
 *       call on a directory whose name is the GDG dataset name. The
 *       dataset name is preserved verbatim (uppercase, dots and all)
 *       because downstream JCL-translated apps reference these
 *       directories by that exact name.</li>
 *   <li>The {@code LIMIT(5) SCRATCH} attributes are documented but not
 *       enforced by this app; the COBOL JCL relies on z/OS catalog
 *       management to roll off old generations once the limit is
 *       exceeded. In the Java tree, generation roll-off (if needed)
 *       is the responsibility of the writer of the GDG &mdash; a
 *       follow-up effort flagged in {@code MIGRATION_NOTES.md}. The
 *       initial milestone preserves the COBOL behaviour of "directory
 *       exists, ready to receive generations".</li>
 *   <li>The IDCAMS idiom {@code IF LASTCC=12 THEN SET MAXCC=0} (i.e.
 *       "treat 'already exists' as success") is the natural semantic
 *       of {@code Files.createDirectories}, which is idempotent and
 *       does not throw {@link java.nio.file.FileAlreadyExistsException}
 *       on a pre-existing directory.</li>
 * </ul>
 *
 * <h2>Runtime configuration</h2>
 * <p>The base path under which the GDG directories are created is
 * read from the system property {@code carddemo.gdg.base.dir} or
 * the environment variable {@code CARDDEMO_GDG_BASE_DIR} (12-factor
 * configuration per AAP &sect;0.7.2). If neither is set, the working
 * directory is used. This matches the behaviour documented in
 * {@code application.properties.example}.
 *
 * <h2>Process-exit semantics</h2>
 * <p>The COBOL job emits return code {@code 0} on success (every
 * {@code DEFINE} either created the GDG or hit the {@code LASTCC=12}
 * "already exists" guard). This main mirrors that contract: any
 * {@link java.io.IOException} during directory creation results in
 * {@link System#exit(int)} with code {@code 12}, matching the IDCAMS
 * "error encountered" convention; success exits with code {@code 0}.
 *
 * <h2>Non-goals</h2>
 * <ul>
 *   <li>No Spring, no Lombok, no Apache Commons.</li>
 *   <li>No reflection.</li>
 *   <li>No {@code java.io.File} &mdash; {@link java.nio.file} only
 *       per AAP &sect;0.6.5.</li>
 *   <li>No preview features.</li>
 *   <li>No generation roll-off (LIMIT enforcement) &mdash; flagged in
 *       {@code MIGRATION_NOTES.md} as a follow-up effort.</li>
 * </ul>
 *
 * @see <a href="https://www.ibm.com/docs/en/zos/3.1.0?topic=services-generation-data-groups">z/OS Generation Data Groups</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "DEFGDGB",
        sourcePath = "app/jcl/DEFGDGB.jcl",
        translationDate = "2025-10-15",
        notes = "JCL translation of the IDCAMS DEFINE GENERATIONDATAGROUP step that "
                + "creates six GDG bases for the CardDemo batch tree (TRANSACT.BKUP, "
                + "TRANSACT.DALY, TRANREPT, TCATBALF.BKUP, SYSTRAN, TRANSACT.COMBINED). "
                + "In the file-based runtime each GDG base is a filesystem directory; "
                + "generation roll-off (LIMIT(5)) is a follow-up effort per MIGRATION_NOTES.md."
)
public final class DefineGdgApp {

    private static final Logger LOG = LoggerFactory.getLogger(DefineGdgApp.class);

    /**
     * System property key for the base directory under which the GDG
     * directories are created. Documented in
     * {@code application.properties.example} per AAP &sect;0.7.2.
     */
    public static final String SYSPROP_BASE_DIR = "carddemo.gdg.base.dir";

    /**
     * Environment variable name for the same setting (12-factor
     * configuration: env vars override file properties per the
     * sibling-app convention).
     */
    public static final String ENVVAR_BASE_DIR = "CARDDEMO_GDG_BASE_DIR";

    /**
     * The six GDG-base dataset names from
     * {@code app/jcl/DEFGDGB.jcl}. Order preserved from the JCL so
     * that any future feature toggling on order (e.g. an upstream
     * audit log diff) sees identical sequencing.
     */
    public static final java.util.List<String> GDG_BASE_NAMES =
            java.util.List.of(
                    "AWS.M2.CARDDEMO.TRANSACT.BKUP",
                    "AWS.M2.CARDDEMO.TRANSACT.DALY",
                    "AWS.M2.CARDDEMO.TRANREPT",
                    "AWS.M2.CARDDEMO.TCATBALF.BKUP",
                    "AWS.M2.CARDDEMO.SYSTRAN",
                    "AWS.M2.CARDDEMO.TRANSACT.COMBINED"
            );

    /** Successful IDCAMS-style return code (every define succeeded or was already present). */
    private static final int RC_OK = 0;

    /** IDCAMS "error" return code: an IOException occurred during directory creation. */
    private static final int RC_ERROR = 12;

    /**
     * Java main entry point. Mirrors the {@code STEP05 EXEC PGM=IDCAMS}
     * step of {@code DEFGDGB.jcl}: creates six GDG-base directories
     * idempotently. Exits with {@link #RC_OK} on success or
     * {@link #RC_ERROR} on the first {@link java.io.IOException}.
     *
     * @param args command-line arguments. The first positional
     *             argument, if present, overrides both the system
     *             property and the environment variable for the base
     *             directory (handy for ad-hoc testing without
     *             reconfiguring the environment).
     */
    public static void main(String[] args) {
        Path baseDir = resolveBaseDirectory(args);
        LOG.info("DEFGDGB: creating {} GDG base directories under {}",
                GDG_BASE_NAMES.size(), baseDir);
        try {
            int created = run(baseDir);
            LOG.info("DEFGDGB: {} directories created or confirmed under {} (rc={})",
                    created, baseDir, RC_OK);
            System.exit(RC_OK);
        } catch (java.io.IOException e) {
            LOG.error("DEFGDGB: failed to create one or more GDG base directories under {}: {}",
                    baseDir, e.getMessage(), e);
            System.exit(RC_ERROR);
        }
    }

    /**
     * Creates every directory in {@link #GDG_BASE_NAMES} under the
     * supplied {@code baseDir}. Idempotent: a directory that already
     * exists is logged at INFO and counted as a confirmed name (the
     * Java analog of IDCAMS' {@code IF LASTCC=12 THEN SET MAXCC=0}
     * "already exists is OK" idiom). Visible for testing.
     *
     * @param baseDir the base directory (absolute path); must not be
     *                {@code null}
     * @return the number of directories processed (always equals
     *         {@link #GDG_BASE_NAMES}.size()&nbsp;on success, since
     *         every name is either newly created or already present)
     * @throws java.io.IOException if any directory cannot be created
     *                             for a reason other than "already
     *                             exists"
     */
    static int run(Path baseDir) throws java.io.IOException {
        java.util.Objects.requireNonNull(baseDir, "baseDir must not be null");
        java.nio.file.Files.createDirectories(baseDir);
        int count = 0;
        for (String name : GDG_BASE_NAMES) {
            Path gdg = baseDir.resolve(name);
            if (java.nio.file.Files.isDirectory(gdg)) {
                LOG.info("DEFGDGB: GDG base already exists at {} (idempotent)", gdg);
            } else {
                java.nio.file.Files.createDirectories(gdg);
                LOG.info("DEFGDGB: created GDG base at {}", gdg);
            }
            count++;
        }
        return count;
    }

    /**
     * Resolves the base directory from (in order of precedence) the
     * first command-line argument, the system property
     * {@link #SYSPROP_BASE_DIR}, the environment variable
     * {@link #ENVVAR_BASE_DIR}, or the JVM working directory. Visible
     * for testing.
     *
     * @param args command-line arguments
     * @return the resolved absolute path
     */
    static Path resolveBaseDirectory(String[] args) {
        String explicit = (args != null && args.length > 0 && args[0] != null && !args[0].isEmpty())
                ? args[0]
                : null;
        if (explicit == null) {
            explicit = System.getProperty(SYSPROP_BASE_DIR);
        }
        if (explicit == null || explicit.isEmpty()) {
            explicit = System.getenv(ENVVAR_BASE_DIR);
        }
        if (explicit == null || explicit.isEmpty()) {
            return java.nio.file.Paths.get("").toAbsolutePath();
        }
        return java.nio.file.Paths.get(explicit).toAbsolutePath();
    }

    /**
     * Utility class &mdash; not constructible.
     */
    private DefineGdgApp() {
        throw new AssertionError("DefineGdgApp is not constructible");
    }
}
