/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.transaction;

import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.AccountRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.DailyTransactionRepository;
import com.blitzy.carddemo.domain.port.TransactionCategoryBalanceRepository;
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.record.AccountRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.DalyTranRecord;
import com.blitzy.carddemo.domain.record.TranCatBalRecord;
import com.blitzy.carddemo.domain.record.TranRecord;
import com.blitzy.carddemo.domain.util.Decimals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Java translation of the {@code CBTRN02C} COBOL batch program at
 * {@code app/cbl/CBTRN02C.cbl} ("Daily transaction posting engine").
 *
 * <h2>Program purpose</h2>
 * <p>Reads every {@code DALYTRAN-RECORD} sequentially. For each record it:
 * <ol>
 *   <li>Validates the transaction via {@code 1500-VALIDATE-TRAN} (composed
 *       of {@code 1500-A-LOOKUP-XREF} and {@code 1500-B-LOOKUP-ACCT},
 *       checking the credit limit and account expiration date).</li>
 *   <li>If validation passes, posts the transaction:
 *       {@code 2700-UPDATE-TCATBAL} (create or update category balance) +
 *       {@code 2800-UPDATE-ACCOUNT-REC} (apply amount to balance and cycle
 *       credit/debit counters) + {@code 2900-WRITE-TRANSACTION-FILE}.</li>
 *   <li>If validation fails, increments the reject count and writes a
 *       reject record via {@code 2500-WRITE-REJECT-REC}.</li>
 * </ol>
 *
 * <h2>Files opened</h2>
 * <ul>
 *   <li>DALYTRAN — INPUT, sequential read</li>
 *   <li>TRANSACT — OUTPUT, sequential write</li>
 *   <li>XREFFILE — INPUT, random by card number</li>
 *   <li>DALYREJS — OUTPUT, sequential write (reject file)</li>
 *   <li>ACCTFILE — I-O, random read + REWRITE</li>
 *   <li>TCATBAL  — I-O, random read + REWRITE + WRITE</li>
 * </ul>
 *
 * <h2>Validation reason codes (from COBOL paragraph 1500)</h2>
 * <ul>
 *   <li>{@value #REASON_INVALID_CARD} — INVALID CARD NUMBER FOUND</li>
 *   <li>{@value #REASON_ACCOUNT_NOT_FOUND} — ACCOUNT RECORD NOT FOUND</li>
 *   <li>{@value #REASON_OVERLIMIT} — OVERLIMIT TRANSACTION</li>
 *   <li>{@value #REASON_EXPIRED} — TRANSACTION RECEIVED AFTER ACCT EXPIRATION</li>
 * </ul>
 *
 * <h2>Decimal arithmetic (AAP &sect;0.6.1)</h2>
 * <p>Posting computations use {@link Decimals#add(BigDecimal, BigDecimal, int,
 * RoundingMode)} with {@link RoundingMode#DOWN} (no COBOL ROUNDED clause
 * is present on the {@code ADD} statements; default truncation applies).
 * Monetary scale is fixed at 2 per AAP &sect;0.6.1.
 *
 * <h2>DB2 timestamp format (AAP &sect;0.6.4)</h2>
 * <p>The COBOL paragraph {@code Z-GET-DB2-FORMAT-TIMESTAMP} produces a
 * 26-byte timestamp of the form {@code yyyy-MM-dd-HH.mm.ss.XX0000}; XX is
 * centiseconds and 0000 is a fixed trailer. The Java translation uses
 * {@link LocalDateTime#getNano()} divided by 10_000_000 to obtain
 * centiseconds, matching the COBOL {@code COB-MIL} 2-digit field.
 *
 * <h2>Return code</h2>
 * <p>If {@code WS-REJECT-COUNT > 0} after the loop, the COBOL program
 * sets {@code RETURN-CODE = 4}. The Java translation exposes the value
 * via {@link #returnCode()}; the wrapping main class can use it as the
 * process exit status.
 */
@CobolProgram(
        value = "CBTRN02C",
        sourcePath = "app/cbl/CBTRN02C.cbl",
        notes = "Daily posting engine; validates + posts transactions, writes reject file. "
                + "Monetary ADD with RoundingMode.DOWN per AAP §0.6.1; DB2 timestamp via "
                + "getNano()/10_000_000 per AAP §0.6.4."
)
public final class CbTrn02C {

    private static final Logger log = LoggerFactory.getLogger(CbTrn02C.class);

    /** Mirror of the COBOL PROGRAM-ID. */
    public static final String PROGRAM_ID = "CBTRN02C";

    /** Monetary scale per AAP §0.6.1. */
    public static final int MONETARY_SCALE = 2;

    /** WS-VALIDATION-FAIL-REASON code: 1500-A-LOOKUP-XREF INVALID KEY. */
    public static final int REASON_INVALID_CARD = 100;
    /** WS-VALIDATION-FAIL-REASON code: 1500-B-LOOKUP-ACCT INVALID KEY. */
    public static final int REASON_ACCOUNT_NOT_FOUND = 101;
    /** WS-VALIDATION-FAIL-REASON code: 1500-B post-read overlimit check. */
    public static final int REASON_OVERLIMIT = 102;
    /** WS-VALIDATION-FAIL-REASON code: 1500-B post-read expiration check. */
    public static final int REASON_EXPIRED = 103;
    /** WS-VALIDATION-FAIL-REASON code: 2800-UPDATE-ACCOUNT-REC INVALID KEY (rare). */
    public static final int REASON_ACCOUNT_REWRITE_FAILED = 109;

    /** RETURN-CODE value when rejects are present (per COBOL: MOVE 4 TO RETURN-CODE). */
    public static final int RETURN_CODE_WITH_REJECTS = 4;

    private final DailyTransactionRepository dailyTransactionRepository;
    private final TransactionRepository transactionRepository;
    private final CardXrefRepository xrefRepository;
    private final AccountRepository accountRepository;
    private final TransactionCategoryBalanceRepository tcatBalRepository;

    /** COBOL WS-TRANSACTION-COUNT PIC 9(09). */
    private long transactionCount = 0L;
    /** COBOL WS-REJECT-COUNT PIC 9(09). */
    private long rejectCount = 0L;
    /** COBOL RETURN-CODE. */
    private int returnCode = 0;

    /** Cached XREF read from 1500-A (used by 2000/2700). */
    private CardXrefRecord currentXref;
    /** Cached ACCOUNT read from 1500-B (used by 2800). */
    private AccountRecord currentAccount;

    /**
     * Constructor injection per AAP &sect;0.3. CBTRN02C uses five logical
     * datasets; in the Java port the DALYTRAN reader and the DALYREJS
     * sink are exposed through the same {@link DailyTransactionRepository}
     * port (the COBOL FD-REJS-RECORD is layered onto DalyTranRecord via
     * {@link DailyTransactionRepository#appendReject(DalyTranRecord, String)}).
     *
     * @param dailyTransactionRepository      port for DALYTRAN sequential reads and DALYREJS sequential writes
     * @param transactionRepository           port for TRANSACT sequential writes
     * @param xrefRepository                  port for XREFFILE random reads by card number
     * @param accountRepository               port for ACCTFILE I-O (random read + REWRITE)
     * @param tcatBalRepository               port for TCATBAL I-O (random read + REWRITE + WRITE)
     */
    public CbTrn02C(DailyTransactionRepository dailyTransactionRepository,
                    TransactionRepository transactionRepository,
                    CardXrefRepository xrefRepository,
                    AccountRepository accountRepository,
                    TransactionCategoryBalanceRepository tcatBalRepository) {
        this.dailyTransactionRepository = Objects.requireNonNull(dailyTransactionRepository,
                "dailyTransactionRepository");
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository");
        this.xrefRepository = Objects.requireNonNull(xrefRepository, "xrefRepository");
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository");
        this.tcatBalRepository = Objects.requireNonNull(tcatBalRepository,
                "tcatBalRepository");
    }

    /**
     * Drives the program as the COBOL PROCEDURE DIVISION does:
     * <pre>{@code
     * PERFORM 0000-DALYTRAN-OPEN through 0500-TCATBALF-OPEN.
     * PERFORM UNTIL END-OF-FILE = 'Y'
     *   PERFORM 1000-DALYTRAN-GET-NEXT
     *   ADD 1 TO WS-TRANSACTION-COUNT
     *   PERFORM 1500-VALIDATE-TRAN
     *   IF WS-VALIDATION-FAIL-REASON = 0
     *     PERFORM 2000-POST-TRANSACTION
     *   ELSE
     *     ADD 1 TO WS-REJECT-COUNT
     *     PERFORM 2500-WRITE-REJECT-REC
     *   END-IF
     * END-PERFORM.
     * PERFORM 9000-DALYTRAN-CLOSE through 9500-TCATBALF-CLOSE.
     * IF WS-REJECT-COUNT > 0 -> MOVE 4 TO RETURN-CODE.
     * }</pre>
     *
     * @throws AbendException if a file I/O error occurs
     */
    public void run() {
        log.info("START OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
        // Open paragraphs (0000-0500) — repositories lazy-open on first use
        openFiles();

        try (Stream<DalyTranRecord> records = dailyTransactionRepository.streamSequential()) {
            records.forEach(this::processTransaction);
        } catch (RuntimeException e) {
            log.error("ERROR READING DALYTRAN FILE");
            displayIoStatus("12");
            closeAll();
            abendProgram(e);
            return;
        }

        // Close paragraphs (9000-9500)
        closeAll();

        log.info("TRANSACTIONS PROCESSED :{}", formatNineDigits(transactionCount));
        log.info("TRANSACTIONS REJECTED  :{}", formatNineDigits(rejectCount));

        if (rejectCount > 0L) {
            returnCode = RETURN_CODE_WITH_REJECTS;
        }
        log.info("END OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
    }

    /**
     * Returns the RETURN-CODE the COBOL program would have set. Callers
     * (the {@code carddemo-app} main class) MAY pass this as the process
     * exit code.
     */
    public int returnCode() {
        return returnCode;
    }

    /** Returns the count of processed transactions (mirror of WS-TRANSACTION-COUNT). */
    public long transactionCount() {
        return transactionCount;
    }

    /** Returns the count of rejected transactions (mirror of WS-REJECT-COUNT). */
    public long rejectCount() {
        return rejectCount;
    }

    // ---------------------------------------------------------------- per-record body

    /**
     * Equivalent to the loop body in the PROCEDURE DIVISION. Mirrors the
     * COBOL flow: ADD 1 to count, clear reason, validate, branch on reason.
     */
    private void processTransaction(DalyTranRecord record) {
        transactionCount++;

        // MOVE 0 TO WS-VALIDATION-FAIL-REASON
        // MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC
        ValidationResult result = validateTransaction(record);

        if (result.reason() == 0) {
            // PERFORM 2000-POST-TRANSACTION
            postTransaction(record);
        } else {
            // ADD 1 TO WS-REJECT-COUNT, PERFORM 2500-WRITE-REJECT-REC
            rejectCount++;
            writeRejectRec(record, result);
        }
    }

    // ---------------------------------------------------------------- 1500 VALIDATE

    /**
     * Mirrors {@code 1500-VALIDATE-TRAN}: performs {@code 1500-A-LOOKUP-XREF}
     * then {@code 1500-B-LOOKUP-ACCT} only if the XREF lookup succeeded.
     * Returns the validation result carrying reason code and description.
     */
    private ValidationResult validateTransaction(DalyTranRecord record) {
        currentXref = null;
        currentAccount = null;

        // 1500-A-LOOKUP-XREF
        ValidationResult xrefResult = lookupXref(record);
        if (xrefResult.reason() != 0) {
            return xrefResult;
        }

        // 1500-B-LOOKUP-ACCT (only if XREF was found)
        return lookupAccount(record);
    }

    /**
     * Mirrors {@code 1500-A-LOOKUP-XREF}. Reads XREF by card number;
     * sets reason 100 on miss.
     */
    private ValidationResult lookupXref(DalyTranRecord record) {
        Optional<CardXrefRecord> xref = xrefRepository.findByCardNum(
                record.dalytranCardNum());
        if (xref.isEmpty()) {
            return new ValidationResult(REASON_INVALID_CARD, "INVALID CARD NUMBER FOUND");
        }
        currentXref = xref.get();
        return ValidationResult.OK;
    }

    /**
     * Mirrors {@code 1500-B-LOOKUP-ACCT}. Reads ACCOUNT by ID; if found,
     * checks the credit-limit guard (reason 102) and the expiration-date
     * guard (reason 103). Sets reason 101 on miss.
     */
    private ValidationResult lookupAccount(DalyTranRecord record) {
        Optional<AccountRecord> acct = accountRepository.findById(currentXref.xrefAcctId());
        if (acct.isEmpty()) {
            return new ValidationResult(REASON_ACCOUNT_NOT_FOUND, "ACCOUNT RECORD NOT FOUND");
        }
        currentAccount = acct.get();

        // COBOL: COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
        //                             - ACCT-CURR-CYC-DEBIT
        //                             + DALYTRAN-AMT
        // No ROUNDED clause -> RoundingMode.DOWN per AAP §0.6.1.
        BigDecimal tempBal = Decimals.subtract(currentAccount.acctCurrCycCredit(),
                currentAccount.acctCurrCycDebit(),
                MONETARY_SCALE, RoundingMode.DOWN);
        tempBal = Decimals.add(tempBal, record.dalytranAmt(),
                MONETARY_SCALE, RoundingMode.DOWN);

        // COBOL: IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL CONTINUE
        //        ELSE MOVE 102 TO WS-VALIDATION-FAIL-REASON.
        if (currentAccount.acctCreditLimit().compareTo(tempBal) < 0) {
            return new ValidationResult(REASON_OVERLIMIT, "OVERLIMIT TRANSACTION");
        }

        // COBOL: IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10) CONTINUE
        //        ELSE MOVE 103 TO WS-VALIDATION-FAIL-REASON.
        // DALYTRAN-ORIG-TS is PIC X(26); the first 10 chars form the YYYY-MM-DD prefix.
        String expirStr = currentAccount.acctExpiraionDate().toString(); // ISO yyyy-MM-dd
        String origDateStr = record.dalytranOrigTs().length() >= 10
                ? record.dalytranOrigTs().substring(0, 10)
                : record.dalytranOrigTs();
        // Lexicographic compare on ISO yyyy-MM-dd is equivalent to chronological
        // compare; matches the COBOL alphanumeric comparison semantics exactly.
        if (expirStr.compareTo(origDateStr) < 0) {
            return new ValidationResult(REASON_EXPIRED,
                    "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        }

        return ValidationResult.OK;
    }

    // ---------------------------------------------------------------- 2000 POST

    /**
     * Mirrors {@code 2000-POST-TRANSACTION}: moves DALYTRAN fields into
     * TRAN-RECORD, populates the DB2 process timestamp, performs the
     * three update paragraphs (TCATBAL, ACCT, TRANSACT).
     */
    private void postTransaction(DalyTranRecord record) {
        // AAP §0.6.4: PIC X(26) timestamps map to LocalDateTime in the
        // TranRecord schema. The DalyTranRecord still carries the
        // timestamp as a String; convert via TranRecord.parseTimestamp.
        LocalDateTime procTs = LocalDateTime.now();

        // MOVE DALYTRAN-... TO TRAN-... (field-by-field MOVE)
        TranRecord tran = new TranRecord(
                record.dalytranId(),
                record.dalytranTypeCd(),
                record.dalytranCatCd(),
                record.dalytranSource(),
                record.dalytranDesc(),
                record.dalytranAmt(),
                record.dalytranMerchantId(),
                record.dalytranMerchantName(),
                record.dalytranMerchantCity(),
                record.dalytranMerchantZip(),
                record.dalytranCardNum(),
                TranRecord.parseTimestamp(record.dalytranOrigTs()),  // TRAN-ORIG-TS = DALYTRAN-ORIG-TS
                procTs,                                              // TRAN-PROC-TS = DB2-FORMAT-TS
                TranRecord.emptyFiller());

        // PERFORM 2700-UPDATE-TCATBAL
        ValidationResult tcatResult = updateTcatBal(record);
        if (tcatResult.reason() != 0) {
            // The COBOL paragraph performs ABEND on any TCATBAL error other than
            // 23 (NOT FOUND, which triggers create). If updateTcatBal returned
            // a non-zero reason, we treat that as a runtime error and reject.
            rejectCount++;
            writeRejectRec(record, tcatResult);
            return;
        }

        // PERFORM 2800-UPDATE-ACCOUNT-REC
        ValidationResult acctResult = updateAccountRec(record);
        if (acctResult.reason() != 0) {
            // The COBOL paragraph only records reason 109 in the trailer and
            // does NOT increment the reject count or write the reject file;
            // it continues to 2900-WRITE-TRANSACTION-FILE regardless. Match
            // that semantics: log the trailer but proceed.
            log.warn("VALIDATION FAILURE IN 2800: reason={} desc={}",
                    acctResult.reason(), acctResult.description());
        }

        // PERFORM 2900-WRITE-TRANSACTION-FILE
        writeTransactionFile(tran);
    }

    // ---------------------------------------------------------------- 2500 REJECT

    /**
     * Mirrors {@code 2500-WRITE-REJECT-REC}: writes the DALYTRAN record
     * plus the validation trailer (reason code + description, total 80
     * bytes) to the DALYREJS file.
     */
    private void writeRejectRec(DalyTranRecord record, ValidationResult result) {
        // COBOL: MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA
        //        MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER
        // We carry the reason code in the trailer string passed to the port.
        // The port-level adapter is responsible for the byte layout
        // (4-byte reason + 76-byte description per WS-VALIDATION-TRAILER).
        String trailer = formatTrailer(result);
        try {
            dailyTransactionRepository.appendReject(record, trailer);
        } catch (RuntimeException e) {
            log.error("ERROR WRITING TO REJECTS FILE");
            displayIoStatus("12");
            abendProgram(e);
        }
    }

    /**
     * Builds the 80-byte validation trailer: 4-byte zero-padded reason
     * code followed by the 76-byte space-padded description, matching
     * {@code WS-VALIDATION-TRAILER} in COBOL.
     */
    private static String formatTrailer(ValidationResult result) {
        String desc = result.description() == null ? "" : result.description();
        String descPadded = (desc + " ".repeat(76)).substring(0, 76);
        return String.format("%04d%s", result.reason(), descPadded);
    }

    // ---------------------------------------------------------------- 2700 TCATBAL

    /**
     * Mirrors {@code 2700-UPDATE-TCATBAL}: reads TCATBAL by composite key;
     * if found, REWRITEs with new balance; if not found, performs
     * {@code 2700-A-CREATE-TCATBAL-REC}.
     */
    private ValidationResult updateTcatBal(DalyTranRecord record) {
        long acctId = currentXref.xrefAcctId();
        String typeCd = record.dalytranTypeCd();
        int catCd = record.dalytranCatCd();

        Optional<TranCatBalRecord> existing =
                tcatBalRepository.findByKey(acctId, typeCd, catCd);

        if (existing.isEmpty()) {
            log.info("TCATBAL record not found for key : {} {} {} .. Creating.",
                    formatAcctId(acctId), typeCd, formatCatCd(catCd));
            // 2700-A-CREATE-TCATBAL-REC
            createTcatBalRec(record);
        } else {
            // 2700-B-UPDATE-TCATBAL-REC
            updateExistingTcatBal(existing.get(), record);
        }
        return ValidationResult.OK;
    }

    /**
     * Mirrors {@code 2700-A-CREATE-TCATBAL-REC}: builds a new TCATBAL
     * record with the daily-transaction amount as the initial balance.
     */
    private void createTcatBalRec(DalyTranRecord record) {
        long acctId = currentXref.xrefAcctId();
        String typeCd = record.dalytranTypeCd();
        int catCd = record.dalytranCatCd();

        // COBOL: INITIALIZE TRAN-CAT-BAL-RECORD; ADD DALYTRAN-AMT TO TRAN-CAT-BAL
        // Per AAP §0.6.1: ADD without ROUNDED -> RoundingMode.DOWN.
        BigDecimal initialBal = Decimals.scaled(record.dalytranAmt(),
                MONETARY_SCALE, RoundingMode.DOWN);

        TranCatBalRecord newRec = new TranCatBalRecord(
                new TranCatBalRecord.TranCatKey(acctId, typeCd, catCd),
                initialBal,
                TranCatBalRecord.emptyFiller());

        try {
            tcatBalRepository.save(newRec);
        } catch (RuntimeException e) {
            log.error("ERROR WRITING TRANSACTION BALANCE FILE");
            displayIoStatus("12");
            abendProgram(e);
        }
    }

    /**
     * Mirrors {@code 2700-B-UPDATE-TCATBAL-REC}: adds the
     * daily-transaction amount to the existing TCATBAL balance and
     * REWRITEs the record.
     */
    private void updateExistingTcatBal(TranCatBalRecord existing, DalyTranRecord record) {
        // COBOL: ADD DALYTRAN-AMT TO TRAN-CAT-BAL; REWRITE TCATBAL
        BigDecimal newBal = Decimals.add(existing.tranCatBal(), record.dalytranAmt(),
                MONETARY_SCALE, RoundingMode.DOWN);
        TranCatBalRecord updated = existing.withBalanceAdjustment(
                newBal.subtract(existing.tranCatBal()));
        try {
            tcatBalRepository.save(updated);
        } catch (RuntimeException e) {
            log.error("ERROR REWRITING TRANSACTION BALANCE FILE");
            displayIoStatus("12");
            abendProgram(e);
        }
    }

    // ---------------------------------------------------------------- 2800 ACCOUNT

    /**
     * Mirrors {@code 2800-UPDATE-ACCOUNT-REC}: adds the daily-transaction
     * amount to the current balance and to the cycle-credit (if positive)
     * or cycle-debit (if negative) field; REWRITEs the account record.
     * On INVALID KEY sets WS-VALIDATION-FAIL-REASON = 109 and proceeds
     * (no ABEND).
     */
    private ValidationResult updateAccountRec(DalyTranRecord record) {
        // COBOL: ADD DALYTRAN-AMT TO ACCT-CURR-BAL
        BigDecimal newBal = Decimals.add(currentAccount.acctCurrBal(),
                record.dalytranAmt(), MONETARY_SCALE, RoundingMode.DOWN);

        BigDecimal newCycCredit = currentAccount.acctCurrCycCredit();
        BigDecimal newCycDebit = currentAccount.acctCurrCycDebit();

        // COBOL: IF DALYTRAN-AMT >= 0
        //          ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
        //        ELSE
        //          ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
        //        END-IF
        if (record.dalytranAmt().compareTo(BigDecimal.ZERO) >= 0) {
            newCycCredit = Decimals.add(newCycCredit, record.dalytranAmt(),
                    MONETARY_SCALE, RoundingMode.DOWN);
        } else {
            newCycDebit = Decimals.add(newCycDebit, record.dalytranAmt(),
                    MONETARY_SCALE, RoundingMode.DOWN);
        }

        AccountRecord updated = new AccountRecord(
                currentAccount.acctId(),
                currentAccount.acctActiveStatus(),
                newBal,
                currentAccount.acctCreditLimit(),
                currentAccount.acctCashCreditLimit(),
                currentAccount.acctOpenDate(),
                currentAccount.acctExpiraionDate(),
                currentAccount.acctReissueDate(),
                newCycCredit,
                newCycDebit,
                currentAccount.acctAddrZip(),
                currentAccount.acctGroupId(),
                currentAccount.filler());

        try {
            accountRepository.save(updated);
        } catch (RuntimeException e) {
            // INVALID KEY branch: record the reason but do NOT abend.
            return new ValidationResult(REASON_ACCOUNT_REWRITE_FAILED,
                    "ACCOUNT RECORD NOT FOUND");
        }
        return ValidationResult.OK;
    }

    // ---------------------------------------------------------------- 2900 TRAN WRITE

    /**
     * Mirrors {@code 2900-WRITE-TRANSACTION-FILE}: writes the populated
     * TRAN-RECORD to the TRANSACT file. ABENDs on file status != '00'.
     */
    private void writeTransactionFile(TranRecord tran) {
        try {
            transactionRepository.append(tran);
        } catch (RuntimeException e) {
            log.error("ERROR WRITING TO TRANSACTION FILE");
            displayIoStatus("12");
            abendProgram(e);
        }
    }

    // ---------------------------------------------------------------- OPEN / CLOSE

    /**
     * Mirrors the OPEN paragraphs 0000-0500. The Java port uses repository
     * ports that lazy-open underlying resources; this method is a
     * traceability shim for the COBOL paragraph structure.
     */
    private void openFiles() {
        // 0000-DALYTRAN-OPEN  (INPUT)
        // 0100-TRANFILE-OPEN  (OUTPUT)
        // 0200-XREFFILE-OPEN  (INPUT)
        // 0300-DALYREJS-OPEN  (OUTPUT)
        // 0400-ACCTFILE-OPEN  (I-O)
        // 0500-TCATBALF-OPEN  (I-O)
        // Port adapters open lazily on first method call.
    }

    private void closeAll() {
        // 9000-DALYTRAN-CLOSE through 9500-TCATBALF-CLOSE.
        closeQuietly(dailyTransactionRepository, "DALYTRAN FILE");
        closeQuietly(transactionRepository, "TRANSACTION FILE");
        closeQuietly(xrefRepository, "CROSS REF FILE");
        closeQuietly(accountRepository, "ACCOUNT FILE");
        closeQuietly(tcatBalRepository, "TRANSACTION BALANCE FILE");
    }

    private void closeQuietly(AutoCloseable resource, String name) {
        try {
            resource.close();
        } catch (Exception e) {
            log.error("ERROR CLOSING {}: {}", name, e.getMessage());
            displayIoStatus("12");
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Mirrors {@code Z-GET-DB2-FORMAT-TIMESTAMP}. The DB2 26-byte timestamp
     * format is {@code yyyy-MM-dd-HH.mm.ss.XX0000} where XX is centiseconds
     * (hundredths of a second). Java {@link LocalDateTime#getNano()} returns
     * nanoseconds; dividing by 10_000_000 yields centiseconds (AAP &sect;0.6.4).
     *
     * @param now the current moment
     * @return the 26-byte formatted timestamp
     */
    static String formatDb2Timestamp(LocalDateTime now) {
        int centiseconds = now.getNano() / 10_000_000;
        return String.format("%04d-%02d-%02d-%02d.%02d.%02d.%02d0000",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                now.getHour(), now.getMinute(), now.getSecond(),
                centiseconds);
    }

    private void displayIoStatus(String ioStatus) {
        String formatted = ioStatus == null || ioStatus.isBlank()
                ? "0000"
                : "00" + ioStatus;
        log.info("FILE STATUS IS: NNNN{}", formatted);
    }

    private void abendProgram(Throwable cause) {
        log.error("ABENDING PROGRAM");
        throw new AbendException(999, PROGRAM_ID + " abend", cause);
    }

    /** Formats a count as 9 zero-padded digits (mirror of PIC 9(09)). */
    private static String formatNineDigits(long value) {
        return String.format("%09d", value);
    }

    /** Formats acct id as PIC 9(11). */
    private static String formatAcctId(long acctId) {
        return String.format("%011d", acctId);
    }

    /** Formats cat cd as PIC 9(04). */
    private static String formatCatCd(int catCd) {
        return String.format("%04d", catCd);
    }

    /**
     * Validation outcome carrying COBOL {@code WS-VALIDATION-TRAILER}
     * fields: the 4-digit reason code and the 76-char description.
     *
     * <p>Reason 0 = OK; non-zero = reject.
     */
    public record ValidationResult(int reason, String description) {
        public static final ValidationResult OK = new ValidationResult(0, "");
    }
}
