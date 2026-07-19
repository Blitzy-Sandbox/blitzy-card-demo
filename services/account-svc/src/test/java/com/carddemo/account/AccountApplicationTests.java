package com.carddemo.account;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * CardDemo Account Service — context-load / health smoke test.
 *
 * <p>This is the one and only test class of {@code account-svc}, the request-serving,
 * health-gated Spring Boot 3.5.16 / Java 21 microservice for the <strong>account</strong>
 * bounded context of the CardDemo walking skeleton. It is a JUnit 5 (Jupiter)
 * context-load smoke test that backs the build-and-start-green requirement: it proves that
 * the whole {@code account-svc} Spring application context assembles and boots into a valid
 * context — that the seams hold together — rather than asserting behavioral parity with the
 * legacy mainframe. Booting the context exercises the wiring of the hand-written
 * {@code com.carddemo.account.web.AccountController} (which {@code implements} the
 * OpenAPI-generated {@code com.carddemo.account.api.AccountsApi}), the
 * {@code com.carddemo.account.config.CorrelationIdFilter} and
 * {@code com.carddemo.account.config.OpenApiConfig} beans, and the generated
 * {@code com.carddemo.account.api} interfaces / {@code com.carddemo.account.model} DTOs
 * (emitted under {@code target/generated-sources/openapi} at build time from the frozen
 * contract {@code contracts/account-svc.openapi.yaml}).</p>
 *
 * <p><strong>Runs fully OFFLINE — with NO live Oracle database.</strong> The offline
 * compile/test phase and the Docker image build have no database, so this test must pass
 * without one. Although the production {@code src/main/resources/application.yml} wires a
 * real Oracle datasource (and {@code spring-boot-starter-data-jpa} is on the classpath),
 * {@code DataSourceAutoConfiguration} would still create a {@code DataSource}/HikariCP bean
 * that cannot connect offline. The {@code @SpringBootTest(properties = ...)} attribute below
 * therefore excludes the datasource, JPA, datasource-transaction-manager, and Flyway
 * auto-configurations for this test only, so the context is built with no datasource, no JPA
 * {@code EntityManagerFactory}, no datasource transaction manager, and no Flyway. This is
 * safe because {@code account-svc} declares NO {@code @Entity} and NO Spring Data repository
 * — it is a typed stub, so nothing in the context depends on those beans. The exclusion is a
 * test-only measure that lives here (the account-svc convention); the production
 * {@code AccountApplication} deliberately keeps the real datasource intact at runtime.</p>
 *
 * <p><strong>[DEFERRED] typed stubs.</strong> Every {@code account-svc} endpoint
 * ({@code GET /accounts/{accountId}}, {@code PUT /accounts/{accountId}}) returns a typed
 * placeholder DTO with no live persistence and no business logic. Returning a typed
 * placeholder is the correct outcome; a hallucinated real implementation would be a failure.
 * Accordingly this test asserts context assembly ONLY — it does not test business behavior,
 * persistence, endpoints, or health.</p>
 *
 * <p>Provenance: [SRC: COACTVWC/COACTUPC | ACCTDAT] — app/csd/CARDDEMO.CSD (legacy CICS
 * Account View transaction {@code CAVW -> program COACTVWC} "Accept and process Account View
 * request" and Account Update transaction {@code CAUP -> program COACTUPC} "Accept and
 * process ACCOUNT UPDATE"), both operating over the {@code ACCTDAT} VSAM KSDS
 * (DSNAME {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}; record layout app/cpy/CVACT01Y.cpy,
 * {@code ACCOUNT-RECORD} / {@code ACCT-ID PIC 9(11)}). account-svc is the modern account
 * bounded context; the legacy references are provenance/topology only.</p>
 */
@SpringBootTest(properties =
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
class AccountApplicationTests {

    /**
     * Verifies the {@code account-svc} Spring context assembles and boots offline.
     *
     * <p>The assertion is implicit: if the context fails to load, {@code @SpringBootTest}
     * fails this test. No datasource/JPA/Flyway is present (excluded above), so no database
     * connection is attempted.</p>
     */
    @Test
    void contextLoads() {
        // Intentionally empty: passing means the account-svc context assembled and booted offline.
    }
}
