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
import com.awsm2.carddemo.dto.BillPaymentDto;
import com.awsm2.carddemo.service.BillPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Billing REST controller &mdash; pay account balance.
 *
 * <p><b>COBOL provenance (AAP &sect;0.4.1):</b> Replaces
 * {@code app/cbl/COBIL00C.cbl} (CICS transaction id {@code CB00}) which
 * implemented the full-balance bill payment flow:</p>
 * <ol>
 *   <li>{@code READ ACCTDATA RIDFLD(ACCT-ID)} &mdash; load the account
 *       record to retrieve {@code ACCT-CURR-BAL} (the current balance
 *       to be paid).</li>
 *   <li>Display the current balance on {@code app/bms/COBIL00.bms} and
 *       request confirmation (Y/N).</li>
 *   <li>On confirmation:
 *     <ol>
 *       <li>{@code STARTBR / READNEXT TRANSACT} to find the next
 *           transaction ID (MAX-TRAN-ID+1 pattern).</li>
 *       <li>{@code GETCARDXREF-BYACCT} to resolve the card number
 *           (via {@code CXACAIX} AIX on {@code XREF-ACCT-ID}).</li>
 *       <li>{@code WRITE TRANSACT} with the new payment transaction
 *           (negative amount = credit).</li>
 *       <li>{@code READ UPDATE ACCTDATA} + {@code REWRITE} to zero the
 *           current balance (and decrement the current-cycle credit).</li>
 *     </ol>
 *   </li>
 * </ol>
 *
 * <p><b>Endpoint inventory (AAP &sect;0.3.4):</b></p>
 * <ul>
 *   <li>{@code POST /api/billing/pay} &mdash; submit a bill payment;
 *       the controller delegates to
 *       {@link BillPaymentService#payBill(BillPaymentDto)} which
 *       performs the {@code @Transactional} dual write of
 *       {@code Account} + {@code Transaction}, publishes the
 *       {@code account.updated} MSK event, and returns the resulting
 *       transaction ID. Returns HTTP 201 Created.</li>
 * </ul>
 *
 * <p><b>Event-driven publishing (AAP &sect;0.6.5):</b> the service
 * publishes the {@code account.updated} (and possibly
 * {@code transaction.posted}) MSK Kafka events partitioned by account
 * ID after the {@code @Transactional} commit, so downstream consumers
 * (audit, reporting, account-balance projection) react asynchronously
 * without coupling to the request lifecycle.</p>
 *
 * <p><b>Transactional integrity (AAP &sect;0.7.1):</b> the service-level
 * method is annotated with
 * {@code @Transactional(rollbackFor = Exception.class)} preserving the
 * COBOL {@code SYNCPOINT} / {@code SYNCPOINT ROLLBACK} semantic
 * (already present in {@code COBIL00C.cbl} as the dual write of
 * {@code TRANSACT} and {@code ACCTDATA}). On any failure the entire
 * unit-of-work rolls back, preserving the account balance and not
 * persisting the payment transaction.</p>
 *
 * <p><b>Cross-reference ordering (CP5 review):</b> the service now uses
 * the AIX-ordered query
 * {@code CardCrossReferenceRepository.findByXrefAcctIdOrderByXrefCardNumAsc(...)}
 * for deterministic primary-card selection on multi-card accounts,
 * matching the COBOL {@code CXACAIX} AIX which orders by
 * {@code XREF-CARD-NUM}.</p>
 *
 * <p><b>Layered architecture compliance:</b> thin Spring MVC fa&ccedil;ade;
 * delegates to {@link BillPaymentService} via constructor injection.</p>
 *
 * @see BillPaymentService
 * @see BillPaymentDto
 * @see com.awsm2.carddemo.dto.ApiResponse
 */
@RestController
@RequestMapping("/api/billing")
@Tag(name = "Billing",
        description = "Bill payment. Replaces CICS COBIL00C "
                + "(Tran-ID CB00, bill payment).")
public class BillingController {

    /**
     * SLF4J facade for structured JSON logging. Per AAP &sect;0.7.2.
     * PCI-DSS discipline (AAP &sect;0.6.6): only the account ID
     * (non-sensitive) is logged. Current balance, amount paid, and
     * transaction ID are NOT logged at controller level (the service
     * emits a separate audit event for the persisted payment).
     */
    private static final Logger LOG = LoggerFactory.getLogger(BillingController.class);

    /**
     * Bill payment service collaborator &mdash; encapsulates the
     * {@code @Transactional} dual write of {@code Account} +
     * {@code Transaction}, the MAX-TRAN-ID+1 sequence generation, the
     * CARDXREF AIX-ordered card lookup, and the MSK event publishing.
     * Originally implemented by the
     * {@code COBIL00C.cbl:PROCESS-ENTER-KEY} +
     * {@code WRITE-TRANSACT-FILE} +
     * {@code UPDATE-ACCTDATA-FILE} paragraphs.
     */
    private final BillPaymentService billPaymentService;

    /**
     * Constructor used by Spring's dependency injection container.
     *
     * @param billPaymentService the {@link BillPaymentService}
     *                           collaborator; never {@code null}
     */
    public BillingController(BillPaymentService billPaymentService) {
        this.billPaymentService = billPaymentService;
    }

    /**
     * Submits a bill payment for the supplied account.
     *
     * <p><b>COBOL provenance:</b> Replaces the entire
     * {@code COBIL00C.cbl:PROCESS-ENTER-KEY} +
     * {@code WRITE-TRANSACT-FILE} + {@code UPDATE-ACCTDATA-FILE} flow
     * described in the class-level Javadoc. The Java target requires
     * the client to first GET the account to see the current balance
     * (the {@link BillPaymentDto#currentBalance()} field is
     * {@code READ_ONLY} so the service ignores any client-supplied
     * value and reads the actual balance from the database), then
     * POST this payment with {@code confirm="Y"}.</p>
     *
     * <p><b>Confirmation semantics:</b> the {@code confirm} field
     * mirrors the COBOL {@code CONFIRMI PIC X(1)} BMS field. Only
     * {@code "Y"} initiates payment; {@code "N"} (or any other value)
     * results in a validation rejection from the service layer with
     * HTTP 400. This preserves the COBOL two-step "Type Y to confirm"
     * UX as a one-call REST flow where confirmation is part of the
     * payload.</p>
     *
     * <p><b>HTTP 201 Created</b> is the success status because the
     * payment creates a new transaction (identifiable by the returned
     * {@link BillPaymentDto#transactionId()} field).</p>
     *
     * @param request the validated {@link BillPaymentDto} carrying the
     *                {@code accountId} and {@code confirm} fields
     * @return {@link ResponseEntity} with HTTP 201 and the
     *         {@link BillPaymentDto} (including the generated
     *         {@code transactionId}, {@code postedAt}, and
     *         {@code amountPaid} fields) wrapped in
     *         {@link ApiResponse}. HTTP 400 on validation failure;
     *         HTTP 404 if the account does not exist
     */
    @PostMapping("/pay")
    @Operation(
            summary = "Pay full account balance",
            description = "Submits a bill payment for the supplied account "
                    + "(full balance pay-down). Creates a new payment "
                    + "transaction, decrements the account balance, and "
                    + "publishes the 'account.updated' MSK event. Replaces "
                    + "CICS COBIL00C / Tran-ID CB00 (bill payment)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Bill payment successful; transaction created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failure (confirmation != Y, "
                            + "missing fields, etc.)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Account not found")
    })
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<ApiResponse<BillPaymentDto>> payBill(
            @Valid @RequestBody BillPaymentDto request) {
        // COBOL: COBIL00C / Tran-ID CB00 -- PROCESS-ENTER-KEY +
        //   WRITE-TRANSACT-FILE + UPDATE-ACCTDATA-FILE (delegates to
        //   BillPaymentService which uses @Transactional dual write +
        //   CARDXREF AIX-ordered card selection +
        //   MSK 'account.updated' publish per AAP §0.4.1, §0.6.5).
        // PCI-DSS: do NOT log balance/amount fields. Only the
        // non-sensitive accountId metadata is logged.
        LOG.debug("Bill payment requested for accountId={}", request.accountId());
        BillPaymentDto result = billPaymentService.processBillPayment(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(result, "Bill payment successful"));
    }
}
