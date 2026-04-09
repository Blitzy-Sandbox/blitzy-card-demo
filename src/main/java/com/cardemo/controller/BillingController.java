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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cardemo.model.dto.BillPaymentRequest;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.service.billing.BillPaymentService;

/**
 * Spring MVC REST controller providing bill payment operations, replacing CICS
 * BMS screen COBIL00 and COBOL program COBIL00C.cbl (572 lines, CICS
 * transaction CB00).
 *
 * <p>This controller handles a single POST endpoint for processing bill payments.
 * It delegates the entire atomic payment cycle to {@link BillPaymentService},
 * which performs account lookup, balance validation, cross-reference resolution,
 * auto-ID generation (browse-to-end + increment), transaction creation with
 * hardcoded COBOL values, and account balance update — all within a single
 * {@code @Transactional} operation.</p>
 *
 * <h3>COBOL Program Coverage</h3>
 * <table>
 *   <caption>COBIL00C.cbl paragraph to REST endpoint mapping</caption>
 *   <tr><th>COBOL Paragraph</th><th>Lines</th><th>Java Mapping</th></tr>
 *   <tr><td>MAIN-PARA</td><td>101-152</td>
 *       <td>Request routing — pseudo-conversational model to stateless REST</td></tr>
 *   <tr><td>PROCESS-ENTER-KEY</td><td>154-244</td>
 *       <td>{@link #payBill(BillPaymentRequest)} entry point + confirmation check</td></tr>
 *   <tr><td>READ-ACCTDAT-FILE</td><td>343-372</td>
 *       <td>{@link BillPaymentService#processPayment(String)} — account lookup</td></tr>
 *   <tr><td>READ-CXACAIX-FILE</td><td>408-436</td>
 *       <td>{@link BillPaymentService#processPayment(String)} — cross-reference resolution</td></tr>
 *   <tr><td>STARTBR-TRANSACT-FILE</td><td>441-467</td>
 *       <td>{@link BillPaymentService#processPayment(String)} — auto-ID generation (start browse)</td></tr>
 *   <tr><td>READPREV-TRANSACT-FILE</td><td>472-496</td>
 *       <td>{@link BillPaymentService#processPayment(String)} — auto-ID generation (read previous)</td></tr>
 *   <tr><td>ENDBR-TRANSACT-FILE</td><td>501-505</td>
 *       <td>{@link BillPaymentService#processPayment(String)} — end browse</td></tr>
 *   <tr><td>WRITE-TRANSACT-FILE</td><td>510-547</td>
 *       <td>{@link BillPaymentService#processPayment(String)} — transaction write</td></tr>
 *   <tr><td>UPDATE-ACCTDAT-FILE</td><td>377-403</td>
 *       <td>{@link BillPaymentService#processPayment(String)} — account balance update</td></tr>
 *   <tr><td>RETURN-TO-PREV-SCREEN</td><td>273-284</td>
 *       <td>Cancellation response (HTTP 200 with message)</td></tr>
 *   <tr><td>SEND-BILLPAY-SCREEN</td><td>289-301</td>
 *       <td>JSON response body via ResponseEntity</td></tr>
 *   <tr><td>RECEIVE-BILLPAY-SCREEN</td><td>306-314</td>
 *       <td>{@code @RequestBody} JSON deserialization</td></tr>
 *   <tr><td>POPULATE-HEADER-INFO</td><td>319-338</td>
 *       <td>N/A — screen header metadata not applicable to REST</td></tr>
 *   <tr><td>CLEAR-CURRENT-SCREEN</td><td>552-555</td>
 *       <td>Cancellation response — maps to HTTP 200 cancel</td></tr>
 *   <tr><td>INITIALIZE-ALL-FIELDS</td><td>560-566</td>
 *       <td>N/A — DTO statelessness eliminates field reset</td></tr>
 * </table>
 *
 * <h3>HTTP Status Codes</h3>
 * <ul>
 *   <li><strong>200 OK</strong> — Bill payment cancelled (confirmIndicator = "N" or blank)</li>
 *   <li><strong>201 Created</strong> — Payment successful (new Transaction entity created)</li>
 *   <li><strong>400 Bad Request</strong> — Validation failure (handled by ControllerAdvice)</li>
 *   <li><strong>404 Not Found</strong> — Account not found (handled by ControllerAdvice)</li>
 *   <li><strong>422 Unprocessable Entity</strong> — Credit limit exceeded (handled by ControllerAdvice)</li>
 * </ul>
 *
 * <h3>Confirmation Flow Mapping</h3>
 * <p>In the original COBOL/CICS implementation (COBIL00C.cbl PROCESS-ENTER-KEY):</p>
 * <ol>
 *   <li>User enters account ID on BMS screen (ACTIDINI PIC X(11))</li>
 *   <li>COBOL reads account, displays current balance</li>
 *   <li>User confirms with ENTER and CONFIRMI='Y' (or cancels with PF3 or 'N')</li>
 *   <li>COBOL processes payment: creates transaction, updates balance</li>
 * </ol>
 * <p>In the Java REST migration:</p>
 * <ol>
 *   <li>Client fetches account details via {@code GET /api/accounts/{id}}</li>
 *   <li>Client sends {@code POST /api/billing/pay} with accountId and confirmIndicator</li>
 *   <li>If confirmIndicator is not "Y" — return HTTP 200 with cancellation message</li>
 *   <li>If confirmIndicator is "Y" — process payment, return HTTP 201 with Transaction</li>
 * </ol>
 *
 * <p>Source traceability: COBIL00C.cbl — CardDemo v1.0-15-g27d6c6f-68</p>
 *
 * @see BillPaymentService
 * @see BillPaymentRequest
 * @see Transaction
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    /**
     * SLF4J logger for structured logging with correlation IDs.
     * Logs bill payment requests at INFO level with account ID before delegating
     * to the service, supporting the AAP §0.7.1 observability requirement for
     * structured logging with correlation IDs propagated via MDC.
     * CRITICAL: Sensitive financial data is never logged in plaintext.
     */
    private static final Logger logger = LoggerFactory.getLogger(BillingController.class);

    /**
     * Bill payment service implementing the full atomic payment cycle.
     * Migrated from COBIL00C.cbl (572 lines), performs account lookup, balance
     * validation, cross-reference resolution, auto-ID generation, transaction
     * creation, and account balance update within a single {@code @Transactional}
     * operation. Injected via constructor injection (no {@code @Autowired}
     * annotation per Spring best practices).
     */
    private final BillPaymentService billPaymentService;

    /**
     * Constructs a new BillingController with the required BillPaymentService dependency.
     *
     * <p>Uses Spring constructor injection, which is the recommended dependency injection
     * pattern. When a class has a single constructor, Spring automatically uses it for
     * injection without requiring the {@code @Autowired} annotation.</p>
     *
     * @param billPaymentService the service handling the full atomic bill payment cycle
     *                            including account lookup, balance validation,
     *                            cross-reference resolution, auto-ID generation,
     *                            transaction creation, and balance update;
     *                            must not be {@code null}
     */
    public BillingController(BillPaymentService billPaymentService) {
        this.billPaymentService = billPaymentService;
    }

    /**
     * Processes a bill payment request for the specified account.
     *
     * <p>This endpoint is the Java equivalent of COBOL COBIL00C.cbl paragraph
     * PROCESS-ENTER-KEY (lines 154-244). It preserves the two-step confirmation
     * pattern from the original BMS screen interaction where the user first views
     * account details, then confirms with 'Y' to proceed with payment.</p>
     *
     * <h3>Confirmation Flow (COBIL00C.cbl lines 168-243)</h3>
     * <p>The COBOL program evaluates the CONFIRMI field:</p>
     * <ul>
     *   <li>If CONFIRMI = 'Y' or 'y' → proceed with full payment cycle</li>
     *   <li>If CONFIRMI = 'N' or 'n' → cancel, clear screen fields (CLEAR-CURRENT-SCREEN)</li>
     *   <li>If CONFIRMI = SPACES → initial request, display account info only</li>
     *   <li>Otherwise → invalid confirmation value error</li>
     * </ul>
     * <p>In the REST migration, the controller checks the confirmIndicator:
     * if it is not "Y" (case-insensitive), the payment is cancelled with HTTP 200.
     * An explicit "Y" is required to proceed with payment, matching the COBOL
     * behavioral parity requirement.</p>
     *
     * <h3>Payment Cycle (on confirmation "Y")</h3>
     * <p>The {@link BillPaymentService#processPayment(String)} method performs
     * the complete atomic cycle within a {@code @Transactional} boundary:</p>
     * <ol>
     *   <li>Account lookup via ACCTDAT (READ-ACCTDAT-FILE, lines 343-372)</li>
     *   <li>Balance validation — checks current balance &gt; 0 (nothing to pay check)</li>
     *   <li>Cross-reference resolution via CXACAIX (READ-CXACAIX-FILE, lines 408-436)</li>
     *   <li>Auto-ID generation — browse to end + increment (STARTBR/READPREV/ENDBR, lines 441-505)</li>
     *   <li>Transaction creation with hardcoded values:
     *       tranTypeCd="02", tranCatCd=0002, tranSource="POS TERM",
     *       tranDesc="BILL PAYMENT - ONLINE", merchantId="999999999",
     *       merchantName="BILL PAYMENT" (WRITE-TRANSACT-FILE, lines 510-547)</li>
     *   <li>Account balance update — COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT
     *       (UPDATE-ACCTDAT-FILE, lines 377-403)</li>
     * </ol>
     *
     * <h3>HTTP Response Codes</h3>
     * <ul>
     *   <li><strong>200 OK</strong> — Payment cancelled (confirmIndicator is not "Y")</li>
     *   <li><strong>201 Created</strong> — Payment successful, new Transaction created.
     *       The response body contains the full Transaction entity with all fields
     *       including the auto-generated 16-character transaction ID.</li>
     *   <li><strong>400 Bad Request</strong> — Validation failure (blank accountId,
     *       invalid confirmIndicator). Triggered by {@code @Valid} bean validation
     *       and handled by {@code @ControllerAdvice}.</li>
     *   <li><strong>404 Not Found</strong> — Account not found during lookup.
     *       Thrown as {@code RecordNotFoundException} by the service and handled
     *       by {@code @ControllerAdvice}.</li>
     *   <li><strong>422 Unprocessable Entity</strong> — Payment would exceed credit
     *       limit (COBOL reject code 102). Thrown as
     *       {@code CreditLimitExceededException} by the service and handled by
     *       {@code @ControllerAdvice}.</li>
     * </ul>
     *
     * @param request the bill payment request DTO containing:
     *                {@code accountId} — the 11-character account identifier
     *                (validated via {@code @NotBlank} and {@code @Size(max=11)},
     *                 mapping BMS field ACTIDINI PIC X(11)); and
     *                {@code confirmIndicator} — "Y" to proceed or "N" to cancel
     *                (validated via {@code @Pattern(regexp="^[YN]?$")},
     *                 mapping BMS field CONFIRMI PIC X(1))
     * @return {@code ResponseEntity<?>} with either:
     *         <ul>
     *           <li>HTTP 200 with cancellation message if confirmIndicator is not "Y"</li>
     *           <li>HTTP 201 with created Transaction entity if payment succeeds</li>
     *         </ul>
     */
    @PostMapping("/pay")
    public ResponseEntity<?> payBill(@Valid @RequestBody BillPaymentRequest request) {

        // Log bill payment request for observability (AAP §0.7.1).
        // Correlation ID is automatically injected via MDC by CorrelationIdFilter.
        // Account ID is safe to log; sensitive financial data (balances, amounts) is
        // never logged at the controller level.
        logger.info("Processing bill payment for account {}", request.getAccountId());

        // Confirmation check — mirrors COBOL COBIL00C.cbl PROCESS-ENTER-KEY
        // paragraph (lines 168-175). In COBOL, the CONFIRMI field is evaluated:
        //   'Y'/'y' → proceed with payment cycle
        //   'N'/'n' → cancel, clear fields (CLEAR-CURRENT-SCREEN paragraph)
        //   SPACES  → initial account display (no payment)
        //   OTHER   → invalid confirmation error
        // The REST equivalent requires an explicit 'Y' to proceed, matching
        // the COBOL behavioral parity requirement. Any other value (including
        // null, blank, or 'N') cancels the payment.
        String confirmIndicator = request.getConfirmIndicator();
        if (!"Y".equalsIgnoreCase(confirmIndicator)) {
            logger.info("Bill payment cancelled by user for account {} (confirmIndicator='{}')",
                    request.getAccountId(), confirmIndicator);
            return ResponseEntity.ok("Bill payment cancelled");
        }

        // Delegate to BillPaymentService for the complete atomic payment cycle.
        // All business logic (account lookup, balance validation, cross-reference
        // resolution, auto-ID generation, transaction creation, balance update) is
        // in the service layer — the controller contains no business logic per
        // architectural conventions.
        var result = billPaymentService.processPayment(request.getAccountId());

        logger.info("Bill payment completed successfully for account {}, transactionId={}",
                request.getAccountId(), result.getTranId());

        // Return HTTP 201 Created — a new Transaction entity was created in the
        // database, mapping COBOL WRITE-TRANSACT-FILE (lines 510-547) where the
        // DFHRESP(NORMAL) response displays "Payment successful. Your Transaction
        // ID is {tranId}." The response body contains the full Transaction entity
        // with all fields for the client to display.
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}
