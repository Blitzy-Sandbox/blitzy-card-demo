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

// JEP 511 (finalized in Java 25): single declaration imports java.base.
import module java.base;

import com.blitzy.carddemo.adapter.file.FileAccountRepository;
import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.application.account.CbAct01C;
import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/READACCT.jcl}
 * &mdash; the {@code EXEC PGM=CBACT01C} step that reads and prints the
 * account master VSAM file as a DISPLAY dump.
 *
 * <h2>Source artefact</h2>
 * <p>Original JCL ({@code app/jcl/READACCT.jcl}) executes
 * {@code STEP05 EXEC PGM=CBACT01C} with the {@code ACCTFILE} DD pointing
 * at the {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS} dataset. The Java
 * translation wires {@link FileAccountRepository} into
 * {@link CbAct01C} and invokes {@code run()}, which streams every
 * 300-byte AccountRecord to stdout via {@code DISPLAY} (translated to
 * SLF4J INFO logging in CbAct01C).
 *
 * <h2>Runtime configuration (AAP &sect;0.7.2)</h2>
 * <ul>
 *   <li>{@code carddemo.file.acctdata.path} &rarr; ACCTFILE input
 *       (default {@code ./data/acctdata.dat})</li>
 * </ul>
 *
 * <h2>Process-exit semantics</h2>
 * <ul>
 *   <li>{@link #RC_OK} ({@code 0}) on a clean dump pass</li>
 *   <li>{@link #RC_NO_INPUT} ({@code 4}) when the input file is absent</li>
 *   <li>{@link #RC_ERROR} ({@code 16}) on any abend or uncaught failure</li>
 * </ul>
 *
 * @see CbAct01C
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBACT01C",
        sourcePath = "app/jcl/READACCT.jcl",
        translationDate = "2025-10-24",
        notes = "Composition root for the IDCAMS PRINT-style account dump utility (CBACT01C "
                + "engine). Wires FileAccountRepository into CbAct01C and runs sequentially. "
                + "Translates the mainframe DISPLAY output to SLF4J INFO logs."
)
public final class ReadAccountDumpApp {

    private static final Logger LOG = LoggerFactory.getLogger(ReadAccountDumpApp.class);

    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    static final String PROP_ACCTDATA_PATH = "carddemo.file.acctdata.path";
    static final String DEFAULT_ACCTDATA_PATH = "./data/acctdata.dat";

    static final int RC_OK = 0;
    static final int RC_NO_INPUT = 4;
    static final int RC_ERROR = 16;

    private ReadAccountDumpApp() {
        throw new AssertionError("ReadAccountDumpApp is not constructible");
    }

    /**
     * Java main entry point bound by the {@code shade-read-account-dump}
     * execution in {@code carddemo-app/pom.xml}.
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(ReadAccountDumpApp::execute);
        } catch (Exception e) {
            LOG.error("READACCT job failed with uncaught exception", e);
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

    static int execute() {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("READACCT job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        Path acctDataPath = Path.of(getProp(PROP_ACCTDATA_PATH, DEFAULT_ACCTDATA_PATH));

        if (!Files.exists(acctDataPath)) {
            LOG.warn("READACCT: ACCTFILE input not found at {}; returning rc={}",
                    acctDataPath, RC_NO_INPUT);
            return RC_NO_INPUT;
        }

        try (FileAccountRepository acctRepo = new FileAccountRepository(acctDataPath)) {
            LOG.info("READACCT: wiring CbAct01C; acctdata={}", acctDataPath);
            CbAct01C cbAct01C = new CbAct01C(acctRepo);
            // CbAct01C.run() returns an APPL-RESULT-style int per AAP §0.4.1
            // (CBACT01C translation): APPL_AOK (0) on success, APPL_ERROR
            // (12) on any I/O failure that would have triggered the COBOL
            // 9999-ABEND-PROGRAM paragraph. Errors are NOT thrown out of
            // run() — they are surfaced via the return code. We map any
            // non-zero application result to RC_ERROR so the process exit
            // code reflects the failure (matching the observable outcome
            // of the COBOL CEE3ABD abend).
            int applResult = cbAct01C.run();
            if (applResult != CbAct01C.APPL_AOK) {
                LOG.error("READACCT: CBACT01C returned non-zero APPL_RESULT={}; "
                        + "mapping to RC_ERROR={}", applResult, RC_ERROR);
                return RC_ERROR;
            }
            LOG.info("READACCT job complete; rc={}", RC_OK);
            return RC_OK;
        } catch (AbendException ae) {
            // Defensive catch retained for any AbendException raised by
            // collaborators other than CbAct01C (e.g., the adapter layer
            // or future shared utilities). CbAct01C itself no longer
            // throws AbendException — it surfaces failures via the int
            // return value above per AAP §0.4.1.
            LOG.error("READACCT: CBACT01C abended with code={}: {}",
                    ae.abendCode(), ae.getMessage(), ae);
            return RC_ERROR;
        }
    }

    /** 12-factor configuration lookup. */
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
