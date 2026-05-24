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
import com.blitzy.carddemo.domain.port.CardRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.CustomerRepository;
import com.blitzy.carddemo.domain.port.DailyTransactionRepository;
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.record.AccountRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.DalyTranRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Java translation of the {@code CBTRN01C} COBOL batch program at
 * {@code app/cbl/CBTRN01C.cbl} ("Daily transaction validator").
 *
 * <p>The COBOL program reads each {@code DALYTRAN-RECORD} from the daily
 * transaction file. For every record it:
 * <ol>
 *   <li>DISPLAYs the raw record (whole-record dump).</li>
 *   <li>Looks up the XREF entry by card number ({@code 2000-LOOKUP-XREF}).</li>
 *   <li>If the XREF lookup succeeds, reads the ACCOUNT record by
 *       account ID ({@code 3000-READ-ACCOUNT}) and DISPLAYs whether the
 *       read succeeded or failed.</li>
 *   <li>If the XREF lookup fails, DISPLAYs a verification-failure message
 *       and skips the transaction.</li>
 * </ol>
 *
 * <p>CBTRN01C does not modify any file beyond opening/closing the inputs;
 * it is essentially a diagnostic / verification pre-pass. The actual
 * posting is performed by {@link CbTrn02C}.
 */
@CobolProgram(
        value = "CBTRN01C",
        sourcePath = "app/cbl/CBTRN01C.cbl",
        notes = "Daily transaction loader / validator; verifies each DALYTRAN against XREF and ACCT"
)
public final class CbTrn01C {

    private static final Logger log = LoggerFactory.getLogger(CbTrn01C.class);

    /** Mirror of the COBOL PROGRAM-ID. */
    public static final String PROGRAM_ID = "CBTRN01C";

    private final DailyTransactionRepository dailyTransactionRepository;
    private final CustomerRepository customerRepository;
    private final CardXrefRepository xrefRepository;
    private final CardRepository cardRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    /**
     * Constructor injection per AAP &sect;0.3. The COBOL program opens all
     * five files but only reads from DALYTRAN, XREFFILE, and ACCTFILE; the
     * CUSTFILE, CARDFILE, and TRANSACT opens/closes are preserved
     * verbatim even though no I/O is issued against them.
     */
    public CbTrn01C(DailyTransactionRepository dailyTransactionRepository,
                    CustomerRepository customerRepository,
                    CardXrefRepository xrefRepository,
                    CardRepository cardRepository,
                    AccountRepository accountRepository,
                    TransactionRepository transactionRepository) {
        this.dailyTransactionRepository = Objects.requireNonNull(dailyTransactionRepository,
                "dailyTransactionRepository");
        this.customerRepository = Objects.requireNonNull(customerRepository,
                "customerRepository");
        this.xrefRepository = Objects.requireNonNull(xrefRepository, "xrefRepository");
        this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository");
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository");
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository");
    }

    /**
     * Drives the program as the COBOL PROCEDURE DIVISION does.
     *
     * @throws AbendException on file I/O failure
     */
    public void run() {
        log.info("START OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
        try (Stream<DalyTranRecord> records = dailyTransactionRepository.streamSequential()) {
            records.forEach(this::processDalyTranRecord);
        } catch (RuntimeException e) {
            log.error("ERROR READING DAILY TRANSACTION FILE");
            displayIoStatus("12");
            closeAll();
            abendProgram(e);
            return;
        }
        closeAll();
        log.info("END OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
    }

    /**
     * Mirrors the per-iteration body of the COBOL main loop.
     */
    private void processDalyTranRecord(DalyTranRecord record) {
        // DISPLAY DALYTRAN-RECORD
        log.info("{}", new String(record.encode(), StandardCharsets.ISO_8859_1));

        // 2000-LOOKUP-XREF
        Optional<CardXrefRecord> xref = lookupXref(record.dalytranCardNum());
        if (xref.isPresent()) {
            // Pull ACCT-ID from the xref and read the account
            long acctId = xref.get().xrefAcctId();
            Optional<AccountRecord> account = readAccount(acctId);
            if (account.isEmpty()) {
                // DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'
                log.info("ACCOUNT {} NOT FOUND", acctId);
            }
        } else {
            // DISPLAY 'CARD NUMBER ' DALYTRAN-CARD-NUM
            //         ' COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'
            //         DALYTRAN-ID
            log.info("CARD NUMBER {} COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-{}",
                    record.dalytranCardNum(), record.dalytranId());
        }
    }

    /**
     * Mirrors {@code 2000-LOOKUP-XREF}: reads the XREF entry by card
     * number. On success, the COBOL paragraph displays the XREF fields;
     * the Java translation emits the same labeled lines via the logger.
     */
    private Optional<CardXrefRecord> lookupXref(String cardNum) {
        Optional<CardXrefRecord> xref = xrefRepository.findByCardNumber(cardNum);
        if (xref.isPresent()) {
            log.info("SUCCESSFUL READ OF XREF");
            log.info("CARD NUMBER: {}", xref.get().xrefCardNum());
            log.info("ACCOUNT ID : {}", xref.get().xrefAcctId());
            log.info("CUSTOMER ID: {}", xref.get().xrefCustId());
        } else {
            log.info("INVALID CARD NUMBER FOR XREF");
        }
        return xref;
    }

    /**
     * Mirrors {@code 3000-READ-ACCOUNT}: reads the account by ID; emits a
     * SUCCESSFUL/INVALID line per the COBOL DISPLAY statements.
     */
    private Optional<AccountRecord> readAccount(long acctId) {
        Optional<AccountRecord> account = accountRepository.findById(acctId);
        if (account.isPresent()) {
            log.info("SUCCESSFUL READ OF ACCOUNT FILE");
        } else {
            log.info("INVALID ACCOUNT NUMBER FOUND");
        }
        return account;
    }

    private void closeAll() {
        closeQuietly(dailyTransactionRepository, "DAILY TRANSACTION FILE");
        closeQuietly(customerRepository, "CUSTOMER FILE");
        closeQuietly(xrefRepository, "CROSS REF FILE");
        closeQuietly(cardRepository, "CARDFILE");
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

    private void displayIoStatus(String ioStatus) {
        String formatted = ioStatus == null || ioStatus.isBlank()
                ? "0000"
                : "00" + ioStatus;
        log.info("FILE STATUS IS: NNNN{}", formatted);
    }

    private void abendProgram(Throwable cause) {
        log.error("ABENDING PROGRAM");
        throw new AbendException(999, "CBTRN01C abend", cause);
    }
}
