package com.carddemo.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * payment-svc &mdash; CardDemo Payment Service, the typed-stub Bill Payment bounded
 * context of the walking skeleton (F-SKEL).
 *
 * <p>TYPED-STUB, REQUEST-SERVING service. Every payment operation is a typed stub
 * ([DEFERRED]) that returns a typed placeholder DTO: there is NO live payment logic,
 * NO Oracle read, NO persistence, NO balance mutation, and NO transaction generation.
 * This entrypoint encodes NO behavior &mdash; it only bootstraps the Spring context.
 * The typed-stub controller lives in {@code web/PaymentController}, the correlation
 * hop in {@code config/CorrelationIdFilter}, and the OpenAPI metadata bean in
 * {@code config/OpenApiConfig}.</p>
 *
 * <p>This class is the Spring component-scan root: it lives at the base package
 * {@code com.carddemo.payment} so the plain {@code @SpringBootApplication} default
 * component scan automatically covers the sibling {@code web} and {@code config}
 * subpackages, plus the OpenAPI-generated {@code api} / {@code model} packages emitted
 * under {@code target/generated-sources/openapi} at build time. Therefore no
 * {@code scanBasePackages} / {@code @ComponentScan} is needed or permitted.</p>
 *
 * <p>Provenance: [SRC: COBIL00C | ACCTDAT] &mdash; app/csd/CARDDEMO.CSD
 * (transaction CB00 -&gt; program COBIL00C "Bill Payment - Pay account balance in full"
 * over file ACCTDAT).</p>
 */
@SpringBootApplication
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
