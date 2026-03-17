/*
 * CrossReferenceFileReader.java — Spring Batch ItemReader for CardCrossReference Entity
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 *   - CBACT03C.cbl (178 lines) — Cross-Reference File Reader Utility
 *   - CVACT03Y.cpy — CARD-XREF-RECORD layout (50 bytes)
 *
 * This class replaces the COBOL batch utility program CBACT03C.cbl, which
 * sequentially reads all records from the CARDXREF VSAM KSDS dataset and
 * displays their contents. The original program opens the cross-reference
 * file, reads each record in sequence, displays key fields (card number,
 * customer ID, account ID), and closes the file upon reaching end-of-file.
 *
 * COBOL Paragraph → Java Method Mapping:
 *   0000-XREFFILE-OPEN     → lazy initialization in read() [first invocation]
 *   1000-XREFFILE-GET-NEXT → read() [returns CardCrossReference or null for EOF]
 *   9000-XREFFILE-CLOSE    → implicit (iterator exhaustion)
 *   Z-ABEND-PROGRAM        → DataAccessException propagation
 *   Z-DISPLAY-IO-STATUS    → SLF4J error logging
 *
 * Key differences from COBOL CBACT03C.cbl:
 *   - VSAM KSDS keyed sequential read → JPA findAll() with Iterator
 *   - XREF-CARD-NUM as primary key (PIC X(16)) → xrefCardNum String PK
 *   - FILE STATUS code checking → Spring DataAccessException hierarchy
 *   - DISPLAY statement output → SLF4J structured logging
 *   - CEE3ABD abend handling → exception propagation to Spring Batch
 */
package com.cardemo.batch.readers;

import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.repository.CardCrossReferenceRepository;

import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

/**
 * Spring Batch {@link ItemReader} that reads all {@link CardCrossReference}
 * records from the PostgreSQL {@code card_cross_references} table via
 * {@link CardCrossReferenceRepository#findAll()}.
 *
 * <p>This is a diagnostic/utility batch reader replacing the COBOL program
 * {@code CBACT03C.cbl} (178 lines). The original program performs a sequential
 * read of the {@code CARDXREF} VSAM KSDS dataset (50-byte records, keyed on
 * {@code XREF-CARD-NUM PIC X(16)}), with an alternate index path
 * {@code CXACAIX} on {@code XREF-ACCT-ID} for account-based lookups.</p>
 *
 * <p>The Java implementation uses lazy initialization: on the first call to
 * {@link #read()}, it fetches all cross-reference records from the repository
 * and creates an iterator. Subsequent calls return the next record until the
 * iterator is exhausted, at which point {@code null} is returned to signal
 * end-of-file (matching Spring Batch's EOF convention).</p>
 *
 * <h3>COBOL I/O Pattern Replaced</h3>
 * <pre>
 * OPEN INPUT XREFFILE               → lazy findAll() on first read()
 * READ XREFFILE INTO CARD-XREF-REC  → iterator.next()
 * AT END SET END-OF-FILE TO TRUE    → iterator.hasNext() == false → return null
 * CLOSE XREFFILE                    → implicit (no resource to close)
 * </pre>
 *
 * <h3>Alternate Index Note</h3>
 * <p>The COBOL system defines the {@code CXACAIX} alternate index (AIX)
 * on {@code XREF-ACCT-ID} for the CARDXREF dataset. In the Java target,
 * this corresponds to the PostgreSQL index {@code idx_card_xref_account_id}
 * on the {@code account_id} column. The {@link CardCrossReferenceRepository}
 * provides {@code findByXrefAcctId(String)} for equivalent access.</p>
 *
 * @see CardCrossReference
 * @see CardCrossReferenceRepository
 * @see <a href="https://github.com/aws-samples/carddemo/blob/27d6c6f/app/cbl/CBACT03C.cbl">
 *      CBACT03C.cbl</a>
 */
@Component
public class CrossReferenceFileReader implements ItemReader<CardCrossReference> {

    private static final Logger log = LoggerFactory.getLogger(CrossReferenceFileReader.class);

    /**
     * COBOL program identifier for traceability logging.
     * Matches the original program-id 'CBACT03C' from CBACT03C.cbl line 7.
     */
    private static final String COBOL_PROGRAM_ID = "CBACT03C";

    /**
     * JPA repository providing access to the card_cross_references table.
     * Replaces the COBOL FD XREFFILE / VSAM CARDXREF dataset.
     */
    @Autowired
    private CardCrossReferenceRepository crossReferenceRepository;

    /**
     * Iterator over all cross-reference records. Initialized lazily on
     * the first call to {@link #read()}. Replaces the COBOL sequential
     * READ loop with VSAM READNEXT semantics.
     */
    private Iterator<CardCrossReference> iterator;

    /**
     * Lazy initialization flag. {@code true} after the first successful
     * call to {@link CardCrossReferenceRepository#findAll()}, corresponding
     * to the COBOL paragraph {@code 0000-XREFFILE-OPEN} which opens the
     * VSAM cross-reference file.
     */
    private boolean initialized;

    /**
     * Running count of records read, for diagnostic logging.
     * Mirrors the implicit record count in the COBOL DISPLAY loop.
     */
    private long recordCount;

    /**
     * Reads the next {@link CardCrossReference} record from the dataset.
     *
     * <p>On the first invocation, this method performs the equivalent of
     * COBOL paragraph {@code 0000-XREFFILE-OPEN}: fetching all cross-
     * reference records from the repository. Subsequent invocations return
     * the next record (equivalent to {@code 1000-XREFFILE-GET-NEXT}) until
     * the dataset is exhausted, at which point {@code null} is returned to
     * signal end-of-file to Spring Batch.</p>
     *
     * @return the next {@link CardCrossReference} record, or {@code null}
     *         if all records have been read (end-of-file)
     * @throws DataAccessException if a database error occurs during the
     *         initial fetch (equivalent to COBOL FILE STATUS != '00')
     */
    @Override
    public CardCrossReference read() {
        if (!initialized) {
            log.info("START OF EXECUTION OF PROGRAM {}", COBOL_PROGRAM_ID);
            log.info("Opening cross-reference dataset — fetching all records from repository");
            try {
                iterator = crossReferenceRepository.findAll().iterator();
                initialized = true;
                recordCount = 0;
            } catch (DataAccessException ex) {
                log.error("Error opening cross-reference dataset (FILE STATUS equivalent): {}",
                        ex.getMessage(), ex);
                throw ex;
            }
        }

        if (iterator.hasNext()) {
            CardCrossReference xref = iterator.next();
            recordCount++;
            log.debug("Cross-reference record read [{}]: xrefCardNum={}, xrefCustId={}, xrefAcctId={}",
                    recordCount,
                    xref.getXrefCardNum(),
                    xref.getXrefCustId(),
                    xref.getXrefAcctId());
            return xref;
        }

        log.info("End of cross-reference dataset reached — {} records read", recordCount);
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
        log.debug("CrossReferenceFileReader reset — next read() will re-initialize");
    }
}
