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
// exported by the java.base module — used here for java.nio.file.{Path, Files},
// java.io.IOException, java.util.Locale, and java.lang.ScopedValue (which
// moved from java.util.concurrent to java.lang when JEP 506 was finalized in
// Java 25; see java.base/java/lang/ScopedValue.java).
import module java.base;

import com.blitzy.carddemo.adapter.file.FileAccountRepository;
import com.blitzy.carddemo.adapter.file.FileCardXrefRepository;
import com.blitzy.carddemo.adapter.file.FileDailyTransactionRepository;
import com.blitzy.carddemo.adapter.file.FileTransactionCategoryBalanceRepository;
import com.blitzy.carddemo.adapter.file.FileTransactionRepository;
import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.application.transaction.CbTrn02C;
import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/POSTTRAN.jcl}
 * &mdash; the daily transaction posting job that invokes the COBOL CBTRN02C
 * posting engine (the most critical batch program in CardDemo per AAP
 * &sect;0.6.11).
 *
 * <h2>Source artefact</h2>
 * <p>Original JCL ({@code app/jcl/POSTTRAN.jcl}) chains the
 * {@code STEP1 EXEC PGM=CBTRN02C} step with the following DD allocations:
 * <ul>
 *   <li>{@code DALYTRAN} &mdash; sequential input (PS, LRECL=350)</li>
 *   <li>{@code TRANSACT} &mdash; VSAM KSDS, posted output</li>
 *   <li>{@code XREFFILE} &mdash; VSAM KSDS, card-account cross-reference (read-only)</li>
 *   <li>{@code ACCTFILE} &mdash; VSAM KSDS, account master (read+update)</li>
 *   <li>{@code TCATBALF} &mdash; VSAM KSDS, per-category balance (read+upsert)</li>
 *   <li>{@code DALYREJS} &mdash; sequential output (PS, LRECL=430) for reject records</li>
 * </ul>
 *
 * <h2>Translation strategy &mdash; idiom-for-idiom (AAP &sect;0.7.1)</h2>
 * <p>The {@code STEP1 EXEC PGM=CBTRN02C} step is realised as plain Java
 * factory wiring of five {@link com.blitzy.carddemo.domain.port file-backed
 * repository adapters} into the
 * {@link com.blitzy.carddemo.application.transaction.CbTrn02C CbTrn02C}
 * posting use case &mdash; no Spring container is involved (per AAP
 * &sect;0.7.4 the COBOL system has no DI container, so none is introduced).
 * The {@code DALYREJS} reject append flow is wired through the
 * {@link FileDailyTransactionRepository#appendReject} 430-byte format
 * (350-byte DALYTRAN body + 4-byte PIC 9(04) reason + 76-byte PIC X(76)
 * description) per AAP &sect;0.6.9 and the JCL
 * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} clause.
 *
 * <h2>Runtime configuration (AAP &sect;0.7.2)</h2>
 * <p>Each file path is resolved via {@link #getProp(String, String)} from
 * either an environment variable (uppercased, dots&rarr;underscores) or a
 * JVM system property:
 * <ul>
 *   <li>{@code carddemo.file.dailytran.path} &rarr; {@link FileDailyTransactionRepository}</li>
 *   <li>{@code carddemo.file.dalyrejs.path} &rarr; {@link FileDailyTransactionRepository#rejectFile()}</li>
 *   <li>{@code carddemo.file.transact.path} &rarr; {@link FileTransactionRepository}</li>
 *   <li>{@code carddemo.file.cardxref.path} &rarr; {@link FileCardXrefRepository}</li>
 *   <li>{@code carddemo.file.acctdata.path} &rarr; {@link FileAccountRepository}</li>
 *   <li>{@code carddemo.file.tcatbalf.path} &rarr; {@link FileTransactionCategoryBalanceRepository}</li>
 * </ul>
 * Defaults match the {@code application.properties.example} template.
 *
 * <h2>BatchRunContext / ScopedValue (AAP &sect;0.6.6, JEP 506 Final)</h2>
 * <p>The {@link BatchRunContext} is resolved via
 * {@link BatchRunContext#fromEnvironment()} and bound to {@link #BATCH_CTX}
 * for the duration of {@link #execute()}. <strong>No {@link ThreadLocal}
 * is used anywhere</strong>, per AAP &sect;0.7.4.
 *
 * <h2>Process-exit semantics</h2>
 * <p>Return codes:
 * <ul>
 *   <li>{@link #RC_OK} ({@code 0}) on a clean posting run with zero rejects</li>
 *   <li>{@link #RC_WITH_REJECTS} ({@code 4}) when the COBOL engine reports
 *       at least one reject record via
 *       {@link com.blitzy.carddemo.application.transaction.CbTrn02C#returnCode()}
 *       &mdash; this matches the COBOL
 *       {@code MOVE 4 TO RETURN-CODE} idiom on the EOJ branch when
 *       {@code WS-COUNTER-RECS-REJECTED &gt; 0}.</li>
 *   <li>{@link #RC_ERROR} ({@code 16}) on any uncaught failure &mdash; this
 *       includes an unchecked {@link RuntimeException} thrown by the
 *       repositories, the use case, or the
 *       {@link com.blitzy.carddemo.application.AbendException} raised by
 *       {@code abendProgram()} inside CbTrn02C.</li>
 * </ul>
 * The exit-code computation uses an exhaustive pattern-matching switch
 * over {@link Integer} (Java 21 Final) with <strong>no {@code default}
 * branch</strong> per AAP &sect;0.7.3.
 *
 * @see CbTrn02C
 * @see FileDailyTransactionRepository
 * @see BatchRunContext
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBTRN02C",
        sourcePath = "app/jcl/POSTTRAN.jcl",
        translationDate = "2025-10-24",
        notes = "Composition root for the daily transaction posting job (CBTRN02C engine). "
                + "Wires 5 file-backed repository adapters (DailyTransaction, Transaction, "
                + "CardXref, Account, TransactionCategoryBalance) into the CbTrn02C use case. "
                + "RC_OK=0, RC_WITH_REJECTS=4, RC_ERROR=16 per COBOL convention."
)
public final class PostTransactionsApp {

    private static final Logger LOG = LoggerFactory.getLogger(PostTransactionsApp.class);

    /**
     * The {@link ScopedValue} binding for this app's {@link BatchRunContext}.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // -----------------------------------------------------------------------
    // Configuration keys (12-factor per AAP §0.7.2)
    // -----------------------------------------------------------------------

    static final String PROP_DAILYTRAN_PATH = "carddemo.file.dailytran.path";
    static final String DEFAULT_DAILYTRAN_PATH = "./data/dailytran.dat";

    static final String PROP_DALYREJS_PATH = "carddemo.file.dalyrejs.path";
    static final String DEFAULT_DALYREJS_PATH = "./data/dalyrejs.dat";

    static final String PROP_TRANSACT_PATH = "carddemo.file.transact.path";
    static final String DEFAULT_TRANSACT_PATH = "./data/transact.dat";

    static final String PROP_CARDXREF_PATH = "carddemo.file.cardxref.path";
    static final String DEFAULT_CARDXREF_PATH = "./data/cardxref.dat";

    static final String PROP_ACCTDATA_PATH = "carddemo.file.acctdata.path";
    static final String DEFAULT_ACCTDATA_PATH = "./data/acctdata.dat";

    static final String PROP_TCATBALF_PATH = "carddemo.file.tcatbalf.path";
    static final String DEFAULT_TCATBALF_PATH = "./data/tcatbalf.dat";

    // -----------------------------------------------------------------------
    // Return codes
    // -----------------------------------------------------------------------

    static final int RC_OK = 0;
    static final int RC_WITH_REJECTS = 4;
    static final int RC_NO_INPUT = 4;
    static final int RC_ERROR = 16;

    private PostTransactionsApp() {
        throw new AssertionError("PostTransactionsApp is not constructible");
    }

    /**
     * Java main entry point bound by the {@code shade-post-transactions}
     * execution in {@code carddemo-app/pom.xml}. Translates
     * {@code STEP1 EXEC PGM=CBTRN02C} of {@code app/jcl/POSTTRAN.jcl}.
     *
     * @param args unused; configuration flows through environment / system
     *             properties for 12-factor compliance
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(PostTransactionsApp::execute);
        } catch (Exception e) {
            LOG.error("POSTTRAN job failed with uncaught exception", e);
            rc = RC_ERROR;
        }
        // Pattern-matching switch — exhaustive on Integer, NO default branch
        // per AAP §0.7.3. Coverage: case null + 5 known-good constants
        // (0/4/8/12/16) + 2 guards (negative, >16) + unguarded type pattern
        // for remaining 1..3, 5..7, 9..11, 13..15.
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
     * Runs the posting job body inside the {@link #BATCH_CTX} scope.
     *
     * @return {@link #RC_OK} on a clean run, {@link #RC_WITH_REJECTS} when
     *         CBTRN02C reports rejects, {@link #RC_NO_INPUT} when the
     *         DALYTRAN input file is absent, or {@link #RC_ERROR} on any
     *         abend.
     */
    static int execute() {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("POSTTRAN job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        // Path resolution flows through SafePathResolver so every JCL DD
        // path is (a) read with documented 12-factor env/sysprop/default
        // precedence and (b) normalised against any `..` segments per
        // AAP §0.7.2 (CWE-22 mitigation). resolveTrusted preserves the
        // trusted-deployment model declared at the top of execute():
        // the operator who configures these paths is the same one who
        // controls the data directory layout. Normalisation alone
        // closes the path-traversal flaw flagged in Checkpoint 4
        // review S2.
        Path dailyTranPath = SafePathResolver.resolveTrusted(PROP_DAILYTRAN_PATH, DEFAULT_DAILYTRAN_PATH);
        Path dalyRejsPath = SafePathResolver.resolveTrusted(PROP_DALYREJS_PATH, DEFAULT_DALYREJS_PATH);
        Path transactPath = SafePathResolver.resolveTrusted(PROP_TRANSACT_PATH, DEFAULT_TRANSACT_PATH);
        Path cardXrefPath = SafePathResolver.resolveTrusted(PROP_CARDXREF_PATH, DEFAULT_CARDXREF_PATH);
        Path acctDataPath = SafePathResolver.resolveTrusted(PROP_ACCTDATA_PATH, DEFAULT_ACCTDATA_PATH);
        Path tcatBalfPath = SafePathResolver.resolveTrusted(PROP_TCATBALF_PATH, DEFAULT_TCATBALF_PATH);

        // Pre-flight: DALYTRAN is the only required input. The other four are
        // read+update / append datasets that the COBOL job creates if absent
        // (CBTRN02C internally opens them with INPUT/I-O/OUTPUT as needed).
        if (!Files.exists(dailyTranPath)) {
            LOG.warn("POSTTRAN: DALYTRAN input not found at {}; returning rc={}",
                    dailyTranPath, RC_NO_INPUT);
            return RC_NO_INPUT;
        }

        // Ensure the parent of the DALYREJS reject file exists — CBTRN02C
        // calls appendReject() lazily so the file is created on first reject.
        Path dalyRejsParent = dalyRejsPath.getParent();
        if (dalyRejsParent != null) {
            try {
                Files.createDirectories(dalyRejsParent);
            } catch (IOException e) {
                LOG.error("POSTTRAN: failed to create DALYREJS parent directory {}",
                        dalyRejsParent, e);
                return RC_ERROR;
            }
        }

        // Wire the 5 file-backed repository adapters. AutoCloseable per
        // AAP §0.5.1, &sect;0.6.10 — they all close cleanly.
        try (FileDailyTransactionRepository dailyTranRepo =
                     new FileDailyTransactionRepository(dailyTranPath, dalyRejsPath);
             FileTransactionRepository tranRepo =
                     new FileTransactionRepository(transactPath);
             FileCardXrefRepository xrefRepo =
                     new FileCardXrefRepository(cardXrefPath);
             FileAccountRepository acctRepo =
                     new FileAccountRepository(acctDataPath);
             FileTransactionCategoryBalanceRepository tcatRepo =
                     new FileTransactionCategoryBalanceRepository(tcatBalfPath)) {

            LOG.info("POSTTRAN: wiring CbTrn02C use case; dailytran={}, dalyrejs={}, "
                    + "transact={}, cardxref={}, acctdata={}, tcatbalf={}",
                    dailyTranPath, dalyRejsPath, transactPath, cardXrefPath,
                    acctDataPath, tcatBalfPath);

            // Constructor parameter order per file schema and AAP §0.5.3:
            //   (DailyTransaction, CardXref, Account, Transaction, TcatBal)
            CbTrn02C cbTrn02C = new CbTrn02C(dailyTranRepo, xrefRepo, acctRepo,
                    tranRepo, tcatRepo);
            cbTrn02C.run();

            int rc = cbTrn02C.returnCode();
            LOG.info("POSTTRAN job complete; rc={}", rc);
            return rc;
        } catch (AbendException ae) {
            LOG.error("POSTTRAN: CBTRN02C abended with code={}: {}",
                    ae.abendCode(), ae.getMessage(), ae);
            return RC_ERROR;
        }
    }

    /**
     * Resolves a configuration value with the documented 12-factor precedence:
     * environment variable &rarr; JVM system property &rarr; default.
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
