package com.carddemo.integration.repository;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared base for all Spring Data JPA repository integration tests.
 *
 * <p>Boots a real PostgreSQL 16 instance via Testcontainers (no mocks) so the
 * VSAM-to-PostgreSQL re-platforming can be verified against an authentic relational
 * engine. Flyway migrations V1 (schema) then V2 (indexes) then V3 (seed) run automatically
 * on the fresh container before Hibernate schema validation, seeding the nine ASCII
 * reference fixtures.</p>
 *
 * <p>A single container is started once in a static initializer and reused across every
 * subclass, so the Spring context (and the one-time Flyway provisioning) is cached for the
 * whole suite. {@code @DataJpaTest} wraps each test method in a transaction that rolls back
 * on completion, so the committed seed data survives while per-test mutations are discarded.</p>
 *
 * <p>The container field uses the non-generic Testcontainers 2.x
 * {@code org.testcontainers.postgresql.PostgreSQLContainer} (the superclass
 * {@code JdbcDatabaseContainer} carries the self-type parameter, not this class), so no type
 * argument is written: this is a reference to a non-generic class, not a raw type, and keeps the
 * {@code -Xlint:all -Werror} build warning-free.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
abstract class AbstractRepositoryIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
}
