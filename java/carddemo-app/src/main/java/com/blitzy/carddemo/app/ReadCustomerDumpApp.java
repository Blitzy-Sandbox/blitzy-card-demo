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

import com.blitzy.carddemo.adapter.file.FileCustomerRepository;
import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.application.customer.CbCus01C;
import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of {@code app/jcl/READCUST.jcl}
 * &mdash; the {@code EXEC PGM=CBCUS01C} step that reads and prints the
 * customer master VSAM file as a DISPLAY dump.
 *
 * <h2>Runtime configuration (AAP &sect;0.7.2)</h2>
 * <ul>
 *   <li>{@code carddemo.file.custdata.path} &rarr; CUSTFILE input
 *       (default {@code ./data/custdata.dat})</li>
 * </ul>
 *
 * <h2>Process-exit semantics</h2>
 * <ul>
 *   <li>{@link #RC_OK} ({@code 0}) on a clean dump pass</li>
 *   <li>{@link #RC_NO_INPUT} ({@code 4}) when the input file is absent</li>
 *   <li>{@link #RC_ERROR} ({@code 16}) on any abend or uncaught failure</li>
 * </ul>
 *
 * @see CbCus01C
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBCUS01C",
        sourcePath = "app/jcl/READCUST.jcl",
        translationDate = "2025-10-24",
        notes = "Composition root for the IDCAMS PRINT-style customer dump utility "
                + "(CBCUS01C engine). Wires FileCustomerRepository into CbCus01C and runs "
                + "sequentially. Translates COBOL DISPLAY output to SLF4J INFO logs."
)
public final class ReadCustomerDumpApp {

    private static final Logger LOG = LoggerFactory.getLogger(ReadCustomerDumpApp.class);

    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    static final String PROP_CUSTDATA_PATH = "carddemo.file.custdata.path";
    static final String DEFAULT_CUSTDATA_PATH = "./data/custdata.dat";

    static final int RC_OK = 0;
    static final int RC_NO_INPUT = 4;
    static final int RC_ERROR = 16;

    private ReadCustomerDumpApp() {
        throw new AssertionError("ReadCustomerDumpApp is not constructible");
    }

    /**
     * Java main entry point bound by the {@code shade-read-customer-dump}
     * execution in {@code carddemo-app/pom.xml}.
     */
    public static void main(String[] args) {
        BatchRunContext ctx = BatchRunContext.fromEnvironment();
        Integer rc;
        try {
            rc = ScopedValue.where(BATCH_CTX, ctx).call(ReadCustomerDumpApp::execute);
        } catch (Exception e) {
            LOG.error("READCUST job failed with uncaught exception", e);
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
        LOG.info("READCUST job starting; runId={}, processingDate={}, tenant={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant());

        Path custDataPath = Path.of(getProp(PROP_CUSTDATA_PATH, DEFAULT_CUSTDATA_PATH));

        if (!Files.exists(custDataPath)) {
            LOG.warn("READCUST: CUSTFILE input not found at {}; returning rc={}",
                    custDataPath, RC_NO_INPUT);
            return RC_NO_INPUT;
        }

        try (FileCustomerRepository custRepo = new FileCustomerRepository(custDataPath)) {
            LOG.info("READCUST: wiring CbCus01C; custdata={}", custDataPath);
            CbCus01C cbCus01C = new CbCus01C(custRepo);
            cbCus01C.run();
            LOG.info("READCUST job complete; rc={}", RC_OK);
            return RC_OK;
        } catch (AbendException ae) {
            LOG.error("READCUST: CBCUS01C abended with code={}: {}",
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
