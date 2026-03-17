/*
 * CustomerFileReader.java — Spring Batch ItemReader for Customer Entity
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 *   - CBCUS01C.cbl (178 lines) — Customer File Reader Utility
 *   - CVCUS01Y.cpy / CUSTREC.cpy — CUSTOMER-RECORD layout (500 bytes)
 *
 * This class replaces the COBOL batch utility program CBCUS01C.cbl, which
 * sequentially reads all records from the CUSTDAT VSAM KSDS dataset and
 * displays their contents. The original program opens the customer file,
 * reads each record in sequence, displays key fields (customer ID, first
 * name, last name), and closes the file upon reaching end-of-file.
 *
 * COBOL Paragraph → Java Method Mapping:
 *   0000-CUSTFILE-OPEN     → lazy initialization in read() [first invocation]
 *   1000-CUSTFILE-GET-NEXT → read() [returns Customer or null for EOF]
 *   9000-CUSTFILE-CLOSE    → implicit (iterator exhaustion)
 *   Z-ABEND-PROGRAM        → DataAccessException propagation
 *   Z-DISPLAY-IO-STATUS    → SLF4J error logging
 *
 * Key differences from COBOL CBCUS01C.cbl:
 *   - VSAM KSDS keyed sequential read → JPA findAll() with Iterator
 *   - CUST-ID as primary key (PIC 9(09)) → custId String PK
 *   - FILE STATUS code checking → Spring DataAccessException hierarchy
 *   - DISPLAY statement output → SLF4J structured logging
 *   - CEE3ABD abend handling → exception propagation to Spring Batch
 *
 * SECURITY NOTE:
 *   The Customer entity contains PII fields including SSN (custSsn).
 *   This reader MUST NOT log SSN or other PII data. Only non-sensitive
 *   identification fields (custId, custFirstName, custLastName) are
 *   logged at DEBUG level.
 */
package com.cardemo.batch.readers;

import com.cardemo.model.entity.Customer;
import com.cardemo.repository.CustomerRepository;

import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

/**
 * Spring Batch {@link ItemReader} that reads all {@link Customer} records from
 * the PostgreSQL {@code customers} table via {@link CustomerRepository#findAll()}.
 *
 * <p>This is a diagnostic/utility batch reader replacing the COBOL program
 * {@code CBCUS01C.cbl} (178 lines). The original program performs a sequential
 * read of the {@code CUSTDAT} VSAM KSDS dataset (500-byte records, keyed on
 * {@code CUST-ID PIC 9(09)}), displaying each record's key fields to SYSOUT.</p>
 *
 * <p>The Java implementation uses lazy initialization: on the first call to
 * {@link #read()}, it fetches all customers from the repository and creates
 * an iterator. Subsequent calls return the next customer until the iterator
 * is exhausted, at which point {@code null} is returned to signal end-of-file
 * (matching Spring Batch's EOF convention).</p>
 *
 * <h3>COBOL I/O Pattern Replaced</h3>
 * <pre>
 * OPEN INPUT CUSTFILE               → lazy findAll() on first read()
 * READ CUSTFILE INTO CUSTOMER-REC   → iterator.next()
 * AT END SET END-OF-FILE TO TRUE    → iterator.hasNext() == false → return null
 * CLOSE CUSTFILE                    → implicit (no resource to close)
 * </pre>
 *
 * <h3>PII Protection</h3>
 * <p>The Customer entity contains PII fields including SSN ({@code custSsn}).
 * This reader intentionally logs only non-sensitive fields ({@code custId},
 * {@code custFirstName}, {@code custLastName}) at DEBUG level. No SSN,
 * address, phone, or other PII is ever included in log output.</p>
 *
 * @see Customer
 * @see CustomerRepository
 * @see <a href="https://github.com/aws-samples/carddemo/blob/27d6c6f/app/cbl/CBCUS01C.cbl">
 *      CBCUS01C.cbl</a>
 */
@Component
public class CustomerFileReader implements ItemReader<Customer> {

    private static final Logger log = LoggerFactory.getLogger(CustomerFileReader.class);

    /**
     * COBOL program identifier for traceability logging.
     * Matches the original program-id 'CBCUS01C' from CBCUS01C.cbl line 7.
     */
    private static final String COBOL_PROGRAM_ID = "CBCUS01C";

    /**
     * JPA repository providing access to the customers table.
     * Replaces the COBOL FD CUSTFILE / VSAM CUSTDAT dataset.
     */
    @Autowired
    private CustomerRepository customerRepository;

    /**
     * Iterator over all customer records. Initialized lazily on the first
     * call to {@link #read()}. Replaces the COBOL sequential READ loop
     * with VSAM READNEXT semantics.
     */
    private Iterator<Customer> iterator;

    /**
     * Lazy initialization flag. {@code true} after the first successful
     * call to {@link CustomerRepository#findAll()}, corresponding to the
     * COBOL paragraph {@code 0000-CUSTFILE-OPEN} which opens the VSAM file.
     */
    private boolean initialized;

    /**
     * Running count of records read, for diagnostic logging.
     * Mirrors the implicit record count in the COBOL DISPLAY loop.
     */
    private long recordCount;

    /**
     * Reads the next {@link Customer} record from the dataset.
     *
     * <p>On the first invocation, this method performs the equivalent of
     * COBOL paragraph {@code 0000-CUSTFILE-OPEN}: fetching all customer
     * records from the repository. Subsequent invocations return the next
     * record (equivalent to {@code 1000-CUSTFILE-GET-NEXT}) until the
     * dataset is exhausted, at which point {@code null} is returned to
     * signal end-of-file to Spring Batch.</p>
     *
     * <p><strong>PII Note:</strong> Only {@code custId}, {@code custFirstName},
     * and {@code custLastName} are logged. No SSN, address, phone, or other
     * PII data is ever included in diagnostic output.</p>
     *
     * @return the next {@link Customer} record, or {@code null} if all
     *         records have been read (end-of-file)
     * @throws DataAccessException if a database error occurs during the
     *         initial fetch (equivalent to COBOL FILE STATUS != '00')
     */
    @Override
    public Customer read() {
        if (!initialized) {
            log.info("START OF EXECUTION OF PROGRAM {}", COBOL_PROGRAM_ID);
            log.info("Opening customer dataset — fetching all records from repository");
            try {
                iterator = customerRepository.findAll().iterator();
                initialized = true;
                recordCount = 0;
            } catch (DataAccessException ex) {
                log.error("Error opening customer dataset (FILE STATUS equivalent): {}",
                        ex.getMessage(), ex);
                throw ex;
            }
        }

        if (iterator.hasNext()) {
            Customer customer = iterator.next();
            recordCount++;
            // SECURITY: Log only non-PII fields — NEVER log SSN, address, or phone
            log.debug("Customer record read [{}]: custId={}, custFirstName={}, custLastName={}",
                    recordCount,
                    customer.getCustId(),
                    customer.getCustFirstName(),
                    customer.getCustLastName());
            return customer;
        }

        log.info("End of customer dataset reached — {} records read", recordCount);
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
        log.debug("CustomerFileReader reset — next read() will re-initialize");
    }
}
