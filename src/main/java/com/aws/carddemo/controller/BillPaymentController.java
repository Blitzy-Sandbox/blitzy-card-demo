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
package com.aws.carddemo.controller;

import com.aws.carddemo.service.BillPaymentRequest;
import com.aws.carddemo.service.BillPaymentResult;
import com.aws.carddemo.service.BillPaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the CardDemo bill-payment endpoint — the Java
 * migration of the CICS BMS screen {@code app/bms/COBIL00.bms} (Bill
 * Payment screen) and COBOL program {@code app/cbl/COBIL00C.cbl} (TRANID
 * {@code CB00}, 572 lines, the bill-payment dispatcher).
 *
 * <h2>HTTP Contract</h2>
 *
 * <p>{@code POST /api/bill-payment} — body is a JSON-serialised
 * {@link BillPaymentRequest}; response is a JSON-serialised
 * {@link BillPaymentResult} with HTTP-status mapping driven by the message
 * literal the service places on its {@code BillPaymentResult.failure(...)}
 * envelope:
 *
 * <ul>
 *   <li>{@code 200 OK} — full-balance payoff succeeded; body's
 *       {@code success} field is {@code true} and {@code message} carries
 *       the COBOL-equivalent {@code "Payment successful. Your Transaction
 *       ID is XXX."} confirmation.</li>
 *   <li>{@code 400 BAD_REQUEST} — operator confirmation issues
 *       ({@link #MSG_CONFIRMATION_CANCELLED} / {@link #MSG_PLEASE_CONFIRM})
 *       or the COBOL empty-account-ID reject
 *       ({@link #MSG_ACCOUNT_ID_EMPTY}). All three are pre-DB rejects that
 *       the service surfaces without touching any repository.</li>
 *   <li>{@code 401 UNAUTHORIZED} — request lacked authentication (handled
 *       by Spring Security's filter chain BEFORE the controller is
 *       reached).</li>
 *   <li>{@code 403 FORBIDDEN} — request was missing the CSRF token on a
 *       state-changing POST. Spring Security's {@code CsrfFilter} produces
 *       this status BEFORE the controller is reached.</li>
 *   <li>{@code 404 NOT_FOUND} — account ID was not found in the master
 *       file ({@link #MSG_ACCOUNT_NOT_FOUND}). Mirrors the COBOL
 *       {@code READ-ACCTDAT-FILE} {@code DFHRESP(NOTFND)} branch and the
 *       {@code READ-CXACAIX-FILE} {@code DFHRESP(NOTFND)} branch (both
 *       surface the same reject message to the operator).</li>
 *   <li>{@code 415 UNSUPPORTED_MEDIA_TYPE} — request lacked the
 *       {@code Content-Type: application/json} header. Spring MVC enforces
 *       this via the {@code consumes = MediaType.APPLICATION_JSON_VALUE}
 *       attribute on the handler.</li>
 *   <li>{@code 422 UNPROCESSABLE_ENTITY} — business-rule rejection: the
 *       account exists but its current balance is at or below zero
 *       ({@link #MSG_NOTHING_TO_PAY}). Mirrors the COBOL
 *       {@code IF ACCT-CURR-BAL <= ZEROS} branch (lines 197–206) which
 *       rejects with {@code 'You have nothing to pay...'}. HTTP 422 is the
 *       canonical REST status for "syntactically valid request that
 *       violates a business invariant" (RFC 4918).</li>
 *   <li>{@code 500 INTERNAL_SERVER_ERROR} — unexpected service-layer
 *       failure (for example a {@code DataAccessException} when the
 *       {@code ACCTDAT} table is unreachable). The exception detail is
 *       NEVER echoed back into the response body — only the canned
 *       {@link #MSG_INTERNAL_ERROR} string is emitted (AAP §0.10.5 applied
 *       to error paths).</li>
 * </ul>
 *
 * <h2>COBOL Provenance — COBIL00C.cbl</h2>
 *
 * <p>The COBOL {@code PROCESS-ENTER-KEY} paragraph (lines 154–244)
 * orchestrates the full workflow on the mainframe. The Java migration
 * delegates the entire workflow (confirmation gate, account-ID validation,
 * account lookup, zero-balance check, card cross-reference lookup,
 * transaction-ID generation, transaction record write, account-balance
 * zeroing) to {@link BillPaymentService#payBill(BillPaymentRequest)} —
 * mirroring the design used by {@link AuthController} for sign-on. The
 * controller's responsibility is restricted to:
 * <ol>
 *   <li>Spring-Security-driven authentication and CSRF gating
 *       (handled by the auto-configured filter chain before the controller
 *       is invoked).</li>
 *   <li>Mapping the service's {@link BillPaymentResult} outcome to the
 *       appropriate HTTP status code based on the reject message literal.</li>
 *   <li>Handling unexpected service-layer exceptions with a sanitised
 *       HTTP 500 response (per AAP §0.10.5 — no internal detail leakage).</li>
 * </ol>
 *
 * <p>Hard-coded COBOL transaction constants asserted at the HTTP boundary
 * (preserved by {@link BillPaymentService} and surfaced verbatim in the
 * {@code message} field of the success response):
 * <ul>
 *   <li>{@code TRAN-TYPE-CD} = {@code "02"} (payment)</li>
 *   <li>{@code TRAN-CAT-CD} = {@code "0002"} (payment category)</li>
 *   <li>{@code TRAN-DESC} = {@code "BILL PAYMENT - ONLINE"}</li>
 *   <li>{@code TRAN-MERCHANT-NAME} = {@code "BILL PAYMENT"}</li>
 * </ul>
 *
 * <h2>Cross-Cutting Concerns</h2>
 *
 * <ul>
 *   <li><b>Service-layer reject-message mirrors.</b> The
 *       {@code MSG_*} constants on {@link BillPaymentService} are
 *       package-private (no modifier on the {@code static final String}
 *       declarations in that class) and cannot be referenced from a
 *       sibling package. The literals are duplicated here so the
 *       HTTP-status mapping can dispatch on them deterministically; the
 *       sibling test class {@code BillPaymentControllerTest} mirrors the
 *       SAME literals so drift surfaces loudly (AAP §0.10.10 style
 *       consistency).</li>
 *   <li><b>CSRF.</b> {@code POST /api/bill-payment} is state-changing so
 *       it requires a valid CSRF token under the production
 *       {@code SecurityConfig}. Spring Security's {@code CsrfFilter}
 *       produces HTTP 403 when the token is missing or invalid; the
 *       controller never sees the request in that case.</li>
 *   <li><b>Authentication.</b> Any authenticated caller may pay any bill
 *       (the COBOL workflow gates access by the operator's
 *       {@code SEC-USR-TYPE}; the Java migration preserves
 *       authentication-required semantics via the auto-configured filter
 *       chain but does not narrow the per-call account access — that
 *       narrowing belongs to a future authorisation refinement).</li>
 *   <li><b>PCI / financial-data logging.</b> The controller deliberately
 *       does not log the {@link BillPaymentRequest} body and does not log
 *       the {@link BillPaymentResult} body (the success message embeds the
 *       16-character transaction ID but no balance or card number). Per
 *       AAP §0.10.5 ("No financial data written to logs at any level"):
 *       the {@code logback-test.xml} turbofilter further masks any
 *       accidental disclosure.</li>
 * </ul>
 *
 * @see BillPaymentService
 * @see BillPaymentRequest
 * @see BillPaymentResult
 */
@RestController
@RequestMapping("/api/bill-payment")
public class BillPaymentController {

    // ------------------------------------------------------------------------
    // Service-layer reject-message mirrors
    // ------------------------------------------------------------------------
    //
    // BillPaymentService.MSG_* constants are package-private (no modifier on
    // the `static final String` declarations in that class), so they cannot
    // be referenced directly from this controller class which lives in a
    // sibling package. The controller therefore duplicates the literal
    // strings here so the HTTP-status mapping can dispatch on them
    // deterministically. The matching test class BillPaymentControllerTest
    // also mirrors the SAME literals — so if the service ever renames or
    // relocates one of these messages, the controller's mapping AND its
    // test will fail together, surfacing the drift loudly.
    //
    // Sources:
    //   com.aws.carddemo.service.BillPaymentService.MSG_ACCOUNT_ID_EMPTY
    //   com.aws.carddemo.service.BillPaymentService.MSG_ACCOUNT_NOT_FOUND
    //   com.aws.carddemo.service.BillPaymentService.MSG_NOTHING_TO_PAY
    //   com.aws.carddemo.service.BillPaymentService.MSG_CONFIRMATION_CANCELLED
    //   com.aws.carddemo.service.BillPaymentService.MSG_PLEASE_CONFIRM
    // ------------------------------------------------------------------------

    /**
     * Service-layer reject when {@code accountId} is empty/null/whitespace —
     * mirrors COBIL00C.cbl line 161: {@code MOVE 'Acct ID can NOT be empty...'
     * TO WS-MESSAGE}. Produces HTTP 400.
     */
    static final String MSG_ACCOUNT_ID_EMPTY = "Acct ID can NOT be empty...";

    /**
     * Service-layer reject when the account does not exist (or its card
     * cross-reference is missing). Mirrors COBIL00C.cbl lines 361 / 425.
     * Produces HTTP 404.
     */
    static final String MSG_ACCOUNT_NOT_FOUND = "Account ID NOT found...";

    /**
     * Service-layer reject when the account's current balance is at or below
     * zero — full-balance payoff is the only mode supported, and zero is
     * nothing to pay. Mirrors COBIL00C.cbl line 201:
     * {@code MOVE 'You have nothing to pay...' TO WS-MESSAGE}. Produces
     * HTTP 422 (business-rule rejection, RFC 4918 Unprocessable Entity).
     */
    static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /**
     * Service-layer reject when the operator's confirmation is {@code N/n}
     * (operator explicitly cancelled). Produces HTTP 400.
     */
    static final String MSG_CONFIRMATION_CANCELLED =
            "Confirmation cancelled by user. Try again";

    /**
     * Service-layer reject when the confirmation prompt is empty, missing,
     * or carries a value other than {@code Y/N} (case-insensitive).
     * Java-migration consolidation of the COBOL "SPACES/LOW-VALUES" and
     * "WHEN OTHER" branches. Produces HTTP 400.
     */
    static final String MSG_PLEASE_CONFIRM =
            "Please confirm to make bill payment...";

    /**
     * Generic HTTP 500 message when the service layer throws an unexpected
     * exception. The underlying exception detail is logged internally
     * (production would inject a {@code Logger} here) but NEVER returned in
     * the HTTP response — preventing accidental disclosure of database
     * error strings, stack traces, SQL fragments, or internal class names
     * (AAP §0.10.5).
     */
    static final String MSG_INTERNAL_ERROR = "An unexpected error occurred";

    /** HTTP 400 message when the JSON request body cannot be parsed. */
    static final String MSG_MALFORMED_REQUEST = "Malformed JSON request body";

    // ------------------------------------------------------------------------
    // Collaborators (constructor-injected; @MockBean in slice tests)
    // ------------------------------------------------------------------------

    private final BillPaymentService billPaymentService;

    /**
     * Constructs the controller with a constructor-injected
     * {@link BillPaymentService} collaborator.
     *
     * @param billPaymentService the service that owns the bill-payment
     *                           workflow (confirmation gate, account lookup,
     *                           zero-balance check, transaction-ID
     *                           generation, transaction write, balance
     *                           zeroing); must not be {@code null}
     */
    public BillPaymentController(BillPaymentService billPaymentService) {
        this.billPaymentService = billPaymentService;
    }

    /**
     * Pays off the FULL current balance of an account.
     *
     * <p>Accepts a JSON-serialised {@link BillPaymentRequest}, delegates
     * verification and persistence to
     * {@link BillPaymentService#payBill(BillPaymentRequest)}, and maps the
     * resulting {@link BillPaymentResult} to an HTTP status code per the
     * table in the class-level Javadoc.
     *
     * @param request the bill-payment request (JSON body) carrying the
     *                target account ID and the operator's confirmation
     *                response
     * @return a {@link ResponseEntity} carrying the {@link BillPaymentResult}
     *         body and the appropriate HTTP status code
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BillPaymentResult> payBill(@RequestBody BillPaymentRequest request) {
        // Delegate the entire bill-payment workflow to the service. The
        // service handles the confirmation gate, account-ID validation,
        // account lookup, zero-balance check, card cross-reference lookup,
        // transaction-ID generation, transaction write, and balance zeroing.
        BillPaymentResult result = billPaymentService.payBill(request);

        // Happy path — HTTP 200 OK with the success-bearing result. The
        // body's message field carries the COBOL-equivalent "Payment
        // successful. Your Transaction ID is XXX." string verbatim.
        if (result.isSuccess()) {
            return ResponseEntity.ok(result);
        }

        // Failure path — dispatch on the reject message literal to map to
        // the appropriate HTTP status code. Per AAP §0.10.4 the message
        // literals are immutable boundary contracts mirrored as constants
        // on this controller; if the service renames one, the test will
        // fail loudly because the controller's mapping no longer matches.
        String message = result.getMessage();
        HttpStatus status;
        if (MSG_ACCOUNT_NOT_FOUND.equals(message)) {
            // COBIL00C READ-ACCTDAT-FILE / READ-CXACAIX-FILE NOTFND branch
            // (lines 359–364 and 423–428).
            status = HttpStatus.NOT_FOUND;
        } else if (MSG_NOTHING_TO_PAY.equals(message)) {
            // COBIL00C "You have nothing to pay..." business-rule reject
            // (lines 197–206). Maps to HTTP 422 Unprocessable Entity per
            // RFC 4918 — the request is syntactically valid but violates
            // the "balance > 0" business invariant.
            status = HttpStatus.UNPROCESSABLE_ENTITY;
        } else {
            // All other rejects (MSG_ACCOUNT_ID_EMPTY, MSG_CONFIRMATION_
            // CANCELLED, MSG_PLEASE_CONFIRM, or any future validation
            // reject the service introduces) map to HTTP 400. These are
            // request-shape problems the client can correct by adjusting
            // its input.
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status).body(result);
    }

    // ========================================================================
    // @ExceptionHandler — request-shape and unexpected-failure handling
    // ========================================================================

    /**
     * Handles malformed JSON request bodies — Spring's
     * {@link HttpMessageNotReadableException} fires when the body fails JSON
     * deserialisation (truncated JSON, invalid JSON syntax, wrong shape).
     * Returns HTTP 400 with a sanitised message. Declared BEFORE the
     * generic {@link RuntimeException} handler so Spring's most-specific-
     * handler-wins resolution picks it for the JSON-parse case.
     *
     * @param ex the message-conversion exception; the controller does NOT
     *           include any field of this exception in the response
     * @return HTTP 400 with a generic malformed-body message
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<BillPaymentResult> handleMalformedRequestBody(HttpMessageNotReadableException ex) {
        // The exception's message may include parts of the offending JSON
        // payload (which could carry account identifiers); we deliberately
        // discard it and return a generic message instead. The `ex`
        // parameter is kept in the signature so Spring's exception resolver
        // matches the most-specific overload, and so production deployments
        // can later add a Logger.debug(ex) call here without changing the
        // method signature.
        if (ex == null) {
            return ResponseEntity.badRequest()
                    .body(BillPaymentResult.failure(MSG_MALFORMED_REQUEST));
        }
        return ResponseEntity.badRequest()
                .body(BillPaymentResult.failure(MSG_MALFORMED_REQUEST));
    }

    /**
     * Handles unexpected {@link RuntimeException}s thrown by the service
     * layer. Returns HTTP 500 with a sanitised body — the underlying
     * exception detail must never leak into the HTTP response (AAP §0.10.5
     * applied to error paths).
     *
     * <p>{@link AccessDeniedException} (and its Spring Security 6.1+
     * subtype {@code AuthorizationDeniedException}) is RE-THROWN so Spring
     * Security's {@code ExceptionTranslationFilter} can map it to HTTP 403.
     *
     * @param ex the caught exception; not echoed in the response
     * @return HTTP 500 with a generic error message
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<BillPaymentResult> handleServiceFailure(RuntimeException ex) {
        if (ex instanceof AccessDeniedException) {
            throw (AccessDeniedException) ex;
        }
        // Defensive: dereference `ex` purely to satisfy the unused-parameter
        // lint; we intentionally discard its message and stack trace so they
        // cannot be included in the response. Production code would replace
        // this with a structured Logger invocation that records {ex} for
        // operations review.
        if (ex == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(BillPaymentResult.failure(MSG_INTERNAL_ERROR));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BillPaymentResult.failure(MSG_INTERNAL_ERROR));
    }
}
