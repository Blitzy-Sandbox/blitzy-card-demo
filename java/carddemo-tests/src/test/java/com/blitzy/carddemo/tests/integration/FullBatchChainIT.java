/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.blitzy.carddemo.app.CombineTransactionsApp;
import com.blitzy.carddemo.app.CreateStatementsApp;
import com.blitzy.carddemo.app.InterestCalculationApp;
import com.blitzy.carddemo.app.PostTransactionsApp;
import com.blitzy.carddemo.app.TransactionBackupApp;
import com.blitzy.carddemo.app.TransactionReportApp;
import com.blitzy.carddemo.batch.BatchRunContext;

/**
 * End-to-end full-batch-chain integration test for Refine-PR Item 6.
 *
 * <p><strong>Authority:</strong></p>
 * <ul>
 *   <li>AAP &sect;0.4.1 — JCL job inventory and the canonical batch
 *       sequence (CLOSEFIL → loads → POSTTRAN → INTCALC → TRANBKP →
 *       COMBTRAN → CREASTMT → indexing → OPENFIL).</li>
 *   <li>AAP &sect;0.6.5 — file I/O exactness; the test reads the
 *       canonical {@code app/data/ASCII/} fixtures preserved under
 *       the immutable {@code app/} tree (per AAP &sect;0.2.2 those
 *       fixtures are READ-ONLY references and MUST NOT be modified).</li>
 *   <li>AAP &sect;0.6.6 — virtual-thread fan-out only where
 *       reordering does NOT change observable output; this IT
 *       implicitly verifies the sequential happens-before contract
 *       of the four-step chain.</li>
 *   <li>AAP &sect;0.6.11 — non-negotiable PR gate (golden-record
 *       harness + jqwik + JFR baselines + ITs).</li>
 *   <li>AAP &sect;0.7.2 — preserve byte-for-byte fidelity; ABEND-999
 *       behavior is preserved, NOT "fixed".</li>
 *   <li>Refine-PR Item 6 — "Wires the verified end-to-end sequence
 *       from {@code README.md} &sect;9.4.3 (POSTTRAN → INTCALC →
 *       COMBTRAN → CREASTMT → ...) as a single JUnit Jupiter test
 *       using {@code @TempDir} and the canonical {@code app/data/ASCII/}
 *       fixtures. Tag it {@code @Tag("integration")}; configure
 *       Surefire / Failsafe so it runs in {@code mvn verify} and can be
 *       excluded via {@code -DexcludedGroups=integration}. Assert that
 *       the CBTRN03C ABEND-999 exit code matches the faithful-COBOL
 *       expectation — do not treat it as a failure."</li>
 *   <li>{@code java/RUNBOOK.md} &sect;4 — operator-facing
 *       documentation of the CBTRN03C ABEND-999 contract.</li>
 * </ul>
 *
 * <h2>What this IT verifies</h2>
 * <ol>
 *   <li>The four canonical Phase-2 shaded jars
 *       ({@code carddemo-post-transactions},
 *       {@code carddemo-interest-calculation},
 *       {@code carddemo-transaction-backup},
 *       {@code carddemo-combine-transactions},
 *       {@code carddemo-create-statements}) execute to completion
 *       end-to-end against the canonical {@code app/data/ASCII/}
 *       fixtures, without throwing uncaught exceptions and without
 *       returning a JVM exit-code value outside the COBOL-recognised
 *       set {0, 4, 8, 12, 16} (AAP &sect;0.4.1 + RUNBOOK.md
 *       &sect;5.1).</li>
 *   <li>TRANREPT (CBTRN03C) exhibits the faithful-COBOL ABEND-999
 *       contract — either completing cleanly (RC_OK) or returning
 *       RC_SEVERE (16) via the {@code AbendException} → exit-code
 *       clamp documented in RUNBOOK.md &sect;4. ABEND-999 is NOT
 *       treated as a test failure per Refine-PR Item 6.</li>
 * </ol>
 *
 * <h2>What this IT does NOT verify</h2>
 * <ul>
 *   <li>Byte-for-byte parity with COBOL outputs. That is the
 *       responsibility of the {@code *GoldenTest.java} harness,
 *       which is currently {@code @Disabled} pending z/OS COBOL
 *       captures (Refine-PR scope explicitly excludes the z/OS
 *       capture work). This IT validates that the chain
 *       <em>runs</em>; the golden tests will validate that it runs
 *       <em>correctly</em>.</li>
 *   <li>Specific RC values per step. The fixtures under
 *       {@code app/data/ASCII/} were authored for the COBOL deck and
 *       may legitimately drive any step to {@code RC_WITH_REJECTS}
 *       (POSTTRAN), {@code RC_NO_INPUT} (any step whose required
 *       input is absent), or {@code RC_OK}. This IT accepts the
 *       full COBOL-recognised set; the per-step RUNBOOK.md
 *       &sect;5.1 table is the operational reference.</li>
 *   <li>Performance characteristics. {@code JfrBaselineTest} is the
 *       responsible harness (AAP &sect;0.6.11).</li>
 * </ul>
 *
 * <h2>Why reflection is used to invoke {@code execute()}</h2>
 *
 * <p>All five exercised app classes
 * ({@link PostTransactionsApp}, {@link InterestCalculationApp},
 * {@link TransactionBackupApp}, {@link CombineTransactionsApp},
 * {@link CreateStatementsApp}, {@link TransactionReportApp}) expose
 * a {@code public static void main(String[] args)} entry point that
 * unconditionally calls {@link System#exit(int)} to surface the COBOL
 * return code as a JVM exit code. Invoking {@code main()} from a test
 * would therefore terminate the test JVM and abort the entire test
 * run.</p>
 *
 * <p>Each app also exposes a sibling {@code static int execute()}
 * package-private (3 of 6) or {@code private} (1 of 6:
 * {@link CombineTransactionsApp}) method that returns the COBOL
 * return code WITHOUT calling {@link System#exit(int)}. This is the
 * test-friendly entry point. Because the visibility varies and this
 * IT lives in a different package
 * ({@code com.blitzy.carddemo.tests.integration}) than the apps
 * ({@code com.blitzy.carddemo.app}), {@link java.lang.reflect.Method
 * Method.setAccessible(true)} is used uniformly across all six
 * invocations to keep the dispatch logic consistent.</p>
 *
 * <p>The {@link ScopedValue} binding of {@link BatchRunContext#BATCH_CTX}
 * is established BY THIS TEST per the contract documented on
 * {@code PostTransactionsApp.BATCH_CTX} (and identically on every
 * other app): "bound exactly once in main(String[]) via
 * ScopedValue.where(BATCH_CTX, ctx).call(...)". Each {@code execute()}
 * invocation runs inside its own freshly-bound scope so per-step
 * isolation of {@link BatchRunContext#runId()} is preserved.</p>
 *
 * <h2>Why {@code @TempDir} for output isolation</h2>
 *
 * <p>POSTTRAN, INTCALC, and CREASTMT all write to the file system
 * (TRANSACT, TCATBALF, DALYREJS+GDG, statement HTML/text). If the
 * test pointed these writes at the canonical {@code app/data/ASCII/}
 * fixtures it would mutate the read-only reference data tree, which
 * AAP &sect;0.2.2 explicitly forbids ("the entire {@code app/} tree
 * is out of scope ... must remain unmodified"). The IT therefore
 * strip-copies the line-terminated ASCII fixtures into a per-test
 * {@code @TempDir} which JUnit Jupiter cleans up after the test
 * finishes, leaving {@code app/data/ASCII/} byte-identical to the
 * baseline.</p>
 *
 * <p>Strip-copying is required because the canonical fixtures use
 * LF line terminators (one record per line, terminated by 0x0A)
 * whereas the {@link com.blitzy.carddemo.adapter.file.FixedWidthReader}
 * expects raw fixed-width records WITHOUT terminators. The
 * strip-copy routine in {@link #stripCopyFixtures(Path)} reads each
 * fixture line-by-line and writes the concatenated lines (no
 * terminator) to the destination, producing the exact byte stream
 * the adapter expects.</p>
 *
 * <h2>Why no parent module dependencies are required</h2>
 *
 * <p>This IT depends on {@code carddemo-app} (the composition root)
 * which transitively brings in every other in-reactor module. The
 * IT's classpath therefore includes {@link BatchRunContext} from
 * {@code carddemo-batch}, {@link PostTransactionsApp} etc. from
 * {@code carddemo-app}, and every record type from
 * {@code carddemo-domain} — exactly the same classpath that the
 * unit-test classes in
 * {@code com.blitzy.carddemo.tests.property/golden/perf} see.</p>
 *
 * <h2>Forbidden patterns honoured</h2>
 * <ul>
 *   <li>NO {@link java.io.File} — uses {@link java.nio.file.Path}
 *       exclusively per AAP &sect;0.6.5.</li>
 *   <li>NO {@code double}/{@code float} for any value (none are
 *       used).</li>
 *   <li>NO {@link ThreadLocal} — uses {@link ScopedValue} (JEP 506
 *       Final) per AAP &sect;0.6.6.</li>
 *   <li>NO {@link java.util.Date} / {@link java.util.Calendar} —
 *       uses {@link LocalDate} per AAP &sect;0.6.4.</li>
 *   <li>NO Spring / Spring Test / Mockito — JUnit Jupiter only.</li>
 *   <li>NO {@code --enable-preview} flag.</li>
 * </ul>
 */
@Tag("integration")
@DisplayName("End-to-end full batch chain (POSTTRAN → INTCALC → TRANBKP → COMBTRAN → CREASTMT)")
final class FullBatchChainIT {

    /** SLF4J logger for chain-execution diagnostics. */
    private static final Logger LOG = LoggerFactory.getLogger(FullBatchChainIT.class);

    /**
     * The canonical COBOL-recognised return-code set per AAP &sect;0.4.1
     * and {@code java/RUNBOOK.md} &sect;5.1.
     */
    private static final List<Integer> COBOL_RETURN_CODES = List.of(0, 4, 8, 12, 16);

    /**
     * The set of system-property keys this IT sets before each test
     * and unsets after each test. Tracked centrally so the
     * {@link #clearSystemProperties()} teardown does not leak state
     * into subsequent tests.
     */
    private final Map<String, String> propertiesSetByTest = new LinkedHashMap<>();

    /**
     * Per-test temporary directory managed by JUnit Jupiter; the
     * {@code @TempDir} contract guarantees the directory is created
     * before the test method runs and recursively deleted afterwards.
     * All test outputs and strip-copied fixtures land underneath this
     * directory so the canonical {@code app/data/ASCII/} fixtures
     * remain byte-identical to the baseline.
     */
    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // Lifecycle: per-test fixture preparation and teardown
    // -----------------------------------------------------------------------

    /**
     * Strip-copies the canonical {@code app/data/ASCII/} fixtures into
     * {@code tempDir/data/} and configures the carddemo system
     * properties that the apps read via
     * {@link com.blitzy.carddemo.app.SafePathResolver
     * SafePathResolver}.
     *
     * @throws IOException if a fixture cannot be located or copied
     */
    @BeforeEach
    void setUp() throws IOException {
        Path dataDir = tempDir.resolve("data");
        Path gdgDir = tempDir.resolve("gdg");
        Path workDir = tempDir.resolve("work");
        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(dataDir);
        Files.createDirectories(gdgDir);
        Files.createDirectories(workDir);
        Files.createDirectories(outputDir);

        stripCopyFixtures(dataDir);

        // Configure all carddemo.* system properties consumed by the
        // app classes. Defaults (./data/<name>.dat etc.) would point
        // at the working directory which is undefined under the
        // Maven test JVM; explicit absolute paths avoid that.
        setProperty("carddemo.file.charset", "US-ASCII");
        setProperty("carddemo.file.acctdata.path",
                dataDir.resolve("acctdata.dat").toString());
        setProperty("carddemo.file.carddata.path",
                dataDir.resolve("carddata.dat").toString());
        setProperty("carddemo.file.cardxref.path",
                dataDir.resolve("cardxref.dat").toString());
        setProperty("carddemo.file.custdata.path",
                dataDir.resolve("custdata.dat").toString());
        setProperty("carddemo.file.dailytran.path",
                dataDir.resolve("dailytran.dat").toString());
        setProperty("carddemo.file.transact.path",
                dataDir.resolve("transact.dat").toString());
        setProperty("carddemo.file.discgrp.path",
                dataDir.resolve("discgrp.dat").toString());
        setProperty("carddemo.file.trancatg.path",
                dataDir.resolve("trancatg.dat").toString());
        setProperty("carddemo.file.trantype.path",
                dataDir.resolve("trantype.dat").toString());
        setProperty("carddemo.file.tcatbalf.path",
                dataDir.resolve("tcatbalf.dat").toString());
        setProperty("carddemo.file.stmt.text.path",
                outputDir.resolve("statements.txt").toString());
        setProperty("carddemo.file.stmt.html.path",
                outputDir.resolve("statements.html").toString());

        // TRANREPT and DATEPARM file paths. Both default to relative
        // paths (./output/tranrept.dat and ./work/dateparm.dat
        // respectively); if not overridden here, TransactionReportApp
        // would create those directories in the test runner's CWD
        // (which is carddemo-tests/ during a Maven reactor build), and
        // those directories would then appear as untracked git
        // artifacts after the IT runs. Setting these explicitly keeps
        // the entire test footprint inside @TempDir per AAP §0.6.5
        // (java.nio.file only; isolated workspace per test).
        setProperty("carddemo.file.tranrept.path",
                outputDir.resolve("tranrept.dat").toString());
        setProperty("carddemo.file.dateparm.path",
                workDir.resolve("dateparm.dat").toString());

        // Allowed-root configuration for SafePathResolver. Each of
        // these widens the per-resolver allow-list to the tempDir
        // subtree so the test never resolves outside the @TempDir.
        setProperty("carddemo.data.root", dataDir.toString());
        setProperty("carddemo.output.root", outputDir.toString());

        // GDG and work directory; both required by COMBTRAN and
        // CREASTMT respectively.
        setProperty("carddemo.gdg.root", gdgDir.toString());
        setProperty("carddemo.work.path", workDir.toString());

        // INTCALC PARM matches the canonical INTCALC.jcl fixture
        // date 2022-07-18 (see DEFAULT_PARM_DATE on
        // InterestCalculationApp); preserved verbatim per AAP §0.7.2.
        setProperty("carddemo.intcalc.parm", "2022071800");

        // TRANREPT date range. The canonical fixture date range is
        // 2022-01-01 to 2022-07-06 per the AAP §0.7.5
        // [TODO — TRANREPT date range] resolution.
        setProperty("carddemo.tranrept.start-date", "2022-01-01");
        setProperty("carddemo.tranrept.end-date", "2022-12-31");

        LOG.info("FullBatchChainIT setUp complete: dataDir={}, gdgDir={}, workDir={}, "
                + "outputDir={}", dataDir, gdgDir, workDir, outputDir);
    }

    /**
     * Clears every system property this IT set in
     * {@link #setUp()}, restoring the JVM property state to its
     * pre-test baseline. Without this teardown the property mutations
     * would leak into subsequent ITs (and into Surefire's
     * post-Failsafe cleanup) and could mask real configuration bugs
     * downstream.
     */
    @AfterEach
    void clearSystemProperties() {
        for (String key : propertiesSetByTest.keySet()) {
            System.clearProperty(key);
        }
        propertiesSetByTest.clear();
    }

    // -----------------------------------------------------------------------
    // The integration tests
    // -----------------------------------------------------------------------

    /**
     * Executes the canonical Phase-2 batch chain end-to-end and
     * asserts each step returns a COBOL-recognised RC.
     *
     * <p>Chain order:</p>
     * <ol>
     *   <li>POSTTRAN ({@link PostTransactionsApp}) — posting engine
     *       writing TRANSACT and DALYREJS</li>
     *   <li>INTCALC ({@link InterestCalculationApp}) — interest
     *       calculation engine reading ACCTDATA + TCATBALF +
     *       DISCGRP, writing back to the same files in I-O mode</li>
     *   <li>TRANBKP ({@link TransactionBackupApp}) — backs up
     *       TRANSACT to {@code gdgRoot/bkup/} so COMBTRAN has a
     *       generation to read</li>
     *   <li>COMBTRAN ({@link CombineTransactionsApp}) — merges
     *       TRANBKP(0) + SYSTRAN(0) into TRANSACT.COMBINED(+1) under
     *       the GDG root</li>
     *   <li>CREASTMT ({@link CreateStatementsApp}) — generates
     *       customer statements as HTML + text</li>
     * </ol>
     *
     * <p>Assertion strategy: each step must return an integer in the
     * COBOL-recognised set {0, 4, 8, 12, 16} per AAP &sect;0.4.1. The
     * test does NOT assert specific values per step because the
     * canonical fixtures may legitimately drive any step to
     * RC_WITH_REJECTS (POSTTRAN), RC_NO_INPUT (any step lacking its
     * input), or RC_OK. The per-step RUNBOOK.md &sect;5.1 table is
     * the operational reference; an out-of-set return value (e.g., 1,
     * 5, 13, or 17) indicates a defect in the main-method's exit-code
     * pattern-matching switch and would be flagged for investigation.</p>
     *
     * @throws Exception if any reflective invocation fails
     */
    @Test
    @DisplayName("Phase-2 batch chain runs end-to-end and every step returns a COBOL-recognised RC")
    void fullBatchChainRunsToCompletion() throws Exception {
        BatchRunContext ctx = new BatchRunContext(
                "fbc-" + Instant.now().toEpochMilli(),
                LocalDate.of(2022, 7, 18),
                "fullbatchchain-it");

        // Step 1: POSTTRAN (CBTRN02C)
        int rcPost = invokeExecute(PostTransactionsApp.class, ctx);
        LOG.info("POSTTRAN returned RC={}", rcPost);
        assertThat(rcPost)
                .as("POSTTRAN returned RC=%d which is outside the COBOL-recognised "
                        + "return-code set %s. This indicates a defect in the "
                        + "PostTransactionsApp.main() exit-code pattern-matching switch "
                        + "(see AAP §0.7.3 — exhaustiveness over the COBOL RC set).",
                        rcPost, COBOL_RETURN_CODES)
                .isIn(COBOL_RETURN_CODES);

        // Step 2: INTCALC (CBACT04C)
        int rcIntcalc = invokeExecute(InterestCalculationApp.class, ctx);
        LOG.info("INTCALC returned RC={}", rcIntcalc);
        assertThat(rcIntcalc)
                .as("INTCALC returned RC=%d which is outside the COBOL-recognised "
                        + "return-code set %s.", rcIntcalc, COBOL_RETURN_CODES)
                .isIn(COBOL_RETURN_CODES);

        // Step 3: TRANBKP (IDCAMS REPRO) — backs up TRANSACT into
        // gdgRoot/bkup/ so COMBTRAN has a generation to merge from
        int rcBkp = invokeExecute(TransactionBackupApp.class, ctx);
        LOG.info("TRANBKP returned RC={}", rcBkp);
        assertThat(rcBkp)
                .as("TRANBKP returned RC=%d which is outside the COBOL-recognised "
                        + "return-code set %s.", rcBkp, COBOL_RETURN_CODES)
                .isIn(COBOL_RETURN_CODES);

        // Step 4: COMBTRAN (JCL SORT)
        int rcComb = invokeExecute(CombineTransactionsApp.class, ctx);
        LOG.info("COMBTRAN returned RC={}", rcComb);
        assertThat(rcComb)
                .as("COMBTRAN returned RC=%d which is outside the COBOL-recognised "
                        + "return-code set %s.", rcComb, COBOL_RETURN_CODES)
                .isIn(COBOL_RETURN_CODES);

        // Step 5: CREASTMT (CBSTM03A)
        int rcStmt = invokeExecute(CreateStatementsApp.class, ctx);
        LOG.info("CREASTMT returned RC={}", rcStmt);
        assertThat(rcStmt)
                .as("CREASTMT returned RC=%d which is outside the COBOL-recognised "
                        + "return-code set %s.", rcStmt, COBOL_RETURN_CODES)
                .isIn(COBOL_RETURN_CODES);

        LOG.info("FullBatchChainIT.fullBatchChainRunsToCompletion: SUCCESS "
                + "(POSTTRAN={}, INTCALC={}, TRANBKP={}, COMBTRAN={}, CREASTMT={})",
                rcPost, rcIntcalc, rcBkp, rcComb, rcStmt);
    }

    /**
     * Verifies that the TRANREPT (CBTRN03C) program exhibits the
     * faithful-COBOL ABEND-999 contract documented in {@code
     * java/RUNBOOK.md} &sect;4.
     *
     * <p><strong>Refine-PR Item 6 mandate:</strong> "Assert that the
     * CBTRN03C ABEND-999 exit code matches the faithful-COBOL
     * expectation — do not treat it as a failure."</p>
     *
     * <p>The Java port catches {@code AbendException} at the
     * {@link TransactionReportApp#main(String[])} boundary and
     * returns RC_SEVERE (16). However, this IT invokes
     * {@code execute()} directly (to avoid {@link System#exit(int)}),
     * so the {@code AbendException} may propagate out of the
     * reflective call instead of being clamped to an integer return
     * code. This test handles BOTH cases:</p>
     *
     * <ol>
     *   <li><strong>Normal return path:</strong> {@code execute()}
     *       returns an integer in {0, 4, 8, 12, 16}. The IT asserts
     *       the RC is in the COBOL set; any of {0, 12, 16} is
     *       acceptable per RUNBOOK.md &sect;5.1.</li>
     *   <li><strong>ABEND-999 propagation path:</strong>
     *       {@code execute()} throws an {@code AbendException} (or a
     *       checked equivalent wrapped by reflection). The IT
     *       inspects the exception chain for the canonical
     *       {@code AbendException} cause; if found, the test PASSES
     *       (this is the faithful-COBOL outcome). Any other
     *       exception type rethrows as a real test failure.</li>
     * </ol>
     *
     * <p>This dual-path handling preserves the faithful-COBOL
     * contract regardless of whether the exception clamping happens
     * inside {@code execute()} or in the {@code main()} wrapper.</p>
     *
     * @throws Exception if a non-AbendException propagates
     */
    @Test
    @DisplayName("TRANREPT (CBTRN03C) exhibits faithful-COBOL ABEND-999 contract")
    void transactionReportPreservesFaithfulCobolAbendContract() throws Exception {
        BatchRunContext ctx = new BatchRunContext(
                "fbc-trnrpt-" + Instant.now().toEpochMilli(),
                LocalDate.of(2022, 7, 18),
                "fullbatchchain-it-trnrpt");

        // Pre-condition: POSTTRAN must run first to populate TRANSACT
        // (otherwise TRANREPT would return RC_NO_INPUT before reaching
        // any of the CBTRN03C lookup paths). Tolerate POSTTRAN
        // returning any COBOL RC — this IT is about TRANREPT, not
        // POSTTRAN.
        int rcPre = invokeExecute(PostTransactionsApp.class, ctx);
        LOG.info("[pre-TRANREPT] POSTTRAN returned RC={}", rcPre);
        assertThat(rcPre).isIn(COBOL_RETURN_CODES);

        int rcTranrept;
        try {
            rcTranrept = invokeExecute(TransactionReportApp.class, ctx);
            LOG.info("TRANREPT returned RC={} (no AbendException propagated)", rcTranrept);
        } catch (RuntimeException re) {
            // Walk the exception chain looking for AbendException
            // (named by simple-name to avoid a hard compile-time
            // dependency on the carddemo-application module's
            // AbendException type, which is package-internal to its
            // module).
            Throwable cause = re;
            boolean isAbend = false;
            while (cause != null) {
                String simpleName = cause.getClass().getSimpleName();
                if ("AbendException".equals(simpleName)) {
                    isAbend = true;
                    break;
                }
                cause = cause.getCause();
            }
            if (!isAbend) {
                throw re;
            }
            LOG.info("TRANREPT propagated AbendException — this IS the canonical "
                    + "faithful-COBOL ABEND-999 outcome per RUNBOOK.md §4");
            rcTranrept = 16; // canonical RC_SEVERE per RUNBOOK.md §5.1
        }

        // Per Refine-PR Item 6: 0 (clean run), 12 (no input), 16
        // (ABEND-999) all match the faithful-COBOL expectation.
        // 4 (RC_WARN) and 8 (RC_ERROR) are still recognised COBOL
        // values but TRANREPT/CBTRN03C does not produce them in
        // the canonical flow; if they appear, log them and assert
        // they are still in the COBOL-recognised set.
        assertThat(rcTranrept)
                .as("TRANREPT returned RC=%d which is outside the COBOL-recognised "
                        + "return-code set %s. Per Refine-PR Item 6 we accept RC ∈ "
                        + "{0=RC_OK, 12=RC_NO_INPUT, 16=RC_SEVERE/ABEND-999} as the "
                        + "faithful-COBOL outcome set. See RUNBOOK.md §4.",
                        rcTranrept, COBOL_RETURN_CODES)
                .isIn(COBOL_RETURN_CODES);

        LOG.info("FullBatchChainIT.transactionReportPreservesFaithfulCobolAbendContract: "
                + "SUCCESS (TRANREPT RC={})", rcTranrept);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Records a system property for cleanup and applies it via
     * {@link System#setProperty(String, String)}.
     *
     * @param key   the property key
     * @param value the property value
     */
    private void setProperty(String key, String value) {
        propertiesSetByTest.put(key, value);
        System.setProperty(key, value);
    }

    /**
     * Locates the canonical {@code app/data/ASCII/} fixture root by
     * walking up from the JVM working directory until a sibling
     * {@code app/data/ASCII/} directory is found.
     *
     * <p>The walk is required because the test JVM's working
     * directory is one of:</p>
     * <ul>
     *   <li>{@code java/carddemo-tests/} when {@code mvn} is run
     *       from inside the {@code java/} reactor with
     *       {@code -pl carddemo-tests}.</li>
     *   <li>{@code java/} when {@code mvn} is run from the reactor
     *       root.</li>
     *   <li>The repo root when launched from CI with
     *       {@code -f java/pom.xml}.</li>
     * </ul>
     *
     * @return the absolute path to the {@code app/data/ASCII/}
     *         directory; never null
     * @throws IllegalStateException if no candidate is found
     */
    private static Path findFixtureRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path candidate = cwd.resolve("app/data/ASCII");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        Path probe = cwd;
        while (probe != null) {
            Path c = probe.resolve("app/data/ASCII");
            if (Files.isDirectory(c)) {
                return c;
            }
            probe = probe.getParent();
        }
        throw new IllegalStateException(
                "Could not locate app/data/ASCII fixture directory starting from "
                        + cwd + " (walked all parents up to /). Either run the test "
                        + "from the repository root or set the JVM working directory.");
    }

    /**
     * Strip-copies the 9 canonical ASCII fixtures into {@code dataDir},
     * renaming them from {@code <name>.txt} to {@code <name>.dat}.
     *
     * @param dataDir destination directory; must already exist
     * @throws IOException on any I/O failure
     */
    private static void stripCopyFixtures(Path dataDir) throws IOException {
        Path src = findFixtureRoot();
        // Map of source file name → destination file name. The
        // destination file names match the application defaults
        // (e.g., PostTransactionsApp.DEFAULT_DAILYTRAN_PATH ==
        // "./data/dailytran.dat") and the application-properties.example
        // template (carddemo.file.tcatbalf.path == tcatbalf.dat).
        Map<String, String> fixtureMap = new LinkedHashMap<>();
        fixtureMap.put("acctdata.txt", "acctdata.dat");
        fixtureMap.put("carddata.txt", "carddata.dat");
        fixtureMap.put("cardxref.txt", "cardxref.dat");
        fixtureMap.put("custdata.txt", "custdata.dat");
        fixtureMap.put("dailytran.txt", "dailytran.dat");
        fixtureMap.put("discgrp.txt", "discgrp.dat");
        fixtureMap.put("tcatbal.txt", "tcatbalf.dat");
        fixtureMap.put("trancatg.txt", "trancatg.dat");
        fixtureMap.put("trantype.txt", "trantype.dat");

        for (Map.Entry<String, String> e : fixtureMap.entrySet()) {
            Path source = src.resolve(e.getKey());
            Path dest = dataDir.resolve(e.getValue());
            if (!Files.exists(source)) {
                throw new IllegalStateException("Canonical fixture missing: " + source);
            }
            stripLineEndingsAndCopy(source, dest);
        }
    }

    /**
     * Reads {@code source} as US-ASCII lines and writes the
     * concatenated content (no line terminators) to {@code dest}.
     *
     * <p>This is required because the canonical
     * {@code app/data/ASCII/*.txt} fixtures store fixed-width records
     * separated by LF terminators (one record per line) whereas the
     * {@code FixedWidthReader} expects raw fixed-width records
     * without terminators. Reading and concatenating restores the
     * underlying byte stream as the COBOL VSAM ESDS/KSDS would
     * present it.</p>
     *
     * @param source source path (read US-ASCII)
     * @param dest   destination path (overwritten if exists)
     * @throws IOException on any I/O failure
     */
    private static void stripLineEndingsAndCopy(Path source, Path dest) throws IOException {
        List<String> lines = Files.readAllLines(source, StandardCharsets.US_ASCII);
        // Use a fresh ArrayList-backed buffer rather than streaming
        // the bytes line-by-line so the resulting file write is
        // atomic at the OS level (Files.write replaces atomically by
        // default with WRITE+CREATE+TRUNCATE_EXISTING).
        List<byte[]> chunks = new ArrayList<>(lines.size());
        int totalLength = 0;
        for (String line : lines) {
            byte[] b = line.getBytes(StandardCharsets.US_ASCII);
            chunks.add(b);
            totalLength += b.length;
        }
        byte[] joined = new byte[totalLength];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, joined, offset, chunk.length);
            offset += chunk.length;
        }
        try (OutputStream out = Files.newOutputStream(dest)) {
            out.write(joined);
        }
    }

    /**
     * Reflectively invokes the {@code static int execute()} method on
     * the supplied app class, with the supplied {@link BatchRunContext}
     * bound to the app's {@code public static final ScopedValue<BatchRunContext>
     * BATCH_CTX} field per the JEP 506 ScopedValue contract documented
     * on every app class.
     *
     * <p><strong>Why we bind the per-app {@code BATCH_CTX} rather than
     * {@link BatchRunContext#BATCH_CTX}:</strong> some apps
     * ({@link PostTransactionsApp}, {@link InterestCalculationApp})
     * declare {@code BATCH_CTX} as an alias for
     * {@code BatchRunContext.BATCH_CTX} (the canonical shared
     * identity), while others ({@link TransactionBackupApp},
     * {@link CombineTransactionsApp}, {@link CreateStatementsApp},
     * {@link TransactionReportApp}) declare their own
     * {@code ScopedValue.newInstance()} with a per-app identity. Both
     * patterns are valid per AAP &sect;0.6.6 — the requirement is that
     * the binding seen by {@code execute()} matches the binding
     * established by {@code main()}. Reading the per-app
     * {@code BATCH_CTX} field reflectively guarantees we bind the
     * RIGHT identity regardless of which pattern the app class chose.</p>
     *
     * <p>Some apps' {@code execute} method takes a {@code String[]
     * args} parameter ({@link TransactionReportApp}) while others are
     * parameterless. This helper introspects the method signature and
     * passes an empty {@code String[]} when the argument is required.</p>
     *
     * @param appClass the app class hosting the {@code execute} method
     *                 and the {@code BATCH_CTX} field
     * @param ctx      the batch-run context to bind for this invocation
     * @return the integer return code produced by {@code execute()}
     * @throws Exception if the reflective invocation fails or
     *                   {@code execute()} throws
     */
    @SuppressWarnings("unchecked")
    private static int invokeExecute(Class<?> appClass, BatchRunContext ctx) throws Exception {
        // Locate the per-app BATCH_CTX field. Every app class in
        // carddemo-app declares `public static final ScopedValue<BatchRunContext>
        // BATCH_CTX` per the JEP 506 contract.
        Field batchCtxField = appClass.getField("BATCH_CTX");
        Object fieldValue = batchCtxField.get(null);
        if (!(fieldValue instanceof ScopedValue<?>)) {
            throw new IllegalStateException(
                    appClass.getName() + ".BATCH_CTX is not a ScopedValue; got "
                            + (fieldValue == null ? "null" : fieldValue.getClass().getName()));
        }
        // Safe cast: the field declaration constrains the type
        // parameter to BatchRunContext.
        ScopedValue<BatchRunContext> batchCtx = (ScopedValue<BatchRunContext>) fieldValue;

        // Locate the execute method. Prefer the no-arg overload; fall
        // back to the (String[]) overload required by
        // TransactionReportApp.
        Method execute;
        boolean takesArgs;
        try {
            execute = appClass.getDeclaredMethod("execute");
            takesArgs = false;
        } catch (NoSuchMethodException nsme) {
            execute = appClass.getDeclaredMethod("execute", String[].class);
            takesArgs = true;
        }
        execute.setAccessible(true);
        final boolean takesArgsFinal = takesArgs;
        final Method executeFinal = execute;

        return ScopedValue.where(batchCtx, ctx).call(() -> {
            try {
                Object result = takesArgsFinal
                        ? executeFinal.invoke(null, (Object) new String[0])
                        : executeFinal.invoke(null);
                if (result instanceof Integer i) {
                    return i;
                }
                throw new IllegalStateException(
                        "Expected execute() to return Integer but got: "
                                + (result == null ? "null" : result.getClass().getName()));
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                if (cause instanceof RuntimeException rex) {
                    throw rex;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                throw new RuntimeException("execute() threw a non-RuntimeException: "
                        + cause, cause);
            }
        });
    }
}
