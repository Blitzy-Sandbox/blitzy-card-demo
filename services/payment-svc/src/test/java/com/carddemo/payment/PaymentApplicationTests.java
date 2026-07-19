package com.carddemo.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * CardDemo Payment Service &mdash; context-load / health smoke test.
 *
 * <p>Provenance: [SRC: COBIL00C | ACCTDAT] &mdash; the legacy CICS Bill Payment
 * transaction {@code CB00} &rarr; program {@code COBIL00C} operating over the
 * {@code ACCTDAT} VSAM KSDS. {@code payment-svc} is the modern Bill Payment
 * bounded context of the CardDemo walking skeleton (F-SKEL).</p>
 *
 * <p>Verifies that the full {@code payment-svc} Spring context loads green
 * OFFLINE (with NO live Oracle database) by excluding the datasource, JPA,
 * datasource-transaction-manager, and Flyway auto-configurations for the test.
 * This is SAFE because {@code payment-svc} declares ZERO {@code @Entity} classes
 * and ZERO Spring Data repositories, so nothing in the context depends on those
 * beans; the production {@code application.yml} keeps the real Oracle datasource
 * for runtime. payment-svc endpoints are {@code [DEFERRED]} typed stubs, so this
 * test asserts context assembly only &mdash; not business behavior, persistence,
 * or endpoints.</p>
 */
@SpringBootTest(properties =
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
class PaymentApplicationTests {

    @Test
    void contextLoads() {
        // Intentionally empty: passing means the payment-svc context assembled and booted offline.
    }
}
