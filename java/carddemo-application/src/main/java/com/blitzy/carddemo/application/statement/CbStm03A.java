/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.statement;

import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.record.AccountRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.CustomerRecord;
import com.blitzy.carddemo.domain.record.TrnxRecord;
import com.blitzy.carddemo.domain.util.Decimals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Java translation of the {@code CBSTM03A} COBOL batch program at
 * {@code app/cbl/CBSTM03A.CBL} ("Statement generation program").
 *
 * <h2>Program purpose</h2>
 * <p>Reads {@code XREFFILE}, {@code CUSTFILE}, {@code ACCTFILE}, and
 * {@code TRNXFILE} (all via the {@link CbStm03B} file-services subroutine)
 * and emits two output streams in lock-step:
 * <ul>
 *   <li>{@code STMTFILE} — 80-byte fixed-width plain-text statement</li>
 *   <li>{@code HTMLFILE} — 100-byte fixed-width HTML page (concatenated
 *       into one document by external post-processing)</li>
 * </ul>
 *
 * <h2>Key paragraphs translated</h2>
 * <ul>
 *   <li>{@code PROCEDURE DIVISION} preamble — z/OS TIOT/TCB/PSA control-block
 *       introspection ("Running JCL : ... Step ...") translated to a
 *       logical equivalent reading JVM system properties / environment
 *       (see {@link #emitJobIntrospection()}). Per AAP &sect;0.7.1 the
 *       observable side effect (DISPLAY output) is preserved verbatim.</li>
 *   <li>{@code 0000-START EVALUATE WS-FL-DD} dispatcher — translated to a
 *       direct state-machine {@code switch} loop in {@link #run()} that
 *       replaces the COBOL {@code ALTER ... TO PROCEED TO} construct
 *       (which mutates a GO TO target at runtime).</li>
 *   <li>{@code 1000-MAINLINE} — main per-card loop reading XREF -&gt; CUST
 *       -&gt; ACCT -&gt; emit statement -&gt; iterate TRNX rows.</li>
 *   <li>{@code 1000-XREFFILE-GET-NEXT, 2000-CUSTFILE-GET, 3000-ACCTFILE-GET,
 *       4000-TRNXFILE-GET, 5000-CREATE-STATEMENT, 5100-WRITE-HTML-HEADER,
 *       5200-WRITE-HTML-NMADBS, 6000-WRITE-TRANS, 8100-/8200-/8300-/8400-
 *       FILE-OPEN, 8500-READTRNX-READ, 9100-/9200-/9300-/9400-FILE-CLOSE,
 *       9999-ABEND-PROGRAM} — translated to private methods named after the
 *       COBOL paragraph for traceability.</li>
 * </ul>
 *
 * <h2>Anomalies preserved verbatim (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><b>z/OS control-block introspection</b> — COBOL inspects PSA/TCB/TIOT
 *       to extract JOB name, STEP name, and DD list. Java cannot read mainframe
 *       UCBs; the translation reads JVM system properties
 *       {@code carddemo.job.name}, {@code carddemo.job.step}, and
 *       {@code carddemo.job.ddlist} (comma-separated) and emits identical
 *       {@code "Running JCL : ... Step ..."} and {@code ": ... -- valid UCB"}
 *       DISPLAY lines via SLF4J. See MIGRATION_NOTES.md.</li>
 *   <li><b>{@code ALTER ... TO PROCEED TO}</b> — COBOL legacy GO TO
 *       mutation translated to a direct state-machine dispatch.</li>
 *   <li><b>51&times;10 transaction matrix</b> — {@code WS-CARD-TBL OCCURS 51 TIMES}
 *       with {@code WS-TRAN-TBL OCCURS 10 TIMES} preserved as
 *       {@link CardSlot} list with bounded sizes.</li>
 *   <li><b>HTML output</b> — {@code FD-HTMLFILE-REC PIC X(100)} 100-byte
 *       padded lines; literal HTML tags preserved verbatim including the
 *       {@code "Bank of XYZ"}, {@code "410 Terry Ave N"}, and
 *       {@code "Seattle WA 99999"} stub addresses.</li>
 * </ul>
 *
 * <h2>I/O contract</h2>
 * <p>The COBOL {@code STMT-FILE} uses {@code RECORDING MODE V} (variable),
 * but every WRITE comes from a fixed 80-byte {@code STATEMENT-LINES} group.
 * The Java translation writes 80-byte lines terminated with a single
 * {@code \n} (matching the standard fixture format used by the golden-record
 * harness). Likewise {@code HTML-FILE} writes 100-byte lines. Monetary fields
 * use COBOL edit masks ({@code PIC 9(9).99-} for current balance, {@code PIC
 * Z(9).99-} for transaction amounts), all reproduced exactly per
 * AAP &sect;0.6.5 byte-for-byte fidelity.
 *
 * @see CbStm03B
 */
@CobolProgram(
        value = "CBSTM03A",
        sourcePath = "app/cbl/CBSTM03A.CBL",
        notes = "Statement generator; dual TEXT (PIC X(80)) + HTML (PIC X(100)) output; "
                + "calls CBSTM03B for file I/O; preserves z/OS control-block introspection "
                + "as logical equivalent and ALTER...TO PROCEED TO as state-machine dispatch per AAP §0.7.1"
)
public final class CbStm03A {

    private static final Logger log = LoggerFactory.getLogger(CbStm03A.class);

    public static final String PROGRAM_ID = "CBSTM03A";

    /** Length of one {@code STMT-FILE} record (PIC X(80)). */
    public static final int STMT_LINE_LENGTH = 80;
    /** Length of one {@code HTML-FILE} record (PIC X(100)). */
    public static final int HTML_LINE_LENGTH = 100;

    /** Maximum number of distinct cards per TRNX load (WS-CARD-TBL OCCURS 51). */
    public static final int MAX_CARDS = 51;
    /** Maximum number of transactions per card (WS-TRAN-TBL OCCURS 10). */
    public static final int MAX_TRANS_PER_CARD = 10;

    /** Scale for monetary fields (COBOL PIC S9(9)V99). */
    public static final int MONETARY_SCALE = 2;

    /**
     * State-machine states corresponding to COBOL {@code WS-FL-DD} flag
     * values. Replaces the {@code ALTER ... TO PROCEED TO} dynamic GO TO
     * mutation with a straightforward {@code switch} dispatch.
     */
    private enum DispatchState {
        TRNXFILE, XREFFILE, CUSTFILE, ACCTFILE, READTRNX, MAINLINE, DONE
    }

    // -- Dependencies (injected) ------------------------------------------

    private final CbStm03B cbStm03B;
    private final Path stmtFilePath;
    private final Path htmlFilePath;

    // -- WORKING-STORAGE mirrors ------------------------------------------

    /** Mirror of COBOL {@code WS-M03B-AREA}. */
    private final CbStm03B.M03BArea ws = new CbStm03B.M03BArea();

    /** Mirror of {@code WS-FL-DD} (initial value 'TRNXFILE'). */
    private DispatchState wsFlDd = DispatchState.TRNXFILE;

    /** Mirror of {@code END-OF-FILE}. */
    private boolean endOfFile = false;

    /** Mirror of {@code WS-SAVE-CARD}. */
    private String wsSaveCard = "";

    /** Mirror of {@code CR-CNT, TR-CNT, CR-JMP, TR-JMP} (PIC S9(4) COMP). */
    private int crCnt = 0;
    private int trCnt = 0;
    private int crJmp = 0;
    private int trJmp = 0;

    /** Mirror of {@code WS-TOTAL-AMT} (PIC S9(9)V99 COMP-3). */
    private BigDecimal wsTotalAmt = BigDecimal.ZERO.setScale(MONETARY_SCALE, RoundingMode.DOWN);

    /** Mirror of {@code WS-TRN-AMT} (intermediate display value). */
    private BigDecimal wsTrnAmt = BigDecimal.ZERO.setScale(MONETARY_SCALE, RoundingMode.DOWN);

    /**
     * Mirror of WS-TRNX-TABLE: a 51-slot card table where each slot holds
     * up to 10 transactions ({@code WS-TRAN-NUM PIC X(16) + WS-TRAN-REST
     * PIC X(318)}). The COBOL FIXED size is preserved by validation in
     * {@link #appendTransaction(TrnxRecord)}.
     */
    private final List<CardSlot> wsTrnxTable = new ArrayList<>(MAX_CARDS);

    /** Mirror of {@code XREF-...} layout once {@link #ws} carries a row. */
    private CardXrefRecord currentXref;
    /** Mirror of {@code CUSTOMER-RECORD}. */
    private CustomerRecord currentCustomer;
    /** Mirror of {@code ACCOUNT-RECORD}. */
    private AccountRecord currentAccount;
    /** Mirror of {@code TRNX-RECORD} most-recently read from CBSTM03B. */
    private TrnxRecord currentTrnx;

    /** Collected output lines (80-byte STMTFILE). */
    private final List<String> stmtLines = new ArrayList<>();
    /** Collected output lines (100-byte HTMLFILE). */
    private final List<String> htmlLines = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param cbStm03B     the file-services subroutine
     * @param stmtFilePath path to the STMTFILE output
     * @param htmlFilePath path to the HTMLFILE output
     */
    public CbStm03A(CbStm03B cbStm03B, Path stmtFilePath, Path htmlFilePath) {
        this.cbStm03B = Objects.requireNonNull(cbStm03B, "cbStm03B");
        this.stmtFilePath = Objects.requireNonNull(stmtFilePath, "stmtFilePath");
        this.htmlFilePath = Objects.requireNonNull(htmlFilePath, "htmlFilePath");
    }

    /**
     * Entry point — mirrors the COBOL PROCEDURE DIVISION.
     *
     * <p>The COBOL flow is:
     * <ol>
     *   <li>Inspect TIOT/TCB/PSA, DISPLAY job name + step + DD names</li>
     *   <li>OPEN OUTPUT STMT-FILE HTML-FILE</li>
     *   <li>INITIALIZE the transaction matrix</li>
     *   <li>Enter the 0000-START state-machine loop</li>
     *   <li>After EOF, close all files via 9100-9400 paragraphs</li>
     * </ol>
     *
     * @throws AbendException if any underlying file-service call fails (the
     *                        COBOL {@code 9999-ABEND-PROGRAM} equivalent)
     */
    public void run() {
        log.info("CBSTM03A: statement generation started");

        emitJobIntrospection();

        // OPEN OUTPUT STMT-FILE HTML-FILE
        stmtLines.clear();
        htmlLines.clear();
        // INITIALIZE WS-TRNX-TABLE WS-TRN-TBL-CNTR
        wsTrnxTable.clear();

        // 0000-START state-machine loop (replaces ALTER ... GO TO 0000-START)
        try {
            DispatchState state = wsFlDd;
            while (state != DispatchState.DONE) {
                state = switch (state) {
                    case TRNXFILE -> openTrnxFile();
                    case XREFFILE -> openXrefFile();
                    case CUSTFILE -> openCustFile();
                    case ACCTFILE -> openAcctFile();
                    case READTRNX -> readTrnxRecord();
                    case MAINLINE -> mainline();
                    case DONE -> DispatchState.DONE;
                };
            }
        } catch (AbendException abend) {
            // 9999-ABEND-PROGRAM equivalent — propagate
            throw abend;
        } catch (RuntimeException re) {
            abendProgram(re);
        }

        // CLOSE STMT-FILE HTML-FILE  (CBSTM03B 9100-9400 paragraphs already
        // closed the input files by this point.)
        flushOutputs();
        log.info("CBSTM03A: statement generation complete; wrote {} STMT lines, {} HTML lines",
                stmtLines.size(), htmlLines.size());
    }

    // ====================================================================
    // z/OS CONTROL-BLOCK INTROSPECTION (translated to logical equivalent)
    // ====================================================================

    /**
     * COBOL {@code PROCEDURE DIVISION} preamble — emits DISPLAY lines for
     * the JOB name, STEP name, and DD list extracted from the TIOT chain.
     *
     * <p>Java translation: reads JVM system properties (or defaults to
     * "CBSTM03A-JOB"/"STEP01"/empty DD list). The DISPLAY lines are
     * emitted via SLF4J INFO at the {@code com.blitzy.carddemo} category
     * to preserve the observable side effect.
     *
     * <p>Mirrors AAP &sect;0.7.1 "translate faithfully and flag" — the
     * legacy z/OS introspection has no Java equivalent, so the translation
     * provides a comparable diagnostic surface without simulating mainframe
     * memory layout. See {@code MIGRATION_NOTES.md} &sect;1.4.x.
     */
    private void emitJobIntrospection() {
        String jobName = pad(System.getProperty("carddemo.job.name", "CBSTM03A"), 8).substring(0, 8);
        String stepName = pad(System.getProperty("carddemo.job.step", "STEP01"), 8).substring(0, 8);
        String ddList = System.getProperty("carddemo.job.ddlist", "");

        log.info("Running JCL : {} Step {}", jobName, stepName);
        log.info("DD Names from TIOT: ");

        if (ddList.isEmpty()) {
            // COBOL preserved behavior — when TIOT is empty the loop still
            // emits at least one trailing line. We preserve that minimal
            // diagnostic without inventing DD entries.
            return;
        }
        for (String dd : ddList.split(",")) {
            String trimmed = dd.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String paddedDd = pad(trimmed, 8).substring(0, 8);
            // The COBOL uses "valid UCB" / "null UCB" branching. We
            // unconditionally report "valid UCB" because every Java-side
            // DD that the caller supplies is, by definition, addressable.
            log.info(": {} -- valid UCB", paddedDd);
        }
    }

    // ====================================================================
    // STATE-MACHINE PARAGRAPHS (replace COBOL ALTER ... TO PROCEED TO)
    // ====================================================================

    /**
     * Paragraph {@code 8100-TRNXFILE-OPEN}. Opens TRNXFILE, performs the
     * initial sequential read to prime the {@code WS-SAVE-CARD} guard,
     * then transitions to the READTRNX state to load the transaction
     * matrix.
     *
     * <p>The COBOL paragraph ends with {@code GO TO 0000-START} which —
     * combined with the {@code MOVE 'READTRNX' TO WS-FL-DD} — drives the
     * state machine. Our equivalent simply returns the next state.
     */
    private DispatchState openTrnxFile() {
        // MOVE 'TRNXFILE' TO WS-M03B-DD
        // SET M03B-OPEN TO TRUE
        ws.dd = CbStm03B.DD_TRNXFILE;
        ws.oper = CbStm03B.Oper.OPEN;
        ws.rc = CbStm03B.RC_OK;
        cbStm03B.invoke(ws);
        checkRc("ERROR OPENING TRNXFILE", ws.rc);

        // SET M03B-READ TO TRUE; MOVE SPACES TO WS-M03B-FLDT
        ws.oper = CbStm03B.Oper.READ;
        clearFldt();
        cbStm03B.invoke(ws);
        checkRc("ERROR READING TRNXFILE", ws.rc);

        // MOVE WS-M03B-FLDT TO TRNX-RECORD
        currentTrnx = decodeTrnxFromFldt();
        // MOVE TRNX-CARD-NUM TO WS-SAVE-CARD
        wsSaveCard = currentTrnx.key().trnxCardNum();
        // MOVE 1 TO CR-CNT; MOVE 0 TO TR-CNT
        crCnt = 1;
        trCnt = 0;

        // MOVE 'READTRNX' TO WS-FL-DD; GO TO 0000-START
        wsFlDd = DispatchState.READTRNX;
        return DispatchState.READTRNX;
    }

    /**
     * Paragraph {@code 8200-XREFFILE-OPEN}. Opens XREFFILE then transitions
     * to opening CUSTFILE.
     */
    private DispatchState openXrefFile() {
        ws.dd = CbStm03B.DD_XREFFILE;
        ws.oper = CbStm03B.Oper.OPEN;
        ws.rc = CbStm03B.RC_OK;
        cbStm03B.invoke(ws);
        checkRc("ERROR OPENING XREFFILE", ws.rc);

        wsFlDd = DispatchState.CUSTFILE;
        return DispatchState.CUSTFILE;
    }

    /**
     * Paragraph {@code 8300-CUSTFILE-OPEN}. Opens CUSTFILE then transitions
     * to opening ACCTFILE.
     */
    private DispatchState openCustFile() {
        ws.dd = CbStm03B.DD_CUSTFILE;
        ws.oper = CbStm03B.Oper.OPEN;
        ws.rc = CbStm03B.RC_OK;
        cbStm03B.invoke(ws);
        checkRc("ERROR OPENING CUSTFILE", ws.rc);

        wsFlDd = DispatchState.ACCTFILE;
        return DispatchState.ACCTFILE;
    }

    /**
     * Paragraph {@code 8400-ACCTFILE-OPEN}. Opens ACCTFILE then transitions
     * directly to the MAINLINE loop.
     */
    private DispatchState openAcctFile() {
        ws.dd = CbStm03B.DD_ACCTFILE;
        ws.oper = CbStm03B.Oper.OPEN;
        ws.rc = CbStm03B.RC_OK;
        cbStm03B.invoke(ws);
        checkRc("ERROR OPENING ACCTFILE", ws.rc);

        // GO TO 1000-MAINLINE
        return DispatchState.MAINLINE;
    }

    /**
     * Paragraph {@code 8500-READTRNX-READ}. Iterates the TRNX stream loading
     * the {@code WS-TRNX-TABLE} 51&times;10 matrix until EOF. Then sets
     * {@code WS-FL-DD = 'XREFFILE'} and returns to {@code 0000-START}.
     *
     * <p>COBOL logic preserved verbatim including the recursive
     * {@code GO TO 8500-READTRNX-READ} via a {@code while} loop.
     */
    private DispatchState readTrnxRecord() {
        // First call: trCnt/crCnt already set by 8100-TRNXFILE-OPEN for the
        // very first record. Process the current TRNX as the first matrix
        // entry, then loop reading further records.
        while (true) {
            // IF WS-SAVE-CARD = TRNX-CARD-NUM ADD 1 TO TR-CNT
            // ELSE MOVE TR-CNT TO WS-TRCT(CR-CNT) ; ADD 1 TO CR-CNT ; MOVE 1 TO TR-CNT
            String cardNum = currentTrnx.key().trnxCardNum();
            if (wsSaveCard.equals(cardNum)) {
                trCnt++;
            } else {
                // Persist the previous card's trCnt into wsTrnxTable.
                if (crCnt >= 1 && crCnt <= wsTrnxTable.size()) {
                    wsTrnxTable.get(crCnt - 1).setTrCt(trCnt);
                }
                crCnt++;
                trCnt = 1;
            }

            // MOVE TRNX-CARD-NUM TO WS-CARD-NUM(CR-CNT)
            // MOVE TRNX-ID TO WS-TRAN-NUM(CR-CNT, TR-CNT)
            // MOVE TRNX-REST TO WS-TRAN-REST(CR-CNT, TR-CNT)
            appendTransaction(currentTrnx);

            // MOVE TRNX-CARD-NUM TO WS-SAVE-CARD
            wsSaveCard = cardNum;

            // Next READ
            ws.dd = CbStm03B.DD_TRNXFILE;
            ws.oper = CbStm03B.Oper.READ;
            clearFldt();
            cbStm03B.invoke(ws);

            if (CbStm03B.RC_OK.equals(ws.rc)) {
                // GO TO 8500-READTRNX-READ
                currentTrnx = decodeTrnxFromFldt();
                continue;
            } else if (CbStm03B.RC_EOF.equals(ws.rc)) {
                // GO TO 8599-EXIT
                break;
            } else {
                log.error("ERROR READING TRNXFILE; RETURN CODE: {}", ws.rc);
                abendProgram(new IllegalStateException("ERROR READING TRNXFILE: " + ws.rc));
                return DispatchState.DONE;
            }
        }

        // 8599-EXIT: MOVE TR-CNT TO WS-TRCT(CR-CNT); MOVE 'XREFFILE' TO WS-FL-DD; GO TO 0000-START
        if (crCnt >= 1 && crCnt <= wsTrnxTable.size()) {
            wsTrnxTable.get(crCnt - 1).setTrCt(trCnt);
        }
        wsFlDd = DispatchState.XREFFILE;
        return DispatchState.XREFFILE;
    }

    // ====================================================================
    // 1000-MAINLINE — per-card statement emission loop
    // ====================================================================

    /**
     * Paragraph {@code 1000-MAINLINE}. Drives the per-card loop that reads
     * XREF / CUST / ACCT, emits the statement, and walks the transaction
     * matrix. Terminates by closing all 4 files via paragraphs
     * {@code 9100-9400}.
     */
    private DispatchState mainline() {
        endOfFile = false;
        while (!endOfFile) {
            xrefFileGetNext(); // 1000-XREFFILE-GET-NEXT
            if (endOfFile) {
                break;
            }
            custFileGet();    // 2000-CUSTFILE-GET
            acctFileGet();    // 3000-ACCTFILE-GET
            createStatement();    // 5000-CREATE-STATEMENT
            crJmp = 1;
            wsTotalAmt = BigDecimal.ZERO.setScale(MONETARY_SCALE, RoundingMode.DOWN);
            trnxFileGet();        // 4000-TRNXFILE-GET
        }

        // PERFORM 9100..9400-FILE-CLOSE
        closeTrnxFile();
        closeXrefFile();
        closeCustFile();
        closeAcctFile();

        return DispatchState.DONE;
    }

    /**
     * Paragraph {@code 1000-XREFFILE-GET-NEXT}. Sequential READ of XREFFILE.
     * On RC=00 decodes the FLDT into the current xref. On RC=10 sets EOF
     * flag. Any other RC aborts with ABEND.
     */
    private void xrefFileGetNext() {
        ws.dd = CbStm03B.DD_XREFFILE;
        ws.oper = CbStm03B.Oper.READ;
        ws.rc = CbStm03B.RC_OK;
        clearFldt();
        cbStm03B.invoke(ws);

        switch (ws.rc) {
            case CbStm03B.RC_OK -> currentXref = decodeXrefFromFldt();
            case CbStm03B.RC_EOF -> endOfFile = true;
            default -> {
                log.error("ERROR READING XREFFILE; RETURN CODE: {}", ws.rc);
                abendProgram(new IllegalStateException("ERROR READING XREFFILE: " + ws.rc));
            }
        }
    }

    /**
     * Paragraph {@code 2000-CUSTFILE-GET}. Random READ of CUSTFILE by
     * customer id. RC=00 decodes; RC=23 sets EOF (no customer found).
     */
    private void custFileGet() {
        ws.dd = CbStm03B.DD_CUSTFILE;
        ws.oper = CbStm03B.Oper.READ_K;
        ws.key = String.format("%09d", currentXref.xrefCustId());
        ws.keyLn = 9;
        ws.rc = CbStm03B.RC_OK;
        clearFldt();
        cbStm03B.invoke(ws);

        switch (ws.rc) {
            case CbStm03B.RC_OK -> currentCustomer = decodeCustomerFromFldt();
            case CbStm03B.RC_NOT_FOUND -> {
                log.warn("CUSTFILE key {} not found; ending statement cycle", ws.key);
                endOfFile = true;
            }
            default -> {
                log.error("ERROR READING CUSTFILE; RETURN CODE: {}", ws.rc);
                abendProgram(new IllegalStateException("ERROR READING CUSTFILE: " + ws.rc));
            }
        }
    }

    /**
     * Paragraph {@code 3000-ACCTFILE-GET}. Random READ of ACCTFILE by
     * account id. Same RC handling as CUSTFILE.
     */
    private void acctFileGet() {
        ws.dd = CbStm03B.DD_ACCTFILE;
        ws.oper = CbStm03B.Oper.READ_K;
        ws.key = String.format("%011d", currentXref.xrefAcctId());
        ws.keyLn = 11;
        ws.rc = CbStm03B.RC_OK;
        clearFldt();
        cbStm03B.invoke(ws);

        switch (ws.rc) {
            case CbStm03B.RC_OK -> currentAccount = decodeAccountFromFldt();
            case CbStm03B.RC_NOT_FOUND -> {
                log.warn("ACCTFILE key {} not found; ending statement cycle", ws.key);
                endOfFile = true;
            }
            default -> {
                log.error("ERROR READING ACCTFILE; RETURN CODE: {}", ws.rc);
                abendProgram(new IllegalStateException("ERROR READING ACCTFILE: " + ws.rc));
            }
        }
    }

    /**
     * Paragraph {@code 4000-TRNXFILE-GET}. Walks the in-memory matrix
     * locating the rows for the current card and writes them via
     * {@link #writeTrans(TrnxRecord)}.
     *
     * <p>COBOL: {@code PERFORM VARYING CR-JMP FROM 1 BY 1 UNTIL CR-JMP &gt; CR-CNT
     *           OR WS-CARD-NUM(CR-JMP) &gt; XREF-CARD-NUM} (sorted-ascending
     * short-circuit). Once the matching card slot is found, inner loop
     * walks 1..WS-TRCT(CR-JMP) emitting each transaction.
     */
    private void trnxFileGet() {
        String xrefCardNum = currentXref.xrefCardNum();
        for (crJmp = 1; crJmp <= crCnt && crJmp <= wsTrnxTable.size(); crJmp++) {
            CardSlot slot = wsTrnxTable.get(crJmp - 1);
            // Short-circuit: WS-CARD-NUM(CR-JMP) > XREF-CARD-NUM
            if (slot.cardNum.compareTo(xrefCardNum) > 0) {
                break;
            }
            if (slot.cardNum.equals(xrefCardNum)) {
                int trCt = slot.trCt;
                for (trJmp = 1; trJmp <= trCt; trJmp++) {
                    TrnxRecord trx = slot.transactions.get(trJmp - 1);
                    writeTrans(trx);
                    // ADD TRNX-AMT TO WS-TOTAL-AMT
                    wsTotalAmt = Decimals.add(wsTotalAmt, trx.trnxAmt(),
                            MONETARY_SCALE, RoundingMode.DOWN);
                }
            }
        }
        // MOVE WS-TOTAL-AMT TO WS-TRN-AMT; MOVE WS-TRN-AMT TO ST-TOTAL-TRAMT
        wsTrnAmt = wsTotalAmt;
        writeStmtLine(formatLine14A(wsTrnAmt));
        // WRITE FD-STMTFILE-REC FROM ST-LINE12 (dotted divider)
        writeStmtLine(STMT_LINE12);
        // WRITE FD-STMTFILE-REC FROM ST-LINE15 (END OF STATEMENT banner)
        writeStmtLine(STMT_LINE15);

        // HTML close-out (LTRS / L10 / L75 / LTDE / LTRE / L78 / L79 / L80)
        writeHtmlLine(HTML_LTRS);
        writeHtmlLine(HTML_L10);
        writeHtmlLine(HTML_L75);
        writeHtmlLine(HTML_LTDE);
        writeHtmlLine(HTML_LTRE);
        writeHtmlLine(HTML_L78);
        writeHtmlLine(HTML_L79);
        writeHtmlLine(HTML_L80);
    }

    // ====================================================================
    // 5000-CREATE-STATEMENT and 5100/5200/6000 HTML/TEXT writers
    // ====================================================================

    /**
     * Paragraph {@code 5000-CREATE-STATEMENT}. Builds the text statement
     * lines (ST-LINE0 ... ST-LINE13) and triggers the HTML header/name
     * sections.
     */
    private void createStatement() {
        // WRITE FD-STMTFILE-REC FROM ST-LINE0  (banner)
        writeStmtLine(STMT_LINE0);

        // PERFORM 5100-WRITE-HTML-HEADER
        writeHtmlHeader();

        // Build ST-NAME via STRING ... DELIMITED BY ' '
        String stName = buildStringSpaceDelimited(75,
                currentCustomer.custFirstName(),
                currentCustomer.custMiddleName(),
                currentCustomer.custLastName());

        // Build ST-ADD1/ST-ADD2 (direct MOVE)
        String stAdd1 = pad(currentCustomer.custAddrLine1(), 50);
        String stAdd2 = pad(currentCustomer.custAddrLine2(), 50);

        // Build ST-ADD3 via STRING (line3 + state + country + zip)
        String stAdd3 = buildStringSpaceDelimited(80,
                currentCustomer.custAddrLine3(),
                currentCustomer.custAddrStateCd(),
                currentCustomer.custAddrCountryCd(),
                currentCustomer.custAddrZip());

        // ST-ACCT-ID = ACCT-ID (PIC X(20), left-justified)
        String stAcctId = pad(Long.toString(currentAccount.acctId()), 20);

        // ST-CURR-BAL = ACCT-CURR-BAL formatted PIC 9(9).99-
        String stCurrBal = formatPic9DotN9(currentAccount.acctCurrBal());

        // ST-FICO-SCORE (3-digit fico left-justified in PIC X(20))
        String stFicoScore = pad(Integer.toString(currentCustomer.custFicoCreditScore()), 20);

        // Cache values for ST-LINE7..ST-LINE13 emission
        String stmtName = stName;
        String stmtAdd1 = stAdd1;
        String stmtAdd2 = stAdd2;
        String stmtAdd3 = stAdd3;
        String stmtAcctId = stAcctId;
        String stmtCurrBal = stCurrBal;
        String stmtFicoScore = stFicoScore;

        // PERFORM 5200-WRITE-HTML-NMADBS
        writeHtmlNameAddrBalScore(stmtName, stmtAdd1, stmtAdd2, stmtAdd3,
                stmtAcctId, stmtCurrBal, stmtFicoScore);

        // ST-LINE1 (name + 5 spaces) PIC X(80)
        writeStmtLine(pad(stmtName, 75) + "     ");
        // ST-LINE2
        writeStmtLine(pad(stmtAdd1, 50) + " ".repeat(30));
        // ST-LINE3
        writeStmtLine(pad(stmtAdd2, 50) + " ".repeat(30));
        // ST-LINE4
        writeStmtLine(pad(stmtAdd3, 80));
        // ST-LINE5 (separator dashes)
        writeStmtLine(STMT_LINE5);
        // ST-LINE6 (Basic Details title)
        writeStmtLine(STMT_LINE6);
        // ST-LINE5 (separator dashes again, per COBOL flow)
        writeStmtLine(STMT_LINE5);
        // ST-LINE7 (Account ID line)
        writeStmtLine(STMT_LINE7_PREFIX + pad(stmtAcctId, 20) + " ".repeat(40));
        // ST-LINE8 (Current Balance line: 20-char label + 12-char money + 7 spaces + 40 spaces)
        writeStmtLine(STMT_LINE8_PREFIX + pad(stmtCurrBal, 12) + " ".repeat(7) + " ".repeat(40));
        // ST-LINE9 (FICO Score line)
        writeStmtLine(STMT_LINE9_PREFIX + pad(stmtFicoScore, 20) + " ".repeat(40));
        // ST-LINE10 (separator dashes)
        writeStmtLine(STMT_LINE10);
        // ST-LINE11 (TRANSACTION SUMMARY)
        writeStmtLine(STMT_LINE11);
        // ST-LINE12 (separator dashes)
        writeStmtLine(STMT_LINE12);
        // ST-LINE13 (column headers)
        writeStmtLine(STMT_LINE13);
        // ST-LINE12 again (separator under headers)
        writeStmtLine(STMT_LINE12);
    }

    /**
     * Paragraph {@code 5100-WRITE-HTML-HEADER}. Writes HTML lines L01..L22-35
     * (the entire fixed HTML preamble including the embedded account number
     * in L11 and the bank address in L15-L18).
     */
    private void writeHtmlHeader() {
        writeHtmlLine(HTML_L01);
        writeHtmlLine(HTML_L02);
        writeHtmlLine(HTML_L03);
        writeHtmlLine(HTML_L04);
        writeHtmlLine(HTML_L05);
        writeHtmlLine(HTML_L06);
        writeHtmlLine(HTML_L07);
        writeHtmlLine(HTML_L08);
        writeHtmlLine(HTML_LTRS);
        writeHtmlLine(HTML_L10);

        // L11 with the embedded account id in L11-ACCT (PIC X(20))
        String acctId = pad(Long.toString(currentAccount.acctId()), 20);
        String l11Line = HTML_L11_PREFIX + acctId + HTML_L11_SUFFIX;
        writeHtmlLine(l11Line);

        writeHtmlLine(HTML_LTDE);
        writeHtmlLine(HTML_LTRE);
        writeHtmlLine(HTML_LTRS);
        writeHtmlLine(HTML_L15);
        writeHtmlLine(HTML_L16);
        writeHtmlLine(HTML_L17);
        writeHtmlLine(HTML_L18);
        writeHtmlLine(HTML_LTDE);
        writeHtmlLine(HTML_LTRE);
        writeHtmlLine(HTML_LTRS);
        writeHtmlLine(HTML_L22_35);
    }

    /**
     * Paragraph {@code 5200-WRITE-HTML-NMADBS}. Writes the HTML section
     * containing the customer name, address, account id, current balance,
     * and FICO score.
     */
    private void writeHtmlNameAddrBalScore(String stName, String stAdd1, String stAdd2, String stAdd3,
                                           String stAcctId, String stCurrBal, String stFicoScore) {
        // STRING '<p style="font-size:16px">' '* L23-NAME '  ' '* ' '</p>' '*'
        String l23Trimmed = trimTrailingSpaces(stName);
        writeHtmlLine("<p style=\"font-size:16px\">" + l23Trimmed + "  </p>");

        // Three address lines as <p>...</p>
        writeHtmlLine("<p>" + trimTrailingSpaces(stAdd1) + "  </p>");
        writeHtmlLine("<p>" + trimTrailingSpaces(stAdd2) + "  </p>");
        writeHtmlLine("<p>" + trimTrailingSpaces(stAdd3) + "  </p>");

        // Standard close-out lines
        writeHtmlLine(HTML_LTDE);
        writeHtmlLine(HTML_LTRE);
        writeHtmlLine(HTML_LTRS);
        writeHtmlLine(HTML_L30_42);
        writeHtmlLine(HTML_L31);
        writeHtmlLine(HTML_LTDE);
        writeHtmlLine(HTML_LTRE);
        writeHtmlLine(HTML_LTRS);
        writeHtmlLine(HTML_L22_35);

        // Account / Balance / FICO trio
        writeHtmlLine("<p>Account ID         : " + trimTrailingSpaces(stAcctId) + "</p>");
        writeHtmlLine("<p>Current Balance    : " + trimTrailingSpaces(stCurrBal) + "</p>");
        writeHtmlLine("<p>FICO Score         : " + trimTrailingSpaces(stFicoScore) + "</p>");

        writeHtmlLine(HTML_LTDE);
        writeHtmlLine(HTML_LTRE);
        writeHtmlLine(HTML_LTRS);
        writeHtmlLine(HTML_L30_42);
        writeHtmlLine(HTML_L43);
        writeHtmlLine(HTML_LTDE);
        writeHtmlLine(HTML_LTRE);
        writeHtmlLine(HTML_LTRS);
        writeHtmlLine(HTML_L47);
        writeHtmlLine(HTML_L48);
        writeHtmlLine(HTML_LTDE);
        writeHtmlLine(HTML_L50);
        writeHtmlLine(HTML_L51);
        writeHtmlLine(HTML_LTDE);
        writeHtmlLine(HTML_L53);
        writeHtmlLine(HTML_L54);
        writeHtmlLine(HTML_LTDE);
        writeHtmlLine(HTML_LTRE);
    }

    /**
     * Paragraph {@code 6000-WRITE-TRANS}. Emits one TRNX row to both
     * STMTFILE (ST-LINE14) and HTMLFILE (3 cells: tran id, description,
     * amount).
     */
    private void writeTrans(TrnxRecord trx) {
        // MOVE TRNX-ID TO ST-TRANID  (PIC X(16))
        // MOVE TRNX-DESC TO ST-TRANDT (PIC X(49))
        // MOVE TRNX-AMT TO ST-TRANAMT (PIC Z(9).99-)
        String stTranId = pad(trx.key().trnxId(), 16);
        String stTranDt = pad(trx.trnxDesc(), 49);
        String stTranAmt = formatPicZN9DotN9(trx.trnxAmt());

        // ST-LINE14 = ST-TRANID(16) + ' '(1) + ST-TRANDT(49) + '$'(1) + ST-TRANAMT(13) = 80
        writeStmtLine(stTranId + " " + stTranDt + "$" + pad(stTranAmt, 13));

        // HTML row
        writeHtmlLine(HTML_LTRS);
        writeHtmlLine(HTML_L58);
        writeHtmlLine("<p>" + trimTrailingSpaces(stTranId) + "</p>");
        writeHtmlLine(HTML_LTDE);

        writeHtmlLine(HTML_L61);
        writeHtmlLine("<p>" + trimTrailingSpaces(stTranDt) + "</p>");
        writeHtmlLine(HTML_LTDE);

        writeHtmlLine(HTML_L64);
        writeHtmlLine("<p>" + trimTrailingSpaces(stTranAmt) + "</p>");
        writeHtmlLine(HTML_LTDE);

        writeHtmlLine(HTML_LTRE);
    }

    // ====================================================================
    // 9100/9200/9300/9400 CLOSE paragraphs
    // ====================================================================

    private void closeTrnxFile() { closeFile(CbStm03B.DD_TRNXFILE, "TRNXFILE"); }
    private void closeXrefFile() { closeFile(CbStm03B.DD_XREFFILE, "XREFFILE"); }
    private void closeCustFile() { closeFile(CbStm03B.DD_CUSTFILE, "CUSTFILE"); }
    private void closeAcctFile() { closeFile(CbStm03B.DD_ACCTFILE, "ACCTFILE"); }

    private void closeFile(String dd, String label) {
        ws.dd = dd;
        ws.oper = CbStm03B.Oper.CLOSE;
        ws.rc = CbStm03B.RC_OK;
        cbStm03B.invoke(ws);
        checkRc("ERROR CLOSING " + label, ws.rc);
    }

    // ====================================================================
    // FLDT decoding helpers
    // ====================================================================

    /**
     * Decode the current {@link CbStm03B.M03BArea#fldt} buffer as a 350-byte
     * TRNX-RECORD via {@link TrnxRecord#parse(byte[])}.
     */
    private TrnxRecord decodeTrnxFromFldt() {
        byte[] chunk = new byte[TrnxRecord.RECORD_LENGTH];
        System.arraycopy(ws.fldt, 0, chunk, 0, chunk.length);
        return TrnxRecord.parse(chunk);
    }

    private CardXrefRecord decodeXrefFromFldt() {
        // Xref fixture rows are 36 bytes (per AAP §0.6.9 / MIGRATION_NOTES.md
        // anomaly). Use FIXTURE_LENGTH to align with the fixture format the
        // CBSTM03B file-services subroutine emits.
        byte[] chunk = new byte[CardXrefRecord.FIXTURE_LENGTH];
        System.arraycopy(ws.fldt, 0, chunk, 0, chunk.length);
        return CardXrefRecord.parse(chunk);
    }

    private CustomerRecord decodeCustomerFromFldt() {
        byte[] chunk = new byte[CustomerRecord.RECORD_LENGTH];
        System.arraycopy(ws.fldt, 0, chunk, 0, chunk.length);
        return CustomerRecord.parse(chunk);
    }

    private AccountRecord decodeAccountFromFldt() {
        byte[] chunk = new byte[AccountRecord.RECORD_LENGTH];
        System.arraycopy(ws.fldt, 0, chunk, 0, chunk.length);
        return AccountRecord.parse(chunk);
    }

    private void clearFldt() {
        java.util.Arrays.fill(ws.fldt, (byte) 0x20);
    }

    // ====================================================================
    // 51×10 TRANSACTION MATRIX
    // ====================================================================

    /**
     * Mirror of one row of {@code WS-CARD-TBL OCCURS 51 TIMES} containing
     * the {@code WS-CARD-NUM} key, the bounded {@code WS-TRAN-TBL OCCURS
     * 10 TIMES} list, and the {@code WS-TRCT} counter.
     */
    private static final class CardSlot {
        final String cardNum;
        final List<TrnxRecord> transactions = new ArrayList<>(MAX_TRANS_PER_CARD);
        int trCt = 0;

        CardSlot(String cardNum) {
            this.cardNum = Objects.requireNonNull(cardNum);
        }

        void setTrCt(int count) { this.trCt = count; }
    }

    private void appendTransaction(TrnxRecord trx) {
        String cardNum = trx.key().trnxCardNum();
        // Ensure crCnt-th slot exists; COBOL: MOVE TRNX-CARD-NUM TO WS-CARD-NUM(CR-CNT)
        while (wsTrnxTable.size() < crCnt) {
            if (wsTrnxTable.size() == MAX_CARDS) {
                log.warn("WS-CARD-TBL exceeded {} cards; preserving COBOL truncation behaviour", MAX_CARDS);
                return;
            }
            wsTrnxTable.add(new CardSlot(cardNum));
        }
        CardSlot slot = wsTrnxTable.get(crCnt - 1);
        if (slot.transactions.size() >= MAX_TRANS_PER_CARD) {
            log.warn("WS-TRAN-TBL exceeded {} transactions for card {}; truncating",
                    MAX_TRANS_PER_CARD, cardNum.length() >= 4
                            ? "************" + cardNum.substring(cardNum.length() - 4)
                            : cardNum);
            return;
        }
        slot.transactions.add(trx);
    }

    // ====================================================================
    // OUTPUT FLUSH
    // ====================================================================

    private void flushOutputs() {
        flushFile(stmtFilePath, stmtLines, STMT_LINE_LENGTH);
        flushFile(htmlFilePath, htmlLines, HTML_LINE_LENGTH);
    }

    private void flushFile(Path path, List<String> lines, int recordLength) {
        StringBuilder buf = new StringBuilder(lines.size() * (recordLength + 1));
        for (String l : lines) {
            buf.append(pad(l, recordLength), 0, recordLength);
            buf.append('\n');
        }
        try {
            Files.writeString(path, buf, StandardCharsets.ISO_8859_1,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            abendProgram(e);
        }
    }

    private void writeStmtLine(String line) {
        stmtLines.add(line);
    }

    private void writeHtmlLine(String line) {
        htmlLines.add(line);
    }

    // ====================================================================
    // FORMATTING HELPERS
    // ====================================================================

    /**
     * Builds an {@code INTO ...} target via COBOL {@code STRING ... DELIMITED
     * BY ' '} semantics: concatenates the trimmed (at first embedded space)
     * version of each argument separated by a single space.
     */
    private String buildStringSpaceDelimited(int targetLength, String... pieces) {
        StringBuilder sb = new StringBuilder();
        for (String piece : pieces) {
            String trimmedTo = piece == null ? "" : piece;
            int firstSpace = trimmedTo.indexOf(' ');
            String value = firstSpace >= 0 ? trimmedTo.substring(0, firstSpace) : trimmedTo;
            sb.append(value);
            sb.append(' ');
            if (sb.length() >= targetLength) {
                break;
            }
        }
        return pad(sb.toString(), targetLength).substring(0, targetLength);
    }

    /**
     * Format a {@link BigDecimal} as COBOL {@code PIC 9(9).99-} (trailing
     * sign): 12 characters, leading-zero-padded, with trailing '-' if
     * negative or blank if positive.
     */
    private String formatPic9DotN9(BigDecimal value) {
        BigDecimal scaled = value.setScale(MONETARY_SCALE, RoundingMode.DOWN);
        boolean negative = scaled.signum() < 0;
        BigDecimal abs = scaled.abs();
        String digits = String.format("%012.2f", abs); // 12 chars including dot
        // String.format with %012.2f for value 0.00 yields "000000000.00" (12 chars)
        // For COBOL PIC 9(9).99-, we need 9 digits + '.' + 2 digits + sign = 13 chars
        String formatted = String.format("%09d.%02d",
                abs.toBigInteger().longValueExact(),
                abs.remainder(BigDecimal.ONE).movePointRight(2).abs().intValueExact());
        return formatted + (negative ? "-" : " ");
    }

    /**
     * Format a {@link BigDecimal} as COBOL {@code PIC Z(9).99-} (leading
     * zeros blanked, trailing sign): 13 characters total.
     */
    private String formatPicZN9DotN9(BigDecimal value) {
        BigDecimal scaled = value.setScale(MONETARY_SCALE, RoundingMode.DOWN);
        boolean negative = scaled.signum() < 0;
        BigDecimal abs = scaled.abs();
        // 9 integer digits w/ leading spaces; '.' + 2 fractional digits; trailing sign
        long whole = abs.toBigInteger().longValueExact();
        String wholeStr = String.format("%9d", whole); // 9-char right-justified
        int frac = abs.remainder(BigDecimal.ONE).movePointRight(2).abs().intValueExact();
        return wholeStr + "." + String.format("%02d", frac) + (negative ? "-" : " ");
    }

    /**
     * Compose {@code ST-LINE14A} (totals line):
     * {@code 'Total EXP:'(10) + spaces(56) + '$'(1) + ST-TOTAL-TRAMT(13) = 80}.
     */
    private String formatLine14A(BigDecimal total) {
        return "Total EXP:" + " ".repeat(56) + "$" + pad(formatPicZN9DotN9(total), 13);
    }

    private static String pad(String s, int length) {
        if (s == null) {
            return " ".repeat(length);
        }
        if (s.length() >= length) {
            return s.substring(0, length);
        }
        StringBuilder sb = new StringBuilder(length);
        sb.append(s);
        while (sb.length() < length) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String trimTrailingSpaces(String s) {
        if (s == null) {
            return "";
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    // ====================================================================
    // ERROR HANDLING / ABEND
    // ====================================================================

    private void checkRc(String message, String rc) {
        if (CbStm03B.RC_OK.equals(rc) || "04".equals(rc)) {
            return;
        }
        log.error("{}; RETURN CODE: {}", message, rc);
        abendProgram(new IllegalStateException(message + " RC=" + rc));
    }

    /**
     * Mirror of paragraph {@code 9999-ABEND-PROGRAM}: DISPLAY 'ABENDING
     * PROGRAM' and {@code CALL 'CEE3ABD'}. Translation emits the DISPLAY
     * via SLF4J and throws {@link AbendException} carrying the COBOL
     * ABCODE 999.
     */
    private void abendProgram(Throwable cause) {
        log.error("ABENDING PROGRAM");
        throw new AbendException(999, PROGRAM_ID + " abend", cause);
    }

    // ====================================================================
    // CONSTANTS — STATEMENT (80-byte) and HTML (100-byte) line templates
    // ====================================================================

    // Banner: 31 '*' + 18 'START OF STATEMENT' + 31 '*'  = 80
    private static final String STMT_LINE0 =
            "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);

    // 80 '-'
    private static final String STMT_LINE5  = "-".repeat(80);
    // 33 spaces + "Basic Details"(14) + 33 spaces
    private static final String STMT_LINE6  = " ".repeat(33) + "Basic Details" + " ".repeat(34);
    // Prefix only — appended to with stAcctId(20) + 40 spaces
    private static final String STMT_LINE7_PREFIX = "Account ID         :";
    private static final String STMT_LINE8_PREFIX = "Current Balance    :";
    private static final String STMT_LINE9_PREFIX = "FICO Score         :";
    private static final String STMT_LINE10 = "-".repeat(80);
    // 30 spaces + "TRANSACTION SUMMARY "(20) + 30 spaces
    private static final String STMT_LINE11 = " ".repeat(30) + "TRANSACTION SUMMARY " + " ".repeat(30);
    private static final String STMT_LINE12 = "-".repeat(80);
    // 16 "Tran ID         " + 51 "Tran Details    " + 13 "  Tran Amount"
    private static final String STMT_LINE13 =
            "Tran ID         " + "Tran Details    " + " ".repeat(35) + "  Tran Amount";
    // Banner: 32 '*' + 16 'END OF STATEMENT' + 32 '*' = 80
    private static final String STMT_LINE15 =
            "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);

    // HTML constants — all preserved verbatim from CVCRD01Y / COSTM01 / CBSTM03A
    private static final String HTML_L01 = "<!DOCTYPE html>";
    private static final String HTML_L02 = "<html lang=\"en\">";
    private static final String HTML_L03 = "<head>";
    private static final String HTML_L04 = "<meta charset=\"utf-8\">";
    private static final String HTML_L05 = "<title>HTML Table Layout</title>";
    private static final String HTML_L06 = "</head>";
    private static final String HTML_L07 = "<body style=\"margin:0px;\">";
    private static final String HTML_L08 =
            "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">";
    private static final String HTML_LTRS = "<tr>";
    private static final String HTML_LTRE = "</tr>";
    private static final String HTML_LTDS = "<td>";
    private static final String HTML_LTDE = "</td>";
    private static final String HTML_L10 =
            "<td colspan=\"3\" style=\"padding:0px 5px; background-color:#1d1d96b3;\">";
    private static final String HTML_L11_PREFIX = "<h3>Statement for Account Number: ";
    private static final String HTML_L11_SUFFIX = "</h3>";
    private static final String HTML_L15 =
            "<td colspan=\"3\" style=\"padding:0px 5px; background-color:#FFAF33;\">";
    private static final String HTML_L16 = "<p style=\"font-size:16px\">Bank of XYZ</p>";
    private static final String HTML_L17 = "<p>410 Terry Ave N</p>";
    private static final String HTML_L18 = "<p>Seattle WA 99999</p>";
    private static final String HTML_L22_35 =
            "<td colspan=\"3\" style=\"padding:0px 5px; background-color:#f2f2f2;\">";
    private static final String HTML_L30_42 =
            "<td colspan=\"3\" style=\"padding:0px 5px; background-color:#33FFD1; text-align:center;\">";
    private static final String HTML_L31 = "<p style=\"font-size:16px\">Basic Details</p>";
    private static final String HTML_L43 = "<p style=\"font-size:16px\">Transaction Summary</p>";
    private static final String HTML_L47 =
            "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
    private static final String HTML_L48 = "<p style=\"font-size:16px\">Tran ID</p>";
    private static final String HTML_L50 =
            "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
    private static final String HTML_L51 = "<p style=\"font-size:16px\">Tran Details</p>";
    private static final String HTML_L53 =
            "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">";
    private static final String HTML_L54 = "<p style=\"font-size:16px\">Amount</p>";
    private static final String HTML_L58 =
            "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
    private static final String HTML_L61 =
            "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
    private static final String HTML_L64 =
            "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">";
    private static final String HTML_L75 = "<h3>End of Statement</h3>";
    private static final String HTML_L78 = "</table>";
    private static final String HTML_L79 = "</body>";
    private static final String HTML_L80 = "</html>";

    // Defensive: silence "unused" warnings for the LTDS constant which the
    // COBOL FD declares but never SETs — preserved for traceability.
    @SuppressWarnings("unused")
    private static final String[] UNUSED_HTML_TOKENS = { HTML_LTDS };
}
