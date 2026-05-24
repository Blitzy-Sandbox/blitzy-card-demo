/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.customer;

import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.CustomerRepository;
import com.blitzy.carddemo.domain.record.CustomerRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Java translation of the {@code CBCUS01C} COBOL batch program at
 * {@code app/cbl/CBCUS01C.cbl} ("Read and print customer data file").
 *
 * <h2>DOUBLE-DISPLAY anomaly (AAP &sect;0.7.1)</h2>
 * <p>Like {@link com.blitzy.carddemo.application.account.CbAct01C} and
 * {@link com.blitzy.carddemo.application.account.CbAct03C}, this program
 * displays each successfully-read record TWICE: once from the main loop
 * and once from inside {@code 1000-CUSTFILE-GET-NEXT}.
 *
 * <h2>SSN masking (AAP &sect;0.7.2)</h2>
 * <p>The customer record contains a 9-digit SSN. The whole-record DISPLAY
 * emits the raw 500-byte buffer. The Logback pattern layout in the
 * deployed shaded jar applies SSN masking automatically.
 */
@CobolProgram(
        value = "CBCUS01C",
        sourcePath = "app/cbl/CBCUS01C.cbl",
        notes = "Sequential CUSTFILE dump; DOUBLE-DISPLAY anomaly preserved per AAP §0.7.1"
)
public final class CbCus01C {

    private static final Logger log = LoggerFactory.getLogger(CbCus01C.class);

    /** Mirror of the COBOL PROGRAM-ID. */
    public static final String PROGRAM_ID = "CBCUS01C";

    private final CustomerRepository customerRepository;

    public CbCus01C(CustomerRepository customerRepository) {
        this.customerRepository = Objects.requireNonNull(customerRepository,
                "customerRepository");
    }

    /**
     * Drives the program as the COBOL PROCEDURE DIVISION does.
     *
     * @throws AbendException if a file I/O error occurs
     */
    public void run() {
        log.info("START OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
        openFile();
        try (Stream<CustomerRecord> records = customerRepository.streamSequential()) {
            records.forEach(record -> {
                // 1000-CUSTFILE-GET-NEXT body: DISPLAY-1
                displayWholeRecord(record);
                // Main loop: DISPLAY-2 (DOUBLE-DISPLAY anomaly)
                displayWholeRecord(record);
            });
        } catch (RuntimeException e) {
            log.error("ERROR READING CUSTFILE");
            displayIoStatus("12");
            closeFile();
            abendProgram(e);
            return;
        }
        closeFile();
        log.info("END OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
    }

    private void openFile() {
        // Repository lazy-opens on first stream()
    }

    private void closeFile() {
        try {
            customerRepository.close();
        } catch (Exception e) {
            log.error("ERROR CLOSING CUSTFILE: {}", e.getMessage());
            displayIoStatus("12");
        }
    }

    private void displayWholeRecord(CustomerRecord record) {
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
        throw new AbendException(999, "CBCUS01C abend", cause);
    }
}
