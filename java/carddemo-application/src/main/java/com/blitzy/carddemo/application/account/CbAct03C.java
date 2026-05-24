/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.account;

import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.record.CardXrefRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Java translation of the {@code CBACT03C} COBOL batch program at
 * {@code app/cbl/CBACT03C.cbl} ("Read and print card cross-reference file").
 *
 * <h2>DOUBLE-DISPLAY anomaly (AAP &sect;0.7.1)</h2>
 * <p>Like {@link CbAct01C}, this program displays each successfully-read
 * record TWICE: once from the main loop ({@code DISPLAY CARD-XREF-RECORD}
 * in the PERFORM UNTIL) and once from inside {@code 1000-XREFFILE-GET-NEXT}
 * after a successful READ. The Java translation calls the whole-record
 * display twice to preserve the COBOL stdout exactly. MIGRATION_NOTES.md
 * &sect;1.4.5 documents the preservation.
 *
 * <h2>Paragraph mapping</h2>
 * <ul>
 *   <li>{@code 0000-XREFFILE-OPEN}     -&gt; {@link #openFile()}</li>
 *   <li>{@code 1000-XREFFILE-GET-NEXT}  -&gt; iteration; emits DISPLAY-1 of the pair</li>
 *   <li>main loop                       -&gt; emits DISPLAY-2 of the pair</li>
 *   <li>{@code 9000-XREFFILE-CLOSE}    -&gt; {@link #closeFile()}</li>
 *   <li>{@code 9910-DISPLAY-IO-STATUS}  -&gt; {@link #displayIoStatus(String)}</li>
 *   <li>{@code 9999-ABEND-PROGRAM}     -&gt; throws {@link AbendException}</li>
 * </ul>
 */
@CobolProgram(
        value = "CBACT03C",
        sourcePath = "app/cbl/CBACT03C.cbl",
        notes = "Sequential XREFFILE dump; DOUBLE-DISPLAY anomaly preserved per AAP §0.7.1"
)
public final class CbAct03C {

    private static final Logger log = LoggerFactory.getLogger(CbAct03C.class);

    /** Mirror of the COBOL PROGRAM-ID. */
    public static final String PROGRAM_ID = "CBACT03C";

    private final CardXrefRepository cardXrefRepository;

    public CbAct03C(CardXrefRepository cardXrefRepository) {
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository,
                "cardXrefRepository");
    }

    /**
     * Drives the program as the COBOL PROCEDURE DIVISION does. Each record
     * is displayed twice (DOUBLE-DISPLAY anomaly).
     *
     * @throws AbendException if a file I/O error occurs
     */
    public void run() {
        log.info("START OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
        openFile();
        try (Stream<CardXrefRecord> records = cardXrefRepository.streamSequential()) {
            records.forEach(record -> {
                // 1000-XREFFILE-GET-NEXT body: DISPLAY-1
                displayWholeRecord(record);
                // Main loop: DISPLAY-2 (the DOUBLE-DISPLAY anomaly)
                displayWholeRecord(record);
            });
        } catch (RuntimeException e) {
            log.error("ERROR READING XREFFILE");
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
            cardXrefRepository.close();
        } catch (Exception e) {
            log.error("ERROR CLOSING XREFFILE: {}", e.getMessage());
            displayIoStatus("12");
        }
    }

    private void displayWholeRecord(CardXrefRecord record) {
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
        throw new AbendException(999, "CBACT03C abend", cause);
    }
}
