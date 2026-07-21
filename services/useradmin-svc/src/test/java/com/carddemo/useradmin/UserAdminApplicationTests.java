package com.carddemo.useradmin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * CardDemo User Admin Service &mdash; context-load / health smoke test.
 *
 * <p>The single, terminal test class in the entire {@code useradmin-svc} test source tree. It backs
 * the <em>build-and-start-green</em> requirement for {@code useradmin-svc}, a request-serving,
 * health-gated Spring Boot 3.5.16 / Java 21 microservice of the CardDemo walking skeleton (a
 * clean-room, modernized topology of the legacy AWS CardDemo mainframe application). The test
 * verifies ONLY that the seams hold together &mdash; that the whole Spring {@code ApplicationContext}
 * assembles and loads &mdash; NOT behavioral parity with the mainframe. {@code useradmin-svc} is a
 * pure typed stub: all user-administration behavior is {@code [DEFERRED]}.</p>
 *
 * <p>Booting the context with a plain {@link SpringBootTest} exercises the wiring of the
 * hand-written {@code com.carddemo.useradmin.web} controllers ({@code UserController} implementing
 * the generated {@code UsersApi}; {@code AdminController} implementing the generated {@code AdminApi}),
 * the cross-cutting {@code com.carddemo.useradmin.config} beans ({@code CorrelationIdFilter} and
 * {@code OpenApiConfig}), and the OpenAPI-generated {@code com.carddemo.useradmin.api} interfaces /
 * {@code com.carddemo.useradmin.model} DTOs (emitted under {@code target/generated-sources/openapi}
 * at build time from the frozen contract {@code contracts/useradmin-svc.openapi.yaml}). Because this
 * class shares the base package {@code com.carddemo.useradmin} with the
 * {@code @SpringBootApplication} entrypoint ({@code UserAdminApplication}), {@link SpringBootTest}
 * auto-discovers that primary {@code @SpringBootConfiguration} by default &mdash; so no explicit
 * configuration-class or component-scan override is declared here, which is intentional and required.</p>
 *
 * <p><strong>Runs fully OFFLINE &mdash; with NO live Oracle database present.</strong> The offline
 * CI compile/test phase and the Docker image build have no database, so this test must pass without
 * one. Although the sibling {@code pom.xml} places {@code spring-boot-starter-data-jpa}, the Oracle
 * {@code ojdbc11} driver, and Flyway on the classpath, {@code useradmin-svc} declares NO
 * {@code @Entity} and NO Spring Data repository (Decision E) &mdash; nothing in the context actually
 * uses a {@code DataSource}. Offline-green is therefore achieved purely by excluding the datasource,
 * JPA, datasource-transaction-manager, and Flyway auto-configurations (never by an embedded or
 * alternate database), so the context builds with no {@code DataSource}, no
 * {@code EntityManagerFactory}, no datasource transaction manager, and no Flyway migration, while the
 * module stays faithful to the real Oracle stack at runtime. Both DB-independence mechanisms are
 * applied belt-and-suspenders: the inline {@code spring.autoconfigure.exclude} property below and the
 * {@code @ActiveProfiles("test")} binding to the sibling {@code src/test/resources/application-test.yml}
 * (which excludes the same four auto-configurations, in the same order, and disables Flyway).</p>
 *
 * <p>No web-layer, datasource, or {@code /actuator/health} assertion is made: a live datasource
 * health contributor would report DOWN offline, and Spring Boot Actuator (not the generated health
 * interface) owns {@code /actuator/health}, which is intentionally left unimplemented. The
 * {@code webEnvironment} is left at the default {@code MOCK} (no port is bound).</p>
 *
 * <p>Provenance (topology / lineage only &mdash; no assertion is derived from it):
 * {@code [SRC: COUSR00C-03C, COADM01C | USRSEC]} &mdash; the legacy CICS User-Administration
 * transactions ({@code CU00 -> COUSR00C} user list, {@code CU01 -> COUSR01C} user add,
 * {@code CU02 -> COUSR02C} user update, {@code CU03 -> COUSR03C} user delete) and the Admin-Menu
 * transaction ({@code CA00 -> COADM01C}), all registered over the {@code USRSEC} VSAM KSDS in
 * {@code app/csd/CARDDEMO.CSD}. {@code useradmin-svc} is the modern user-administration bounded
 * context; the legacy references are provenance/topology only.</p>
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude="
    + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
    + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
    + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
    + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
@ActiveProfiles("test")
class UserAdminApplicationTests {

    /**
     * Verifies the {@code useradmin-svc} Spring application context assembles and boots offline.
     *
     * <p>The assertion is implicit: if any bean fails to wire or the context fails to start
     * (including any stray attempt to open a database connection), {@link SpringBootTest} throws
     * and this test fails. The datasource/JPA/transaction-manager/Flyway auto-configurations are
     * excluded, so no database connection is attempted.</p>
     */
    @Test
    void contextLoads() {
        // Intentionally empty: passing means the useradmin-svc context assembled and booted offline.
    }
}
