/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.billpay;

import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.port.AccountRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.record.AccountRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.TranRecord;
import com.blitzy.carddemo.domain.status.PgmContext;
import com.blitzy.carddemo.domain.util.Decimals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Java translation of the {@code COBIL00C} CICS online program at
 * {@code app/cbl/COBIL00C.cbl} ("Bill Payment - Pay account balance in full").
 *
 * <h2>Program purpose</h2>
 * <p>Allows the operator to pay an account's balance in full. ENTER fetches
 * the account balance; with confirmation Y, a new transaction is written
 * (BILL PAYMENT - ONLINE) and the account balance is decremented to zero
 * (current balance minus paid amount, which is the entire current balance).
 *
 * <h2>Authoring metadata</h2>
 * <pre>
 *   PROGRAM-ID:  COBIL00C
 *   WS-PGMNAME:  'COBIL00C'
 *   WS-TRANID:   'CB00'
 * </pre>
 *
 * <h2>Paragraphs translated</h2>
 * <ul>
 *   <li>{@code MAIN-PARA}: EIBCALEN check, first-time vs re-enter dispatch,
 *       optional CDEMO-CB00-TRN-SELECTED pre-population.</li>
 *   <li>{@code PROCESS-ENTER-KEY}: ACTIDINI validation, CONFIRMI EVALUATE
 *       (Y/y, N/n, SPACES/LOW-VALUES, OTHER), READ-ACCTDAT-FILE,
 *       zero-balance guard, payment processing.</li>
 *   <li>{@code GET-CURRENT-TIMESTAMP}: ASKTIME + FORMATTIME → LocalDateTime
 *       formatted as "yyyy-MM-dd HH:mm:ss.SSSSSS" (truncated to 6 fractional
 *       seconds digits per AAP §0.6.4 timestamp truncation rule).</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN}: XCTL with CDEMO-FROM-PROGRAM fallback
 *       to {@code COMEN01C} (per COBOL line 124).</li>
 *   <li>{@code READ-ACCTDAT-FILE}: account lookup; "Account ID NOT found..." /
 *       "Unable to lookup Account...".</li>
 *   <li>{@code UPDATE-ACCTDAT-FILE}: account rewrite; success / NOTFND /
 *       "Unable to Update Account..."</li>
 *   <li>{@code READ-CXACAIX-FILE}: xref lookup by account id; "Account ID NOT
 *       found..." / "Unable to lookup XREF AIX file..."</li>
 *   <li>{@code STARTBR/READPREV/ENDBR-TRANSACT-FILE}: locate the highest
 *       existing TRAN-ID; on ENDFILE, treat as zero; increment by 1.</li>
 *   <li>{@code WRITE-TRANSACT-FILE}: persist the new transaction; success
 *       emits "Payment successful.  Your Transaction ID is..." (double-space
 *       preserved per AAP §0.7.1); DUPKEY/DUPREC → "Tran ID already exist..."
 *       (typo preserved); OTHER → "Unable to Add Bill pay Transaction..."</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} / {@code INITIALIZE-ALL-FIELDS}.</li>
 * </ul>
 *
 * <h2>Anomalies preserved verbatim (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>Double-space in success message</strong>: COBOL STRING
 *       construct concatenates "Payment successful. " (trailing space) and
 *       " Your Transaction ID is " (leading space) producing "Payment
 *       successful.  Your Transaction ID is..." (TWO spaces). Preserved verbatim.</li>
 *   <li><strong>"Tran ID already exist..." typo</strong> ("exist" not "exists").
 *       Preserved verbatim.</li>
 *   <li><strong>{@code TRAN-ID DELIMITED BY SPACE}</strong>: truncates the
 *       transaction id at the first space character (rare since the ID is
 *       a 16-digit numeric, but applied verbatim for fidelity).</li>
 *   <li><strong>{@code withNano(0)}</strong>: per AAP &sect;0.6.4 timestamp
 *       discipline, the timestamp emitted in the success message and stored
 *       in TRAN-ORIG-TS / TRAN-PROC-TS truncates fractional seconds to
 *       6-digit microsecond precision (matching DB2 TIMESTAMP(6) and the
 *       COBOL FORMATTIME output that has no sub-second precision).</li>
 * </ul>
 */
@CobolProgram(
        value = "COBIL00C",
        sourcePath = "app/cbl/COBIL00C.cbl",
        notes = "Bill payment. Double-space '. ' + ' Your' in success message "
                + "preserved verbatim per AAP §0.7.1. Timestamp truncation to "
                + "6 fractional digits (DB2 TIMESTAMP(6)) per AAP §0.6.4."
)
public final class CoBil00C {

    private static final Logger log = LoggerFactory.getLogger(CoBil00C.class);

    public static final String PROGRAM_ID = "COBIL00C";
    public static final String TRANSACTION_ID = "CB00";

    public static final String TITLE_01 = "AWS Mainframe Modernization";
    public static final String TITLE_02 = "CardDemo";
    public static final String MSG_INVALID_KEY = "Invalid key pressed.  Please see below ...";

    /** "Acct ID can NOT be empty..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_ACCT_EMPTY = "Acct ID can NOT be empty...";
    /** "Account ID NOT found..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_ACCT_NOT_FOUND = "Account ID NOT found...";
    /** "Unable to lookup Account..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_ACCT_LOOKUP_ERROR = "Unable to lookup Account...";
    /** "Unable to Update Account..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_ACCT_UPDATE_ERROR = "Unable to Update Account...";
    /** "You have nothing to pay..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";
    /** "Invalid value. Valid values are (Y/N)..." preserved verbatim. */
    public static final String MSG_INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)...";
    /** "Confirm to make a bill payment..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_CONFIRM_PAYMENT = "Confirm to make a bill payment...";
    /** "Unable to lookup XREF AIX file..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_XREF_LOOKUP_ERROR = "Unable to lookup XREF AIX file...";
    /** "Transaction ID NOT found..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_TRAN_NOT_FOUND = "Transaction ID NOT found...";
    /** "Unable to lookup Transaction..." preserved verbatim per AAP §0.7.1. */
    public static final String MSG_TRAN_LOOKUP_ERROR = "Unable to lookup Transaction...";
    /**
     * "Tran ID already exist..." (typo: "exist" not "exists") preserved
     * verbatim per AAP §0.7.1.
     */
    public static final String MSG_TRAN_DUPLICATE = "Tran ID already exist...";
    /** "Unable to Add Bill pay Transaction..." preserved verbatim. */
    public static final String MSG_TRAN_ADD_ERROR = "Unable to Add Bill pay Transaction...";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    /**
     * DB2 TIMESTAMP(6) format used to populate TRAN-ORIG-TS / TRAN-PROC-TS.
     * Matches COBOL {@code FORMATTIME} output with 6-digit fractional second
     * precision (mapping yyyy-MM-dd HH:mm:ss.SSSSSS).
     */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private static final BigDecimal ZERO_AMT = new BigDecimal("0.00");
    private static final int MONETARY_SCALE = 2;

    /** Fixed values from COBIL00C.cbl per the BILL PAYMENT transaction. */
    public static final String TRAN_TYPE_CD = "02";
    public static final int TRAN_CAT_CD = 2;
    public static final String TRAN_SOURCE = "POS TERM";
    public static final String TRAN_DESC = "BILL PAYMENT - ONLINE";
    public static final long MERCHANT_ID = 999999999L;
    public static final String MERCHANT_NAME = "BILL PAYMENT";
    public static final String MERCHANT_CITY = "N/A";
    public static final String MERCHANT_ZIP = "N/A";

    private final AccountRepository accounts;
    private final CardXrefRepository cardXref;
    private final TransactionRepository transactions;
    private final ProgramRegistry programRegistry;

    public CoBil00C(AccountRepository accounts,
                    CardXrefRepository cardXref,
                    TransactionRepository transactions,
                    ProgramRegistry programRegistry) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.cardXref = Objects.requireNonNull(cardXref, "cardXref");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.programRegistry = Objects.requireNonNull(programRegistry, "programRegistry");
    }

    /**
     * AID-key alias enum for the COBIL00C controller.
     *
     * <p>Translates {@code EVALUATE EIBAID} at lines 122-138 of
     * {@code COBIL00C.cbl}: ENTER, PF3 (back), PF4 (clear), OTHER (invalid).
     */
    public enum AidKey {
        /** {@code DFHENTER}: process the payment confirmation. */
        ENTER,
        /** {@code DFHPF3}: return to {@code CDEMO-FROM-PROGRAM} (default COMEN01C). */
        PF03_BACK,
        /** {@code DFHPF4}: clear the current screen. */
        PF04_CLEAR,
        /** {@code WHEN OTHER}: any unmapped AID key (invalid key error). */
        OTHER
    }

    public record Result(CoBil00Output output, CardDemoCommarea commarea, String xctlTo) {

        public static Result sendMap(CoBil00Output output, CardDemoCommarea commarea) {
            return new Result(output, commarea, null);
        }

        public static Result xctl(String programId, CardDemoCommarea commarea) {
            return new Result(null, commarea, programId);
        }

        public boolean isSendMap() { return output != null; }
        public boolean isXctl() { return xctlTo != null; }
    }

    /**
     * Convenience overload with no pre-selected transaction id.
     */
    public Result run(CoBil00Input input, AidKey aidKey, CardDemoCommarea commarea) {
        return run(input, aidKey, commarea, null);
    }

    /**
     * Entry point. Mirrors COBOL {@code MAIN-PARA}.
     *
     * @param input              BMS input record (may be null first-time)
     * @param aidKey             which AID key was pressed (passed alongside input)
     * @param commarea           inbound commarea
     * @param preselectedTranId  optional pre-populated transaction id
     *                           (CDEMO-CB00-TRN-SELECTED), used as initial ACTIDINI value
     */
    public Result run(CoBil00Input input, AidKey aidKey, CardDemoCommarea commarea,
                      String preselectedTranId) {
        if (commarea == null) {
            CardDemoCommarea outbound = withTarget(CardDemoCommarea.empty(), ProgramRegistry.CO_SGN_00C);
            return Result.xctl(ProgramRegistry.CO_SGN_00C, outbound);
        }

        if (!(commarea.generalInfo().pgmContext() instanceof PgmContext.Reenter)) {
            CardDemoCommarea reentered = withPgmContext(commarea, PgmContext.REENTER);
            if (preselectedTranId != null && !preselectedTranId.isBlank()) {
                CoBil00Input populated = CoBil00Input.empty().withAccountId(preselectedTranId);
                return processEnterKey(populated, reentered);
            }
            return Result.sendMap(buildScreen(CoBil00Input.empty(), "", CoBil00Output.FieldColor.DEFAULT), reentered);
        }

        if (input == null || aidKey == null) {
            return Result.sendMap(buildScreen(CoBil00Input.empty(), MSG_INVALID_KEY,
                                              CoBil00Output.FieldColor.RED), commarea);
        }

        return switch (aidKey) {
            case ENTER -> processEnterKey(input, commarea);
            case PF03_BACK -> {
                String fromProgram = commarea.generalInfo().fromProgram();
                String target = (fromProgram == null || fromProgram.isBlank())
                        ? ProgramRegistry.CO_MEN_01C
                        : fromProgram.trim();
                yield returnToTarget(commarea, target);
            }
            case PF04_CLEAR -> clearCurrentScreen(commarea);
            case OTHER -> Result.sendMap(buildScreen(input, MSG_INVALID_KEY,
                                                    CoBil00Output.FieldColor.RED), commarea);
        };
    }

    /**
     * Paragraph {@code PROCESS-ENTER-KEY}.
     */
    private Result processEnterKey(CoBil00Input input, CardDemoCommarea commarea) {
        // Validate account id is not empty
        if (input.accountId() == null || input.accountId().isBlank()) {
            return Result.sendMap(buildScreen(input, MSG_ACCT_EMPTY,
                                              CoBil00Output.FieldColor.RED), commarea);
        }

        long acctId;
        try {
            acctId = Long.parseLong(input.accountId().trim());
        } catch (NumberFormatException nfe) {
            return Result.sendMap(buildScreen(input, MSG_ACCT_NOT_FOUND,
                                              CoBil00Output.FieldColor.RED), commarea);
        }

        // CONFIRMI EVALUATE: Y/y (confirm), N/n (clear+err), SPACES/LOW-VALUES (read account), other (invalid)
        String confirm = input.confirmation() == null ? "" : input.confirmation();
        boolean confPay;
        if (confirm.equals("Y") || confirm.equals("y")) {
            confPay = true;
        } else if (confirm.equals("N") || confirm.equals("n")) {
            // PERFORM CLEAR-CURRENT-SCREEN, MOVE 'Y' TO WS-ERR-FLG (no error message visible)
            return clearCurrentScreen(commarea);
        } else if (confirm.isBlank()) {
            // SPACES / LOW-VALUES: proceed with READ-ACCTDAT-FILE; no payment.
            confPay = false;
        } else {
            return Result.sendMap(buildScreen(input, MSG_INVALID_CONFIRM,
                                              CoBil00Output.FieldColor.RED), commarea);
        }

        // READ-ACCTDAT-FILE
        Optional<AccountRecord> acctOpt;
        try {
            acctOpt = accounts.findById(acctId);
        } catch (RuntimeException re) {
            log.warn("CoBil00C: ACCTDAT read failed for acctId={}", acctId, re);
            return Result.sendMap(buildScreen(input, MSG_ACCT_LOOKUP_ERROR,
                                              CoBil00Output.FieldColor.RED), commarea);
        }
        if (acctOpt.isEmpty()) {
            return Result.sendMap(buildScreen(input, MSG_ACCT_NOT_FOUND,
                                              CoBil00Output.FieldColor.RED), commarea);
        }
        AccountRecord acct = acctOpt.get();

        // Populate current balance display
        BigDecimal currBal = acct.acctCurrBal();
        CoBil00Input withBalance = new CoBil00Input(
                input.transactionName(),
                input.title01(),
                input.currentDate(),
                input.programName(),
                input.title02(),
                input.currentTime(),
                input.accountId(),
                formatCurrentBalance(currBal),
                input.confirmation(),
                input.errorMessage());

        // Zero / negative balance guard
        if (currBal.compareTo(ZERO_AMT) <= 0) {
            return Result.sendMap(buildScreen(withBalance, MSG_NOTHING_TO_PAY,
                                              CoBil00Output.FieldColor.RED), commarea);
        }

        if (confPay) {
            return processPayment(withBalance, acct, commarea);
        } else {
            return Result.sendMap(buildScreen(withBalance, MSG_CONFIRM_PAYMENT,
                                              CoBil00Output.FieldColor.DEFAULT), commarea);
        }
    }

    /**
     * Paragraph cluster: READ-CXACAIX-FILE + STARTBR/READPREV/ENDBR-TRANSACT-FILE
     * + WRITE-TRANSACT-FILE + UPDATE-ACCTDAT-FILE (the CONF-PAY-YES branch).
     */
    private Result processPayment(CoBil00Input input, AccountRecord acct, CardDemoCommarea commarea) {
        long acctId = acct.acctId();

        // READ-CXACAIX-FILE — locate the card number for this account
        String cardNum;
        try (Stream<CardXrefRecord> xrefStream = cardXref.findByAccountId(acctId)) {
            Optional<CardXrefRecord> first = xrefStream.findFirst();
            if (first.isEmpty()) {
                return Result.sendMap(buildScreen(input, MSG_ACCT_NOT_FOUND,
                                                  CoBil00Output.FieldColor.RED), commarea);
            }
            cardNum = first.get().xrefCardNum();
        } catch (RuntimeException re) {
            log.warn("CoBil00C: CXACAIX read failed for acctId={}", acctId, re);
            return Result.sendMap(buildScreen(input, MSG_XREF_LOOKUP_ERROR,
                                              CoBil00Output.FieldColor.RED), commarea);
        }

        // STARTBR/READPREV/ENDBR-TRANSACT-FILE — find highest existing TRAN-ID
        long nextTranId;
        try {
            nextTranId = nextTransactionId();
        } catch (RuntimeException re) {
            log.warn("CoBil00C: TRANSACT browse failed", re);
            return Result.sendMap(buildScreen(input, MSG_TRAN_LOOKUP_ERROR,
                                              CoBil00Output.FieldColor.RED), commarea);
        }

        // Generate timestamp (AAP §0.6.4: 6-digit microsecond precision)
        LocalDateTime now = LocalDateTime.now().withNano((LocalDateTime.now().getNano() / 1000) * 1000);
        String timestamp = TIMESTAMP_FORMATTER.format(now);

        // Build TRAN-RECORD — INITIALIZE then MOVE per COBOL lines 209-227
        TranRecord newTran = new TranRecord(
                String.format("%016d", nextTranId),     // TRAN-ID PIC X(16)
                TRAN_TYPE_CD,
                TRAN_CAT_CD,
                TRAN_SOURCE,
                TRAN_DESC,
                acct.acctCurrBal().setScale(MONETARY_SCALE, RoundingMode.UNNECESSARY), // TRAN-AMT = ACCT-CURR-BAL
                MERCHANT_ID,
                MERCHANT_NAME,
                MERCHANT_CITY,
                MERCHANT_ZIP,
                cardNum,
                timestamp,                              // TRAN-ORIG-TS
                timestamp,                              // TRAN-PROC-TS
                new byte[TranRecord.LEN_FILLER]);

        // WRITE-TRANSACT-FILE
        try {
            transactions.append(newTran);
        } catch (IllegalStateException dup) {
            log.warn("CoBil00C: TRANSACT WRITE duplicate for tranId={}", nextTranId, dup);
            return Result.sendMap(buildScreen(input, MSG_TRAN_DUPLICATE,
                                              CoBil00Output.FieldColor.RED), commarea);
        } catch (RuntimeException re) {
            log.warn("CoBil00C: TRANSACT WRITE failed for tranId={}", nextTranId, re);
            return Result.sendMap(buildScreen(input, MSG_TRAN_ADD_ERROR,
                                              CoBil00Output.FieldColor.RED), commarea);
        }

        // COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT (zeroes the balance)
        BigDecimal newBal = Decimals.subtract(
                acct.acctCurrBal(),
                newTran.tranAmt(),
                MONETARY_SCALE,
                RoundingMode.HALF_EVEN);
        AccountRecord updatedAcct = new AccountRecord(
                acct.acctId(),
                acct.acctActiveStatus(),
                newBal,
                acct.acctCreditLimit(),
                acct.acctCashCreditLimit(),
                acct.acctOpenDate(),
                acct.acctExpiraionDate(),
                acct.acctReissueDate(),
                acct.acctCurrCycCredit(),
                acct.acctCurrCycDebit(),
                acct.acctAddrZip(),
                acct.acctGroupId(),
                acct.filler());

        // UPDATE-ACCTDAT-FILE
        try {
            accounts.save(updatedAcct);
        } catch (RuntimeException re) {
            log.warn("CoBil00C: ACCTDAT REWRITE failed for acctId={}", acctId, re);
            return Result.sendMap(buildScreen(input, MSG_ACCT_UPDATE_ERROR,
                                              CoBil00Output.FieldColor.RED), commarea);
        }

        // Success — INITIALIZE-ALL-FIELDS then STRING build
        // COBOL: 'Payment successful. '     DELIMITED BY SIZE
        //        ' Your Transaction ID is ' DELIMITED BY SIZE
        //        TRAN-ID                    DELIMITED BY SPACE
        //        '.'                        DELIMITED BY SIZE
        // The trailing space on 'Payment successful. ' + leading space on
        // ' Your Transaction ID is ' produces "Payment successful.  Your"
        // (double space). PRESERVED VERBATIM per AAP §0.7.1.
        String tranIdToken = firstSpaceDelimitedToken(newTran.tranId());
        String successMsg = "Payment successful.  Your Transaction ID is " + tranIdToken + ".";

        return Result.sendMap(
                buildScreen(CoBil00Input.empty(), successMsg, CoBil00Output.FieldColor.GREEN),
                commarea);
    }

    /**
     * Paragraph {@code STARTBR/READPREV/ENDBR-TRANSACT-FILE} cluster.
     *
     * <p>Determines the next transaction id by finding the maximum existing
     * TRAN-ID in the file and adding 1. On ENDFILE (no transactions exist),
     * the COBOL code zeroes TRAN-ID; subsequent ADD 1 yields 1.
     */
    private long nextTransactionId() {
        long maxId = 0L;
        try (Stream<TranRecord> stream = transactions.streamSequential()) {
            var it = stream.iterator();
            while (it.hasNext()) {
                String idStr = it.next().tranId();
                if (idStr == null || idStr.isBlank()) continue;
                try {
                    long id = Long.parseLong(idStr.trim());
                    if (id > maxId) maxId = id;
                } catch (NumberFormatException ignore) {
                    // Skip non-numeric TRAN-IDs (defensive)
                }
            }
        }
        return maxId + 1;
    }

    /**
     * Paragraph {@code CLEAR-CURRENT-SCREEN}.
     */
    private Result clearCurrentScreen(CardDemoCommarea commarea) {
        return Result.sendMap(
                buildScreen(CoBil00Input.empty(), "", CoBil00Output.FieldColor.DEFAULT),
                commarea);
    }

    /**
     * Paragraph {@code RETURN-TO-PREV-SCREEN}: XCTL to target program.
     */
    private Result returnToTarget(CardDemoCommarea commarea, String target) {
        CardDemoCommarea outbound = withTarget(commarea, target);
        log.info("CoBil00C: XCTL to {}", target);
        CardDemoCommarea finalCommarea = outbound;
        if (programRegistry.isRegistered(target)) {
            finalCommarea = programRegistry.invoke(target, outbound);
        }
        return Result.xctl(target, finalCommarea);
    }

    /**
     * Paragraphs {@code SEND-BILLPAY-SCREEN} + {@code POPULATE-HEADER-INFO}.
     */
    private CoBil00Output buildScreen(CoBil00Input input, String message,
                                      CoBil00Output.FieldColor color) {
        return new CoBil00Output(
                TRANSACTION_ID,
                TITLE_01,
                todayDate(),
                PROGRAM_ID,
                TITLE_02,
                nowTime(),
                input.accountId(),
                input.currentBalance(),
                input.confirmation(),
                message == null ? "" : message,
                color,
                0,
                0);
    }

    /**
     * Formats {@code ACCT-CURR-BAL} (BigDecimal) as COBOL PIC +9999999999.99
     * (sign + 10-digit whole + dot + 2-digit fraction).
     */
    private static String formatCurrentBalance(BigDecimal amt) {
        if (amt == null) {
            return "+0000000000.00";
        }
        BigDecimal scaled = amt.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal abs = scaled.abs();
        String sign = scaled.signum() < 0 ? "-" : "+";
        long wholePart = abs.toBigInteger().longValueExact();
        long fracPart = abs.subtract(new BigDecimal(wholePart)).movePointRight(2).longValueExact();
        return String.format("%s%010d.%02d", sign, wholePart, fracPart);
    }

    /**
     * Returns the substring up to the first space in {@code s}, mirroring
     * COBOL {@code STRING ... DELIMITED BY SPACE} semantics.
     */
    private static String firstSpaceDelimitedToken(String s) {
        if (s == null) {
            return "";
        }
        int idx = s.indexOf(' ');
        return idx < 0 ? s : s.substring(0, idx);
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
