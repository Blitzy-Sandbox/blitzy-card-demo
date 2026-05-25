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

import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.application.statement.CbStm03A;
import com.blitzy.carddemo.application.statement.CbStm03B;
import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/CREASTMT.JCL}
 * &mdash; the statement-generation job that invokes the COBOL CBSTM03A
 * statement-printer engine (with the CBSTM03B file-services subroutine).
 *
 * <h2>Source artefact</h2>
 * <p>Original JCL ({@code app/jcl/CREASTMT.JCL}) chains the
 * {@code STEP1 EXEC PGM=CBSTM03A} step with these DD allocations:
 * <ul>
 *   <li>{@code TRNXFILE} &mdash; transaction file (read via CBSTM03B subroutine)</li>
 *   <li>{@code XREFFILE} &mdash; card-account cross-reference (read via CBSTM03B)</li>
 *   <li>{@code CUSTFILE} &mdash; customer master (read via CBSTM03B)</li>
 *   <li>{@code ACCTFILE} &mdash; account master (read via CBSTM03B)</li>
 *   <li>{@code STMTFILE} &mdash; 80-byte fixed-width plain-text statements (output)</li>
 *   <li>{@code HTMLFILE} &mdash; HTML statements (output)</li>
 * </ul>
 *
 * <h2>Translation strategy</h2>
 * <p>The job constructs a single {@link CbStm03B} file-services helper
 * with the four input dataset paths, then injects it (along with the two
 * output paths) into a single {@link CbStm03A} statement-generation use
 * case and invokes {@code run()}.
 *
 * <h2>Runtime configuration (AAP &sect;0.7.2)</h2>
 * <ul>
 *   <li>{@code carddemo.file.transact.path} &rarr; TRNXFILE input</li>
 *   <li>{@code carddemo.file.cardxref.path} &rarr; XREFFILE input</li>
 *   <li>{@code carddemo.file.custdata.path} &rarr; CUSTFILE input</li>
 *   <li>{@code carddemo.file.acctdata.path} &rarr; ACCTFILE input</li>
 *   <li>{@code carddemo.file.stmt.text.path} &rarr; STMTFILE plain text output</li>
 *   <li>{@code carddemo.file.stmt.html.path} &rarr; HTMLFILE HTML output</li>
 * </ul>
 *
 * <h2>Process-exit semantics</h2>
 * <ul>
 *   <li>{@link #RC_OK} ({@code 0}) on a clean statement-generation pass</li>
 *   <li>{@link #RC_NO_INPUT} ({@code 4}) when any required input file is absent</li>
 *   <li>{@link #RC_ERROR} ({@code 16}) on any abend or uncaught failure</li>
 * </ul>
 *
 * @see CbStm03A
 * @see CbStm03B
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBSTM03A",
        sourcePath = "app/jcl/CREASTMT.JCL",
        translationDate = "2025-10-24",
        notes = "Composition root for the statement generation job. Wires CbStm03B "
                + "file-services helper (4 input paths) into CbStm03A (use case + 2 "
                + "output paths) and invokes run(). Legacy z/OS TIOT/TCB/PSA inspection "
                + "and ALTER/GO TO control flow in CBSTM03A is translated faithfully — "
                + "see MIGRATION_NOTES.md for the DEVIATION flag."
)
public final class CreateStatementsApp {

    private static final Logger LOG = LoggerFactory.getLogger(CreateStatementsApp.class);

    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // -----------------------------------------------------------------------
    // Configuration keys (12-factor per AAP §0.7.2)
    // -----------------------------------------------------------------------

    static final String PROP_TRANSACT_PATH = "carddemo.file.transact.path";
    static final String DEFAULT_TRANSACT_PATH = "./data/transact.dat";

    static final String PROP_CARDXREF_PATH = "carddemo.file.cardxref.path";
    static final String DEFAULT_CARDXREF_PATH = "./data/cardxref.dat";

    static final String PROP_CUSTDATA_PATH = "carddemo.file.custdata.path";
    static final String DEFAULT_CUSTDATA_PATH = "./data/custdata.dat";

    static final String PROP_ACCTDATA_PATH = "carddemo.file.acctdata.path";
    static final String DEFAULT_ACCTDATA_PATH = "./data/acctdata.dat";

    static final String PROP_STMT_TEXT_PATH = "carddemo.file.stmt.text.path";
    static final String DEFAULT_STMT_TEXT_PATH = "./output/statements.txt";

    static final String PROP_STMT_HTML_PATH = "carddemo.file.stmt.html.path";
    static final String DEFAULT_STMT_HTML_PATH = "./output/statements.html";

    static final int RC_OK = 0;
    static final int RC_NO_INPUT = 4;
    static final int RC_ERROR = 16;

    private CreateStatementsApp() {
        throw new AssertionError("CreateStatementsApp is not constructible");
    }

    /**
     * Java main entry point bound by the {@code shade-create-statements}
     * execution in {@code carddemo-app/pom.xml}.
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(CreateStatementsApp::execute);
        } catch (Exception e) {
            LOG.error("CREASTMT job failed with uncaught exception", e);
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
     * Runs the statement-generation body inside the {@link #BATCH_CTX} scope.
     */
    static int execute() {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info("CREASTMT job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        Path transactPath = Path.of(getProp(PROP_TRANSACT_PATH, DEFAULT_TRANSACT_PATH));
        Path cardXrefPath = Path.of(getProp(PROP_CARDXREF_PATH, DEFAULT_CARDXREF_PATH));
        Path custDataPath = Path.of(getProp(PROP_CUSTDATA_PATH, DEFAULT_CUSTDATA_PATH));
        Path acctDataPath = Path.of(getProp(PROP_ACCTDATA_PATH, DEFAULT_ACCTDATA_PATH));
        Path stmtTextPath = Path.of(getProp(PROP_STMT_TEXT_PATH, DEFAULT_STMT_TEXT_PATH));
        Path stmtHtmlPath = Path.of(getProp(PROP_STMT_HTML_PATH, DEFAULT_STMT_HTML_PATH));

        // Pre-flight: all four input files are required. CBSTM03B opens them
        // on first use; we check existence here so we can return RC_NO_INPUT
        // (4) instead of letting the abend handler clamp to RC_ERROR (16).
        Path missing = firstMissing(transactPath, cardXrefPath, custDataPath, acctDataPath);
        if (missing != null) {
            LOG.warn("CREASTMT: required input not found at {}; returning rc={}",
                    missing, RC_NO_INPUT);
            return RC_NO_INPUT;
        }

        // Ensure output parent directories exist (do NOT create the files —
        // CBSTM03A opens them with truncate-on-write semantics).
        try {
            ensureParent(stmtTextPath);
            ensureParent(stmtHtmlPath);
        } catch (IOException e) {
            LOG.error("CREASTMT: failed to prepare output directories", e);
            return RC_ERROR;
        }

        LOG.info("CREASTMT: wiring CbStm03B + CbStm03A; transact={}, cardxref={}, "
                + "custdata={}, acctdata={}, stmt.text={}, stmt.html={}",
                transactPath, cardXrefPath, custDataPath, acctDataPath,
                stmtTextPath, stmtHtmlPath);

        try {
            CbStm03B cbStm03B = new CbStm03B(transactPath, cardXrefPath,
                    custDataPath, acctDataPath);
            CbStm03A cbStm03A = new CbStm03A(cbStm03B, stmtTextPath, stmtHtmlPath);
            cbStm03A.run();

            LOG.info("CREASTMT job complete; rc={}", RC_OK);
            return RC_OK;
        } catch (AbendException ae) {
            LOG.error("CREASTMT: CBSTM03A abended with code={}: {}",
                    ae.abendCode(), ae.getMessage(), ae);
            return RC_ERROR;
        }
    }

    /**
     * Returns the first {@link Path} from the given list that does not
     * exist, or {@code null} if all paths exist.
     */
    private static Path firstMissing(Path... paths) {
        for (Path p : paths) {
            if (!Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Creates the parent directory of the supplied path if necessary.
     */
    private static void ensureParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
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
