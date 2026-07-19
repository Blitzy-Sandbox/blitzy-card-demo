package com.carddemo.reporting;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * reporting-svc — context-load smoke test (build-green assertion).
 *
 * <p>ASYNC JOB STUB — HEALTH-EXEMPT. This test only verifies that the Spring
 * application context loads (the {@code jobs/*JobHandler} @Component beans and the
 * {@code config/*} beans wire correctly). It is NOT a health-serving or HTTP test:
 * reporting-svc is not held to start-and-serve, is not in the docker-compose
 * {@code service_healthy} chain, and publishes no host port.</p>
 *
 * <p>Runs OFFLINE with NO live Oracle: the datasource / JPA auto-configuration is
 * excluded so no database connection is attempted (there are no JPA entities or
 * repositories in this service). No mock bean is required.</p>
 *
 * <p>Provenance: [SRC: CORPT00C, CBSTM03A/B | TRANSACT].</p>
 */
@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
})
class ReportingApplicationTests {

    @Test
    void contextLoads() {
        // Intentionally empty: a failure to load the context fails this test.
    }
}
