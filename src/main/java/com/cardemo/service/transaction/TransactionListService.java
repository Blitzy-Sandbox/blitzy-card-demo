/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 *
 * CardDemo Application — Transaction List Service
 * Migrated from COBOL program COTRN00C.cbl (699 lines).
 * COBOL source reference: app/cbl/COTRN00C.cbl (commit 27d6c6f)
 *
 * COBOL Transaction ID: CT00
 * Function: List Transactions from TRANSACT VSAM KSDS file with paginated browse.
 */
package com.cardemo.service.transaction;

import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service class for paginated transaction browsing, migrated from COBOL program
 * {@code COTRN00C.cbl} — the "List Transactions from TRANSACT file" CICS online
 * program (transaction ID CT00).
 *
 * <h2>COBOL-to-Java Paragraph Mapping</h2>
 * <table>
 *   <tr><th>COBOL Paragraph</th><th>Java Method</th><th>Notes</th></tr>
 *   <tr><td>MAIN-PARA</td><td>{@link #listTransactions(String, int)}</td>
 *       <td>Stateless REST entry point</td></tr>
 *   <tr><td>PROCESS-ENTER-KEY</td><td>{@link #listTransactions(String, int)} with filter</td>
 *       <td>Transaction ID validation + page forward</td></tr>
 *   <tr><td>PROCESS-PF7-KEY</td><td>Controller uses {@code page - 1}</td>
 *       <td>Backward navigation via page number arithmetic</td></tr>
 *   <tr><td>PROCESS-PF8-KEY</td><td>Controller uses {@code page + 1}</td>
 *       <td>Forward navigation via page number arithmetic</td></tr>
 *   <tr><td>PROCESS-PAGE-FORWARD</td><td>{@link #listTransactions(String, int)} internal</td>
 *       <td>PageRequest with Sort.ASC on tranId</td></tr>
 *   <tr><td>PROCESS-PAGE-BACKWARD</td><td>{@link #listTransactions(String, int)} with prior page</td>
 *       <td>Handled by page number arithmetic</td></tr>
 *   <tr><td>POPULATE-TRAN-DATA</td><td>{@link #toDto(Transaction)}</td>
 *       <td>Entity to DTO mapping with type conversions</td></tr>
 *   <tr><td>STARTBR-TRANSACT-FILE</td><td>Repository query execution</td>
 *       <td>JPA query start</td></tr>
 *   <tr><td>READNEXT-TRANSACT-FILE</td><td>Spring Data pagination</td>
 *       <td>JPA pagination results</td></tr>
 *   <tr><td>READPREV-TRANSACT-FILE</td><td>Reverse page navigation</td>
 *       <td>Page number decrement</td></tr>
 *   <tr><td>ENDBR-TRANSACT-FILE</td><td>N/A</td>
 *       <td>JPA manages connection lifecycle</td></tr>
 * </table>
 *
 * <h2>Key Design Decisions</h2>
 * <ul>
 *   <li>Page size fixed at 10 rows per page, preserving COBOL's
 *       {@code PERFORM UNTIL WS-IDX >= 11} (line 297)</li>
 *   <li>VSAM STARTBR/READNEXT pagination mapped to Spring Data {@link Page}
 *       with {@link PageRequest} and ascending sort on transaction ID</li>
 *   <li>COBOL CDEMO-CT00-NEXT-PAGE-FLG mapped to {@link Page#hasNext()}</li>
 *   <li>CDEMO-CT00-TRNID-FIRST / CDEMO-CT00-TRNID-LAST mapped to
 *       {@link PageNavigation} record</li>
 *   <li>Stateless service — no mutable instance state; all context via parameters</li>
 *   <li>{@code @Transactional(readOnly = true)} for read-only query optimization</li>
 * </ul>
 *
 * <p><strong>Precision Rule:</strong> All monetary amounts ({@code TRAN-AMT})
 * use {@link java.math.BigDecimal} — zero {@code float}/{@code double}
 * substitution per AAP §0.8.2.</p>
 *
 * @see Transaction
 * @see TransactionDto
 * @see TransactionRepository
 */
@Service
public class TransactionListService {

    private static final Logger log = LoggerFactory.getLogger(TransactionListService.class);

    /**
     * Page size for transaction list browsing — exactly 10 rows per page.
     *
     * <p>Preserves COBOL behavioral parity with {@code COTRN00C.cbl} line 290/297:
     * <pre>
     * PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX &gt; 10
     * ...
     * PERFORM UNTIL WS-IDX &gt;= 11 OR TRANSACT-EOF OR ERR-FLG-ON
     * </pre>
     *
     * <p>This constant MUST remain 10 to maintain 100% behavioral parity
     * with the original COBOL program. Changing this value would break
     * the COMMAREA page navigation contract.
     */
    private static final int PAGE_SIZE = 10;

    private final TransactionRepository transactionRepository;

    /**
     * Constructs the TransactionListService with its required repository dependency.
     *
     * <p>Uses constructor injection (Spring Boot best practice) — not field injection.
     * The {@link TransactionRepository} provides paginated access to the TRANSACT
     * VSAM dataset, replacing COBOL CICS STARTBR/READNEXT/READPREV operations.
     *
     * @param transactionRepository Spring Data JPA repository for Transaction entities
     */
    public TransactionListService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    // -----------------------------------------------------------------------
    // Nested Record — Page Navigation Context
    // -----------------------------------------------------------------------

    /**
     * Immutable record encapsulating page navigation context for the transaction list.
     *
     * <p>Maps the following COBOL COMMAREA fields from {@code COTRN00C.cbl}
     * (lines 62-68):
     * <ul>
     *   <li>{@code CDEMO-CT00-TRNID-FIRST PIC X(16)} → {@link #firstTransactionId()}</li>
     *   <li>{@code CDEMO-CT00-TRNID-LAST PIC X(16)} → {@link #lastTransactionId()}</li>
     *   <li>{@code CDEMO-CT00-PAGE-NUM PIC 9(08)} → {@link #pageNumber()}</li>
     *   <li>{@code CDEMO-CT00-NEXT-PAGE-FLG PIC X(01)} ('Y'/'N') → {@link #hasNextPage()}</li>
     * </ul>
     *
     * <p>In the COBOL program, these fields are populated in the
     * {@code POPULATE-TRAN-DATA} paragraph (lines 381-445):
     * <ul>
     *   <li>First record on page (WS-IDX = 1): TRAN-ID → CDEMO-CT00-TRNID-FIRST (line 393)</li>
     *   <li>Last record on page (WS-IDX = 10): TRAN-ID → CDEMO-CT00-TRNID-LAST (line 439)</li>
     * </ul>
     *
     * @param firstTransactionId the transaction ID of the first record on the current page;
     *                           {@code null} if the page is empty
     * @param lastTransactionId  the transaction ID of the last record on the current page;
     *                           {@code null} if the page is empty
     * @param pageNumber         zero-based page number (maps CDEMO-CT00-PAGE-NUM)
     * @param hasNextPage        {@code true} if additional pages exist beyond the current page
     *                           (maps CDEMO-CT00-NEXT-PAGE-FLG = 'Y')
     */
    public record PageNavigation(
            String firstTransactionId,
            String lastTransactionId,
            int pageNumber,
            boolean hasNextPage
    ) {
    }

    // -----------------------------------------------------------------------
    // Primary Method — Paginated Transaction Listing
    // -----------------------------------------------------------------------

    /**
     * Lists transactions with pagination, optionally filtered by a starting transaction ID.
     *
     * <p>Equivalent to COBOL {@code PROCESS-PAGE-FORWARD} paragraph
     * ({@code COTRN00C.cbl} lines 279-328). The method performs:
     * <ol>
     *   <li>Validates the page number (defaults to 0 if negative)</li>
     *   <li>Creates a {@link PageRequest} with page size 10 and ascending sort on
     *       {@code tranId}, preserving VSAM KSDS ascending key sequence for
     *       READNEXT browse</li>
     *   <li>Executes the query:
     *       <ul>
     *         <li>If {@code startTransactionId} is provided and non-blank: uses
     *             {@link TransactionRepository#findByTranIdGreaterThanEqual(String,
     *             org.springframework.data.domain.Pageable)} — maps COBOL
     *             {@code STARTBR TRANSACT RIDFLD(TRAN-ID)} with specific starting
     *             key (line 210)</li>
     *         <li>If no filter: uses {@link TransactionRepository#findAll(
     *             org.springframework.data.domain.Pageable)} — maps COBOL
     *             {@code STARTBR} with LOW-VALUES (line 207)</li>
     *       </ul>
     *   </li>
     *   <li>Maps each {@link Transaction} entity to a {@link TransactionDto}
     *       via {@link #toDto(Transaction)}</li>
     * </ol>
     *
     * <p>The returned {@link Page} includes:
     * <ul>
     *   <li>{@link Page#getContent()} — up to 10 transaction DTOs</li>
     *   <li>{@link Page#hasNext()} — equivalent to COBOL
     *       {@code CDEMO-CT00-NEXT-PAGE-FLG} = 'Y' (lines 305-320)</li>
     *   <li>{@link Page#getNumber()} — current page number</li>
     *   <li>{@link Page#getNumberOfElements()} — actual record count on this page</li>
     * </ul>
     *
     * @param startTransactionId optional starting transaction ID for range filtering;
     *                           maps COBOL TRNIDINI input field. Pass {@code null} or
     *                           blank to list from the beginning.
     * @param page               zero-based page number; maps CDEMO-CT00-PAGE-NUM.
     *                           Negative values are corrected to 0.
     * @return a {@link Page} of {@link TransactionDto} with pagination metadata,
     *         never {@code null}
     */
    @Transactional(readOnly = true)
    public Page<TransactionDto> listTransactions(String startTransactionId, int page) {
        // Validate page number — default to 0 if negative
        int safePage = Math.max(page, 0);

        // Create pageable request with ascending sort on transaction ID.
        // Sort.by(Sort.Direction.ASC, "tranId") preserves COBOL VSAM KSDS
        // ascending key sequence for READNEXT browse (COTRN00C.cbl lines 279-328).
        PageRequest pageable = PageRequest.of(safePage, PAGE_SIZE,
                Sort.by(Sort.Direction.ASC, "tranId"));

        // Execute the appropriate query based on whether a starting transaction
        // ID filter is provided.
        Page<Transaction> transactionPage;

        if (startTransactionId != null && !startTransactionId.isBlank()) {
            // Maps COBOL STARTBR TRANSACT RIDFLD(TRAN-ID) with a specific
            // starting key (COTRN00C.cbl line 210: MOVE TRNIDINI TO TRAN-ID)
            log.info("Listing transactions starting from ID '{}', page {}",
                    startTransactionId, safePage);
            transactionPage = transactionRepository.findByTranIdGreaterThanEqual(
                    startTransactionId, pageable);
        } else {
            // Maps COBOL STARTBR with LOW-VALUES — browse from beginning
            // (COTRN00C.cbl line 207: MOVE LOW-VALUES TO TRAN-ID)
            log.info("Listing all transactions, page {}", safePage);
            transactionPage = transactionRepository.findAll(pageable);
        }

        // Map Page<Transaction> to Page<TransactionDto> using the toDto converter.
        // Page.map() preserves all pagination metadata (total elements, hasNext,
        // page number) while transforming the content elements.
        Page<TransactionDto> result = transactionPage.map(this::toDto);

        log.info("Listed transactions page {} with {} results",
                result.getNumber(), result.getNumberOfElements());

        return result;
    }

    // -----------------------------------------------------------------------
    // Navigation Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Determines if a next page exists beyond the current page.
     *
     * <p>Maps COBOL {@code CDEMO-CT00-NEXT-PAGE-FLG} logic
     * ({@code COTRN00C.cbl} lines 305-320). In the COBOL program, after reading
     * 10 records, an additional {@code READNEXT} is performed to determine if
     * more records exist. Spring Data {@link Page#hasNext()} provides identical
     * semantics by comparing the total element count against the current page
     * position and page size.
     *
     * @param currentPage the current page of transaction DTOs
     * @return {@code true} if additional pages exist beyond the current page;
     *         {@code false} if this is the last page
     */
    public boolean hasNextPage(Page<TransactionDto> currentPage) {
        return currentPage.hasNext();
    }

    /**
     * Extracts page navigation context from a result page.
     *
     * <p>Creates a {@link PageNavigation} record containing the first and last
     * transaction IDs on the current page, the page number, and the next-page
     * indicator. This maps the COBOL COMMAREA fields:
     * <ul>
     *   <li>{@code CDEMO-CT00-TRNID-FIRST} (line 63) — set in POPULATE-TRAN-DATA
     *       when WS-IDX = 1 (line 393)</li>
     *   <li>{@code CDEMO-CT00-TRNID-LAST} (line 64) — set in POPULATE-TRAN-DATA
     *       when WS-IDX = 10 (line 439)</li>
     *   <li>{@code CDEMO-CT00-PAGE-NUM} (line 65) — current page number</li>
     *   <li>{@code CDEMO-CT00-NEXT-PAGE-FLG} (line 66) — 'Y' if more pages exist</li>
     * </ul>
     *
     * <p>For empty pages, both first and last transaction IDs are {@code null}.
     *
     * @param page the current page of transaction DTOs
     * @return a {@link PageNavigation} record with navigation context
     */
    public PageNavigation getPageNavigation(Page<TransactionDto> page) {
        List<TransactionDto> content = page.getContent();

        if (content.isEmpty()) {
            return new PageNavigation(null, null, page.getNumber(), page.hasNext());
        }

        // Extract first and last transaction IDs from the page content.
        // Uses Java 21+ SequencedCollection.getFirst()/getLast() methods.
        // Maps COBOL lines 393 (first record) and 439 (last record).
        String firstId = content.getFirst().getTranId();
        String lastId = content.getLast().getTranId();

        return new PageNavigation(firstId, lastId, page.getNumber(), page.hasNext());
    }

    // -----------------------------------------------------------------------
    // Entity-to-DTO Conversion
    // -----------------------------------------------------------------------

    /**
     * Converts a {@link Transaction} JPA entity to a {@link TransactionDto}.
     *
     * <p>Maps the COBOL {@code POPULATE-TRAN-DATA} paragraph
     * ({@code COTRN00C.cbl} lines 381-445) where TRAN-RECORD fields are
     * moved to BMS screen output fields. All 13 data fields from the entity
     * are mapped to the DTO:
     *
     * <table>
     *   <tr><th>Entity Getter</th><th>DTO Setter</th><th>COBOL Field</th></tr>
     *   <tr><td>getTranId()</td><td>setTranId()</td><td>TRAN-ID PIC X(16)</td></tr>
     *   <tr><td>getTranTypeCd()</td><td>setTranTypeCd()</td><td>TRAN-TYPE-CD PIC X(02)</td></tr>
     *   <tr><td>getTranCatCd()</td><td>setTranCatCd()</td><td>TRAN-CAT-CD PIC 9(04)</td></tr>
     *   <tr><td>getTranSource()</td><td>setTranSource()</td><td>TRAN-SOURCE PIC X(10)</td></tr>
     *   <tr><td>getTranDesc()</td><td>setTranDesc()</td><td>TRAN-DESC PIC X(100)</td></tr>
     *   <tr><td>getTranAmt()</td><td>setTranAmt()</td><td>TRAN-AMT PIC S9(09)V99</td></tr>
     *   <tr><td>getTranCardNum()</td><td>setTranCardNum()</td><td>TRAN-CARD-NUM PIC X(16)</td></tr>
     *   <tr><td>getTranMerchantId()</td><td>setTranMerchId()</td><td>TRAN-MERCHANT-ID PIC 9(09)</td></tr>
     *   <tr><td>getTranMerchantName()</td><td>setTranMerchName()</td><td>TRAN-MERCHANT-NAME PIC X(50)</td></tr>
     *   <tr><td>getTranMerchantCity()</td><td>setTranMerchCity()</td><td>TRAN-MERCHANT-CITY PIC X(50)</td></tr>
     *   <tr><td>getTranMerchantZip()</td><td>setTranMerchZip()</td><td>TRAN-MERCHANT-ZIP PIC X(10)</td></tr>
     *   <tr><td>getTranOrigTs()</td><td>setTranOrigTs()</td><td>TRAN-ORIG-TS PIC X(26)</td></tr>
     *   <tr><td>getTranProcTs()</td><td>setTranProcTs()</td><td>TRAN-PROC-TS PIC X(26)</td></tr>
     * </table>
     *
     * <p><strong>Type Conversions:</strong>
     * <ul>
     *   <li>{@code getTranCatCd()} returns {@link Short}; converted to 4-digit
     *       zero-padded {@link String} to preserve COBOL {@code PIC 9(04)} format</li>
     *   <li>{@code getTranAmt()} returns {@link java.math.BigDecimal} — preserved
     *       as-is for COMP-3 decimal precision (AAP §0.8.2)</li>
     *   <li>{@code getTranOrigTs()} / {@code getTranProcTs()} return
     *       {@link java.time.LocalDateTime} — preserved as-is</li>
     * </ul>
     *
     * @param entity the Transaction JPA entity to convert
     * @return a fully populated TransactionDto
     */
    private TransactionDto toDto(Transaction entity) {
        TransactionDto dto = new TransactionDto();

        // Primary key
        dto.setTranId(entity.getTranId());

        // Type and category codes
        dto.setTranTypeCd(entity.getTranTypeCd());

        // TRAN-CAT-CD is PIC 9(04) in COBOL → Short in entity → String in DTO.
        // Format with leading zeros to preserve the 4-digit COBOL representation.
        dto.setTranCatCd(entity.getTranCatCd() != null
                ? String.format("%04d", entity.getTranCatCd())
                : null);

        // Source and description
        dto.setTranSource(entity.getTranSource());
        dto.setTranDesc(entity.getTranDesc());

        // CRITICAL: TRAN-AMT is PIC S9(09)V99 (COMP-3 packed decimal).
        // Mapped as BigDecimal — NEVER float/double (AAP §0.8.2).
        dto.setTranAmt(entity.getTranAmt());

        // Card number — preserved as String for leading zeros
        dto.setTranCardNum(entity.getTranCardNum());

        // Merchant fields — note the abbreviated DTO setter names
        // (entity: getTranMerchantId → DTO: setTranMerchId)
        dto.setTranMerchId(entity.getTranMerchantId());
        dto.setTranMerchName(entity.getTranMerchantName());
        dto.setTranMerchCity(entity.getTranMerchantCity());
        dto.setTranMerchZip(entity.getTranMerchantZip());

        // Timestamps — LocalDateTime preserving COBOL PIC X(26) timestamp data
        dto.setTranOrigTs(entity.getTranOrigTs());
        dto.setTranProcTs(entity.getTranProcTs());

        return dto;
    }
}
