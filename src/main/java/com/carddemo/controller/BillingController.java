package com.carddemo.controller;

import com.carddemo.dto.BillingDto;
import com.carddemo.service.BillingService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stateless REST surface for online bill payment, rooted at {@code /api/billing}.
 *
 * <p>This controller is the HTTP entry point for the CICS pseudo-conversational
 * bill-payment program {@code COBIL00C} (transaction {@code CB00}, mapset
 * {@code app/bms/COBIL00.bms}) translated at source commit {@code 27d6c6f}. The
 * single endpoint {@code POST /api/billing/pay} delegates to
 * {@link BillingService}, which pays the account balance in full &mdash; writing
 * one payment transaction and decrementing the balance to zero as a single
 * atomic unit of work &mdash; and echoes the updated balance and confirmation
 * flag in the response.</p>
 *
 * <p>The controller is a thin HTTP adapter: it holds no business logic and
 * performs no data access. The request body is checked declaratively with
 * {@link Valid}; the validation, not-found, business-rule, and concurrency
 * failures raised by the service are translated to HTTP status codes by the
 * centralized {@code GlobalExceptionHandler}. All routes require an authenticated
 * caller, enforced by the application security configuration.</p>
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final BillingService billingService;

    /**
     * Creates the controller with its single collaborating service.
     *
     * @param billingService the service that validates the request, loads the
     *                        account, and posts the full-balance payment; never
     *                        {@code null}
     */
    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    /**
     * Pays the supplied account's balance in full ({@code COBIL00C} /
     * {@code CB00}).
     *
     * <p>Delegates to {@link BillingService#payBill(BillingDto.PayRequest)},
     * which validates the account identifier and {@code Y}/{@code N} confirmation
     * flag, loads the account, guards against a non-positive balance, and
     * &mdash; on confirmation &mdash; writes the payment transaction and
     * decrements the balance atomically.</p>
     *
     * @param request the validated bill-payment request carrying the account
     *                identifier and the {@code Y}/{@code N} confirmation flag
     * @return {@code 200 OK} with the payment outcome echoing the account
     *         identifier, the updated balance, and the confirmation flag
     */
    @PostMapping("/pay")
    public ResponseEntity<BillingDto.PayResponse> payBill(
            @Valid @RequestBody BillingDto.PayRequest request) {
        return ResponseEntity.ok(billingService.payBill(request));
    }
}
