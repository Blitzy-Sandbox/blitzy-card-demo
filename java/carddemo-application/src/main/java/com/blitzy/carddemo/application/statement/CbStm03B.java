/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.statement;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.AccountRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.CustomerRepository;
import com.blitzy.carddemo.domain.record.AccountRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.CustomerRecord;
import com.blitzy.carddemo.domain.record.TrnxRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Java translation of the {@code CBSTM03B} COBOL batch subroutine at
 * {@code app/cbl/CBSTM03B.CBL} ("Statement file-services subroutine").
 *
 * <h2>Program purpose</h2>
 * <p>This is a <i>file-services dispatcher</i> called repeatedly by
 * {@link CbStm03A} to OPEN, READ, READ-K (random by key), and CLOSE four
 * logical datasets:
 * <ul>
 *   <li>{@code TRNXFILE} (transaction-by-card sequential, INDEXED)</li>
 *   <li>{@code XREFFILE} (card cross-reference sequential, INDEXED)</li>
 *   <li>{@code CUSTFILE} (customer random-by-key)</li>
 *   <li>{@code ACCTFILE} (account random-by-key)</li>
 * </ul>
 *
 * <h2>COBOL LINKAGE SECTION (LK-M03B-AREA)</h2>
 * <pre>{@code
 *   05  LK-M03B-DD          PIC X(08).
 *   05  LK-M03B-OPER        PIC X(01).
 *       88  M03B-OPEN       VALUE 'O'.
 *       88  M03B-CLOSE      VALUE 'C'.
 *       88  M03B-READ       VALUE 'R'.
 *       88  M03B-READ-K     VALUE 'K'.
 *       88  M03B-WRITE      VALUE 'W'.
 *       88  M03B-REWRITE    VALUE 'Z'.
 *   05  LK-M03B-RC          PIC X(02).
 *   05  LK-M03B-KEY         PIC X(25).
 *   05  LK-M03B-KEY-LN      PIC S9(4).
 *   05  LK-M03B-FLDT        PIC X(1000).
 * }</pre>
 *
 * <p>The Java translation preserves the OPER 88-level set as a sealed
 * enum-style hierarchy via {@link Oper}; the RC string mirrors COBOL FILE
 * STATUS (e.g., "00" success, "10" EOF, "23" not-found).
 *
 * <h2>Notes</h2>
 * <ul>
 *   <li>TRNXFILE handling is open/sequential-read/close only (no WRITE).</li>
 *   <li>XREFFILE handling is open/sequential-read/close only.</li>
 *   <li>CUSTFILE handling is open/read-by-key/close only.</li>
 *   <li>ACCTFILE handling is open/read-by-key/close only.</li>
 *   <li>The COBOL also defines WRITE / REWRITE operations in the 88-level
 *       set but never dispatches them in any of the 4 file paragraphs;
 *       the Java translation likewise rejects them at runtime.</li>
 * </ul>
 */
@CobolProgram(
        value = "CBSTM03B",
        sourcePath = "app/cbl/CBSTM03B.CBL",
        notes = "Statement file-services dispatcher; OPEN/READ/READ-K/CLOSE over 4 datasets"
)
public final class CbStm03B {

    private static final Logger log = LoggerFactory.getLogger(CbStm03B.class);

    public static final String PROGRAM_ID = "CBSTM03B";

    public static final String DD_TRNXFILE = "TRNXFILE";
    public static final String DD_XREFFILE = "XREFFILE";
    public static final String DD_CUSTFILE = "CUSTFILE";
    public static final String DD_ACCTFILE = "ACCTFILE";

    public static final String RC_OK = "00";
    public static final String RC_EOF = "10";
    public static final String RC_NOT_FOUND = "23";
    public static final String RC_NOT_OPEN = "47";
    public static final String RC_UNSUPPORTED = "39";

    /**
     * COBOL OPER 88-level set: {@code 'O' open, 'C' close, 'R' read,
     * 'K' read-by-key, 'W' write, 'Z' rewrite}. The COBOL paragraphs only
     * implement OPEN / CLOSE / READ / READ-K; WRITE and REWRITE remain
     * unimplemented and are rejected at runtime.
     */
    public enum Oper {
        OPEN('O'), CLOSE('C'), READ('R'), READ_K('K'), WRITE('W'), REWRITE('Z');

        public final char code;
        Oper(char code) { this.code = code; }

        /**
         * Decodes the single-character LK-M03B-OPER value.
         *
         * @throws IllegalArgumentException if {@code code} is not one of OCRKWZ
         */
        public static Oper fromCode(char code) {
            return switch (code) {
                case 'O' -> OPEN;
                case 'C' -> CLOSE;
                case 'R' -> READ;
                case 'K' -> READ_K;
                case 'W' -> WRITE;
                case 'Z' -> REWRITE;
                default -> throw new IllegalArgumentException(
                        "Unknown M03B operator: '" + code + "'");
            };
        }
    }

    /**
     * Mirror of the {@code LK-M03B-AREA} LINKAGE SECTION record carrying
     * the file-services call args.
     */
    public static final class M03BArea {
        public String dd;       // LK-M03B-DD (8 chars)
        public Oper oper;       // LK-M03B-OPER
        public String rc;       // LK-M03B-RC  (2-char FILE STATUS)
        public String key;      // LK-M03B-KEY (25 chars)
        public int keyLn;       // LK-M03B-KEY-LN
        public byte[] fldt;     // LK-M03B-FLDT (1000-byte data area)

        public M03BArea() {
            this.rc = RC_OK;
            this.fldt = new byte[1000];
        }
    }

    // -- dependencies (one port per dataset)
    private final TrnxFileService trnxService;
    private final XrefFileService xrefService;
    private final CustFileService custService;
    private final AcctFileService acctService;

    /**
     * Constructor injection. Each "file service" wraps the corresponding
     * domain port and adds the open/close + sequential-read state needed
     * by the dispatcher.
     */
    public CbStm03B(CardXrefRepository xrefRepository,
                    CustomerRepository customerRepository,
                    AccountRepository accountRepository,
                    TrnxStreamProvider trnxProvider) {
        Objects.requireNonNull(xrefRepository, "xrefRepository");
        Objects.requireNonNull(customerRepository, "customerRepository");
        Objects.requireNonNull(accountRepository, "accountRepository");
        Objects.requireNonNull(trnxProvider, "trnxProvider");
        this.trnxService = new TrnxFileService(trnxProvider);
        this.xrefService = new XrefFileService(xrefRepository);
        this.custService = new CustFileService(customerRepository);
        this.acctService = new AcctFileService(accountRepository);
    }

    /**
     * Single entry point — mirrors COBOL {@code PROCEDURE DIVISION USING
     * LK-M03B-AREA} dispatch via the {@code 0000-START EVALUATE LK-M03B-DD}
     * statement.
     *
     * @param area in/out parameter carrying the file-services call args
     */
    public void invoke(M03BArea area) {
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(area.oper, "area.oper");
        Objects.requireNonNull(area.dd, "area.dd");

        String dd = area.dd.trim();
        switch (dd) {
            case DD_TRNXFILE -> trnxService.process(area);
            case DD_XREFFILE -> xrefService.process(area);
            case DD_CUSTFILE -> custService.process(area);
            case DD_ACCTFILE -> acctService.process(area);
            default -> {
                // COBOL: WHEN OTHER GO TO 9999-GOBACK (no RC mutation, just
                // returns); preserve verbatim — no rc change.
                log.warn("CBSTM03B: unknown DD: {}", dd);
            }
        }
    }

    /**
     * Provider interface for the TRNX sequential stream. The COBOL TRNX
     * file has no first-class domain port (it is a statement-generation
     * artifact); the consumer of CBSTM03B supplies a stream factory.
     */
    @FunctionalInterface
    public interface TrnxStreamProvider {
        Stream<TrnxRecord> openSequential();
    }

    // ------------------------------------------------------------ TRNXFILE

    /** Mirror of paragraph {@code 1000-TRNXFILE-PROC}. */
    private static final class TrnxFileService {
        private final TrnxStreamProvider provider;
        private Stream<TrnxRecord> stream;
        private Iterator<TrnxRecord> cursor;
        private String lastRc = RC_OK;

        TrnxFileService(TrnxStreamProvider provider) { this.provider = provider; }

        void process(M03BArea area) {
            switch (area.oper) {
                case OPEN -> {
                    // OPEN INPUT TRNX-FILE
                    closeQuietly();
                    try {
                        stream = provider.openSequential();
                        cursor = stream.iterator();
                        lastRc = RC_OK;
                    } catch (RuntimeException e) {
                        lastRc = "12";
                    }
                }
                case READ -> {
                    // READ TRNX-FILE INTO LK-M03B-FLDT
                    if (cursor == null) {
                        lastRc = RC_NOT_OPEN;
                    } else if (cursor.hasNext()) {
                        copyToFldt(area, cursor.next().encode());
                        lastRc = RC_OK;
                    } else {
                        lastRc = RC_EOF;
                    }
                }
                case CLOSE -> {
                    closeQuietly();
                    lastRc = RC_OK;
                }
                default -> lastRc = RC_UNSUPPORTED;
            }
            // MOVE TRNXFILE-STATUS TO LK-M03B-RC
            area.rc = lastRc;
        }

        private void closeQuietly() {
            if (stream != null) {
                try { stream.close(); } catch (RuntimeException ignored) { /* swallow */ }
            }
            stream = null;
            cursor = null;
        }
    }

    // ------------------------------------------------------------ XREFFILE

    /** Mirror of paragraph {@code 2000-XREFFILE-PROC}. */
    private static final class XrefFileService {
        private final CardXrefRepository repo;
        private Stream<CardXrefRecord> stream;
        private Iterator<CardXrefRecord> cursor;
        private String lastRc = RC_OK;

        XrefFileService(CardXrefRepository repo) { this.repo = repo; }

        void process(M03BArea area) {
            switch (area.oper) {
                case OPEN -> {
                    closeQuietly();
                    try {
                        stream = repo.streamSequential();
                        cursor = stream.iterator();
                        lastRc = RC_OK;
                    } catch (RuntimeException e) {
                        lastRc = "12";
                    }
                }
                case READ -> {
                    if (cursor == null) {
                        lastRc = RC_NOT_OPEN;
                    } else if (cursor.hasNext()) {
                        copyToFldt(area, cursor.next().encode());
                        lastRc = RC_OK;
                    } else {
                        lastRc = RC_EOF;
                    }
                }
                case CLOSE -> {
                    closeQuietly();
                    lastRc = RC_OK;
                }
                default -> lastRc = RC_UNSUPPORTED;
            }
            area.rc = lastRc;
        }

        private void closeQuietly() {
            if (stream != null) {
                try { stream.close(); } catch (RuntimeException ignored) { /* swallow */ }
            }
            stream = null;
            cursor = null;
        }
    }

    // ------------------------------------------------------------ CUSTFILE

    /** Mirror of paragraph {@code 3000-CUSTFILE-PROC}. */
    private static final class CustFileService {
        private final CustomerRepository repo;
        private String lastRc = RC_OK;

        CustFileService(CustomerRepository repo) { this.repo = repo; }

        void process(M03BArea area) {
            switch (area.oper) {
                case OPEN -> lastRc = RC_OK;  // No-op for random-access ports
                case READ_K -> {
                    // MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-CUST-ID
                    String keyStr = sliceKey(area).trim();
                    long custId;
                    try {
                        custId = Long.parseLong(keyStr);
                    } catch (NumberFormatException e) {
                        lastRc = RC_NOT_FOUND;
                        area.rc = lastRc;
                        return;
                    }
                    Optional<CustomerRecord> rec = repo.findById(custId);
                    if (rec.isPresent()) {
                        copyToFldt(area, rec.get().encode());
                        lastRc = RC_OK;
                    } else {
                        lastRc = RC_NOT_FOUND;
                    }
                }
                case CLOSE -> lastRc = RC_OK;
                default -> lastRc = RC_UNSUPPORTED;
            }
            area.rc = lastRc;
        }
    }

    // ------------------------------------------------------------ ACCTFILE

    /** Mirror of paragraph {@code 4000-ACCTFILE-PROC}. */
    private static final class AcctFileService {
        private final AccountRepository repo;
        private String lastRc = RC_OK;

        AcctFileService(AccountRepository repo) { this.repo = repo; }

        void process(M03BArea area) {
            switch (area.oper) {
                case OPEN -> lastRc = RC_OK;
                case READ_K -> {
                    String keyStr = sliceKey(area).trim();
                    long acctId;
                    try {
                        acctId = Long.parseLong(keyStr);
                    } catch (NumberFormatException e) {
                        lastRc = RC_NOT_FOUND;
                        area.rc = lastRc;
                        return;
                    }
                    Optional<AccountRecord> rec = repo.findById(acctId);
                    if (rec.isPresent()) {
                        copyToFldt(area, rec.get().encode());
                        lastRc = RC_OK;
                    } else {
                        lastRc = RC_NOT_FOUND;
                    }
                }
                case CLOSE -> lastRc = RC_OK;
                default -> lastRc = RC_UNSUPPORTED;
            }
            area.rc = lastRc;
        }
    }

    // ------------------------------------------------------------ helpers

    private static String sliceKey(M03BArea area) {
        if (area.key == null || area.keyLn <= 0) {
            return "";
        }
        int len = Math.min(area.keyLn, area.key.length());
        return area.key.substring(0, len);
    }

    private static void copyToFldt(M03BArea area, byte[] payload) {
        if (area.fldt == null || area.fldt.length < payload.length) {
            area.fldt = new byte[Math.max(1000, payload.length)];
        }
        // Clear area first (mirror INITIALIZE LK-M03B-FLDT semantics)
        java.util.Arrays.fill(area.fldt, (byte) ' ');
        System.arraycopy(payload, 0, area.fldt, 0, payload.length);
    }
}
