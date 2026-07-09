package com.carddemo.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.carddemo.dto.BillPaymentRequest;
import com.carddemo.dto.BillPaymentResponse;
import com.carddemo.service.BillPaymentService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * REST controller for the online <strong>Bill Payment</strong> use case &mdash; the Java&nbsp;25 /
 * Spring&nbsp;Boot&nbsp;3.5 replacement for the legacy CICS/BMS 3270 program {@code COBIL00C}
 * (transaction {@code CB00}). The frozen COBOL source is referenced read-only at commit SHA
 * {@code 27d6c6f} (full {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}) and is never copied into
 * this repository (AAP&nbsp;&sect;0.5.1, "{@code BillPaymentController.java + service/BillPaymentService.java}
 * &larr; {@code COBIL00C.cbl}; Bill payment (CB00)").
 *
 * <p>In the mainframe design the {@code COBIL00} BMS map gathered an account id and a confirmation
 * flag; {@code COBIL00C}'s {@code PROCESS-ENTER-KEY} paragraph (source&nbsp;L154&ndash;L244) then
 * paid the account's <em>full current balance</em> after confirmation &mdash; writing a payment
 * transaction ({@code TRAN-TYPE-CD='02'}, {@code TRAN-CAT-CD=2}, source {@code 'POS TERM'},
 * description {@code 'BILL PAYMENT - ONLINE'}, amount {@code = ACCT-CURR-BAL}, merchant
 * {@code 999999999}/{@code 'BILL PAYMENT'}), auto-generating the transaction id from the last id
 * plus one, then decrementing the balance ({@code ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}) and
 * rewriting the account. This controller re-expresses only the <em>presentation boundary</em> of
 * that pseudo-conversational flow as a single stateless JSON endpoint; the confirmation flag
 * replaces the CICS {@code COMMAREA} confirm state (AAP&nbsp;&sect;0.8.4).</p>
 *
 * <h2>Thin controller by design</h2>
 * <p>This class contains <strong>no business logic</strong>. The entire {@code COBIL00C} decision
 * flow &mdash; the empty-account-id guard, the account read, the "nothing to pay" rule
 * ({@code ACCT-CURR-BAL <= 0}), the preview-versus-commit split on the confirmation flag, the
 * auto-generated transaction id, the balance decrement, and the transaction write &mdash; lives in
 * {@link BillPaymentService#payBill(BillPaymentRequest)}. The controller binds and validates the
 * HTTP request, records a non-sensitive audit line, delegates once, and returns the produced DTO.
 * It deliberately does <strong>not</strong> catch exceptions: a {@code ValidationException}
 * ({@code 400}; invalid account id or "nothing to pay") and a {@code ResourceNotFoundException}
 * ({@code 404}; account or card cross-reference missing) raised by the service, and every
 * request-binding failure, are translated to the shared {@code ErrorResponse} JSON body by
 * {@link GlobalExceptionHandler}.</p>
 *
 * <h2>Endpoint and path choice</h2>
 * <p>The operation is exposed as {@code POST /api/accounts/{accountId}/bill-payment} &mdash; the
 * account-scoped nested-resource form preferred for clarity over a flat
 * {@code POST /api/bill-payments}. The account id therefore travels in the URL path (its
 * authoritative, validated location); the optional request body carries only the confirmation flag.
 * A {@code 200 OK} is always returned on the non-error paths: when the request is <em>not</em>
 * confirmed the service returns a preview (the current balance plus the prompt "Confirm to make a
 * bill payment...") and persists nothing; when confirmed, the payment is committed and the response
 * carries the {@code paymentAmount}, {@code newBalance}, and generated {@code confirmationNumber}.</p>
 *
 * <h2>Path/body reconciliation</h2>
 * <p>The path variable is the single source of truth for the account id and is validated there
 * ({@code @NotBlank}, {@code @Size(max = 11)}, {@code @Pattern("\\d{1,11}")} under the class-level
 * {@code @Validated}), so a blank or non-numeric account id is rejected as {@code 400} before the
 * service is invoked. The body is intentionally <em>not</em> annotated {@code @Valid}: only its
 * {@code confirm} flag is consumed, and a {@code null} or absent body simply selects the preview
 * path. This keeps the natural request shape &mdash; {@code {"confirm": true}} &mdash; valid without
 * forcing the caller to duplicate the account id in the body. If the body <em>does</em> supply an
 * account id that disagrees with the path, that contradiction is rejected as {@code 400}. The
 * controller then constructs a normalized {@link BillPaymentRequest} from the path account id and the
 * body's {@code confirm} flag before delegating; the service re-validates the account id as
 * defence-in-depth.</p>
 *
 * <h2>Security</h2>
 * <p>This endpoint requires an authenticated caller but imposes <strong>no</strong> role restriction
 * &mdash; bill payment is reachable by regular users as well as administrators, so there is no admin
 * gate ({@code @PreAuthorize}) here. Authentication is enforced by the security filter chain in the
 * {@code config} package, not by this controller.</p>
 *
 * <h2>Decimal fidelity and observability</h2>
 * <p>The controller performs <strong>no</strong> arithmetic and uses no {@code float}/{@code double}:
 * every monetary value is a {@link java.math.BigDecimal} held at scale&nbsp;2 by the DTO layer, and
 * the balance decrement and payment amount are computed in the service (AAP&nbsp;&sect;0.8.2). Each
 * request and its outcome are logged at {@code INFO} with the (non-sensitive) account id and the
 * generated confirmation number; a full card number (PAN) or any secret is never logged. Every log
 * line carries the MDC {@code correlationId} established upstream by the observability correlation-id
 * filter (AAP&nbsp;&sect;0.7.1).</p>
 *
 * <p>The type is a stateless, thread-safe Spring singleton using constructor injection; its single
 * service collaborator is {@code final}. Design rationale and the COBOL-paragraph mapping are
 * recorded in {@code docs/decision-log.md} and {@code docs/traceability-matrix.md} (Explainability
 * rule), not in code comments.</p>
 *
 * @see BillPaymentService
 * @see BillPaymentRequest
 * @see BillPaymentResponse
 * @see GlobalExceptionHandler
 */
@RestController
@RequestMapping("/api")
@Validated
public class BillPaymentController {

    /**
     * Structured logger; every line carries the MDC {@code correlationId} supplied by the
     * correlation-id filter. It records only non-sensitive diagnostics (the account id and the
     * generated confirmation number) and never a full card number (PAN) or any secret.
     */
    private static final Logger log = LoggerFactory.getLogger(BillPaymentController.class);

    /**
     * The bill-payment service that performs the entire {@code COBIL00C} flow. Constructor injection
     * replaces the legacy static COBOL {@code CALL}/VSAM linkage and keeps the controller's single
     * dependency explicit and {@code final}.
     */
    private final BillPaymentService billPaymentService;

    /**
     * Creates the bill-payment controller with its service collaborator.
     *
     * <p>Constructor injection is used exclusively (never field injection) so the dependency is
     * explicit and {@code final}, and so the controller can be instantiated directly in a standalone
     * unit test with a mocked {@link BillPaymentService} &mdash; no Spring {@code ApplicationContext}
     * required.</p>
     *
     * @param billPaymentService the bill-payment service (never {@code null})
     */
    public BillPaymentController(final BillPaymentService billPaymentService) {
        this.billPaymentService = billPaymentService;
    }

    /**
     * Pays the identified account's current balance in full &mdash; the stateless REST migration of
     * the {@code COBIL00C} ({@code CB00}) enter-key flow.
     *
     * <p>The account id is taken from the URL path (its authoritative, already-validated location);
     * the optional body supplies only the confirmation flag. The controller reconciles the two (see
     * the class documentation), builds a normalized {@link BillPaymentRequest}, and delegates the
     * whole decision flow to {@link BillPaymentService#payBill(BillPaymentRequest)}:</p>
     * <ul>
     *   <li>When the request is <strong>not confirmed</strong> ({@code confirm} is {@code null} or
     *       {@code false}, or the body is absent), the service returns a preview carrying the current
     *       balance and the prompt "Confirm to make a bill payment..." with a {@code null}
     *       confirmation number, and persists nothing &mdash; still {@code 200 OK}.</li>
     *   <li>When the request is <strong>confirmed</strong> ({@code confirm} is {@code true}), the
     *       service writes the bill-payment transaction, drives the balance to zero, and returns the
     *       {@code paymentAmount}, {@code newBalance}, and generated {@code confirmationNumber} &mdash;
     *       {@code 200 OK}.</li>
     * </ul>
     *
     * <p>No exception is caught here (AAP requirement). A blank or non-numeric path account id fails
     * the parameter constraints and becomes {@code 400}; a path/body account-id contradiction is
     * rejected as {@code 400}; a "nothing to pay" balance ({@code <= 0}) raised by the service becomes
     * {@code 400}; and a missing account (or its card cross-reference) becomes {@code 404}. All
     * mapping is centralized in {@link GlobalExceptionHandler}.</p>
     *
     * @param accountId the account identifier to pay, from the URL path (legacy {@code ACTIDIN},
     *                  {@code PIC X(11)}); required, at most 11 characters, digits only
     * @param request   the optional bill-payment body; only its {@code confirm} flag is consumed, and
     *                  an account id in the body, if present, must equal {@code accountId}; may be
     *                  {@code null} (selects the preview path)
     * @return {@code 200 OK} with a {@link BillPaymentResponse} carrying either the confirmation
     *         prompt (unconfirmed) or the applied payment (confirmed)
     * @throws ResponseStatusException {@code 400 Bad Request} if the body supplies an account id that
     *                                 differs from the path account id
     */
    @PostMapping("/accounts/{accountId}/bill-payment")
    public ResponseEntity<BillPaymentResponse> payBill(
            @PathVariable @NotBlank @Size(max = 11) @Pattern(regexp = "\\d{1,11}") final String accountId,
            @RequestBody(required = false) final BillPaymentRequest request) {

        // The account id in the path is authoritative. If the optional body also carries an account
        // id and it disagrees with the path, that contradiction is a client error (the service cannot
        // detect it, since it receives only the normalized request). Reject it as 400; the shared
        // advice renders the standard ErrorResponse body.
        final String bodyAccountId = (request == null) ? null : request.accountId();
        if (bodyAccountId != null && !bodyAccountId.isBlank() && !bodyAccountId.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Account id in the URL and request body must match");
        }

        // Consume only the confirmation flag from the body; the account id always comes from the path.
        // A null flag (or an absent body) is the COBIL00C blank-confirm preview branch.
        final Boolean confirm = (request == null) ? null : request.confirm();
        final BillPaymentRequest normalizedRequest = new BillPaymentRequest(accountId, confirm);

        // Non-sensitive audit line: the account id (not a PAN) and whether payment was authorised.
        log.info("Bill payment request (CB00 / COBIL00C): accountId={}, confirmed={}",
                accountId, Boolean.TRUE.equals(confirm));

        // Delegate the entire COBIL00C PROCESS-ENTER-KEY flow to the service. Any ValidationException
        // (400) or ResourceNotFoundException (404) it raises propagates to GlobalExceptionHandler.
        final BillPaymentResponse response = billPaymentService.payBill(normalizedRequest);

        // Record the outcome. confirmationNumber is null on the preview path and the generated
        // transaction id on the committed path; neither is sensitive.
        log.info("Bill payment outcome (CB00 / COBIL00C): accountId={}, confirmationNumber={}",
                accountId, response.confirmationNumber());

        return ResponseEntity.ok(response);
    }
}
