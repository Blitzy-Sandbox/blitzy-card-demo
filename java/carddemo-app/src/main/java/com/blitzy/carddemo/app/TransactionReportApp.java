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

import com.blitzy.carddemo.adapter.file.FileCardXrefRepository;
import com.blitzy.carddemo.adapter.file.FileTransactionCategoryRepository;
import com.blitzy.carddemo.adapter.file.FileTransactionRepository;
import com.blitzy.carddemo.adapter.file.FileTransactionTypeRepository;
import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.application.transaction.CbTrn03C;
import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/TRANREPT.jcl}
 * &mdash; the paginated transaction detail report job that invokes the COBOL
 * CBTRN03C engine.
 *
 * <h2>Source artefact</h2>
 * <p>Original JCL ({@code app/jcl/TRANREPT.jcl}) chains a {@code REPROC}
 * unload of {@code TRANSACT} and a {@code STEP05R EXEC PGM=CBTRN03C}
 * step with these DD allocations:
 * <ul>
 *   <li>{@code TRANFILE} &mdash; transaction master (read sequentially)</li>
 *   <li>{@code XREFFILE} &mdash; card-account cross-reference</li>
 *   <li>{@code TRANTYPE} &mdash; transaction type lookup</li>
 *   <li>{@code TRANCATG} &mdash; transaction category lookup</li>
 *   <li>{@code DATEPARM} &mdash; start/end date filter (translated to
 *       {@code carddemo.tran-report.start-date} and
 *       {@code carddemo.tran-report.end-date} configuration keys)</li>
 *   <li>{@code TRANREPT} &mdash; 133-byte fixed-width paginated report output</li>
 * </ul>
 *
 * <h2>Translation strategy</h2>
 * <p>The mainframe {@code DATEPARM} DD points at a dataset whose two
 * lines hold the start and end dates in YYYY-MM-DD form. The Java
 * translation accepts these via either properties or command-line args
 * 1 and 2 (e.g.,
 * {@code java -jar carddemo-transaction-report.jar 2022-07-01 2022-07-31}).
 *
 * <h2>Runtime configuration (AAP &sect;0.7.2)</h2>
 * <ul>
 *   <li>{@code carddemo.file.transact.path} &rarr; TRANFILE input</li>
 *   <li>{@code carddemo.file.cardxref.path} &rarr; XREFFILE input</li>
 *   <li>{@code carddemo.file.trantype.path} &rarr; TRANTYPE lookup</li>
 *   <li>{@code carddemo.file.trancatg.path} &rarr; TRANCATG lookup</li>
 *   <li>{@code carddemo.file.tranrept.path} &rarr; TRANREPT output</li>
 *   <li>{@code carddemo.tran-report.start-date} &rarr; WS-START-DATE</li>
 *   <li>{@code carddemo.tran-report.end-date} &rarr; WS-END-DATE</li>
 * </ul>
 *
 * <h2>Process-exit semantics</h2>
 * <ul>
 *   <li>{@link #RC_OK} ({@code 0}) on a clean report run</li>
 *   <li>{@link #RC_NO_INPUT} ({@code 4}) when the TRANSACT input file is absent</li>
 *   <li>{@link #RC_ERROR} ({@code 16}) on any abend or uncaught failure</li>
 * </ul>
 *
 * @see CbTrn03C
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBTRN03C",
        sourcePath = "app/jcl/TRANREPT.jcl",
        translationDate = "2025-10-24",
        notes = "Composition root for the paginated transaction report job (CBTRN03C engine). "
                + "Wires 4 file-backed repository adapters (Transaction, CardXref, "
                + "TransactionType, TransactionCategory) plus 2 date filter args (start/end). "
                + "Translates the mainframe DATEPARM DD to two configuration properties."
)
public final class TransactionReportApp {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionReportApp.class);

    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // -----------------------------------------------------------------------
    // Configuration keys (12-factor per AAP §0.7.2)
    // -----------------------------------------------------------------------

    static final String PROP_TRANSACT_PATH = "carddemo.file.transact.path";
    static final String DEFAULT_TRANSACT_PATH = "./data/transact.dat";

    static final String PROP_CARDXREF_PATH = "carddemo.file.cardxref.path";
    static final String DEFAULT_CARDXREF_PATH = "./data/cardxref.dat";

    static final String PROP_TRANTYPE_PATH = "carddemo.file.trantype.path";
    static final String DEFAULT_TRANTYPE_PATH = "./data/trantype.dat";

    static final String PROP_TRANCATG_PATH = "carddemo.file.trancatg.path";
    static final String DEFAULT_TRANCATG_PATH = "./data/trancatg.dat";

    static final String PROP_TRANREPT_PATH = "carddemo.file.tranrept.path";
    static final String DEFAULT_TRANREPT_PATH = "./output/tranrept.dat";

    static final String PROP_START_DATE = "carddemo.tran-report.start-date";
    static final String PROP_END_DATE = "carddemo.tran-report.end-date";

    /** Default start/end dates: cover the COBOL ASCII fixture range. */
    static final String DEFAULT_START_DATE = "0000-00-00";
    static final String DEFAULT_END_DATE = "9999-99-99";

    static final int RC_OK = 0;
    static final int RC_NO_INPUT = 4;
    static final int RC_ERROR = 16;

    /** First two command-line args, captured by main() and read in execute(). */
    private static volatile String startDateOverride;
    private static volatile String endDateOverride;

    private TransactionReportApp() {
        throw new AssertionError("TransactionReportApp is not constructible");
    }

    /**
     * Java main entry point. Accepts optional first / second command-line
     * arguments as {@code YYYY-MM-DD} start and end dates.
     */
    public static void main(String[] args) {
        if (args != null) {
            if (args.length > 0 && args[0] != null && !args[0].isBlank()) {
                startDateOverride = args[0];
            }
            if (args.length > 1 && args[1] != null && !args[1].isBlank()) {
                endDateOverride = args[1];
            }
        }
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(TransactionReportApp::execute);
        } catch (Exception e) {
            LOG.error("TRANREPT job failed with uncaught exception", e);
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
     * Runs the transaction-report body inside the {@link #BATCH_CTX} scope.
     */
    static int execute() {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("TRANREPT job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        String startDate = resolveDate(startDateOverride, PROP_START_DATE, DEFAULT_START_DATE);
        String endDate = resolveDate(endDateOverride, PROP_END_DATE, DEFAULT_END_DATE);
        LOG.info("TRANREPT: date filter startDate={}, endDate={}", startDate, endDate);

        Path transactPath = Path.of(getProp(PROP_TRANSACT_PATH, DEFAULT_TRANSACT_PATH));
        Path cardXrefPath = Path.of(getProp(PROP_CARDXREF_PATH, DEFAULT_CARDXREF_PATH));
        Path tranTypePath = Path.of(getProp(PROP_TRANTYPE_PATH, DEFAULT_TRANTYPE_PATH));
        Path tranCatgPath = Path.of(getProp(PROP_TRANCATG_PATH, DEFAULT_TRANCATG_PATH));
        Path tranReptPath = Path.of(getProp(PROP_TRANREPT_PATH, DEFAULT_TRANREPT_PATH));

        if (!Files.exists(transactPath)) {
            LOG.warn("TRANREPT: TRANSACT input not found at {}; returning rc={}",
                    transactPath, RC_NO_INPUT);
            return RC_NO_INPUT;
        }

        // Ensure the output parent directory exists.
        Path reptParent = tranReptPath.getParent();
        if (reptParent != null) {
            try {
                Files.createDirectories(reptParent);
            } catch (IOException e) {
                LOG.error("TRANREPT: failed to create output parent directory {}",
                        reptParent, e);
                return RC_ERROR;
            }
        }

        try (FileTransactionRepository tranRepo =
                     new FileTransactionRepository(transactPath);
             FileCardXrefRepository xrefRepo =
                     new FileCardXrefRepository(cardXrefPath)) {

            // TransactionType + TransactionCategory repositories don't extend
            // AutoCloseable, so they're constructed outside the try-with-resources.
            FileTransactionTypeRepository tranTypeRepo =
                    new FileTransactionTypeRepository(tranTypePath);
            FileTransactionCategoryRepository tranCatgRepo =
                    new FileTransactionCategoryRepository(tranCatgPath);

            LOG.info("TRANREPT: wiring CbTrn03C use case; transact={}, cardxref={}, "
                    + "trantype={}, trancatg={}, tranrept={}",
                    transactPath, cardXrefPath, tranTypePath, tranCatgPath, tranReptPath);

            CbTrn03C cbTrn03C = new CbTrn03C(tranRepo, xrefRepo, tranTypeRepo,
                    tranCatgRepo, tranReptPath, startDate, endDate);
            cbTrn03C.run();

            LOG.info("TRANREPT job complete; rc={}", RC_OK);
            return RC_OK;
        } catch (AbendException ae) {
            LOG.error("TRANREPT: CBTRN03C abended with code={}: {}",
                    ae.abendCode(), ae.getMessage(), ae);
            return RC_ERROR;
        }
    }

    /**
     * Resolves a date value with documented precedence: command-line override
     * (captured into the static field by {@link #main(String[])}) &rarr;
     * env/sysprop &rarr; the supplied {@code defaultValue}.
     */
    static String resolveDate(String override, String propKey, String defaultValue) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        return getProp(propKey, defaultValue);
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
