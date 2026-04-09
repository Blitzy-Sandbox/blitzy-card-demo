/*
 * CardFileReader.java — Spring Batch ItemReader for Card Entity
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 *   - CBACT02C.cbl (178 lines) — Card File Reader Utility
 *   - CVACT02Y.cpy — CARD-RECORD layout (150 bytes, 6 data fields + FILLER)
 *
 * This class replaces the COBOL batch utility program CBACT02C.cbl, which
 * sequentially reads all records from the CARDDAT VSAM KSDS dataset and
 * displays their contents. The original program opens the file, reads each
 * record in sequence, displays key fields (card number, account ID, active
 * status), and closes the file upon reaching end-of-file.
 *
 * COBOL Paragraph → Java Method Mapping:
 *   0000-CARDFILE-OPEN     → lazy initialization in read() [first invocation]
 *   1000-CARDFILE-GET-NEXT → read() [returns Card or null for EOF]
 *   9000-CARDFILE-CLOSE    → implicit (iterator exhaustion)
 *   Z-ABEND-PROGRAM        → DataAccessException propagation
 *   Z-DISPLAY-IO-STATUS    → SLF4J error logging
 *
 * Key differences from COBOL CBACT02C.cbl:
 *   - VSAM KSDS keyed sequential read → JPA findAll() with Iterator
 *   - FILE STATUS code checking → Spring DataAccessException hierarchy
 *   - DISPLAY statement output → SLF4J structured logging
 *   - CEE3ABD abend handling → exception propagation to Spring Batch
 */
package com.cardemo.batch.readers;

import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;

import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

/**
 * Spring Batch {@link ItemReader} that reads all {@link Card} records from
 * the PostgreSQL {@code cards} table via {@link CardRepository#findAll()}.
 *
 * <p>This is a diagnostic/utility batch reader replacing the COBOL program
 * {@code CBACT02C.cbl} (178 lines). The original program performs a sequential
 * read of the {@code CARDDAT} VSAM KSDS dataset (150-byte records, keyed on
 * {@code CARD-NUM PIC X(16)}), displaying each record's key fields to SYSOUT.</p>
 *
 * <p>The Java implementation uses lazy initialization: on the first call to
 * {@link #read()}, it fetches all cards from the repository and creates an
 * iterator. Subsequent calls return the next card until the iterator is
 * exhausted, at which point {@code null} is returned to signal end-of-file
 * (matching Spring Batch's EOF convention).</p>
 *
 * <h3>COBOL I/O Pattern Replaced</h3>
 * <pre>
 * OPEN INPUT CARDFILE              → lazy findAll() on first read()
 * READ CARDFILE INTO CARD-RECORD   → iterator.next()
 * AT END SET END-OF-FILE TO TRUE   → iterator.hasNext() == false → return null
 * CLOSE CARDFILE                   → implicit (no resource to close)
 * </pre>
 *
 * <h3>Error Handling</h3>
 * <p>COBOL FILE STATUS error codes are replaced by Spring's
 * {@link DataAccessException} hierarchy. On any database error, the exception
 * is logged and propagated to the Spring Batch framework, which handles step
 * failure and potential retry — equivalent to the COBOL CEE3ABD abend path.</p>
 *
 * @see Card
 * @see CardRepository
 * @see <a href="https://github.com/aws-samples/carddemo/blob/27d6c6f/app/cbl/CBACT02C.cbl">
 *      CBACT02C.cbl</a>
 */
@Component
public class CardFileReader implements ItemReader<Card> {

    private static final Logger log = LoggerFactory.getLogger(CardFileReader.class);

    /**
     * COBOL program identifier for traceability logging.
     * Matches the original program-id 'CBACT02C' from CBACT02C.cbl line 7.
     */
    private static final String COBOL_PROGRAM_ID = "CBACT02C";

    /**
     * JPA repository providing access to the cards table.
     * Replaces the COBOL FD CARDFILE / VSAM CARDDAT dataset.
     */
    @Autowired
    private CardRepository cardRepository;

    /**
     * Iterator over all card records. Initialized lazily on the first
     * call to {@link #read()}. Replaces the COBOL sequential READ loop
     * with VSAM READNEXT semantics.
     */
    private Iterator<Card> iterator;

    /**
     * Lazy initialization flag. {@code true} after the first successful
     * call to {@link CardRepository#findAll()}, corresponding to the
     * COBOL paragraph {@code 0000-CARDFILE-OPEN} which opens the VSAM file.
     */
    private boolean initialized;

    /**
     * Running count of records read, for diagnostic logging.
     * Mirrors the implicit record count in the COBOL DISPLAY loop.
     */
    private long recordCount;

    /**
     * Reads the next {@link Card} record from the dataset.
     *
     * <p>On the first invocation, this method performs the equivalent of
     * COBOL paragraph {@code 0000-CARDFILE-OPEN}: fetching all card
     * records from the repository. Subsequent invocations return the next
     * record (equivalent to {@code 1000-CARDFILE-GET-NEXT}) until the
     * dataset is exhausted, at which point {@code null} is returned to
     * signal end-of-file to Spring Batch.</p>
     *
     * @return the next {@link Card} record, or {@code null} if all
     *         records have been read (end-of-file)
     * @throws DataAccessException if a database error occurs during the
     *         initial fetch (equivalent to COBOL FILE STATUS != '00')
     */
    @Override
    public Card read() {
        if (!initialized) {
            log.info("START OF EXECUTION OF PROGRAM {}", COBOL_PROGRAM_ID);
            log.info("Opening card dataset — fetching all records from repository");
            try {
                iterator = cardRepository.findAll().iterator();
                initialized = true;
                recordCount = 0;
            } catch (DataAccessException ex) {
                log.error("Error opening card dataset (FILE STATUS equivalent): {}",
                        ex.getMessage(), ex);
                throw ex;
            }
        }

        if (iterator.hasNext()) {
            Card card = iterator.next();
            recordCount++;
            log.debug("Card record read [{}]: cardNum={}, cardAcctId={}, activeStatus={}",
                    recordCount,
                    card.getCardNum(),
                    card.getCardAcctId(),
                    card.getCardActiveStatus());
            return card;
        }

        log.info("End of card dataset reached — {} records read", recordCount);
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
        log.debug("CardFileReader reset — next read() will re-initialize");
    }
}
