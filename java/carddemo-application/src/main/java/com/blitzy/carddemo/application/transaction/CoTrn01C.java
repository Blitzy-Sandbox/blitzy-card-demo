/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.transaction;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.record.TranRecord;
import com.blitzy.carddemo.domain.status.PgmContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

/**
 * Java translation of the {@code COTRN01C} CICS online program at
 * {@code app/cbl/COTRN01C.cbl} ("View a Transaction from TRANSACT file").
 *
 * <h2>Program purpose</h2>
 * <p>Displays the transaction-view screen. Operator enters a transaction ID
 * and presses ENTER to fetch and display the transaction's fields. PF3
 * returns to the previous program; PF4 clears the screen; PF5 XCTLs to
 * COTRN00C (transaction list).
 *
 * <h2>Authoring metadata</h2>
 * <pre>
 *   PROGRAM-ID:  COTRN01C
 *   WS-PGMNAME:  'COTRN01C'
 *   WS-TRANID:   'CT01'
 * </pre>
 *
 * <h2>Paragraphs translated</h2>
 * <ul>
 *   <li>{@code MAIN-PARA}: EIBCALEN check, first-time vs re-enter dispatch
 *       with optional pre-selected transaction id (CDEMO-CT01-TRN-SELECTED).</li>
 *   <li>{@code PROCESS-ENTER-KEY}: TRNIDINI empty-check, READ-TRANSACT-FILE,
 *       populate all display fields.</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN}: XCTL to CDEMO-FROM-PROGRAM (default
 *       COMEN01C for PF3, COTRN00C for PF5).</li>
 *   <li>{@code READ-TRANSACT-FILE}: EXEC CICS READ DATASET('TRANSACT')
 *       UPDATE; translated to {@link TransactionRepository#findById(String)}.</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} / {@code INITIALIZE-ALL-FIELDS}: blank
 *       the input fields.</li>
 * </ul>
 *
 * <h2>Messages preserved verbatim (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li>"Tran ID can NOT be empty..." (triple-dot ellipsis)</li>
 *   <li>"Transaction ID NOT found..."</li>
 *   <li>"Unable to lookup Transaction..."</li>
 * </ul>
 *
 * <h2>Pre-selected transaction (CDEMO-CT01-TRN-SELECTED)</h2>
 * <p>When invoked from COTRN00C (transaction list) with a row selected,
 * the COBOL program automatically performs PROCESS-ENTER-KEY at first entry.
 * Since {@link CardDemoCommarea} does not yet model this commarea extension,
 * the Java translation exposes it as a separate parameter on the run() overload.
 *
 * <h2>Amount formatting</h2>
 * <p>{@code TRAN-AMT} (BigDecimal scale 2) is rendered with COBOL PIC
 * +99999999.99 — sign-and-magnitude with leading '+' or '-', then 8-digit
 * whole part, dot, 2-digit fraction. Result string is 12 chars.
 */
@CobolProgram(
        value = "COTRN01C",
        sourcePath = "app/cbl/COTRN01C.cbl",
        notes = "Transaction view; preselected-from-list path preserved via "
                + "separate parameter (no commarea extension)"
)
public final class CoTrn01C {

    private static final Logger log = LoggerFactory.getLogger(CoTrn01C.class);

    public static final String PROGRAM_ID = "COTRN01C";
    public static final String TRANSACTION_ID = "CT01";

    public static final String TITLE_01 = "AWS Mainframe Modernization";
    public static final String TITLE_02 = "CardDemo";
    public static final String MSG_INVALID_KEY = "Invalid key pressed.  Please see below ...";

    /** "Tran ID can NOT be empty..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_TRANID_EMPTY = "Tran ID can NOT be empty...";

    /** "Transaction ID NOT found..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_NOT_FOUND = "Transaction ID NOT found...";

    /** "Unable to lookup Transaction..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_LOOKUP_ERROR = "Unable to lookup Transaction...";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TransactionRepository transactions;
    private final ProgramRegistry programRegistry;

    public CoTrn01C(TransactionRepository transactions, ProgramRegistry programRegistry) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.programRegistry = Objects.requireNonNull(programRegistry, "programRegistry");
    }

    /**
     * Result wrapper for the COBOL EXEC CICS RETURN vs XCTL dual flow.
     */
    public record Result(CoTrn01Output output, CardDemoCommarea commarea, String xctlTo) {

        public static Result sendMap(CoTrn01Output output, CardDemoCommarea commarea) {
            return new Result(output, commarea, null);
        }

        public static Result xctl(String programId, CardDemoCommarea commarea) {
            return new Result(null, commarea, programId);
        }

        public boolean isSendMap() { return output != null; }
        public boolean isXctl() { return xctlTo != null; }
    }

    /**
     * Convenience overload with no preselected-transaction.
     */
    public Result run(CoTrn01Input input, CardDemoCommarea commarea) {
        return run(input, commarea, null);
    }

    /**
     * Entry point. Mirrors COBOL {@code MAIN-PARA}.
     *
     * @param input              BMS input record (may be null first-time)
     * @param commarea           inbound commarea
     * @param preselectedTranId  optional pre-populated transaction id (CDEMO-CT01-TRN-SELECTED)
     */
    public Result run(CoTrn01Input input, CardDemoCommarea commarea, String preselectedTranId) {
        if (commarea == null) {
            CardDemoCommarea outbound = withTarget(CardDemoCommarea.empty(), ProgramRegistry.CO_SGN_00C);
            return Result.xctl(ProgramRegistry.CO_SGN_00C, outbound);
        }

        if (!(commarea.generalInfo().pgmContext() instanceof PgmContext.Reenter)) {
            CardDemoCommarea reentered = withPgmContext(commarea, PgmContext.REENTER);
            if (preselectedTranId != null && !preselectedTranId.isBlank()) {
                CoTrn01Input prep = CoTrn01Input.empty();
                // Set trnIdIn to the preselected value
                CoTrn01Input withId = new CoTrn01Input(
                        prep.trnName(), prep.title01(), prep.curDate(), prep.pgmName(),
                        prep.title02(), prep.curTime(),
                        preselectedTranId,
                        prep.trnId(), prep.cardNum(), prep.tTypCd(), prep.tCatCd(),
                        prep.trnSrc(), prep.tDesc(), prep.trnAmt(), prep.tOrigDt(),
                        prep.tProcDt(), prep.mId(), prep.mName(), prep.mCity(), prep.mZip(),
                        prep.errMsg(),
                        CoTrn01Input.AidKey.ENTER);
                return processEnterKey(withId, reentered);
            }
            return Result.sendMap(buildScreen(CoTrn01Input.empty(), ""), reentered);
        }

        if (input == null) {
            return Result.sendMap(buildScreen(CoTrn01Input.empty(), MSG_INVALID_KEY), commarea);
        }

        return switch (input.aidKey()) {
            case ENTER -> processEnterKey(input, commarea);
            case PF3 -> returnToPrevScreen(commarea, /*defaultOnEmpty=*/ProgramRegistry.CO_MEN_01C);
            case PF4 -> clearCurrentScreen(commarea);
            case PF5 -> returnToTarget(commarea, ProgramRegistry.CO_TRN_00C);
            case CLEAR, PA1, PA2, PF1, PF2, PF6, PF7, PF8, PF9, PF10, PF11, PF12, OTHER
                    -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY), commarea);
        };
    }

    /**
     * Paragraph {@code PROCESS-ENTER-KEY}: TRNIDINI empty-check, READ-TRANSACT-FILE.
     */
    private Result processEnterKey(CoTrn01Input input, CardDemoCommarea commarea) {
        if (input.trnIdIn() == null || input.trnIdIn().isBlank()) {
            return Result.sendMap(buildScreen(input, MSG_TRANID_EMPTY), commarea);
        }

        try {
            Optional<TranRecord> tranOpt = transactions.findById(input.trnIdIn().trim());
            if (tranOpt.isEmpty()) {
                return Result.sendMap(buildScreen(input, MSG_NOT_FOUND), commarea);
            }
            // DFHRESP(NORMAL) — populate display fields
            TranRecord tran = tranOpt.get();
            CoTrn01Input populated = new CoTrn01Input(
                    input.trnName(), input.title01(), input.curDate(), input.pgmName(),
                    input.title02(), input.curTime(),
                    input.trnIdIn(),
                    // Transaction-detail display echoes
                    tran.tranId(),
                    tran.tranCardNum(),
                    tran.tranTypeCd(),
                    String.format("%04d", tran.tranCatCd()),
                    tran.tranSource(),
                    tran.tranDesc(),
                    formatTranAmount(tran.tranAmt()),
                    tran.tranOrigTs(),
                    tran.tranProcTs(),
                    String.format("%09d", tran.tranMerchantId()),
                    tran.tranMerchantName(),
                    tran.tranMerchantCity(),
                    tran.tranMerchantZip(),
                    input.errMsg(),
                    input.aidKey());
            return Result.sendMap(buildScreen(populated, ""), commarea);
        } catch (RuntimeException re) {
            log.warn("CoTrn01C: TRANSACT read failed for tranId={}", input.trnIdIn(), re);
            return Result.sendMap(buildScreen(input, MSG_LOOKUP_ERROR), commarea);
        }
    }

    /**
     * Paragraph {@code CLEAR-CURRENT-SCREEN}.
     */
    private Result clearCurrentScreen(CardDemoCommarea commarea) {
        return Result.sendMap(buildScreen(CoTrn01Input.empty(), ""), commarea);
    }

    /**
     * PF3 path: return-to-prev with fallback when CDEMO-FROM-PROGRAM is empty.
     */
    private Result returnToPrevScreen(CardDemoCommarea commarea, String defaultOnEmpty) {
        String fromProgram = commarea.generalInfo().fromProgram();
        String target = (fromProgram == null || fromProgram.isBlank())
                ? defaultOnEmpty
                : fromProgram.trim();
        return returnToTarget(commarea, target);
    }

    /**
     * PF5 path: XCTL to a fixed target ({@code COTRN00C}).
     */
    private Result returnToTarget(CardDemoCommarea commarea, String target) {
        CardDemoCommarea outbound = withTarget(commarea, target);
        log.info("CoTrn01C: XCTL to {}", target);
        CardDemoCommarea finalCommarea = outbound;
        if (programRegistry.isRegistered(target)) {
            finalCommarea = programRegistry.invoke(target, outbound);
        }
        return Result.xctl(target, finalCommarea);
    }

    /**
     * Builds the populated output record (paragraph {@code SEND-TRNVIEW-SCREEN}
     * + {@code POPULATE-HEADER-INFO}).
     */
    private CoTrn01Output buildScreen(CoTrn01Input input, String message) {
        CoTrn01Output.FieldColor color = (message == null || message.isBlank())
                ? CoTrn01Output.FieldColor.DEFAULT
                : CoTrn01Output.FieldColor.RED;
        return new CoTrn01Output(
                TITLE_01,
                TITLE_02,
                TRANSACTION_ID,
                PROGRAM_ID,
                todayDate(),
                nowTime(),
                input.trnIdIn(),
                input.trnId(),
                input.cardNum(),
                input.tTypCd(),
                input.tCatCd(),
                input.trnSrc(),
                input.tDesc(),
                input.trnAmt(),
                input.tOrigDt(),
                input.tProcDt(),
                input.mId(),
                input.mName(),
                input.mCity(),
                input.mZip(),
                message == null ? "" : message,
                color);
    }

    /**
     * Formats a {@link BigDecimal} amount as COBOL PIC +99999999.99
     * (12-char string: sign + 8-digit whole + dot + 2-digit fraction).
     */
    private static String formatTranAmount(BigDecimal amt) {
        if (amt == null) {
            return "+00000000.00";
        }
        BigDecimal scaled = amt.setScale(2, java.math.RoundingMode.UNNECESSARY);
        BigDecimal abs = scaled.abs();
        String sign = scaled.signum() < 0 ? "-" : "+";
        // Whole portion: zero-padded to 8 digits
        long wholePart = abs.toBigInteger().longValueExact();
        long fracPart = abs.subtract(new BigDecimal(wholePart)).movePointRight(2).longValueExact();
        return String.format("%s%08d.%02d", sign, wholePart, fracPart);
    }

    // -- helpers ----------------------------------------------------------

    private static CardDemoCommarea withTarget(CardDemoCommarea commarea, String toProgram) {
        CardDemoCommarea.GeneralInfo gi = commarea.generalInfo();
        CardDemoCommarea.GeneralInfo updated = new CardDemoCommarea.GeneralInfo(
                TRANSACTION_ID,
                PROGRAM_ID,
                gi.toTranid(),
                toProgram,
                gi.userId(),
                gi.userType(),
                PgmContext.ENTER);
        return commarea.withGeneralInfo(updated);
    }

    private static CardDemoCommarea withPgmContext(CardDemoCommarea commarea, PgmContext ctx) {
        CardDemoCommarea.GeneralInfo gi = commarea.generalInfo();
        CardDemoCommarea.GeneralInfo updated = new CardDemoCommarea.GeneralInfo(
                gi.fromTranid(),
                gi.fromProgram(),
                gi.toTranid(),
                gi.toProgram(),
                gi.userId(),
                gi.userType(),
                ctx);
        return commarea.withGeneralInfo(updated);
    }

    private static String todayDate() {
        return LocalDateTime.now().format(DATE_FORMATTER);
    }

    private static String nowTime() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
