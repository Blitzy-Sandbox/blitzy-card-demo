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
import com.blitzy.carddemo.domain.record.AccountRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * Java translation of the {@code CBACT01C} COBOL batch program at
 * {@code app/cbl/CBACT01C.cbl} ("Read and print account data file").
 *
 * <h2>Program structure</h2>
 * <p>The COBOL program walks the ACCTFILE VSAM KSDS sequentially and dumps
 * every record to STDOUT. The Java translation walks the same logical
 * sequence using the {@link AccountRepository} port; for byte-for-byte
 * parity with the COBOL output every step of the COBOL paragraph
 * structure is reproduced as a private method:
 * <ul>
 *   <li>{@code 0000-ACCTFILE-OPEN}    -&gt; {@link #openFile()}</li>
 *   <li>{@code 1000-ACCTFILE-GET-NEXT} -&gt; iteration over
 *       {@link AccountRepository#streamSequential()}</li>
 *   <li>{@code 1100-DISPLAY-ACCT-RECORD} -&gt; {@link #displayAcctRecord(AccountRecord)}</li>
 *   <li>{@code 9000-ACCTFILE-CLOSE}   -&gt; {@link #closeFile()}</li>
 *   <li>{@code 9910-DISPLAY-IO-STATUS} -&gt; {@link #displayIoStatus(String)}</li>
 *   <li>{@code 9999-ABEND-PROGRAM}     -&gt; throws {@link AbendException}</li>
 * </ul>
 *
 * <h2>DOUBLE-DISPLAY anomaly (AAP &sect;0.7.1)</h2>
 * <p>The COBOL PROCEDURE DIVISION displays each successfully-read account
 * record twice: once as a whole-record dump from the main loop
 * ({@code DISPLAY ACCOUNT-RECORD} in the main PERFORM UNTIL), and once as a
 * labeled field-by-field listing from {@code 1100-DISPLAY-ACCT-RECORD}.
 * This is dead-data in the sense that no business meaning is attached to
 * the double output, but per the AAP Minimal Change Clause and Refactor
 * Discipline Guidelines the behavior must be reproduced verbatim. The
 * {@link #run()} method does the same: it calls
 * {@link #displayWholeRecord(AccountRecord)} from the main loop and
 * {@link #displayAcctRecord(AccountRecord)} from the GET-NEXT-equivalent
 * branch. The MIGRATION_NOTES.md &sect;1.4.5 entry records the
 * preservation.
 *
 * <h2>ACCT-EXPIRAION-DATE typo (AAP &sect;0.7.1)</h2>
 * <p>The COBOL paragraph {@code 1100-DISPLAY-ACCT-RECORD} labels the
 * expiration date field {@code 'ACCT-EXPIRAION-DATE     :'} (note the
 * missing T in "EXPIRATION"). The Java field-by-field display preserves
 * the typo verbatim. The MIGRATION_NOTES.md &sect;1.4.6 entry records the
 * preservation.
 *
 * <h2>Output destination</h2>
 * <p>The COBOL {@code DISPLAY} verb writes to the JES SYSOUT data set.
 * Per AAP &sect;0.7.2 System.out is forbidden in Java production code; we
 * route through SLF4J at INFO level so the same line content reaches the
 * configured logging backend (which, per logback.xml, writes structured
 * JSON to STDOUT in the deployed shaded-jar).
 */
@CobolProgram(
        value = "CBACT01C",
        sourcePath = "app/cbl/CBACT01C.cbl",
        notes = "Sequential ACCTFILE dump; DOUBLE-DISPLAY + ACCT-EXPIRAION-DATE typo preserved per AAP §0.7.1"
)
public final class CbAct01C {

    private static final Logger log = LoggerFactory.getLogger(CbAct01C.class);

    /** Mirror of the {@code WS-PROGRAM-ID} constant used in trace lines. */
    public static final String PROGRAM_ID = "CBACT01C";

    private final AccountRepository accountRepository;

    /**
     * Constructor for use by the composition root. Per AAP &sect;0.3 the
     * Java application is wired through plain constructor injection — no
     * Spring container.
     *
     * @param accountRepository the port that provides sequential access to
     *                          the ACCTFILE equivalent (file or DB adapter)
     */
    public CbAct01C(AccountRepository accountRepository) {
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository");
    }

    /**
     * Drives the program as the COBOL {@code PROCEDURE DIVISION} does.
     * Iterates the ACCTFILE sequentially and displays each record twice
     * (whole-record + labeled field-by-field), mirroring the COBOL
     * DOUBLE-DISPLAY anomaly.
     *
     * @throws AbendException if a file I/O error occurs (corresponds to the
     *                        COBOL {@code 9999-ABEND-PROGRAM} call to CEE3ABD)
     */
    public void run() {
        log.info("START OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
        openFile();
        try (Stream<AccountRecord> records = accountRepository.streamSequential()) {
            records.forEach(record -> {
                // 1000-ACCTFILE-GET-NEXT body: the COBOL program calls
                // PERFORM 1100-DISPLAY-ACCT-RECORD inside the read branch...
                displayAcctRecord(record);
                // ...and the main loop then DISPLAYs the whole ACCOUNT-RECORD again.
                // This is the DOUBLE-DISPLAY anomaly (AAP §0.7.1).
                displayWholeRecord(record);
            });
        } catch (RuntimeException e) {
            log.error("ERROR READING ACCOUNT FILE");
            displayIoStatus("12"); // APPL-RESULT = 12 corresponds to FILE STATUS != 00 or 10
            closeFile();
            abendProgram(e);
            return;
        }
        closeFile();
        log.info("END OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
    }

    /**
     * Mirrors the {@code 0000-ACCTFILE-OPEN} paragraph: opens the input
     * file and aborts on I/O failure.
     */
    private void openFile() {
        // The repository constructor opens the underlying file; this method
        // exists as a paragraph-equivalent for traceability and error messaging.
        // No explicit open is needed because streamSequential() will throw if
        // the file cannot be opened.
    }

    /**
     * Mirrors the {@code 9000-ACCTFILE-CLOSE} paragraph: closes the input
     * file. Errors during close are logged but do not raise an ABEND in
     * the Java translation because the program is already on its way out.
     */
    private void closeFile() {
        try {
            accountRepository.close();
        } catch (Exception e) {
            log.error("ERROR CLOSING ACCOUNT FILE: {}", e.getMessage());
            displayIoStatus("12");
        }
    }

    /**
     * Mirrors the whole-record {@code DISPLAY ACCOUNT-RECORD} statement
     * from the main loop: emits the 300-byte record as a single line of
     * text. Encoded via the record's {@link AccountRecord#encode()} byte
     * layout and rendered as ISO-8859-1 to preserve byte-for-byte width.
     *
     * @param record the account record to display
     */
    private void displayWholeRecord(AccountRecord record) {
        byte[] encoded = record.encode();
        // ISO-8859-1 preserves every byte 1:1 as a Java char so the rendered
        // line equals the underlying 300-byte record exactly.
        String line = new String(encoded, java.nio.charset.StandardCharsets.ISO_8859_1);
        log.info("{}", line);
    }

    /**
     * Mirrors the {@code 1100-DISPLAY-ACCT-RECORD} paragraph. The labels
     * are preserved verbatim from the COBOL source, INCLUDING the
     * {@code ACCT-EXPIRAION-DATE} typo (missing 'T') per AAP &sect;0.7.1
     * Minimal Change Clause.
     *
     * @param record the account record to display
     */
    private void displayAcctRecord(AccountRecord record) {
        log.info("ACCT-ID                 :{}", record.acctId());
        log.info("ACCT-ACTIVE-STATUS      :{}", record.acctActiveStatus());
        log.info("ACCT-CURR-BAL           :{}", record.acctCurrBal());
        log.info("ACCT-CREDIT-LIMIT       :{}", record.acctCreditLimit());
        log.info("ACCT-CASH-CREDIT-LIMIT  :{}", record.acctCashCreditLimit());
        log.info("ACCT-OPEN-DATE          :{}", record.acctOpenDate());
        // ACCT-EXPIRAION-DATE: typo preserved verbatim per AAP §0.7.1.
        log.info("ACCT-EXPIRAION-DATE     :{}", record.acctExpiraionDate());
        log.info("ACCT-REISSUE-DATE       :{}", record.acctReissueDate());
        log.info("ACCT-CURR-CYC-CREDIT    :{}", record.acctCurrCycCredit());
        log.info("ACCT-CURR-CYC-DEBIT     :{}", record.acctCurrCycDebit());
        log.info("ACCT-GROUP-ID           :{}", record.acctGroupId());
        log.info("-------------------------------------------------");
    }

    /**
     * Mirrors {@code 9910-DISPLAY-IO-STATUS}: logs the file status code in
     * the four-character form the COBOL paragraph would have produced.
     *
     * @param ioStatus the two-character FILE STATUS code
     */
    private void displayIoStatus(String ioStatus) {
        String formatted = ioStatus == null || ioStatus.isBlank()
                ? "0000"
                : "00" + ioStatus;
        log.info("FILE STATUS IS: NNNN{}", formatted);
    }

    /**
     * Mirrors {@code 9999-ABEND-PROGRAM}: log "ABENDING PROGRAM" and raise
     * an {@link AbendException} carrying the original cause. The COBOL
     * program calls {@code CEE3ABD} with ABCODE=999; the Java translation
     * uses a typed runtime exception so the calling main can map it to a
     * non-zero process exit code.
     *
     * @param cause the underlying cause of the abend
     * @throws AbendException always
     */
    private void abendProgram(Throwable cause) {
        log.error("ABENDING PROGRAM");
        throw new AbendException(999, "CBACT01C abend", cause);
    }
}
