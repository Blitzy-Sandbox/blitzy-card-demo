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

// JEP 511 (finalized in Java 25): single declaration imports the java.base
// module (Path, Files, IOException, Locale, ScopedValue, etc).
import module java.base;

import com.blitzy.carddemo.adapter.file.FileAccountRepository;
import com.blitzy.carddemo.adapter.file.FileCardXrefRepository;
import com.blitzy.carddemo.adapter.file.FileDiscountGroupRepository;
import com.blitzy.carddemo.adapter.file.FileTransactionCategoryBalanceRepository;
import com.blitzy.carddemo.adapter.file.FileTransactionRepository;
import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.application.account.CbAct04C;
import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/INTCALC.jcl}
 * &mdash; the monthly interest calculation job that invokes the COBOL
 * CBACT04C interest accrual engine.
 *
 * <h2>Source artefact</h2>
 * <p>Original JCL ({@code app/jcl/INTCALC.jcl}) chains the
 * {@code STEP15 EXEC PGM=CBACT04C,PARM='YYYYMMDDHH'} step with these DD
 * allocations:
 * <ul>
 *   <li>{@code TCATBALF} &mdash; VSAM KSDS, per-category balance (read+upsert)</li>
 *   <li>{@code XREFFILE} &mdash; VSAM KSDS, card-account cross-reference (read-only)</li>
 *   <li>{@code DISCGRP} &mdash; VSAM KSDS, discount group rates (read-only,
 *       with DEFAULT fallback per AAP &sect;0.6.10)</li>
 *   <li>{@code ACCTFILE} &mdash; VSAM KSDS, account master (read+update)</li>
 *   <li>{@code TRANSACT} &mdash; VSAM KSDS, posted interest accrual records (append)</li>
 * </ul>
 *
 * <h2>PARM date handling</h2>
 * <p>The COBOL {@code PROCEDURE DIVISION USING WS-PARM-DATE} accepts a
 * 10-character date+hour parameter in the {@code YYYYMMDDHH} format
 * (e.g., {@code 2022071800}). The Java translation accepts this via
 * either the {@code carddemo.intcalc.parm} property (preferred) or the
 * first command-line argument. Per {@code application.properties.example}
 * the default is {@code 2022071800}.
 *
 * <h2>DEFAULT fallback for missing discount-group records (AAP &sect;0.6.10)</h2>
 * <p>The application-layer {@link CbAct04C} class implements the
 * paragraph {@code 1200-A-GET-DEFAULT-INT-RATE} fallback:
 * <ol>
 *   <li>First look up by {@code (account-group-id, type-cd, cat-cd)}.</li>
 *   <li>If empty, retry with {@code account-group-id = "DEFAULT"}.</li>
 *   <li>If still empty, use a zero interest rate.</li>
 * </ol>
 * This adapter simply returns {@link java.util.Optional#empty()} on
 * not-found &mdash; the fallback logic lives in the application layer,
 * NOT the adapter.
 *
 * <h2>Process-exit semantics</h2>
 * <p>Return codes:
 * <ul>
 *   <li>{@link #RC_OK} ({@code 0}) on a clean interest-accrual pass</li>
 *   <li>{@link #RC_NO_INPUT} ({@code 4}) when the TCATBALF input file is absent</li>
 *   <li>{@link #RC_ERROR} ({@code 16}) on any abend or uncaught failure</li>
 * </ul>
 *
 * @see CbAct04C
 * @see BatchRunContext
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBACT04C",
        sourcePath = "app/jcl/INTCALC.jcl",
        translationDate = "2025-10-24",
        notes = "Composition root for the monthly interest calculation job (CBACT04C engine). "
                + "Wires 5 file-backed repository adapters (TCATBalance, CardXref, "
                + "DiscountGroup, Account, Transaction) plus the PARM='YYYYMMDDHH' date. "
                + "DEFAULT-group fallback lives in the application layer per AAP §0.6.10."
)
public final class InterestCalculationApp {

    private static final Logger LOG = LoggerFactory.getLogger(InterestCalculationApp.class);

    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // -----------------------------------------------------------------------
    // Configuration keys (12-factor per AAP §0.7.2)
    // -----------------------------------------------------------------------

    static final String PROP_TCATBALF_PATH = "carddemo.file.tcatbalf.path";
    static final String DEFAULT_TCATBALF_PATH = "./data/tcatbalf.dat";

    static final String PROP_CARDXREF_PATH = "carddemo.file.cardxref.path";
    static final String DEFAULT_CARDXREF_PATH = "./data/cardxref.dat";

    static final String PROP_DISCGRP_PATH = "carddemo.file.discgrp.path";
    static final String DEFAULT_DISCGRP_PATH = "./data/discgrp.dat";

    static final String PROP_ACCTDATA_PATH = "carddemo.file.acctdata.path";
    static final String DEFAULT_ACCTDATA_PATH = "./data/acctdata.dat";

    static final String PROP_TRANSACT_PATH = "carddemo.file.transact.path";
    static final String DEFAULT_TRANSACT_PATH = "./data/transact.dat";

    /**
     * Configuration key for the COBOL {@code PARM='YYYYMMDDHH'} value passed
     * to CBACT04C's {@code WS-PARM-DATE} linkage.
     */
    static final String PROP_PARM_DATE = "carddemo.intcalc.parm";

    /**
     * Default PARM date when neither the system property nor the
     * command-line argument is supplied. Matches the
     * {@code application.properties.example} default.
     */
    static final String DEFAULT_PARM_DATE = "2022071800";

    static final int RC_OK = 0;
    static final int RC_NO_INPUT = 4;
    static final int RC_ERROR = 16;

    /** Holds the first command-line arg if supplied; consumed by execute(). */
    private static volatile String parmDateOverride;

    private InterestCalculationApp() {
        throw new AssertionError("InterestCalculationApp is not constructible");
    }

    /**
     * Java main entry point. Accepts an optional first command-line
     * argument as the {@code YYYYMMDDHH} interest accrual date; falls
     * back to the {@code carddemo.intcalc.parm} property and finally to
     * {@link #DEFAULT_PARM_DATE}.
     */
    public static void main(String[] args) {
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            parmDateOverride = args[0];
        }
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(InterestCalculationApp::execute);
        } catch (Exception e) {
            LOG.error("INTCALC job failed with uncaught exception", e);
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

    /**
     * Runs the interest accrual body inside the {@link #BATCH_CTX} scope.
     */
    static int execute() {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("INTCALC job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        String parmDate = resolveParmDate();
        LOG.info("INTCALC: PARM='{}' (interest accrual date YYYYMMDDHH)", parmDate);

        Path tcatBalfPath = SafePathResolver.resolveTrusted(PROP_TCATBALF_PATH, DEFAULT_TCATBALF_PATH);
        Path cardXrefPath = SafePathResolver.resolveTrusted(PROP_CARDXREF_PATH, DEFAULT_CARDXREF_PATH);
        Path discGrpPath = SafePathResolver.resolveTrusted(PROP_DISCGRP_PATH, DEFAULT_DISCGRP_PATH);
        Path acctDataPath = SafePathResolver.resolveTrusted(PROP_ACCTDATA_PATH, DEFAULT_ACCTDATA_PATH);
        Path transactPath = SafePathResolver.resolveTrusted(PROP_TRANSACT_PATH, DEFAULT_TRANSACT_PATH);

        // Pre-flight: TCATBALF is the only mandatory input (CBACT04C iterates
        // over it). The other four are read/append datasets that the job
        // creates if absent.
        if (!Files.exists(tcatBalfPath)) {
            LOG.warn("INTCALC: TCATBALF input not found at {}; returning rc={}",
                    tcatBalfPath, RC_NO_INPUT);
            return RC_NO_INPUT;
        }

        try (FileTransactionCategoryBalanceRepository tcatRepo =
                     new FileTransactionCategoryBalanceRepository(tcatBalfPath);
             FileCardXrefRepository xrefRepo =
                     new FileCardXrefRepository(cardXrefPath);
             FileDiscountGroupRepository discRepo =
                     new FileDiscountGroupRepository(discGrpPath);
             FileAccountRepository acctRepo =
                     new FileAccountRepository(acctDataPath);
             FileTransactionRepository tranRepo =
                     new FileTransactionRepository(transactPath)) {

            LOG.info("INTCALC: wiring CbAct04C use case; tcatbalf={}, cardxref={}, "
                    + "discgrp={}, acctdata={}, transact={}",
                    tcatBalfPath, cardXrefPath, discGrpPath, acctDataPath, transactPath);

            // CbAct04C constructor argument order per the file schema:
            // (TransactionCategoryBalanceRepository, AccountRepository,
            //  CardXrefRepository, DiscountGroupRepository,
            //  TransactionRepository). See
            // java/carddemo-application/.../account/CbAct04C.java schema.
            CbAct04C cbAct04C = new CbAct04C(
                    tcatRepo,       // TransactionCategoryBalanceRepository
                    acctRepo,       // AccountRepository
                    xrefRepo,       // CardXrefRepository
                    discRepo,       // DiscountGroupRepository
                    tranRepo);      // TransactionRepository

            // CbAct04C#run(LocalDate) consumes a date-only value. The COBOL
            // PARM is a 10-character YYYYMMDDHH string; the new Java
            // contract uses the date portion (first 8 chars) parsed via
            // java.time. The hour suffix is preserved in run-level logging
            // for traceability. Per AAP §0.6.4, ResolverStyle.STRICT
            // catches malformed PARM-DATE values that COBOL would silently
            // corrupt.
            LocalDate processingDate = LocalDate.parse(
                    parmDate.substring(0, 8),
                    DateTimeFormatter.ofPattern("yyyyMMdd")
                            .withResolverStyle(ResolverStyle.STRICT));
            int applRc = cbAct04C.run(processingDate);

            LOG.info("INTCALC job complete; CBACT04C APPL_RC={}, rc={}",
                    applRc, applRc == CbAct04C.APPL_AOK ? RC_OK : RC_ERROR);
            return applRc == CbAct04C.APPL_AOK ? RC_OK : RC_ERROR;
        } catch (AbendException ae) {
            LOG.error("INTCALC: CBACT04C abended with code={}: {}",
                    ae.abendCode(), ae.getMessage(), ae);
            return RC_ERROR;
        } catch (java.time.format.DateTimeParseException dtpe) {
            LOG.error("INTCALC: invalid PARM date '{}': {}",
                    resolveParmDate(), dtpe.getMessage(), dtpe);
            return RC_ERROR;
        }
    }

    /**
     * Resolves the {@code YYYYMMDDHH} PARM date with documented precedence:
     * command-line arg (captured into {@link #parmDateOverride}) &rarr;
     * env / system-property {@link #PROP_PARM_DATE} &rarr;
     * {@link #DEFAULT_PARM_DATE}.
     */
    static String resolveParmDate() {
        String override = parmDateOverride;
        if (override != null && !override.isBlank()) {
            return override;
        }
        return getProp(PROP_PARM_DATE, DEFAULT_PARM_DATE);
    }

    /** 12-factor configuration lookup (env first, then sysprop). */
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
