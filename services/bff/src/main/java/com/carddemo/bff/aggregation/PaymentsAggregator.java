package com.carddemo.bff.aggregation;

import org.springframework.stereotype.Service;

import com.carddemo.bff.model.BillPaymentRequest;
import com.carddemo.bff.model.BillPaymentResponse;

/**
 * [DEFERRED] Bill Payment aggregator (typed stub) for the CardDemo walking skeleton.
 *
 * <p>Backs the BFF Bill Payment endpoint {@code POST /api/payments/bill}
 * (generated {@code PaymentsApi}, operation {@code payBill}). In this skeleton
 * the endpoint is a <strong>typed placeholder</strong>: it returns a well-formed
 * {@link BillPaymentResponse} without performing any real work. There is
 * <em>no</em> downstream service call, <em>no</em> persistence, <em>no</em>
 * balance mutation, and <em>no</em> business logic &mdash; a typed placeholder is
 * the correct outcome here, not a hallucinated implementation.</p>
 *
 * <p>Only the Card Detail path (UI -&gt; BFF -&gt; card-svc -&gt; Oracle FREEPDB1) is
 * the single live vertical tracer slice; every other aggregation, including this
 * one, is deliberately deferred. Because nothing is actually executed, the
 * returned {@link BillPaymentResponse.StatusEnum#PENDING PENDING} status is used
 * rather than {@link BillPaymentResponse.StatusEnum#CONFIRMED CONFIRMED}.</p>
 *
 * <p>As a BFF component this class performs aggregation only: it holds no
 * collaborators, opens no connections, and owns no domain state. The sibling
 * {@code web/} controller (which implements the generated {@code PaymentsApi})
 * delegates to {@link #payBill(BillPaymentRequest)} and wraps the result in
 * {@code ResponseEntity.ok(...)}.</p>
 *
 * <p>Provenance: [SRC: COBIL00C | ACCTDAT] &mdash; the legacy Bill Payment
 * transaction {@code COBIL00C} (paying an account balance in full and posting an
 * online payment transaction over the {@code ACCTDAT} account dataset). None of
 * that legacy behavior is ported in the skeleton; this stub only echoes the
 * request account id back in a typed placeholder shape.</p>
 */
@Service
public class PaymentsAggregator {

    /**
     * Produces a typed placeholder {@link BillPaymentResponse} for the deferred
     * Bill Payment feature.
     *
     * <p>No payment is executed: this method does not call any downstream service,
     * touch any database, or compute/mutate any balance. It simply echoes the
     * submitted account id into a well-formed response and reports the payment as
     * {@link BillPaymentResponse.StatusEnum#PENDING PENDING} (nothing was actually
     * processed). Consequently:</p>
     * <ul>
     *   <li>{@code accountId} &mdash; echoed from {@link BillPaymentRequest#getAccountId()};</li>
     *   <li>{@code newBalance} &mdash; the placeholder decimal string {@code "0.00"}
     *       (no real balance was computed);</li>
     *   <li>{@code transactionId} &mdash; the empty string {@code ""} (no payment
     *       transaction was posted);</li>
     *   <li>{@code status} &mdash; {@link BillPaymentResponse.StatusEnum#PENDING PENDING}.</li>
     * </ul>
     *
     * @param request the bill-payment instruction from the placeholder Bill Payment
     *                screen; its {@code accountId} is echoed into the response
     * @return a typed placeholder {@link BillPaymentResponse} with {@code PENDING}
     *         status; never {@code null}
     */
    public BillPaymentResponse payBill(BillPaymentRequest request) {
        BillPaymentResponse response = new BillPaymentResponse();
        response.setAccountId(request.getAccountId());
        response.setNewBalance("0.00");
        response.setTransactionId("");
        response.setStatus(BillPaymentResponse.StatusEnum.PENDING);
        return response;
    }
}
