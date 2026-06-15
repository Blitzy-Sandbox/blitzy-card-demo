package com.cardemo.controller;

import com.cardemo.model.dto.BillPaymentRequest;
import com.cardemo.service.billing.BillPaymentService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST replacement for the AWS CardDemo CICS BMS 3270 <strong>Bill Payment</strong> screen. It
 * exposes a single route, <strong>{@code POST /api/billing/pay}</strong>, and is a thin adapter over
 * {@link BillPaymentService}.
 *
 * <p>This controller is the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x realization of
 * AAP&nbsp;&sect;0.4.1 (tech-spec&nbsp;L650: <em>{@code controller/BillingController.java} CREATE &larr;
 * {@code app/bms/COBIL00.bms} &mdash; "POST /api/billing/pay"</em>) and of AAP&nbsp;&sect;0.3.4
 * (BMS&nbsp;&rarr;&nbsp;REST contract translation). It preserves feature <strong>F-009</strong> (Bill
 * Payment) without expansion (Minimal Change Clause, AAP&nbsp;&sect;0.7.1): exactly one endpoint, no
 * business logic, no data access.</p>
 *
 * <h2>Authoritative source artifacts (read-only reference, never copied)</h2>
 * <ul>
 *   <li><strong>{@code app/bms/COBIL00.bms}</strong> &mdash; the Bill Payment mapset
 *       ({@code COBIL00} / map {@code COBIL0A}), driven by CICS program {@code COBIL00C}, transaction
 *       <strong>{@code CB00}</strong>. The 3270 screen accepted an account id ({@code ACTIDIN
 *       PIC X(11)}), displayed the account's current balance ({@code CURBAL}) and asked the operator
 *       to confirm payment with a single {@code (Y/N)} keystroke ({@code CONFIRM PIC X(1)}).</li>
 * </ul>
 * <p>Its symbolic map ({@code app/cpy-bms/COBIL00.CPY}) was migrated into {@link BillPaymentRequest}
 * (the {@code COPY CSSETATY} field contract) and its nested
 * {@link BillPaymentRequest.Response Response}. This controller never re-declares those structures and
 * never copies COBOL/BMS text &mdash; only the screen <em>behavior</em> is reproduced, by delegation
 * to {@link BillPaymentService}.</p>
 *
 * <h2>Key insight &mdash; full-balance dual write owned by the service</h2>
 * <p>Bill payment always clears the <strong>full</strong> current balance in a single unit of work:
 * {@code COBIL00C} moved {@code ACCT-CURR-BAL} into {@code TRAN-AMT}, wrote a payment transaction, then
 * decremented the balance to zero. That account-id validation, the confirm / preview / cancel
 * branching, the browse-to-end auto-generated transaction id and the {@code @Transactional} dual write
 * (create transaction + update balance &mdash; AAP&nbsp;&sect;0.6.4) all live in
 * {@link BillPaymentService}. This controller adds <strong>zero</strong> logic; the single handler is a
 * pure delegation returning {@code 200 OK} with the service's
 * {@link BillPaymentRequest.Response Response} (AAP&nbsp;&sect;0.3.3).</p>
 *
 * <h2>COBOL &rarr; REST substitutions (documented per the Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>{@code RECEIVE MAP('COBIL0A')} / {@code SEND MAP('COBIL0A')} &rarr; request/response
 *       DTO.</strong> The 3270 field harvest and screen paint collapse into Jackson (de)serialization
 *       of {@link BillPaymentRequest} and {@link BillPaymentRequest.Response Response}. Screen chrome,
 *       the {@code CURBAL} display field, the {@code ERRMSG} line and BMS control bytes have no REST
 *       analogue and are not modeled as request fields (AAP&nbsp;&sect;0.4.2).</li>
 *   <li><strong>{@code RETURN TRANSID('CB00') COMMAREA} &rarr; stateless REST.</strong> The CICS
 *       pseudo-conversational hand-off (the {@code COCOM01Y} COMMAREA carrying state across turns) is
 *       replaced by stateless HTTP; this controller holds no conversational state
 *       (AAP&nbsp;&sect;0.1.2). The COBOL multi-turn confirm screen collapses into the single
 *       {@code confirm} field on the request: {@code Y}=pay-the-full-balance, {@code N}=cancel,
 *       blank=preview &mdash; all interpreted by the service, never here.</li>
 *   <li><strong>AID keys &rarr; distinct client-driven REST calls.</strong> The Bill Payment screen's
 *       {@code ENTER} (submit), {@code PF3} (back) and {@code PF4} (clear) are client-navigation
 *       concerns: the client simply issues {@code POST /api/billing/pay} (or navigates away). They are
 *       not endpoints on this controller (AAP&nbsp;&sect;0.1.2).</li>
 * </ul>
 *
 * <h2>Validation parity &mdash; delegated to the service (AAP &sect;0.7.2)</h2>
 * <p>{@link BillPaymentService} is the authoritative source for the validation <em>order</em> and the
 * verbatim COBOL message text ({@code COBIL00C} emits specific ordered messages such as
 * "Acct ID can NOT be empty...", "Invalid value. Valid values are (Y/N)..." and "You have nothing to
 * pay..."). The request body is therefore bound as a plain {@code @RequestBody}
 * <strong>without</strong> {@code @Valid}. {@link BillPaymentRequest#getAccountId() accountId} carries
 * {@code @NotBlank}/{@code @Pattern} constraints; if {@code @Valid} were applied here a blank or
 * non-numeric account id would be short-circuited at the boundary with a generic
 * {@code MethodArgumentNotValidException} (HTTP&nbsp;400) <em>instead</em> of the verbatim COBOL
 * "Acct ID can NOT be empty..." message that only the service produces. Omitting {@code @Valid} ensures
 * every request reaches the service, which re-checks defensively, so message parity holds exactly
 * (consistent with the other CardDemo controllers, e.g. {@code AccountController}). The
 * {@link BillPaymentRequest} Jakarta constraints remain the documented field contract (the
 * {@code COPY CSSETATY} translation), but the service is the runtime source of truth.</p>
 *
 * <h2>Error handling &mdash; centralized advice, exceptions propagate</h2>
 * <p>This controller defines <strong>no</strong> {@code @ExceptionHandler} /
 * {@code @RestControllerAdvice} and catches no domain exception. The service throws and this controller
 * lets propagate the typed exceptions translated by the centralized {@code @RestControllerAdvice} in
 * {@code config/WebConfig}:</p>
 * <ul>
 *   <li>{@code com.cardemo.exception.ValidationException} &rarr; HTTP&nbsp;<strong>400 Bad
 *       Request</strong> (blank account id, invalid confirm value, or "nothing to pay" &mdash; the
 *       verbatim COBOL edit messages).</li>
 *   <li>{@code com.cardemo.exception.RecordNotFoundException} &rarr; HTTP&nbsp;<strong>404 Not
 *       Found</strong> (the account or its card cross-reference is absent).</li>
 *   <li>{@code com.cardemo.exception.DuplicateRecordException} &rarr; HTTP&nbsp;<strong>409
 *       Conflict</strong> (the generated transaction id collides with an existing row).</li>
 * </ul>
 *
 * <h2>Decimal fidelity (AAP &sect;0.7.3)</h2>
 * <p>The balances on {@link BillPaymentRequest.Response Response} are {@link java.math.BigDecimal} of
 * scale 2 &mdash; <strong>never</strong> {@code float} or {@code double}. This controller passes the
 * service's response through untouched and performs no arithmetic, so decimal precision is preserved
 * end to end.</p>
 *
 * <h2>Security</h2>
 * <p>The {@code /api/billing/*} endpoint requires authentication; access is governed by the Spring
 * Security configuration in {@code config/SecurityConfig}. No security infrastructure and no
 * method-security annotations are introduced in this thin adapter.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL/BMS baseline at commit SHA
 * {@code 27d6c6f}. The COBOL and BMS sources are read-only reference material and are never copied into
 * this repository (AAP&nbsp;&sect;0.7.2).</p>
 *
 * @see BillPaymentService
 * @see BillPaymentRequest
 * @see BillPaymentRequest.Response
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    /**
     * Service owning the bill-payment business logic ({@code COBIL00C}): account-id validation, the
     * confirm / preview / cancel branching, the auto-generated transaction id and the
     * {@code @Transactional} dual write (create transaction + update balance).
     */
    private final BillPaymentService billPaymentService;

    /**
     * Constructs the controller with the bill-payment service injected by Spring (constructor
     * injection; the field is {@code final}; no field {@code @Autowired}).
     *
     * @param billPaymentService the transactional bill-payment service ({@code COBIL00C})
     */
    public BillingController(final BillPaymentService billPaymentService) {
        this.billPaymentService = billPaymentService;
    }

    /**
     * Processes a bill payment, reproducing the server-side core of {@code COBIL00C}.
     *
     * <p><strong>Endpoint:</strong> {@code POST /api/billing/pay}.</p>
     *
     * <p>The request body carries the account id and the single-character confirm flag. The flag drives
     * the behavior, all interpreted by {@link BillPaymentService#processBillPayment(BillPaymentRequest)}:
     * {@code Y}/{@code y} pays the full current balance (creates a payment transaction and sets the
     * balance to zero), {@code N}/{@code n} cancels (a no-op cleared response), and a blank/{@code null}
     * flag previews (returns the current balance and the "Confirm to make a bill payment..." prompt).
     * The body is bound as {@code @RequestBody} <strong>without</strong> {@code @Valid}: the service
     * owns the ordered verbatim COBOL edit messages, so applying bean validation here would pre-empt
     * those messages with a generic framework error and break parity (AAP&nbsp;&sect;0.7.2). The
     * pay path's transaction insert and balance update share one transaction
     * ({@code @Transactional} in the service, AAP&nbsp;&sect;0.6.4), so they commit together or roll
     * back together.</p>
     *
     * <p>HTTP&nbsp;<strong>200 OK</strong> (not 201) is returned for every outcome: this is a payment
     * <em>action</em> endpoint whose response is the payment confirmation or preview, and the same
     * endpoint serves preview, cancel and commit; no new addressable resource is created from the
     * client's perspective.</p>
     *
     * @param request the bill-payment request (account id + confirm flag) bound from the JSON body;
     *                never {@code null}
     * @return {@code 200 OK} carrying the {@link BillPaymentRequest.Response Response}: a cleared
     *         response for a cancel ({@code N}); the balance plus the confirm prompt for a preview
     *         (blank); or the pre-payment balance, generated transaction id, post-payment balance and
     *         success message for a confirmed payment
     * @throws com.cardemo.exception.ValidationException     if the account id is empty/non-numeric, the
     *         confirm flag is invalid, or the balance is not positive (rendered as HTTP&nbsp;400 by
     *         {@code config/WebConfig}); propagated, not caught
     * @throws com.cardemo.exception.RecordNotFoundException if the account or its card cross-reference
     *         does not exist (HTTP&nbsp;404); propagated, not caught
     * @throws com.cardemo.exception.DuplicateRecordException if the generated transaction id collides
     *         with an existing row (HTTP&nbsp;409); propagated, not caught
     */
    // COBOL substitution: COBIL00C RECEIVE MAP('COBIL0A') field harvest -> @RequestBody binding;
    // SEND MAP('COBIL0A') -> BillPaymentRequest.Response JSON; RETURN TRANSID('CB00') COMMAREA ->
    // stateless POST (no conversational state, AAP §0.1.2). The single confirm field collapses the
    // COBOL multi-turn confirm screen: Y=pay-full-balance, N=cancel, blank=preview -- all service-owned.
    // Bound WITHOUT @Valid so a blank/invalid account id reaches the service and surfaces the verbatim
    // "Acct ID can NOT be empty..." message rather than a generic MethodArgumentNotValidException
    // (parity, AAP §0.7.2; consistent with AccountController; the service re-checks defensively).
    // AID-key note: the screen's ENTER/PF3/PF4 map to distinct client-driven REST calls, not endpoints
    // here (AAP §0.1.2). Pure delegation: the dual write (transaction + balance) is one @Transactional
    // unit inside BillPaymentService (AAP §0.6.4); BigDecimal balances pass through untouched (§0.7.3).
    @PostMapping("/pay")
    public ResponseEntity<BillPaymentRequest.Response> pay(@RequestBody final BillPaymentRequest request) {
        return ResponseEntity.ok(billPaymentService.processBillPayment(request));
    }
}
