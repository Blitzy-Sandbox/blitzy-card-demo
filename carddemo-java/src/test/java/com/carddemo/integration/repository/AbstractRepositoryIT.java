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
 * <p>The container is declared with the relocated, non-deprecated
 * {@link org.testcontainers.postgresql.PostgreSQLContainer} (Testcontainers 2.x), which is a
 * concrete, non-parameterized class; it is therefore referenced without type arguments, keeping
 * the slice free of both deprecation and raw-type lint warnings under {@code -Xlint:all -Werror}.</p>
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
