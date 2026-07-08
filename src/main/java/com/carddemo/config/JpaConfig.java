package com.carddemo.config;

import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import jakarta.annotation.PostConstruct;

/**
 * JPA assembly configuration for the CardDemo migration.
 *
 * <p>This is a deliberately thin {@code @Configuration}. Spring Boot auto-configuration
 * ({@code DataSourceAutoConfiguration}, {@code HibernateJpaAutoConfiguration},
 * {@code JpaBaseConfiguration}, {@code TransactionAutoConfiguration}) already provides the
 * {@code DataSource}, {@code EntityManagerFactory}, and {@code JpaTransactionManager}, and
 * already enables transaction management; none of those beans are redefined here. Repository
 * ({@code com.carddemo.repository.*}) and entity ({@code com.carddemo.entity.*}) scanning is
 * inherited from the root {@code @SpringBootApplication}; this class intentionally does not
 * declare explicit JPA repository or entity-scan base packages, which would narrow that scan
 * and hide sibling packages.</p>
 *
 * <p>Flyway owns the database schema (migrations V1&rarr;V2&rarr;V3); Hibernate only validates
 * it. The single active responsibility of this class is a fail-fast startup guard that refuses
 * to boot when {@code spring.jpa.hibernate.ddl-auto} is set to a schema-mutating value, keeping
 * the migrated schema authoritative for the byte-parity gates.</p>
 *
 * <p>JPA auditing is intentionally omitted: the migrated entities map fixed-width COBOL copybook
 * records that carry their own explicit date/user fields (e.g. {@code ACCT-OPEN-DATE}) to plain
 * columns, so no Spring Data auditing annotations are present (see {@code docs/decision-log.md}).</p>
 */
@Configuration
public class JpaConfig {

    private static final Logger log = LoggerFactory.getLogger(JpaConfig.class);

    /**
     * Hibernate {@code ddl-auto} values that would create, alter, or drop schema objects and
     * therefore fight the Flyway-managed schema. Only {@code validate} and {@code none} (and an
     * unset/empty value, which defaults to {@code validate}) are permitted.
     */
    private static final Set<String> FORBIDDEN_DDL_AUTO = Set.of("create", "create-drop", "update", "drop");

    private final Environment environment;

    /**
     * @param environment the Spring {@link Environment}, injected by constructor, used to resolve
     *                    {@code spring.jpa.hibernate.ddl-auto} from the active {@code application*.yml}
     *                    profile
     */
    public JpaConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * Fail-fast guard asserting that Hibernate never mutates the Flyway-owned schema.
     *
     * <p>Reads {@code spring.jpa.hibernate.ddl-auto} (defaulting to {@code validate} when unset, to
     * match {@code application.yml}) and refuses to start when it resolves to a mutating value
     * ({@code create}, {@code create-drop}, {@code update}, {@code drop}). All legitimate profiles —
     * including Testcontainers integration tests, which also run Flyway — use {@code validate}, so
     * this guard blocks only dangerous misconfiguration and never a valid profile.</p>
     *
     * @throws IllegalStateException if {@code ddl-auto} resolves to a schema-mutating value
     */
    @PostConstruct
    void assertNonMutatingDdlAuto() {
        String ddlAuto = environment.getProperty("spring.jpa.hibernate.ddl-auto", "validate");
        String normalized = ddlAuto == null ? "" : ddlAuto.trim().toLowerCase(Locale.ROOT);
        if (FORBIDDEN_DDL_AUTO.contains(normalized)) {
            throw new IllegalStateException(
                    "Refusing to start: spring.jpa.hibernate.ddl-auto='" + ddlAuto
                            + "' would mutate the Flyway-owned schema. Allowed values: 'validate' or 'none'.");
        }
        log.info("JPA schema management OK: ddl-auto='{}' (Flyway owns DDL).",
                normalized.isEmpty() ? "validate" : normalized);
    }
}
