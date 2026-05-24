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
import com.awsm2.carddemo.dto.TransactionDetailDto;
import com.awsm2.carddemo.service.BillPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
// NOTE: The Swagger annotation
// io.swagger.v3.oas.annotations.responses.ApiResponse collides with the
// project's response-envelope DTO com.awsm2.carddemo.dto.ApiResponse.
// Java does NOT support import aliases (no `import X as Y` syntax), so this
// file uses the FULLY QUALIFIED NAME of the Swagger annotation inline in the
// @ApiResponses({...}) block below per the existing CardDemo controller
// convention (see AccountController, TransactionController, etc.). This
// keeps the project's `ApiResponse` reference unambiguous everywhere it
// appears as a Java type (e.g., ResponseEntity<ApiResponse<...>>).
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Bill payment REST controller.
 *
 * <p>Replaces CICS program {@code COBIL00C} (Tran-ID {@code CB00}) at
 * {@code app/cbl/COBIL00C.cbl} per AAP &sect;0.4.1, paired with BMS mapset
 * {@code app/bms/COBIL00.bms} and symbolic-map copybook
 * {@code app/cpy-bms/COBIL00.CPY}. The COBOL program implements a single
 * &quot;pay account balance in full&quot; online flow: read the
 * {@code ACCOUNT-RECORD} from {@code ACCTDAT}, resolve the cardholder's
 * primary card via the {@code CXACAIX} alternate index over
 * {@code CARDXREF}, generate the next sequential 16-digit transaction ID by
 * browsing {@code TRANSACT} to end ({@code MOVE HIGH-VALUES TO TRAN-ID;
 * STARTBR; READPREV; ENDBR; ADD 1 TO WS-TRAN-ID-NUM}), write a new
 * {@code TRAN-RECORD} (type {@code '02'} POS TERM with amount equal to the
 * current balance), and zero the {@code ACCT-CURR-BAL} via {@code REWRITE}
 * on {@code ACCTDAT} &mdash; all under an implicit CICS task-end
 * {@code SYNCPOINT}.</p>
 *
 * <p>The Java/Spring Boot target preserves every COBOL behavior verbatim
 * (per AAP &sect;0.7.3 Minimal Change Clause). The full implementation
 * sits in {@link BillPaymentService}; this controller is a thin Spring
 * MVC fa&ccedil;ade that:</p>
 * <ol>
 *   <li>activates Jakarta Bean Validation on the inbound
 *       {@link BillPaymentDto} (replacing the COBOL
 *       {@code WS-VALIDATION-FAIL-REASON} / {@code WS-ERR-FLG} cascade in
 *       {@code COBIL00C PROCESS-ENTER-KEY} L154&ndash;L191);</li>
 *   <li>enforces JWT-based authentication and role-based authorization
 *       on the endpoint via Spring Security (replacing the implicit COBOL
 *       {@code CDEMO-USRTYP-*} flag propagation from the central
 *       {@code COSGN00C} signon flow);</li>
 *   <li>delegates to
 *       {@link BillPaymentService#processBillPayment(BillPaymentDto)}
 *       which wraps the {@code TRANSACT} insert + {@code ACCTDAT} update
 *       in a single
 *       {@code @Transactional(rollbackFor = Exception.class, isolation =
 *       Isolation.READ_COMMITTED)} unit of work and publishes the
 *       {@code account.updated} and {@code transaction.posted} MSK Kafka
 *       events partitioned by account ID per AAP &sect;0.6.5;</li>
 *   <li>maps the service's {@link BillPaymentDto} result into a
 *       {@link TransactionDetailDto} carrying every field of the
 *       authoritative {@code TRAN-RECORD} layout (350-byte layout from
 *       {@code app/cpy/CVTRA05Y.cpy}) so REST consumers receive the
 *       canonical transaction-record shape they'd see from
 *       {@code GET /api/transactions/{id}};</li>
 *   <li>wraps the response in the standardized {@link ApiResponse}
 *       envelope (AAP &sect;0.3.4) with HTTP 201 Created.</li>
 * </ol>
 *
 * <h2>Endpoint inventory (AAP &sect;0.3.4)</h2>
 * <ul>
 *   <li>{@code POST /api/billing/pay} &mdash; submit a bill payment
 *       (full-balance pay-down) for the supplied account; returns the
 *       generated transaction record. Available to any authenticated
 *       principal with the {@code USER} or {@code ADMIN} role. Returns
 *       HTTP 201 Created on success.</li>
 * </ul>
 *
 * <h2>HTTP status codes (AAP &sect;0.3.4 standardized error envelope)</h2>
 * <ul>
 *   <li><b>201 Created</b> &mdash; bill payment processed; the response
 *       body carries the populated {@link TransactionDetailDto}.</li>
 *   <li><b>400 Bad Request</b> &mdash; Jakarta Bean Validation failure
 *       on the request body (missing {@code accountId}, non-11-digit
 *       account ID, {@code confirm} flag is not {@code Y} or
 *       {@code N}); intercepted by
 *       {@link com.awsm2.carddemo.exception.GlobalExceptionHandler} and
 *       translated into the standardized {@link ApiResponse} envelope
 *       with per-field errors. Replaces the COBOL
 *       {@code WS-VALIDATION-FAIL-REASON} / {@code WS-ERR-FLG} cascade
 *       in {@code COBIL00C} L154&ndash;L191.</li>
 *   <li><b>401 Unauthorized</b> &mdash; missing or invalid JWT;
 *       intercepted by the
 *       {@link com.awsm2.carddemo.security.JwtAuthenticationFilter} per
 *       AAP &sect;0.3.4.</li>
 *   <li><b>403 Forbidden</b> &mdash; authenticated principal does not
 *       carry the {@code USER} or {@code ADMIN} role; intercepted by
 *       {@link org.springframework.security.access.AccessDeniedException}
 *       handling in {@code GlobalExceptionHandler}.</li>
 *   <li><b>404 Not Found</b> &mdash;
 *       {@link com.awsm2.carddemo.exception.RecordNotFoundException}
 *       thrown by the service when no {@code Account} record exists for
 *       the supplied {@code accountId}, OR when no
 *       {@code CardCrossReference} row links a card to the account
 *       (replaces the COBOL {@code DFHRESP(NOTFND)} branches in
 *       {@code READ-ACCTDAT-FILE} L356&ndash;L372 and
 *       {@code READ-CXACAIX-FILE} L420&ndash;L436).</li>
 *   <li><b>409 Conflict</b> &mdash;
 *       {@link com.awsm2.carddemo.exception.ConcurrentModificationException}
 *       thrown on JPA optimistic-lock failure during the
 *       {@code REWRITE ACCTDAT} step (replaces the COBOL implicit
 *       before/after image comparison; in the Java target this is the
 *       {@code @Version} field on the {@code Account} entity per AAP
 *       &sect;0.6.2).</li>
 *   <li><b>422 Unprocessable Entity</b> &mdash; reserved for future
 *       business-rule exceptions that the AAP &sect;0.3.4 status-code
 *       table maps to 422 (e.g.,
 *       {@link com.awsm2.carddemo.exception.ExpiredCardException},
 *       {@link com.awsm2.carddemo.exception.CreditLimitExceededException}).
 *       The current
 *       {@link BillPaymentService#processBillPayment(BillPaymentDto)}
 *       implementation does not throw any 422-mapped exception
 *       (input-validation errors raise
 *       {@link com.awsm2.carddemo.exception.ValidationException} which
 *       {@code GlobalExceptionHandler} maps to <b>400</b> per AAP
 *       &sect;0.3.4); the entry is retained here for OpenAPI contract
 *       completeness and to reserve the response shape for future
 *       expansions (e.g., adding a card-expiration check to the
 *       bill-payment flow).</li>
 *   <li><b>400 Bad Request</b> (additional) &mdash;
 *       {@link com.awsm2.carddemo.exception.ValidationException}
 *       thrown by the service for business-rule input violations
 *       &mdash; invalid confirm flag (verbatim COBOL message
 *       {@code "Invalid value. Valid values are (Y/N)..."} per
 *       L185&ndash;L190). Mapped to 400 by
 *       {@link com.awsm2.carddemo.exception.GlobalExceptionHandler}
 *       per AAP &sect;0.3.4.</li>
 *   <li><b>500 Internal Server Error</b> &mdash;
 *       {@link com.awsm2.carddemo.exception.OnSizeErrorException}
 *       thrown when the post-payment balance would overflow the
 *       {@code ACCT-CURR-BAL PIC S9(10)V99} precision ceiling, or when
 *       the MAX-TRAN-ID + 1 arithmetic would exhaust the 16-digit ID
 *       space (replicates the COBOL {@code ON SIZE ERROR} semantic per
 *       AAP &sect;0.7.1).</li>
 * </ul>
 *
 * <h2>Event-driven publishing (AAP &sect;0.6.5)</h2>
 *
 * <p>The {@link BillPaymentService} publishes two MSK Kafka events
 * after the {@code @Transactional} commit: {@code transaction.posted}
 * (carrying the new {@code TRAN-RECORD}) and {@code account.updated}
 * (carrying the updated {@code ACCOUNT-RECORD}). Both events are
 * partitioned by account ID so that all events for the same account
 * land on the same partition and remain strictly ordered for any
 * single consumer, satisfying the AAP &sect;0.6.5 per-account
 * ordering invariant. Publishing failures are logged inside the
 * adapter and do NOT roll back the database state &mdash; the
 * Kafka producer is configured with
 * {@code acks=all}+{@code enable.idempotence=true} so retries cover
 * transient failures.</p>
 *
 * <h2>PCI-DSS logging discipline (AAP &sect;0.6.6, &sect;0.7.1)</h2>
 *
 * <p>Per the PCI-DSS-aligned PII discipline, this controller logs
 * ONLY the non-sensitive metadata of the request: the 11-digit
 * {@code accountId} and the {@code confirm} flag. The 16-digit card
 * number (PAN) is never accessed at the controller layer (it is
 * resolved server-side by the service via the {@code CXACAIX}
 * cross-reference). The current balance, paid amount, and new balance
 * are not logged at controller-level (the service emits a separate
 * audit event with PII-safe payloads to CloudTrail + OpenSearch per
 * AAP &sect;0.6.6).</p>
 *
 * <h2>Layered architecture compliance (AAP &sect;0.3.3)</h2>
 *
 * <p>This controller is a thin Spring MVC fa&ccedil;ade that delegates
 * all business logic to {@link BillPaymentService} via constructor
 * injection. The controller does NOT:</p>
 * <ul>
 *   <li>call any repository directly &mdash; the service owns
 *       persistence;</li>
 *   <li>call any AWS SDK directly &mdash; the service delegates to
 *       adapter beans (per AAP &sect;0.3.3 Adapter Pattern);</li>
 *   <li>perform any monetary arithmetic &mdash; the service owns
 *       {@link java.math.BigDecimal} arithmetic with
 *       {@link java.math.RoundingMode#HALF_EVEN} per AAP &sect;0.6.1;</li>
 *   <li>catch any domain exception &mdash; the
 *       {@code GlobalExceptionHandler} {@code @RestControllerAdvice}
 *       catches them globally.</li>
 * </ul>
 *
 * <h2>Mapping BillPaymentDto &rarr; TransactionDetailDto</h2>
 *
 * <p>The {@link BillPaymentService} returns a {@link BillPaymentDto}
 * (the same DTO used as the request body), populated with the
 * confirmation receipt fields: {@code accountId}, {@code currentBalance}
 * (the new, post-payment balance), {@code confirm} (echoed),
 * {@code transactionId} (the generated 16-digit ID), {@code postedAt}
 * (the processing timestamp), and {@code amountPaid} (the paid
 * amount). This controller maps that DTO into a
 * {@link TransactionDetailDto} which carries the FULL 350-byte
 * {@code TRAN-RECORD} layout. The fields that are not present on
 * {@link BillPaymentDto} are populated from the COBOL literal
 * constants documented in
 * {@code COBIL00C.cbl:PROCESS-ENTER-KEY} (L219&ndash;L229):</p>
 * <ul>
 *   <li>{@code transactionType} = {@code "02"} (COBOL:
 *       {@code MOVE '02' TO TRAN-TYPE-CD}, L220)</li>
 *   <li>{@code transactionCategory} = {@code 2} (COBOL:
 *       {@code MOVE 2 TO TRAN-CAT-CD}, L221)</li>
 *   <li>{@code source} = {@code "POS TERM"} (COBOL:
 *       {@code MOVE 'POS TERM' TO TRAN-SOURCE}, L222)</li>
 *   <li>{@code description} = {@code "BILL PAYMENT - ONLINE"} (COBOL:
 *       {@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC}, L223)</li>
 *   <li>{@code merchantId} = {@code 999999999L} (COBOL:
 *       {@code MOVE 999999999 TO TRAN-MERCHANT-ID}, L226)</li>
 *   <li>{@code merchantName} = {@code "BILL PAYMENT"} (COBOL:
 *       {@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME}, L227)</li>
 *   <li>{@code merchantCity} = {@code "N/A"} (COBOL:
 *       {@code MOVE 'N/A' TO TRAN-MERCHANT-CITY}, L228)</li>
 *   <li>{@code merchantZip} = {@code "N/A"} (COBOL:
 *       {@code MOVE 'N/A' TO TRAN-MERCHANT-ZIP}, L229)</li>
 *   <li>{@code cardNumber} = {@code null} &mdash; the PAN is
 *       deliberately not exposed on this DTO at the controller boundary
 *       per PCI-DSS PAN-handling discipline (AAP &sect;0.6.6); the full
 *       PAN is persisted on the underlying {@code Transaction} entity
 *       and is available via {@code GET /api/transactions/{id}} to
 *       authorized callers, but the bill-payment response receipt
 *       intentionally suppresses it.</li>
 *   <li>{@code originationTimestamp} = {@code processingTimestamp}
 *       = {@link BillPaymentDto#postedAt()} (COBOL: both
 *       {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are set to
 *       {@code WS-TIMESTAMP} at L231&ndash;L232).</li>
 * </ul>
 *
 * <p>For the non-success service paths (cancellation, blank-confirm
 * preview, &quot;nothing to pay&quot; short-circuit), the service
 * returns a {@link BillPaymentDto} with {@code transactionId},
 * {@code postedAt}, and {@code amountPaid} all {@code null}. Per the
 * service contract and the
 * {@link BillPaymentService#processBillPayment(BillPaymentDto)
 * service Javadoc}, those paths still emit HTTP 201 Created with the
 * mapped {@link TransactionDetailDto} (whose fields will reflect the
 * null state appropriately); HTTP 422 is reserved for hard validation
 * failures the service throws via
 * {@link com.awsm2.carddemo.exception.ValidationException}.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3 traceability)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COBIL00C.cbl}
 *       (CICS TRANID {@code 'CB00'}; files {@code 'ACCTDAT'} +
 *       {@code 'CARDXREF'} + {@code 'TRANSACT'})</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COBIL00.bms} (mapset
 *       {@code COBIL00}, map {@code COBIL0A})</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COBIL00.CPY}</li>
 *   <li><b>Record layouts:</b> {@code app/cpy/CVACT01Y.cpy} (account),
 *       {@code app/cpy/CVTRA05Y.cpy} (transaction),
 *       {@code app/cpy/CVACT03Y.cpy} (card cross-reference)</li>
 *   <li><b>Delegated service:</b> {@link BillPaymentService}
 *       (one-service-per-COBOL-program mapping per AAP &sect;0.3.3 /
 *       &sect;0.7.1)</li>
 * </ul>
 *
 * @see BillPaymentService
 * @see BillPaymentDto
 * @see TransactionDetailDto
 * @see ApiResponse
 */
@RestController
@RequestMapping("/api/billing")
@Tag(name = "Billing",
        description = "Bill payment processing. Replaces CICS COBIL00C "
                + "(Tran-ID CB00, bill payment).")
@Validated
public class BillingController {

    /**
     * SLF4J facade for structured JSON logging.
     *
     * <p>Per AAP &sect;0.7.2, the application uses structured JSON
     * logging via Logback + {@code logstash-logback-encoder}; the
     * resulting log lines are shipped to CloudWatch Logs by the ECS
     * Fargate log driver. Each log line includes the level, timestamp,
     * thread, logger name, MDC fields (correlation id, request id),
     * and the structured message arguments.</p>
     *
     * <p>PCI-DSS discipline (AAP &sect;0.6.6, &sect;0.7.1): this
     * controller logs ONLY the non-sensitive {@code accountId} (11
     * digits) and {@code confirm} flag (single character). The full
     * 16-digit PAN is never accessed at the controller layer; the
     * current balance, paid amount, and new balance are NOT logged
     * here (the service emits a separate, PII-safe audit event with
     * the last four digits of the PAN only).</p>
     */
    private static final Logger LOG =
            LoggerFactory.getLogger(BillingController.class);

    // =========================================================================
    // COBOL literal constants from COBIL00C.cbl PROCESS-ENTER-KEY (L218-L232).
    // These mirror the BILL_PAY_* constants declared in BillPaymentService and
    // are duplicated here verbatim per AAP §0.7.3 Minimal Change Clause so the
    // DTO-to-DTO mapper preserves the canonical TRAN-RECORD field values
    // without coupling to private state on the service. Any change to these
    // constants must also be made in BillPaymentService (the canonical
    // declaration site) so the persisted Transaction record matches the
    // emitted TransactionDetailDto.
    // =========================================================================

    /**
     * Transaction type code for bill payment.
     * <p>COBOL: {@code MOVE '02' TO TRAN-TYPE-CD}
     * ({@code app/cbl/COBIL00C.cbl} L220).
     */
    private static final String BILL_PAY_TYPE_CODE = "02";

    /**
     * Transaction category code for bill payment.
     * <p>COBOL: {@code MOVE 2 TO TRAN-CAT-CD}
     * ({@code app/cbl/COBIL00C.cbl} L221). The
     * {@link TransactionDetailDto#transactionCategory()} component is
     * typed as {@link Integer} per the DTO contract, so the COBOL
     * numeric literal {@code 2} translates verbatim to
     * {@code Integer.valueOf(2)}.
     */
    private static final Integer BILL_PAY_CAT_CODE = 2;

    /**
     * Transaction source channel for bill payment.
     * <p>COBOL: {@code MOVE 'POS TERM' TO TRAN-SOURCE}
     * ({@code app/cbl/COBIL00C.cbl} L222).
     */
    private static final String BILL_PAY_SOURCE = "POS TERM";

    /**
     * Transaction description string for bill payment.
     * <p>COBOL: {@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC}
     * ({@code app/cbl/COBIL00C.cbl} L223).
     */
    private static final String BILL_PAY_DESC = "BILL PAYMENT - ONLINE";

    /**
     * Merchant identifier constant for bill payment (9-digit numeric
     * literal).
     * <p>COBOL: {@code MOVE 999999999 TO TRAN-MERCHANT-ID}
     * ({@code app/cbl/COBIL00C.cbl} L226).
     */
    private static final Long BILL_PAY_MERCHANT_ID = 999_999_999L;

    /**
     * Merchant name constant for bill payment.
     * <p>COBOL: {@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME}
     * ({@code app/cbl/COBIL00C.cbl} L227).
     */
    private static final String BILL_PAY_MERCHANT_NAME = "BILL PAYMENT";

    /**
     * Merchant city/zip placeholder constant for bill payment.
     * <p>COBOL: {@code MOVE 'N/A' TO TRAN-MERCHANT-CITY} and
     * {@code MOVE 'N/A' TO TRAN-MERCHANT-ZIP}
     * ({@code app/cbl/COBIL00C.cbl} L228&ndash;L229).
     */
    private static final String BILL_PAY_NA = "N/A";

    /**
     * Human-readable success message attached to the {@link ApiResponse}
     * envelope on HTTP 201 Created responses. Kept as a class-level
     * constant for log-search consistency and to keep the controller
     * method body terse.
     */
    private static final String SUCCESS_MESSAGE =
            "Bill payment processed successfully";

    // =========================================================================
    // Collaborator beans (constructor-injected, final per AAP §0.7.1 DI rule).
    // =========================================================================

    /**
     * Bill payment service collaborator &mdash; encapsulates the entire
     * COBIL00C business logic per AAP &sect;0.3.3 (one-service-per-
     * COBOL-program mapping):
     * <ul>
     *   <li>Input validation cascade (account ID, confirm flag)
     *       replicating {@code COBIL00C PROCESS-ENTER-KEY}
     *       L154&ndash;L191;</li>
     *   <li>{@code ACCTDAT} read replicating
     *       {@code READ-ACCTDAT-FILE} L343&ndash;L372;</li>
     *   <li>{@code CXACAIX} alternate-index lookup replicating
     *       {@code READ-CXACAIX-FILE} L408&ndash;L436;</li>
     *   <li>MAX-TRAN-ID + 1 sequence generation replicating the
     *       {@code STARTBR}/{@code READPREV}/{@code ENDBR} HIGH-VALUES
     *       browse on {@code TRANSACT} L211&ndash;L217;</li>
     *   <li>{@code TRANSACT} insert + {@code ACCTDAT} update under one
     *       {@code @Transactional(rollbackFor = Exception.class)}
     *       boundary replicating the implicit CICS task-end
     *       {@code SYNCPOINT} (AAP &sect;0.6.2);</li>
     *   <li>{@code transaction.posted} and {@code account.updated}
     *       MSK Kafka event publication partitioned by account ID per
     *       AAP &sect;0.6.5;</li>
     *   <li>ElastiCache Redis cache-aside invalidation on the account-
     *       view cache key (AAP &sect;0.7.1);</li>
     *   <li>CloudTrail + OpenSearch immutable audit event emission
     *       (AAP &sect;0.6.6).</li>
     * </ul>
     */
    private final BillPaymentService billPaymentService;

    /**
     * Constructor used by Spring's dependency injection container.
     *
     * <p>Per AAP &sect;0.7.1, dependency injection is constructor-
     * based only &mdash; no field {@code @Autowired}, no setter
     * injection, no Lombok. The injected collaborator is non-null;
     * {@link Objects#requireNonNull(Object, String)} fast-fails if
     * Spring fails to wire it (defense in depth against
     * misconfiguration).</p>
     *
     * @param billPaymentService the {@link BillPaymentService}
     *                           collaborator; never {@code null}
     */
    public BillingController(BillPaymentService billPaymentService) {
        this.billPaymentService = Objects.requireNonNull(billPaymentService,
                "billPaymentService must not be null");
    }

    // =========================================================================
    // POST /api/billing/pay — replaces COBIL00C / Tran-ID CB00
    // =========================================================================

    /**
     * Process a bill payment for the supplied account, paying the
     * full current balance.
     *
     * <p><b>COBOL provenance:</b> Replaces the entire
     * {@code COBIL00C.cbl} {@code MAIN-PARA} +
     * {@code PROCESS-ENTER-KEY} flow (L99&ndash;L244). The Java
     * target preserves the COBOL behavior verbatim per AAP &sect;0.7.3
     * Minimal Change Clause:</p>
     * <ol>
     *   <li><b>Input validation</b> (COBOL L154&ndash;L191): Jakarta
     *       Bean Validation on the {@link BillPaymentDto} record
     *       components fires before the service is called. Empty
     *       account ID, non-11-digit account ID, missing confirm flag,
     *       or non-Y/N confirm flag all result in HTTP 400 (translated
     *       by {@code GlobalExceptionHandler}). Additional business-rule
     *       validation (e.g., invalid confirm sentinel) is performed by
     *       the service and surfaced as HTTP 422 via
     *       {@link com.awsm2.carddemo.exception.ValidationException}.</li>
     *   <li><b>Service delegation</b>: the controller invokes
     *       {@link BillPaymentService#processBillPayment(BillPaymentDto)}
     *       which performs the dual-write of {@code TRANSACT} +
     *       {@code ACCTDAT} under a single
     *       {@code @Transactional(rollbackFor = Exception.class,
     *       isolation = Isolation.READ_COMMITTED)} boundary and
     *       publishes the MSK Kafka events partitioned by account
     *       ID.</li>
     *   <li><b>Response shaping</b>: the controller maps the
     *       service's {@link BillPaymentDto} confirmation receipt into
     *       a {@link TransactionDetailDto} carrying the canonical
     *       {@code TRAN-RECORD} layout (350 bytes per
     *       {@code app/cpy/CVTRA05Y.cpy}), populating COBOL-literal
     *       fields from the verbatim constants documented in this
     *       class's Javadoc.</li>
     *   <li><b>HTTP envelope</b>: the response is wrapped in the
     *       standardized {@link ApiResponse} envelope and returned
     *       with HTTP 201 Created per AAP &sect;0.3.4.</li>
     * </ol>
     *
     * <p><b>HTTP status codes:</b></p>
     * <ul>
     *   <li>201 Created &mdash; bill payment processed; the response
     *       body carries the populated {@link TransactionDetailDto}
     *       wrapped in {@link ApiResponse#success(Object, String)}.</li>
     *   <li>400 Bad Request &mdash; Jakarta Bean Validation failure on
     *       the request body (caught by
     *       {@code GlobalExceptionHandler.handleMethodArgumentNotValid}).</li>
     *   <li>401 Unauthorized &mdash; missing or invalid JWT (caught by
     *       {@link com.awsm2.carddemo.security.JwtAuthenticationFilter}).</li>
     *   <li>403 Forbidden &mdash; authenticated principal does not
     *       hold the {@code USER} or {@code ADMIN} role (caught by
     *       {@code GlobalExceptionHandler.handleAccessDenied}).</li>
     *   <li>404 Not Found &mdash;
     *       {@link com.awsm2.carddemo.exception.RecordNotFoundException}
     *       thrown by the service when no account or card-cross-
     *       reference exists for the supplied {@code accountId}.</li>
     *   <li>409 Conflict &mdash;
     *       {@link com.awsm2.carddemo.exception.ConcurrentModificationException}
     *       thrown on JPA optimistic-lock failure during the account
     *       balance update.</li>
     *   <li>422 Unprocessable Entity &mdash; reserved per AAP
     *       &sect;0.3.4 for business-rule exceptions like
     *       {@link com.awsm2.carddemo.exception.ExpiredCardException}
     *       or
     *       {@link com.awsm2.carddemo.exception.CreditLimitExceededException};
     *       not currently emitted by the {@link BillPaymentService}
     *       implementation (input-validation failures map to 400 via
     *       {@link com.awsm2.carddemo.exception.ValidationException}).</li>
     *   <li>500 Internal Server Error &mdash;
     *       {@link com.awsm2.carddemo.exception.OnSizeErrorException}
     *       thrown when post-payment balance arithmetic would
     *       overflow the {@code PIC S9(10)V99} ceiling.</li>
     * </ul>
     *
     * @param request the validated {@link BillPaymentDto} carrying
     *                the {@code accountId} (11-digit) and {@code confirm}
     *                flag ({@code Y} or {@code N}); must not be
     *                {@code null}
     * @return {@link ResponseEntity} with HTTP 201 Created and the
     *         generated {@link TransactionDetailDto} wrapped in the
     *         standardized {@link ApiResponse} envelope
     */
    @PostMapping("/pay")
    @Operation(
            summary = "Process a bill payment",
            description = "Pays the full current balance of the specified "
                    + "account in a single transactional dual-write "
                    + "(Account balance + Transaction record) and publishes "
                    + "the 'account.updated' and 'transaction.posted' MSK "
                    + "events partitioned by account ID. Replaces CICS "
                    + "COBIL00C / Tran-ID CB00 (Bill Payment)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "Bill payment processed; transaction "
                            + "record returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Validation failed (accountId, confirm "
                            + "flag) — Jakarta Bean Validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Unauthenticated — JWT missing or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Forbidden — caller lacks USER or ADMIN role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Account or card cross-reference not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "Concurrent modification on account update "
                            + "(optimistic lock failure)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
                    description = "Business rule failure (reserved for "
                            + "future ExpiredCard / CreditLimitExceeded "
                            + "checks per AAP §0.3.4)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500",
                    description = "On size error — balance overflow or "
                            + "transaction ID overflow")
    })
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<ApiResponse<TransactionDetailDto>> processBillPayment(
            @Valid @RequestBody BillPaymentDto request) {
        // COBOL: COBIL00C / Tran-ID CB00 — Bill Payment
        //   Reads ACCTDAT + CCXREF, writes TRANSACT (TYPE='02' POS TERM),
        //   updates ACCTDAT balance. Confirmation flag (WS-CONF-PAY-FLG = 'Y')
        //   validated by service before commit. @Transactional dual-write
        //   replaces implicit CICS file-state coupling. Publishes
        //   'account.updated' Kafka event (partition key = account ID) per
        //   AAP §0.6.5. PCI-DSS (AAP §0.6.6): only non-sensitive accountId
        //   + confirm flag are logged — never the card number, current
        //   balance, paid amount, or new balance.
        Objects.requireNonNull(request, "request must not be null");
        LOG.info("Bill payment requested for accountId={} confirm={}",
                request.accountId(), request.confirm());

        // Delegate to the service which owns the @Transactional dual-write,
        // MSK event publication, cache invalidation, and audit emission.
        // The service returns a BillPaymentDto carrying the confirmation
        // receipt fields (transactionId, postedAt, amountPaid populated on
        // the happy path; null on cancel/preview/nothing-to-pay paths).
        BillPaymentDto serviceResult = billPaymentService.processBillPayment(request);

        // Map BillPaymentDto → TransactionDetailDto using the COBOL literal
        // constants for the fields that are not carried on BillPaymentDto.
        // The mapping preserves the canonical 350-byte TRAN-RECORD layout
        // documented in app/cpy/CVTRA05Y.cpy.
        TransactionDetailDto transactionRecord = mapToTransactionDetail(serviceResult);

        // Structured success log (PII-safe per AAP §0.6.6, §0.7.1).
        // Note: transactionRecord.transactionId() may be null on the
        // non-success service paths (cancel, blank-confirm preview,
        // nothing-to-pay short-circuit) per the service contract; the
        // SLF4J {} placeholder serializes null as the literal "null"
        // string so this log line is safe in all cases.
        LOG.info("Bill payment completed: tranId={} accountId={}",
                transactionRecord.transactionId(), request.accountId());

        // POST returns 201 Created per AAP §0.3.4 status-code mapping
        // (the endpoint creates a Transaction record on the success path).
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(transactionRecord, SUCCESS_MESSAGE));
    }

    // =========================================================================
    // Private helpers — BillPaymentDto → TransactionDetailDto mapping
    // =========================================================================

    /**
     * Maps a {@link BillPaymentDto} (returned by
     * {@link BillPaymentService#processBillPayment(BillPaymentDto)}) into
     * a {@link TransactionDetailDto} carrying the canonical 350-byte
     * {@code TRAN-RECORD} layout from {@code app/cpy/CVTRA05Y.cpy}.
     *
     * <p>The mapping populates fields not carried on the request/response
     * dual-purpose {@link BillPaymentDto} from the COBOL literal constants
     * declared in {@code COBIL00C.cbl PROCESS-ENTER-KEY} L218&ndash;L232
     * &mdash; preserving the verbatim {@code MOVE} statements per AAP
     * &sect;0.7.3 Minimal Change Clause:</p>
     * <pre>{@code
     *   COBOL                            Java target
     *   ------------------------------   ----------------------------------
     *   MOVE WS-TRAN-ID-NUM TO TRAN-ID   serviceResult.transactionId()
     *   MOVE '02' TO TRAN-TYPE-CD        BILL_PAY_TYPE_CODE
     *   MOVE 2 TO TRAN-CAT-CD            BILL_PAY_CAT_CODE
     *   MOVE 'POS TERM' TO TRAN-SOURCE   BILL_PAY_SOURCE
     *   MOVE 'BILL PAYMENT - ONLINE'     BILL_PAY_DESC
     *      TO TRAN-DESC
     *   MOVE ACCT-CURR-BAL TO TRAN-AMT   serviceResult.amountPaid()
     *   MOVE 999999999 TO                BILL_PAY_MERCHANT_ID
     *      TRAN-MERCHANT-ID
     *   MOVE 'BILL PAYMENT' TO           BILL_PAY_MERCHANT_NAME
     *      TRAN-MERCHANT-NAME
     *   MOVE 'N/A' TO                    BILL_PAY_NA
     *      TRAN-MERCHANT-CITY
     *   MOVE 'N/A' TO                    BILL_PAY_NA
     *      TRAN-MERCHANT-ZIP
     *   MOVE XREF-CARD-NUM TO            null (PCI-DSS PAN suppression
     *      TRAN-CARD-NUM                   at the controller boundary)
     *   MOVE WS-TIMESTAMP TO             serviceResult.postedAt()
     *      TRAN-ORIG-TS
     *   MOVE WS-TIMESTAMP TO             serviceResult.postedAt()
     *      TRAN-PROC-TS
     * }</pre>
     *
     * <p><b>PCI-DSS PAN suppression at the controller boundary (AAP
     * &sect;0.6.6, &sect;0.7.1):</b> the {@link TransactionDetailDto#cardNumber()}
     * component is deliberately set to {@code null} in the bill-payment
     * receipt because the {@link BillPaymentService} does not expose the
     * full PAN through its {@link BillPaymentDto} return value (the
     * service uses the resolved card number internally to populate the
     * persisted {@code Transaction} entity and the MSK event payload,
     * but masks it to the last four digits in the audit log and never
     * surfaces it back to the controller layer). Authorized callers who
     * need the full PAN for the just-posted bill payment can issue a
     * subsequent {@code GET /api/transactions/{id}} request, which
     * exposes the PAN per the transaction-detail contract; the bill-
     * payment confirmation receipt intentionally suppresses it as
     * defense in depth against accidental client-side logging.</p>
     *
     * <p><b>Non-success path handling:</b> on the cancellation path
     * ({@code confirm=N}), the blank-confirm preview path
     * ({@code confirm=""}), or the "nothing to pay" short-circuit
     * ({@code ACCT-CURR-BAL &le; 0}), the service returns a
     * {@link BillPaymentDto} with {@code transactionId},
     * {@code postedAt}, and {@code amountPaid} all {@code null}. The
     * mapper passes those null values through verbatim &mdash; the
     * resulting {@link TransactionDetailDto} carries null for those
     * fields, signaling to the caller that no transaction was posted.
     * The COBOL literal constants for type, category, source,
     * description, and merchant fields are still populated (these are
     * compile-time constants identifying the transaction as a
     * "BILL PAYMENT - ONLINE" attempt; they remain stable across
     * success and non-success paths).</p>
     *
     * @param serviceResult the {@link BillPaymentDto} returned by the
     *                      {@link BillPaymentService}; must not be
     *                      {@code null}
     * @return a populated {@link TransactionDetailDto} ready for the
     *         HTTP response envelope
     */
    // COBOL: COBIL00C:PROCESS-ENTER-KEY (L218-L232) — MOVE statements
    // populating TRAN-RECORD before WRITE-TRANSACT-FILE.
    private static TransactionDetailDto mapToTransactionDetail(BillPaymentDto serviceResult) {
        Objects.requireNonNull(serviceResult, "serviceResult must not be null");
        return new TransactionDetailDto(
                serviceResult.transactionId(),   // COBOL: MOVE WS-TRAN-ID-NUM TO TRAN-ID
                BILL_PAY_TYPE_CODE,              // COBOL: MOVE '02' TO TRAN-TYPE-CD
                BILL_PAY_CAT_CODE,               // COBOL: MOVE 2 TO TRAN-CAT-CD
                BILL_PAY_SOURCE,                 // COBOL: MOVE 'POS TERM' TO TRAN-SOURCE
                BILL_PAY_DESC,                   // COBOL: MOVE 'BILL PAYMENT - ONLINE'
                serviceResult.amountPaid(),      // COBOL: MOVE ACCT-CURR-BAL TO TRAN-AMT
                BILL_PAY_MERCHANT_ID,            // COBOL: MOVE 999999999 TO TRAN-MERCHANT-ID
                BILL_PAY_MERCHANT_NAME,          // COBOL: MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME
                BILL_PAY_NA,                     // COBOL: MOVE 'N/A' TO TRAN-MERCHANT-CITY
                BILL_PAY_NA,                     // COBOL: MOVE 'N/A' TO TRAN-MERCHANT-ZIP
                null,                            // PCI-DSS: PAN suppressed at controller boundary
                serviceResult.postedAt(),        // COBOL: MOVE WS-TIMESTAMP TO TRAN-ORIG-TS
                serviceResult.postedAt());       // COBOL: MOVE WS-TIMESTAMP TO TRAN-PROC-TS
    }
}
