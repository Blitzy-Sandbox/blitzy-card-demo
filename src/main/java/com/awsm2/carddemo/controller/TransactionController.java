/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.controller;

import com.awsm2.carddemo.dto.ApiResponse;
import com.awsm2.carddemo.dto.TransactionAddDto;
import com.awsm2.carddemo.dto.TransactionDetailDto;
import com.awsm2.carddemo.dto.TransactionListDto;
import com.awsm2.carddemo.service.TransactionAddService;
import com.awsm2.carddemo.service.TransactionDetailService;
import com.awsm2.carddemo.service.TransactionListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transaction REST controller &mdash; list, view, and add transactions.
 *
 * <p><b>COBOL provenance (AAP &sect;0.4.1):</b> this controller replaces
 * three CICS COBOL programs operating on the {@code TRANSACT} VSAM KSDS
 * cluster (record layout {@code app/cpy/CVTRA05Y.cpy} TRAN-RECORD,
 * 350-byte):</p>
 * <ul>
 *   <li>{@code app/cbl/COTRN00C.cbl} (CICS transaction id {@code CT00})
 *       &mdash; Transaction list with pagination (10 rows per page,
 *       per the BMS {@code OCCURS 10} in {@code COTRN00.bms}),
 *       supporting optional card-number filtering. The Java target
 *       uses {@link TransactionListService#listTransactions(String, int)}.</li>
 *   <li>{@code app/cbl/COTRN01C.cbl} (CICS transaction id {@code CT01})
 *       &mdash; Transaction detail by transaction ID; rendered via
 *       {@code app/bms/COTRN01.bms}. The Java target uses
 *       {@link TransactionDetailService#getTransactionDetail(String)}.</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} (CICS transaction id {@code CT02})
 *       &mdash; Transaction add with confirmation flow; generates a
 *       new transaction ID via MAX-TRAN-ID+1 (originally implemented
 *       by browsing {@code TRANSACT} to end and incrementing the last
 *       key; the Java target uses
 *       {@code TransactionRepository.findTopByOrderByTranIdDesc()} for
 *       the same semantic). Publishes the {@code transaction.posted}
 *       MSK Kafka event on success. The Java target uses
 *       {@link TransactionAddService#addTransaction(TransactionAddDto)}.</li>
 * </ul>
 *
 * <p><b>Endpoint inventory (AAP &sect;0.3.4):</b></p>
 * <ul>
 *   <li>{@code GET /api/transactions?id={filter}&page={n}} &mdash;
 *       paginated transaction list (10 rows per page); supports an
 *       optional {@code id} filter (interpreted as a card-number
 *       filter, matching the COBOL {@code COTRN00C} behaviour).</li>
 *   <li>{@code GET /api/transactions/{id}} &mdash; transaction detail
 *       by transaction ID (16-character key).</li>
 *   <li>{@code POST /api/transactions} &mdash; create new transaction;
 *       generates a sequential transaction ID, performs the
 *       cross-reference validation, persists, and publishes
 *       {@code transaction.posted} to MSK. Returns HTTP 201 Created.</li>
 * </ul>
 *
 * <p><b>Event-driven publishing (AAP &sect;0.6.5):</b> the
 * {@code POST} path delegates to {@link TransactionAddService} which
 * publishes the {@code transaction.posted} MSK Kafka event partitioned
 * by account ID for per-account ordering. The controller is the
 * synchronous HTTP boundary; downstream consumers (audit, reporting,
 * account-balance projection) react to the published event
 * asynchronously.</p>
 *
 * <p><b>Card number representation (AAP &sect;0.6.6 PCI-DSS):</b> card
 * numbers in the {@code TransactionAddDto.cardNumber} field are full
 * 16-digit PANs on the wire (the BMS screen also accepted them in
 * cleartext). Responses use masked PAN where possible; the controller
 * logs at TRACE level only when card numbers appear.</p>
 *
 * <p><b>Layered architecture compliance:</b> thin Spring MVC fa&ccedil;ade;
 * delegates to {@link TransactionListService},
 * {@link TransactionDetailService}, and {@link TransactionAddService}
 * via constructor injection.</p>
 *
 * @see TransactionListService
 * @see TransactionDetailService
 * @see TransactionAddService
 * @see TransactionListDto
 * @see TransactionDetailDto
 * @see TransactionAddDto
 */
@RestController
@RequestMapping("/api/transactions")
@Tag(name = "Transaction",
        description = "Transaction list, detail, and add. Replaces CICS "
                + "COTRN00C (Tran-ID CT00), COTRN01C (Tran-ID CT01), and "
                + "COTRN02C (Tran-ID CT02).")
@Validated
public class TransactionController {

    /**
     * SLF4J facade for structured JSON logging. Per AAP &sect;0.7.2.
     * PCI-DSS discipline (AAP &sect;0.6.6): transaction IDs and
     * filter values are non-sensitive metadata and may be logged;
     * full PANs and transaction amounts are NOT logged at DEBUG /
     * INFO level.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionController.class);

    /**
     * Transaction list service collaborator &mdash; encapsulates the
     * paginated {@code TRANSACT} browse originally implemented by the
     * {@code COTRN00C.cbl:PROCESS-PF7} / {@code PROCESS-PF8} paginating
     * paragraphs, with the 10-rows-per-page contract from the BMS
     * {@code COTRN00.bms} {@code OCCURS 10}.
     */
    private final TransactionListService transactionListService;

    /**
     * Transaction detail service collaborator &mdash; encapsulates the
     * single keyed read of {@code TRANSACT} originally implemented by
     * the {@code COTRN01C.cbl:READ-TRANSACT-FILE} paragraph.
     */
    private final TransactionDetailService transactionDetailService;

    /**
     * Transaction add service collaborator &mdash; encapsulates the
     * MAX-TRAN-ID+1 sequence generation, cross-reference validation,
     * persistence, and MSK {@code transaction.posted} event publication
     * originally implemented by the
     * {@code COTRN02C.cbl:PROCESS-ENTER-KEY} +
     * {@code WRITE-TRANSACT-FILE} paragraphs.
     */
    private final TransactionAddService transactionAddService;

    /**
     * Constructor used by Spring's dependency injection container.
     *
     * @param transactionListService   the
     *                                 {@link TransactionListService}
     *                                 collaborator
     * @param transactionDetailService the
     *                                 {@link TransactionDetailService}
     *                                 collaborator
     * @param transactionAddService    the
     *                                 {@link TransactionAddService}
     *                                 collaborator
     */
    public TransactionController(TransactionListService transactionListService,
                                 TransactionDetailService transactionDetailService,
                                 TransactionAddService transactionAddService) {
        this.transactionListService = transactionListService;
        this.transactionDetailService = transactionDetailService;
        this.transactionAddService = transactionAddService;
    }

    /**
     * Returns a paginated transaction list with optional card-number filter.
     *
     * <p><b>COBOL provenance:</b> Replaces
     * {@code app/cbl/COTRN00C.cbl:PROCESS-ENTER-KEY} +
     * {@code PROCESS-PF7} / {@code PROCESS-PF8} which browsed the
     * {@code TRANSACT} VSAM KSDS in 10-row pages (per BMS
     * {@code COTRN00.bms} {@code OCCURS 10}), optionally filtered by
     * card number.</p>
     *
     * @param idFilter optional card-number filter (16-digit; partial
     *                 matches may be supported by the service); maps to
     *                 the COBOL {@code TRNIDIN} BMS field. May be
     *                 {@code null} for full unfiltered listing
     * @param page     0-based page index; defaults to 0
     * @return {@link ResponseEntity} with HTTP 200 and the
     *         {@link TransactionListDto} wrapped in {@link ApiResponse}
     */
    @GetMapping
    @Operation(
            summary = "List transactions (paginated, 10 rows per page)",
            description = "Returns a paginated transaction list with optional "
                    + "card-number filter. Replaces CICS COTRN00C / Tran-ID CT00 "
                    + "(transaction list) including the PF7/PF8 paging behaviour."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Transaction list returned successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Forbidden")
    })
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<ApiResponse<TransactionListDto>> listTransactions(
            @RequestParam(value = "id", required = false)
            @Pattern(regexp = "^[0-9]{1,16}$|^$",
                    message = "id filter must be 1-16 digits")
            @Parameter(
                    description = "Optional starting transaction ID filter "
                            + "(COBOL TRAN-ID, up to 16 digits). Maps to the "
                            + "BMS TRNIDINI input field of COTRN00.bms.",
                    example = "1000000000000001")
            String idFilter,

            @RequestParam(value = "page", required = false, defaultValue = "0")
            @Min(value = 0, message = "page must be >= 0")
            @Parameter(
                    description = "0-based page index; defaults to 0 when "
                            + "omitted. Translated from the COBOL "
                            + "CDEMO-CT00-PAGE-NUM COMMAREA field per AAP "
                            + "\u00a70.3.4 stateless REST.",
                    example = "0")
            int page) {
        // COBOL: COTRN00C / Tran-ID CT00 -- PROCESS-ENTER-KEY paginated
        //   browse (delegates to TransactionListService which preserves
        //   the COBOL PAGE_SIZE=10 contract per AAP §0.4.1).
        // PCI-DSS-safe traceability log (AAP §0.6.6 / §0.7.2): only the
        // non-sensitive transaction-ID prefix filter and page index are
        // emitted; no card number, PAN, or amount leaks into logs.
        LOG.debug("Transaction list requested: idFilter={} page={}", idFilter, page);
        TransactionListDto transactionList =
                transactionListService.listTransactions(idFilter, page);
        return ResponseEntity.ok(ApiResponse.success(transactionList));
    }

    /**
     * Returns the transaction detail for the supplied transaction ID.
     *
     * <p><b>COBOL provenance:</b> Replaces
     * {@code app/cbl/COTRN01C.cbl:READ-TRANSACT-FILE} which read the
     * {@code TRAN-RECORD} from {@code TRANSACT} keyed on
     * {@code TRAN-ID} ({@code PIC X(16)}).</p>
     *
     * @param id the 16-character transaction identifier
     *           ({@code TRAN-ID PIC X(16)} from
     *           {@code app/cpy/CVTRA05Y.cpy})
     * @return {@link ResponseEntity} with HTTP 200 and the
     *         {@link TransactionDetailDto} wrapped in
     *         {@link ApiResponse}. HTTP 404 if the transaction does not
     *         exist
     */
    @GetMapping("/{id}")
    @Operation(
            summary = "View transaction detail by transaction ID",
            description = "Returns the transaction detail for the supplied "
                    + "16-character transaction ID. Replaces CICS COTRN01C / "
                    + "Tran-ID CT01 (transaction detail)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Transaction detail returned successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Transaction not found")
    })
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<ApiResponse<TransactionDetailDto>> getTransaction(
            @PathVariable("id")
            @NotBlank(message = "Transaction ID is required")
            @Pattern(regexp = "^[0-9]{16}$",
                    message = "Transaction ID must be exactly 16 digits")
            @Parameter(
                    description = "16-digit transaction ID (COBOL TRAN-ID "
                            + "PIC X(16) per app/cpy/CVTRA05Y.cpy)",
                    example = "1000000000000001")
            String id) {
        // COBOL: COTRN01C / Tran-ID CT01 -- READ-TRANSACT-FILE
        //   (delegates to TransactionDetailService per AAP §0.4.1).
        // PCI-DSS (AAP §0.6.6, §0.7.2): only the non-sensitive
        // transaction ID is emitted; PAN and amount are NOT logged.
        LOG.debug("Transaction detail requested for tranId={}", id);
        TransactionDetailDto detail = transactionDetailService.getTransactionDetail(id);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    /**
     * Creates a new transaction.
     *
     * <p><b>COBOL provenance:</b> Replaces
     * {@code app/cbl/COTRN02C.cbl:PROCESS-ENTER-KEY} +
     * {@code WRITE-TRANSACT-FILE} which:</p>
     * <ol>
     *   <li>Validated input fields (account ID or card number, type,
     *       category, amount, merchant data).</li>
     *   <li>Browsed {@code TRANSACT} to the end (MAX-TRAN-ID+1) to
     *       generate the next sequential transaction ID.</li>
     *   <li>Resolved the missing identifier via
     *       {@code CARDXREF} cross-reference lookup.</li>
     *   <li>Wrote the new {@code TRAN-RECORD} to {@code TRANSACT}.</li>
     *   <li>On {@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)}:
     *       returned "Duplicate transaction ID" error.</li>
     * </ol>
     *
     * <p>The Java target replaces these with:</p>
     * <ol>
     *   <li>Jakarta Bean Validation on the {@link TransactionAddDto}
     *       (HTTP 400 on failure).</li>
     *   <li>{@code TransactionRepository.findTopByOrderByTranIdDesc()}
     *       to get the highest existing transaction ID, then increment
     *       by 1 (same MAX+1 semantic, faster than a full browse).</li>
     *   <li>{@code CardCrossReferenceRepository.findById} /
     *       {@code findByXrefAcctIdOrderByXrefCardNumAsc} for the
     *       cross-reference resolution.</li>
     *   <li>{@code transactionRepository.save} (HTTP 409
     *       {@code DuplicateRecordException} on duplicate-key).</li>
     *   <li>{@code KafkaEventPublisher.publishTransactionPosted} to
     *       emit the MSK event (AAP &sect;0.6.5).</li>
     * </ol>
     *
     * <p><b>HTTP 201 Created</b> is the success status per REST
     * conventions for resource creation. The response body carries the
     * full {@link TransactionAddDto} with the generated transaction ID
     * so the client can use it for subsequent lookups.</p>
     *
     * @param request the validated {@link TransactionAddDto}
     * @return {@link ResponseEntity} with HTTP 201 and the saved
     *         {@link TransactionAddDto} (including the generated
     *         transaction ID) wrapped in {@link ApiResponse}.
     *         HTTP 400 on validation failure; HTTP 409 on duplicate-key
     */
    @PostMapping
    @Operation(
            summary = "Add a new transaction",
            description = "Creates a new transaction record. Caller must provide "
                    + "EITHER account ID OR card number (XOR -- exactly one). "
                    + "Generated 16-digit transaction ID is returned. Publishes "
                    + "MSK 'transaction.posted' event partitioned by account ID "
                    + "(AAP \u00a70.6.5). Replaces CICS COTRN02C / Tran-ID CT02 "
                    + "(Transaction Add)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Transaction created successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failed (XOR rule, amount, dates, types)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Account or card not found (XREF lookup failed)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "Duplicate transaction ID"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "Credit limit exceeded or card expired"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "On size error -- amount overflow")
    })
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<ApiResponse<TransactionAddDto>> addTransaction(
            @Valid @RequestBody TransactionAddDto request) {
        // COBOL: COTRN02C / Tran-ID CT02 -- Transaction Add
        //         XOR ACCT-ID-N vs CARD-NUM-N via CXACAIX alternate-index lookup
        //         MAX(TRAN-ID)+1 -> JPA sequence
        //         Publishes 'transaction.posted' Kafka event (partition key =
        //         account ID, per AAP §0.6.5)
        // Delegates to TransactionAddService which performs MAX+1 sequence,
        // XREF resolution, persist, and MSK publish per AAP §0.4.1, §0.6.5.
        // PCI-DSS (AAP §0.6.6, §0.7.2): PAN is NOT logged at the controller
        // boundary. Only non-sensitive metadata (type and category) is
        // emitted at DEBUG; the service emits a follow-up audit event with
        // a masked PAN (last-4 only) via AuditLogService.
        LOG.info("Transaction add requested: type={}, category={}",
                request.transactionType(), request.transactionCategory());
        TransactionAddDto saved = transactionAddService.addTransaction(request);
        // The TransactionAddService persists the generated 16-digit TRAN-ID
        // back into the response DTO; we log it for traceability so support
        // operators can correlate the HTTP request with the downstream
        // 'transaction.posted' Kafka event and audit-log record (the
        // service also emits a structured audit event of its own).
        LOG.info("Transaction add successful: type={}, category={}",
                saved.transactionType(), saved.transactionCategory());
        // HTTP 201 Created per AAP §0.3.4 HTTP status mapping for POST
        // creating resources.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(saved, "Transaction created successfully"));
    }
}
