/*
 * AccountFileReader.java — Spring Batch ItemReader for Account Entity
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 *   - CBACT01C.cbl (193 lines) — Account File Reader Utility
 *   - CVACT01Y.cpy — ACCOUNT-RECORD layout (300 bytes, 12 data fields + FILLER)
 *
 * This class replaces the COBOL batch utility program CBACT01C.cbl, which
 * sequentially reads all records from the ACCTDAT VSAM KSDS dataset and
 * displays their contents. The original program opens the file, reads each
 * record in sequence, displays key fields (account ID, status, balance,
 * credit limit, group ID), and closes the file upon reaching end-of-file.
 *
 * COBOL Paragraph → Java Method Mapping:
 *   0000-ACCTFILE-OPEN     → lazy initialization in read() [first invocation]
 *   1000-ACCTFILE-GET-NEXT → read() [returns Account or null for EOF]
 *   9000-ACCTFILE-CLOSE    → implicit (iterator exhaustion)
 *   Z-ABEND-PROGRAM        → DataAccessException propagation
 *   Z-DISPLAY-IO-STATUS    → SLF4J error logging
 *
 * Key differences from COBOL CBACT01C.cbl:
 *   - VSAM KSDS keyed sequential read → JPA findAll() with Iterator
 *   - FILE STATUS code checking → Spring DataAccessException hierarchy
 *   - DISPLAY statement output → SLF4J structured logging
 *   - CEE3ABD abend handling → exception propagation to Spring Batch
 *
 * Decision Log Reference:
 *   D-001: BigDecimal for all COMP-3/COMP fields (ACCT-CURR-BAL, ACCT-CREDIT-LIMIT)
 */
package com.cardemo.batch.readers;

import com.cardemo.model.entity.Account;
import com.cardemo.repository.AccountRepository;

import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

/**
 * Spring Batch {@link ItemReader} that reads all {@link Account} records from
 * the PostgreSQL {@code accounts} table via {@link AccountRepository#findAll()}.
 *
 * <p>This is a diagnostic/utility batch reader replacing the COBOL program
 * {@code CBACT01C.cbl} (193 lines). The original program performs a sequential
 * read of the {@code ACCTDAT} VSAM KSDS dataset (300-byte records, keyed on
 * {@code ACCT-ID PIC 9(11)}), displaying each record's key fields to SYSOUT.</p>
 *
 * <p>The Java implementation uses lazy initialization: on the first call to
 * {@link #read()}, it fetches all accounts from the repository and creates an
 * iterator. Subsequent calls return the next account until the iterator is
 * exhausted, at which point {@code null} is returned to signal end-of-file
 * (matching Spring Batch's EOF convention).</p>
 *
 * <h3>COBOL I/O Pattern Replaced</h3>
 * <pre>
 * OPEN INPUT ACCTFILE              → lazy findAll() on first read()
 * READ ACCTFILE INTO ACCOUNT-RECORD → iterator.next()
 * AT END SET END-OF-FILE TO TRUE   → iterator.hasNext() == false → return null
 * CLOSE ACCTFILE                   → implicit (no resource to close)
 * </pre>
 *
 * <h3>Error Handling</h3>
 * <p>COBOL FILE STATUS error codes are replaced by Spring's
 * {@link DataAccessException} hierarchy. On any database error, the exception
 * is logged and propagated to the Spring Batch framework, which handles step
 * failure and potential retry — equivalent to the COBOL CEE3ABD abend path.</p>
 *
 * @see Account
 * @see AccountRepository
 * @see <a href="https://github.com/aws-samples/carddemo/blob/27d6c6f/app/cbl/CBACT01C.cbl">
 *      CBACT01C.cbl</a>
 */
@Component
public class AccountFileReader implements ItemReader<Account> {

    private static final Logger log = LoggerFactory.getLogger(AccountFileReader.class);

    /**
     * COBOL program identifier for traceability logging.
     * Matches the original program-id 'CBACT01C' from CBACT01C.cbl line 7.
     */
    private static final String COBOL_PROGRAM_ID = "CBACT01C";

    /**
     * JPA repository providing access to the accounts table.
     * Replaces the COBOL FD ACCTFILE / VSAM ACCTDAT dataset.
     */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * Iterator over all account records. Initialized lazily on the first
     * call to {@link #read()}. Replaces the COBOL sequential READ loop
     * with VSAM READNEXT semantics.
     */
    private Iterator<Account> iterator;

    /**
     * Lazy initialization flag. {@code true} after the first successful
     * call to {@link AccountRepository#findAll()}, corresponding to the
     * COBOL paragraph {@code 0000-ACCTFILE-OPEN} which opens the VSAM file.
     */
    private boolean initialized;

    /**
     * Running count of records read, for diagnostic logging.
     * Mirrors the implicit record count in the COBOL DISPLAY loop.
     */
    private long recordCount;

    /**
     * Reads the next {@link Account} record from the dataset.
     *
     * <p>On the first invocation, this method performs the equivalent of
     * COBOL paragraph {@code 0000-ACCTFILE-OPEN}: fetching all account
     * records from the repository. Subsequent invocations return the next
     * record (equivalent to {@code 1000-ACCTFILE-GET-NEXT}) until the
     * dataset is exhausted, at which point {@code null} is returned to
     * signal end-of-file to Spring Batch.</p>
     *
     * @return the next {@link Account} record, or {@code null} if all
     *         records have been read (end-of-file)
     * @throws DataAccessException if a database error occurs during the
     *         initial fetch (equivalent to COBOL FILE STATUS != '00')
     */
    @Override
    public Account read() {
        if (!initialized) {
            log.info("START OF EXECUTION OF PROGRAM {}", COBOL_PROGRAM_ID);
            log.info("Opening account dataset — fetching all records from repository");
            try {
                iterator = accountRepository.findAll().iterator();
                initialized = true;
                recordCount = 0;
            } catch (DataAccessException ex) {
                log.error("Error opening account dataset (FILE STATUS equivalent): {}",
                        ex.getMessage(), ex);
                throw ex;
            }
        }

        if (iterator.hasNext()) {
            Account account = iterator.next();
            recordCount++;
            log.debug("Account record read [{}]: acctId={}, activeStatus={}, "
                    + "currBal={}, creditLimit={}, groupId={}",
                    recordCount,
                    account.getAcctId(),
                    account.getAcctActiveStatus(),
                    account.getAcctCurrBal(),
                    account.getAcctCreditLimit(),
                    account.getAcctGroupId());
            return account;
        }

        log.info("End of account dataset reached — {} records read", recordCount);
        log.info("END OF EXECUTION OF PROGRAM {}", COBOL_PROGRAM_ID);
        return null;
    }

    /**
     * Resets the reader state, allowing re-reading of the dataset.
     *
     * <p>This method has no direct COBOL equivalent — in the original program,
     * the file is simply closed and re-opened. In Java, we reset the iterator
     * and initialization flag so that the next call to {@link #read()} will
     * re-fetch all records from the repository.</p>
     */
    public void reset() {
        initialized = false;
        iterator = null;
        recordCount = 0;
        log.debug("AccountFileReader reset — next read() will re-initialize");
    }
}
