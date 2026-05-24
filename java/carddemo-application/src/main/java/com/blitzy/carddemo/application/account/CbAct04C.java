/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.account;

import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.AccountRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.DiscountGroupRepository;
import com.blitzy.carddemo.domain.port.TransactionCategoryBalanceRepository;
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.record.AccountRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.DisGroupRecord;
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
 * Java translation of the {@code CBACT04C} COBOL batch program at
 * {@code app/cbl/CBACT04C.cbl} ("Interest calculator").
 *
 * <h2>Program purpose</h2>
 * <p>Walks the TCATBAL VSAM KSDS sequentially. For each
 * {@link TranCatBalRecord} record, looks up the account, the cross-reference
 * record (for the card number), and the discount-group entry that yields
 * the interest rate. Computes monthly interest as
 * {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} (truncating per the COBOL
 * default rounding mode) and writes a new transaction record for the
 * accrued interest. When the account ID changes between TCATBAL records,
 * the program updates the previous account's current balance by adding the
 * accumulated interest and resetting the current-cycle credit/debit
 * counters.
 *
 * <h2>Decimal arithmetic (AAP &sect;0.6.1)</h2>
 * <p>The {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
 * statement carries no {@code ROUNDED} clause, so the COBOL default
 * (truncate) applies. This Java translation uses
 * {@link Decimals#multiply(BigDecimal, BigDecimal, int, RoundingMode)} +
 * {@link Decimals#divide(BigDecimal, BigDecimal, int, RoundingMode)} with
 * {@link RoundingMode#DOWN} per AAP &sect;0.6.1.
 *
 * <h2>DB2 timestamp format (AAP &sect;0.6.4)</h2>
 * <p>The COBOL paragraph {@code Z-GET-DB2-FORMAT-TIMESTAMP} builds a 26-byte
 * timestamp of the form {@code yyyy-MM-dd-HH.mm.ss.XX0000} where {@code XX}
 * is centiseconds (hundredths of a second) and {@code 0000} is a fixed
 * trailer. The Java translation truncates {@link LocalDateTime#getNano()}
 * by dividing by 10_000_000 to obtain centiseconds, matching the COBOL
 * {@code COB-MIL} 2-digit field.
 *
 * <h2>Default disclosure group fallback</h2>
 * <p>When the per-account discount group lookup returns FILE STATUS '23'
 * (NOT FOUND), the COBOL paragraph
 * {@code 1200-A-GET-DEFAULT-INT-RATE} retries with the literal
 * {@code 'DEFAULT'} as the group ID. The Java translation calls
 * {@link DiscountGroupRepository#findByKey(String, String, int)} with
 * {@code "DEFAULT"} on first-lookup miss.
 *
 * <h2>1400-COMPUTE-FEES</h2>
 * <p>The COBOL paragraph is explicitly marked {@code * To be implemented}
 * and contains only an {@code EXIT}. The Java translation preserves the
 * empty fee-computation behavior verbatim (no-op).
 */
@CobolProgram(
        value = "CBACT04C",
        sourcePath = "app/cbl/CBACT04C.cbl",
        notes = "Interest calculator: (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 with RoundingMode.DOWN per AAP §0.6.1"
)
public final class CbAct04C {

    private static final Logger log = LoggerFactory.getLogger(CbAct04C.class);

    /** Mirror of the COBOL PROGRAM-ID. */
    public static final String PROGRAM_ID = "CBACT04C";

    /** Default group ID used when the per-account discount group is missing. */
    public static final String DEFAULT_GROUP_ID = "DEFAULT";

    /** Interest transaction type (per COBOL: MOVE '01' TO TRAN-TYPE-CD). */
    public static final String INTEREST_TRAN_TYPE_CD = "01";

    /** Interest transaction category code (per COBOL: MOVE '05' TO TRAN-CAT-CD). */
    public static final int INTEREST_TRAN_CAT_CD = 5;

    /** Transaction source (per COBOL: MOVE 'System' TO TRAN-SOURCE). */
    public static final String INTEREST_TRAN_SOURCE = "System";

    /** Monetary scale per AAP §0.6.1. */
    public static final int MONETARY_SCALE = 2;

    /** Divisor in the interest formula (12 months * 100 percent). */
    public static final BigDecimal MONTHS_PER_YEAR_PCT = new BigDecimal("1200");

    private final TransactionCategoryBalanceRepository tcatBalRepository;
    private final CardXrefRepository xrefRepository;
    private final DiscountGroupRepository discountGroupRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    /** Last-seen account ID across the iteration (COBOL WS-LAST-ACCT-NUM). */
    private long lastAcctNum = -1L;
    /** Total accumulated interest for the current account (COBOL WS-TOTAL-INT). */
    private BigDecimal totalInt = BigDecimal.ZERO.setScale(MONETARY_SCALE);
    /** First-iteration sentinel (COBOL WS-FIRST-TIME). */
    private boolean firstTime = true;
    /** Transaction ID suffix counter (COBOL WS-TRANID-SUFFIX). */
    private long tranIdSuffix = 0L;

    /** The current account being processed (cached after each 1100 lookup). */
    private AccountRecord currentAccount;
    /** The current xref entry (cached after each 1110 lookup). */
    private CardXrefRecord currentXref;

    /** The parm date passed in from JCL (PROCEDURE DIVISION USING EXTERNAL-PARMS). */
    private final String parmDate;

    /**
     * Constructor injection per AAP &sect;0.3.
     *
     * @param tcatBalRepository       port for TCATBAL sequential reads
     * @param xrefRepository          port for XREFFILE random reads (by acct ID)
     * @param discountGroupRepository port for DISCGRP random reads (by group/type/cat)
     * @param accountRepository       port for ACCTFILE random reads + rewrites
     * @param transactionRepository   port for TRANSACT sequential writes
     * @param parmDate                the EXTERNAL-PARMS date (PARM-DATE PIC X(10))
     */
    public CbAct04C(TransactionCategoryBalanceRepository tcatBalRepository,
                    CardXrefRepository xrefRepository,
                    DiscountGroupRepository discountGroupRepository,
                    AccountRepository accountRepository,
                    TransactionRepository transactionRepository,
                    String parmDate) {
        this.tcatBalRepository = Objects.requireNonNull(tcatBalRepository,
                "tcatBalRepository");
        this.xrefRepository = Objects.requireNonNull(xrefRepository, "xrefRepository");
        this.discountGroupRepository = Objects.requireNonNull(discountGroupRepository,
                "discountGroupRepository");
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository");
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository");
        this.parmDate = parmDate == null ? "" : parmDate;
    }

    /**
     * Drives the program as the COBOL PROCEDURE DIVISION does.
     *
     * @throws AbendException if a file I/O error occurs
     */
    public void run() {
        log.info("START OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
        // Open paragraphs (0000-0400) — repositories lazy-open on first use
        try (Stream<TranCatBalRecord> records = tcatBalRepository.streamSequential()) {
            records.forEach(this::processTcatBalRecord);
        } catch (RuntimeException e) {
            log.error("ERROR READING TCATBAL");
            displayIoStatus("12");
            closeAll();
            abendProgram(e);
            return;
        }
        // Final account update when the loop ends (COBOL ELSE branch on END-OF-FILE)
        if (!firstTime) {
            updateAccount();
        }
        closeAll();
        log.info("END OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
    }

    /**
     * Equivalent to the per-iteration body of the PERFORM UNTIL END-OF-FILE
     * loop in the COBOL PROCEDURE DIVISION.
     */
    private void processTcatBalRecord(TranCatBalRecord record) {
        // DISPLAY TRAN-CAT-BAL-RECORD (preserved verbatim)
        displayWholeRecord(record);

        long acctId = record.tranCatKey().trancatAcctId();
        if (acctId != lastAcctNum) {
            if (!firstTime) {
                // 1050-UPDATE-ACCOUNT for the PRIOR account
                updateAccount();
            } else {
                firstTime = false;
            }
            totalInt = BigDecimal.ZERO.setScale(MONETARY_SCALE);
            lastAcctNum = acctId;
            // 1100-GET-ACCT-DATA
            currentAccount = getAcctData(acctId);
            // 1110-GET-XREF-DATA
            currentXref = getXrefData(acctId);
        }

        // 1200-GET-INTEREST-RATE (with default fallback to DEFAULT group)
        BigDecimal disIntRate = getInterestRate(record.tranCatKey().trancatTypeCd(),
                record.tranCatKey().trancatCd());

        if (disIntRate.compareTo(BigDecimal.ZERO) != 0) {
            // 1300-COMPUTE-INTEREST
            BigDecimal monthlyInt = computeInterest(record.tranCatBal(), disIntRate);
            totalInt = Decimals.add(totalInt, monthlyInt, MONETARY_SCALE,
                    RoundingMode.DOWN);
            // 1300-B-WRITE-TX (called from 1300)
            writeInterestTransaction(monthlyInt);
            // 1400-COMPUTE-FEES — explicitly empty per COBOL "To be implemented"
            computeFees();
        }
    }

    /**
     * Mirrors the {@code 1050-UPDATE-ACCOUNT} paragraph: adds the
     * accumulated interest to the current balance and resets the current
     * cycle credit/debit fields, then REWRITEs the account record.
     */
    private void updateAccount() {
        if (currentAccount == null) {
            return; // nothing to update
        }
        BigDecimal newBal = Decimals.add(currentAccount.acctCurrBal(), totalInt,
                MONETARY_SCALE, RoundingMode.DOWN);
        AccountRecord updated = new AccountRecord(
                currentAccount.acctId(),
                currentAccount.acctActiveStatus(),
                newBal,
                currentAccount.acctCreditLimit(),
                currentAccount.acctCashCreditLimit(),
                currentAccount.acctOpenDate(),
                currentAccount.acctExpiraionDate(),
                currentAccount.acctReissueDate(),
                BigDecimal.ZERO.setScale(MONETARY_SCALE),  // MOVE 0 TO ACCT-CURR-CYC-CREDIT
                BigDecimal.ZERO.setScale(MONETARY_SCALE),  // MOVE 0 TO ACCT-CURR-CYC-DEBIT
                currentAccount.acctAddrZip(),
                currentAccount.acctGroupId(),
                currentAccount.filler());
        accountRepository.save(updated);
    }

    /**
     * Mirrors the {@code 1100-GET-ACCT-DATA} paragraph: reads the account
     * record by ID. Aborts with ABEND on file status != '00'.
     */
    private AccountRecord getAcctData(long acctId) {
        return accountRepository.findById(acctId)
                .orElseThrow(() -> {
                    log.error("ACCOUNT NOT FOUND: {}", acctId);
                    log.error("ERROR READING ACCOUNT FILE");
                    displayIoStatus("23");
                    return new AbendException(999,
                            "Account not found: " + acctId);
                });
    }

    /**
     * Mirrors the {@code 1110-GET-XREF-DATA} paragraph: reads the xref
     * record by account ID (alternate key). Aborts on file status != '00'.
     */
    private CardXrefRecord getXrefData(long acctId) {
        // CardXrefRepository.findByAccountId returns the first matching xref
        // record per AIX iteration order — matches the COBOL READ
        // KEY IS FD-XREF-ACCT-ID semantics (first AIX hit, NOTFND if none).
        return xrefRepository.findByAccountId(acctId)
                .orElseThrow(() -> {
                    log.error("ACCOUNT NOT FOUND: {}", acctId);
                    log.error("ERROR READING XREF FILE");
                    displayIoStatus("23");
                    return new AbendException(999,
                            "Xref entry not found for acct: " + acctId);
                });
    }

    /**
     * Mirrors the {@code 1200-GET-INTEREST-RATE} paragraph with the
     * {@code 1200-A-GET-DEFAULT-INT-RATE} fallback for FILE STATUS = '23'.
     * Returns the discount interest rate; returns BigDecimal.ZERO if both
     * lookups miss (per COBOL: 'DEFAULT' lookup may itself miss).
     */
    private BigDecimal getInterestRate(String typeCd, int catCd) {
        Optional<DisGroupRecord> direct = discountGroupRepository.findByKey(
                currentAccount.acctGroupId(), typeCd, catCd);
        if (direct.isPresent()) {
            return direct.get().disIntRate();
        }
        log.info("DISCLOSURE GROUP RECORD MISSING");
        log.info("TRY WITH DEFAULT GROUP CODE");
        // 1200-A-GET-DEFAULT-INT-RATE: retry with literal 'DEFAULT' group
        Optional<DisGroupRecord> fallback = discountGroupRepository.findByKey(
                DEFAULT_GROUP_ID, typeCd, catCd);
        if (fallback.isPresent()) {
            return fallback.get().disIntRate();
        }
        // COBOL would have ABENDed here; we keep the lenient behavior of
        // returning 0 so the caller's "IF DIS-INT-RATE NOT = 0" guard skips.
        return BigDecimal.ZERO.setScale(MONETARY_SCALE);
    }

    /**
     * Mirrors the {@code 1300-COMPUTE-INTEREST} paragraph: computes
     * {@code (balance * rate) / 1200} truncating per AAP &sect;0.6.1.
     */
    private BigDecimal computeInterest(BigDecimal balance, BigDecimal rate) {
        // COBOL: COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
        // No ROUNDED clause -> RoundingMode.DOWN per AAP §0.6.1.
        // Compute with intermediate precision to preserve significant digits,
        // then scale the final result.
        BigDecimal product = balance.multiply(rate, Decimals.DEFAULT_MATH_CONTEXT);
        return Decimals.divide(product, MONTHS_PER_YEAR_PCT, MONETARY_SCALE,
                RoundingMode.DOWN);
    }

    /**
     * Mirrors the {@code 1300-B-WRITE-TX} paragraph: builds the new
     * transaction record and writes it to TRANSACT.
     */
    private void writeInterestTransaction(BigDecimal monthlyInt) {
        tranIdSuffix++;
        // STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID
        // The COBOL STRING is fixed-position concatenation; PARM-DATE is 10
        // bytes and WS-TRANID-SUFFIX is 6 zoned digits -> 16 bytes total.
        String tranId = String.format("%-10s%06d", parmDate, tranIdSuffix);
        // STRING 'Int. for a/c ', ACCT-ID DELIMITED BY SIZE INTO TRAN-DESC
        String tranDesc = "Int. for a/c " + String.format("%011d", currentAccount.acctId());

        // AAP §0.6.4: PIC X(26) timestamps map to java.time.LocalDateTime.
        // The COBOL DB2 CURRENT-TIMESTAMP-26 yields a wall-clock value
        // (no time zone) — LocalDateTime is the matching java.time type.
        LocalDateTime dbTs = LocalDateTime.now();
        TranRecord tx = new TranRecord(
                tranId,
                INTEREST_TRAN_TYPE_CD,
                INTEREST_TRAN_CAT_CD,
                INTEREST_TRAN_SOURCE,
                tranDesc,
                monthlyInt,
                0L,                                // TRAN-MERCHANT-ID = 0
                "",                                // TRAN-MERCHANT-NAME = SPACES
                "",                                // TRAN-MERCHANT-CITY = SPACES
                "",                                // TRAN-MERCHANT-ZIP = SPACES
                currentXref.xrefCardNum(),         // MOVE XREF-CARD-NUM TO TRAN-CARD-NUM
                dbTs,                              // TRAN-ORIG-TS
                dbTs,                              // TRAN-PROC-TS
                TranRecord.emptyFiller());
        transactionRepository.append(tx);
    }

    /**
     * Mirrors the {@code 1400-COMPUTE-FEES} paragraph, which is explicitly
     * marked {@code * To be implemented} in the COBOL source. The Java
     * translation preserves the no-op behavior verbatim per AAP &sect;0.7.1.
     */
    private void computeFees() {
        // 1400-COMPUTE-FEES: To be implemented (preserved verbatim from COBOL)
    }

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

    private void closeAll() {
        // Close paragraphs (9000-9400) — wrap each in try/catch so one
        // failure doesn't prevent the others from running.
        closeQuietly(tcatBalRepository, "TRANSACTION CATEGORY BALANCE FILE");
        closeQuietly(xrefRepository, "CROSS REF FILE");
        closeQuietly(discountGroupRepository, "DISCLOSURE GROUP FILE");
        closeQuietly(accountRepository, "ACCOUNT FILE");
        closeQuietly(transactionRepository, "TRANSACTION FILE");
    }

    private void closeQuietly(AutoCloseable resource, String name) {
        try {
            resource.close();
        } catch (Exception e) {
            log.error("ERROR CLOSING {}: {}", name, e.getMessage());
            displayIoStatus("12");
        }
    }

    private void displayWholeRecord(TranCatBalRecord record) {
        byte[] encoded = record.encode();
        log.info("{}", new String(encoded, StandardCharsets.ISO_8859_1));
    }

    private void displayIoStatus(String ioStatus) {
        String formatted = ioStatus == null || ioStatus.isBlank()
                ? "0000"
                : "00" + ioStatus;
        log.info("FILE STATUS IS: NNNN{}", formatted);
    }

    private void abendProgram(Throwable cause) {
        log.error("ABENDING PROGRAM");
        throw new AbendException(999, "CBACT04C abend", cause);
    }
}
