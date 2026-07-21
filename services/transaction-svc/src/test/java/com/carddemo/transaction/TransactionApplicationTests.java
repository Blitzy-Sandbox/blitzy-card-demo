package com.carddemo.transaction;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * CardDemo Transaction Service &mdash; context-load / health smoke test.
 *
 * <p>Provenance: {@code [SRC: COTRN00C/COTRN01C/COTRN02C | TRANSACT]} &mdash; the legacy
 * CICS Transaction List ({@code CT00 -> COTRN00C}, "List Transactions from TRANSACT file"),
 * Transaction View ({@code CT01 -> COTRN01C}, "View a Transaction from TRANSACT file") and
 * Transaction Add ({@code CT02 -> COTRN02C}, "Add a new Transaction to TRANSACT file")
 * programs over the {@code TRANSACT} VSAM KSDS registered in {@code app/csd/CARDDEMO.CSD};
 * record layout {@code app/cpy/CVTRA05Y.cpy}. The legacy reference is topology/provenance
 * only and does not dictate this test.</p>
 *
 * <p>This is the single test class of the {@code transaction-svc} bounded context of the
 * CardDemo walking skeleton (Spring Boot 3.5.16 / Java 21). It backs the
 * <strong>build-and-start-green</strong> requirement by proving that the full Spring
 * application context <strong>assembles and boots green OFFLINE</strong> &mdash; with NO live
 * Oracle database present &mdash; keeping {@code mvn -f services/transaction-svc/pom.xml test}
 * green in the offline compile/test / image-build phase, with the datasource / JPA /
 * Flyway auto-configurations excluded for the test (see the {@code properties} attribute
 * below).</p>
 *
 * <p>{@code transaction-svc} endpoints ({@code GET /transactions},
 * {@code GET /transactions/{transactionId}}, {@code POST /transactions}) are all
 * {@code [DEFERRED]} typed stubs returning typed placeholder DTOs, with NO live persistence
 * and NO business logic. Accordingly this test asserts <strong>context assembly only</strong>
 * &mdash; that the hand-written {@code web/TransactionController} (implements the generated
 * {@code com.carddemo.transaction.api.TransactionsApi}) and the {@code config} seam wire
 * together with the OpenAPI-generated interfaces/models &mdash; and deliberately makes NO
 * assertion about business behavior, persistence, endpoints or health.</p>
 */
@SpringBootTest(properties =
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
class TransactionApplicationTests {

    // Why the four excludes: spring-boot-starter-data-jpa + ojdbc11 + flyway are on the
    // classpath and the production application.yml wires a real Oracle datasource, so
    // DataSourceAutoConfiguration would still build a DataSource/HikariDataSource bean.
    // Excluding these four auto-configs (DataSource, Hibernate JPA, DataSource transaction
    // manager, Flyway) yields a context with no datasource, no EntityManagerFactory, no
    // datasource transaction manager and no Flyway, so it boots cleanly with no Oracle
    // reachable. This is safe because transaction-svc declares zero @Entity classes and
    // zero Spring Data repositories (it is a pure typed [DEFERRED] stub). The exclusion is
    // a test-only measure that lives here (the transaction-svc / account-svc convention);
    // the main application and its production application.yml keep the real datasource.

    @Test
    void contextLoads() {
        // Intentionally empty: passing means the transaction-svc context assembled and booted offline.
    }
}
