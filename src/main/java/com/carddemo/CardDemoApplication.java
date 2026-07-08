package com.carddemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entry point for the migrated AWS CardDemo credit-card management system
 * (COBOL/CICS/VSAM/JCL &rarr; Java 25 LTS + Spring Boot 3.x).
 *
 * <p>This class is deliberately located in the root package {@code com.carddemo} so that the
 * {@link SpringBootApplication @SpringBootApplication} default component scan and auto-configuration
 * cover every layer of the {@code com.carddemo.<layer>} tree &mdash; {@code config}, {@code entity},
 * {@code repository}, {@code service}, {@code controller}, {@code dto}, {@code batch},
 * {@code exception}, and {@code observability}. It simultaneously bootstraps the web tier (REST
 * controllers that replace the legacy BMS screens), the JPA tier (Spring Data over PostgreSQL 16),
 * and the Spring Batch tier (the five-stage pipeline that replaces the JCL job stream).</p>
 *
 * <p>{@link ConfigurationPropertiesScan @ConfigurationPropertiesScan} enables discovery and binding
 * of the {@code @ConfigurationProperties} classes for the {@code carddemo.*} namespaces (for example
 * {@code carddemo.batch.*}, {@code carddemo.security.jwt.*}, and {@code carddemo.aws.*}), which are
 * authored in the {@code config} and {@code batch} layers.</p>
 *
 * <p>Batch jobs are <strong>not</strong> launched at startup: {@code spring.batch.job.enabled=false}
 * (declared in {@code application.yml}) defers execution to on-demand invocation and the SQS FIFO
 * report trigger. All ports, datasource URLs, AWS endpoints, active profiles, and secrets are
 * externalized to {@code application*.yml}; none are hardcoded here.</p>
 *
 * <p>Design rationale and the COBOL-paragraph&nbsp;&rarr;&nbsp;Java mappings live in
 * {@code docs/decision-log.md} and {@code docs/traceability-matrix.md} respectively, per the
 * Explainability rule &mdash; not in verbose source comments. The frozen COBOL source under
 * {@code app/} is referenced read-only by commit SHA {@code 27d6c6f} and is never copied into this
 * target.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CardDemoApplication {

    /**
     * Boots the CardDemo Spring application context.
     *
     * <p>Delegates to {@link SpringApplication#run(Class, String...)} with this class as the primary
     * source. Web server port, active profiles, datasource, Flyway migrations, AWS endpoints, and
     * observability wiring are all resolved from the externalized {@code application*.yml}
     * configuration and profile-specific overrides &mdash; nothing is configured programmatically
     * here.</p>
     *
     * @param args command-line arguments forwarded verbatim to {@link SpringApplication}
     */
    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
