package com.carddemo.payment.web;

import com.carddemo.payment.api.PaymentsApi;
import com.carddemo.payment.model.BalanceResponse;
import com.carddemo.payment.model.BillPaymentRequest;
import com.carddemo.payment.model.BillPaymentResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * CardDemo Payment Service &mdash; web/REST layer.
 *
 * <p>REST entry point for the {@code payment-svc} (Bill Payment) bounded context of the CardDemo
 * walking skeleton (F-SKEL). This controller implements the contract-first, OpenAPI-generated
 * {@link PaymentsApi} interface and returns <strong>typed placeholder</strong> responses only.</p>
 *
 * <p><strong>[DEFERRED]</strong> &mdash; both operations ({@code getBalance} and {@code payBill})
 * are typed stubs. There is deliberately NO live balance read, NO persistence, NO balance
 * mutation, and NO payment-transaction generation in this skeleton. For the walking-skeleton run,
 * an interface plus a typed placeholder is the correct outcome; a hallucinated (invented) real
 * implementation would be a failure.</p>
 *
 * <p>Legacy provenance: <strong>[SRC: COBIL00C | ACCTDAT]</strong> &mdash; the CICS Bill Payment
 * transaction {@code CB00} &rarr; program {@code COBIL00C} (mapset {@code COBIL00}, screen
 * {@code app/bms/COBIL00.bms}) operating over the {@code ACCTDAT} VSAM KSDS. The legacy program
 * reads the account to display the current balance ({@code CURBAL}) and, on confirmation
 * ({@code CONFIRM}), writes a payment transaction and rewrites the account balance to zero; all of
 * that behaviour is <strong>[DEFERRED]</strong> here. Field shapes derive from {@code ACCOUNT-RECORD}
 * (app/cpy/CVACT01Y.cpy): {@code ACCT-ID PIC 9(11)} and {@code ACCT-CURR-BAL PIC S9(10)V99}.</p>
 *
 * <p>Cross-cutting concerns are intentionally excluded from this class: the HTTP paths/verbs and
 * parameter bindings live on the generated {@link PaymentsApi} interface (the generator runs with
 * {@code interfaceOnly=true}); the {@code /actuator/health} probe is served by Spring Boot Actuator
 * (never by a controller here) so that the docker-compose {@code service_healthy} gate holds; and
 * correlation-ID propagation into the SLF4J MDC is owned by the sibling
 * {@code config.CorrelationIdFilter}. This controller therefore carries a bare {@link RestController}
 * annotation, declares no request mappings, injects no dependencies, and never touches the MDC.</p>
 */
@RestController
public class PaymentController implements PaymentsApi {

    /** Fixed placeholder account identifier (ACCT-ID, PIC 9(11)) used when no request value is present. */
    private static final String PLACEHOLDER_ACCOUNT_ID = "00000000001";

    /** Fixed placeholder monetary value (decimal string, mirroring ACCT-CURR-BAL PIC S9(10)V99). */
    private static final String PLACEHOLDER_AMOUNT = "0.00";

    /**
     * {@code GET /payments/balance/{accountId}} &mdash; retrieve the current balance for an account.
     *
     * <p><strong>[DEFERRED]</strong> typed stub. Returns a placeholder {@link BalanceResponse}; the
     * path {@code accountId} is echoed back for realism, but no account lookup is performed. Mirrors
     * the legacy {@code CURBAL} display step of {@code COBIL00C} without reading {@code ACCTDAT}.</p>
     *
     * @param accountId      11-digit account identifier from the request path (ACCT-ID, PIC 9(11))
     * @param xCorrelationID optional correlation identifier; accepted for contract fidelity and
     *                       ignored here (MDC propagation is owned by {@code CorrelationIdFilter})
     * @return {@code 200 OK} with a typed placeholder balance payload
     */
    @Override
    public ResponseEntity<BalanceResponse> getBalance(String accountId, UUID xCorrelationID) {
        // [DEFERRED] typed stub — placeholder balance; no ACCTDAT read (see COBIL00C READ-ACCTDAT-FILE).
        BalanceResponse placeholder = new BalanceResponse()
                .accountId(accountId)
                .currentBalance(PLACEHOLDER_AMOUNT);
        return ResponseEntity.ok(placeholder);
    }

    /**
     * {@code POST /payments/bill} &mdash; pay the outstanding balance for an account.
     *
     * <p><strong>[DEFERRED]</strong> typed stub. The request body is semantically ignored: nothing
     * is persisted, no payment transaction is generated, and no balance is mutated. The request
     * {@code accountId} is echoed back (null-safe) for realism. Mirrors the legacy confirm-and-pay
     * step of {@code COBIL00C} without any of its side effects.</p>
     *
     * <p><strong>Honest stub semantics.</strong> Because no work is actually performed, the
     * response reports {@link BillPaymentResponse.StatusEnum#PENDING PENDING} &mdash; <em>not</em>
     * {@code CONFIRMED} &mdash; and carries an empty {@code transactionId} ({@code ""}), since no
     * payment transaction was posted. This deliberately matches the BFF
     * {@code aggregation.PaymentsAggregator} so the deferred Bill Payment feature presents a single,
     * consistent, truthful placeholder shape across the topology. Reporting {@code CONFIRMED} with a
     * fabricated transaction id would falsely imply a completed payment and is therefore avoided.</p>
     *
     * @param billPaymentRequest the bill-payment instruction; echoed for realism, otherwise ignored
     * @param xCorrelationID     optional correlation identifier; accepted for contract fidelity and
     *                           ignored here (MDC propagation is owned by {@code CorrelationIdFilter})
     * @return {@code 200 OK} with a typed placeholder confirmation payload (status {@code PENDING})
     */
    @Override
    public ResponseEntity<BillPaymentResponse> payBill(BillPaymentRequest billPaymentRequest, UUID xCorrelationID) {
        // [DEFERRED] typed stub — nothing processed: no TRANSACT write, no balance rewrite (see COBIL00C
        // WRITE-TRANSACT-FILE / UPDATE-ACCTDAT-FILE). Report PENDING + empty transactionId (honest stub),
        // consistent with the BFF PaymentsAggregator; CONFIRMED + a synthetic id would falsely imply a
        // completed payment.
        String accountId = (billPaymentRequest != null && billPaymentRequest.getAccountId() != null)
                ? billPaymentRequest.getAccountId()
                : PLACEHOLDER_ACCOUNT_ID;
        BillPaymentResponse placeholder = new BillPaymentResponse()
                .accountId(accountId)
                .previousBalance(PLACEHOLDER_AMOUNT)
                .paymentAmount(PLACEHOLDER_AMOUNT)
                .newBalance(PLACEHOLDER_AMOUNT)
                .transactionId("")
                .status(BillPaymentResponse.StatusEnum.PENDING);
        return ResponseEntity.ok(placeholder);
    }
}
