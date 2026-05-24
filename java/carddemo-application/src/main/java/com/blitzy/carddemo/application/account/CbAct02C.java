/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.account;

import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.CardRepository;
import com.blitzy.carddemo.domain.record.CardRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Java translation of the {@code CBACT02C} COBOL batch program at
 * {@code app/cbl/CBACT02C.cbl} ("Read and print card data file").
 *
 * <p>The COBOL program walks the CARDFILE VSAM KSDS sequentially and
 * dumps every record to STDOUT. Unlike its sibling {@link CbAct01C}
 * (which exhibits the DOUBLE-DISPLAY anomaly), CBACT02C is
 * <strong>SINGLE-DISPLAY</strong>: the field-by-field DISPLAY inside
 * {@code 1000-CARDFILE-GET-NEXT} is commented out with {@code *} in the
 * COBOL source ({@code app/cbl/CBACT02C.cbl}), so only the main-loop
 * whole-record DISPLAY fires.
 *
 * <h2>Paragraph mapping</h2>
 * <ul>
 *   <li>{@code 0000-CARDFILE-OPEN}     -&gt; {@link #openFile()}</li>
 *   <li>{@code 1000-CARDFILE-GET-NEXT}  -&gt; iteration over {@link CardRepository#streamSequential()}</li>
 *   <li>{@code 9000-CARDFILE-CLOSE}    -&gt; {@link #closeFile()}</li>
 *   <li>{@code 9910-DISPLAY-IO-STATUS}  -&gt; {@link #displayIoStatus(String)}</li>
 *   <li>{@code 9999-ABEND-PROGRAM}     -&gt; throws {@link AbendException}</li>
 * </ul>
 *
 * <h2>PAN masking (AAP &sect;0.7.2)</h2>
 * <p>The whole-record DISPLAY emits the raw 150-byte buffer including the
 * 16-character card number. The deployed shaded jar routes log output
 * through Logback which applies a PAN-masking pattern layout that
 * rewrites any 13..16 digit run to mask all but the trailing 4 digits.
 * The application code does NOT mask the bytes locally because the COBOL
 * DISPLAY produces the unmasked record; the operator's view through the
 * logger is masked.
 */
@CobolProgram(
        value = "CBACT02C",
        sourcePath = "app/cbl/CBACT02C.cbl",
        notes = "Sequential CARDFILE dump; SINGLE-DISPLAY (1100 field-by-field is commented out in source)"
)
public final class CbAct02C {

    private static final Logger log = LoggerFactory.getLogger(CbAct02C.class);

    /** Mirror of the COBOL PROGRAM-ID. */
    public static final String PROGRAM_ID = "CBACT02C";

    private final CardRepository cardRepository;

    /**
     * Constructor injection per AAP &sect;0.3.
     *
     * @param cardRepository the port that provides sequential access to CARDFILE
     */
    public CbAct02C(CardRepository cardRepository) {
        this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository");
    }

    /**
     * Drives the program as the COBOL PROCEDURE DIVISION does.
     *
     * @throws AbendException if a file I/O error occurs
     */
    public void run() {
        log.info("START OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
        openFile();
        try (Stream<CardRecord> records = cardRepository.streamSequential()) {
            records.forEach(this::displayWholeRecord);
        } catch (RuntimeException e) {
            log.error("ERROR READING CARDFILE");
            displayIoStatus("12");
            closeFile();
            abendProgram(e);
            return;
        }
        closeFile();
        log.info("END OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
    }

    private void openFile() {
        // Repository lazy-opens on first stream(); paragraph kept for traceability
    }

    private void closeFile() {
        try {
            cardRepository.close();
        } catch (Exception e) {
            log.error("ERROR CLOSING CARDFILE: {}", e.getMessage());
            displayIoStatus("12");
        }
    }

    /**
     * Mirror of the main-loop {@code DISPLAY CARD-RECORD} statement: emits
     * the 150-byte record as a single line via the logger. The Logback
     * pattern layout will apply PAN masking automatically.
     */
    private void displayWholeRecord(CardRecord record) {
        byte[] encoded = record.encode();
        String line = new String(encoded, StandardCharsets.ISO_8859_1);
        log.info("{}", line);
    }

    private void displayIoStatus(String ioStatus) {
        String formatted = ioStatus == null || ioStatus.isBlank()
                ? "0000"
                : "00" + ioStatus;
        log.info("FILE STATUS IS: NNNN{}", formatted);
    }

    private void abendProgram(Throwable cause) {
        log.error("ABENDING PROGRAM");
        throw new AbendException(999, "CBACT02C abend", cause);
    }
}
