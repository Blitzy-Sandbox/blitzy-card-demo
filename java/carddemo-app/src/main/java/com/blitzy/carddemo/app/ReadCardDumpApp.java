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

import com.blitzy.carddemo.adapter.file.FileCardRepository;
import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.application.account.CbAct02C;
import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/READCARD.jcl}
 * &mdash; the {@code EXEC PGM=CBACT02C} step that reads and prints the
 * card master VSAM file as a DISPLAY dump.
 *
 * <h2>PAN masking (AAP &sect;0.7.2)</h2>
 * <p>The CBACT02C COBOL program emits full card numbers (PIC X(16)) to the
 * SYSOUT DD; the Java translation masks all but the last 4 digits when
 * logging through SLF4J. This is enforced by
 * {@link com.blitzy.carddemo.domain.record.CardRecord#toString()} which
 * uses the {@code ************LLLL} mask. Card numbers persisted to file
 * via {@link FileCardRepository#save} are NOT altered &mdash; byte-for-byte
 * file fidelity per AAP &sect;0.1.3 requires the persisted PAN to remain
 * the verbatim 16-character value.
 *
 * <h2>Runtime configuration (AAP &sect;0.7.2)</h2>
 * <ul>
 *   <li>{@code carddemo.file.carddata.path} &rarr; CARDFILE input
 *       (default {@code ./data/carddata.dat})</li>
 * </ul>
 *
 * <h2>Process-exit semantics</h2>
 * <ul>
 *   <li>{@link #RC_OK} ({@code 0}) on a clean dump pass</li>
 *   <li>{@link #RC_NO_INPUT} ({@code 4}) when the input file is absent</li>
 *   <li>{@link #RC_ERROR} ({@code 16}) on any abend or uncaught failure</li>
 * </ul>
 *
 * @see CbAct02C
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBACT02C",
        sourcePath = "app/jcl/READCARD.jcl",
        translationDate = "2025-10-24",
        notes = "Composition root for the IDCAMS PRINT-style card dump utility (CBACT02C "
                + "engine). Wires FileCardRepository into CbAct02C and runs sequentially. "
                + "PAN is masked in log output but preserved verbatim in file storage."
)
public final class ReadCardDumpApp {

    private static final Logger LOG = LoggerFactory.getLogger(ReadCardDumpApp.class);

    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    static final String PROP_CARDDATA_PATH = "carddemo.file.carddata.path";
    static final String DEFAULT_CARDDATA_PATH = "./data/carddata.dat";

    static final int RC_OK = 0;
    static final int RC_NO_INPUT = 4;
    static final int RC_ERROR = 16;

    private ReadCardDumpApp() {
        throw new AssertionError("ReadCardDumpApp is not constructible");
    }

    /**
     * Java main entry point bound by the {@code shade-read-card-dump}
     * execution in {@code carddemo-app/pom.xml}.
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(ReadCardDumpApp::execute);
        } catch (Exception e) {
            LOG.error("READCARD job failed with uncaught exception", e);
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
        LOG.info("READCARD job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        Path cardDataPath = Path.of(getProp(PROP_CARDDATA_PATH, DEFAULT_CARDDATA_PATH));

        if (!Files.exists(cardDataPath)) {
            LOG.warn("READCARD: CARDFILE input not found at {}; returning rc={}",
                    cardDataPath, RC_NO_INPUT);
            return RC_NO_INPUT;
        }

        try (FileCardRepository cardRepo = new FileCardRepository(cardDataPath)) {
            LOG.info("READCARD: wiring CbAct02C; carddata={}", cardDataPath);
            CbAct02C cbAct02C = new CbAct02C(cardRepo);
            cbAct02C.run();
            LOG.info("READCARD job complete; rc={}", RC_OK);
            return RC_OK;
        } catch (AbendException ae) {
            LOG.error("READCARD: CBACT02C abended with code={}: {}",
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
