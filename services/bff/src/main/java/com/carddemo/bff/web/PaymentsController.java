package com.carddemo.bff.web;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.carddemo.bff.aggregation.PaymentsAggregator;
import com.carddemo.bff.api.PaymentsApi;
import com.carddemo.bff.model.BillPaymentRequest;
import com.carddemo.bff.model.BillPaymentResponse;

/**
 * CardDemo BFF &mdash; Bill Payment web/REST layer (typed {@code [DEFERRED]} stub).
 *
 * <p>HTTP edge for the Bill Payment screen of the CardDemo walking skeleton (Spring Boot 3.5.16 /
 * Java 21). This is the single hand-written class in the {@code com.carddemo.bff.web} package for
 * the Payments surface; it {@code implements} the contract-first, OpenAPI-generated
 * {@link PaymentsApi} interface (emitted under {@code target/generated-sources/openapi} from the
 * frozen single-source-of-truth contract {@code contracts/bff.openapi.yaml}, vendored into this
 * module at {@code src/main/resources/openapi/bff.openapi.yaml}).</p>
 *
 * <p><strong>{@code [DEFERRED]}.</strong> The single operation {@code payBill}
 * ({@code POST /api/payments/bill}) is a typed placeholder. This controller performs
 * <em>no</em> domain logic, <em>no</em> persistence, and <em>no</em> balance mutation of any kind;
 * it delegates to {@link PaymentsAggregator}, which returns a well-formed
 * {@link BillPaymentResponse} with {@link BillPaymentResponse.StatusEnum#PENDING PENDING} status.
 * For the walking-skeleton run an interface plus a typed placeholder is the correct outcome; a
 * hallucinated (invented) real implementation would be a failure. Only the Card Detail path
 * (UI &rarr; BFF &rarr; card-svc &rarr; Oracle FREEPDB1) is the single live vertical tracer slice.</p>
 *
 * <p>As the BFF is the only backend the React SPA binds to, this class is a <strong>thin HTTP
 * layer</strong> only: it maps the generated interface method to the aggregator and wraps the
 * result in {@code ResponseEntity.ok(...)}. It opens no connections and makes no downstream service
 * or database call itself &mdash; any (deferred) aggregation is owned by {@link PaymentsAggregator}.</p>
 *
 * <p>Cross-cutting concerns are intentionally excluded from this class: the HTTP path/verb and
 * parameter bindings live on the generated {@link PaymentsApi} interface (the generator runs with
 * {@code interfaceOnly=true}), so this controller declares <em>no</em> request-mapping annotations
 * and serves the interface-declared {@code /api/payments/bill} verbatim; the
 * {@code /actuator/health} probe is served by Spring Boot Actuator (never by a controller here) so
 * the docker-compose {@code service_healthy} gate holds, and the generated {@code HealthApi} is
 * deliberately left unimplemented; and correlation-ID propagation into the SLF4J MDC is owned by
 * the sibling {@code config.CorrelationIdFilter}, so the {@code X-Correlation-ID} header is
 * accepted for contract fidelity and ignored here.</p>
 *
 * <p>Provenance: <strong>{@code [SRC: COBIL00C | ACCTDAT]}</strong> &mdash; the legacy CICS Bill
 * Payment transaction {@code CB00} &rarr; program {@code COBIL00C} (mapset {@code COBIL00}), which
 * pays an account balance in full and posts an online payment transaction over the {@code ACCTDAT}
 * account dataset [app/csd/CARDDEMO.CSD]. None of that legacy behaviour is ported in the skeleton.</p>
 */
@RestController
public class PaymentsController implements PaymentsApi {

    /**
     * Aggregation collaborator that produces the typed placeholder bill-payment response. Injected
     * by constructor (Spring resolves the single candidate {@code @Service} bean); the field is
     * {@code final} and never reassigned.
     */
    private final PaymentsAggregator paymentsAggregator;

    /**
     * Creates the controller with its aggregation collaborator.
     *
     * <p>Constructor injection is used deliberately (no {@code @Autowired}, no field/setter
     * injection): with a single constructor Spring performs autowiring implicitly, the dependency
     * is mandatory and immutable, and the controller stays trivially unit-testable.</p>
     *
     * @param paymentsAggregator the bill-payment aggregator that returns the typed placeholder
     *                           response; must not be {@code null}
     */
    public PaymentsController(PaymentsAggregator paymentsAggregator) {
        this.paymentsAggregator = paymentsAggregator;
    }

    /**
     * {@code POST /api/payments/bill} &mdash; submit a bill payment ({@code [DEFERRED]} typed stub).
     *
     * <p>Delegates to {@link PaymentsAggregator#payBill(BillPaymentRequest)} and wraps the resulting
     * placeholder {@link BillPaymentResponse} (status {@code PENDING}) in a {@code 200 OK}. No
     * balance is read or mutated, no payment transaction is posted, and no downstream service or
     * database is contacted &mdash; mirroring the legacy {@code COBIL00C} confirm-and-pay flow
     * without any of its side effects.</p>
     *
     * @param billPaymentRequest the bill-payment instruction submitted from the placeholder Bill
     *                           Payment screen; forwarded to the aggregator (request-body validation
     *                           is enforced by the generated interface before this method is reached)
     * @param xCorrelationID     optional correlation identifier; accepted for contract fidelity and
     *                           intentionally ignored here (MDC propagation is owned by
     *                           {@code config.CorrelationIdFilter})
     * @return {@code 200 OK} with the typed placeholder {@link BillPaymentResponse}
     */
    @Override
    public ResponseEntity<BillPaymentResponse> payBill(BillPaymentRequest billPaymentRequest, UUID xCorrelationID) {
        return ResponseEntity.ok(paymentsAggregator.payBill(billPaymentRequest));
    }
}
