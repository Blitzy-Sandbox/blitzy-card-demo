/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.controller;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.model.dto.TransactionDto;
import com.cardemo.service.transaction.TransactionAddService;
import com.cardemo.service.transaction.TransactionDetailService;
import com.cardemo.service.transaction.TransactionListService;

/**
 * Spring MVC REST controller providing transaction list, detail, add, and copy
 * operations, replacing CICS BMS screens COTRN00 (list), COTRN01 (detail),
 * and COTRN02 (add) from the CardDemo mainframe application.
 *
 * <p>This controller is a thin delegation layer — all business logic, validation,
 * cross-reference resolution, and transaction management resides in the three
 * service classes. The controller is responsible only for HTTP binding, request
 * logging, and response wrapping.</p>
 *
 * <h3>COBOL Program Coverage</h3>
 * <table>
 *   <caption>BMS Screen to REST endpoint mapping</caption>
 *   <tr><th>BMS Screen</th><th>COBOL Program</th><th>REST Endpoint</th><th>HTTP Method</th></tr>
 *   <tr><td>COTRN00 (Transaction List)</td><td>COTRN00C.cbl (699 lines, CT00)</td>
 *       <td>/api/transactions</td><td>GET</td></tr>
 *   <tr><td>COTRN01 (Transaction Detail)</td><td>COTRN01C.cbl (330 lines, CT01)</td>
 *       <td>/api/transactions/{id}</td><td>GET</td></tr>
 *   <tr><td>COTRN02 (Transaction Add)</td><td>COTRN02C.cbl (783 lines, CT02)</td>
 *       <td>/api/transactions</td><td>POST</td></tr>
 *   <tr><td>COTRN02 (Copy for Add)</td><td>COTRN02C.cbl (783 lines, CT02)</td>
 *       <td>/api/transactions/copy/{sourceId}</td><td>GET</td></tr>
 * </table>
 *
 * <h3>HTTP Status Codes</h3>
 * <ul>
 *   <li><strong>200 OK</strong> — Successful transaction list, detail, or copy</li>
 *   <li><strong>201 Created</strong> — Successful new transaction creation</li>
 *   <li><strong>400 Bad Request</strong> — Validation failures on transaction add
 *       (field validations from 10+ COTRN02C.cbl rules) — handled by ControllerAdvice</li>
 *   <li><strong>404 Not Found</strong> — Transaction, card, or account not found
 *       (maps COBOL FILE STATUS 23 / DFHRESP(NOTFND)) — handled by ControllerAdvice</li>
 *   <li><strong>409 Conflict</strong> — Duplicate transaction ID on add (rare race
 *       condition, maps FILE STATUS 22 / DFHRESP(DUPKEY)) — handled by ControllerAdvice</li>
 * </ul>
 *
 * <h3>Key Design Decisions</h3>
 * <ul>
 *   <li>No exception handling in controller — all exceptions propagate to the global
 *       {@code @ControllerAdvice} handler for consistent error response formatting.</li>
 *   <li>No business logic in controller — paginated browsing (10 rows/page), auto-ID
 *       generation (browse-to-end + increment, 16-char zero-padded), and bidirectional
 *       card/account cross-reference resolution are delegated to services.</li>
 *   <li>All transaction amounts are {@link java.math.BigDecimal} — zero float/double
 *       substitution per AAP decimal precision rules (TRAN-AMT PIC S9(09)V99 COMP-3).</li>
 *   <li>Structured logging with SLF4J supports correlation ID propagation via MDC
 *       per AAP observability requirements.</li>
 *   <li>Page size of 10 for transaction list matches COBOL COTRN00C.cbl page size,
 *       enforced in {@link TransactionListService}.</li>
 * </ul>
 *
 * <p>Source traceability: COTRN00C.cbl + COTRN01C.cbl + COTRN02C.cbl —
 * CardDemo v1.0-15-g27d6c6f-68</p>
 *
 * @see TransactionListService
 * @see TransactionDetailService
 * @see TransactionAddService
 * @see TransactionDto
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    /**
     * SLF4J logger for structured logging with correlation IDs.
     * Logs transaction list, detail, add, and copy operations at INFO level
     * for audit trail and observability, supporting the AAP observability
     * requirement for structured logging with correlation IDs propagated via MDC.
     */
    private static final Logger logger = LoggerFactory.getLogger(TransactionController.class);

    /**
     * Transaction list service implementing paginated browse.
     * Migrated from COBOL program COTRN00C.cbl (699 lines, CICS transaction CT00).
     * Performs: STARTBR/READNEXT/READPREV on TRANSACT VSAM KSDS mapped to
     * Spring Data JPA paginated queries with 10-row page size.
     */
    private final TransactionListService transactionListService;

    /**
     * Transaction detail service implementing single keyed read.
     * Migrated from COBOL program COTRN01C.cbl (330 lines, CICS transaction CT01).
     * Performs: READ TRANSACT by RIDFLD(TRAN-ID) mapped to JPA {@code findById}.
     * Throws {@link com.cardemo.exception.RecordNotFoundException} on FILE STATUS 23.
     */
    private final TransactionDetailService transactionDetailService;

    /**
     * Transaction add service implementing creation with auto-ID generation.
     * Migrated from COBOL program COTRN02C.cbl (783 lines, CICS transaction CT02).
     * Performs: auto-ID generation (browse-to-end + increment, 16-char zero-padded),
     * bidirectional card/account cross-reference resolution, and field validation
     * across 10+ rules before WRITE to TRANSACT VSAM.
     */
    private final TransactionAddService transactionAddService;

    /**
     * Constructs a new TransactionController with the required service dependencies.
     *
     * <p>Uses Spring constructor injection, which is the recommended dependency injection
     * pattern. When a class has a single constructor, Spring automatically uses it for
     * autowiring without requiring the {@code @Autowired} annotation.</p>
     *
     * @param transactionListService   service for paginated transaction listing (COTRN00C.cbl)
     * @param transactionDetailService service for single transaction detail read (COTRN01C.cbl)
     * @param transactionAddService    service for transaction creation and copy (COTRN02C.cbl)
     */
    public TransactionController(TransactionListService transactionListService,
                                 TransactionDetailService transactionDetailService,
                                 TransactionAddService transactionAddService) {
        this.transactionListService = transactionListService;
        this.transactionDetailService = transactionDetailService;
        this.transactionAddService = transactionAddService;
    }

    /**
     * Lists transactions with optional pagination and filtering by start transaction ID.
     *
     * <p>Maps COBOL program COTRN00C.cbl (699 lines, CICS transaction CT00) which
     * performs paginated browse of the TRANSACT VSAM KSDS dataset:</p>
     * <ol>
     *   <li><strong>STARTBR (1000-START-BROWSE):</strong> Positions cursor at the
     *       requested transaction ID (or beginning if none specified)</li>
     *   <li><strong>READNEXT (1100-READ-NEXT):</strong> Reads forward 10 records per
     *       page, matching the COBOL 10-row page size for behavioral parity</li>
     *   <li><strong>READPREV (1200-READ-PREV):</strong> Supports backward navigation
     *       through transaction ID-based filtering</li>
     * </ol>
     *
     * <p>The {@code startTransactionId} parameter maps the TRNIDINI filter field from
     * BMS screen COTRN00 (COTRN00.CPY). When provided, only transactions with IDs
     * greater than or equal to this value are returned.</p>
     *
     * <p>Page size is fixed at 10 rows per page, enforced in
     * {@link TransactionListService} to maintain COBOL behavioral parity.</p>
     *
     * @param page               zero-based page number (default 0, maps COBOL PAGE-NUM)
     * @param startTransactionId optional transaction ID filter (maps BMS TRNIDINI field);
     *                           when null, returns transactions from the beginning
     * @return HTTP 200 with {@link Page} of {@link TransactionDto} containing paginated
     *         results with total elements, total pages, hasNext, and current page metadata
     */
    @GetMapping
    public ResponseEntity<Page<TransactionDto>> listTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String startTransactionId) {
        logger.info("Listing transactions page={} startTransactionId={}", page, startTransactionId);
        Page<TransactionDto> result = transactionListService.listTransactions(startTransactionId, page);
        return ResponseEntity.ok(result);
    }

    /**
     * Retrieves a single transaction by its unique identifier.
     *
     * <p>Maps COBOL program COTRN01C.cbl (330 lines, CICS transaction CT01) which
     * performs a single keyed read:</p>
     * <ol>
     *   <li><strong>READ TRANSACT (2000-READ-TRANSACTION):</strong> Reads the TRANSACT
     *       VSAM KSDS by RIDFLD(TRAN-ID), mapped to JPA {@code findById}</li>
     *   <li><strong>SEND MAP (3000-SEND-MAP):</strong> Displays all transaction fields
     *       on BMS screen COTRN01, mapped to the returned {@link TransactionDto}</li>
     * </ol>
     *
     * <p>This is a read-only operation — no {@code @Transactional} annotation is
     * needed at the controller level because the service uses
     * {@code @Transactional(readOnly = true)} internally.</p>
     *
     * @param id the transaction identifier (16-character string, maps COBOL PIC X(16))
     * @return HTTP 200 with {@link TransactionDto} containing all transaction fields
     * @throws com.cardemo.exception.RecordNotFoundException if the transaction is not
     *         found (mapped to HTTP 404 by ControllerAdvice, maps COBOL FILE STATUS 23)
     */
    @GetMapping("/{id}")
    public ResponseEntity<TransactionDto> getTransaction(@PathVariable String id) {
        logger.info("Getting transaction {}", id);
        TransactionDto result = transactionDetailService.getTransaction(id);
        return ResponseEntity.ok(result);
    }

    /**
     * Creates a new transaction with auto-generated ID and cross-reference resolution.
     *
     * <p>Maps COBOL program COTRN02C.cbl (783 lines, CICS transaction CT02) which
     * performs a multi-step creation flow:</p>
     * <ol>
     *   <li><strong>Auto-ID Generation (4100-GENERATE-TRAN-ID):</strong> Browses to the
     *       end of TRANSACT VSAM, reads the last key, increments by 1, and zero-pads
     *       to 16 characters. Mapped to JPA max-ID query with increment.</li>
     *   <li><strong>Cross-Reference Resolution (4200-RESOLVE-XREF):</strong> Resolves
     *       card number to account ID (or vice versa) via CARDXREF VSAM lookup,
     *       enabling bidirectional card/account association.</li>
     *   <li><strong>Field Validation (VALIDATE-INPUT-DATA-FIELDS, lines 330-498):</strong>
     *       Validates 10+ fields including transaction type code, category code, source,
     *       description, amount range, card number, merchant data, and date fields.</li>
     *   <li><strong>WRITE TRANSACT (4300-WRITE-TRANSACTION):</strong> Writes the validated
     *       record to the TRANSACT VSAM KSDS dataset, mapped to JPA {@code save}.</li>
     * </ol>
     *
     * <p>The {@code @Valid} annotation triggers Jakarta Bean Validation on the
     * {@link TransactionDto} request body, enforcing {@code @Size} constraints on all
     * string fields matching COBOL PIC clause lengths (tranId max=16, tranTypeCd max=2,
     * tranCatCd max=4, tranSource max=10, tranDesc max=100, etc.). This replaces the
     * COBOL CSSETATY.cpy BMS field attribute setting with declarative validation.</p>
     *
     * <p>The transaction ID in the request DTO is ignored — a new ID is auto-generated
     * by the service, matching the COBOL behavior where the user does not specify the
     * transaction ID.</p>
     *
     * @param transactionDto the transaction data to create (validated via {@code @Valid})
     * @return HTTP 201 Created with {@link TransactionDto} containing the persisted
     *         transaction including the auto-generated transaction ID
     * @throws com.cardemo.exception.ValidationException if field validation fails
     *         (mapped to HTTP 400 by ControllerAdvice)
     * @throws com.cardemo.exception.RecordNotFoundException if the card or account
     *         referenced in the transaction is not found during cross-reference resolution
     *         (mapped to HTTP 404 by ControllerAdvice)
     * @throws com.cardemo.exception.DuplicateRecordException if the auto-generated
     *         transaction ID already exists due to a rare race condition
     *         (mapped to HTTP 409 by ControllerAdvice, maps COBOL FILE STATUS 22)
     */
    @PostMapping
    public ResponseEntity<TransactionDto> addTransaction(
            @Valid @RequestBody TransactionDto transactionDto) {
        logger.info("Adding new transaction");
        TransactionDto result = transactionAddService.addTransaction(transactionDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Copies an existing transaction's data for use in creating a new transaction.
     *
     * <p>Maps the COBOL COTRN02C.cbl copy convenience feature where selecting a
     * transaction from the list screen (COTRN00, PF5 key) pre-populates the add
     * screen (COTRN02) with the source transaction's field values.</p>
     *
     * <p>The returned {@link TransactionDto} has all fields populated from the source
     * transaction except the transaction ID, which is set to {@code null}. The ID
     * will be auto-generated when the copied transaction is submitted via
     * {@link #addTransaction(TransactionDto)}.</p>
     *
     * <p>This is a read-only operation — no data is modified. The caller is expected
     * to review and potentially modify the copied data before submitting it as a
     * new transaction via POST.</p>
     *
     * @param sourceId the transaction ID to copy from (16-character string, maps COBOL PIC X(16))
     * @return HTTP 200 with {@link TransactionDto} populated from the source transaction
     *         (with null transaction ID for subsequent auto-generation)
     * @throws com.cardemo.exception.RecordNotFoundException if the source transaction is
     *         not found (mapped to HTTP 404 by ControllerAdvice)
     */
    @GetMapping("/copy/{sourceId}")
    public ResponseEntity<TransactionDto> copyTransaction(@PathVariable String sourceId) {
        logger.info("Copying transaction {} for new add", sourceId);
        TransactionDto result = transactionAddService.copyFromTransaction(sourceId);
        return ResponseEntity.ok(result);
    }
}
