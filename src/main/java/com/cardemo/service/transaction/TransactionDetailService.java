/*
 * TransactionDetailService.java
 *
 * Spring service class migrating COBOL program COTRN01C.cbl (330 lines) — the
 * "View a Transaction from TRANSACT file" CICS online program (transaction ID CT01).
 *
 * This service performs a single transaction keyed read by transaction ID, mapping
 * the COBOL EXEC CICS READ DATASET('TRANSACT') INTO(TRAN-RECORD) RIDFLD(TRAN-ID)
 * pattern to TransactionRepository.findById().
 *
 * COBOL Traceability (original repository commit SHA 27d6c6f):
 * - MAIN-PARA (lines 86-139): Program entry point → getTransaction() stateless entry
 * - PROCESS-ENTER-KEY (lines 144-192): Input validation + read + DTO mapping
 * - READ-TRANSACT-FILE (lines 267-296): VSAM keyed read → JPA findById()
 * - CLEAR-CURRENT-SCREEN (lines 301-304): Not needed in REST (no stateful screen)
 * - INITIALIZE-ALL-FIELDS (lines 309-326): Not needed in REST
 * - SEND-TRNVIEW-SCREEN (lines 213-225): Maps to controller JSON response
 * - POPULATE-HEADER-INFO (lines 243-262): No 3270 header in REST
 *
 * FILE STATUS Mapping:
 * - 00 (NORMAL)  → successful Optional.of(Transaction) return
 * - 23 (NOTFND)  → RecordNotFoundException("Transaction", transactionId)
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.transaction;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service class for retrieving a single transaction by its transaction ID.
 *
 * <p>Migrates the COBOL program {@code COTRN01C.cbl} (330 lines), which implements
 * the "View a Transaction from TRANSACT file" CICS online screen (transaction ID CT01).
 * The original COBOL program performs a keyed read on the TRANSACT VSAM KSDS dataset
 * and displays the transaction detail fields on a BMS 3270 terminal screen.</p>
 *
 * <p>In the Java migration, the 3270 terminal interaction is replaced by a stateless
 * service method that returns a {@link TransactionDto} with all 13 data fields from
 * the TRAN-RECORD layout (CVTRA05Y.cpy, 350 bytes).</p>
 *
 * <h3>COBOL Paragraph → Java Method Mapping</h3>
 * <table>
 *   <caption>Traceability from COTRN01C.cbl to TransactionDetailService</caption>
 *   <tr><th>COBOL Paragraph</th><th>Java Method</th><th>Notes</th></tr>
 *   <tr><td>MAIN-PARA</td><td>{@link #getTransaction(String)}</td>
 *       <td>Stateless REST entry point</td></tr>
 *   <tr><td>PROCESS-ENTER-KEY</td><td>{@link #getTransaction(String)}</td>
 *       <td>Input validation + read + map to DTO</td></tr>
 *   <tr><td>READ-TRANSACT-FILE</td><td>{@code transactionRepository.findById()}</td>
 *       <td>VSAM READ → JPA findById</td></tr>
 *   <tr><td>CLEAR-CURRENT-SCREEN</td><td>N/A</td>
 *       <td>Not needed in REST (no stateful screen)</td></tr>
 *   <tr><td>INITIALIZE-ALL-FIELDS</td><td>N/A</td>
 *       <td>Not needed in REST</td></tr>
 *   <tr><td>SEND-TRNVIEW-SCREEN</td><td>Controller response</td>
 *       <td>REST JSON response replaces BMS SEND MAP</td></tr>
 *   <tr><td>POPULATE-HEADER-INFO</td><td>N/A</td>
 *       <td>No 3270 header in REST</td></tr>
 * </table>
 *
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li>Read-only service — annotated with {@code @Transactional(readOnly = true)}
 *       to optimize JPA/Hibernate dirty-checking bypass and enable potential database
 *       routing to read replicas. The original COBOL READ uses the UPDATE flag for
 *       record locking, but COTRN01C is view-only; UPDATE is unnecessary in REST.</li>
 *   <li>BigDecimal for TRAN-AMT — COBOL {@code PIC S9(09)V99} maps to
 *       {@code BigDecimal} with precision 11 and scale 2. Zero float/double
 *       substitution per AAP §0.8.2.</li>
 *   <li>Category code conversion — Entity stores {@code tranCatCd} as {@code Short}
 *       (matching DDL SMALLINT); DTO uses {@code String} (matching COBOL PIC 9(04)).
 *       Conversion preserves leading zeros via {@code String.format("%04d", ...)}.</li>
 * </ul>
 *
 * @see Transaction
 * @see TransactionDto
 * @see TransactionRepository
 */
@Service
public class TransactionDetailService {

    private static final Logger log = LoggerFactory.getLogger(TransactionDetailService.class);

    private final TransactionRepository transactionRepository;

    /**
     * Constructs a new {@code TransactionDetailService} with the required repository
     * dependency injected by the Spring container.
     *
     * <p>Constructor injection is used (rather than field injection) per Spring best
     * practices, ensuring immutability and testability of the service bean.</p>
     *
     * @param transactionRepository the JPA repository for Transaction entity access,
     *                              providing keyed read via {@code findById(String)}
     */
    public TransactionDetailService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Retrieves a single transaction by its transaction ID.
     *
     * <p>Equivalent to the combined logic of COBOL paragraphs {@code PROCESS-ENTER-KEY}
     * (COTRN01C.cbl lines 144-192) and {@code READ-TRANSACT-FILE} (lines 267-296).
     * The method performs:</p>
     * <ol>
     *   <li><strong>Input validation</strong> (maps COTRN01C lines 146-156):
     *       Validates that the transaction ID is not null, empty, or blank.
     *       In COBOL, {@code IF TRNIDINI = SPACES OR LOW-VALUES} triggers
     *       the error message "Tran ID can NOT be empty..."</li>
     *   <li><strong>Keyed read</strong> (maps COTRN01C lines 267-296):
     *       Reads the transaction record from the database via
     *       {@code TransactionRepository.findById(transactionId)}.
     *       Maps COBOL {@code EXEC CICS READ DATASET(WS-TRANSACT-FILE)
     *       INTO(TRAN-RECORD) RIDFLD(TRAN-ID)}.</li>
     *   <li><strong>DTO mapping</strong> (maps COTRN01C lines 176-192):
     *       Converts the JPA entity to a response DTO with all 13 data fields
     *       from the TRAN-RECORD layout (CVTRA05Y.cpy).</li>
     * </ol>
     *
     * @param transactionId the 16-character transaction ID
     *                      (maps {@code TRNIDINI} input field, COBOL PIC X(16))
     * @return {@link TransactionDto} with all transaction detail fields populated
     * @throws IllegalArgumentException if {@code transactionId} is null, empty,
     *         or blank (maps COBOL line 147 validation: "Tran ID can NOT be empty...")
     * @throws RecordNotFoundException if no transaction exists with the given ID
     *         (maps COBOL DFHRESP(NOTFND) at line 283: "Transaction ID NOT found...")
     */
    @Transactional(readOnly = true)
    public TransactionDto getTransaction(String transactionId) {
        // Input validation — maps COTRN01C.cbl lines 146-156:
        // EVALUATE TRUE
        //     WHEN TRNIDINI OF COTRN1AI = SPACES OR LOW-VALUES
        //         MOVE 'Tran ID can NOT be empty...' TO WS-MESSAGE
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("Transaction ID cannot be empty");
        }

        // Keyed read — maps COTRN01C.cbl READ-TRANSACT-FILE (lines 267-296):
        // EXEC CICS READ DATASET(WS-TRANSACT-FILE) INTO(TRAN-RECORD)
        //      RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID)
        //      RESP(WS-RESP-CD)
        //
        // EVALUATE WS-RESP-CD
        //     WHEN DFHRESP(NORMAL)   → CONTINUE (success)
        //     WHEN DFHRESP(NOTFND)   → FILE STATUS 23 → RecordNotFoundException
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RecordNotFoundException("Transaction", transactionId));

        // DTO mapping — maps COTRN01C.cbl PROCESS-ENTER-KEY (lines 176-192):
        // Populate all display fields from TRAN-RECORD to screen map COTRN1AI
        TransactionDto dto = toDto(transaction);

        // Structured logging for observability (AAP §0.7.1)
        // Correlation ID propagated via MDC for distributed tracing
        log.debug("Retrieved transaction detail for ID: {}", transactionId);

        return dto;
    }

    /**
     * Converts a {@link Transaction} JPA entity to a {@link TransactionDto} response object.
     *
     * <p>Maps all 13 data fields from the TRAN-RECORD layout defined in CVTRA05Y.cpy
     * (350 bytes total, excluding the 20-byte FILLER). This corresponds to the COBOL
     * field population block in {@code PROCESS-ENTER-KEY} (COTRN01C.cbl lines 176-192)
     * where each TRAN-RECORD field is moved to the corresponding BMS screen output field.</p>
     *
     * <h4>Field Mapping (CVTRA05Y.cpy → Transaction entity → TransactionDto)</h4>
     * <ol>
     *   <li>{@code TRAN-ID PIC X(16)} → {@code getTranId()} → {@code setTranId(String)}</li>
     *   <li>{@code TRAN-TYPE-CD PIC X(02)} → {@code getTranTypeCd()} → {@code setTranTypeCd(String)}</li>
     *   <li>{@code TRAN-CAT-CD PIC 9(04)} → {@code getTranCatCd()} → {@code setTranCatCd(String)}
     *       — Short→String conversion with leading zero preservation</li>
     *   <li>{@code TRAN-SOURCE PIC X(10)} → {@code getTranSource()} → {@code setTranSource(String)}</li>
     *   <li>{@code TRAN-DESC PIC X(100)} → {@code getTranDesc()} → {@code setTranDesc(String)}</li>
     *   <li>{@code TRAN-AMT PIC S9(09)V99} → {@code getTranAmt()} → {@code setTranAmt(BigDecimal)}
     *       — CRITICAL: BigDecimal precision preserved, NEVER float/double</li>
     *   <li>{@code TRAN-MERCHANT-ID PIC 9(09)} → {@code getTranMerchantId()} → {@code setTranMerchId(String)}</li>
     *   <li>{@code TRAN-MERCHANT-NAME PIC X(50)} → {@code getTranMerchantName()} → {@code setTranMerchName(String)}</li>
     *   <li>{@code TRAN-MERCHANT-CITY PIC X(50)} → {@code getTranMerchantCity()} → {@code setTranMerchCity(String)}</li>
     *   <li>{@code TRAN-MERCHANT-ZIP PIC X(10)} → {@code getTranMerchantZip()} → {@code setTranMerchZip(String)}</li>
     *   <li>{@code TRAN-CARD-NUM PIC X(16)} → {@code getTranCardNum()} → {@code setTranCardNum(String)}</li>
     *   <li>{@code TRAN-ORIG-TS PIC X(26)} → {@code getTranOrigTs()} → {@code setTranOrigTs(LocalDateTime)}</li>
     *   <li>{@code TRAN-PROC-TS PIC X(26)} → {@code getTranProcTs()} → {@code setTranProcTs(LocalDateTime)}</li>
     * </ol>
     *
     * @param entity the Transaction JPA entity loaded from the database
     * @return a fully populated TransactionDto with all 13 mapped fields
     */
    private TransactionDto toDto(Transaction entity) {
        TransactionDto dto = new TransactionDto();

        // Field 1: TRAN-ID PIC X(16) — Transaction identifier (primary key)
        // COBOL: MOVE TRAN-ID TO TRNIDI OF COTRN1AI (line 178)
        dto.setTranId(entity.getTranId());

        // Field 2: TRAN-TYPE-CD PIC X(02) — Transaction type code
        // COBOL: MOVE TRAN-TYPE-CD TO TTYPCDI OF COTRN1AI (line 180)
        dto.setTranTypeCd(entity.getTranTypeCd());

        // Field 3: TRAN-CAT-CD PIC 9(04) — Transaction category code
        // COBOL: MOVE TRAN-CAT-CD TO TCATCDI OF COTRN1AI (line 181)
        // Entity stores as Short (DDL SMALLINT); DTO uses String (COBOL PIC 9(04)).
        // Conversion preserves leading zeros to match COBOL 4-digit display format.
        dto.setTranCatCd(entity.getTranCatCd() != null
                ? String.format("%04d", entity.getTranCatCd())
                : null);

        // Field 4: TRAN-SOURCE PIC X(10) — Transaction source identifier
        // COBOL: MOVE TRAN-SOURCE TO TRNSRCI OF COTRN1AI (line 182)
        dto.setTranSource(entity.getTranSource());

        // Field 5: TRAN-DESC PIC X(100) — Transaction description
        // COBOL: MOVE TRAN-DESC TO TDESCI OF COTRN1AI (line 184)
        dto.setTranDesc(entity.getTranDesc());

        // Field 6: TRAN-AMT PIC S9(09)V99 — Transaction amount
        // COBOL: MOVE TRAN-AMT TO WS-TRAN-AMT (line 177), then to TRNAMTI (line 183)
        // CRITICAL: BigDecimal preserves exact COMP-3 packed decimal precision.
        // Zero float/double substitution per AAP §0.8.2.
        dto.setTranAmt(entity.getTranAmt());

        // Field 7: TRAN-CARD-NUM PIC X(16) — Card number (VSAM AIX field)
        // COBOL: MOVE TRAN-CARD-NUM TO CARDNUMI OF COTRN1AI (line 179)
        dto.setTranCardNum(entity.getTranCardNum());

        // Field 8: TRAN-MERCHANT-ID PIC 9(09) — Merchant identifier
        // COBOL: MOVE TRAN-MERCHANT-ID TO MIDI OF COTRN1AI (line 187)
        dto.setTranMerchId(entity.getTranMerchantId());

        // Field 9: TRAN-MERCHANT-NAME PIC X(50) — Merchant name
        // COBOL: MOVE TRAN-MERCHANT-NAME TO MNAMEI OF COTRN1AI (line 188)
        dto.setTranMerchName(entity.getTranMerchantName());

        // Field 10: TRAN-MERCHANT-CITY PIC X(50) — Merchant city
        // COBOL: MOVE TRAN-MERCHANT-CITY TO MCITYI OF COTRN1AI (line 189)
        dto.setTranMerchCity(entity.getTranMerchantCity());

        // Field 11: TRAN-MERCHANT-ZIP PIC X(10) — Merchant ZIP code
        // COBOL: MOVE TRAN-MERCHANT-ZIP TO MZIPI OF COTRN1AI (line 190)
        dto.setTranMerchZip(entity.getTranMerchantZip());

        // Field 12: TRAN-ORIG-TS PIC X(26) — Origination timestamp
        // COBOL: MOVE TRAN-ORIG-TS TO TORIGDTI OF COTRN1AI (line 185)
        dto.setTranOrigTs(entity.getTranOrigTs());

        // Field 13: TRAN-PROC-TS PIC X(26) — Processing timestamp
        // COBOL: MOVE TRAN-PROC-TS TO TPROCDTI OF COTRN1AI (line 186)
        dto.setTranProcTs(entity.getTranProcTs());

        // Note: COBOL FILLER PIC X(20) at offset 330 is NOT mapped — padding only.

        return dto;
    }
}
