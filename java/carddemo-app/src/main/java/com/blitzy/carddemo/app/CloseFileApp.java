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

// JEP 511 (finalized in Java 25): brings java.nio.file.Path and the
// java.lang/java.io types this main needs into scope.
import module java.base;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java translation of the {@code CLOSEFIL.jcl} job that, on the
 * mainframe, issues a {@code CEMT SET FIL(...) CLO} (close file)
 * command via SDSF for the five CICS-managed VSAM files:
 * {@code TRANSACT}, {@code CCXREF}, {@code ACCTDAT},
 * {@code CXACAIX}, and {@code USRSEC}.
 *
 * <h2>Source artifact</h2>
 * <p>{@code app/jcl/CLOSEFIL.jcl} step {@code CLCIFIL} (PGM=SDSF)
 * sends {@code /F CICSAWSA,'CEMT SET FIL(<name>) CLO'} for each of
 * the five file names listed above.
 *
 * <h2>Translation strategy &mdash; idiom-for-idiom (AAP &sect;0.7.1)</h2>
 * <p>The COBOL job is a CICS-region operational utility: it tells the
 * online CICS region that VSAM files should be closed (typically
 * before a batch job updates them, to release the CICS-held read
 * lock). In the file-based Java runtime there is no CICS region;
 * files are opened on demand and closed via try-with-resources by the
 * file adapter implementations, so the per-file "close" notification
 * is not required.
 *
 * <p>Per AAP &sect;0.4.1 ("IEFBR14 no-ops translated for
 * completeness") and the user-mandated milestone deliverable, this
 * main exists to preserve the JCL step inventory and provide a stable
 * extension point: if a downstream feature ever needs to perform
 * per-file post-close work (e.g.&nbsp;cache eviction, lock-file
 * cleanup, marker-file write), the logic goes here. The default
 * implementation is intentionally a verifying no-op: it logs each
 * file name and confirms that the configured path exists.
 *
 * <h2>File path resolution</h2>
 * <p>For each of the five names this main reads the configured file
 * path from the system property
 * {@code carddemo.file.<lowercase-name>.path} or the environment
 * variable {@code CARDDEMO_FILE_<UPPERCASE_NAME>_PATH}, matching the
 * resolution contract of {@link OpenFileApp}. If a path is configured
 * and the file exists, INFO is logged. If the path is configured but
 * the file is missing, WARN is logged (no error: a file may
 * legitimately not yet exist before the first write). Missing
 * configuration is logged at DEBUG and skipped.
 *
 * <h2>Process-exit semantics</h2>
 * <p>The COBOL JCL job exits with return code {@code 0} regardless of
 * individual CEMT command outcomes (SDSF reports errors but does not
 * fail the job). This main preserves that contract: exit code is
 * always {@code 0}.
 *
 * <h2>Non-goals (AAP &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring, Lombok, or Apache Commons.</li>
 *   <li>No reflection.</li>
 *   <li>No {@code java.io.File} &mdash; {@link java.nio.file} only
 *       per AAP &sect;0.6.5.</li>
 *   <li>No preview features.</li>
 *   <li>No actual CICS interaction &mdash; that infrastructure is
 *       out of scope for the file-based runtime per AAP &sect;0.2.2
 *       and &sect;0.6.12.</li>
 * </ul>
 *
 * @see OpenFileApp the symmetric "open" no-op
 * @since 1.0.0
 */
@CobolProgram(
        value = "CLOSEFIL",
        sourcePath = "app/jcl/CLOSEFIL.jcl",
        translationDate = "2025-10-15",
        notes = "JCL translation of the CICS file-close operational utility. On the "
                + "mainframe this job uses SDSF to issue CEMT SET FIL(...) CLO for "
                + "TRANSACT, CCXREF, ACCTDAT, CXACAIX, USRSEC. In the file-based Java "
                + "runtime there is no CICS region; files are closed via "
                + "try-with-resources by the file adapters, so this main is a verifying "
                + "no-op that logs each configured file name and confirms its filesystem "
                + "path exists. Preserved for JCL step inventory completeness per "
                + "AAP §0.4.1."
)
public final class CloseFileApp {

    private static final Logger LOG = LoggerFactory.getLogger(CloseFileApp.class);

    /**
     * The five CICS-managed file names from
     * {@code CLOSEFIL.jcl}, preserved in the original order. Matches
     * {@link OpenFileApp#CICS_FILE_NAMES} so a single configuration
     * surface drives both apps.
     */
    public static final java.util.List<String> CICS_FILE_NAMES =
            java.util.List.of("TRANSACT", "CCXREF", "ACCTDAT", "CXACAIX", "USRSEC");

    /** Successful exit code (always returned). */
    private static final int RC_OK = 0;

    /**
     * Java main entry point. Mirrors the {@code CLCIFIL EXEC PGM=SDSF}
     * step of {@code CLOSEFIL.jcl}: for each of the five CICS-managed
     * file names, verifies (or, if no path is configured, simply
     * acknowledges) the file. Always exits with {@link #RC_OK}.
     *
     * @param args command-line arguments (currently unused; the JCL
     *             has no parameters)
     */
    public static void main(String[] args) {
        LOG.info("CLOSEFIL: verifying configured paths for {} CICS-managed files",
                CICS_FILE_NAMES.size());
        run();
        LOG.info("CLOSEFIL: complete (rc={})", RC_OK);
        System.exit(RC_OK);
    }

    /**
     * Performs the verifying no-op for every name in
     * {@link #CICS_FILE_NAMES}. Visible for testing.
     */
    static void run() {
        for (String name : CICS_FILE_NAMES) {
            String configured = resolveFilePath(name);
            if (configured == null || configured.isEmpty()) {
                LOG.debug("CLOSEFIL: no path configured for {} (skipping verify)", name);
                continue;
            }
            java.nio.file.Path p = java.nio.file.Paths.get(configured);
            if (java.nio.file.Files.exists(p)) {
                LOG.info("CLOSEFIL: {} verified at {}", name, p);
            } else {
                LOG.warn("CLOSEFIL: {} configured at {} but the file does not exist "
                                + "(pre-write state is legitimate)",
                        name, p);
            }
        }
    }

    /**
     * Reads the configured filesystem path for a CICS file name.
     * Looks up the system property
     * {@code carddemo.file.<lowercase>.path} first, then the
     * environment variable {@code CARDDEMO_FILE_<UPPERCASE>_PATH}.
     * Returns {@code null} if neither is set. Matches the resolution
     * contract of {@link OpenFileApp#resolveFilePath(String)}.
     *
     * <p>Visible for testing.
     *
     * @param cicsFileName one of {@link #CICS_FILE_NAMES}
     * @return the configured path, or {@code null} if not configured
     */
    static String resolveFilePath(String cicsFileName) {
        String lower = cicsFileName.toLowerCase(java.util.Locale.ROOT);
        String upper = cicsFileName.toUpperCase(java.util.Locale.ROOT);
        String fromProp = System.getProperty("carddemo.file." + lower + ".path");
        if (fromProp != null && !fromProp.isEmpty()) {
            return fromProp;
        }
        return System.getenv("CARDDEMO_FILE_" + upper + "_PATH");
    }

    /** Utility class &mdash; not constructible. */
    private CloseFileApp() {
        throw new AssertionError("CloseFileApp is not constructible");
    }
}
